package rep03;

import java.time.LocalDateTime;

/** Registo imutável nos seus campos; o mesmo formato textual é usado no disco e na rede. */
public class SensorRecord {
    // private limita o acesso direto; final impede reatribuir o campo após o construtor.
    // seq é atribuído pelo líder; não é um contador independente de cada réplica.
    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;
    private final long lamport;
    private final VectorClock vector;

    // this distingue os campos do objeto dos parâmetros com o mesmo nome.
    public SensorRecord(long seq, String sensorId, double temperature, String timestamp, long lamport, VectorClock vector) {
        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector.copy();
    }

    // cria um registo com o instante atual (usado só pelo líder)
    // static: chamado pela classe, sem precisar de um objeto existente.
    // Hora local do líder, sem nanossegundos; não é um relógio lógico nem prova de ordem.
    public static SensorRecord now(long seq, String sensorId, double temperature, long lamport, VectorClock vector) {
        return new SensorRecord(seq, sensorId, temperature, LocalDateTime.now().withNano(0).toString(), lamport, vector);
    }

    // representação textual: é o que se guarda no ficheiro e o que se envia pela rede
    // Não usa Serializable: o transporte converte esta String em bytes UTF-8.
    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp + ";" + lamport + ";" + vector;
    }

    // Reconstrói os campos recebidos, conservando o instante original do líder.
    // Os índices começam em 0: sequência, sensor, temperatura, instante, Lamport, vetor.
    public static SensorRecord fromLine(String line, int n) {
        // trim remove espaços nas extremidades; split separa pelo ponto e vírgula.
        String[] f = line.trim().split(";");
        if (f.length != 6) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        // As conversões podem lançar NumberFormatException (subclasse de IllegalArgumentException).
        // Validação limitada à estrutura e números: não valida a data, nem impede NaN/Infinity.
        return new SensorRecord(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), f[3], Long.parseLong(f[4]), VectorClock.parse(f[5], n));
    }

    // Getters consultam o estado; não alteram os campos.
    public long getSeq() { return seq; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector.copy(); }
}
