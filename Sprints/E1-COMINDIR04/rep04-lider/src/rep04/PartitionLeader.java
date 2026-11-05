package rep04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Líder de uma partição, com validação da chave e recuperação por TCP. */
public class PartitionLeader {
    private final int partition;
    private final Partitioner partitioner;
    private final String host;
    private final int port;
    private final IpfsPubSub pubSub;
    private final RecordFile file;
    private long lastWritten;
    private long lamport;
    private final VectorClock vector;
    private final long inc = System.currentTimeMillis();
    private long hb;
    private List<SensorRecord> pendentes = new ArrayList<>();

    public PartitionLeader(int partition, int partitionCount, String host, String api) throws IOException {
        this.partition = partition;
        partitioner = new Partitioner(partitionCount);
        this.host = host;
        port = Wire.PORTO_BASE + partition;
        pubSub = new IpfsPubSub(api);
        file = new RecordFile("lider-p" + partition + ".txt", partitionCount);
        SensorRecord last = file.last();
        lastWritten = last == null ? 0 : last.getSeq();
        lamport = last == null ? 0 : last.getLamport();
        vector = last == null ? new VectorClock(partitionCount) : last.getVector();
        Files.writeString(Path.of("lider-p" + partition + ".txt"), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // Numeração e gravação usam o mesmo monitor: este objeto PartitionLeader.
    private synchronized SensorRecord recordReading(String sensor, double temperature,
                                                    long lamportMsg, VectorClock vectorMsg) throws IOException {
        lamport = Math.max(lamport, lamportMsg) + 1;
        vector.merge(vectorMsg);
        vector.tick(partition);
        long next = lastWritten + 1;
        if (vector.get(partition) != next) {
            throw new IllegalStateException("V[" + partition + "] != seq: " + vector + " / " + next);
        }
        SensorRecord record = SensorRecord.now(next, sensor, temperature, lamport, vector);
        file.append(record);
        // Só confirma o avanço do contador depois de append terminar sem erro.
        lastWritten = next;
        pendentes.add(record);
        System.out.println("EVENTO: seq=" + next + " L=" + lamport + " V=" + vector);
        System.out.println("Gravado: " + record.toLine() + " | escrita(ms)=" + System.currentTimeMillis());
        return record;
    }

    // Mesmo monitor de recordReading: a proteção termina quando a lista é devolvida.
    private synchronized List<SensorRecord> recoveryRecords(long from, long to) throws IOException {
        long availableTo = Math.min(to, lastWritten);
        return file.range(from, availableTo);
    }

    private void onInput(String message) {
        String sensor;
        double temperature;
        long lamportMsg;
        VectorClock vectorMsg;
        try {
            if (!Wire.type(message).equals("IN")) {
                throw new IllegalArgumentException("Tipo inesperado");
            }
            String[] fields = Wire.body(message).split(";", -1);
            if (fields.length != 4 || fields[0].isBlank()) {
                throw new IllegalArgumentException("Leitura mal formada");
            }
            sensor = fields[0];
            temperature = Double.parseDouble(fields[1]);
            lamportMsg = Long.parseLong(fields[2]);
            vectorMsg = VectorClock.parse(fields[3], partitioner.count());
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + message);
            return;
        }

        int expectedPartition = partitioner.of(sensor);
        if (expectedPartition != partition) {
            System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + expectedPartition);
            return;
        }

        try {
            recordReading(sensor, temperature, lamportMsg, vectorMsg);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void enviarLote() {
        Batch batch;
        synchronized (this) {
            batch = new Batch(host, port, inc, ++hb, lastWritten, pendentes);
            pendentes = new ArrayList<>();
        }
        String message = batch.encode();
        try {
            pubSub.publish(Wire.topicData(partition), message);
            long instant = System.currentTimeMillis();
            String line = inc + ";" + batch.hb + ";" + instant + ";" + batch.lastSeq
                    + ";" + batch.records.size() + ";" + message.getBytes(StandardCharsets.UTF_8).length;
            Files.writeString(Path.of("hb-p" + partition + ".txt"), line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("HEARTBEAT: hb=" + batch.hb + " n=" + batch.records.size()
                    + " ultimoSeq=" + batch.lastSeq);
        } catch (IOException error) {
            System.err.println("Publicação/registo do lote falhou: hb=" + batch.hb + " | " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("Publicação do lote interrompida: hb=" + batch.hb);
        }
    }

    private void startInput() {
        Thread input = new Thread(() -> {
            try {
                System.out.println("A iniciar subscrição de " + Wire.topicIn(partition));
                pubSub.subscribe(Wire.topicIn(partition), this::onInput);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Entrada interrompida.");
            } catch (IOException | UncheckedIOException | IllegalStateException e) {
                System.out.println("Entrada terminada por falha: " + e.getMessage());
            }
        }, "entrada-p" + partition);
        input.start();
    }

    private void serveRecovery() throws IOException {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (ServerSocket server = new ServerSocket(port)) {
            startInput();
            scheduler.scheduleAtFixedRate(this::enviarLote, 0, Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
            System.out.println("Recuperação TCP à escuta no porto " + port);
            while (true) {
                try (Socket socket = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    socket.setSoTimeout(Wire.T_TCP_MS);
                    String request = in.readLine();
                    long from;
                    long to;
                    try {
                        if (request == null) {
                            throw new IllegalArgumentException("Pedido ausente");
                        }
                        String[] fields = request.trim().split("\\s+");
                        if (fields.length != 3 || !fields[0].equals("PEDIDO")) {
                            throw new IllegalArgumentException("Formato esperado: PEDIDO <de> <até>");
                        }
                        from = Long.parseLong(fields[1]);
                        to = Long.parseLong(fields[2]);
                        if (from > to) {
                            throw new IllegalArgumentException("de não pode ser maior que até");
                        }
                    } catch (IllegalArgumentException e) {
                        out.println("ERRO " + e.getMessage());
                        continue;
                    }

                    List<SensorRecord> records = recoveryRecords(from, to);
                    // A consulta sincronizada terminou; o envio não mantém o monitor ocupado.
                    for (SensorRecord record : records) {
                        out.println(record.toLine());
                    }
                    out.println("FIM");
                    if (out.checkError()) {
                        throw new IOException("Falha ao enviar a resposta TCP");
                    }
                    System.out.println("RECUPERAÇÃO: " + from + ".." + to + " para "
                            + socket.getRemoteSocketAddress() + " (" + records.size() + " registos)");
                } catch (IOException e) {
                    System.out.println("Recuperação interrompida: " + e.getMessage());
                }
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 4) {
            System.out.println("Uso: PartitionLeader <k> <N> [endereço] [api]");
            return;
        }

        int partition;
        int partitionCount;
        try {
            partition = Integer.parseInt(args[0]);
            partitionCount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("k e N devem ser números inteiros.");
            return;
        }
        if (partitionCount < 1 || partition < 0 || partition >= partitionCount) {
            System.out.println("É necessário N >= 1 e 0 <= k < N.");
            return;
        }

        String host = args.length > 2 ? args[2] : "127.0.0.1";
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";
        System.out.println("Configuração: partição " + partition + " de " + partitionCount);
        System.out.println("Endereço a anunciar: " + host);
        System.out.println("API IPFS: " + api);
        try {
            PartitionLeader leader = new PartitionLeader(partition, partitionCount, host, api);
            System.out.println("Última sequência gravada: " + leader.lastWritten);
            leader.serveRecovery();
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("Falha no líder: " + e.getMessage());
            return;
        }
    }
}
