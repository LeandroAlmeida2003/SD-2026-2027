package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Analisa os carimbos persistidos; não participa na entrega às réplicas. */
public final class CausalCheck {
    private static final class Entry {
        final int partition;
        final SensorRecord record;

        Entry(int partition, SensorRecord record) {
            this.partition = partition;
            this.record = record;
        }

        String label() {
            return "p" + partition + "#" + record.getSeq();
        }
    }

    private static int check(int n, Path folder) throws IOException {
        List<Entry> entries = new ArrayList<>();
        long errors = 0;
        for (int k = 0; k < n; k++) {
            Path path = folder.resolve("lider-p" + k + ".txt");
            if (!Files.isRegularFile(path)) {
                System.out.println("ERRO: ficheiro ausente: " + path.getFileName());
                errors++;
                continue;
            }
            int count = 0;
            int lineNumber = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    SensorRecord record = SensorRecord.fromLine(line, n);
                    Entry entry = new Entry(k, record);
                    entries.add(entry);
                    count++;
                    if (record.getVector().get(k) != record.getSeq()) {
                        errors++;
                        System.out.println("ERRO: " + entry.label() + " V[k] != seq: "
                                + record.getVector());
                    }
                } catch (IllegalArgumentException e) {
                    errors++;
                    System.out.println("ERRO: " + path.getFileName() + ":" + lineNumber
                            + " | " + e.getMessage());
                }
            }
            System.out.println("PARTIÇÃO p" + k + ": registos=" + count);
        }

        long before = 0;
        long after = 0;
        long concurrent = 0;
        long concurrentDistinct = 0;
        long equal = 0;
        long violations = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry a = entries.get(i);
            for (int j = i + 1; j < entries.size(); j++) {
                Entry b = entries.get(j);
                if (a.partition == b.partition) continue;
                VectorClock.Order order = a.record.getVector().compare(b.record.getVector());
                boolean validLamport = true;
                switch (order) {
                    case BEFORE:
                        before++;
                        validLamport = a.record.getLamport() < b.record.getLamport();
                        break;
                    case AFTER:
                        after++;
                        validLamport = a.record.getLamport() > b.record.getLamport();
                        break;
                    case CONCURRENT:
                        concurrent++;
                        if (a.record.getLamport() != b.record.getLamport()) concurrentDistinct++;
                        break;
                    case EQUAL:
                        equal++;
                        errors++;
                        System.out.println("ERRO: vetores iguais entre " + a.label() + " e " + b.label());
                        break;
                }
                if (!validLamport) {
                    violations++;
                    System.out.println("VIOLAÇÃO LAMPORT: " + a.label() + " / " + b.label()
                            + " ordem=" + order + " L=" + a.record.getLamport()
                            + "/" + b.record.getLamport());
                }
            }
        }

        System.out.println("PARES ENTRE PARTIÇÕES (A é o registo da partição de menor índice):");
        System.out.println("A → B: " + before);
        System.out.println("B → A: " + after);
        System.out.println("Pares causais: " + (before + after));
        System.out.println("Pares concorrentes: " + concurrent);
        System.out.println("Vetores iguais: " + equal);
        System.out.println("Concorrentes com L distinto: " + concurrentDistinct);
        if (concurrent == 0) {
            System.out.println("Proporção de concorrentes com L distinto: não aplicável");
        } else {
            double proportion = (double) concurrentDistinct / concurrent;
            System.out.printf(Locale.ROOT, "Proporção de concorrentes com L distinto: %.6f (%.2f%%)%n",
                    proportion, proportion * 100);
        }
        System.out.println("Violações da condição de Lamport: " + violations);

        entries.sort(Comparator.comparingLong((Entry e) -> e.record.getLamport())
                .thenComparingInt(e -> e.partition));
        List<String> view = new ArrayList<>();
        for (Entry entry : entries) {
            view.add("p" + entry.partition + ";" + entry.record.toLine());
        }
        Path output = folder.resolve("vista-lamport.txt");
        Files.write(output, view, StandardCharsets.UTF_8);
        System.out.println("Vista por (L,k): " + output);
        boolean valid = errors == 0 && violations == 0;
        System.out.println(valid ? "RESULTADO: OK" : "RESULTADO: ERROS; os totais podem ser parciais.");
        return valid ? 0 : 1;
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Uso: CausalCheck <N> <pasta>");
            System.exit(1);
        }
        try {
            int n = Integer.parseInt(args[0]);
            Path folder = Paths.get(args[1]);
            if (n < 1 || !Files.isDirectory(folder)) {
                throw new IllegalArgumentException("É necessário N positivo e uma pasta existente");
            }
            System.exit(check(n, folder));
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("ERRO: " + e.getMessage());
            System.exit(1);
        }
    }
}
