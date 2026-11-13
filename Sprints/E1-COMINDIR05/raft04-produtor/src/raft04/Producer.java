package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Producer implements AutoCloseable {
    private final Partitioner partitioner;
    private final IpfsPubSub pubSub;
    private final MonitorLog log;
    private final HeartbeatMonitor[] monitors;
    private final ScheduledExecutorService scheduler;

    public Producer(int partitionCount, String api) throws IOException {
        partitioner = new Partitioner(partitionCount);
        pubSub = new IpfsPubSub(api);
        log = new MonitorLog("produtor");
        Files.writeString(Path.of("produtor.txt"), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        monitors = new HeartbeatMonitor[partitionCount];
        for (int partition = 0; partition < partitionCount; partition++) {
            final int observed = partition;
            monitors[partition] = new HeartbeatMonitor(partition, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, log::event);
            pubSub.subscribeAsync(Wire.topicData(partition), message -> onHeader(observed, message), true);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "verificacao-produtor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> {
            for (HeartbeatMonitor monitor : monitors) monitor.check();
        }, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
    }

    private void onHeader(int partition, String message) {
        try {
            Batch.Header header = Batch.header(message);
            if (!monitors[partition].onHeartbeat(header.term, header.leader, header.hb)) return;
            log.received(partition, header);
        } catch (IllegalArgumentException error) {
            System.err.println("Cabeçalho rejeitado p" + partition + ": " + error.getMessage());
        }
    }

    private void send(String sensor, double temperature) throws InterruptedException {
        if (sensor.isBlank() || sensor.contains(";") || !Double.isFinite(temperature)) {
            throw new IllegalArgumentException("Sensor ou temperatura inválido");
        }
        int partition = partitioner.of(sensor);
        if (monitors[partition].state() == HeartbeatMonitor.State.SUSPEITO) {
            System.out.println("AVISO: partição p" + partition + " sem líder conhecido; leitura " + sensor + " pode perder-se");
        }
        String message = Wire.in(sensor, temperature, 0, new VectorClock(partitioner.count()));
        try {
            long instant = System.currentTimeMillis();
            pubSub.publish(Wire.topicIn(partition), message);
            Files.writeString(Path.of("produtor.txt"), instant + ";p" + partition + ";" + sensor + ";" + temperature + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("Publicado em " + Wire.topicIn(partition) + ": " + message);
        } catch (IOException error) {
            System.err.println("Publicação falhou p" + partition + ": " + error.getMessage());
        }
    }

    private void burst(int count, long intervalMs) throws InterruptedException {
        if (count < 1 || intervalMs < 0) throw new IllegalArgumentException("Quantidade positiva e intervalo >= 0");
        Random random = new Random();
        long start = System.currentTimeMillis();
        for (int index = 0; index < count; index++) {
            String sensor = String.format(Locale.ROOT, "S%02d", random.nextInt(10) + 1);
            send(sensor, 15 + random.nextDouble() * 15);
            if (index + 1 < count) Thread.sleep(intervalMs);
        }
        System.out.println("Rajada terminada: tentativas=" + count + "; duração_ms=" + (System.currentTimeMillis() - start));
    }

    private void console() throws InterruptedException {
        try (Scanner input = new Scanner(System.in)) {
            System.out.println("<sensor> <temperatura> | rajada <quantidade> <intervalo_ms> | sair");
            while (input.hasNextLine()) {
                String line = input.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                if (line.isEmpty()) continue;
                String[] fields = line.split("\\s+");
                try {
                    if (fields.length == 3 && fields[0].equalsIgnoreCase("rajada")) {
                        burst(Integer.parseInt(fields[1]), Long.parseLong(fields[2]));
                    } else if (fields.length == 2) {
                        send(fields[0], Double.parseDouble(fields[1]));
                    } else {
                        System.out.println("Formato inválido");
                    }
                } catch (IllegalArgumentException error) {
                    System.err.println(error.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Uso: Producer <N> [api]");
            return;
        }
        try (Producer producer = new Producer(Integer.parseInt(args[0]),
                args.length == 2 ? args[1] : System.getProperty("raft04.api", "http://127.0.0.1:5001/api/v0"))) {
            producer.console();
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("Falha no produtor: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
