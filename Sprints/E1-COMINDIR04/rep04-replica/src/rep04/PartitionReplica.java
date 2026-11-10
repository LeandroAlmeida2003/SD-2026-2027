package rep04;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PartitionReplica {
    private static final int PARTITION_COUNT = 3;
    private final int partition;
    private final RecordFile file;
    private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();
    private final MonitorLog log;
    private final HeartbeatMonitor monitor;
    private final double loss;
    private final Long discard;
    private boolean discarded;
    private long last;

    public PartitionReplica(int id, int partition, MonitorLog log, double loss, Long discard) throws IOException {
        this.partition = partition;
        this.file = new RecordFile("replica-" + id + "-p" + partition + ".txt", PARTITION_COUNT);
        this.last = file.lastSeq();
        this.log = log;
        this.loss = loss;
        this.discard = discard;
        monitor = new HeartbeatMonitor(partition, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, log::event);
    }

    public void onMessage(String message) {
        Batch batch;
        try {
            batch = Batch.decode(message, PARTITION_COUNT);
        } catch (IllegalArgumentException error) {
            System.err.println("REJEITADO p" + partition + ": " + error.getMessage());
            return;
        }
        boolean targeted = !discarded && discard != null
                && batch.records.stream().anyMatch(record -> record.getSeq() == discard);
        if (targeted) discarded = true;
        if (targeted || Math.random() < loss) {
            System.out.println("DESCARTADO (simulação): hb=" + batch.hb + " | p" + partition);
            return;
        }
        monitor.onHeartbeat(batch.inc, batch.hb);
        log.received(partition, batch);
        try {
            for (SensorRecord record : batch.records) apply(record);
            if (last < batch.lastSeq) recover(batch.address, batch.port, batch.lastSeq);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void apply(SensorRecord record) throws IOException {
        long sequence = record.getSeq();
        if (sequence <= last || pending.containsKey(sequence)) return;
        pending.put(sequence, record);
        while (pending.containsKey(last + 1)) {
            SensorRecord next = pending.get(last + 1);
            file.append(next);
            last = next.getSeq();
            pending.remove(last);
            System.out.println("Aplicado: " + next.toLine() + " | p" + partition
                    + " | escrita(ms)=" + System.currentTimeMillis());
        }
    }

    private void recover(String address, int port, long to) {
        long from = last + 1;
        log.event("RECUPERACAO;p" + partition + ";de=" + from + ";ate=" + to);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), Wire.T_TCP_MS);
            socket.setSoTimeout(Wire.T_TCP_MS);
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter output = new PrintWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                output.println("PEDIDO " + from + " " + to);
                if (output.checkError()) throw new IOException("Falha ao enviar PEDIDO");
                while (true) {
                    String line = input.readLine();
                    if (line == null) throw new EOFException("Resposta interrompida antes de FIM");
                    if (line.equals("FIM")) break;
                    if (line.startsWith("ERRO ")) throw new IOException(line);
                    SensorRecord record = SensorRecord.fromLine(line, PARTITION_COUNT);
                    if (record.getSeq() < from || record.getSeq() > to) {
                        throw new IOException("Sequência fora do intervalo pedido");
                    }
                    apply(record);
                }
                if (last < to) throw new IOException("Recuperação incompleta: último=" + last + ", esperado=" + to);
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("RECUPERAÇÃO FALHOU p" + partition + ": " + error.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Uso: PartitionReplica <id> <partições> [portoApi]");
            return;
        }
        try {
            int id = Integer.parseInt(args[0]);
            int apiPort = args.length == 3 ? Integer.parseInt(args[2]) : 5001;
            if (id < 1 || apiPort < 1 || apiPort > 65535) {
                throw new IllegalArgumentException("ID ou portoApi inválido");
            }
            Set<Integer> partitions = new LinkedHashSet<>();
            for (String field : args[1].split(",", -1)) {
                int partition = Integer.parseInt(field.trim());
                if (partition < 0 || partition >= PARTITION_COUNT || !partitions.add(partition)) {
                    throw new IllegalArgumentException("Partições entre 0 e 2, sem repetições");
                }
            }
            double loss = Double.parseDouble(System.getProperty("perda", "0"));
            Long discard = System.getProperty("descartar") == null ? null
                    : Long.valueOf(System.getProperty("descartar"));
            if (!Double.isFinite(loss) || loss < 0 || loss > 1 || (discard != null && discard < 1)) {
                throw new IllegalArgumentException("perda deve estar em [0,1] e descartar deve ser positivo");
            }
            MonitorLog log = new MonitorLog("replica" + id);
            List<PartitionReplica> replicas = new ArrayList<>();
            for (int partition : partitions) {
                replicas.add(new PartitionReplica(id, partition, log, loss, discard));
            }
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "verificacao-replica" + id);
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleAtFixedRate(() -> replicas.forEach(replica -> replica.monitor.check()),
                    Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
            IpfsPubSub pubSub = new IpfsPubSub("http://127.0.0.1:" + apiPort + "/api/v0");
            for (PartitionReplica replica : replicas) {
                pubSub.subscribeAsync(Wire.topicData(replica.partition), replica::onMessage, false);
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("Falha na réplica: " + error.getMessage());
        }
    }
}
