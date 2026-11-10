package rep04;

import java.util.ArrayList;
import java.util.List;

public final class Batch {
    public final String address;
    public final int port;
    public final long inc;
    public final long hb;
    public final long lastSeq;
    public final List<SensorRecord> records;

    public Batch(String address, int port, long inc, long hb, long lastSeq, List<SensorRecord> records) {
        new Header(address, port, inc, hb, lastSeq, records.size());
        this.address = address;
        this.port = port;
        this.inc = inc;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.records = List.copyOf(records);
    }

    public String encode() {
        StringBuilder message = new StringBuilder("LOTE;" + address + ";" + port + ";" + inc
                + ";" + hb + ";" + lastSeq + ";" + records.size());
        for (SensorRecord record : records) message.append('\n').append(record.toLine());
        return message.toString();
    }

    public static Header header(String message) {
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        String[] fields = line.strip().split(";", -1);
        if (fields.length != 7 || !fields[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho LOTE inválido");
        }
        return new Header(fields[1], Integer.parseInt(fields[2]), Long.parseLong(fields[3]),
                Long.parseLong(fields[4]), Long.parseLong(fields[5]), Integer.parseInt(fields[6]));
    }

    public static Batch decode(String message, int partitionCount) {
        Header header = header(message);
        String[] lines = message.strip().split("\\R", -1);
        if (lines.length - 1 != header.count) {
            throw new IllegalArgumentException("Número de registos diferente do cabeçalho");
        }
        List<SensorRecord> records = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            records.add(SensorRecord.fromLine(lines[index], partitionCount));
        }
        return new Batch(header.address, header.port, header.inc, header.hb, header.lastSeq, records);
    }

    public static final class Header {
        public final String address;
        public final int port;
        public final long inc;
        public final long hb;
        public final long lastSeq;
        public final int count;

        private Header(String address, int port, long inc, long hb, long lastSeq, int count) {
            if (address.isBlank() || address.contains(";") || address.contains("\n")
                    || port < 1 || port > 65535 || inc < 0 || hb < 1 || lastSeq < 0 || count < 0) {
                throw new IllegalArgumentException("Campos inválidos no cabeçalho LOTE");
            }
            this.address = address;
            this.port = port;
            this.inc = inc;
            this.hb = hb;
            this.lastSeq = lastSeq;
            this.count = count;
        }
    }
}
