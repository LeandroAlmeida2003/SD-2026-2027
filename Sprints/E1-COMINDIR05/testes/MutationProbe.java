package raft04;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Contraprovas dirigidas; não são medições de latência de uma rede. */
public final class MutationProbe {
    private static final Map<Integer, MonitorLog> logs = new HashMap<>();
    private static RaftState state(int i) throws Exception {
        Path path = Path.of("estado-p0-n" + i + ".txt");
        if (!Files.exists(path)) Files.writeString(path, "0;0\n");
        MonitorLog log = logs.computeIfAbsent(i, key -> {
            try { return new MonitorLog(Path.of("eventos-p0-n" + key + ".txt")); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        RaftState s = new RaftState(0, i, 3, 1, 1, Path.of("estado-p0-n" + i + ".txt"), log::event);
        log.event("TERMO;p0;arranque;termo=" + s.term());
        return s;
    }
    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("CONTRAPROVA: " + description);
    }
    private static SensorRecord record(long seq, long term, long l, long v) {
        return new SensorRecord(seq, term, "S01", 20 + seq, "2026-11-10T12:00:00", l, VectorClock.parse("" + v, 1));
    }
    public static void main(String[] args) throws Exception {
        try {
            Files.writeString(Path.of("produtor.txt"), "");
            for (int i = 1; i <= 3; i++) Files.writeString(Path.of("no-p0-n" + i + ".txt"), "");
            RaftState a = state(1), b = state(2), c = state(3);
            switch (args[0]) {
                case "maioria":
                    Thread.sleep(5); a.tick(); b.tick();
                    // A API deve continuar segura perante votos duplicados, mesmo que a rede
                    // normal filtre mensagens de origem própria antes de chamar onVote.
                    require(a.onVote(1, 1, 1, true) && b.onVote(1, 2, 2, true), "um único voto elege dois candidatos no termo 1");
                    break;
                case "persistencia":
                    Thread.sleep(5); a.tick(); b.tick();
                    require(c.onVoteRequest(1, 1, 0, 0, 0, 0).granted, "n3 votou em n1");
                    a.onVote(1, 3, 1, true);
                    c = state(3); // Reinício real da máquina de estados: volta a ler o disco.
                    require(c.onVoteRequest(1, 2, 0, 0, 0, 0).granted, "reinício sem persistência permitiu segundo voto");
                    b.onVote(1, 3, 2, true);
                    break;
                case "atualizacao":
                    Thread.sleep(5); c.tick();
                    require(a.onVoteRequest(1, 3, 1, 1, 2, 1).granted, "nó com seq 1 foi votado por nó com seq 2 do mesmo termo");
                    c.onVote(1, 1, 3, true);
                    Files.writeString(Path.of("no-p0-n1.txt"), record(1, 1, 1, 1).toLine() + "\n" + record(2, 1, 2, 2).toLine() + "\n");
                    Files.writeString(Path.of("no-p0-n2.txt"), Files.readString(Path.of("no-p0-n1.txt")));
                    Files.writeString(Path.of("no-p0-n3.txt"), record(1, 1, 1, 1).toLine() + "\n");
                    break;
                case "obsoleto":
                    Thread.sleep(5); c.tick();
                    c.onVote(1, 2, 3, true);
                    b.onVoteRequest(1, 3, 0, 0, 0, 0);
                    Thread.sleep(5); b.tick();
                    a.onVoteRequest(2, 2, 0, 0, 0, 0);
                    b.onVote(2, 1, 2, true);
                    require(a.onBatch(1, 3), "seguidor do termo 2 aceitou LOTE do termo 1");
                    Files.writeString(Path.of("no-p0-n1.txt"), record(1, 1, 1, 1).toLine() + "\n");
                    for (int i = 2; i <= 3; i++) Files.writeString(Path.of("no-p0-n" + i + ".txt"), record(1, 2, 1, 1).toLine() + "\n");
                    break;
                case "relogios":
                    Files.writeString(Path.of("no-p0-n1.txt"), record(1, 1, 5, 1).toLine() + "\n");
                    // Executa os handlers reais do nó; não reimplementa a inicialização.
                    PartitionNode node = new PartitionNode(0, 1, 1, "127.0.0.1", "http://127.0.0.1:15999/api/v0");
                    Field field = PartitionNode.class.getDeclaredField("raft"); field.setAccessible(true);
                    RaftState raft = (RaftState)field.get(node);
                    Thread.sleep(5); raft.tick();
                    Method election = PartitionNode.class.getDeclaredMethod("onElection", String.class); election.setAccessible(true);
                    election.invoke(node, "VOTO;1;2;1;S");
                    Method input = PartitionNode.class.getDeclaredMethod("onInput", String.class); input.setAccessible(true);
                    input.invoke(node, "IN;S01;22.0;0;0");
                    SensorRecord last = SensorRecord.fromLine(Files.readAllLines(Path.of("no-p0-n1.txt")).get(1), 1);
                    require(last.getVector().get(0) != last.getSeq(), "relógio reiniciado a zero viola V[k]=seq");
                    for (int i = 2; i <= 3; i++) Files.writeString(Path.of("no-p0-n" + i + ".txt"), Files.readString(Path.of("no-p0-n1.txt")));
                    break;
                default: throw new IllegalArgumentException("Mutação desconhecida");
            }
            System.exit(0);
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }
}
