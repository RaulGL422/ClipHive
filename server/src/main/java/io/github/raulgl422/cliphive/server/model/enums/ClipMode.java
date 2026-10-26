package io.github.raulgl422.cliphive.server.model.enums;

/** Who picks the clips of a job (DESIGN.md §4, §4.1). */
public enum ClipMode {
    /** Claude proposes the clips from the content analysis. */
    AUTO,
    /** The ranges marked by hand in the superadmin become the clips. */
    MANUAL
}
