package tpf;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.Before;
import org.junit.Test;

public class ChunkCryptoTest {
    private final byte[] masterKey = new byte[32];
    private final byte[] plaintext = "Texto de teste".getBytes(StandardCharsets.UTF_8);
    private Sealed sealed;

    @Before
    public void seal() throws GeneralSecurityException {
        sealed = ChunkCrypto.seal(masterKey, plaintext);
    }

    @Test
    public void opensSealedChunk() throws GeneralSecurityException {
        assertArrayEquals(plaintext, ChunkCrypto.open(masterKey, sealed));
    }

    @Test
    public void rejectsChangedCiphertext() {
        rejects(new Sealed(sealed.id(), sealed.keyVersion(), sealed.salt(), sealed.nonce(),
                changed(sealed.ciphertext())));
    }

    @Test
    public void rejectsChangedSalt() {
        rejects(new Sealed(sealed.id(), sealed.keyVersion(), changed(sealed.salt()), sealed.nonce(),
                sealed.ciphertext()));
    }

    @Test
    public void rejectsChangedNonce() {
        rejects(new Sealed(sealed.id(), sealed.keyVersion(), sealed.salt(), changed(sealed.nonce()),
                sealed.ciphertext()));
    }

    @Test
    public void rejectsChangedVersion() {
        rejects(new Sealed(sealed.id(), sealed.keyVersion() + 1, sealed.salt(), sealed.nonce(),
                sealed.ciphertext()));
    }

    @Test
    public void rejectsDifferentMasterKey() {
        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(changed(masterKey), sealed));
    }

    private void rejects(Sealed modified) {
        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(masterKey, modified));
    }

    private static byte[] changed(byte[] bytes) {
        byte[] copy = bytes.clone();
        copy[0] ^= 1;
        return copy;
    }
}
