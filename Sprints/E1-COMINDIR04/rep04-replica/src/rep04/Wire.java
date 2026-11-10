package rep04;

public final class Wire {
    public static final double LIMIAR_ALARME = 28.0;
    public static final int T_TCP_MS = 5000;
    public static final int PORTO_BASE = 7000;
    public static final long PERIODO_HB_MS = Long.getLong("rep04.periodo", 5000);
    public static final int FATOR_TIMEOUT = Integer.getInteger("rep04.fator", 3);
    public static final int VERIFICACAO_MS = 1000;

    static {
        if (PERIODO_HB_MS <= 0 || FATOR_TIMEOUT <= 0) {
            throw new IllegalArgumentException("rep04.periodo e rep04.fator devem ser positivos");
        }
    }

    private Wire() { }

    public static String topicIn(int partition) { return "rep04-in-p" + partition; }
    public static String topicData(int partition) { return "rep04-p" + partition; }

    public static String in(String sensor, double temperature, long lamport, VectorClock vector) {
        return "IN;" + sensor + ";" + temperature + ";" + lamport + ";" + vector;
    }

    public static String type(String message) {
        int separator = message.indexOf(';');
        if (separator <= 0) throw new IllegalArgumentException("Mensagem sem tipo");
        return message.substring(0, separator);
    }

    public static String body(String message) {
        type(message);
        return message.substring(message.indexOf(';') + 1);
    }
}
