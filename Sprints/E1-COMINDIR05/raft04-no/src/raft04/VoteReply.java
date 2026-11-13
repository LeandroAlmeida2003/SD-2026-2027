package raft04;

public final class VoteReply {
    public final long term;
    public final int voter, candidate;
    public final boolean granted;
    public VoteReply(long term, int voter, int candidate, boolean granted) {
        if (term < 1 || voter < 1 || voter > Wire.GRUPO || candidate < 1 || candidate > Wire.GRUPO)
            throw new IllegalArgumentException("VOTO inválido");
        this.term = term; this.voter = voter; this.candidate = candidate; this.granted = granted;
    }
    public String encode() { return "VOTO;" + term + ";" + voter + ";" + candidate + ";" + (granted ? "S" : "N"); }
    public static VoteReply decode(String text) {
        String[] f = text.split(";", -1);
        if (f.length != 5 || !f[0].equals("VOTO") || !(f[4].equals("S") || f[4].equals("N")))
            throw new IllegalArgumentException("VOTO mal formado");
        return new VoteReply(Long.parseLong(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4].equals("S"));
    }
}
