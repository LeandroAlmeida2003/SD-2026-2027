package raft04;

import java.time.LocalDateTime;

/** Identidade no histórico: (seq, termo). O termo da eleição não é Lamport. */
public final class SensorRecord {
    private final long seq, term, lamport;
    private final String sensor, timestamp;
    private final double temperature;
    private final VectorClock vector;
    public SensorRecord(long seq, long term, String sensor, double temperature, String timestamp,
                        long lamport, VectorClock vector) {
        if (seq < 1 || term < 1 || lamport < 0 || sensor.isBlank() || sensor.contains(";")
                || !Double.isFinite(temperature)) throw new IllegalArgumentException("Registo inválido");
        LocalDateTime.parse(timestamp);
        this.seq = seq; this.term = term; this.sensor = sensor; this.temperature = temperature;
        this.timestamp = timestamp; this.lamport = lamport; this.vector = vector.copy();
    }
    public static SensorRecord now(long seq, long term, String sensor, double temperature, long l, VectorClock v) {
        return new SensorRecord(seq, term, sensor, temperature, LocalDateTime.now().toString(), l, v);
    }
    public String toLine() {
        return seq + ";" + term + ";" + sensor + ";" + temperature + ";" + timestamp + ";" + lamport + ";" + vector;
    }
    public static SensorRecord fromLine(String line, int n) {
        String[] f = line.split(";", -1);
        if (f.length != 7) throw new IllegalArgumentException("Registo deve ter sete campos: " + line);
        return new SensorRecord(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2], Double.parseDouble(f[3]),
                f[4], Long.parseLong(f[5]), VectorClock.parse(f[6], n));
    }
    public long getSeq() { return seq; }
    public long getTerm() { return term; }
    public String getSensorId() { return sensor; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector.copy(); }
}
