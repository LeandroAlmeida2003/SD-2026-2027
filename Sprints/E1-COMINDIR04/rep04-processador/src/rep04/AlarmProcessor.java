package rep04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Observa as partições e transporta o conhecimento acumulado nos alarmes. */
public class AlarmProcessor {
    private final Partitioner partitioner;
    private final IpfsPubSub pubSub;
    private final Set<String> processed = new HashSet<>();
    private final MonitorLog log;
    private final HeartbeatMonitor[] monitors;
    // Estes três campos partilhados são protegidos pelo monitor this.
    private long lp;
    private final VectorClock vp;

    public AlarmProcessor(int n, String api) throws IOException {
        partitioner = new Partitioner(n);
        pubSub = new IpfsPubSub(api);
        vp = new VectorClock(n);
        log = new MonitorLog("processador");
        monitors = new HeartbeatMonitor[n];
        for (int partition = 0; partition < n; partition++) {
            monitors[partition] = new HeartbeatMonitor(partition, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, log::event);
        }
    }

    private void onBatch(int partition, String message) {
        Batch batch;
        try {
            batch = Batch.decode(message, partitioner.count());
        } catch (IllegalArgumentException error) {
            System.err.println("REJEITADO p" + partition + ": " + error.getMessage());
            return;
        }
        monitors[partition].onHeartbeat(batch.inc, batch.hb);
        log.received(partition, batch);
        for (SensorRecord record : batch.records) onRecord(partition, record);
    }

    private void onRecord(int partition, SensorRecord record) {
        String sensor = record.getSensorId();
        if (sensor.startsWith("ALARM-")) {
            return;
        }

        String alarmMessage;
        int destination;
        synchronized (this) {
            // add devolve false se este par já tiver sido observado.
            if (!processed.add(partition + ":" + record.getSeq())) {
                return;
            }
            lp = Math.max(lp, record.getLamport());
            vp.merge(record.getVector());
            if (!(record.getTemperature() >= Wire.LIMIAR_ALARME)) {
                return;
            }

            String alarmSensor = "ALARM-" + sensor;
            destination = partitioner.of(alarmSensor);
            // L e a cópia de V são capturados sob o mesmo monitor.
            alarmMessage = Wire.in(alarmSensor, record.getTemperature(), lp, vp.copy());
        }

        // A mensagem já é uma String independente do estado partilhado.
        try {
            if (monitors[destination].state() == HeartbeatMonitor.State.SUSPEITO) {
                System.out.println("AVISO: líder p" + destination + " suspeito; alarme " + sensor + " pode perder-se");
            }
            pubSub.publish(Wire.topicIn(destination), alarmMessage);
            System.out.println("ALARME: " + sensor + " p" + partition + "#"
                    + record.getSeq() + " → p" + destination);
        } catch (IOException e) {
            System.out.println("Publicação do alarme falhou: " + alarmMessage + " | " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação interrompida", e);
        }
    }

    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "verificacao-processador");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> {
            for (HeartbeatMonitor monitor : monitors) monitor.check();
        }, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
        for (int k = 0; k < partitioner.count(); k++) {
            final int partition = k;
            Thread subscriber = new Thread(() -> {
                try {
                    System.out.println("A iniciar subscrição de " + Wire.topicData(partition));
                    pubSub.subscribe(Wire.topicData(partition), message -> onBatch(partition, message));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Subscrição interrompida: p" + partition);
                } catch (IOException | UncheckedIOException | IllegalStateException e) {
                    System.out.println("Subscrição terminada: p" + partition + " | " + e.getMessage());
                }
            }, "processador-p" + partition);
            subscriber.start();
        }
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Uso: AlarmProcessor <N> [api]");
            return;
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        try {
            new AlarmProcessor(Integer.parseInt(args[0]), api).start();
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("Falha no processador: " + e.getMessage());
        }
    }
}
