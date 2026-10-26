package io.github.raulgl422.cliphive.protocol.codec;

import io.github.raulgl422.cliphive.protocol.message.MessageDirection;
import io.github.raulgl422.cliphive.protocol.message.ProtocolMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a {@link ProtocolMessage} into the JSON frame sent over the
 * server&lt;-&gt;worker WebSocket, and back. This is the single place that
 * owns the wire format so server and worker can never drift apart on how a
 * message is encoded - see {@link ProtocolMessage} for why JSON-with-a-type-field
 * was chosen.
 *
 * <p>Built on Jackson 3 ({@code tools.jackson.*}) via {@link JsonMapper#builder()}
 * - Jackson 3's {@code ObjectMapper} is immutable once built (unlike Jackson 2's,
 * which could be reconfigured after construction, a source of subtle
 * thread-safety bugs), so every option is set in the builder chain below.
 *
 * <p>Unknown JSON properties are ignored on decode rather than rejected, so a
 * worker one minor release ahead/behind the server (same major protocol
 * version - see {@link io.github.raulgl422.cliphive.protocol.ProtocolVersion}) doesn't
 * break the connection over a field neither side strictly needs yet.
 */
public final class ProtocolCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private ProtocolCodec() {
    }

    /** Serializes a message to its JSON wire form. */
    public static String encode(ProtocolMessage message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JacksonException e) {
            throw new ProtocolCodecException("Failed to encode " + message.getClass().getSimpleName(), e);
        }
    }

    /**
     * Parses a JSON wire frame back into the concrete {@link ProtocolMessage}
     * it represents, based on its {@code type} field.
     */
    public static ProtocolMessage decode(String json) {
        try {
            return MAPPER.readValue(json, ProtocolMessage.class);
        } catch (JacksonException e) {
            throw new ProtocolCodecException("Failed to decode protocol message: " + json, e);
        }
    }

    /**
     * Like {@link #decode(String)}, but also checks the message travels in
     * {@code expectedDirection} (see {@link MessageDirection}) before
     * returning it. Use this on each side's receive path - the server
     * decoding with {@code WORKER_TO_SERVER} and the worker decoding with
     * {@code SERVER_TO_WORKER} - so a message sent on the wrong channel
     * (a bug on either side) is rejected immediately instead of reaching
     * business logic that assumed the other direction.
     *
     * @throws ProtocolCodecException if the JSON is malformed, or decodes to
     *                                a message type that doesn't travel in
     *                                {@code expectedDirection}
     */
    public static ProtocolMessage decode(String json, MessageDirection expectedDirection) {
        ProtocolMessage message = decode(json);
        MessageDirection actual = MessageDirection.of(message);
        if (actual != expectedDirection) {
            throw new ProtocolCodecException(
                    "Expected a " + expectedDirection + " message but got "
                            + message.getClass().getSimpleName() + " (" + actual + "): " + json,
                    null);
        }
        return message;
    }
}
