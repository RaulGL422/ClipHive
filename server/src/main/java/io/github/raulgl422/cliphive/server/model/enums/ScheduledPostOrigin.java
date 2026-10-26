package io.github.raulgl422.cliphive.server.model.enums;

/** How a {@code scheduled_post} got its time - docs/DESIGN.md section 10.4. */
public enum ScheduledPostOrigin {
    /** Published as soon as the clip finished rendering (testing mode). */
    IMMEDIATE,
    /** Time picked by the daily Claude publication planner. */
    CLAUDE_PLAN,
    /** Edited or created by hand from the superadmin. */
    MANUAL
}
