package rep04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class HeartbeatCheck {
    private final int partitionCount;
    private final Path folder;
    private final List<String> report = new ArrayList<>();
    private final Map<Integer, List<Heartbeat>> heartbeats = new TreeMap<>();
    private final Map<Integer, Map<Long, SensorRecord>> records = new TreeMap<>();
    private final List<Suspicion> suspicions = new ArrayList<>();
    private int errors;

    private static final class Stats {
        long count;
        double sum;
        double maximum = Double.NEGATIVE_INFINITY;
        void add(double value) { count++; sum += value; maximum = Math.max(maximum, value); }
        String describe() {
            return count == 0 ? "sem amostras" : String.format(Locale.ROOT,
                    "amostras=%d média=%.3f máximo=%.3f", count, sum / count, maximum);
        }
    }

    private static final class Heartbeat {
        int partition;
        long inc;
        long hb;
        long instant;
        long lastSeq;
        int count;
        long bytes;
    }

    private static final class Event {
        long instant;
        String type;
        int partition;
        Map<String, String> detail = new HashMap<>();
    }

    private static final class Suspicion {
        String participant;
        int partition;
        long instant;
        long inc;
        String classification;
    }

    private HeartbeatCheck(int partitionCount, Path folder) {
        this.partitionCount = partitionCount;
        this.folder = folder;
    }

    private void error(Path path, int line, String message) {
        errors++;
        report.add("ERRO: " + path.getFileName() + ":" + line + " | " + message);
    }

    private static long timestamp(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
    }

    private void readPartition(int partition) throws IOException {
        Path history = folder.resolve("lider-p" + partition + ".txt");
        Map<Long, SensorRecord> partitionRecords = new TreeMap<>();
        records.put(partition, partitionRecords);
        if (!Files.isRegularFile(history)) {
            error(history, 0, "ficheiro ausente");
        } else {
            int lineNumber = 0;
            for (String line : Files.readAllLines(history, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    SensorRecord record = SensorRecord.fromLine(line, partitionCount);
                    timestamp(record.getTimestamp());
                    if (partitionRecords.putIfAbsent(record.getSeq(), record) != null) {
                        throw new IllegalArgumentException("seq repetido no histórico");
                    }
                } catch (IllegalArgumentException | DateTimeParseException failure) {
                    error(history, lineNumber, failure.getMessage());
                }
            }
        }
        Path path = folder.resolve("hb-p" + partition + ".txt");
        List<Heartbeat> partitionHeartbeats = new ArrayList<>();
        heartbeats.put(partition, partitionHeartbeats);
        if (!Files.isRegularFile(path)) {
            error(path, 0, "ficheiro ausente");
            return;
        }
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) continue;
            try {
                String[] fields = line.split(";", -1);
                if (fields.length != 6) throw new IllegalArgumentException("esperados seis campos");
                Heartbeat heartbeat = new Heartbeat();
                heartbeat.partition = partition;
                heartbeat.inc = Long.parseLong(fields[0]);
                heartbeat.hb = Long.parseLong(fields[1]);
                heartbeat.instant = Long.parseLong(fields[2]);
                heartbeat.lastSeq = Long.parseLong(fields[3]);
                heartbeat.count = Integer.parseInt(fields[4]);
                heartbeat.bytes = Long.parseLong(fields[5]);
                if (heartbeat.inc < 0 || heartbeat.hb < 1 || heartbeat.instant < 0
                        || heartbeat.lastSeq < 0 || heartbeat.count < 0 || heartbeat.bytes < 0) {
                    throw new IllegalArgumentException("valores negativos ou hb inválido");
                }
                partitionHeartbeats.add(heartbeat);
            } catch (IllegalArgumentException failure) {
                error(path, lineNumber, failure.getMessage());
            }
        }
        partitionHeartbeats.sort(Comparator.comparingLong(heartbeat -> heartbeat.instant));
    }

    private void batchMetrics() {
        long totalBytes = 0;
        long transported = 0;
        long equivalentBytes = 0;
        long equivalentCount = 0;
        for (Map.Entry<Integer, List<Heartbeat>> entry : heartbeats.entrySet()) {
            Map<Long, List<Heartbeat>> incarnations = entry.getValue().stream()
                    .collect(Collectors.groupingBy(heartbeat -> heartbeat.inc, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<Long, List<Heartbeat>> incarnation : incarnations.entrySet()) {
                Stats sizes = new Stats();
                Stats intervals = new Stats();
                long empty = 0;
                Long previous = null;
                for (Heartbeat heartbeat : incarnation.getValue()) {
                    sizes.add(heartbeat.count);
                    if (heartbeat.count == 0) empty++;
                    if (previous != null) intervals.add(heartbeat.instant - previous);
                    previous = heartbeat.instant;
                    totalBytes += heartbeat.bytes;
                    transported += heartbeat.count;
                    if (heartbeat.count == 0) continue;
                    long recordBytes = 0;
                    int found = 0;
                    for (long sequence = heartbeat.lastSeq - heartbeat.count + 1;
                            sequence <= heartbeat.lastSeq; sequence++) {
                        SensorRecord record = records.get(entry.getKey()).get(sequence);
                        if (record != null) {
                            recordBytes += record.toLine().getBytes(StandardCharsets.UTF_8).length;
                            found++;
                        }
                    }
                    if (found != heartbeat.count) {
                        report.add("AVISO: não foi possível reconstruir todos os registos de p"
                                + entry.getKey() + " hb=" + heartbeat.hb);
                        continue;
                    }
                    long headerBytes = heartbeat.bytes - recordBytes - heartbeat.count;
                    int fixedHeaderBytes = ("LOTE;;;" + heartbeat.inc + ";" + heartbeat.hb + ";"
                            + heartbeat.lastSeq + ";" + heartbeat.count).getBytes(StandardCharsets.UTF_8).length;
                    long addressPortBytes = headerBytes - fixedHeaderBytes;
                    if (addressPortBytes < 1) {
                        report.add("AVISO: tamanho incompatível com LOTE p" + entry.getKey() + " hb=" + heartbeat.hb);
                        continue;
                    }
                    equivalentBytes += recordBytes + heartbeat.count * (6 + addressPortBytes);
                    equivalentCount += heartbeat.count;
                }
                report.add("p" + entry.getKey() + " inc=" + incarnation.getKey()
                        + " lotes=" + sizes.count + " vazios=" + empty
                        + String.format(Locale.ROOT, " proporção_vazios=%.6f", (double) empty / sizes.count));
                report.add("  registos/lote: " + sizes.describe());
                report.add("  intervalo_hb_ms: " + intervals.describe());
            }
        }
        report.add(transported == 0 ? "Bytes/registo em LOTE: sem registos"
                : String.format(Locale.ROOT, "Bytes/registo em LOTE (inclui lotes vazios): %.3f",
                        (double) totalBytes / transported));
        report.add(equivalentCount == 0 ? "Bytes/registo em REG equivalente: sem amostras"
                : String.format(Locale.ROOT, "Bytes/registo em REG equivalente: %.3f",
                        (double) equivalentBytes / equivalentCount));
    }

    private List<Event> readEvents(Path path) throws IOException {
        List<Event> events = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) continue;
            try {
                String[] fields = line.split(";", -1);
                if (fields.length < 4 || !fields[2].matches("p[0-9]+")) {
                    throw new IllegalArgumentException("evento mal formado");
                }
                Event event = new Event();
                event.instant = Long.parseLong(fields[0]);
                event.type = fields[1];
                event.partition = Integer.parseInt(fields[2].substring(1));
                if (event.partition >= partitionCount) throw new IllegalArgumentException("partição inválida");
                for (int index = 3; index < fields.length; index++) {
                    int separator = fields[index].indexOf('=');
                    if (separator > 0) event.detail.put(fields[index].substring(0, separator), fields[index].substring(separator + 1));
                }
                events.add(event);
            } catch (IllegalArgumentException failure) {
                error(path, lineNumber, failure.getMessage());
            }
        }
        events.sort(Comparator.comparingLong(event -> event.instant));
        return events;
    }

    private void monitorMetrics() throws IOException {
        List<Path> paths;
        try (Stream<Path> files = Files.list(folder)) {
            paths = files.filter(path -> path.getFileName().toString().matches("monitor-.*\\.txt"))
                    .sorted().collect(Collectors.toList());
        }
        if (paths.isEmpty()) error(folder.resolve("monitor-*.txt"), 0, "nenhum ficheiro de monitor");
        Map<Integer, Stats> totalLatency = new TreeMap<>();
        long lost = 0;
        long recovery = 0;
        for (Path path : paths) {
            String participant = path.getFileName().toString().replaceFirst("^monitor-", "").replaceFirst("\\.txt$", "");
            List<Event> events = readEvents(path);
            Map<Integer, Long> currentInc = new HashMap<>();
            Map<Integer, Long> currentHb = new HashMap<>();
            Map<Integer, Stats> latencies = new TreeMap<>();
            Set<String> measured = new HashSet<>();
            for (int index = 0; index < events.size(); index++) {
                Event event = events.get(index);
                if (event.type.equals("HB_PERDIDO")) lost++;
                if (event.type.equals("RECUPERACAO")) recovery++;
                try {
                    if (event.type.equals("RECEBIDO")) {
                        long inc = Long.parseLong(event.detail.get("inc"));
                        long hb = Long.parseLong(event.detail.get("hb"));
                        if (inc > currentInc.getOrDefault(event.partition, -1L)
                                || (inc == currentInc.getOrDefault(event.partition, -1L)
                                    && hb > currentHb.getOrDefault(event.partition, 0L))) {
                            currentInc.put(event.partition, inc);
                            currentHb.put(event.partition, hb);
                        }
                        String range = event.detail.get("seq");
                        if (range == null || range.equals("-")) continue;
                        String[] ends = range.split("\\.\\.");
                        if (ends.length != 2) throw new IllegalArgumentException("intervalo seq inválido");
                        long first = Long.parseLong(ends[0]);
                        long last = Long.parseLong(ends[1]);
                        long declaredCount = Long.parseLong(event.detail.get("n"));
                        long declaredLast = Long.parseLong(event.detail.get("ultimoSeq"));
                        if (first < 1 || last < first || last - first + 1 != declaredCount || last != declaredLast) {
                            error(path, index + 1, "intervalo do lote incompatível com n/ultimoSeq");
                        }
                        for (SensorRecord record : records.get(event.partition).values()) {
                            if (record.getSeq() < first || record.getSeq() > last
                                    || !measured.add(event.partition + ":" + record.getSeq())) continue;
                            long latency = event.instant - timestamp(record.getTimestamp());
                            latencies.computeIfAbsent(event.partition, ignored -> new Stats()).add(latency);
                            totalLatency.computeIfAbsent(event.partition, ignored -> new Stats()).add(latency);
                        }
                    } else if (event.type.equals("SUSPEITA")) {
                        Suspicion suspicion = new Suspicion();
                        suspicion.participant = participant;
                        suspicion.partition = event.partition;
                        suspicion.instant = event.instant;
                        suspicion.inc = currentInc.getOrDefault(event.partition, -1L);
                        suspicion.classification = suspicion.inc < 0 ? "INCONCLUSIVA (sem heartbeat inicial)" : "FALHA_REAL";
                        for (int next = index + 1; next < events.size(); next++) {
                            Event following = events.get(next);
                            if (following.partition != event.partition || !following.type.equals("RETOMA")) continue;
                            if (Long.parseLong(following.detail.get("inc")) == suspicion.inc) {
                                suspicion.classification = "FALSA_SUSPEITA";
                            }
                            break;
                        }
                        long latestPublished = -1;
                        for (Heartbeat heartbeat : heartbeats.get(event.partition)) {
                            if (heartbeat.instant <= event.instant) latestPublished = heartbeat.instant;
                        }
                        suspicions.add(suspicion);
                        report.add("SUSPEITA " + participant + " p" + event.partition + " instante=" + event.instant
                                + " inc=" + suspicion.inc + " " + suspicion.classification
                                + " silêncio_publicado_ms=" + (latestPublished < 0 ? "desconhecido" : event.instant - latestPublished)
                                + " silêncio_local_ms=" + event.detail.getOrDefault("silencio_ms", "?"));
                    }
                } catch (IllegalArgumentException | NullPointerException | DateTimeParseException failure) {
                    error(path, index + 1, "detalhes inválidos: " + failure.getMessage());
                }
            }
            for (Map.Entry<Integer, Stats> entry : latencies.entrySet()) {
                report.add("Latência_lote_ms " + participant + " p" + entry.getKey() + ": " + entry.getValue().describe());
            }
        }
        for (Map.Entry<Integer, Stats> entry : totalLatency.entrySet()) {
            report.add("Latência_lote_ms todas_as_receções p" + entry.getKey() + ": " + entry.getValue().describe());
        }
        report.add("HB_PERDIDO=" + lost + " RECUPERACAO=" + recovery);
        report.add("Falsas suspeitas=" + suspicions.stream()
                .filter(suspicion -> suspicion.classification.equals("FALSA_SUSPEITA")).count());
    }

    private void failureMetrics(Path failures) throws IOException {
        List<long[]> incidents = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(failures, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.isBlank()) continue;
            try {
                String[] fields = line.split(";", -1);
                if (fields.length != 2 || !fields[0].matches("p[0-9]+")) {
                    throw new IllegalArgumentException("esperado p<k>;instante");
                }
                int partition = Integer.parseInt(fields[0].substring(1));
                if (partition >= partitionCount) throw new IllegalArgumentException("partição inválida");
                incidents.add(new long[] {partition, Long.parseLong(fields[1])});
            } catch (IllegalArgumentException failure) {
                error(failures, lineNumber, failure.getMessage());
            }
        }
        incidents.sort(Comparator.comparingLong(incident -> incident[1]));
        for (int index = 0; index < incidents.size(); index++) {
            int partition = (int) incidents.get(index)[0];
            long failureTime = incidents.get(index)[1];
            long limit = Long.MAX_VALUE;
            for (int next = index + 1; next < incidents.size(); next++) {
                if (incidents.get(next)[0] == partition) { limit = incidents.get(next)[1]; break; }
            }
            long incarnation = -1;
            for (Heartbeat heartbeat : heartbeats.get(partition)) {
                if (heartbeat.instant <= failureTime) incarnation = heartbeat.inc;
                else if (incarnation >= 0 && heartbeat.inc != incarnation) { limit = Math.min(limit, heartbeat.instant); break; }
            }
            Map<String, Suspicion> detected = new TreeMap<>();
            for (Suspicion suspicion : suspicions) {
                if (suspicion.partition == partition && suspicion.instant >= failureTime && suspicion.instant < limit
                        && suspicion.inc == incarnation && suspicion.classification.equals("FALHA_REAL")) {
                    detected.putIfAbsent(suspicion.participant, suspicion);
                }
            }
            if (detected.isEmpty()) report.add("Falha p" + partition + " instante=" + failureTime + ": sem deteção correlacionável");
            for (Suspicion suspicion : detected.values()) {
                report.add("Deteção " + suspicion.participant + " p" + partition + " falha=" + failureTime
                        + " deteção_ms=" + (suspicion.instant - failureTime));
            }
        }
    }

    private int run(Path failures) throws IOException {
        report.add("COMINDIR04 — relatório de heartbeat");
        report.add("Instantes comparados na mesma máquina; latências por primeira receção de cada registo/participante.");
        report.add("FALHA_REAL usa o critério do enunciado: sem RETOMA da mesma incarnação na janela observada.");
        for (int partition = 0; partition < partitionCount; partition++) readPartition(partition);
        batchMetrics();
        monitorMetrics();
        if (failures != null) failureMetrics(failures);
        report.add("RESULTADO: " + (errors == 0 ? "OK" : "ERROS=" + errors));
        Files.write(folder.resolve("relatorio-heartbeat.txt"), report, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
        return errors == 0 ? 0 : 1;
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Uso: HeartbeatCheck <N> <pasta> [falhas.txt]");
            System.exit(1);
        }
        try {
            int partitions = Integer.parseInt(args[0]);
            Path folder = Path.of(args[1]);
            if (partitions < 1 || !Files.isDirectory(folder)) throw new IllegalArgumentException("N ou pasta inválido");
            System.exit(new HeartbeatCheck(partitions, folder).run(args.length == 3 ? Path.of(args[2]) : null));
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("ERRO: " + failure.getMessage());
            System.exit(1);
        }
    }
}
