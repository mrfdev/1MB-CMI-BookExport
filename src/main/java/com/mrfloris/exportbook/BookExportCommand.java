package com.mrfloris.exportbook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Permission-aware command router for player, console, admin, and debug operations. */
final class BookExportCommand implements TabExecutor {
    private static final String EXPORT = "bookexport.export";
    private static final String CUSTOM_TITLE = "bookexport.export.custom-title";
    private static final String INFO = "bookexport.info";
    private static final String HELP = "bookexport.help";
    private static final String STATUS = "bookexport.admin.status";
    private static final String LIST = "bookexport.admin.list";
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
            return export(sender, null);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "export" -> export(sender, join(args, 1));
            case "info" -> showInfo(sender);
            case "help", "?" -> showHelp(sender);
            case "admin" -> admin(sender, args);
            case "debug" -> debug(sender, args, 1);
            case "list" -> list(sender, args.length > 1 ? args[1] : "1");
            case "reload" -> reload(sender);
            default -> export(sender, String.join(" ", args)); // Legacy /bookexport <title>
        };
    }

    private boolean export(CommandSender sender, String requestedTitle) {
        if (!require(sender, EXPORT, "export books")) {
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
            ExportResult result = exporter.export(player, requestedTitle);
            plugin.clearLastFailure();
            player.sendMessage(Messages.success("Exported " + result.path().getFileName()
                    + " (" + result.pages() + " page(s), " + result.utf8Bytes() + " UTF-8 bytes)."));
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            player.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
    }

    private boolean showInfo(CommandSender sender) {
        if (!require(sender, INFO, "view plugin information")) {
            return true;
        }
        BuildInfo build = plugin.buildInfo();
        sender.sendMessage(Messages.header("BookExport " + build.version() + " build " + build.buildNumber()));
        sender.sendMessage(Messages.info("Purpose", "Export written books and book-and-quill pages to UTF-8 text"));
        sender.sendMessage(Messages.info("Compatibility", "Paper " + build.paperTarget() + ", Java target " + build.javaTarget()));
        sender.sendMessage(Messages.info("Server", plugin.getServer().getVersion()));
        sender.sendMessage(Messages.source(build.sourceUrl()));
        return true;
    }

    private boolean showHelp(CommandSender sender) {
        if (!require(sender, HELP, "view help")) {
            return true;
        }
        sender.sendMessage(Messages.header("BookExport commands"));
        if (sender.hasPermission(EXPORT)) {
            sender.sendMessage(Messages.command("/bookexport", "export a signed book using its title"));
            sender.sendMessage(Messages.command("/bookexport export [title]", "export the held book explicitly"));
        }
        if (sender.hasPermission(INFO)) {
            sender.sendMessage(Messages.command("/bookexport info", "show version and compatibility"));
        }
        sender.sendMessage(Messages.command("/bookexport help", "show permission-filtered help"));
        if (sender.hasPermission(STATUS)) {
            sender.sendMessage(Messages.command("/bookexport admin", "show validated admin status"));
        }
        if (sender.hasPermission(LIST)) {
            sender.sendMessage(Messages.command("/bookexport list [page]", "list exported text files"));
        }
        if (sender.hasPermission(RELOAD)) {
            sender.sendMessage(Messages.command("/bookexport reload", "reload and validate config.yml"));
        }
        if (sender.hasPermission(DEBUG)) {
            sender.sendMessage(Messages.command("/bookexport debug [runtime|book|cmi|preview]", "show read-only diagnostics"));
        }
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
            return showAdminStatus(sender);
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, args.length > 2 ? args[2] : "1");
            case "reload" -> reload(sender);
            case "debug" -> debug(sender, args, 2);
            default -> {
                sender.sendMessage(Messages.error("Usage: /bookexport admin [status|list|reload|debug]"));
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
        sender.sendMessage(Messages.info("Export directory", settings.exportDirectory().toString()));
        sender.sendMessage(Messages.info("Directory health",
                Files.isDirectory(settings.exportDirectory()) && Files.isWritable(settings.exportDirectory())
                        ? "writable" : "not writable"));
        sender.sendMessage(Messages.info("Output profile", settings.colorMode().name().toLowerCase(Locale.ROOT)));
        sender.sendMessage(Messages.info("Pagination", settings.pagination()
                ? settings.paginationMarkup() + " (first-page=" + settings.paginationOnFirstPage() + ')'
                : "disabled"));
        sender.sendMessage(Messages.info("Metadata", Boolean.toString(settings.includeBookMetadata())));
        sender.sendMessage(Messages.info("Debug logging", Boolean.toString(settings.debugLogging())));
        sender.sendMessage(Messages.info("Last failure", plugin.lastFailure()));
        sender.sendMessage(Messages.command("/bookexport admin list", "list exports"));
        sender.sendMessage(Messages.command("/bookexport admin reload", "reload configuration"));
        sender.sendMessage(Messages.command("/bookexport admin debug", "runtime diagnostics"));
        return true;
    }

    private boolean list(CommandSender sender, String pageArgument) {
        if (!require(sender, LIST, "list exports")) {
            return true;
        }

        int requestedPage;
        try {
            requestedPage = Integer.parseInt(pageArgument);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Messages.error("List page must be a positive number."));
            return true;
        }
        if (requestedPage < 1) {
            sender.sendMessage(Messages.error("List page must be a positive number."));
            return true;
        }

        try {
            List<String> files = exporter.listExports();
            if (files.isEmpty()) {
                sender.sendMessage(Messages.info("Exports", "No .txt files found"));
                return true;
            }

            ListPage listPage = ListPage.calculate(
                    files.size(),
                    requestedPage,
                    plugin.settings().listPageSize()
            );
            sender.sendMessage(Messages.header(
                    "BookExport files (page " + listPage.page() + '/' + listPage.pageCount() + ')'
            ));
            for (String file : files.subList(listPage.fromIndex(), listPage.toIndex())) {
                sender.sendMessage(Component.text("- " + file, NamedTextColor.GRAY));
            }
            if (listPage.pageCount() > 1) {
                sender.sendMessage(Messages.listNavigation(listPage));
            }
        } catch (BookExportException exception) {
            plugin.recordFailure(exception.getMessage());
            sender.sendMessage(Messages.error(exception.getMessage()));
        }
        return true;
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
            sender.sendMessage(Messages.error("Configuration reload failed; the previous runtime settings remain active."));
        }
        return true;
    }

    private boolean debug(CommandSender sender, String[] args, int detailIndex) {
        if (!require(sender, DEBUG, "view diagnostics")) {
            return true;
        }
        String detail = args.length > detailIndex ? args[detailIndex].toLowerCase(Locale.ROOT) : "runtime";
        return switch (detail) {
            case "runtime" -> showRuntimeDebug(sender);
            case "book" -> showBookDebug(sender);
            case "cmi" -> showCmiDebug(sender);
            case "preview" -> preview(sender, join(args, detailIndex + 1));
            default -> {
                sender.sendMessage(Messages.error("Usage: /bookexport debug [runtime|book|cmi|preview [title]]"));
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
        sender.sendMessage(Messages.info("Directory writable",
                Boolean.toString(Files.isWritable(plugin.settings().exportDirectory()))));
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
        sender.sendMessage(Messages.info("Output mode", settings.colorMode().name().toLowerCase(Locale.ROOT)));
        sender.sendMessage(Messages.info("Document header", settings.cmiDocumentHeader()));
        sender.sendMessage(Messages.info("Page marker", settings.paginationMarkup()));
        sender.sendMessage(Messages.info("Reminder", "Run /cmi reload after publishing a new file"));
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
            addIfPermitted(options, sender, INFO, "info");
            addIfPermitted(options, sender, HELP, "help");
            addIfPermitted(options, sender, STATUS, "admin");
            addIfPermitted(options, sender, DEBUG, "debug");
            addIfPermitted(options, sender, LIST, "list");
            addIfPermitted(options, sender, RELOAD, "reload");
            return filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            List<String> options = new ArrayList<>();
            addIfPermitted(options, sender, STATUS, "status");
            addIfPermitted(options, sender, LIST, "list");
            addIfPermitted(options, sender, RELOAD, "reload");
            addIfPermitted(options, sender, DEBUG, "debug");
            return filter(options, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission(DEBUG)) {
            return filter(List.of("runtime", "book", "cmi", "preview"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("debug") && sender.hasPermission(DEBUG)) {
            return filter(List.of("runtime", "book", "cmi", "preview"), args[2]);
        }
        return List.of();
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
        return options.stream().filter(option -> option.startsWith(normalizedPrefix)).toList();
    }
}
