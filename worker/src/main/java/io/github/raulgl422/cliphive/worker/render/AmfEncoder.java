package io.github.raulgl422.cliphive.worker.render;

import java.util.List;

/** AMD AMF H.264 encoder (Windows). docs/DESIGN.md §9.3. */
public record AmfEncoder(int bitrate) implements Encoder {
    @Override public String codecName()           { return "h264_amf"; }
    @Override public List<String> preInputArgs()  { return List.of(); }
    @Override public List<String> preCodecArgs()  { return List.of(); }
}
