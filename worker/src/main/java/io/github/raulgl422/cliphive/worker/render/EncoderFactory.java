package io.github.raulgl422.cliphive.worker.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Picks the {@link Encoder} for a render (docs/DESIGN.md §8.4, §9.3).
 *
 * <p>The encoder list comes from {@code HardwareDetector}: the GPU encoders
 * that passed its test encode, best first (AMF > NVENC > VAAPI), then the one
 * software encoder it chose (libopenh264, or libx264 if that's the only one).
 * The user's {@code forcedEncoder} wins when it's in that list; otherwise the
 * first one is used.
 *
 * <p>Bitrate follows §9.4: 8 Mbps at 30 fps, 12 Mbps at 60 fps.
 */
public class EncoderFactory {

    private static final Logger log = LoggerFactory.getLogger(EncoderFactory.class);

    private static final String DEFAULT_SOFTWARE = "libopenh264";

    /** Last forcedEncoder warned about — read on every render, so warn once per value. */
    private static volatile String lastWarned;

    private EncoderFactory() {}

    /**
     * @param encoders usable encoders from {@code HardwareDetector}, best first
     * @param forced   {@code forcedEncoder} from {@code worker.yml}:
     *                 {@code auto}, {@code amf}, {@code nvenc}, {@code vaapi} or {@code software}
     */
    public static Encoder from(List<String> encoders, String forced, int fps) {
        int bitrate = fps >= 60 ? 12_000_000 : 8_000_000;

        String name = forced == null ? "" : forced.strip().toLowerCase(Locale.ROOT);
        switch (name) {
            case "", "auto" -> {}
            case "software" -> { return create(softwareCodec(encoders), bitrate); }
            case "amf", "nvenc", "vaapi" -> {
                String codec = "h264_" + name;
                if (encoders.contains(codec)) return create(codec, bitrate);
                warnOnce(forced, "Forced encoder '{}' did not pass the startup test — using the best detected one");
            }
            default -> warnOnce(forced, "Unknown forcedEncoder '{}' — using the best detected encoder");
        }
        return create(encoders.isEmpty() ? DEFAULT_SOFTWARE : encoders.getFirst(), bitrate);
    }

    /** The detector ends its list with the software encoder it picked. */
    private static String softwareCodec(List<String> encoders) {
        return encoders.isEmpty() || encoders.getLast().startsWith("h264_")
                ? DEFAULT_SOFTWARE : encoders.getLast();
    }

    private static Encoder create(String codec, int bitrate) {
        return switch (codec) {
            case "h264_amf"   -> new AmfEncoder(bitrate);
            case "h264_nvenc" -> new NvencEncoder(bitrate);
            case "h264_vaapi" -> new VaapiEncoder(bitrate);
            default           -> new SoftwareEncoder(bitrate, codec);
        };
    }

    private static void warnOnce(String forced, String message) {
        if (!Objects.equals(forced, lastWarned)) {
            lastWarned = forced;
            log.warn(message, forced);
        }
    }
}
