package raft04;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Um nó tem três papéis; não existe um processo líder especial. */
public final class PartitionNode {
    private final int partition, self, n, port;
    private final String host;
    private final Partitioner partitioner;
    private final IpfsPubSub pubSub;
    private final RecordFile file;
    private final MonitorLog log;
    private final RaftState raft;
    private final ScheduledExecutorService elections = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService batches = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> batchTask;
    private long seq, lamport, hb;
    private VectorClock vector;
    private List<SensorRecord> pending = new ArrayList<>();

    public PartitionNode(int k, int i, int n, String host, String api) throws IOException {
        if (n < 1 || k < 0 || k >= n || i < 1 || i > Wire.GRUPO || Wire.nodePort(k, i) > 65535)
            throw new IllegalArgumentException("Esperado 0 <= k < N e 1 <= i <= 3");
        partition = k; self = i; this.n = n; this.host = host;
        port = Wire.nodePort(k, i); partitioner = new Partitioner(n); pubSub = new IpfsPubSub(api);
        file = new RecordFile("no-p" + k + "-n" + i + ".txt", n);
        log = new MonitorLog(Path.of("eventos-p" + k + "-n" + i + ".txt"));
        raft = new RaftState(k, i, Wire.GRUPO, Wire.ELEICAO_MIN_MS, Wire.ELEICAO_MAX_MS,
                Path.of("estado-p" + k + "-n" + i + ".txt"), log::event);
        restoreClocks();
        // TERMO no arranque permite medir o tempo desde o primeiro nó, mesmo após reinício.
        log.event("TERMO;p" + k + ";arranque;termo=" + raft.term() + ";votouEm=disco;no=n" + i);
    }
    private void restoreClocks() {
        SensorRecord last = file.last();
        seq = file.lastSeq(); lamport = last == null ? 0 : last.getLamport();
        vector = last == null ? new VectorClock(n) : last.getVector();
    }
    // Ordem única: monitor do nó -> RaftState -> RecordFile/log; nunca a inversa.
    private void cancelIfFollower() {
        if (raft.role() != RaftState.Role.LIDER && batchTask != null) {
            batchTask.cancel(false); batchTask = null; pending.clear();
        }
    }
    private void publish(String topic, String message, boolean replication) {
        if (replication && Wire.isolated(partition, self)) return;
        try { pubSub.publish(topic, message); }
        catch (IOException e) { log.event("ERRO;p" + partition + ";publicacao=" + e.getMessage()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void tick() {
        VoteRequest request = null;
        synchronized (this) {
            long term = raft.tick();
            if (term >= 0) request = new VoteRequest(term, self, file.lastSeq(), file.lastTerm());
        }
        if (request != null) publish(Wire.topicElection(partition), request.encode(), true);
    }
    private void onElection(String text) {
        if (Wire.isolated(partition, self)) return;
        try {
            VoteReply reply = null;
            synchronized (this) {
                if (Wire.type(text).equals("PEDIDO_VOTO")) {
                    VoteRequest r = VoteRequest.decode(text);
                    if (r.candidate == self) return;
                    RaftState.Vote vote = raft.onVoteRequest(r.term, r.candidate, r.lastSeq, r.lastTerm,
                            file.lastSeq(), file.lastTerm());
                    reply = new VoteReply(vote.term, self, r.candidate, vote.granted);
                } else {
                    VoteReply r = VoteReply.decode(text);
                    if (r.voter == self) return;
                    // Mesmo um voto dirigido a outro candidato transporta um termo observável.
                    boolean elected = raft.onVote(r.term, r.voter, r.candidate, r.granted);
                    if (elected) {
                        restoreClocks(); hb = 0; pending.clear();
                        if (batchTask != null) batchTask.cancel(false);
                        batchTask = batches.scheduleAtFixedRate(this::enviarLote, 0,
                                Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
                    }
                }
                cancelIfFollower();
            }
            if (reply != null) publish(Wire.topicElection(partition), reply.encode(), true);
        } catch (IllegalArgumentException e) { log.event("ERRO;p" + partition + ";eleicao=" + e.getMessage()); }
    }
    private void onInput(String text) {
        try {
            if (!Wire.type(text).equals("IN")) throw new IllegalArgumentException("Tipo IN esperado");
            String[] f = Wire.body(text).split(";", -1);
            if (f.length != 4 || f[0].isBlank()) throw new IllegalArgumentException("IN mal formado");
            double temp = Double.parseDouble(f[1]);
            long msgL = Long.parseLong(f[2]);
            VectorClock msgV = VectorClock.parse(f[3], n);
            if (!Double.isFinite(temp) || msgL < 0 || partitioner.of(f[0]) != partition)
                throw new IllegalArgumentException("IN inválido ou partição errada");
            synchronized (this) {
                long term = raft.termIfLeader();
                if (term < 0) { log.event("IGNORADO;p" + partition + ";IN;papel=" + raft.role()); return; }
                VectorClock nextV = vector.copy();
                nextV.merge(msgV); nextV.tick(partition);
                long nextL = Math.max(lamport, msgL) + 1;
                // Um alarme pode trazer conhecimento do ramo antigo. Não se altera esse conhecimento
                // artificialmente: CausalCheck evidenciará eventuais limites após truncagens.
                SensorRecord r = SensorRecord.now(seq + 1, term, f[0], temp, nextL, nextV);
                file.append(r); seq++; lamport = nextL; vector = nextV; pending.add(r);
                log.event("RECEBIDO;p" + partition + ";IN;termo=" + term + ";seq=" + seq);
            }
        } catch (IllegalArgumentException e) { log.event("ERRO;p" + partition + ";IN=" + e.getMessage()); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private void enviarLote() {
        Batch batch;
        synchronized (this) {
            long term = raft.termIfLeader();
            if (term < 0) { cancelIfFollower(); return; }
            batch = new Batch(term, self, host, port, ++hb, file.lastSeq(), file.lastTerm(), pending);
            pending = new ArrayList<>();
        }
        if (Wire.isolated(partition, self)) return;
        String message = batch.encode();
        try {
            pubSub.publish(Wire.topicData(partition), message);
            String line = batch.term + ";" + batch.hb + ";" + System.currentTimeMillis() + ";"
                    + batch.lastSeq + ";" + batch.lastTerm + ";" + batch.records.size() + ";"
                    + message.getBytes(StandardCharsets.UTF_8).length + "\n";
            Files.writeString(Path.of("hb-p" + partition + "-n" + self + ".txt"), line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { log.event("ERRO;p" + partition + ";LOTE=" + e.getMessage()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void onBatch(String text) {
        if (Wire.isolated(partition, self)) return;
        try {
            Batch batch = Batch.decode(text, n);
            if (batch.leader == self) return;
            synchronized (this) {
                if (!raft.onBatch(batch.term, batch.leader)) {
                    log.event("IGNORADO;p" + partition + ";LOTE;termo=" + batch.term); return;
                }
                cancelIfFollower();
                log.received(partition, batch);
                boolean divergent = file.lastSeq() > batch.lastSeq;
                SensorRecord atEnd = file.at(batch.lastSeq);
                if (atEnd != null && atEnd.getTerm() != batch.lastTerm) divergent = true;
                for (SensorRecord r : batch.records) {
                    SensorRecord old = file.at(r.getSeq());
                    if (old != null && old.getTerm() != r.getTerm()) divergent = true;
                }
                if (divergent) reconcile(batch);
                else {
                    for (SensorRecord r : batch.records) {
                        if (r.getSeq() <= file.lastSeq()) continue;
                        if (r.getSeq() > file.lastSeq() + 1) recover(batch, file.lastSeq() + 1, r.getSeq() - 1);
                        file.append(r);
                    }
                    if (file.lastSeq() < batch.lastSeq) recover(batch, file.lastSeq() + 1, batch.lastSeq);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            log.event("ERRO;p" + partition + ";recuperacao=" + e.getMessage());
        }
    }
    private List<SensorRecord> fetch(Batch leader, long from, long to) throws IOException {
        if (Wire.isolated(partition, self)) throw new IOException("Cliente isolado");
        List<SensorRecord> result = new ArrayList<>();
        if (from > to) return result;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(leader.address, leader.port), Wire.T_TCP_MS);
            socket.setSoTimeout(Wire.T_TCP_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println("PEDIDO " + from + " " + to);
            if (out.checkError()) throw new IOException("Envio PEDIDO falhou");
            long expected = from;
            while (true) {
                String line = in.readLine();
                if (line == null) throw new EOFException("Falta FIM");
                if (Wire.isolated(partition, self)) throw new IOException("Cliente isolado");
                if (line.equals("FIM")) break;
                if (line.startsWith("ERRO")) throw new IOException(line);
                SensorRecord r = SensorRecord.fromLine(line, n);
                if (r.getSeq() != expected++ || r.getSeq() > to) throw new IOException("Resposta TCP fora de ordem");
                result.add(r);
            }
            if (expected != to + 1) throw new IOException("Resposta TCP incompleta");
        }
        return result;
    }
    private void recover(Batch b, long from, long to) throws IOException {
        log.event("RECUPERACAO;p" + partition + ";de=" + from + ";ate=" + to);
        for (SensorRecord r : fetch(b, from, to)) file.append(r);
    }
    private void reconcile(Batch b) throws IOException {
        long start = file.lastSeq();
        List<SensorRecord> remote;
        // Recuar blocos de termo, não números arbitrários de linhas.
        while (true) {
            long term = file.at(start).getTerm();
            while (start > 1 && file.at(start - 1).getTerm() == term) start--;
            remote = fetch(b, start, b.lastSeq);
            if (start == 1 || (!remote.isEmpty() && remote.get(0).getTerm() == file.at(start).getTerm())) break;
            start--;
        }
        long firstDifference = start;
        int index = 0;
        while (index < remote.size()) {
            SensorRecord old = file.at(remote.get(index).getSeq());
            if (old == null || old.getTerm() != remote.get(index).getTerm()) break;
            firstDifference++; index++;
        }
        List<SensorRecord> discarded = file.range(firstDifference, file.lastSeq());
        // Arquivo de auditoria permite ao RaftCheck comparar o conteúdo descartado com o final.
        for (SensorRecord r : discarded) Files.writeString(Path.of("truncados-p" + partition + "-n" + self + ".txt"),
                System.currentTimeMillis() + ";" + r.toLine() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        List<SensorRecord> replacement = new ArrayList<>(file.range(1, firstDifference - 1));
        replacement.addAll(remote.subList(index, remote.size()));
        file.replace(replacement);
        Set<Long> terms = new TreeSet<>();
        for (SensorRecord r : discarded) terms.add(r.getTerm());
        log.event("TRUNCADO;p" + partition + ";de=" + firstDifference + ";descartados=" + discarded.size() + ";termos=" + terms);
    }
    public void start() throws IOException {
        // Abrir primeiro o porto: não participar numa eleição se o servidor não arrancou.
        try (ServerSocket server = new ServerSocket(port)) {
            pubSub.subscribeAsync(Wire.topicIn(partition), this::onInput, false);
            pubSub.subscribeAsync(Wire.topicData(partition), this::onBatch, false);
            pubSub.subscribeAsync(Wire.topicElection(partition), this::onElection, false);
            elections.scheduleAtFixedRate(this::tick, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
            while (true) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(Wire.T_TCP_MS);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    if (Wire.isolated(partition, self)) { out.println("ERRO servidor isolado"); continue; }
                    String request = in.readLine();
                    String[] f = request == null ? new String[0] : request.split(" ");
                    if (f.length != 3 || !f[0].equals("PEDIDO")) { out.println("ERRO pedido inválido"); continue; }
                    long from = Long.parseLong(f[1]), to = Long.parseLong(f[2]);
                    if (from < 1 || to < from) { out.println("ERRO intervalo inválido"); continue; }
                    // Só adquire RecordFile; não espera pelo monitor do nó. Evita deadlock
                    // entre dois nós que estejam simultaneamente a recuperar por TCP.
                    for (SensorRecord r : file.range(from, to)) {
                        if (Wire.isolated(partition, self)) throw new IOException("Servidor isolado");
                        out.println(r.toLine());
                    }
                    out.println("FIM");
                } catch (IOException | IllegalArgumentException e) { log.event("ERRO;p" + partition + ";TCP=" + e.getMessage()); }
            }
        } finally { elections.shutdownNow(); batches.shutdownNow(); }
    }
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("PartitionNode <k> <i> [N]");
        new PartitionNode(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                args.length == 3 ? Integer.parseInt(args[2]) : 3,
                System.getProperty("raft04.host", "127.0.0.1"),
                System.getProperty("raft04.api", "http://127.0.0.1:5001/api/v0")).start();
    }
}
