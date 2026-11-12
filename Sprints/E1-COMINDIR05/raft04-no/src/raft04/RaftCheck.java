package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Análise posterior: nunca participa na eleição nem corrige os históricos. */
public final class RaftCheck {
    private final int n;
    private final Path folder;
    private final StringBuilder report = new StringBuilder();
    private int errors, multipleLeaders, decreases, differences, lost, truncated, reappeared;
    private final List<Long> startup = new ArrayList<>(), downtime = new ArrayList<>();
    private static final class Event {
        long time; int node; String kind, detail;
        long field(String key, long fallback) {
            Matcher m = Pattern.compile("(?:^|;)" + key + "=(?:n)?(\\d+)").matcher(detail);
            return m.find() ? Long.parseLong(m.group(1)) : fallback;
        }
    }
    private static final class Election {
        long first = Long.MAX_VALUE, elected = Long.MAX_VALUE;
        final Set<Integer> candidates = new TreeSet<>(), leaders = new TreeSet<>();
        final Map<Integer, Set<Integer>> votes = new TreeMap<>();
    }
    private static final class Heartbeat {
        long term, time; int node;
    }
    private static final class Reading {
        long time; int partition; String sensor; double temperature;
        String key() { return sensor + ";" + temperature; }
    }
    private RaftCheck(int n, Path folder) { this.n = n; this.folder = folder; }
    private void say(String text) { report.append(text).append('\n'); }
    private void error(String text) { errors++; say("ERRO: " + text); }
    private List<String> lines(Path path, boolean required) throws IOException {
        if (!Files.isRegularFile(path)) {
            if (required) error("ficheiro ausente: " + path.getFileName());
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            line = line.replace("\uFEFF", "").trim();
            if (!line.isEmpty()) result.add(line);
        }
        return result;
    }
    private List<SensorRecord> records(Path path) throws IOException {
        List<SensorRecord> out = new ArrayList<>();
        for (String line : lines(path, true)) {
            SensorRecord r = SensorRecord.fromLine(line, n);
            if (r.getSeq() != out.size() + 1L) error("histórico não contíguo: " + path);
            out.add(r);
        }
        return out;
    }
    private List<Reading> readings() throws IOException {
        List<Reading> out = new ArrayList<>();
        for (String line : lines(folder.resolve("produtor.txt"), true)) {
            String[] f = line.split(";", -1);
            if (f.length != 4) throw new IOException("produtor.txt mal formado");
            Reading r = new Reading(); r.time = Long.parseLong(f[0]); r.partition = Integer.parseInt(f[1].substring(1));
            r.sensor = f[2]; r.temperature = Double.parseDouble(f[3]);
            if (r.partition < 0 || r.partition >= n || !Double.isFinite(r.temperature)) throw new IOException("Leitura inválida");
            out.add(r);
        }
        return out;
    }
    private void partition(int k, List<Reading> readings, Path failures) throws IOException {
        say("\nPARTIÇÃO p" + k);
        Map<Long, Election> terms = new TreeMap<>();
        Map<Integer, List<SensorRecord>> histories = new TreeMap<>();
        List<Heartbeat> beats = new ArrayList<>();
        long firstStart = Long.MAX_VALUE, firstLeader = Long.MAX_VALUE;
        List<Event> truncations = new ArrayList<>();
        for (int i = 1; i <= Wire.GRUPO; i++) {
            histories.put(i, records(folder.resolve("no-p" + k + "-n" + i + ".txt")));
            long previous = -1;
            for (String line : lines(folder.resolve("eventos-p" + k + "-n" + i + ".txt"), true)) {
                String[] f = line.split(";", 4);
                if (f.length != 4 || !f[2].equals("p" + k)) throw new IOException("Evento mal formado: " + line);
                Event e = new Event(); e.time = Long.parseLong(f[0]); e.node = i; e.kind = f[1]; e.detail = f[3];
                long term = e.field("termo", -1);
                if (e.kind.equals("TERMO") && e.detail.contains("->"))
                    term = Long.parseLong(e.detail.substring(e.detail.indexOf("->") + 2));
                if (term >= 0 && List.of("TERMO", "CANDIDATO", "VOTO", "LIDER", "NOVO_LIDER").contains(e.kind)) {
                    if (term < previous) { decreases++; error("termo diminuiu em n" + i + ": " + previous + " -> " + term); }
                    previous = term;
                }
                if (e.kind.equals("TERMO") && e.detail.startsWith("arranque")) firstStart = Math.min(firstStart, e.time);
                if (e.kind.equals("CANDIDATO")) {
                    Election t = terms.computeIfAbsent(term, ignored -> new Election());
                    t.candidates.add(i); t.first = Math.min(t.first, e.time);
                    t.votes.computeIfAbsent(i, ignored -> new TreeSet<>()).add(i);
                }
                if (e.kind.equals("VOTO")) {
                    Election t = terms.computeIfAbsent(term, ignored -> new Election());
                    if (e.detail.endsWith(";concedido")) t.votes.computeIfAbsent((int)e.field("candidato", 0), ignored -> new TreeSet<>()).add(i);
                }
                if (e.kind.equals("LIDER")) {
                    Election t = terms.computeIfAbsent(term, ignored -> new Election());
                    t.leaders.add(i); t.elected = Math.min(t.elected, e.time); firstLeader = Math.min(firstLeader, e.time);
                }
                if (e.kind.equals("TRUNCADO")) {
                    truncations.add(e); truncated += (int)e.field("descartados", 0);
                }
            }
            List<String> state = lines(folder.resolve("estado-p" + k + "-n" + i + ".txt"), true);
            if (!state.isEmpty()) {
                String[] f = state.get(0).split(";", -1);
                if (state.size() != 1 || f.length != 2) throw new IOException("Estado mal formado");
                long term = Long.parseLong(f[0]); int voted = Integer.parseInt(f[1]);
                if (term < previous || voted < 0 || voted > Wire.GRUPO) error("Estado final inválido em n" + i);
                say("  n" + i + " estado final=" + state.get(0) + "; registos=" + histories.get(i).size());
            }
            for (String line : lines(folder.resolve("hb-p" + k + "-n" + i + ".txt"), false)) {
                String[] f = line.split(";", -1);
                if (f.length != 7) throw new IOException("Heartbeat mal formado");
                for (String value : f) if (Long.parseLong(value) < 0) throw new IOException("Heartbeat negativo");
                Heartbeat b = new Heartbeat(); b.term = Long.parseLong(f[0]); b.time = Long.parseLong(f[2]); b.node = i;
                beats.add(b);
            }
        }
        int finalLeader = 0;
        for (Map.Entry<Long, Election> item : terms.entrySet()) {
            Election t = item.getValue();
            if (t.leaders.size() > 1) { multipleLeaders++; error("mais de um líder no termo " + item.getKey()); }
            if (!t.leaders.isEmpty()) finalLeader = t.leaders.iterator().next();
            String duration = t.elected == Long.MAX_VALUE || t.first == Long.MAX_VALUE ? "inconclusiva" : (t.elected - t.first) + " ms";
            say("  termo=" + item.getKey() + " candidatos=" + t.candidates + " votos=" + t.votes
                    + " lideres=" + t.leaders + (t.leaders.isEmpty() ? " voto dividido/sem maioria" : "") + " eleicao=" + duration);
        }
        if (firstStart != Long.MAX_VALUE && firstLeader != Long.MAX_VALUE) {
            startup.add(firstLeader - firstStart); say("  primeiro_lider_ms=" + (firstLeader - firstStart));
        } else error("sem evidência do primeiro líder ou do arranque em p" + k);
        beats.sort(Comparator.comparingLong(b -> b.time));
        if (failures != null) for (String line : lines(failures, true)) {
            String[] f = line.split(";", -1);
            if (f.length != 3) throw new IOException("Falha deve ser p<k>;n<i>;instante");
            if (!f[0].equals("p" + k)) continue;
            int failed = Integer.parseInt(f[1].substring(1)); long instant = Long.parseLong(f[2]);
            long oldTerm = -1;
            for (Heartbeat b : beats) if (b.time <= instant && b.node == failed) oldTerm = Math.max(oldTerm, b.term);
            Heartbeat recovered = null;
            if (oldTerm >= 0) for (Heartbeat b : beats) if (b.time > instant && b.term > oldTerm) { recovered = b; break; }
            if (recovered == null) { say("  falha=" + line + ";indisponibilidade=inconclusiva (sem LOTE de termo superior)"); continue; }
            long elapsed = recovered.time - instant; downtime.add(elapsed);
            long sent = 0;
            for (Reading r : readings) if (r.partition == k && r.time >= instant && r.time < recovered.time) {
                sent++; say("    leitura_na_indisponibilidade=" + r.time + ";" + r.key());
            }
            say("  falha=" + line + ";indisponibilidade_ms=" + elapsed + ";leituras_no_intervalo=" + sent);
        }
        // União por identidade evita contar as três cópias como três leituras recebidas.
        Map<String, SensorRecord> union = new HashMap<>();
        for (List<SensorRecord> rs : histories.values()) for (SensorRecord r : rs) union.put(r.getSeq() + ":" + r.getTerm(), r);
        Map<String, Integer> available = new HashMap<>();
        for (SensorRecord r : union.values()) available.merge(r.getSensorId() + ";" + r.getTemperature(), 1, Integer::sum);
        for (Reading r : readings) if (r.partition == k) {
            int count = available.getOrDefault(r.key(), 0);
            if (count == 0) { lost++; say("  PERDIDA=" + r.time + ";" + r.key()); }
            else available.put(r.key(), count - 1);
        }
        List<SensorRecord> leaderRecords = histories.getOrDefault(finalLeader, List.of());
        for (Event e : truncations) say("  TRUNCADO n" + e.node + " instante=" + e.time + ";" + e.detail);
        for (int i = 1; i <= Wire.GRUPO; i++) for (String line : lines(folder.resolve("truncados-p" + k + "-n" + i + ".txt"), false)) {
            SensorRecord discarded = SensorRecord.fromLine(line.substring(line.indexOf(';') + 1), n);
            boolean found = leaderRecords.stream().anyMatch(r -> r.getSeq() != discarded.getSeq()
                    && r.getSensorId().equals(discarded.getSensorId()) && r.getTemperature() == discarded.getTemperature());
            if (found) reappeared++;
            say("    descartado=n" + i + "#" + discarded.getSeq() + ";termo=" + discarded.getTerm() + ";existe_com_outro_seq=" + found);
        }
        int min = histories.values().stream().mapToInt(List::size).min().orElse(0);
        for (int j = 0; j < min; j++) {
            String reference = histories.get(1).get(j).toLine();
            boolean different = false;
            for (List<SensorRecord> rs : histories.values()) if (!rs.get(j).toLine().equals(reference)) different = true;
            if (different) { differences++; say("  DIVERGENTE seq=" + (j + 1)); }
        }
        say("  coincidencia_comparada_ate=" + min + ";comprimentos=" + histories.values().stream().map(List::size).collect(java.util.stream.Collectors.toList()));
        if (histories.values().stream().map(List::size).distinct().count() > 1)
            say("  AVISO: os comprimentos finais diferem; prefixo coincidente não prova recuperação completa.");
    }
    private int run(Path failures) throws IOException {
        List<Reading> readings = readings();
        for (int k = 0; k < n; k++) partition(k, readings, failures);
        for (String id : List.of("produtor", "processador")) {
            Map<String, Integer> counts = new TreeMap<>();
            for (String line : lines(folder.resolve("monitor-" + id + ".txt"), false)) {
                String[] f = line.split(";", 4);
                if (f.length != 4) throw new IOException("Monitor mal formado");
                Long.parseLong(f[0]); counts.merge(f[1], 1, Integer::sum);
            }
            say("MONITOR " + id + ": " + counts);
        }
        say("\ntermos_com_multiplos_lideres=" + multipleLeaders + ";termos_decrescentes=" + decreases
                + ";seq_divergentes=" + differences + ";leituras_perdidas=" + lost
                + ";registos_truncados=" + truncated + ";reaparecidos_outro_seq=" + reappeared);
        say("arranques_ms=" + startup + ";indisponibilidades_ms=" + downtime);
        say("Nota: sem ID de leitura, sensor+temperatura iguais são indistinguíveis; contagem por multiplicidade.");
        say("RESULTADO: " + (errors == 0 && differences == 0 ? "OK" : "ERROS"));
        Files.writeString(folder.resolve("relatorio-raft.txt"), report, StandardCharsets.UTF_8);
        String json = "{\"erros\":" + errors + ",\"multiplosLideres\":" + multipleLeaders + ",\"termosDecrescentes\":" + decreases
                + ",\"divergencias\":" + differences + ",\"perdidas\":" + lost + ",\"truncados\":" + truncated
                + ",\"reaparecidos\":" + reappeared + ",\"arranquesMs\":" + startup + ",\"indisponibilidadesMs\":" + downtime + "}";
        Files.writeString(folder.resolve("resumo-raft.json"), json, StandardCharsets.UTF_8);
        System.out.print(report);
        return errors == 0 && differences == 0 ? 0 : 1;
    }
    public static void main(String[] args) {
        try {
            if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("RaftCheck <N> <pasta> [falhas.txt]");
            int n = Integer.parseInt(args[0]); Path folder = Path.of(args[1]);
            if (n < 1 || !Files.isDirectory(folder)) throw new IllegalArgumentException("N ou pasta inválidos");
            Path failures = args.length == 3 ? Path.of(args[2]) : null;
            if (failures != null && !failures.isAbsolute()) failures = folder.resolve(failures);
            System.exit(new RaftCheck(n, folder).run(failures));
        } catch (IOException | RuntimeException e) { System.err.println("ERRO RaftCheck: " + e.getMessage()); System.exit(1); }
    }
}
