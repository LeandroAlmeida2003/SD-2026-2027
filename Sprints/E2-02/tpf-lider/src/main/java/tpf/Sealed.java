package tpf;

import java.util.Base64;

/**
 * E2-02: guarda o resultado cifrado: ID, versão, salt, nonce e conteúdo com tag.
 * Os metadados públicos vão para .hdr e os dados cifrados para .bin.
 */
record Sealed(String id, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext) {
    // As cópias impedem que alterações nos arrays do chamador modifiquem este objeto.
    Sealed {
        salt = salt.clone();
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    /** Devolve uma cópia para proteger o salt guardado internamente. */
    @Override
    public byte[] salt() {
        return salt.clone();
    }

    /** Devolve uma cópia para proteger o nonce guardado internamente. */
    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    /** Devolve uma cópia para proteger os bytes cifrados guardados internamente. */
    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /** Formato do .hdr: versão decimal;salt em Base64;nonce em Base64. */
    String header() {
        Base64.Encoder encoder = Base64.getEncoder();
        return keyVersion + ";" + encoder.encodeToString(salt) + ";" + encoder.encodeToString(nonce);
    }

    /** Lê o cabeçalho; a validação criptográfica é feita depois por ChunkCrypto.open. */
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
