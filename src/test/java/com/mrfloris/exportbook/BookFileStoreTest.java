package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BookFileStoreTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-14T10:02:03.456Z"),
            ZoneOffset.UTC
    );
    private static final int MAXIMUM_FILENAME_LENGTH = 96;

    @TempDir
    Path temporaryDirectory;

    @Test
    void writeUniqueWritesExactUtf8BytesAndCleansTemporaryFile() throws Exception {
        Path output = temporaryDirectory.resolve("output");
        String content = "First line\nCafé 你好 📘\n§x§1§2§3§4§5§6";

        Path written = BookFileStore.writeUnique(output, "guide", content, MAXIMUM_FILENAME_LENGTH);

        assertEquals(output.resolve("guide.txt"), written);
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(written));
        assertNoInternalTemporaryFiles(output);
    }

    @Test
    void writeUniqueUsesCaseInsensitiveSuffixWithoutOverwriting() throws Exception {
        Path output = Files.createDirectories(temporaryDirectory.resolve("output"));
        Path existing = writeUtf8(output.resolve("Guide.TXT"), "existing");

        Path written = BookFileStore.writeUnique(output, "guide", "new", MAXIMUM_FILENAME_LENGTH);

        assertEquals("guide_1.txt", written.getFileName().toString());
        assertUtf8(existing, "existing");
        assertUtf8(written, "new");
        assertNoInternalTemporaryFiles(output);
    }

    @Test
    void writeUniqueRejectsTraversalAndCleansTemporaryFile() throws Exception {
        Path output = temporaryDirectory.resolve("output");

        assertThrows(
                BookExportException.class,
                () -> BookFileStore.writeUnique(output, "../escaped", "content", MAXIMUM_FILENAME_LENGTH)
        );

        assertFalse(Files.exists(temporaryDirectory.resolve("escaped.txt")));
        assertNoInternalTemporaryFiles(output);
    }

    @Test
    void writeUniqueCleansTemporaryFileWhenFilenameBoundingFails() throws Exception {
        Path output = temporaryDirectory.resolve("output");

        assertThrows(
                IllegalArgumentException.class,
                () -> BookFileStore.writeUnique(output, "guide", "content", 0)
        );

        assertNoInternalTemporaryFiles(output);
    }

    @Test
    void listTextFilesReturnsOnlyRegularTextFilesInCaseInsensitiveOrder() throws Exception {
        Path output = Files.createDirectories(temporaryDirectory.resolve("output"));
        writeUtf8(output.resolve("zeta.TXT"), "z");
        writeUtf8(output.resolve("Alpha.txt"), "a");
        writeUtf8(output.resolve("beta.txt"), "b");
        writeUtf8(output.resolve("notes.md"), "ignored");
        writeUtf8(output.resolve(".txt"), "ignored");
        Files.createDirectory(output.resolve("folder.txt"));
        createSymbolicLinkOrSkip(output.resolve("linked.txt"), temporaryDirectory.resolve("outside.txt"));

        assertEquals(
                List.of("Alpha.txt", "beta.txt", "zeta.TXT"),
                BookFileStore.listTextFiles(output)
        );
    }

    @Test
    void failModePublishesAndArchivesExactBytes() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        String content = "CMI\n<NextPage>\nCafé 你好";
        Path staged = writeUtf8(directories.staging().resolve("Guide.txt"), content);

        PublishResult result = publish(directories, "guide", PublishCollisionMode.FAIL);

        assertEquals(staged, result.stagedPath());
        assertEquals(directories.published().resolve("Guide.txt"), result.publishedPath());
        assertEquals(PublishCollisionMode.FAIL, result.collisionMode());
        assertTrue(result.archived());
        assertFalse(result.replaced());
        assertFalse(result.hasArchiveWarning());
        assertNull(result.backupPath());
        assertFalse(Files.exists(staged));
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(result.publishedPath()));
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(result.archivedPath()));
        assertTrue(listRegularFiles(directories.backups()).isEmpty());
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void failModeCollisionPreservesStagedAndPublishedFiles() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "draft");
        Path published = writeUtf8(directories.published().resolve("GUIDE.TXT"), "published");

        assertThrows(
                BookExportException.class,
                () -> publish(directories, "Guide.txt", PublishCollisionMode.FAIL)
        );

        assertUtf8(staged, "draft");
        assertUtf8(published, "published");
        assertTrue(listRegularFiles(directories.archive()).isEmpty());
        assertTrue(listRegularFiles(directories.backups()).isEmpty());
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void uniqueModeUsesCaseInsensitiveSuffixAndArchivesDraft() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "draft");
        Path existing = writeUtf8(directories.published().resolve("GUIDE.TXT"), "published");

        PublishResult result = publish(directories, "guide.txt", PublishCollisionMode.UNIQUE);

        assertEquals("guide_1.txt", result.publishedPath().getFileName().toString());
        assertUtf8(existing, "published");
        assertUtf8(result.publishedPath(), "draft");
        assertUtf8(result.archivedPath(), "draft");
        assertFalse(Files.exists(staged));
        assertNull(result.backupPath());
        assertEquals(PublishCollisionMode.UNIQUE, result.collisionMode());
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void replaceModeBacksUpOldBytesAndArchivesPublishedDraft() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "new 你好");
        Path existing = writeUtf8(directories.published().resolve("GUIDE.TXT"), "old café");

        PublishResult result = publish(
                directories,
                "Guide.txt",
                PublishCollisionMode.REPLACE_WITH_BACKUP
        );

        assertEquals(existing, result.publishedPath());
        assertTrue(result.replaced());
        assertTrue(result.archived());
        assertFalse(result.hasArchiveWarning());
        assertUtf8(existing, "new 你好");
        assertUtf8(result.backupPath(), "old café");
        assertUtf8(result.archivedPath(), "new 你好");
        assertTrue(result.backupPath().getFileName().toString().contains("_backup_GUIDE"));
        assertTrue(result.archivedPath().getFileName().toString().contains("_published_guide"));
        assertFalse(Files.exists(staged));
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void fixedTimestampHistoryCollisionsReceiveUniqueSuffixes() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path published = writeUtf8(directories.published().resolve("guide.txt"), "version zero");
        writeUtf8(directories.staging().resolve("guide.txt"), "version one");
        publish(directories, "guide.txt", PublishCollisionMode.REPLACE_WITH_BACKUP);

        writeUtf8(directories.staging().resolve("guide.txt"), "version two");
        PublishResult second = publish(
                directories,
                "guide.txt",
                PublishCollisionMode.REPLACE_WITH_BACKUP
        );

        assertUtf8(published, "version two");
        assertEquals(2, listRegularFiles(directories.backups()).size());
        assertEquals(2, listRegularFiles(directories.archive()).size());
        assertEquals(
                Set.of("version zero", "version one"),
                readUtf8Set(directories.backups())
        );
        assertEquals(
                Set.of("version one", "version two"),
                readUtf8Set(directories.archive())
        );
        assertTrue(second.backupPath().getFileName().toString().endsWith("_1.txt"));
        assertTrue(second.archivedPath().getFileName().toString().endsWith("_1.txt"));
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void replaceWithoutExistingTargetPreservesDraftAndCreatesNoHistory() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "draft");

        assertThrows(
                BookExportException.class,
                () -> publish(directories, "guide.txt", PublishCollisionMode.REPLACE_WITH_BACKUP)
        );

        assertUtf8(staged, "draft");
        assertTrue(listRegularFiles(directories.published()).isEmpty());
        assertTrue(listRegularFiles(directories.archive()).isEmpty());
        assertTrue(listRegularFiles(directories.backups()).isEmpty());
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void workflowDirectoryPreflightFailurePreservesDraft() throws Exception {
        Path staging = Files.createDirectories(temporaryDirectory.resolve("staging"));
        Path published = temporaryDirectory.resolve("published");
        Path archiveFile = writeUtf8(temporaryDirectory.resolve("archive-is-a-file"), "not a directory");
        Path backups = temporaryDirectory.resolve("backups");
        Path staged = writeUtf8(staging.resolve("guide.txt"), "draft");

        assertThrows(
                IOException.class,
                () -> BookFileStore.publish(
                        staging,
                        published,
                        archiveFile,
                        backups,
                        "guide.txt",
                        PublishCollisionMode.FAIL,
                        MAXIMUM_FILENAME_LENGTH,
                        FIXED_CLOCK
                )
        );

        assertUtf8(staged, "draft");
        assertTrue(listRegularFiles(published).isEmpty());
        assertFalse(Files.exists(backups));
        assertNoInternalTemporaryFiles(staging, published);
    }

    @Test
    void directoryDraftIsRejectedWithoutPublishing() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Files.createDirectory(directories.staging().resolve("guide.txt"));

        assertThrows(
                BookExportException.class,
                () -> publish(directories, "guide.txt", PublishCollisionMode.FAIL)
        );

        assertTrue(Files.isDirectory(directories.staging().resolve("guide.txt")));
        assertTrue(listRegularFiles(directories.published()).isEmpty());
        assertTrue(listRegularFiles(directories.archive()).isEmpty());
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void symbolicLinkDraftIsRejectedAndExternalFileIsUntouched() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path external = writeUtf8(temporaryDirectory.resolve("outside.txt"), "external");
        Path link = directories.staging().resolve("guide.txt");
        createSymbolicLinkOrSkip(link, external);

        assertThrows(
                BookExportException.class,
                () -> publish(directories, "guide.txt", PublishCollisionMode.FAIL)
        );

        assertTrue(Files.isSymbolicLink(link));
        assertUtf8(external, "external");
        assertTrue(listRegularFiles(directories.published()).isEmpty());
        assertTrue(listRegularFiles(directories.archive()).isEmpty());
        assertTrue(listRegularFiles(directories.backups()).isEmpty());
        assertNoInternalTemporaryFiles(directories.all());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "../guide.txt",
            "sub/guide.txt",
            "sub\\guide.txt",
            "/absolute/guide.txt",
            ".bookexport-write-guide.txt",
            "bad\u0000name.txt"
    })
    void invalidRequestedFilenamesAreRejected(String requestedFilename) {
        assertThrows(
                BookExportException.class,
                () -> BookFileStore.normalizeRequestedTextFilename(requestedFilename)
        );
    }

    @Test
    void omittedTextExtensionIsAddedAndExistingCaseIsPreserved() throws Exception {
        assertEquals("guide.txt", BookFileStore.normalizeRequestedTextFilename(" guide "));
        assertEquals("Guide.TXT", BookFileStore.normalizeRequestedTextFilename("Guide.TXT"));
    }

    @Test
    void dotSegmentsAreRejectedBeforeExtensionNormalization() {
        assertThrows(
                BookExportException.class,
                () -> BookFileStore.normalizeRequestedTextFilename(".")
        );
        assertThrows(
                BookExportException.class,
                () -> BookFileStore.normalizeRequestedTextFilename("..")
        );
    }

    @Test
    void internalTemporaryPrefixIsRejectedCaseInsensitively() {
        assertThrows(
                BookExportException.class,
                () -> BookFileStore.normalizeRequestedTextFilename(".BOOKEXPORT-publish-test.txt")
        );
    }

    @Test
    void extensionOnlyFilenameIsRejected() {
        assertThrows(
                BookExportException.class,
                () -> BookFileStore.normalizeRequestedTextFilename(".txt")
        );
    }

    private WorkflowDirectories workflowDirectories() throws IOException {
        return new WorkflowDirectories(
                Files.createDirectories(temporaryDirectory.resolve("staging")),
                Files.createDirectories(temporaryDirectory.resolve("published")),
                Files.createDirectories(temporaryDirectory.resolve("archive")),
                Files.createDirectories(temporaryDirectory.resolve("backups"))
        );
    }

    private static PublishResult publish(
            WorkflowDirectories directories,
            String requestedFilename,
            PublishCollisionMode collisionMode
    ) throws IOException, BookExportException {
        return BookFileStore.publish(
                directories.staging(),
                directories.published(),
                directories.archive(),
                directories.backups(),
                requestedFilename,
                collisionMode,
                MAXIMUM_FILENAME_LENGTH,
                FIXED_CLOCK
        );
    }

    private static Path writeUtf8(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void assertUtf8(Path path, String expected) throws IOException {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path));
    }

    private static List<Path> listRegularFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static Set<String> readUtf8Set(Path directory) throws IOException {
        Set<String> contents = new HashSet<>();
        for (Path path : listRegularFiles(directory)) {
            contents.add(Files.readString(path, StandardCharsets.UTF_8));
        }
        return contents;
    }

    private static void assertNoInternalTemporaryFiles(Path... directories) throws IOException {
        for (Path directory : directories) {
            if (!Files.exists(directory)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> leftovers = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String filename = path.getFileName().toString();
                            return filename.startsWith(".bookexport-") && filename.endsWith(".tmp");
                        })
                        .toList();
                assertTrue(leftovers.isEmpty(), () -> "Temporary files were not cleaned: " + leftovers);
            }
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        if (!Files.exists(target)) {
            writeUtf8(target, "target");
        }
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }

    private record WorkflowDirectories(
            Path staging,
            Path published,
            Path archive,
            Path backups
    ) {
        Path[] all() {
            return new Path[]{staging, published, archive, backups};
        }
    }
}
