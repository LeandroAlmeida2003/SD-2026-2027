package tpf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;

/**
 * E2-02: cria ou carrega a chave mestra em master.key, com acesso restrito ao dono.
 * É necessário conservar a mesma chave para abrir os chunks depois de reiniciar.
 */
final class MasterKey {
    // 32 bytes = 256 bits; esta chave não deve ser guardada no Git.
    private static final int KEY_BYTES = 32;

    private MasterKey() {
    }

    /** Tenta carregar; só cria outra chave se o ficheiro não existir. */
    static byte[] loadOrCreate(Path path) throws IOException {
        try {
            return load(path);
        } catch (NoSuchFileException exception) {
            return create(path);
        }
    }

    /** Exige exatamente 32 bytes; uma chave existente com tamanho errado dá erro. */
    private static byte[] load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            // Pedir um byte extra permite detetar também ficheiros demasiado grandes.
            byte[] key = input.readNBytes(KEY_BYTES + 1);
            if (key.length != KEY_BYTES) {
                throw new IOException("A chave existente tem de ter exatamente 32 bytes.");
            }
            return key;
        }
    }

    /** Gera a chave, cria o ficheiro protegido e força a escrita para disco. */
    private static byte[] create(Path path) throws IOException {
        byte[] key = new byte[KEY_BYTES];
        // Preenche os 32 bytes com aleatoriedade adequada à criação de chaves.
        new SecureRandom().nextBytes(key);

        try (FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                ownerOnlyPermissions(path))) {
            ByteBuffer buffer = ByteBuffer.wrap(key);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        // Se outro arranque criou a chave entretanto, carrega a que já existe.
        } catch (FileAlreadyExistsException exception) {
            return load(path);
        }
        return key;
    }

    /** Aplica as permissões logo na criação: POSIX quando disponível, ACL no Windows. */
    private static FileAttribute<?> ownerOnlyPermissions(Path path) throws IOException {
        var fileStore = Files.getFileStore(path.toAbsolutePath().getParent());
        if (fileStore.supportsFileAttributeView("posix")) {
            // rw-------: só o dono pode ler e escrever.
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        }
        if (!fileStore.supportsFileAttributeView("acl")) {
            throw new IOException("O sistema de ficheiros não permite restringir a leitura da chave ao dono.");
        }

        // No Windows, a lista de permissões permite acesso apenas ao utilizador dono.
        UserPrincipal owner = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        AclEntry entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(AclEntryPermission.values())
                .build();
        return new FileAttribute<List<AclEntry>>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return List.of(entry);
            }
        };
    }
}
