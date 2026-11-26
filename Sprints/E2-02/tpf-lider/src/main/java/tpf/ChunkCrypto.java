package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * E2-02: deriva uma chave para cada chunk, cifra com AES-GCM e verifica a abertura.
 * MK é a chave mestra; PRK é uma chave intermédia; DEK é a chave que cifra o chunk.
 */
final class ChunkCrypto {
    // Versão suportada neste sprint; também é autenticada no AAD.
    private static final int KEY_VERSION = 1;
    // Identifica o formato dos metadados autenticados, que não são cifrados.
    private static final byte[] AAD_PREFIX = "tpf-v1".getBytes(StandardCharsets.UTF_8);
    // Contexto usado no HKDF para indicar a finalidade da chave derivada.
    private static final byte[] INFO = "tpf-chunk-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private ChunkCrypto() {
    }

    /** Recebe dados já com padding e devolve ID, metadados e cifra com a tag. */
    static Sealed seal(byte[] masterKey, byte[] plaintext) throws GeneralSecurityException {
        // Salt: 32 bytes aleatórios usados na derivação da chave deste chunk.
        byte[] salt = new byte[32];
        // Nonce: 12 bytes usados no AES-GCM; não deve repetir-se com a mesma chave.
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        // doFinal cifra e acrescenta a tag GCM de 16 bytes ao resultado.
        byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, masterKey, salt, nonce).doFinal(plaintext);
        return new Sealed(hash(ciphertext), KEY_VERSION, salt, nonce, ciphertext);
    }

    /** Verifica hash, versão, tamanhos e autenticação antes de devolver dados decifrados. */
    static byte[] open(byte[] masterKey, Sealed sealed) throws GeneralSecurityException {
        byte[] ciphertext = sealed.ciphertext();
        // 1: confirma que os bytes recebidos correspondem ao ID esperado.
        if (!hash(ciphertext).equals(sealed.id())) {
            throw new GeneralSecurityException("Hash do chunk incorreto.");
        }
        // 2: recusa versões para as quais este programa não tem suporte.
        if (sealed.keyVersion() != KEY_VERSION) {
            throw new GeneralSecurityException("Versão da chave desconhecida.");
        }
        byte[] salt = sealed.salt();
        byte[] nonce = sealed.nonce();
        if (salt.length != 32 || nonce.length != 12) {
            throw new GeneralSecurityException("Salt ou nonce com tamanho inválido.");
        }
        // 3: doFinal verifica a tag; chave, conteúdo ou metadados errados fazem-no falhar.
        return cipher(Cipher.DECRYPT_MODE, masterKey, salt, nonce).doFinal(ciphertext);
    }

    /** SHA-256 da cifra com tag, em hexadecimal: é o nome que identifica o chunk. */
    private static String hash(byte[] ciphertext) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ciphertext));
    }

    /** Prepara a mesma derivação e o mesmo AAD para cifrar ou decifrar. */
    private static Cipher cipher(int mode, byte[] masterKey, byte[] salt, byte[] nonce)
            throws GeneralSecurityException {
        // HKDF, extração: PRK = HMAC-SHA256(salt, masterKey).
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(masterKey);
        // HKDF, expansão: DEK = HMAC-SHA256(PRK, INFO || 0x01).
        // 0x01 identifica o primeiro bloco; este bloco já fornece os 32 bytes pedidos.
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        hmac.update(INFO);
        byte[] dek = hmac.doFinal(new byte[]{1});

        // AES com DEK de 32 bytes = 256 bits; GCM com tag de 128 bits = 16 bytes.
        // NoPadding refere-se à cifra: o padding da aplicação já foi feito em Chunk.
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, nonce));
        // AAD = prefixo + versão (4 bytes big-endian) + salt + nonce.
        // Os metadados são autenticados: alterá-los impede uma abertura válida.
        cipher.updateAAD(ByteBuffer.allocate(AAD_PREFIX.length + Integer.BYTES + salt.length + nonce.length)
                .put(AAD_PREFIX).putInt(KEY_VERSION).put(salt).put(nonce).array());
        return cipher;
    }
}
