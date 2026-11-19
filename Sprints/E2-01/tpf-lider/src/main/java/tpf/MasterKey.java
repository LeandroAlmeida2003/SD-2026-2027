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

final class MasterKey {
    private static final int KEY_BYTES = 32;

    private MasterKey() {
    }

    static byte[] loadOrCreate(Path path) throws IOException {
        try {
            return load(path);
        } catch (NoSuchFileException exception) {
            return create(path);
        }
    }

    private static byte[] load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] key = input.readNBytes(KEY_BYTES + 1);
            if (key.length != KEY_BYTES) {
                throw new IOException("A chave existente tem de ter exatamente 32 bytes.");
            }
            return key;
        }
    }

    private static byte[] create(Path path) throws IOException {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);

        try (FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                ownerOnlyPermissions(path))) {
            ByteBuffer buffer = ByteBuffer.wrap(key);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (FileAlreadyExistsException exception) {
            return load(path);
        }
        return key;
    }

    private static FileAttribute<?> ownerOnlyPermissions(Path path) throws IOException {
        var fileStore = Files.getFileStore(path.toAbsolutePath().getParent());
        if (fileStore.supportsFileAttributeView("posix")) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        }
        if (!fileStore.supportsFileAttributeView("acl")) {
            throw new IOException("O sistema de ficheiros não permite restringir a leitura da chave ao dono.");
        }

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
