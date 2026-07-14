package com.mrfloris.exportbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Exact byte count and SHA-256 digest for a rendered export file. */
record ContentFingerprint(long utf8Bytes, String sha256) {
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    ContentFingerprint {
        if (utf8Bytes < 0L) {
            throw new IllegalArgumentException("utf8Bytes must not be negative.");
        }
        if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters.");
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
    }

    static ContentFingerprint from(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fingerprint source is not a regular non-symbolic-link file: "
                    + safeFilename(path));
        }

        MessageDigest digest = sha256Digest();
        long bytes = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                try {
                    bytes = Math.addExact(bytes, read);
                } catch (ArithmeticException exception) {
                    throw new IOException("Fingerprint source is too large to count safely.", exception);
                }
                digest.update(buffer, 0, read);
            }
        }
        return new ContentFingerprint(bytes, HexFormat.of().formatHex(digest.digest()));
    }

    boolean matches(Path path) throws IOException {
        return equals(from(path));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("This Java runtime does not provide SHA-256.", exception);
        }
    }

    private static String safeFilename(Path path) {
        Path filename = path == null ? null : path.getFileName();
        return filename == null ? "<unknown>" : filename.toString();
    }
}
