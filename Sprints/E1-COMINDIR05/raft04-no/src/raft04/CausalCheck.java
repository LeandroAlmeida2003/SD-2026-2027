package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Verifica todas as cópias; a vista causal usa a união das identidades finais. */
public final class CausalCheck {
    private static final class Entry {
        int k; SensorRecord r;
        Entry(int k, SensorRecord r) { this.k = k; this.r = r; }
    }
    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException("CausalCheck <N> <pasta>");
            int n = Integer.parseInt(args[0]); Path folder = Path.of(args[1]);
            if (n < 1 || !Files.isDirectory(folder)) throw new IllegalArgumentException("N ou pasta inválidos");
            StringBuilder report = new StringBuilder();
            Map<String, Entry> entries = new TreeMap<>();
            int errors = 0;
            for (int k = 0; k < n; k++) for (int i = 1; i <= Wire.GRUPO; i++) {
                Path path = folder.resolve("no-p" + k + "-n" + i + ".txt");
                if (!Files.isRegularFile(path)) { report.append("ERRO ausente: ").append(path).append('\n'); errors++; continue; }
                long previousL = -1, previousSeq = 0;
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    SensorRecord r = SensorRecord.fromLine(line, n);
                    if (r.getVector().get(k) != r.getSeq() || r.getLamport() <= previousL || r.getSeq() != previousSeq + 1) {
                        errors++; report.append("ERRO V[k]!=seq, L ou sequência: ").append(path.getFileName()).append(':').append(r.toLine()).append('\n');
                    }
                    previousL = r.getLamport(); previousSeq = r.getSeq();
                    entries.put(k + ":" + r.getSeq() + ":" + r.getTerm(), new Entry(k, r));
                }
            }
            List<Entry> list = new ArrayList<>(entries.values());
            long causal = 0, concurrent = 0;
            for (int a = 0; a < list.size(); a++) for (int b = a + 1; b < list.size(); b++) {
                Entry x = list.get(a), y = list.get(b);
                if (x.k == y.k) continue;
                VectorClock.Order order = x.r.getVector().compare(y.r.getVector());
                boolean valid = true;
                switch (order) {
                    case BEFORE: causal++; valid = x.r.getLamport() < y.r.getLamport(); break;
                    case AFTER: causal++; valid = x.r.getLamport() > y.r.getLamport(); break;
                    case CONCURRENT: concurrent++; break;
                    case EQUAL: valid = false; break;
                }
                if (!valid) { errors++; report.append("ERRO causal: p").append(x.k).append('#').append(x.r.getSeq())
                        .append(" / p").append(y.k).append('#').append(y.r.getSeq()).append('\n'); }
            }
            list.sort(Comparator.comparingLong((Entry e) -> e.r.getLamport()).thenComparingInt(e -> e.k).thenComparingLong(e -> e.r.getTerm()));
            List<String> view = new ArrayList<>();
            for (Entry e : list) view.add("p" + e.k + ";" + e.r.toLine());
            Files.write(folder.resolve("vista-lamport.txt"), view, StandardCharsets.UTF_8);
            report.append("pares_causais=").append(causal).append(";concorrentes=").append(concurrent)
                    .append(";erros=").append(errors).append("\nRESULTADO: ").append(errors == 0 ? "OK" : "ERROS").append('\n');
            Files.writeString(folder.resolve("relatorio-causal.txt"), report, StandardCharsets.UTF_8);
            System.out.print(report); System.exit(errors == 0 ? 0 : 1);
        } catch (IOException | RuntimeException e) { System.err.println("ERRO CausalCheck: " + e.getMessage()); System.exit(1); }
    }
}
