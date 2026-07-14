package com.mrfloris.exportbook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.WritableBookMeta;

import java.util.List;

/** Reads modern written-book components and writable-book strings without deprecated APIs. */
final class BookSnapshotReader {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('\u00A7')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final PlainTextComponentSerializer PLAIN_SERIALIZER = PlainTextComponentSerializer.plainText();

    private BookSnapshotReader() {
    }

    static boolean isSupportedBook(ItemStack item) {
        return item.getType() == Material.WRITTEN_BOOK || item.getType() == Material.WRITABLE_BOOK;
    }

    static BookSnapshot read(ItemStack item) {
        if (item.getType() == Material.WRITTEN_BOOK && item.getItemMeta() instanceof BookMeta meta) {
            Component title = meta.title();
            Component author = meta.author();
            List<String> pages = meta.pages().stream()
                    .map(LEGACY_SERIALIZER::serialize)
                    .toList();
            return new BookSnapshot(
                    true,
                    serializePlain(title),
                    serializeLegacy(title),
                    serializePlain(author),
                    serializeLegacy(author),
                    pages
            );
        }

        // Paper documents that material must be checked before this interface because
        // BookMeta also extends WritableBookMeta.
        if (item.getType() == Material.WRITABLE_BOOK && item.getItemMeta() instanceof WritableBookMeta meta) {
            return new BookSnapshot(false, "", "", "", "", meta.getPages());
        }

        throw new IllegalArgumentException("Item is not a valid written or writable book");
    }

    private static String serializePlain(Component component) {
        return component == null ? "" : PLAIN_SERIALIZER.serialize(component).trim();
    }

    private static String serializeLegacy(Component component) {
        return component == null ? "" : LEGACY_SERIALIZER.serialize(component);
    }
}
