package tpf;

import java.util.Base64;

record Sealed(String id, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext) {
    String header() {
        Base64.Encoder encoder = Base64.getEncoder();
        return keyVersion + ";" + encoder.encodeToString(salt) + ";" + encoder.encodeToString(nonce);
    }

    static Sealed read(String id, String header, byte[] ciphertext) {
        String[] fields = header.strip().split(";", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Cabeçalho inválido.");
        }
        Base64.Decoder decoder = Base64.getDecoder();
        return new Sealed(id, Integer.parseInt(fields[0]), decoder.decode(fields[1]),
                decoder.decode(fields[2]), ciphertext);
    }
}
