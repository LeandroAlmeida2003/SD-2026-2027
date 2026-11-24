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

final class ChunkCrypto {
    private static final int KEY_VERSION = 1;
    private static final byte[] AAD_PREFIX = "tpf-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO = "tpf-chunk-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private ChunkCrypto() {
    }

    static Sealed seal(byte[] masterKey, byte[] plaintext) throws GeneralSecurityException {
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, masterKey, salt, nonce).doFinal(plaintext);
        return new Sealed(hash(ciphertext), KEY_VERSION, salt, nonce, ciphertext);
    }

    static byte[] open(byte[] masterKey, Sealed sealed) throws GeneralSecurityException {
        if (!hash(sealed.ciphertext()).equals(sealed.id())) {
            throw new GeneralSecurityException("Hash do chunk incorreto.");
        }
        if (sealed.keyVersion() != KEY_VERSION) {
            throw new GeneralSecurityException("Versão da chave desconhecida.");
        }
        if (sealed.salt().length != 32 || sealed.nonce().length != 12) {
            throw new GeneralSecurityException("Salt ou nonce com tamanho inválido.");
        }
        return cipher(Cipher.DECRYPT_MODE, masterKey, sealed.salt(), sealed.nonce())
                .doFinal(sealed.ciphertext());
    }

    private static String hash(byte[] ciphertext) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ciphertext));
    }

    private static Cipher cipher(int mode, byte[] masterKey, byte[] salt, byte[] nonce)
            throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(masterKey);
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        hmac.update(INFO);
        byte[] dek = hmac.doFinal(new byte[]{1});

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(ByteBuffer.allocate(AAD_PREFIX.length + Integer.BYTES + salt.length + nonce.length)
                .put(AAD_PREFIX).putInt(KEY_VERSION).put(salt).put(nonce).array());
        return cipher;
    }
}
