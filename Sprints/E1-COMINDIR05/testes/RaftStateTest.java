package raft04;

import java.nio.file.*;

/** Testes de segurança determinísticos, sem rede nem bibliotecas de teste. */
public final class RaftStateTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static RaftState state(Path p, int self) throws Exception {
        return new RaftState(0, self, 3, 1, 1, p, ignored -> {});
    }
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("raft-state-test-");
        RaftState a = state(folder.resolve("a.txt"), 1);
        Thread.sleep(5); long term = a.tick();
        require(term == 1 && Files.readString(folder.resolve("a.txt")).trim().equals("1;1"), "Candidatura deve persistir termo e voto");
        require(a.role() == RaftState.Role.CANDIDATO, "Um voto não é maioria");
        require(!a.onVote(1, 1, 1, true), "Voto duplicado não pode eleger");
        require(a.onVote(1, 2, 1, true), "Dois votos distintos elegem");
        require(!a.onVote(1, 3, 1, true), "Transição a líder deve ocorrer só uma vez");
        require(!a.onBatch(0, 2), "Lote obsoleto deve ser ignorado");
        a.onVote(2, 2, 3, false);
        require(a.role() == RaftState.Role.SEGUIDOR && a.term() == 2, "Termo superior demite mesmo num voto para outro candidato");
        RaftState.Vote v = a.onVoteRequest(2, 2, 4, 1, 5, 1);
        require(!v.granted, "Candidato com seq menor no mesmo último termo deve ser recusado");
        v = a.onVoteRequest(2, 2, 4, 2, 5, 1);
        require(v.granted, "Último termo tem precedência sobre seq");
        require(a.onVoteRequest(2, 2, 4, 2, 5, 1).granted, "Repetição do mesmo pedido é idempotente");
        require(!a.onVoteRequest(2, 3, 4, 2, 5, 1).granted, "Não pode votar em dois candidatos no mesmo termo");
        RaftState restarted = state(folder.resolve("a.txt"), 1);
        require(restarted.term() == 2 && !restarted.onVoteRequest(2, 3, 4, 2, 5, 1).granted, "Reinício deve preservar voto");
        require(restarted.onVoteRequest(3, 3, 5, 2, 5, 2).granted, "Novo termo permite novo voto");
        RaftState candidate = state(folder.resolve("c.txt"), 3);
        Thread.sleep(5); candidate.tick();
        require(candidate.onBatch(1, 2) && candidate.role() == RaftState.Role.SEGUIDOR, "LOTE do mesmo termo faz candidato reconhecer líder");
        SensorRecord r = new SensorRecord(1, 2, "S01", 21, "2026-11-10T12:00:00", 1, VectorClock.parse("1,0,0", 3));
        Batch b = new Batch(2, 1, "127.0.0.1", 7100, 1, 1, 2, java.util.List.of(r));
        require(Batch.decode(b.encode(), 3).records.get(0).toLine().equals(r.toLine()), "Codec LOTE deve preservar todos os campos");
        require(VoteRequest.decode(new VoteRequest(2, 1, 1, 2).encode()).lastTerm == 2, "Codec pedido");
        require(VoteReply.decode(new VoteReply(2, 2, 1, false).encode()).granted == false, "Codec voto");
        RecordFile file = new RecordFile(folder.resolve("records.txt").toString(), 3);
        file.append(r); file.replace(java.util.List.of());
        require(file.lastSeq() == 0 && Files.readString(folder.resolve("records.txt")).isEmpty(), "Truncagem atómica deve alterar memória e disco");
        HeartbeatMonitor monitor = new HeartbeatMonitor(0, 2000, 3, ignored -> {});
        require(monitor.onHeartbeat(2, 1, 1), "Primeiro heartbeat");
        require(!monitor.onHeartbeat(1, 2, 100) && monitor.term() == 2, "Heartbeat antigo não altera monitor");
        System.out.println("OK: persistência, voto único, maioria, demissão, codecs, truncagem e monitor");
    }
}
