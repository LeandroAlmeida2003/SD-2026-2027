package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Memória e disco mudam sob o mesmo monitor; substituição por rename atómico. */
public final class RecordFile {
    private final Path path;
    private final List<SensorRecord> records = new ArrayList<>();
    public RecordFile(String name, int n) throws IOException {
        path = Path.of(name);
        if (Files.exists(path)) for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            SensorRecord r = SensorRecord.fromLine(line, n);
            if (r.getSeq() != records.size() + 1L) throw new IOException("Histórico não contíguo: " + path);
            records.add(r);
        }
        Files.writeString(path, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    public synchronized void append(SensorRecord r) throws IOException {
        if (r.getSeq() != records.size() + 1L) throw new IOException("Append fora de ordem");
        Files.writeString(path, r.toLine() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        records.add(r);
    }
    public synchronized SensorRecord at(long seq) {
        return seq < 1 || seq > records.size() ? null : records.get((int)seq - 1);
    }
    public synchronized SensorRecord last() { return at(records.size()); }
    public synchronized long lastSeq() { return records.size(); }
    public synchronized long lastTerm() { return last() == null ? 0 : last().getTerm(); }
    public synchronized List<SensorRecord> range(long from, long to) {
        if (from < 1 || from > records.size() || to < from) return List.of();
        return new ArrayList<>(records.subList((int)from - 1, (int)Math.min(to, records.size())));
    }
    public synchronized void replace(List<SensorRecord> replacement) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int j = 0; j < replacement.size(); j++) {
            if (replacement.get(j).getSeq() != j + 1L) throw new IOException("Substituição não contígua");
            text.append(replacement.get(j).toLine()).append('\n');
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        records.clear(); records.addAll(replacement);
    }
}
