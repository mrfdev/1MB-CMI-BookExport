package com.mrfloris.exportbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic, strict UTF-8 codec for content-free draft sidecars. */
final class DraftManifestCodec {
    static final int MAXIMUM_ENCODED_BYTES = 64 * 1024;

    private static final List<String> FIELD_ORDER = List.of(
            "schema-version",
            "draft-id",
            "revision",
            "origin",
            "review-status",
            "publication-status",
            "created-at",
            "created-by-name",
            "created-by-uuid",
            "book-author",
            "source-pages",
            "source-utf16-units",
            "content-utf8-bytes",
            "content-sha256",
            "intended-filename",
            "staged-filename",
            "decision-at",
            "decision-by-name",
            "decision-by-uuid",
            "decision-utf8-bytes",
            "decision-sha256",
            "decision-implicit",
            "published-at",
            "published-by-name",
            "published-by-uuid",
            "collision-mode",
            "published-filename",
            "archive-filename",
            "backup-filename",
            "backup-utf8-bytes",
            "backup-sha256"
    );
    private static final Set<String> KNOWN_FIELDS = Set.copyOf(FIELD_ORDER);

    byte[] encode(DraftManifest manifest) throws IOException {
        Map<String, String> fields = fields(manifest);
        StringBuilder encoded = new StringBuilder(2_048);
        for (String key : FIELD_ORDER) {
            String value = fields.get(key);
            if (value == null) {
                throw new IOException("Draft manifest encoder omitted required field " + key + '.');
            }
            encoded.append(key).append('=').append(escape(value)).append('\n');
        }

        byte[] bytes = encodeUtf8(encoded);
        if (bytes.length > MAXIMUM_ENCODED_BYTES) {
            throw new IOException("Draft manifest exceeds the 64 KiB storage limit.");
        }
        return bytes;
    }

    DraftManifest decode(byte[] encoded) throws IOException {
        if (encoded == null) {
            throw new IOException("Draft manifest bytes are missing.");
        }
        if (encoded.length > MAXIMUM_ENCODED_BYTES) {
            throw new IOException("Draft manifest exceeds the 64 KiB storage limit.");
        }
        String text = decodeUtf8(encoded);
        Map<String, String> fields = parseFields(text);
        try {
            return manifest(fields);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new IOException("Draft manifest contains malformed typed metadata.", exception);
        }
    }

    DraftManifest read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Draft manifest is not a regular non-symbolic-link file: "
                    + safeFilename(path));
        }
        return decode(readBounded(path));
    }

    private static Map<String, String> fields(DraftManifest manifest) {
        Map<String, String> fields = new HashMap<>();
        fields.put("schema-version", Integer.toString(manifest.schemaVersion()));
        fields.put("draft-id", manifest.draftId().toString());
        fields.put("revision", Long.toString(manifest.revision()));
        fields.put("origin", manifest.origin().key());
        fields.put("review-status", manifest.reviewStatus().key());
        fields.put("publication-status", manifest.publicationStatus().key());
        fields.put("created-at", manifest.createdAt().toString());
        fields.put("created-by-name", manifest.createdBy().name());
        fields.put("created-by-uuid", optionalUuid(manifest.createdBy().uuid()));
        fields.put("book-author", manifest.bookAuthor());
        fields.put("source-pages", Integer.toString(manifest.sourcePages()));
        fields.put("source-utf16-units", Integer.toString(manifest.sourceUtf16Units()));
        fields.put("content-utf8-bytes", Long.toString(manifest.contentFingerprint().utf8Bytes()));
        fields.put("content-sha256", manifest.contentFingerprint().sha256());
        fields.put("intended-filename", manifest.intendedFilename());
        fields.put("staged-filename", manifest.stagedFilename());

        DraftManifest.ReviewDecision decision = manifest.reviewDecision();
        fields.put("decision-at", decision == null ? "" : decision.at().toString());
        fields.put("decision-by-name", decision == null ? "" : decision.actor().name());
        fields.put("decision-by-uuid", decision == null ? "" : optionalUuid(decision.actor().uuid()));
        fields.put("decision-utf8-bytes", decision == null
                ? "" : Long.toString(decision.fingerprint().utf8Bytes()));
        fields.put("decision-sha256", decision == null ? "" : decision.fingerprint().sha256());
        fields.put("decision-implicit", decision == null ? "" : Boolean.toString(decision.implicit()));

        DraftManifest.Publication publication = manifest.publication();
        fields.put("published-at", publication == null ? "" : publication.at().toString());
        fields.put("published-by-name", publication == null ? "" : publication.actor().name());
        fields.put("published-by-uuid", publication == null
                ? "" : optionalUuid(publication.actor().uuid()));
        fields.put("collision-mode", publication == null ? "" : publication.collisionMode().key());
        fields.put("published-filename", publication == null ? "" : publication.publishedFilename());
        fields.put("archive-filename", publication == null || publication.archiveFilename() == null
                ? "" : publication.archiveFilename());
        fields.put("backup-filename", publication == null || publication.backupFilename() == null
                ? "" : publication.backupFilename());
        fields.put("backup-utf8-bytes", publication == null || publication.backupFingerprint() == null
                ? "" : Long.toString(publication.backupFingerprint().utf8Bytes()));
        fields.put("backup-sha256", publication == null || publication.backupFingerprint() == null
                ? "" : publication.backupFingerprint().sha256());
        return fields;
    }

    private static DraftManifest manifest(Map<String, String> fields) {
        int schema = parseInt(fields, "schema-version");
        if (schema != DraftManifest.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported draft manifest schema version: " + schema);
        }

        DraftReviewStatus reviewStatus = DraftReviewStatus.parse(required(fields, "review-status"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown review status."));
        DraftPublicationStatus publicationStatus = DraftPublicationStatus.parse(
                required(fields, "publication-status")
        ).orElseThrow(() -> new IllegalArgumentException("Unknown publication status."));

        DraftManifest.ReviewDecision reviewDecision = reviewDecision(fields, reviewStatus);
        DraftManifest.Publication publication = publication(fields, publicationStatus);
        return new DraftManifest(
                schema,
                parseUuid(required(fields, "draft-id"), "draft-id"),
                parseLong(fields, "revision"),
                DraftOrigin.parse(required(fields, "origin"))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown draft origin.")),
                reviewStatus,
                publicationStatus,
                parseInstant(required(fields, "created-at"), "created-at"),
                actor(fields, "created-by-name", "created-by-uuid"),
                required(fields, "book-author"),
                parseInt(fields, "source-pages"),
                parseInt(fields, "source-utf16-units"),
                fingerprint(fields, "content-utf8-bytes", "content-sha256", false),
                required(fields, "intended-filename"),
                required(fields, "staged-filename"),
                reviewDecision,
                publication
        );
    }

    private static DraftManifest.ReviewDecision reviewDecision(
            Map<String, String> fields,
            DraftReviewStatus reviewStatus
    ) {
        boolean absent = allEmpty(
                fields,
                "decision-at",
                "decision-by-name",
                "decision-by-uuid",
                "decision-utf8-bytes",
                "decision-sha256",
                "decision-implicit"
        );
        if (reviewStatus == DraftReviewStatus.UNREVIEWED) {
            if (!absent) {
                throw new IllegalArgumentException("Unreviewed manifest contains decision metadata.");
            }
            return null;
        }
        if (absent) {
            throw new IllegalArgumentException("Reviewed manifest is missing decision metadata.");
        }
        requireNonEmptyGroup(
                fields,
                "decision-at",
                "decision-by-name",
                "decision-utf8-bytes",
                "decision-sha256",
                "decision-implicit"
        );
        return new DraftManifest.ReviewDecision(
                parseInstant(required(fields, "decision-at"), "decision-at"),
                actor(fields, "decision-by-name", "decision-by-uuid"),
                fingerprint(fields, "decision-utf8-bytes", "decision-sha256", false),
                parseBoolean(fields, "decision-implicit")
        );
    }

    private static DraftManifest.Publication publication(
            Map<String, String> fields,
            DraftPublicationStatus publicationStatus
    ) {
        boolean absent = allEmpty(
                fields,
                "published-at",
                "published-by-name",
                "published-by-uuid",
                "collision-mode",
                "published-filename",
                "archive-filename",
                "backup-filename",
                "backup-utf8-bytes",
                "backup-sha256"
        );
        if (publicationStatus == DraftPublicationStatus.STAGED) {
            if (!absent) {
                throw new IllegalArgumentException("Staged manifest contains publication metadata.");
            }
            return null;
        }
        if (absent) {
            throw new IllegalArgumentException("Published manifest is missing publication metadata.");
        }
        requireNonEmptyGroup(
                fields,
                "published-at",
                "published-by-name",
                "collision-mode",
                "published-filename"
        );
        if (publicationStatus == DraftPublicationStatus.PUBLISHED
                && required(fields, "archive-filename").isEmpty()) {
            throw new IllegalArgumentException("Published manifest is missing its archive filename.");
        }

        String backupFilename = emptyToNull(required(fields, "backup-filename"));
        boolean backupDigestAbsent = required(fields, "backup-utf8-bytes").isEmpty()
                && required(fields, "backup-sha256").isEmpty();
        if ((backupFilename == null) != backupDigestAbsent) {
            throw new IllegalArgumentException("Backup filename and fingerprint must be present together.");
        }
        ContentFingerprint backupFingerprint = backupFilename == null
                ? null : fingerprint(fields, "backup-utf8-bytes", "backup-sha256", false);

        return new DraftManifest.Publication(
                parseInstant(required(fields, "published-at"), "published-at"),
                actor(fields, "published-by-name", "published-by-uuid"),
                PublishCollisionMode.parse(required(fields, "collision-mode"))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown collision mode.")),
                required(fields, "published-filename"),
                emptyToNull(required(fields, "archive-filename")),
                backupFilename,
                backupFingerprint
        );
    }

    private static DraftManifest.Actor actor(Map<String, String> fields, String nameKey, String uuidKey) {
        String name = required(fields, nameKey);
        if (name.isBlank()) {
            throw new IllegalArgumentException(nameKey + " may not be blank.");
        }
        String uuid = required(fields, uuidKey);
        return new DraftManifest.Actor(name, uuid.isEmpty() ? null : parseUuid(uuid, uuidKey));
    }

    private static ContentFingerprint fingerprint(
            Map<String, String> fields,
            String bytesKey,
            String digestKey,
            boolean allowEmpty
    ) {
        String bytes = required(fields, bytesKey);
        String digest = required(fields, digestKey);
        if (allowEmpty && bytes.isEmpty() && digest.isEmpty()) {
            return null;
        }
        if (bytes.isEmpty() || digest.isEmpty()) {
            throw new IllegalArgumentException(bytesKey + " and " + digestKey + " must appear together.");
        }
        return new ContentFingerprint(parseLong(bytes, bytesKey), digest);
    }

    private static Map<String, String> parseFields(String text) throws IOException {
        Map<String, String> fields = new HashMap<>();
        int start = 0;
        int lineNumber = 1;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            String line = text.substring(start, end);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                throw new IOException("Draft manifest contains a blank line at " + lineNumber + '.');
            }
            int separator = firstUnescapedEquals(line);
            if (separator <= 0) {
                throw new IOException("Draft manifest line " + lineNumber + " is not key=value metadata.");
            }
            String key = line.substring(0, separator);
            if (!KNOWN_FIELDS.contains(key)) {
                throw new IOException("Draft manifest contains an unknown field.");
            }
            if (fields.putIfAbsent(key, unescape(line.substring(separator + 1), lineNumber)) != null) {
                throw new IOException("Draft manifest contains duplicate field " + key + '.');
            }
            start = newline < 0 ? text.length() : newline + 1;
            lineNumber++;
        }
        if (fields.size() != KNOWN_FIELDS.size()) {
            Set<String> missing = new HashSet<>(KNOWN_FIELDS);
            missing.removeAll(fields.keySet());
            throw new IOException("Draft manifest is missing required fields: " + String.join(", ", missing));
        }
        return fields;
    }

    private static int firstUnescapedEquals(String line) {
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '=') {
                return index;
            }
        }
        return -1;
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\f' -> escaped.append("\\f");
                case '=', ':', '#', '!' -> escaped.append('\\').append(current);
                default -> {
                    if (Character.isISOControl(current)) {
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String unescape(String value, int lineNumber) throws IOException {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (++index >= value.length()) {
                throw new IOException("Draft manifest line " + lineNumber + " ends with an escape.");
            }
            char escaped = value.charAt(index);
            switch (escaped) {
                case '\\', '=', ':', '#', '!' -> decoded.append(escaped);
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'f' -> decoded.append('\f');
                case 'u' -> {
                    if (index + 4 >= value.length()) {
                        throw new IOException("Draft manifest line " + lineNumber
                                + " contains a short Unicode escape.");
                    }
                    String hexadecimal = value.substring(index + 1, index + 5);
                    try {
                        decoded.append((char) Integer.parseInt(hexadecimal, 16));
                    } catch (NumberFormatException exception) {
                        throw new IOException("Draft manifest line " + lineNumber
                                + " contains an invalid Unicode escape.", exception);
                    }
                    index += 4;
                }
                default -> throw new IOException("Draft manifest line " + lineNumber
                        + " contains an unsupported escape.");
            }
        }
        return decoded.toString();
    }

    private static String decodeUtf8(byte[] encoded) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Draft manifest is not valid UTF-8.", exception);
        }
    }

    private static byte[] encodeUtf8(CharSequence text) throws IOException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IOException("Draft manifest contains malformed Unicode metadata.", exception);
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAXIMUM_ENCODED_BYTES) {
                    throw new IOException("Draft manifest exceeds the 64 KiB storage limit.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field " + key + '.');
        }
        return value;
    }

    private static int parseInt(Map<String, String> fields, String key) {
        try {
            return Integer.parseInt(required(fields, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " is not a valid integer.");
        }
    }

    private static long parseLong(Map<String, String> fields, String key) {
        return parseLong(required(fields, key), key);
    }

    private static long parseLong(String value, String key) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " is not a valid integer.");
        }
    }

    private static boolean parseBoolean(Map<String, String> fields, String key) {
        String value = required(fields, key);
        if (value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false.");
    }

    private static Instant parseInstant(String value, String key) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(key + " is not a valid UTC instant.");
        }
    }

    private static UUID parseUuid(String value, String key) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(key + " is not a valid UUID.");
        }
    }

    private static boolean allEmpty(Map<String, String> fields, String... keys) {
        for (String key : keys) {
            if (!required(fields, key).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void requireNonEmptyGroup(Map<String, String> fields, String... keys) {
        List<String> empty = new ArrayList<>();
        for (String key : keys) {
            if (required(fields, key).isEmpty()) {
                empty.add(key);
            }
        }
        if (!empty.isEmpty()) {
            throw new IllegalArgumentException("Draft manifest fields may not be empty: "
                    + String.join(", ", empty));
        }
    }

    private static String optionalUuid(UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static String safeFilename(Path path) {
        Path filename = path == null ? null : path.getFileName();
        return filename == null ? "<unknown>" : filename.toString();
    }
}
