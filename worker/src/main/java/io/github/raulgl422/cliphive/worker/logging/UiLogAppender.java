package io.github.raulgl422.cliphive.worker.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Logback appender that mirrors log lines into the worker window's log panel.
 *
 * <p>Lines logged before the window exists (startup, model download,
 * autotest) are buffered and replayed when {@link #attach} is called.
 */
public class UiLogAppender extends AppenderBase<ILoggingEvent> {

    private static final int BUFFER_SIZE = 200;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final Deque<String> buffer = new ArrayDeque<>();
    private static Consumer<String> sink;

    /** Connects the window's log panel; replays the lines logged so far. */
    public static synchronized void attach(Consumer<String> uiSink) {
        sink = uiSink;
        buffer.forEach(uiSink);
        buffer.clear();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String line = TIME.format(Instant.ofEpochMilli(event.getTimeStamp()))
                + " " + event.getLevel() + "  " + event.getFormattedMessage();
        synchronized (UiLogAppender.class) {
            if (sink != null) {
                sink.accept(line);
            } else {
                buffer.addLast(line);
                while (buffer.size() > BUFFER_SIZE) buffer.removeFirst();
            }
        }
    }
}
