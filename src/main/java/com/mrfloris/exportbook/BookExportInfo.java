package com.mrfloris.exportbook;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure, permission-aware presentation model for the public plugin information command. */
record BookExportInfo(
        String version,
        String buildNumber,
        String paperTarget,
        String javaTarget,
        String workflow,
        String serverVersion,
        String docsUrl,
        String sourceUrl
) {
    BookExportInfo {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(buildNumber, "buildNumber");
        Objects.requireNonNull(paperTarget, "paperTarget");
        Objects.requireNonNull(javaTarget, "javaTarget");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(serverVersion, "serverVersion");
        Objects.requireNonNull(docsUrl, "docsUrl");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
    }

    List<Component> messages(
            boolean canHelp,
            boolean canExport,
            boolean canUseCustomTitle,
            boolean canListStaged
    ) {
        List<Component> messages = new ArrayList<>();
        messages.add(Messages.header("BookExport " + version + " build " + buildNumber));
        messages.add(Messages.info(
                "About",
                "Turn a held written book or book and quill into a reviewable UTF-8 CMI CustomText draft"
        ));
        messages.add(Messages.info(
                "Compatibility",
                "Paper " + paperTarget + ", Java target " + javaTarget
        ));
        messages.add(Messages.info("Workflow", workflow));
        messages.add(Messages.info("Server", serverVersion));
        if (canHelp || canExport || canListStaged) {
            messages.add(Messages.info("Quick start", "Use the clickable commands below"));
        }
        if (canHelp) {
            messages.add(Messages.command("/bookexport help", "show the commands available to you"));
        }
        if (canExport) {
            String command = canUseCustomTitle ? "/bookexport stage [title]" : "/bookexport stage";
            String description = canUseCustomTitle
                    ? "hold a book, then create a draft for staff review"
                    : "hold a signed written book, then create a draft for staff review";
            messages.add(Messages.command(
                    command,
                    description
            ));
        }
        if (canListStaged) {
            messages.add(Messages.command(
                    "/bookexport list staged",
                    "open the staged review queue"
            ));
        }
        messages.add(Messages.link(
                "Documentation",
                docsUrl,
                "Open the BookExport player guide"
        ));
        messages.add(Messages.source(sourceUrl));
        return List.copyOf(messages);
    }
}
