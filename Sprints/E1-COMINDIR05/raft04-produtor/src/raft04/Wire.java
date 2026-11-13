package raft04;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Wire {
    public static final double LIMIAR_ALARME = 28;
    public static final int T_TCP_MS = 5000, PORTO_BASE = 7100, GRUPO = 3;
    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000);
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3);
    public static final int VERIFICACAO_MS = 200;
    public static final long ELEICAO_MIN_MS = Long.getLong("raft04.eleicaoMin", 3 * PERIODO_HB_MS);
    public static final long ELEICAO_MAX_MS = Long.getLong("raft04.eleicaoMax", 6 * PERIODO_HB_MS);
    static {
        if (PERIODO_HB_MS <= 0 || FATOR_TIMEOUT <= 0 || ELEICAO_MIN_MS <= 0
                || ELEICAO_MAX_MS < ELEICAO_MIN_MS) throw new IllegalArgumentException("Temporizadores inválidos");
    }
    private Wire() { }
    public static String topicIn(int k) { return "raft04-in-p" + k; }
    public static String topicData(int k) { return "raft04-p" + k; }
    public static String topicElection(int k) { return "raft04-eleicao-p" + k; }
    public static int nodePort(int k, int i) { return PORTO_BASE + k * GRUPO + i - 1; }
    public static boolean isolated(int k, int i) { return Files.exists(Path.of("isolar-p" + k + "-n" + i)); }
    public static String in(String sensor, double temp, long l, VectorClock v) {
        return "IN;" + sensor + ";" + temp + ";" + l + ";" + v;
    }
    public static String type(String message) {
        int pos = message.indexOf(';');
        if (pos <= 0) throw new IllegalArgumentException("Mensagem sem tipo");
        return message.substring(0, pos);
    }
    public static String body(String message) { type(message); return message.substring(message.indexOf(';') + 1); }
}
