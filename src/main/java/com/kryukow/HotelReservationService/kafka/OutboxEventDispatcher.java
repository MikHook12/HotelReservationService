package com.kryukow.HotelReservationService.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import com.kryukow.HotelReservationService.model.OutboxEvent;
import com.kryukow.HotelReservationService.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxEventDispatcher {
    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final long ackTimeoutSeconds;

    public OutboxEventDispatcher(OutboxEventRepository repository, KafkaTemplate<String, Object> kafkaTemplate,
                                 ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                                 @Value("${app.outbox.batch-size:20}") int batchSize,
                                 @Value("${app.outbox.ack-timeout-seconds:10}") long ackTimeoutSeconds) {
        if (batchSize < 1 || ackTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Outbox batch size and ack timeout must be positive");
        }
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void dispatch() {
        transactionTemplate.executeWithoutResult(status -> {
            for (OutboxEvent event : repository.lockPendingBatch(batchSize)) {
                try {
                    // JsonSerializer receives the original DTO, preserving its JSON shape and type header.
                    Object payload = deserialize(event);
                    kafkaTemplate.send(event.getTopic(), event.getId().toString(), payload)
                            .get(ackTimeoutSeconds, TimeUnit.SECONDS);
                    event.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
                } catch (Exception e) {
                    event.setAttempts(event.getAttempts() + 1);
                    long delaySeconds = Math.min(300, 1L << Math.min(event.getAttempts(), 8));
                    event.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds));
                    log.warn("Outbox event {} failed on attempt {}; retry in {} seconds",
                            event.getId(), event.getAttempts(), delaySeconds, e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // JPA dirty checking persists acknowledgements/retry times when this transaction commits.
            // If Kafka acknowledged but the DB commit fails, redelivery is safe via the stable UUID key.
        });
    }

    private Object deserialize(OutboxEvent event) throws JsonProcessingException {
        return switch (event.getEventType()) {
            case USER_REGISTERED -> objectMapper.readValue(event.getPayload(), UserRegisteredEvent.class);
            case BOOKING_CREATED -> objectMapper.readValue(event.getPayload(), BookingCreatedEvent.class);
        };
    }
}
