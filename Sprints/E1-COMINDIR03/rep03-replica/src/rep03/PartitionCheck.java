package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Inspeção dos ficheiros reunidos de uma execução, sem os modificar. */
public final class PartitionCheck {
    private static final int EXPECTED_REPLICAS = 2;
    private static final Pattern DATA_FILE = Pattern.compile("(lider|replica-[1-9][0-9]*)-p([0-9]+)\\.txt");

    private static final class FileData {
        final List<String> lines;
        final List<SensorRecord> records = new ArrayList<>();
        boolean valid = true;

        FileData(Path path, int n) throws IOException {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            long expected = 1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                try {
                    SensorRecord record = SensorRecord.fromLine(line, n);
                    records.add(record);
                    if (record.getSeq() != expected) {
                        System.out.println("  ERRO: " + path.getFileName() + ": linha " + (i + 1)
                                + ", seq=" + record.getSeq() + ", esperado=" + expected);
                        valid = false;
                    }
                } catch (IllegalArgumentException e) {
                    System.out.println("  ERRO: " + path.getFileName() + ": linha " + (i + 1)
                            + " inválida: " + e.getMessage());
                    valid = false;
                }
                expected++;
            }
            System.out.println("  " + path.getFileName() + ": registos=" + records.size()
                    + "; sequência/formato=" + (valid ? "OK" : "ERRO"));
        }
    }

    private static boolean prefix(List<String> shorter, List<String> longer) {
        return shorter.size() <= longer.size()
                && shorter.equals(longer.subList(0, shorter.size()));
    }

    private static int check(int n, Path folder) throws IOException {
        Partitioner partitioner = new Partitioner(n);
        Map<Integer, List<Path>> replicas = new HashMap<>();
        List<Path> paths;
        try (Stream<Path> files = Files.list(folder)) {
            paths = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
        }
        boolean valid = true;
        for (Path path : paths) {
            Matcher matcher = DATA_FILE.matcher(path.getFileName().toString());
            if (!matcher.matches()) continue;
            int k;
            try {
                k = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                System.out.println("ERRO: número de partição inválido em " + path.getFileName());
                valid = false;
                continue;
            }
            if (k < 0 || k >= n || !matcher.group(2).equals(Integer.toString(k))) {
                System.out.println("ERRO: ficheiro fora da configuração N=" + n + ": " + path.getFileName());
                valid = false;
            } else if (matcher.group(1).startsWith("replica-")) {
                replicas.computeIfAbsent(k, ignored -> new ArrayList<>()).add(path);
            }
        }

        long total = 0;
        long partitionSum = 0;
        long replicaTotal = 0;
        int incomplete = 0;
        for (int k = 0; k < n; k++) {
            System.out.println("PARTIÇÃO p" + k);
            FileData leader = null;
            Path leaderPath = folder.resolve("lider-p" + k + ".txt");
            try {
                leader = new FileData(leaderPath, n);
                valid &= leader.valid;
                total += leader.records.size();
                Set<String> sensors = new HashSet<>();
                long count = 0;
                for (SensorRecord record : leader.records) {
                    count++;
                    sensors.add(record.getSensorId());
                    int actual = partitioner.of(record.getSensorId());
                    if (actual != k) {
                        valid = false;
                        System.out.println("  ERRO: seq=" + record.getSeq() + ", sensor="
                                + record.getSensorId() + " pertence a p" + actual);
                    }
                }
                partitionSum += count;
                System.out.println("  Dados da partição: registos=" + count + "; sensores distintos=" + sensors.size());
            } catch (IOException e) {
                valid = false;
                System.out.println("  ERRO ao ler " + leaderPath.getFileName() + ": " + e.getMessage());
            }

            List<Path> copies = replicas.getOrDefault(k, new ArrayList<>());
            System.out.println("  Réplicas=" + copies.size() + "; esperado R=" + EXPECTED_REPLICAS);
            if (copies.size() != EXPECTED_REPLICAS) valid = false;
            for (Path copyPath : copies) {
                try {
                    FileData copy = new FileData(copyPath, n);
                    valid &= copy.valid;
                    replicaTotal += copy.records.size();
                    if (leader == null) {
                        System.out.println("    Comparação indisponível: ficheiro do líder ilegível ou ausente.");
                    } else {
                        boolean isPrefix = prefix(copy.lines, leader.lines);
                        boolean equal = copy.lines.equals(leader.lines);
                        long missing = Math.max(0, leader.records.size() - copy.records.size());
                        long extra = Math.max(0, copy.records.size() - leader.records.size());
                        System.out.println("    " + (equal ? "IGUAL" : isPrefix ? "PREFIXO" : "DIVERGENTE")
                                + "; faltam=" + missing + "; em excesso=" + extra);
                        if (!isPrefix) valid = false;
                        if (isPrefix && !equal) incomplete++;
                    }
                } catch (IOException e) {
                    valid = false;
                    System.out.println("  ERRO ao ler " + copyPath.getFileName() + ": " + e.getMessage());
                }
            }
        }
        System.out.println("TOTAL DO SISTEMA (ficheiros dos líderes): " + total);
        System.out.println("SOMA DAS PARTIÇÕES: " + partitionSum + "; " + (total == partitionSum ? "OK" : "ERRO"));
        System.out.println("TOTAL NAS RÉPLICAS (cópias): " + replicaTotal);
        System.out.println("RÉPLICAS COM PREFIXO INCOMPLETO: " + incomplete);
        if (!valid) System.out.println("RESULTADO: ERROS DETETADOS; os totais podem ser parciais se houver linhas ou ficheiros ilegíveis.");
        else if (incomplete > 0) System.out.println("RESULTADO: SEM ERROS ESTRUTURAIS; EXISTEM RÉPLICAS INCOMPLETAS.");
        else System.out.println("RESULTADO: OK; TODAS AS RÉPLICAS IGUAIS AOS LÍDERES.");
        return valid && total == partitionSum ? 0 : 1;
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Uso: PartitionCheck <N> <pasta>");
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