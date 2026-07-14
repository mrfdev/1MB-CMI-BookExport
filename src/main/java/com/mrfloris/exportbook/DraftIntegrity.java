package com.mrfloris.exportbook;

/** Relationship between a manifest and the draft currently on disk. */
enum DraftIntegrity {
    VERIFIED,
    LEGACY_UNTRACKED,
    CONTENT_CHANGED,
    DRAFT_MISSING,
    ASSOCIATION_MISMATCH
}
