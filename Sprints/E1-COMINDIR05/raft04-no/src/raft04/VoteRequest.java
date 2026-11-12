package raft04;

public final class VoteRequest {
    public final long term, lastSeq, lastTerm;
    public final int candidate;
    public VoteRequest(long term, int candidate, long lastSeq, long lastTerm) {
        if (term < 1 || candidate < 1 || candidate > Wire.GRUPO || lastSeq < 0 || lastTerm < 0
                || lastTerm > term || (lastSeq == 0) != (lastTerm == 0))
            throw new IllegalArgumentException("PEDIDO_VOTO inválido");
        this.term = term; this.candidate = candidate; this.lastSeq = lastSeq; this.lastTerm = lastTerm;
    }
    public String encode() { return "PEDIDO_VOTO;" + term + ";" + candidate + ";" + lastSeq + ";" + lastTerm; }
    public static VoteRequest decode(String text) {
        String[] f = text.split(";", -1);
        if (f.length != 5 || !f[0].equals("PEDIDO_VOTO")) throw new IllegalArgumentException("PEDIDO_VOTO mal formado");
        return new VoteRequest(Long.parseLong(f[1]), Integer.parseInt(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]));
    }
}
