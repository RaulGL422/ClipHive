package io.github.raulgl422.cliphive.protocol.message;

/**
 * Worker -&gt; Server. First message on every connection: authenticates the
 * worker and declares what it is and what it can do.
 *
 * <p>The server validates {@code protocolMajorVersion} against
 * {@link io.github.raulgl422.cliphive.protocol.ProtocolVersion#CURRENT} (R14) before
 * replying with {@link Welcome} - a major mismatch is rejected rather than
 * risking a worker misreading an incompatible message shape.
 *
 * @param workerToken   worker's bearer token (server only stores its hash -
 *                      docs/DESIGN.md section 7, "Alta de workers")
 * @param protocolMajorVersion major version of this codebase's {@code protocol} module
 * @param workerVersion human-readable worker build/version string, for diagnostics
 * @param operatingSystem free-form OS identifier, e.g. {@code "windows"}, {@code "linux"}
 * @param capabilities  hardware/software capabilities - see {@link WorkerCapabilities}
 */
public record Hello(
        String workerToken,
        int protocolMajorVersion,
        String workerVersion,
        String operatingSystem,
        WorkerCapabilities capabilities
) implements ProtocolMessage {
}
