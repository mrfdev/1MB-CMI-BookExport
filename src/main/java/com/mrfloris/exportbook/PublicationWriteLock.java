package com.mrfloris.exportbook;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * Coordinates live writes with other BookExport processes that use the same
 * published directory and lock protocol.
 */
final class PublicationWriteLock implements AutoCloseable {
    static final String FILENAME = ".bookexport-publication.lock";
    private static final ConcurrentMap<Path, Semaphore> PROCESS_GUARDS = new ConcurrentHashMap<>();

    private final FileChannel channel;
    private final FileLock lock;
    private final Semaphore processGuard;

    private PublicationWriteLock(FileChannel channel, FileLock lock, Semaphore processGuard) {
        this.channel = channel;
        this.lock = lock;
        this.processGuard = processGuard;
    }

    static <T> T withExclusiveLock(Path publishedDirectory, Operation<T> operation)
            throws IOException, BookExportException {
        Objects.requireNonNull(operation, "operation");
        PublicationWriteLock writeLock = acquire(publishedDirectory);
        T result;
        Throwable operationFailure = null;
        try {
            result = operation.run();
        } catch (IOException | BookExportException | RuntimeException | Error exception) {
            operationFailure = exception;
            throw exception;
        } finally {
            if (operationFailure != null) {
                try {
                    writeLock.close();
                } catch (IOException closeException) {
                    operationFailure.addSuppressed(closeException);
                }
            }
        }

        try {
            writeLock.close();
        } catch (IOException exception) {
            throw new BookExportException(
                    "The filesystem write may have completed, but its cross-process publication lock "
                            + "did not release cleanly. Do not retry until an administrator inspects "
                            + "the destination and restarts BookExport.",
                    exception
            );
        }
        return result;
    }

    private static PublicationWriteLock acquire(Path publishedDirectory)
            throws IOException, BookExportException {
        Path directory = Objects.requireNonNull(publishedDirectory, "publishedDirectory")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Published destination is not a regular directory.");
        }

        Path lockPath = directory.resolve(FILENAME);
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Publication lock sentinel is not a regular non-symbolic-link file.");
        }

        Semaphore processGuard = PROCESS_GUARDS.computeIfAbsent(lockPath, ignored -> new Semaphore(1));
        if (!processGuard.tryAcquire()) {
            throw busy();
        }

        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Publication lock sentinel changed while it was being opened.");
            }

            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw busy();
            } catch (UnsupportedOperationException exception) {
                throw new IOException("Filesystem does not support the publication lock protocol.", exception);
            }
            if (lock == null) {
                throw busy();
            }
            return new PublicationWriteLock(channel, lock, processGuard);
        } catch (IOException | BookExportException | RuntimeException | Error exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            processGuard.release();
            throw exception;
        }
    }

    private static BookExportException busy() {
        return new BookExportException(
                "Another BookExport writer is currently using this published destination. "
                        + "Wait for that publication to finish, then retry."
        );
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        } finally {
            processGuard.release();
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface Operation<T> {
        T run() throws IOException, BookExportException;
    }
}
