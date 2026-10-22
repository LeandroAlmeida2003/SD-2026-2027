package rep02;

import java.time.LocalDateTime;

/** Registo imutável nos seus campos; o mesmo formato textual é usado no disco e na rede. */
public class SensorRecord {
    // private limita o acesso direto; final impede reatribuir o campo após o construtor.
    // seq é atribuído pelo líder; não é um contador independente de cada réplica.
    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;

    // this distingue os campos do objeto dos parâmetros com o mesmo nome.
    public SensorRecord(long seq, String sensorId, double temperature, String timestamp) {
        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
    }

    // cria um registo com o instante atual (usado só pelo líder)
    // static: chamado pela classe, sem precisar de um objeto existente.
    // Hora local do líder, sem nanossegundos; não é um relógio lógico nem prova de ordem.
    public static SensorRecord now(long seq, String sensorId, double temperature) {
        return new SensorRecord(seq, sensorId, temperature, LocalDateTime.now().withNano(0).toString());
    }

    // representação textual: é o que se guarda no ficheiro e o que se envia pela rede
    // Não usa Serializable: o transporte converte esta String em bytes UTF-8.
    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp;
    }

    // Reconstrói os campos recebidos, conservando o instante original do líder.
    // Os índices começam em 0: sequência, sensor, temperatura, instante.
    public static SensorRecord fromLine(String line) {
        // trim remove espaços nas extremidades; split separa pelo ponto e vírgula.
        String[] f = line.trim().split(";");
        if (f.length != 4) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        // As conversões podem lançar NumberFormatException (subclasse de IllegalArgumentException).
        // Validação limitada à estrutura e números: não valida a data, nem impede NaN/Infinity.
        return new SensorRecord(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), f[3]);
    }

    // Getters consultam o estado; não alteram os campos.
    public long getSeq() { return seq; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
}
