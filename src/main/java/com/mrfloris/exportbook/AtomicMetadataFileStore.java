package com.mrfloris.exportbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Durable atomic byte storage with fail-closed compare-and-swap semantics. */
final class AtomicMetadataFileStore {
    private static final String TEMPORARY_PREFIX = ".bookexport-transaction-";
    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final ConcurrentMap<Path, Object> PATH_LOCKS = new ConcurrentHashMap<>();

    void initializeDirectory(Path directory) throws IOException {
        Path normalized = normalized(directory);
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Metadata directory requires a parent.");
        }
        boolean existed = Files.exists(normalized, LinkOption.NOFOLLOW_LINKS);
        if (!existed) {
            Files.createDirectories(normalized);
        }
        requireDirectory(normalized);
        forceDirectory(normalized);
        if (!existed) {
            forceDirectory(parent);
        }
    }

    void create(Path target, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Path normalized = normalized(target);
        synchronized (lock(normalized)) {
            Path parent = requireParent(normalized);
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Metadata target already exists.");
            }
            writeAtomically(normalized, bytes, null, false, parent);
        }
    }

    void replace(Path target, byte[] expected, byte[] replacement) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(replacement, "replacement");
        Path normalized = normalized(target);
        synchronized (lock(normalized)) {
            Path parent = requireParent(normalized);
            requireExpected(normalized, expected);
            writeAtomically(normalized, replacement, expected, true, parent);
        }
    }

    void delete(Path target, byte[] expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Path normalized = normalized(target);
        synchronized (lock(normalized)) {
            Path parent = requireParent(normalized);
            requireExpected(normalized, expected);
            Files.delete(normalized);
            forceDirectory(parent);
        }
    }

    byte[] read(Path target, int maximumBytes) throws IOException {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative.");
        }
        Path normalized = normalized(target);
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Metadata target is not a regular non-symbolic-link file.");
        }
        try (InputStream input = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                try {
                    total = Math.addExact(total, read);
                } catch (ArithmeticException exception) {
                    throw new IOException("Metadata target is too large.", exception);
                }
                if (total > maximumBytes) {
                    throw new IOException("Metadata target exceeds its storage limit.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void writeAtomically(
            Path target,
            byte[] replacement,
            byte[] expected,
            boolean replace,
            Path parent
    ) throws IOException {
        Path temporary = Files.createTempFile(parent, TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
        byte[] reservation = null;
        boolean reservationActive = false;
        Throwable failure = null;
        try {
            writeAndForce(temporary, replacement);
            if (replace) {
                requireExpected(target, expected);
            } else {
                reservation = createReservation(target);
                reservationActive = true;
                forceDirectory(parent);
                if (!reservationMatches(target, reservation)) {
                    throw new IOException("Metadata reservation changed concurrently.");
                }
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Filesystem cannot atomically store metadata.", exception);
            } catch (FileAlreadyExistsException exception) {
                throw new IOException("Metadata target was claimed concurrently.", exception);
            }
            reservationActive = false;
            forceDirectory(parent);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                boolean changed = Files.deleteIfExists(temporary);
                if (reservationActive && reservationMatches(target, reservation)) {
                    changed |= Files.deleteIfExists(target);
                }
                if (changed) {
                    forceDirectory(parent);
                }
            } catch (IOException cleanupException) {
                if (failure != null) {
                    failure.addSuppressed(cleanupException);
                } else {
                    throw cleanupException;
                }
            }
        }
    }

    private static byte[] createReservation(Path target) throws IOException {
        byte[] marker = ("BookExport transaction reservation " + UUID.randomUUID())
                .getBytes(StandardCharsets.US_ASCII);
        boolean created = false;
        boolean complete = false;
        Throwable failure = null;
        try {
            try (FileChannel channel = FileChannel.open(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                created = true;
                ByteBuffer buffer = ByteBuffer.wrap(marker);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            complete = true;
            return marker;
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (created && !complete) {
                try {
                    deletePartialReservationIfOwned(target, marker);
                } catch (IOException cleanupException) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupException);
                    } else {
                        throw cleanupException;
                    }
                }
            }
        }
    }

    private static void requireExpected(Path target, byte[] expected) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) != expected.length
                || !Arrays.equals(Files.readAllBytes(target), expected)) {
            throw new IOException("Metadata changed concurrently; operation fails closed.");
        }
    }

    private static boolean reservationMatches(Path target, byte[] marker) throws IOException {
        return marker != null
                && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && Files.size(target) == marker.length
                && Arrays.equals(Files.readAllBytes(target), marker);
    }

    private static void deletePartialReservationIfOwned(Path target, byte[] marker)
            throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long size = Files.size(target);
        if (size < 0L || size > marker.length) {
            return;
        }
        byte[] existing = Files.readAllBytes(target);
        for (int index = 0; index < existing.length; index++) {
            if (existing[index] != marker[index]) {
                return;
            }
        }
        Files.deleteIfExists(target);
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static Path requireParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Metadata target requires a parent directory.");
        }
        requireDirectory(parent);
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Metadata target may not be a symbolic link.");
        }
        return parent;
    }

    private static void requireDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Metadata parent is not a regular directory.");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        requireDirectory(directory);
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("Filesystem cannot durably synchronize metadata directories.", exception);
        }
    }

    private static Object lock(Path target) {
        Path scope = target.getParent() == null ? target : target.getParent();
        return PATH_LOCKS.computeIfAbsent(scope, ignored -> new Object());
    }

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
