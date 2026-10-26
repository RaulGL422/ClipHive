package io.github.raulgl422.cliphive.worker.render;

import java.util.List;

/** NVIDIA NVENC H.264 encoder. docs/DESIGN.md §9.3. */
public record NvencEncoder(int bitrate) implements Encoder {
    @Override public String codecName()           { return "h264_nvenc"; }
    @Override public List<String> preInputArgs()  { return List.of(); }
    @Override public List<String> preCodecArgs()  { return List.of(); }
}
