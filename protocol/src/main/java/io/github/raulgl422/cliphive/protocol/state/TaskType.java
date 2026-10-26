package io.github.raulgl422.cliphive.protocol.state;

/**
 * The two kinds of work a worker can be assigned. Both are modeled as
 * separate, independently-schedulable tasks so a worker crash only loses one
 * audio chunk or one clip, never a whole video job - see docs/DESIGN.md
 * section 6.1.
 *
 * <p>See docs/DESIGN.md section 9 for what each task actually does.
 */
public enum TaskType {
    /**
     * Transcribe one ~10-minute audio chunk (with a small overlap with its
     * neighbors) using whisper.cpp with word-level timestamps. One task per
     * chunk, parallelizable across workers - docs/DESIGN.md section 9.1.
     */
    TRANSCRIBE,

    /**
     * Render one clip: trim (with concat if interior segments were removed),
     * fps decision, 9:16 with blurred background, burned-in karaoke
     * subtitles, GPU/CPU encode. One task per clip - docs/DESIGN.md
     * section 9.2.
     */
    RENDER_CLIP
}
