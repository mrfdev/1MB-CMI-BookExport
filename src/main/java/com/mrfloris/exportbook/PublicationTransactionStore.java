package com.mrfloris.exportbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Durable compare-and-swap store for publication transaction journals. */
final class PublicationTransactionStore {
    static final String JOURNAL_SUFFIX = ".bookexport-transaction.properties";
    static final String TRANSACTION_SUFFIX = JOURNAL_SUFFIX;

    private final Path directory;
    private final Clock clock;
    private final PublicationTransactionCodec codec;
    private final AtomicMetadataFileStore files;

    PublicationTransactionStore(Path directory) {
        this(directory, Clock.systemUTC());
    }

    PublicationTransactionStore(Path directory, Clock clock) {
        this(directory, clock, new PublicationTransactionCodec(), new AtomicMetadataFileStore());
    }

    PublicationTransactionStore(
            Path directory,
            Clock clock,
            PublicationTransactionCodec codec,
            AtomicMetadataFileStore files
    ) {
        this.directory = normalized(directory);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.files = Objects.requireNonNull(files, "files");
    }

    synchronized void initialize() throws IOException {
        files.initializeDirectory(directory);
    }

    synchronized PublicationTransaction create(PublicationTransaction transaction)
            throws IOException {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.state() != PublicationTransactionState.PREPARED
                || transaction.revision() != 1L
                || !transaction.createdAt().equals(transaction.updatedAt())) {
            throw new IOException("A new transaction must be its first durable prepared revision.");
        }
        initialize();
        Path target = pathFor(transaction.transactionId());
        requireNoCaseInsensitiveMatch(target);
        files.create(target, codec.encode(transaction));
        PublicationTransaction stored = codec.read(target);
        if (!stored.equals(transaction)) {
            throw new IOException("Created transaction failed read-after-write verification.");
        }
        return stored;
    }

    synchronized PublicationTransaction update(
            PublicationTransaction expected,
            PublicationTransactionState next
    ) throws IOException {
        Objects.requireNonNull(expected, "expected");
        PublicationTransaction replacement;
        try {
            replacement = expected.withState(next, clock.instant());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Invalid publication transaction state transition.", exception);
        }
        Path target = pathFor(expected.transactionId());
        byte[] expectedBytes = codec.encode(expected);
        files.replace(target, expectedBytes, codec.encode(replacement));
        PublicationTransaction stored = codec.read(target);
        if (!stored.equals(replacement)) {
            throw new IOException("Updated transaction failed read-after-write verification.");
        }
        return stored;
    }

    synchronized void delete(PublicationTransaction expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        files.delete(pathFor(expected.transactionId()), codec.encode(expected));
    }

    synchronized Optional<PublicationTransaction> find(UUID transactionId) throws IOException {
        Objects.requireNonNull(transactionId, "transactionId");
        List<JournalEntry> entries = scan();
        requireAllReadable(entries);
        return entries.stream()
                .map(JournalEntry::transaction)
                .filter(transaction -> transaction.transactionId().equals(transactionId))
                .findFirst();
    }

    synchronized List<PublicationTransaction> list() throws IOException {
        List<JournalEntry> entries = scan();
        requireAllReadable(entries);
        return entries.stream()
                .map(JournalEntry::transaction)
                .sorted(Comparator
                        .comparing(PublicationTransaction::updatedAt)
                        .reversed()
                        .thenComparing(transaction -> transaction.transactionId().toString()))
                .toList();
    }

    /**
     * Reads every journal candidate while reducing entry-level failures to a
     * stable category. No parser exception message or untrusted metadata is
     * exposed through the result.
     */
    synchronized List<JournalEntry> scan() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Publication transaction path is not a regular directory.");
        }

        List<Path> candidates;
        try (Stream<Path> paths = Files.list(directory)) {
            candidates = paths
                    .filter(path -> isJournalCandidate(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        List<JournalEntry> entries = new ArrayList<>(candidates.size());
        Map<UUID, Integer> decodedCounts = new HashMap<>();
        for (Path candidate : candidates) {
            JournalEntry entry = readEntry(candidate);
            entries.add(entry);
            if (entry.readable()) {
                decodedCounts.merge(entry.transaction().transactionId(), 1, Integer::sum);
            }
        }
        for (int index = 0; index < entries.size(); index++) {
            JournalEntry entry = entries.get(index);
            if (entry.readable()
                    && decodedCounts.getOrDefault(entry.transaction().transactionId(), 0) > 1) {
                entries.set(index, JournalEntry.failed(
                        entry.path(),
                        entry.filenameTransactionId(),
                        JournalReadFailure.DUPLICATE_TRANSACTION_ID
                ));
            }
        }
        return List.copyOf(entries);
    }

    Path pathFor(UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        return directory.resolve(transactionId + JOURNAL_SUFFIX);
    }

    Path directory() {
        return directory;
    }

    private JournalEntry readEntry(Path candidate) {
        UUID filenameId = transactionIdFromFilename(candidate.getFileName().toString());
        if (filenameId == null) {
            return JournalEntry.failed(
                    candidate,
                    null,
                    JournalReadFailure.MALFORMED_FILENAME
            );
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return JournalEntry.failed(
                    candidate,
                    filenameId,
                    JournalReadFailure.NOT_REGULAR_FILE
            );
        }
        PublicationTransaction transaction;
        try {
            transaction = codec.read(candidate);
        } catch (IOException | RuntimeException exception) {
            return JournalEntry.failed(
                    candidate,
                    filenameId,
                    JournalReadFailure.UNREADABLE_OR_MALFORMED
            );
        }
        if (!transaction.transactionId().equals(filenameId)) {
            return JournalEntry.failed(
                    candidate,
                    filenameId,
                    JournalReadFailure.TRANSACTION_ID_MISMATCH
            );
        }
        return JournalEntry.readable(candidate, filenameId, transaction);
    }

    private static void requireAllReadable(List<JournalEntry> entries) throws IOException {
        if (entries.stream().anyMatch(entry -> !entry.readable())) {
            throw new IOException(
                    "Publication transaction directory contains an unreadable or ambiguous journal."
            );
        }
    }

    private void requireNoCaseInsensitiveMatch(Path expected) throws IOException {
        String expectedFilename = expected.getFileName().toString();
        try (Stream<Path> paths = Files.list(directory)) {
            if (paths.anyMatch(path -> path.getFileName().toString()
                    .equalsIgnoreCase(expectedFilename))) {
                throw new IOException("Publication transaction journal already exists.");
            }
        }
    }

    private static boolean isJournalCandidate(String filename) {
        return filename.length() > JOURNAL_SUFFIX.length()
                && filename.regionMatches(
                        true,
                        filename.length() - JOURNAL_SUFFIX.length(),
                        JOURNAL_SUFFIX,
                        0,
                        JOURNAL_SUFFIX.length()
                );
    }

    private static UUID transactionIdFromFilename(String filename) {
        if (filename.length() <= JOURNAL_SUFFIX.length()
                || !filename.endsWith(JOURNAL_SUFFIX)) {
            return null;
        }
        String value = filename.substring(0, filename.length() - JOURNAL_SUFFIX.length());
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    enum JournalReadFailure {
        MALFORMED_FILENAME,
        NOT_REGULAR_FILE,
        UNREADABLE_OR_MALFORMED,
        TRANSACTION_ID_MISMATCH,
        DUPLICATE_TRANSACTION_ID
    }

    record JournalEntry(
            Path path,
            UUID filenameTransactionId,
            PublicationTransaction transaction,
            JournalReadFailure failure
    ) {
        JournalEntry {
            path = normalized(path);
            if ((transaction == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "A journal entry must be either readable or failed."
                );
            }
            if (transaction != null
                    && (filenameTransactionId == null
                    || !filenameTransactionId.equals(transaction.transactionId()))) {
                throw new IllegalArgumentException(
                        "Readable journal ID must match its canonical filename."
                );
            }
        }

        static JournalEntry readable(
                Path path,
                UUID filenameTransactionId,
                PublicationTransaction transaction
        ) {
            return new JournalEntry(path, filenameTransactionId, transaction, null);
        }

        static JournalEntry failed(
                Path path,
                UUID filenameTransactionId,
                JournalReadFailure failure
        ) {
            return new JournalEntry(path, filenameTransactionId, null, failure);
        }

        boolean readable() {
            return transaction != null;
        }

        @Override
        public String toString() {
            return "JournalEntry[filenameTransactionId=" + filenameTransactionId
                    + ", readable=" + readable()
                    + ", failure=" + failure + ']';
        }
    }
}
