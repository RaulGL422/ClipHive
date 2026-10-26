package io.github.raulgl422.cliphive.protocol.codec;

/**
 * Wraps a JSON encode/decode failure from {@link ProtocolCodec}. Unchecked on
 * purpose: callers on both the server and worker side treat a malformed
 * protocol message as a bug (in this codebase or in a mismatched protocol
 * version), not a recoverable condition they need to be forced to handle
 * case by case.
 */
public class ProtocolCodecException extends RuntimeException {
    public ProtocolCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
