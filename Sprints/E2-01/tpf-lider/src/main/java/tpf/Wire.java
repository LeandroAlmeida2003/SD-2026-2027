package tpf;

final class Wire {
    private static final String CHUNK_MAX_PROPERTY = "tpf.chunk.max.bytes";
    private static final String PAD_PROPERTY = "tpf.pad.bytes";
    private static final String SELAGEM_PROPERTY = "tpf.selagem.ms";

    static final int CHUNK_MAX_BYTES = readInt(CHUNK_MAX_PROPERTY, 65536);
    static final int PAD_BYTES = readInt(PAD_PROPERTY, 4096);
    static final int SELAGEM_MS = readInt(SELAGEM_PROPERTY, 5000);

    private Wire() {
    }

    static void validate() {
        requirePositive(CHUNK_MAX_PROPERTY, CHUNK_MAX_BYTES);
        requirePositive(PAD_PROPERTY, PAD_BYTES);
        requirePositive(SELAGEM_PROPERTY, SELAGEM_MS);
    }

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

    private static void requirePositive(String property, int value) {
        if (value <= 0) {
            throw new IllegalStateException(property + " tem de ser um inteiro positivo.");
        }
    }
}
