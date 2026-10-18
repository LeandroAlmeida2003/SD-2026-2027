package rep01;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PubSubReplica {
    static final String TOPIC = "rep01-temperaturas";

    private final RecordFile file;
    private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();
    private long last;
    private final String id;
    // Medições desta execução; iniciar processos novos para cada rajada medida.
    private long received;
    private long written;
    private long duplicates;
    private long firstReceive;
    private long lastReceive;
    private boolean summaryPending;

    public PubSubReplica(String id) throws IOException {
        this.id = id;
        file = new RecordFile("replica-" + id + ".txt");
        last = file.lastSeq();
    }

    // Executado para cada mensagem já descodificada pela IpfsPubSub.
    // Mesmo monitor do resumo: impede ler o estado a meio de uma atualização.
    // Pode esperar pelo monitor; não fornece garantias de rede.
    public synchronized void onMessage(String line) {
        long receptionTime = System.currentTimeMillis();
        SensorRecord r;
        try {
            r = SensorRecord.fromLine(line);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + line);
            return; // rejeita só esta mensagem; a subscrição continua
        }

        if (received == 0) firstReceive = receptionTime;
        lastReceive = receptionTime;
        received++; // inclui duplicados; rejeitados não entram neste contador
        summaryPending = true;

        try {
            // Já escrito OU já retido: duplicado, sem nova escrita.
            if (r.getSeq() <= last || pending.containsKey(r.getSeq())) {
                duplicates++;
                System.out.println("DUPLICADO: " + r.getSeq());
            } else if (r.getSeq() == last + 1) { // próximo esperado, sem saltos
                file.append(r);
                written++; // uma escrita concluída, mesmo quando veio de pending
                last = r.getSeq();
                System.out.println("Aplicado: " + r.toLine());

                while (pending.containsKey(last + 1)) {
                    SensorRecord next = pending.get(last + 1);
                    file.append(next);
                    written++; // uma escrita concluída, mesmo quando veio de pending
                    last = next.getSeq();
                    pending.remove(last); // retira só depois de escrever com sucesso
                    System.out.println("Aplicado: " + next.toLine());
                }
            } else {
                // Apenas em memória: perde-se no reinício e pode crescer sem limite.
                pending.put(r.getSeq(), r);
                System.out.println("EM ESPERA: " + r.getSeq()
                        + " (falta o registo " + (last + 1) + ")");
            }
        } catch (IOException e) {
            // Consumer<String> não permite lançar IOException diretamente.
            // Erro de disco termina o processamento; não é uma mensagem inválida.
            throw new UncheckedIOException(e);
        }
    }

    // O silêncio permite mostrar um estado; não garante que todos os registos chegaram.
    // Contadores por execução: iniciar processos novos para medições independentes.
    public synchronized void showSummaryIfIdle() {
        if (!summaryPending || System.currentTimeMillis() - lastReceive < 2000) {
            return;
        }
        System.out.println("Resumo da execução — réplica " + id);
        System.out.println("Primeira receção (ms): " + firstReceive);
        System.out.println("Última receção (ms): " + lastReceive);
        System.out.println("Intervalo de receção (ms): " + (lastReceive - firstReceive));
        System.out.println("Recebidos (incluindo duplicados): " + received);
        System.out.println("Recebidos distintos: " + (received - duplicates));
        System.out.println("Escritos: " + written);
        System.out.println("Duplicados: " + duplicates);
        System.out.println("Retidos: " + pending.size());
        summaryPending = false; // não repetir sem novos dados
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        // Porto da API HTTP; diferente do porto swarm entre os nós.
        String port = args.length > 1 ? args[1] : "5001";
        String api = "http://127.0.0.1:" + port + "/api/v0";
        try {
            PubSubReplica replica = new PubSubReplica(id);
            IpfsPubSub pubSub = new IpfsPubSub(api);
            System.out.println("Réplica " + id + " a subscrever " + TOPIC + " via " + api);
            ScheduledExecutorService summaryTask = Executors.newSingleThreadScheduledExecutor();
            try {
                // Outra thread verifica: espera inicial 1 s, intervalo de 1 s após cada chamada.
                summaryTask.scheduleWithFixedDelay(replica::showSummaryIfIdle, 1, 1, TimeUnit.SECONDS);
                // BLOQUEANTE: mantém o stream aberto; callback recebe cada texto descodificado.
                pubSub.subscribe(TOPIC, replica::onMessage);
            } finally {
                // Liberta a tarefa também quando a subscrição termina por erro.
                summaryTask.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Subscrição interrompida.");
        } catch (UncheckedIOException e) {
            System.out.println("IO no processamento da mensagem: " + e.getCause().getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
