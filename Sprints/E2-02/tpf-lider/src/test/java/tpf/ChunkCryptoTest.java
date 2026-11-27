package tpf;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.AEADBadTagException;
import org.junit.Before;
import org.junit.Test;

/** E2-02: testa cifra e rejeição de alterações com uma chave sintética, sem usar master.key real. */
public class ChunkCryptoTest {
    private final byte[] masterKey = new byte[32];
    private final byte[] plaintext = "Texto de teste".getBytes(StandardCharsets.UTF_8);
    private Sealed sealed;

    /** Prepara um chunk cifrado novo antes de cada teste. */
    @Before
    public void seal() throws GeneralSecurityException {
        sealed = ChunkCrypto.seal(masterKey, plaintext);
    }

    /** A abertura com a chave certa tem de devolver exatamente os bytes originais. */
    @Test
    public void opensSealedChunk() throws GeneralSecurityException {
        assertArrayEquals(plaintext, ChunkCrypto.open(masterKey, sealed));
    }

    /** Alterar os bytes sem atualizar o ID tem de falhar a verificação do hash. */
    @Test
    public void rejectsChangedCiphertext() {
        rejects(new Sealed(sealed.id(), sealed.keyVersion(), sealed.salt(), sealed.nonce(),
                changed(sealed.ciphertext())));
    }

    /** Um salt alterado impede obter uma abertura autenticada válida. */
    @Test
    public void rejectsChangedSalt() {
        rejects(new Sealed(sealed.id(), sealed.keyVersion(), changed(sealed.salt()), sealed.nonce(),
                sealed.ciphertext()));
    }

    /** Um nonce alterado tem de fazer falhar a autenticação GCM. */
    @Test
    public void rejectsChangedNonce() {
        rejects(new Sealed(sealed.id(), sealed.keyVersion(), sealed.salt(), changed(sealed.nonce()),
                sealed.ciphertext()));
    }

    /** Uma versão diferente da suportada tem de ser recusada. */
    @Test
    public void rejectsChangedVersion() {
        rejects(new Sealed(sealed.id(), sealed.keyVersion() + 1, sealed.salt(), sealed.nonce(),
                sealed.ciphertext()));
    }

    /** Outra chave mestra não pode abrir o chunk. */
    @Test
    public void rejectsDifferentMasterKey() {
        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(changed(masterKey), sealed));
    }

    /** Mesmo com o hash recalculado, a alteração é detetada pela tag GCM. */
    @Test
    public void rejectsTamperingEvenWhenTheAttackerRecomputesTheHash() throws Exception {
        byte[] ciphertext = changed(sealed.ciphertext());
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ciphertext));
        Sealed modified = new Sealed(id, sealed.keyVersion(), sealed.salt(), sealed.nonce(), ciphertext);
        assertThrows(AEADBadTagException.class, () -> ChunkCrypto.open(masterKey, modified));
    }

    /** Exige uma exceção ao tentar abrir um chunk adulterado. */
    private void rejects(Sealed modified) {
        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(masterKey, modified));
    }

    /** Cria uma cópia e altera um bit, conservando o array original. */
    private static byte[] changed(byte[] bytes) {
        byte[] copy = bytes.clone();
        copy[0] ^= 1;
        return copy;
    }
}
