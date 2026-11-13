package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class MonitorLog {
    private final Path path;
    public MonitorLog(String id) throws IOException { this(Path.of("monitor-" + id + ".txt")); }
    public MonitorLog(Path path) throws IOException {
        this.path = path;
        Files.writeString(path, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    public synchronized void event(String event) {
        String line = System.currentTimeMillis() + ";" + event;
        try {
            Files.writeString(path, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println(line);
    }
    public void received(int k, Batch.Header h) {
        event("RECEBIDO;p" + k + ";termo=" + h.term + ";lider=n" + h.leader + ";hb=" + h.hb
                + ";ultimoSeq=" + h.lastSeq + ";ultimoTermo=" + h.lastTerm + ";n=" + h.count);
    }
    public void received(int k, Batch b) { received(k, Batch.header(b.encode())); }
}
