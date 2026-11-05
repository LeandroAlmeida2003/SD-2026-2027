package rep04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class MonitorLog {
    private final Path path;

    public MonitorLog(String id) throws IOException {
        path = Path.of("monitor-" + id + ".txt");
        Files.writeString(path, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void event(String event) {
        String line = System.currentTimeMillis() + ";" + event;
        try {
            Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        System.out.println(line);
    }

    public void received(int partition, Batch.Header header) {
        String range = header.count == 0 ? "-" : (header.lastSeq - header.count + 1) + ".." + header.lastSeq;
        event("RECEBIDO;p" + partition + ";inc=" + header.inc + ";hb=" + header.hb
                + ";ultimoSeq=" + header.lastSeq + ";n=" + header.count + ";seq=" + range);
    }

    public void received(int partition, Batch batch) {
        String range = batch.records.isEmpty() ? "-" : batch.records.get(0).getSeq()
                + ".." + batch.records.get(batch.records.size() - 1).getSeq();
        event("RECEBIDO;p" + partition + ";inc=" + batch.inc + ";hb=" + batch.hb
                + ";ultimoSeq=" + batch.lastSeq + ";n=" + batch.records.size() + ";seq=" + range);
    }
}
