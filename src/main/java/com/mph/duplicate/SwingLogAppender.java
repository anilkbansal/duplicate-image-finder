package com.mph.duplicate;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import lombok.Setter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Logback appender that forwards log events to a {@link Consumer<String>}
 * supplied by the Swing UI, so all SLF4J log output appears in the
 * embedded log console in real-time.
 *
 * <p>Register it programmatically via {@link #setConsumer(Consumer)} after
 * the UI is initialised; before that, events are silently dropped.
 */
public class SwingLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Shared singleton – set by the UI before scanning starts. */
    @Setter
    private static volatile Consumer<String> consumer;

    @Override
    protected void append(ILoggingEvent event) {
        Consumer<String> c = consumer;
        if (c == null) return;

        String time  = TIME_FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        String level = event.getLevel().toString();
        String msg   = event.getFormattedMessage();
        c.accept(String.format("[%s] %-5s  %s%n", time, level, msg));
    }
}

