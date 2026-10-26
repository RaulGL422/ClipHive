package io.github.raulgl422.cliphive.worker.render;

import java.util.List;

/**
 * Abstraction for an H.264 hardware (or software) encoder
 * (docs/DESIGN.md §2.3, §9.3).
 *
 * <p>Each implementation knows which ffmpeg codec it uses and what extra
 * arguments and filters it needs (e.g. {@code -vaapi_device} and a GPU upload
 * for VAAPI). Callers build the ffmpeg command by injecting an {@link Encoder}
 * without branching on the concrete type.
 */
public interface Encoder {

    /** The ffmpeg codec name, e.g. {@code h264_amf}, {@code libx264}. */
    String codecName();

    /**
     * Extra arguments that must appear <b>before</b> the input file in the
     * ffmpeg command (e.g. {@code -vaapi_device /dev/dri/renderD128}).
     * Empty list for most encoders.
     */
    List<String> preInputArgs();

    /**
     * Extra codec options inserted before {@code -c:v} (e.g.
     * {@code -preset veryfast} for libx264). Empty list for most encoders.
     */
    List<String> preCodecArgs();

    /**
     * Filters appended to the end of the video filter chain, e.g.
     * {@code format=nv12,hwupload} for VAAPI. It must go inside the filter
     * graph: ffmpeg won't combine {@code -vf} with {@code -filter_complex}.
     * Empty for most encoders.
     */
    default String filterTail() { return ""; }

    /** Target bitrate in bits-per-second, e.g. {@code 8_000_000}. */
    int bitrate();
}
