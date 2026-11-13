package raft04;

import java.util.function.Consumer;

public final class HeartbeatMonitor {
    public enum State { DESCONHECIDO, ATIVO, SUSPEITO }
    private final int partition;
    private final long timeoutNanos;
    private final Consumer<String> log;
    private State state = State.DESCONHECIDO;
    private long lastNanos = System.nanoTime(), term = -1, hb;
    private int leader;
    public HeartbeatMonitor(int partition, long periodMs, int factor, Consumer<String> log) {
        if (partition < 0 || periodMs <= 0 || factor <= 0) throw new IllegalArgumentException("Monitor inválido");
        this.partition = partition; this.log = log;
        timeoutNanos = Math.multiplyExact(Math.multiplyExact(periodMs, factor), 1_000_000L);
    }
    /** false significa heartbeat obsoleto/duplicado; não renova o prazo. */
    public synchronized boolean onHeartbeat(long term, int leader, long hb) {
        if (term < this.term) return false;
        if (term == this.term && leader != this.leader) {
            log.accept("ERRO;p" + partition + ";dois lideres no termo=" + term);
            return false;
        }
        if (term == this.term && hb <= this.hb) return false;
        if (term > this.term) log.accept("NOVO_LIDER;p" + partition + ";termo=" + term + ";lider=n" + leader);
        else if (hb != this.hb + 1) log.accept("HB_PERDIDO;p" + partition + ";esperado=" + (this.hb + 1) + ";recebido=" + hb);
        if (state == State.SUSPEITO) log.accept("RETOMA;p" + partition + ";termo=" + term + ";hb=" + hb);
        this.term = term; this.leader = leader; this.hb = hb;
        lastNanos = System.nanoTime(); state = State.ATIVO;
        return true;
    }
    public synchronized void check() {
        long elapsed = System.nanoTime() - lastNanos;
        if (state != State.SUSPEITO && elapsed > timeoutNanos) {
            state = State.SUSPEITO;
            log.accept("SUSPEITA;p" + partition + ";silencio_ms=" + elapsed / 1_000_000);
        }
    }
    public synchronized State state() { return state; }
    public synchronized long term() { return term; }
}
