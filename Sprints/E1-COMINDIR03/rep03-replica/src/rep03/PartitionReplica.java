package rep03;

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

/** Uma instância por partição, com ficheiro, subscrição e recuperação próprios. */
public class PartitionReplica {
    // Configuração fixa N = 3 exigida pela COMINDIR03; não é o número de partições locais.
    private static final int PARTITION_COUNT = 3;
    private final int partition;
    private final RecordFile file;
    private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();
    private final double lossProbability;
    private final Long discardSequence;

    // Depois do arranque, só a thread de subscrição acede a este estado.
    private long last;
    private String leaderHost;
    private int leaderPort;
    private boolean targetedDiscardDone;

    public PartitionReplica(int id, int partition, double lossProbability, Long discardSequence) throws IOException {
        this.partition = partition;
        file = new RecordFile("replica-" + id + "-p" + partition + ".txt", PARTITION_COUNT);
        last = file.lastSeq();
        this.lossProbability = lossProbability;
        this.discardSequence = discardSequence;
    }

    public void onMessage(String message) {
        SensorRecord record;
        String host;
        int port;
        try {
            String[] parts = Wire.regParts(message);
            host = parts[0];
            port = Integer.parseInt(parts[1]);
            if (host.isBlank() || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Endereço ou porto inválido");
            }
            record = SensorRecord.fromLine(parts[2], PARTITION_COUNT);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + message + " | p" + partition);
            return;
        }

        leaderHost = host;
        leaderPort = port;
        // A simulação só afeta PubSub; os registos lidos por TCP não passam aqui.
        if (discardSequence != null && record.getSeq() == discardSequence && !targetedDiscardDone) {
            targetedDiscardDone = true;
            System.out.println("DESCARTADO (simulação): " + record.getSeq() + " | p" + partition);
            return;
        }
        if (Math.random() < lossProbability) {
            System.out.println("DESCARTADO (simulação): " + record.getSeq() + " | p" + partition);
            return;
        }

        try {
            apply(record);
            // Uma tentativa por mensagem: uma falha não provoca um ciclo de novas ligações.
            if (pending.size() > Wire.LIMITE_RETIDOS) {
                recover();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // A mesma regra é usada para registos recebidos por PubSub e por TCP.
    private void apply(SensorRecord record) throws IOException {
        long seq = record.getSeq();
        if (seq <= last || pending.containsKey(seq)) {
            System.out.println("DUPLICADO: " + seq + " | p" + partition);
        } else if (seq == last + 1) {
            write(record);
            while (pending.containsKey(last + 1)) {
                SensorRecord next = pending.get(last + 1);
                write(next);
                pending.remove(last);
            }
        } else {
            pending.put(seq, record);
            System.out.println("EM ESPERA: " + seq + " (falta " + (last + 1)
                    + "; retidos=" + pending.size() + ") | p" + partition);
        }
    }

    private void write(SensorRecord record) throws IOException {
        file.append(record);
        last = record.getSeq();
        // Valor observado após a escrita, para medir o instante de convergência.
        System.out.println("Aplicado: " + record.toLine() + " | escrita(ms)=" + System.currentTimeMillis()
                + " | p" + partition);
    }

    private void recover() {
        long from = last + 1;
        long to = pending.firstKey() - 1;
        System.out.println("RECUPERAÇÃO: " + from + ".." + to + " pedida a "
                + leaderHost + ":" + leaderPort + " | p" + partition);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(leaderHost, leaderPort), Wire.T_TCP_MS);
            socket.setSoTimeout(Wire.T_TCP_MS);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                out.println("PEDIDO " + from + " " + to);
                if (out.checkError()) {
                    throw new IOException("Não foi possível enviar o pedido");
                }
                while (true) {
                    String line = in.readLine();
                    if (line == null) {
                        throw new EOFException("Resposta interrompida antes de FIM");
                    }
                    if (line.equals("FIM")) {
                        break;
                    }
                    if (line.startsWith("ERRO ")) {
                        throw new IOException("Resposta do líder: " + line);
                    }
                    SensorRecord recovered;
                    try {
                        recovered = SensorRecord.fromLine(line, PARTITION_COUNT);
                        if (recovered.getSeq() < from || recovered.getSeq() > to) {
                            throw new IllegalArgumentException("Sequência fora do intervalo pedido");
                        }
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Registo de recuperação inválido: " + line, e);
                    }
                    apply(recovered);
                    System.out.println("RECUPERADO: " + recovered.getSeq() + " | p" + partition);
                }
            }
            System.out.println("Recuperação terminada: último=" + last + "; retidos=" + pending.size()
                    + " | p" + partition);
        } catch (IOException e) {
            // Conserva as escritas e os retidos. A próxima mensagem volta a testar o limite.
            System.out.println("RECUPERAÇÃO FALHOU: " + e.getMessage()
                    + " (último=" + last + "; retidos=" + pending.size() + ") | p" + partition);
        }
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Uso: PartitionReplica <id> <partições> [portoApi]");
            return;
        }
        try {
            int id = Integer.parseInt(args[0]);
            int apiPort = args.length > 2 ? Integer.parseInt(args[2]) : 5001;
            if (id < 1 || apiPort < 1 || apiPort > 65535) {
                throw new IllegalArgumentException("ID deve ser positivo e portoApi deve estar entre 1 e 65535");
            }
            Set<Integer> partitions = new LinkedHashSet<>();
            for (String field : args[1].split(",", -1)) {
                int partition = Integer.parseInt(field.trim());
                if (partition < 0 || partition >= PARTITION_COUNT || !partitions.add(partition)) {
                    throw new IllegalArgumentException("As partições devem estar entre 0 e 2 e sem repetições");
                }
            }
            double loss = Double.parseDouble(System.getProperty("perda", "0"));
            if (!Double.isFinite(loss) || loss < 0 || loss > 1) {
                throw new IllegalArgumentException("perda deve estar entre 0 e 1");
            }
            String discardProperty = System.getProperty("descartar");
            Long discard = discardProperty == null ? null : Long.valueOf(discardProperty);
            if (discard != null && discard < 1) {
                throw new IllegalArgumentException("descartar deve ser uma sequência positiva");
            }

            String api = "http://127.0.0.1:" + apiPort + "/api/v0";
            // Inicializa todos os ficheiros antes de arrancar qualquer subscrição.
            List<PartitionReplica> states = new ArrayList<>();
            for (int partition : partitions) {
                states.add(new PartitionReplica(id, partition, loss, discard));
            }
            for (PartitionReplica replica : states) {
                // Cada thread é a única dona do estado mutável da sua instância.
                int partition = replica.partition;
                IpfsPubSub pubSub = new IpfsPubSub(api);
                Thread subscription = new Thread(() -> {
                    try {
                        System.out.println("A iniciar subscrição de " + Wire.topicData(partition)
                                + " via " + api + " (último=" + replica.last + ")");
                        pubSub.subscribe(Wire.topicData(partition), replica::onMessage);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("Subscrição interrompida | p" + partition);
                    } catch (IOException | UncheckedIOException e) {
                        System.out.println("Subscrição terminada por falha: " + e.getMessage()
                                + " | p" + partition);
                    }
                }, "replica-" + id + "-p" + partition);
                subscription.start();
            }
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("Não foi possível iniciar a réplica: " + e.getMessage());
        }
    }
}
