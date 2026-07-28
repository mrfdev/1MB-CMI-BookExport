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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic, strict UTF-8 codec for content-free publication journals. */
final class PublicationTransactionCodec {
    static final int MAXIMUM_ENCODED_BYTES = 32 * 1024;

    private static final List<String> FIELD_ORDER = List.of(
            "schema-version",
            "transaction-id",
            "revision",
            "state",
            "created-at",
            "updated-at",
            "draft-id",
            "approved-manifest-revision",
            "published-by-name",
            "published-by-uuid",
            "collision-mode",
            "intended-filename",
            "staged-filename",
            "published-filename",
            "archive-filename",
            "backup-filename",
            "approved-utf8-bytes",
            "approved-sha256",
            "replaced-utf8-bytes",
            "replaced-sha256",
            "workflow-roots-sha256"
    );
    private static final Set<String> KNOWN_FIELDS = Set.copyOf(FIELD_ORDER);

    byte[] encode(PublicationTransaction transaction) throws IOException {
        Map<String, String> fields = fields(transaction);
        StringBuilder text = new StringBuilder(2_048);
        for (String key : FIELD_ORDER) {
            String value = fields.get(key);
            if (value == null) {
                throw new IOException("Publication transaction encoder omitted a required field.");
            }
            text.append(key).append('=').append(escape(value)).append('\n');
        }
        byte[] encoded = encodeUtf8(text);
        if (encoded.length > MAXIMUM_ENCODED_BYTES) {
            throw new IOException("Publication transaction exceeds the 32 KiB storage limit.");
        }
        return encoded;
    }

    PublicationTransaction decode(byte[] encoded) throws IOException {
        if (encoded == null) {
            throw new IOException("Publication transaction bytes are missing.");
        }
        if (encoded.length > MAXIMUM_ENCODED_BYTES) {
            throw new IOException("Publication transaction exceeds the 32 KiB storage limit.");
        }
        Map<String, String> fields = parseFields(decodeUtf8(encoded));
        try {
            return transaction(fields);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new IOException(
                    "Publication transaction contains malformed typed metadata.",
                    exception
            );
        }
    }

    PublicationTransaction read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Publication transaction is not a regular non-symbolic-link file."
            );
        }
        return decode(readBounded(path));
    }

    private static Map<String, String> fields(PublicationTransaction transaction) {
        Map<String, String> fields = new HashMap<>();
        fields.put("schema-version", Integer.toString(transaction.schemaVersion()));
        fields.put("transaction-id", transaction.transactionId().toString());
        fields.put("revision", Long.toString(transaction.revision()));
        fields.put("state", transaction.state().key());
        fields.put("created-at", transaction.createdAt().toString());
        fields.put("updated-at", transaction.updatedAt().toString());
        fields.put("draft-id", transaction.draftId().toString());
        fields.put(
                "approved-manifest-revision",
                Long.toString(transaction.approvedManifestRevision())
        );
        fields.put("published-by-name", transaction.publisher().name());
        fields.put(
                "published-by-uuid",
                transaction.publisher().uuid() == null
                        ? "" : transaction.publisher().uuid().toString()
        );
        fields.put("collision-mode", transaction.collisionMode().key());
        fields.put("intended-filename", transaction.intendedFilename());
        fields.put("staged-filename", transaction.stagedFilename());
        fields.put("published-filename", transaction.publishedFilename());
        fields.put("archive-filename", transaction.archiveFilename());
        fields.put(
                "backup-filename",
                transaction.backupFilename() == null ? "" : transaction.backupFilename()
        );
        fields.put(
                "approved-utf8-bytes",
                Long.toString(transaction.approvedFingerprint().utf8Bytes())
        );
        fields.put("approved-sha256", transaction.approvedFingerprint().sha256());
        fields.put(
                "replaced-utf8-bytes",
                transaction.replacedFingerprint() == null
                        ? "" : Long.toString(transaction.replacedFingerprint().utf8Bytes())
        );
        fields.put(
                "replaced-sha256",
                transaction.replacedFingerprint() == null
                        ? "" : transaction.replacedFingerprint().sha256()
        );
        fields.put("workflow-roots-sha256", transaction.workflowRootsSha256());
        return fields;
    }

    private static PublicationTransaction transaction(Map<String, String> fields) {
        int schemaVersion = parseInt(fields, "schema-version");
        if (schemaVersion != PublicationTransaction.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported publication transaction schema version."
            );
        }

        String stateValue = required(fields, "state");
        PublicationTransactionState state = PublicationTransactionState.parse(stateValue)
                .filter(candidate -> candidate.key().equals(stateValue))
                .orElseThrow(() -> new IllegalArgumentException("Unknown transaction state."));
        String collisionValue = required(fields, "collision-mode");
        PublishCollisionMode collisionMode = PublishCollisionMode.parse(collisionValue)
                .filter(candidate -> candidate.key().equals(collisionValue))
                .orElseThrow(() -> new IllegalArgumentException("Unknown collision mode."));

        String backupFilename = emptyToNull(required(fields, "backup-filename"));
        String replacedBytes = required(fields, "replaced-utf8-bytes");
        String replacedDigest = required(fields, "replaced-sha256");
        if ((backupFilename == null) != (replacedBytes.isEmpty() && replacedDigest.isEmpty())) {
            throw new IllegalArgumentException(
                    "Backup filename and replaced fingerprint must be present together."
            );
        }
        ContentFingerprint replacedFingerprint = backupFilename == null
                ? null : fingerprint(replacedBytes, replacedDigest, "replaced fingerprint");

        return new PublicationTransaction(
                schemaVersion,
                parseUuid(required(fields, "transaction-id"), "transaction-id"),
                parsePositiveLong(fields, "revision"),
                state,
                parseInstant(required(fields, "created-at"), "created-at"),
                parseInstant(required(fields, "updated-at"), "updated-at"),
                parseUuid(required(fields, "draft-id"), "draft-id"),
                parsePositiveLong(fields, "approved-manifest-revision"),
                actor(fields),
                collisionMode,
                required(fields, "intended-filename"),
                required(fields, "staged-filename"),
                required(fields, "published-filename"),
                required(fields, "archive-filename"),
                backupFilename,
                fingerprint(
                        required(fields, "approved-utf8-bytes"),
                        required(fields, "approved-sha256"),
                        "approved fingerprint"
                ),
                replacedFingerprint,
                required(fields, "workflow-roots-sha256")
        );
    }

    private static DraftManifest.Actor actor(Map<String, String> fields) {
        String name = required(fields, "published-by-name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Publisher name may not be blank.");
        }
        String uuid = required(fields, "published-by-uuid");
        return new DraftManifest.Actor(
                name,
                uuid.isEmpty() ? null : parseUuid(uuid, "published-by-uuid")
        );
    }

    private static ContentFingerprint fingerprint(String byteCount, String digest, String label) {
        if (byteCount.isEmpty() || digest.isEmpty()) {
            throw new IllegalArgumentException(label + " is incomplete.");
        }
        return new ContentFingerprint(parseNonNegativeLong(byteCount, label), digest);
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
                throw new IOException(
                        "Publication transaction contains a blank metadata line."
                );
            }
            int separator = firstUnescapedEquals(line);
            if (separator <= 0) {
                throw new IOException(
                        "Publication transaction contains malformed key=value metadata."
                );
            }
            String key = line.substring(0, separator);
            if (!KNOWN_FIELDS.contains(key)) {
                throw new IOException("Publication transaction contains an unknown field.");
            }
            if (fields.putIfAbsent(
                    key,
                    unescape(line.substring(separator + 1), lineNumber)
            ) != null) {
                throw new IOException("Publication transaction contains a duplicate field.");
            }
            start = newline < 0 ? text.length() : newline + 1;
            lineNumber++;
        }
        if (fields.size() != KNOWN_FIELDS.size()) {
            Set<String> missing = new HashSet<>(KNOWN_FIELDS);
            missing.removeAll(fields.keySet());
            throw new IOException("Publication transaction is missing required fields.");
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
                        escaped.append(String.format(
                                java.util.Locale.ROOT,
                                "\\u%04x",
                                (int) current
                        ));
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
                throw new IOException("Publication transaction line ends with an escape.");
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
                        throw new IOException(
                                "Publication transaction contains a short Unicode escape."
                        );
                    }
                    String hexadecimal = value.substring(index + 1, index + 5);
                    try {
                        decoded.append((char) Integer.parseInt(hexadecimal, 16));
                    } catch (NumberFormatException exception) {
                        throw new IOException(
                                "Publication transaction contains an invalid Unicode escape."
                        );
                    }
                    index += 4;
                }
                default -> throw new IOException(
                        "Publication transaction contains an unsupported escape."
                );
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
            throw new IOException("Publication transaction is not valid UTF-8.", exception);
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
            throw new IOException(
                    "Publication transaction contains malformed Unicode metadata.",
                    exception
            );
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
                    throw new IOException(
                            "Publication transaction exceeds the 32 KiB storage limit."
                    );
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Publication transaction is missing metadata.");
        }
        return value;
    }

    private static int parseInt(Map<String, String> fields, String key) {
        String value = required(fields, key);
        try {
            int parsed = Integer.parseInt(value);
            if (!Integer.toString(parsed).equals(value)) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Transaction integer metadata is malformed.");
        }
    }

    private static long parsePositiveLong(Map<String, String> fields, String key) {
        long parsed = parseCanonicalLong(required(fields, key));
        if (parsed < 1L) {
            throw new IllegalArgumentException("Transaction counter must be positive.");
        }
        return parsed;
    }

    private static long parseNonNegativeLong(String value, String label) {
        long parsed = parseCanonicalLong(value);
        if (parsed < 0L) {
            throw new IllegalArgumentException(label + " must not be negative.");
        }
        return parsed;
    }

    private static long parseCanonicalLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (!Long.toString(parsed).equals(value)) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Transaction integer metadata is malformed.");
        }
    }

    private static Instant parseInstant(String value, String label) {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw new DateTimeException(label + " is not canonical.");
            }
            return parsed;
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(label + " is not a valid canonical instant.");
        }
    }

    private static UUID parseUuid(String value, String label) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " is not a valid canonical UUID.");
        }
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
