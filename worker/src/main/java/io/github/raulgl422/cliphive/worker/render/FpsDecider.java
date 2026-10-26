package io.github.raulgl422.cliphive.worker.render;

/**
 * Decides the output fps for a clip (docs/DESIGN.md §9.4).
 *
 * <p>Mode {@code "auto"} implements only part of §9.4: it keeps the source fps
 * when it's known and at most 60, else 30. The 60 fps decision (metadata,
 * Claude's content type, {@code mpdecimate}) isn't implemented, and
 * {@code RenderHandler} passes the source fps as unknown, so {@code auto}
 * renders at 30.
 */
public class FpsDecider {

    private FpsDecider() {}

    /**
     * Returns the target fps.
     *
     * @param fpsMode   "auto", "30" or "60" (from job/task parameters)
     * @param sourceFps the source video's fps (0 = unknown)
     */
    public static int decide(String fpsMode, int sourceFps) {
        if ("60".equals(fpsMode)) return 60;
        if ("30".equals(fpsMode)) return 30;
        // auto: use source fps if known and ≤ 60; default 30
        if (sourceFps > 0 && sourceFps <= 60) return sourceFps;
        return 30;
    }
}
