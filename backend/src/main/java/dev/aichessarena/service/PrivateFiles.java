package dev.aichessarena.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Small private files, published in one atomic rename; failures never disclose their contents. */
final class PrivateFiles {
    private PrivateFiles() {}

    static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file).trim() : null;
        } catch (IOException | SecurityException ignored) {
            throw new IllegalStateException("Cannot read instance configuration");
        }
    }

    static void write(Path file, String content) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent(), PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            temporary = Files.createTempFile(file.getParent(), ".config-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(temporary, content);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException ignored) {
            throw new IllegalStateException("Cannot save instance configuration");
        } finally {
            if (temporary != null) delete(temporary);
        }
    }

    static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | SecurityException ignored) {
            throw new IllegalStateException("Cannot remove instance configuration");
        }
    }
}
