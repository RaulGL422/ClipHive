package io.github.raulgl422.cliphive.worker.render;

import java.util.List;

/**
 * AMD VAAPI H.264 encoder (Linux). Requires the {@code -vaapi_device} pre-input
 * argument and frames uploaded to the GPU at the end of the filter chain.
 * docs/DESIGN.md §9.3.
 */
public record VaapiEncoder(int bitrate) implements Encoder {

    private static final String DEVICE = "/dev/dri/renderD128";

    @Override public String codecName()           { return "h264_vaapi"; }
    @Override public List<String> preInputArgs()  { return List.of("-vaapi_device", DEVICE); }
    @Override public List<String> preCodecArgs()  { return List.of(); }
    @Override public String filterTail()          { return "format=nv12,hwupload"; }
}
