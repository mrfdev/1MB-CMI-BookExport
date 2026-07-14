package com.mrfloris.exportbook;

import java.util.Arrays;

/** Pure parser for a staged filename, including command-safe filenames containing spaces. */
record PublishCommandRequest(String stagedFilename, PublishCollisionMode collisionModeOverride) {
    static PublishCommandRequest parse(String[] args, int firstArgument) {
        if (firstArgument < 0 || firstArgument >= args.length) {
            throw new IllegalArgumentException(
                    "Usage: /bookexport admin publish <staged-file> [fail|unique|replace]"
            );
        }

        int filenameEnd = args.length;
        PublishCollisionMode mode = null;
        if (args.length - firstArgument >= 2) {
            mode = PublishCollisionMode.parse(args[args.length - 1]).orElse(null);
            if (mode != null) {
                filenameEnd--;
            }
        }

        String filename = String.join(" ", Arrays.copyOfRange(args, firstArgument, filenameEnd)).trim();
        if (filename.isEmpty()) {
            throw new IllegalArgumentException("Specify a staged .txt filename to publish.");
        }
        return new PublishCommandRequest(filename, mode);
    }

    PublishCollisionMode effectiveMode(PublishCollisionMode configuredDefault) {
        return collisionModeOverride == null ? configuredDefault : collisionModeOverride;
    }
}
