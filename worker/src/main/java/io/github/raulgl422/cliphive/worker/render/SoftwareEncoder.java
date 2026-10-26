package io.github.raulgl422.cliphive.worker.render;

import java.util.List;

/**
 * CPU software encoder. Fallback when no GPU encoder is available. docs/DESIGN.md §9.3.
 *
 * <p>The bundled ffmpeg is an LGPL build, so the codec is normally
 * {@code libopenh264}; {@code libx264} is used only when a user-supplied GPL
 * ffmpeg has it and openh264 is missing (see {@link EncoderFactory}).
 */
public record SoftwareEncoder(int bitrate, String codecName) implements Encoder {

    @Override public List<String> preInputArgs()  { return List.of(); }
    // -preset is an x264 option; openh264 rejects it.
    @Override public List<String> preCodecArgs()  {
        return codecName.equals("libx264") ? List.of("-preset", "veryfast") : List.of();
    }
}
