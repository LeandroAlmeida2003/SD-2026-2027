package tpf;

/** E2-02: regras de tamanho e tempo usadas na selagem dos chunks. */
final class Wire {
    private static final String CHUNK_MAX_PROPERTY = "tpf.chunk.max.bytes";
    private static final String PAD_PROPERTY = "tpf.pad.bytes";
    private static final String SELAGEM_PROPERTY = "tpf.selagem.ms";

    // Limite que desencadeia a selagem; o registo que o ultrapassa ainda fica no chunk.
    static final int CHUNK_MAX_BYTES = readInt(CHUNK_MAX_PROPERTY, 65536);
    // Completa a serialização com zeros até um múltiplo deste número de bytes.
    static final int PAD_BYTES = readInt(PAD_PROPERTY, 4096);
    // Prazo em milissegundos desde o primeiro registo do chunk: 5000 ms = 5 segundos.
    static final int SELAGEM_MS = readInt(SELAGEM_PROPERTY, 5000);

    private Wire() {
    }

    /** Recusa parâmetros iguais a zero, negativos ou inválidos antes de arrancar. */
    static void validate() {
        requirePositive(CHUNK_MAX_PROPERTY, CHUNK_MAX_BYTES);
        requirePositive(PAD_PROPERTY, PAD_BYTES);
        requirePositive(SELAGEM_PROPERTY, SELAGEM_MS);
    }

    /** Lê uma opção -D; sem opção usa o valor predefinido, e texto inválido produz zero. */
    private static int readInt(String property, int defaultValue) {
        String value = System.getProperty(property);
        if (value == null) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** O zero devolvido por readInt também é recusado aqui. */
    private static void requirePositive(String property, int value) {
        if (value <= 0) {
            throw new IllegalStateException(property + " tem de ser um inteiro positivo.");
        }
    }
}
