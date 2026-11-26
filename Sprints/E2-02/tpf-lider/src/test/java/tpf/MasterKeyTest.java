package tpf;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** E2-02: testa tamanho, reutilização e permissões da chave criada numa pasta temporária. */
public class MasterKeyTest {
    // Cada teste isola os seus ficheiros; não altera os dados do bot nesta pasta.
    @Rule
    public final TemporaryFolder pasta = new TemporaryFolder();

    /** Exige 32 bytes, reutilização da mesma chave e acesso de leitura limitado ao dono. */
    @Test
    public void createsPrivateKeyAndLoadsItAgain() throws IOException {
        Path chave = pasta.getRoot().toPath().resolve("master.key");

        byte[] criada = MasterKey.loadOrCreate(chave);

        assertEquals(32, criada.length);
        assertEquals(32L, Files.size(chave));
        assertArrayEquals(criada, MasterKey.loadOrCreate(chave));
        if (Files.getFileStore(chave).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(chave));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(chave, AclFileAttributeView.class);
            var leitores = acl.getAcl().stream()
                    .filter(entrada -> entrada.type() == AclEntryType.ALLOW
                            && entrada.permissions().contains(AclEntryPermission.READ_DATA))
                    .map(entrada -> entrada.principal())
                    .collect(Collectors.toSet());
            assertEquals(Set.of(acl.getOwner()), leitores);
        }
    }

    /** Testa ficheiros com 31 e 33 bytes e verifica que são recusados sem substituir a chave. */
    @Test
    public void rejectsAnExistingKeyWithInvalidSize() throws IOException {
        Path chave = pasta.getRoot().toPath().resolve("master.key");
        for (int tamanho : new int[]{31, 33}) {
            byte[] invalida = new byte[tamanho];
            Files.write(chave, invalida);
            assertThrows(IOException.class, () -> MasterKey.loadOrCreate(chave));
            assertArrayEquals(invalida, Files.readAllBytes(chave));
        }
    }
}
