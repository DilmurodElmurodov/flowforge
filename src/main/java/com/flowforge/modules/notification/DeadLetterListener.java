package com.flowforge.modules.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Dead-letter handler. Records that exhausted their retries land here with diagnostic headers added by the
 * {@code DeadLetterPublishingRecoverer} (original topic/partition/offset, exception class and message).
 * The record is logged and counted; an operator can replay it after fixing the root cause.
 */
@Component
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final Counter deadLetters;

    public DeadLetterListener(MeterRegistry registry) {
        this.deadLetters = Counter.builder("notification.dead.letters")
                .description("Execution events that could not be processed and were dead-lettered")
                .register(registry);
    }

    @KafkaListener(
            topics = "${flowforge.kafka.topics.execution-events}.DLT",
            groupId = "${flowforge.kafka.consumer-groups.notification:flowforge-notification}-dlt")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        deadLetters.increment();
        log.error("DEAD LETTER key={} originalTopic={} originalOffset={} exception={} message={} payload={}",
                record.key(),
                header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                header(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                header(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                record.value());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            return null;
        }
        if (KafkaHeaders.DLT_ORIGINAL_OFFSET.equals(name) && header.value().length == Long.BYTES) {
            return String.valueOf(ByteBuffer.wrap(header.value()).getLong());
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
