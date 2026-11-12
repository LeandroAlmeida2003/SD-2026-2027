package raft04;

import java.util.*;

public final class Batch {
    public final long term, hb, lastSeq, lastTerm;
    public final int leader, port;
    public final String address;
    public final List<SensorRecord> records;
    public Batch(long term, int leader, String address, int port, long hb, long lastSeq, long lastTerm,
                 List<SensorRecord> records) {
        new Header(term, leader, address, port, hb, lastSeq, lastTerm, records.size());
        long previous = 0;
        for (SensorRecord r : records) {
            if (r.getSeq() <= previous || r.getSeq() > lastSeq || r.getTerm() > term)
                throw new IllegalArgumentException("Registos inválidos no lote");
            previous = r.getSeq();
        }
        this.term = term; this.leader = leader; this.address = address; this.port = port;
        this.hb = hb; this.lastSeq = lastSeq; this.lastTerm = lastTerm; this.records = List.copyOf(records);
    }
    public String encode() {
        StringBuilder out = new StringBuilder("LOTE;" + term + ";" + leader + ";" + address + ";" + port
                + ";" + hb + ";" + lastSeq + ";" + lastTerm + ";" + records.size());
        for (SensorRecord r : records) out.append('\n').append(r.toLine());
        return out.toString();
    }
    public static Header header(String message) {
        String[] f = message.split("\\R", 2)[0].split(";", -1);
        if (f.length != 9 || !f[0].equals("LOTE")) throw new IllegalArgumentException("Cabeçalho LOTE inválido");
        return new Header(Long.parseLong(f[1]), Integer.parseInt(f[2]), f[3], Integer.parseInt(f[4]),
                Long.parseLong(f[5]), Long.parseLong(f[6]), Long.parseLong(f[7]), Integer.parseInt(f[8]));
    }
    public static Batch decode(String message, int n) {
        Header h = header(message);
        String[] lines = message.strip().split("\\R");
        if (lines.length != h.count + 1) throw new IllegalArgumentException("Contagem de registos inválida");
        List<SensorRecord> records = new ArrayList<>();
        for (int j = 1; j < lines.length; j++) records.add(SensorRecord.fromLine(lines[j], n));
        return new Batch(h.term, h.leader, h.address, h.port, h.hb, h.lastSeq, h.lastTerm, records);
    }
    public static final class Header {
        public final long term, hb, lastSeq, lastTerm;
        public final int leader, port, count;
        public final String address;
        private Header(long term, int leader, String address, int port, long hb, long lastSeq, long lastTerm, int count) {
            if (term < 1 || leader < 1 || leader > Wire.GRUPO || address.isBlank() || address.contains(";")
                    || port < 1 || port > 65535 || hb < 1 || lastSeq < 0 || lastTerm < 0 || lastTerm > term
                    || (lastSeq == 0) != (lastTerm == 0) || count < 0 || count > lastSeq)
                throw new IllegalArgumentException("Campos inválidos no lote");
            this.term = term; this.leader = leader; this.address = address; this.port = port;
            this.hb = hb; this.lastSeq = lastSeq; this.lastTerm = lastTerm; this.count = count;
        }
    }
}
