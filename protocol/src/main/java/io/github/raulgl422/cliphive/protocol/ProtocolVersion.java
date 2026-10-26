package io.github.raulgl422.cliphive.protocol;

/**
 * Protocol version carried in {@link io.github.raulgl422.cliphive.protocol.message.Hello}.
 *
 * <p>docs/DESIGN.md section 7 (R14): a worker older/newer than the server on
 * a <b>major</b> version must not have its tasks processed with an
 * incompatible message format - the server should reject the connection and
 * the worker should tell the user to update, instead of silently
 * misinterpreting fields. Only the major version is checked; this class
 * doesn't track a minor version because the design doc doesn't call for one
 * yet - add it here first if that need shows up.
 */
public final class ProtocolVersion {

    /** Current major protocol version this codebase implements. */
    public static final int CURRENT = 1;

    private ProtocolVersion() {
    }

    /** Whether a worker reporting {@code workerMajorVersion} can talk to this server. */
    public static boolean isCompatible(int workerMajorVersion) {
        return workerMajorVersion == CURRENT;
    }
}
