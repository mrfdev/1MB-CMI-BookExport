package com.mrfloris.exportbook;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Permission-aware command router for player, console, admin, and debug operations. */
final class BookExportCommand implements TabExecutor {
    private static final String EXPORT = "bookexport.export";
    private static final String CUSTOM_TITLE = "bookexport.export.custom-title";
    private static final String INFO = "bookexport.info";
    private static final String HELP = "bookexport.help";
    private static final String STATUS = "bookexport.admin.status";
    private static final String LIST_PUBLISHED = "bookexport.admin.list";
    private static final String LIST_STAGED = "bookexport.admin.list.staged";
    private static final String LIST_ARCHIVE = "bookexport.admin.list.archive";
    private static final String LIST_BACKUPS = "bookexport.admin.list.backups";
    private static final String REVIEW = "bookexport.admin.review";
    private static final String APPROVE = "bookexport.admin.approve";
    private static final String PUBLISH = "bookexport.admin.publish";
    private static final String REPLACE = "bookexport.admin.replace";
    private static final String HISTORY = "bookexport.admin.history";
    private static final String RELOAD = "bookexport.admin.reload";
    private static final String DEBUG = "bookexport.admin.debug";

    private final ExportBookPlugin plugin;
    private final BookExporter exporter;

    BookExportCommand(ExportBookPlugin plugin, BookExporter exporter) {
        this.plugin = plugin;
        this.exporter = exporter;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return export(sender, null, false);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "export" -> export(sender, join(args, 1), false);
            case "stage" -> export(sender, join(args, 1), true);
            case "info" -> args.length == 1
                    ? showInfo(sender)
                    : usageError(sender, "Usage: /bookexport info");
            case "help", "?" -> args.length == 1
                    ? showHelp(sender)
                    : usageError(sender, "Usage: /bookexport help");
            case "admin" -> admin(sender, args);
            case "debug" -> debug(sender, args, 1);
            case "list" -> list(sender, args, 1);
            case "reload" -> args.length == 1
                    ? reload(sender)
                    : usageError(sender, "Usage: /bookexport reload");
            default -> export(sender, String.join(" ", args), false); // Legacy /bookexport <title>
        };
    }

    private boolean export(CommandSender sender, String requestedTitle, boolean forceStage) {
        if (!require(sender, EXPORT, forceStage ? "stage books" : "export books")) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.error("Only a player can export a held book."));
            return true;
        }
        if (requestedTitle != null && !requestedTitle.isBlank()
                && !require(sender, CUSTOM_TITLE, "choose a custom export title")) {
            return true;
        }

        try {
            ExportResult result = forceStage
                    ? exporter.stage(player, requestedTitle)
                    : exporter.export(player, requestedTitle);
            plugin.clearLastFailure();
            sendExportResult(player, result);
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            player.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private void sendExportResult(Player player, ExportResult result) {
        String details = result.pages() + " page(s), " + result.utf8Bytes() + " UTF-8 bytes";
        if (result.scope() == FileScope.STAGED) {
            player.sendMessage(Messages.success("Staged draft " + result.path().getFileName()
                    + " (" + details + "). Staff should review its exact bytes before publication."));
            if (result.managedDraft()) {
                player.sendMessage(Messages.copyableInfo("Manifest ID", result.draftId().toString()));
                player.sendMessage(Messages.copyableInfo("Staged SHA-256", result.sha256()));
            }
            if (player.hasPermission(REVIEW)) {
                player.sendMessage(Messages.command(
                        "/bookexport admin review " + result.path().getFileName(),
                        "verify content-free metadata and current checksum"
                ));
            }
            if (player.hasPermission(APPROVE)) {
                player.sendMessage(Messages.command(
                        "/bookexport admin approve " + result.path().getFileName(),
                        "approve the current exact bytes"
                ));
            }
            if (player.hasPermission(PUBLISH)) {
                player.sendMessage(Messages.command(
                        "/bookexport admin publish " + result.path().getFileName() + " fail",
                        "publish only if no live file already uses this name"
                ));
            }
            return;
        }

        player.sendMessage(Messages.success("Exported directly as " + result.path().getFileName()
                + " (" + details + ")."));
        player.sendMessage(Messages.info("CMI", "Run /cmi reload before using the new CustomText entry"));
    }

    private boolean showInfo(CommandSender sender) {
        if (!require(sender, INFO, "view plugin information")) {
            return true;
        }
        BuildInfo build = plugin.buildInfo();
        BookExportInfo info = new BookExportInfo(
                build.version(),
                build.buildNumber(),
                build.paperTarget(),
                build.javaTarget(),
                plugin.settings().workflowMode().key(),
                plugin.getServer().getVersion(),
                build.docsUrl(),
                build.sourceUrl()
        );
        for (var message : info.messages(
                sender.hasPermission(HELP),
                sender.hasPermission(EXPORT),
                sender.hasPermission(CUSTOM_TITLE),
                sender.hasPermission(LIST_STAGED)
        )) {
            sender.sendMessage(message);
        }
        return true;
    }

    private boolean showHelp(CommandSender sender) {
        if (!require(sender, HELP, "view help")) {
            return true;
        }
        sender.sendMessage(Messages.header("BookExport commands"));
        if (sender.hasPermission(EXPORT)) {
            String normalAction = plugin.settings().workflowMode() == WorkflowMode.STAGED
                    ? "stage the held book for review"
                    : "export the held book directly (compatibility mode)";
            sender.sendMessage(Messages.command("/bookexport", normalAction));
            sender.sendMessage(Messages.command("/bookexport export [title]", normalAction));
            sender.sendMessage(Messages.command("/bookexport stage [title]", "always create a staged draft"));
        }
        if (sender.hasPermission(INFO)) {
            sender.sendMessage(Messages.command("/bookexport info", "show version and compatibility"));
        }
        sender.sendMessage(Messages.command("/bookexport help", "show permission-filtered help"));
        showListHelp(sender);
        if (sender.hasPermission(REVIEW)) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin review <staged-file>",
                    "show content-free manifest and checksum integrity"
            ));
        }
        if (sender.hasPermission(APPROVE)) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin approve <staged-file>",
                    "approve the current exact draft bytes"
            ));
            sender.sendMessage(Messages.command(
                    "/bookexport admin changes <staged-file>",
                    "request changes and block publication until approval"
            ));
        }
        if (sender.hasPermission(PUBLISH)) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin publish <staged-file> [fail|unique|replace]",
                    "publish a checksum-verified draft; replace needs extra permission"
            ));
        }
        if (sender.hasPermission(HISTORY)) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin history [page]",
                    "list content-free manifest records"
            ));
            sender.sendMessage(Messages.command(
                    "/bookexport admin history show <manifest-id>",
                    "show one stable manifest record"
            ));
        }
        if (sender.hasPermission(STATUS)) {
            sender.sendMessage(Messages.command("/bookexport admin", "show workflow and directory status"));
        }
        if (sender.hasPermission(RELOAD)) {
            sender.sendMessage(Messages.command("/bookexport reload", "reload and validate config.yml"));
        }
        if (sender.hasPermission(DEBUG)) {
            sender.sendMessage(Messages.command(
                    "/bookexport debug [runtime|book|cmi|workflow|preview [title]]",
                    "show read-only diagnostics"
            ));
        }
        return true;
    }

    private void showListHelp(CommandSender sender) {
        if (sender.hasPermission(LIST_PUBLISHED)) {
            sender.sendMessage(Messages.command("/bookexport list [page]", "list published CMI text files"));
        }
        for (FileScope scope : List.of(FileScope.STAGED, FileScope.ARCHIVE, FileScope.BACKUPS)) {
            if (sender.hasPermission(permissionForScope(scope))) {
                sender.sendMessage(Messages.command(
                        "/bookexport list " + scope.key() + " [page]",
                        "list " + scope.displayName()
                ));
            }
        }
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return showAdminStatus(sender);
        }
        if (args[1].equalsIgnoreCase("status")) {
            return args.length == 2
                    ? showAdminStatus(sender)
                    : usageError(sender, "Usage: /bookexport admin status");
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, args, 2);
            case "review" -> review(sender, args);
            case "approve" -> approve(sender, args);
            case "changes" -> requestChanges(sender, args);
            case "publish" -> publish(sender, args);
            case "history" -> history(sender, args);
            case "reload" -> args.length == 2
                    ? reload(sender)
                    : usageError(sender, "Usage: /bookexport admin reload");
            case "debug" -> debug(sender, args, 2);
            default -> {
                sender.sendMessage(Messages.error(
                        "Usage: /bookexport admin "
                                + "[status|list|review|approve|changes|publish|history|reload|debug]"
                ));
                yield true;
            }
        };
    }

    private boolean showAdminStatus(CommandSender sender) {
        if (!require(sender, STATUS, "view admin status")) {
            return true;
        }
        ExportSettings settings = plugin.settings();
        sender.sendMessage(Messages.header("BookExport admin status"));
        sender.sendMessage(Messages.info("Version", plugin.buildInfo().version()));
        sender.sendMessage(Messages.info("Build", plugin.buildInfo().buildNumber()));
        sender.sendMessage(Messages.info("Config", settings.configVersion()
                + (settings.directCompatibilityMode() ? " (direct compatibility mode)" : " (current)")));
        sender.sendMessage(Messages.info("Workflow mode", settings.workflowMode().key()));
        sender.sendMessage(Messages.info("Publish collision default", settings.publishCollisionMode().key()));
        showScopeStatus(sender, FileScope.STAGED);
        showScopeStatus(sender, FileScope.PUBLISHED);
        showScopeStatus(sender, FileScope.ARCHIVE);
        showScopeStatus(sender, FileScope.BACKUPS);
        showManifestHealth(sender);
        sender.sendMessage(Messages.info("Output profile", settings.colorMode().name().toLowerCase(Locale.ROOT)));
        sender.sendMessage(Messages.info("Pagination", settings.pagination()
                ? settings.paginationMarkup() + " (first-page=" + settings.paginationOnFirstPage() + ')'
                : "disabled"));
        sender.sendMessage(Messages.info("Metadata", Boolean.toString(settings.includeBookMetadata())));
        sender.sendMessage(Messages.info("Debug logging", Boolean.toString(settings.debugLogging())));
        sender.sendMessage(Messages.info("Last failure", plugin.lastFailure()));
        showAdminShortcuts(sender);
        return true;
    }

    private void showScopeStatus(CommandSender sender, FileScope scope) {
        Path directory = scope.directory(plugin.settings());
        String health = Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && Files.isWritable(directory)
                ? "writable" : "not writable";
        String count;
        try {
            count = Integer.toString(exporter.listFiles(scope).size());
        } catch (BookExportException exception) {
            count = "unavailable";
        }
        sender.sendMessage(Messages.info(capitalize(scope.key()), directory + " (" + health + ", " + count
                + " .txt file(s))"));
    }

    private void showManifestHealth(CommandSender sender) {
        try {
            ManifestHealth health = exporter.manifestHealth();
            sender.sendMessage(Messages.info(
                    "Draft manifests",
                    health.managed() + " managed (" + health.unreviewed() + " unreviewed, "
                            + health.approved() + " approved, " + health.changesRequested()
                            + " changes-requested), " + health.untracked() + " legacy untracked"
            ));
            sender.sendMessage(Messages.info(
                    "Manifest integrity",
                    health.modified() + " modified, " + health.orphanSidecars() + " orphan sidecar(s), "
                            + health.creationMarkers() + " incomplete-creation marker(s), "
                            + health.errors() + " error(s)"
            ));
            sender.sendMessage(Messages.info(
                    "Manifest outcomes",
                    health.publicationPending() + " archive-pending, "
                            + (health.historyAvailable()
                            ? health.publishedHistory() + " finalized"
                            : "history unavailable")
            ));
        } catch (BookExportException exception) {
            sender.sendMessage(Messages.warning("Manifest health is unavailable: " + exception.getMessage()));
        }
    }

    private void showAdminShortcuts(CommandSender sender) {
        if (sender.hasPermission(LIST_PUBLISHED)) {
            sender.sendMessage(Messages.command("/bookexport admin list", "list published files"));
        }
        if (sender.hasPermission(LIST_STAGED)) {
            sender.sendMessage(Messages.command("/bookexport admin list staged", "review staged drafts"));
        }
        if (sender.hasPermission(REVIEW)) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin review <staged-file>",
                    "verify manifest metadata and current checksum"
            ));
        }
        if (sender.hasPermission(APPROVE)) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin approve <staged-file>",
                    "approve current bytes or use changes to request revision"
            ));
        }
        if (sender.hasPermission(PUBLISH)) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin publish <staged-file>",
                    "publish using the configured collision policy"
            ));
        }
        if (sender.hasPermission(HISTORY)) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin history",
                    "browse content-free review and publication records"
            ));
        }
        if (sender.hasPermission(RELOAD)) {
            sender.sendMessage(Messages.command("/bookexport admin reload", "reload configuration"));
        }
        if (sender.hasPermission(DEBUG)) {
            sender.sendMessage(Messages.command("/bookexport admin debug workflow", "workflow diagnostics"));
        }
    }

    private boolean list(CommandSender sender, String[] args, int firstArgument) {
        ListCommandRequest request;
        try {
            request = ListCommandRequest.parse(args, firstArgument);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Messages.error(exception.getMessage()));
            return true;
        }

        String permission = permissionForScope(request.scope());
        if (!require(sender, permission, "list " + request.scope().displayName())) {
            return true;
        }

        try {
            List<String> files = exporter.listFiles(request.scope());
            if (files.isEmpty()) {
                sender.sendMessage(Messages.info(capitalize(request.scope().key()), "No .txt files found"));
                return true;
            }

            ListPage listPage = ListPage.calculate(
                    files.size(),
                    request.page(),
                    plugin.settings().listPageSize()
            );
            sender.sendMessage(Messages.header(
                    "BookExport " + request.scope().displayName()
                            + " (page " + listPage.page() + '/' + listPage.pageCount() + ')'
            ));
            for (String file : files.subList(listPage.fromIndex(), listPage.toIndex())) {
                if (request.scope() == FileScope.STAGED) {
                    sender.sendMessage(Messages.stagedFileEntry(
                            file,
                            draftListStatus(file),
                            sender.hasPermission(REVIEW),
                            sender.hasPermission(APPROVE),
                            sender.hasPermission(PUBLISH)
                    ));
                } else {
                    sender.sendMessage(Messages.fileEntry(file, request.scope()));
                }
            }
            if (listPage.pageCount() > 1) {
                sender.sendMessage(Messages.listNavigation(listPage, request.scope()));
            }
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            sender.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private DraftListStatus draftListStatus(String filename) {
        try {
            return DraftListStatus.from(exporter.reviewDraft(filename));
        } catch (BookExportException exception) {
            return DraftListStatus.CORRUPT;
        }
    }

    private boolean review(CommandSender sender, String[] args) {
        if (!require(sender, REVIEW, "review staged draft metadata")) {
            return true;
        }
        String filename = joinedFilename(sender, args, 2, "Usage: /bookexport admin review <staged-file>");
        if (filename == null) {
            return true;
        }

        try {
            DraftReview review = exporter.reviewDraft(filename);
            showDraftReview(sender, review);
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            sender.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private boolean approve(CommandSender sender, String[] args) {
        if (!require(sender, APPROVE, "approve staged draft bytes")) {
            return true;
        }
        String filename = joinedFilename(sender, args, 2, "Usage: /bookexport admin approve <staged-file>");
        if (filename == null) {
            return true;
        }

        try {
            DraftReview review = exporter.approveDraft(filename, actor(sender));
            plugin.clearLastFailure();
            ContentFingerprint fingerprint = review.manifest().reviewDecision().fingerprint();
            sender.sendMessage(Messages.success("Approved " + review.draftPath().getFileName()
                    + " at SHA-256 " + shortChecksum(fingerprint.sha256())
                    + ". Later byte changes invalidate this approval."));
            if (sender.hasPermission(REVIEW)) {
                sender.sendMessage(Messages.command(
                        "/bookexport admin review " + review.draftPath().getFileName(),
                        "show the recorded approval and full checksum"
                ));
            }
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            sender.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private boolean requestChanges(CommandSender sender, String[] args) {
        if (!require(sender, APPROVE, "request changes to staged drafts")) {
            return true;
        }
        String filename = joinedFilename(sender, args, 2, "Usage: /bookexport admin changes <staged-file>");
        if (filename == null) {
            return true;
        }

        try {
            DraftReview review = exporter.requestChanges(filename, actor(sender));
            plugin.clearLastFailure();
            sender.sendMessage(Messages.success("Marked " + review.draftPath().getFileName()
                    + " as changes requested. Publication is blocked until its current bytes are approved."));
            if (sender.hasPermission(REVIEW)) {
                sender.sendMessage(Messages.command(
                        "/bookexport admin review " + review.draftPath().getFileName(),
                        "show the content-free review decision"
                ));
            }
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            sender.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (!require(sender, HISTORY, "view manifest history")) {
            return true;
        }
        HistoryCommandRequest request;
        try {
            request = HistoryCommandRequest.parse(args, 2);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Messages.error(exception.getMessage()));
            return true;
        }

        try {
            if (request.isShow()) {
                DraftManifest manifest = exporter.manifestHistory(request.manifestId());
                showManifest(sender, manifest, null, null, "BookExport manifest history");
                return true;
            }

            List<DraftManifest> manifests = exporter.manifestHistory();
            if (manifests.isEmpty()) {
                sender.sendMessage(Messages.info("History", "No manifest records found"));
                return true;
            }

            ListPage page = ListPage.calculate(
                    manifests.size(),
                    request.page(),
                    plugin.settings().listPageSize()
            );
            sender.sendMessage(Messages.header(
                    "BookExport manifest history (page " + page.page() + '/' + page.pageCount() + ')'
            ));
            for (DraftManifest manifest : manifests.subList(page.fromIndex(), page.toIndex())) {
                sender.sendMessage(Messages.historyEntry(manifest));
            }
            if (page.pageCount() > 1) {
                sender.sendMessage(Messages.historyNavigation(page));
            }
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            sender.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private void showDraftReview(CommandSender sender, DraftReview review) {
        DraftManifest manifest = review.manifest();
        if (manifest == null) {
            sender.sendMessage(Messages.header("BookExport draft review"));
            sender.sendMessage(Messages.info("Filename", review.draftPath().getFileName().toString()));
            sender.sendMessage(Messages.info("Integrity", "legacy-untracked"));
            sender.sendMessage(Messages.info("Manifest", "none; source metadata is unknown"));
            sender.sendMessage(Messages.info(
                    "Current UTF-8 bytes",
                    Long.toString(review.actualFingerprint().utf8Bytes())
            ));
            sender.sendMessage(Messages.copyableInfo(
                    "Current SHA-256",
                    review.actualFingerprint().sha256()
            ));
            sender.sendMessage(Messages.warning(
                    "Explicit approval will adopt this legacy draft; compatible publication can also record "
                            + "implicit publisher approval."
            ));
            showReviewShortcuts(sender, review.draftPath().getFileName().toString(), DraftListStatus.LEGACY_UNTRACKED);
            return;
        }
        showManifest(
                sender,
                manifest,
                review.actualFingerprint(),
                review.integrity(),
                "BookExport draft review"
        );
        showReviewShortcuts(sender, review.draftPath().getFileName().toString(), DraftListStatus.from(review));
    }

    private void showManifest(
            CommandSender sender,
            DraftManifest manifest,
            ContentFingerprint actualFingerprint,
            DraftIntegrity integrity,
            String heading
    ) {
        sender.sendMessage(Messages.header(heading));
        sender.sendMessage(Messages.copyableInfo("Manifest ID", manifest.draftId().toString()));
        sender.sendMessage(Messages.info("Revision", Long.toString(manifest.revision())));
        sender.sendMessage(Messages.info("Origin", manifest.origin().key()));
        sender.sendMessage(Messages.info("Review status", manifest.reviewStatus().key()));
        sender.sendMessage(Messages.info("Publication status", manifest.publicationStatus().key()));
        sender.sendMessage(Messages.info("Intended filename", manifest.intendedFilename()));
        sender.sendMessage(Messages.info("Staged filename", manifest.stagedFilename()));
        sender.sendMessage(Messages.info("Stager", formatActor(manifest.createdBy())));
        sender.sendMessage(Messages.info(
                "Book author",
                manifest.bookAuthor().isBlank() ? "unknown" : displayMetadata(manifest.bookAuthor())
        ));
        sender.sendMessage(Messages.info("Created UTC", manifest.createdAt().toString()));
        sender.sendMessage(Messages.info("Source pages", knownNumber(manifest.sourcePages())));
        sender.sendMessage(Messages.info("Source UTF-16 units", knownNumber(manifest.sourceUtf16Units())));
        sender.sendMessage(Messages.info(
                "Recorded UTF-8 bytes",
                Long.toString(manifest.contentFingerprint().utf8Bytes())
        ));
        sender.sendMessage(Messages.copyableInfo(
                "Recorded SHA-256",
                manifest.contentFingerprint().sha256()
        ));

        if (integrity != null) {
            sender.sendMessage(Messages.info("Current integrity", integrityKey(integrity)));
        }
        if (actualFingerprint != null) {
            sender.sendMessage(Messages.info(
                    "Current UTF-8 bytes",
                    Long.toString(actualFingerprint.utf8Bytes())
            ));
            sender.sendMessage(Messages.copyableInfo("Current SHA-256", actualFingerprint.sha256()));
        }

        DraftManifest.ReviewDecision decision = manifest.reviewDecision();
        if (decision == null) {
            sender.sendMessage(Messages.info("Review decision", "none"));
        } else {
            sender.sendMessage(Messages.info(
                    "Reviewed by",
                    formatActor(decision.actor()) + (decision.implicit() ? " (implicit publisher approval)" : "")
            ));
            sender.sendMessage(Messages.info("Reviewed UTC", decision.at().toString()));
            sender.sendMessage(Messages.info(
                    "Reviewed UTF-8 bytes",
                    Long.toString(decision.fingerprint().utf8Bytes())
            ));
            sender.sendMessage(Messages.copyableInfo(
                    "Reviewed SHA-256",
                    decision.fingerprint().sha256()
            ));
        }

        DraftManifest.Publication publication = manifest.publication();
        if (publication != null) {
            sender.sendMessage(Messages.info("Publisher", formatActor(publication.actor())));
            sender.sendMessage(Messages.info("Published UTC", publication.at().toString()));
            sender.sendMessage(Messages.info("Collision mode", publication.collisionMode().key()));
            sender.sendMessage(Messages.info("Final filename", publication.publishedFilename()));
            sender.sendMessage(Messages.info(
                    "Final UTF-8 bytes",
                    Long.toString(manifest.effectiveFingerprint().utf8Bytes())
            ));
            sender.sendMessage(Messages.copyableInfo(
                    "Final SHA-256",
                    manifest.effectiveFingerprint().sha256()
            ));
            sender.sendMessage(Messages.info(
                    "Archive",
                    publication.archiveFilename() == null ? "pending" : publication.archiveFilename()
            ));
            sender.sendMessage(Messages.info(
                    "Backup",
                    publication.backupFilename() == null ? "none" : publication.backupFilename()
            ));
            if (publication.backupFingerprint() != null) {
                sender.sendMessage(Messages.copyableInfo(
                        "Backup SHA-256",
                        publication.backupFingerprint().sha256()
                ));
            }
            sender.sendMessage(Messages.info("Outcome", manifest.publicationStatus().key()));
        }
    }

    private static void showReviewShortcuts(
            CommandSender sender,
            String filename,
            DraftListStatus status
    ) {
        if (sender.hasPermission(APPROVE) && status.approveSuggestionAllowed()) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin approve " + filename,
                    "approve the current exact bytes"
            ));
        }
        if (sender.hasPermission(APPROVE) && status.changesSuggestionAllowed()) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin changes " + filename,
                    "request changes"
            ));
        }
        if (sender.hasPermission(PUBLISH) && status.publishSuggestionAllowed()) {
            sender.sendMessage(Messages.command(
                    "/bookexport admin publish " + filename + " fail",
                    "publish only if no live case-insensitive name exists"
            ));
        }
    }

    private boolean publish(CommandSender sender, String[] args) {
        if (!require(sender, PUBLISH, "publish staged drafts")) {
            return true;
        }
        PublishCommandRequest request;
        try {
            request = PublishCommandRequest.parse(args, 2);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Messages.error(exception.getMessage()));
            return true;
        }

        PublishCollisionMode collisionMode = request.effectiveMode(plugin.settings().publishCollisionMode());
        if (collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP
                && !require(sender, REPLACE, "replace a published file with a verified backup")) {
            return true;
        }

        try {
            PublishResult result = exporter.publish(request.stagedFilename(), collisionMode, actor(sender));
            if (result.hasManifestWarning()) {
                plugin.recordFailure(result.manifestWarning());
            } else if (result.hasArchiveWarning()) {
                plugin.recordFailure(result.archiveWarning());
            } else {
                plugin.clearLastFailure();
            }
            sendPublishResult(sender, result);
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            sender.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private static void sendPublishResult(CommandSender sender, PublishResult result) {
        String stagedName = result.stagedPath().getFileName().toString();
        String publishedName = result.publishedPath().getFileName().toString();
        if (result.replaced()) {
            sender.sendMessage(Messages.success("Replaced " + publishedName + " with reviewed draft "
                    + stagedName + ". Backup: " + result.backupPath().getFileName() + '.'));
        } else if (!stagedName.equals(publishedName)) {
            sender.sendMessage(Messages.success("Published " + stagedName + " as " + publishedName + '.'));
        } else {
            sender.sendMessage(Messages.success("Published " + publishedName + '.'));
        }

        if (result.archived()) {
            sender.sendMessage(Messages.info("Archive", result.archivedPath().getFileName().toString()));
        }
        if (result.hasArchiveWarning()) {
            sender.sendMessage(Messages.warning(result.archiveWarning()));
        }
        if (result.manifest() != null) {
            sender.sendMessage(Messages.copyableInfo(
                    "Manifest ID",
                    result.manifest().draftId().toString()
            ));
            sender.sendMessage(Messages.copyableInfo(
                    "Published SHA-256",
                    result.manifest().effectiveFingerprint().sha256()
            ));
            sender.sendMessage(Messages.info(
                    "Manifest outcome",
                    result.manifest().publicationStatus().key()
            ));
        }
        if (result.hasManifestWarning()) {
            sender.sendMessage(Messages.warning(result.manifestWarning()));
        }
        sender.sendMessage(Messages.info("CMI", "Publication is complete; run /cmi reload to load it"));
    }

    private boolean reload(CommandSender sender) {
        if (!require(sender, RELOAD, "reload BookExport")) {
            return true;
        }
        try {
            plugin.reloadRuntimeSettings();
            sender.sendMessage(Messages.success("Configuration reloaded and validated."));
        } catch (IOException exception) {
            plugin.getLogger().warning("Configuration reload rejected: " + exception.getMessage());
            sender.sendMessage(Messages.error(
                    "Configuration reload failed; the previous runtime settings remain active."
            ));
        }
        return true;
    }

    private boolean debug(CommandSender sender, String[] args, int detailIndex) {
        if (!require(sender, DEBUG, "view diagnostics")) {
            return true;
        }
        String detail = args.length > detailIndex ? args[detailIndex].toLowerCase(Locale.ROOT) : "runtime";
        if (!detail.equals("preview") && args.length > detailIndex + 1) {
            return usageError(
                    sender,
                    "Usage: /bookexport debug [runtime|book|cmi|workflow|preview [title]]"
            );
        }
        return switch (detail) {
            case "runtime" -> showRuntimeDebug(sender);
            case "book" -> showBookDebug(sender);
            case "cmi" -> showCmiDebug(sender);
            case "workflow" -> showWorkflowDebug(sender);
            case "preview" -> preview(sender, join(args, detailIndex + 1));
            default -> {
                sender.sendMessage(Messages.error(
                        "Usage: /bookexport debug [runtime|book|cmi|workflow|preview [title]]"
                ));
                yield true;
            }
        };
    }

    private boolean showRuntimeDebug(CommandSender sender) {
        BuildInfo build = plugin.buildInfo();
        sender.sendMessage(Messages.header("BookExport runtime diagnostics"));
        sender.sendMessage(Messages.info("Plugin", build.version()));
        sender.sendMessage(Messages.info("Build", build.buildNumber()));
        sender.sendMessage(Messages.info("Artifact", build.artifactFileName()));
        sender.sendMessage(Messages.info("Java runtime", System.getProperty("java.version")
                + " (target " + build.javaTarget() + ')'));
        sender.sendMessage(Messages.info("JVM", System.getProperty("java.vm.name")));
        sender.sendMessage(Messages.info("Paper API", build.paperApiVersion()));
        sender.sendMessage(Messages.info("Server", plugin.getServer().getVersion()));
        sender.sendMessage(Messages.info("Workflow", plugin.settings().workflowMode().key()));
        sender.sendMessage(Messages.info("Published directory writable",
                Boolean.toString(Files.isWritable(plugin.settings().publishedDirectory()))));
        sender.sendMessage(Messages.info("Last failure", plugin.lastFailure()));
        return true;
    }

    private boolean showBookDebug(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.error("Book diagnostics require a player-held item."));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        sender.sendMessage(Messages.header("Held-book diagnostics"));
        sender.sendMessage(Messages.info("Material", held.getType().getKey().asString()));
        if (!BookSnapshotReader.isSupportedBook(held)) {
            sender.sendMessage(Messages.info("Supported book", "false"));
            return true;
        }
        try {
            BookSnapshot book = BookSnapshotReader.read(held);
            sender.sendMessage(Messages.info("Signed", Boolean.toString(book.signed())));
            sender.sendMessage(Messages.info("Has title", Boolean.toString(!book.plainTitle().isBlank())));
            sender.sendMessage(Messages.info("Has author", Boolean.toString(!book.plainAuthor().isBlank())));
            sender.sendMessage(Messages.info("Pages", Integer.toString(book.pageCount())));
            sender.sendMessage(Messages.info("UTF-16 units", Integer.toString(book.utf16Units())));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Messages.error("Unable to read the held book metadata."));
        }
        return true;
    }

    private boolean showCmiDebug(CommandSender sender) {
        ExportSettings settings = plugin.settings();
        sender.sendMessage(Messages.header("CMI integration diagnostics"));
        sender.sendMessage(Messages.info("CMI", plugin.pluginVersion("CMI").orElse("not installed")));
        sender.sendMessage(Messages.info("CMILib", plugin.pluginVersion("CMILib").orElse("not installed")));
        sender.sendMessage(Messages.info("PlaceholderAPI",
                plugin.pluginVersion("PlaceholderAPI").orElse("not installed (optional)")));
        sender.sendMessage(Messages.info("Published directory", settings.publishedDirectory().toString()));
        sender.sendMessage(Messages.info("Output mode", settings.colorMode().name().toLowerCase(Locale.ROOT)));
        sender.sendMessage(Messages.info("Document header", settings.cmiDocumentHeader()));
        sender.sendMessage(Messages.info("Page marker", settings.paginationMarkup()));
        sender.sendMessage(Messages.info("Reminder", "Run /cmi reload after publishing a new file"));
        return true;
    }

    private boolean showWorkflowDebug(CommandSender sender) {
        ExportSettings settings = plugin.settings();
        sender.sendMessage(Messages.header("BookExport workflow diagnostics"));
        sender.sendMessage(Messages.info("Config version", Integer.toString(settings.configVersion())));
        sender.sendMessage(Messages.info("Compatibility mode",
                Boolean.toString(settings.directCompatibilityMode())));
        sender.sendMessage(Messages.info("Workflow mode", settings.workflowMode().key()));
        sender.sendMessage(Messages.info("Collision default", settings.publishCollisionMode().key()));
        for (FileScope scope : FileScope.values()) {
            showScopeStatus(sender, scope);
        }
        showManifestHealth(sender);
        sender.sendMessage(Messages.info("Last failure", plugin.lastFailure()));
        return true;
    }

    private boolean preview(CommandSender sender, String requestedTitle) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.error("Export preview requires a player-held book."));
            return true;
        }
        if (requestedTitle != null && !requestedTitle.isBlank()
                && !require(sender, CUSTOM_TITLE, "preview a custom title")) {
            return true;
        }
        try {
            ExportPreview preview = exporter.preview(player, requestedTitle);
            sender.sendMessage(Messages.header("BookExport preview (no file written)"));
            sender.sendMessage(Messages.info("Filename candidate", preview.filenameBase() + ".txt"));
            sender.sendMessage(Messages.info(
                    "Destination",
                    plugin.settings().exportScope(false).displayName()
            ));
            sender.sendMessage(Messages.info("Pages", Integer.toString(preview.book().pageCount())));
            sender.sendMessage(Messages.info("UTF-16 units", Integer.toString(preview.book().utf16Units())));
            sender.sendMessage(Messages.info("UTF-8 bytes", Integer.toString(preview.utf8Bytes())));
        } catch (BookExportException exception) {
            sender.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private static boolean require(CommandSender sender, String permission, String action) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(Messages.error("You need " + permission + " to " + action + '.'));
        return false;
    }

    private static DraftManifest.Actor actor(CommandSender sender) {
        UUID uuid = sender instanceof Player player ? player.getUniqueId() : null;
        return new DraftManifest.Actor(sender.getName(), uuid);
    }

    private static String formatActor(DraftManifest.Actor actor) {
        return actor.uuid() == null
                ? displayMetadata(actor.name())
                : displayMetadata(actor.name()) + " (" + actor.uuid() + ')';
    }

    private static String displayMetadata(String value) {
        StringBuilder visible = new StringBuilder();
        value.codePoints().limit(96).forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (codePoint == '\n') {
                visible.append("\\n");
            } else if (codePoint == '\r') {
                visible.append("\\r");
            } else if (codePoint == '\t') {
                visible.append("\\t");
            } else if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                visible.append("\\u").append(String.format(Locale.ROOT, "%04x", codePoint));
            } else {
                visible.appendCodePoint(codePoint);
            }
        });
        if (value.codePointCount(0, value.length()) > 96) {
            visible.append('…');
        }
        return visible.toString();
    }

    private static String knownNumber(int value) {
        return value < 0 ? "unknown" : Integer.toString(value);
    }

    private static String integrityKey(DraftIntegrity integrity) {
        return integrity.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String shortChecksum(String checksum) {
        return checksum.substring(0, Math.min(12, checksum.length())) + '…';
    }

    private static String joinedFilename(CommandSender sender, String[] args, int start, String usage) {
        String filename = join(args, start);
        if (filename == null) {
            sender.sendMessage(Messages.error(usage));
            return null;
        }
        return filename;
    }

    private static String join(String[] args, int start) {
        if (start >= args.length) {
            return null;
        }
        String value = String.join(" ", List.of(args).subList(start, args.length)).trim();
        return value.isEmpty() ? null : value;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            addIfPermitted(options, sender, EXPORT, "export");
            addIfPermitted(options, sender, EXPORT, "stage");
            addIfPermitted(options, sender, INFO, "info");
            addIfPermitted(options, sender, HELP, "help");
            if (hasAnyAdminPermission(sender)) {
                options.add("admin");
            }
            addIfPermitted(options, sender, DEBUG, "debug");
            if (!scopeOptions(sender).isEmpty()) {
                options.add("list");
            }
            addIfPermitted(options, sender, RELOAD, "reload");
            return filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            List<String> options = new ArrayList<>();
            addIfPermitted(options, sender, STATUS, "status");
            if (!scopeOptions(sender).isEmpty()) {
                options.add("list");
            }
            addIfPermitted(options, sender, REVIEW, "review");
            addIfPermitted(options, sender, APPROVE, "approve");
            addIfPermitted(options, sender, APPROVE, "changes");
            addIfPermitted(options, sender, PUBLISH, "publish");
            addIfPermitted(options, sender, HISTORY, "history");
            addIfPermitted(options, sender, RELOAD, "reload");
            addIfPermitted(options, sender, DEBUG, "debug");
            return filter(options, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            return filter(scopeOptions(sender), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("list")) {
            return filter(scopeOptions(sender), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("review")
                && sender.hasPermission(REVIEW)
                && sender.hasPermission(LIST_STAGED)) {
            return filter(stagedFilenameOptions(), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && (args[1].equalsIgnoreCase("approve") || args[1].equalsIgnoreCase("changes"))
                && sender.hasPermission(APPROVE)
                && sender.hasPermission(LIST_STAGED)) {
            return filter(stagedFilenameOptions(), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("publish")
                && sender.hasPermission(PUBLISH)
                && sender.hasPermission(LIST_STAGED)) {
            return filter(stagedFilenameOptions(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("publish") && sender.hasPermission(PUBLISH)) {
            List<String> modes = new ArrayList<>(List.of("fail", "unique"));
            if (sender.hasPermission(REPLACE)) {
                modes.add("replace");
            }
            return filter(modes, args[3]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("history") && sender.hasPermission(HISTORY)) {
            return filter(List.of("show"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("history")
                && args[2].equalsIgnoreCase("show")
                && sender.hasPermission(HISTORY)) {
            return filter(historyIdOptions(), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission(DEBUG)) {
            return filter(debugOptions(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("debug") && sender.hasPermission(DEBUG)) {
            return filter(debugOptions(), args[2]);
        }
        return List.of();
    }

    private List<String> stagedFilenameOptions() {
        try {
            return exporter.listFiles(FileScope.STAGED);
        } catch (BookExportException exception) {
            return List.of();
        }
    }

    private List<String> historyIdOptions() {
        try {
            return exporter.manifestHistory().stream()
                    .map(DraftManifest::draftId)
                    .map(UUID::toString)
                    .toList();
        } catch (BookExportException exception) {
            return List.of();
        }
    }

    private static List<String> debugOptions() {
        return List.of("runtime", "book", "cmi", "workflow", "preview");
    }

    private static List<String> scopeOptions(CommandSender sender) {
        List<String> scopes = new ArrayList<>();
        for (FileScope scope : FileScope.values()) {
            if (sender.hasPermission(permissionForScope(scope))) {
                scopes.add(scope.key());
            }
        }
        return scopes;
    }

    private static boolean hasAnyAdminPermission(CommandSender sender) {
        return sender.hasPermission(STATUS)
                || sender.hasPermission(LIST_PUBLISHED)
                || sender.hasPermission(LIST_STAGED)
                || sender.hasPermission(LIST_ARCHIVE)
                || sender.hasPermission(LIST_BACKUPS)
                || sender.hasPermission(REVIEW)
                || sender.hasPermission(APPROVE)
                || sender.hasPermission(PUBLISH)
                || sender.hasPermission(HISTORY)
                || sender.hasPermission(RELOAD)
                || sender.hasPermission(DEBUG);
    }

    private static String permissionForScope(FileScope scope) {
        return switch (scope) {
            case PUBLISHED -> LIST_PUBLISHED;
            case STAGED -> LIST_STAGED;
            case ARCHIVE -> LIST_ARCHIVE;
            case BACKUPS -> LIST_BACKUPS;
        };
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean usageError(CommandSender sender, String usage) {
        sender.sendMessage(Messages.error(usage));
        return true;
    }

    private static void addIfPermitted(
            List<String> options,
            CommandSender sender,
            String permission,
            String value
    ) {
        if (sender.hasPermission(permission)) {
            options.add(value);
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
    }
}
