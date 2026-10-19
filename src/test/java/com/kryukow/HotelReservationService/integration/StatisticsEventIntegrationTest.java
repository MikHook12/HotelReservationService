package com.kryukow.HotelReservationService.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import com.kryukow.HotelReservationService.kafka.OutboxEventDispatcher;
import com.kryukow.HotelReservationService.kafka.StatisticsEventListener;
import com.kryukow.HotelReservationService.model.OutboxEvent;
import com.kryukow.HotelReservationService.repository.OutboxEventRepository;
import com.kryukow.HotelReservationService.repository.StatisticEventRepository;
import com.kryukow.HotelReservationService.service.DomainEventPublisher;
import com.kryukow.HotelReservationService.service.StatisticsService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StatisticsEventIntegrationTest extends BaseIntegrationTest {
    @Autowired DomainEventPublisher publisher;
    @Autowired OutboxEventRepository outbox;
    @Autowired StatisticEventRepository statistics;
    @Autowired StatisticsEventListener listener;
    @Autowired StatisticsService service;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired KafkaTemplate<String, Object> kafka;
    @Autowired @Qualifier("statisticsDltKafkaTemplate") KafkaTemplate<String, Object> dltKafka;
    @Autowired ObjectMapper objectMapper;
    @Autowired Environment environment;

    @Test
    void committedOutboxFlowsThroughKafkaAndRedeliveryRemainsOneMongoDocument() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outbox.deleteAll();
            publisher.publishUserRegistered(1234567L);
        });
        OutboxEvent event = outbox.findAll().get(0);
        new OutboxEventDispatcher(outbox, kafka, objectMapper, transactionManager, 20, 10).dispatch();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(statistics.findById(event.getId().toString())).isPresent());
        UserRegisteredEvent payload = objectMapper.readValue(event.getPayload(), UserRegisteredEvent.class);
        kafka.send("user-events", event.getId().toString(), payload).get();
        // Direct redelivery additionally proves Mongo upsert deterministically, without a timing-only count assertion.
        listener.handlerUserRegistered(payload, event.getId().toString());
        assertThat(statistics.findAllById(List.of(event.getId().toString()))).hasSize(1);
        assertThat(outbox.findById(event.getId()).orElseThrow().getPublishedAt()).isNotNull();
        outbox.deleteAll();
    }

    @Test
    void mongoRoundTripExportsDatesAndNumericIdsAsUtf8Csv() throws Exception {
        String key = UUID.randomUUID().toString();
        listener.handlerBookingRegistered(new BookingCreatedEvent(42L, 7L,
                LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 3), LocalDateTime.of(2027, 1, 1, 10, 30)), key);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.exportToCsv(output);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("\"" + key + "\",\"BOOKING_CREATED\",\"42\",\"7\",\"2027-01-02\",\"2027-01-03\"");
    }

    @Test
    void malformedJsonBytesReachDltWithoutBase64Encoding() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] original = "{malformed-json: привет".getBytes(StandardCharsets.UTF_8);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, environment.getRequiredProperty("spring.kafka.bootstrap-servers"),
                ConsumerConfig.GROUP_ID_CONFIG, "raw-dlt-test-" + key,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            consumer.subscribe(List.of("user-events.DLT"));
            int sourcePartition = dltKafka.send("user-events", key, original).get().getRecordMetadata().partition();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key())) {
                        assertThat(record.value()).containsExactly(original);
                        assertThat(record.partition()).isEqualTo(sourcePartition);
                        return true;
                    }
                }
                return false;
            });
        }
        assertThat(statistics.findById(key)).isEmpty();
    }

    @Test
    void malformedEventGoesToDltWithOriginalKeyAfterBoundedRetries() throws Exception {
        String key = UUID.randomUUID().toString();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, environment.getRequiredProperty("spring.kafka.bootstrap-servers"),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + key,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            consumer.subscribe(List.of("user-events.DLT"));
            // The DTO is valid JSON; a null required user ID makes it a poison message for the listener.
            int sourcePartition = kafka.send("user-events", key, new UserRegisteredEvent(null, LocalDateTime.now()))
                    .get().getRecordMetadata().partition();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key())) {
                        assertThat(record.partition()).isEqualTo(sourcePartition);
                        return true;
                    }
                }
                return false;
            });
        }
        assertThat(statistics.findById(key)).isEmpty();
    }
}
