package com.kryukow.HotelReservationService.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.kafka.OutboxEventDispatcher;
import com.kryukow.HotelReservationService.model.OutboxEvent;
import com.kryukow.HotelReservationService.repository.OutboxEventRepository;
import com.kryukow.HotelReservationService.repository.UserRepository;
import com.kryukow.HotelReservationService.service.DomainEventPublisher;
import com.kryukow.HotelReservationService.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxIntegrationTest extends BaseIntegrationTest {
    @Autowired DomainEventPublisher publisher;
    @Autowired OutboxEventRepository repository;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired KafkaTemplate<String, Object> kafka;
    @Autowired ObjectMapper objectMapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void futureEventIsSkippedUntilALaterPollFindsItDue() {
        var tx = new TransactionTemplate(transactionManager);
        UUID id = tx.execute(status -> {
            repository.deleteAll();
            publisher.publishUserRegistered(88L);
            OutboxEvent event = repository.findAll().getFirst();
            event.setNextAttemptAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(1));
            return event.getId();
        });
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> acknowledged = org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(acknowledged.send(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(id.toString()), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(org.mockito.Mockito.mock(org.springframework.kafka.support.SendResult.class)));
        var dispatcher = new OutboxEventDispatcher(repository, acknowledged, objectMapper, transactionManager, 20, 5);

        dispatcher.dispatch();
        org.mockito.Mockito.verifyNoInteractions(acknowledged);
        assertThat(repository.findById(id).orElseThrow().getPublishedAt()).isNull();

        // Advance eligibility against the same database clock used by lockPendingBatch.
        jdbc.update("UPDATE outbox_events SET next_attempt_at=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')-INTERVAL '1 second' WHERE id=?", id);
        dispatcher.dispatch();
        org.mockito.Mockito.verify(acknowledged).send(org.mockito.ArgumentMatchers.eq("user-events"),
                org.mockito.ArgumentMatchers.eq(id.toString()), org.mockito.ArgumentMatchers.any());
        assertThat(repository.findById(id).orElseThrow().getPublishedAt()).isNotNull();
        repository.deleteAll();
    }

    @Test
    void refusesPublishingOutsideCallerTransaction() {
        assertThatThrownBy(() -> publisher.publishUserRegistered(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void registrationAndOutboxRollbackTogether() {
        String name = "rollback-" + UUID.randomUUID();
        UserCreateUpdateRequestDto request = new UserCreateUpdateRequestDto();
        request.setName(name);
        request.setEmail(name + "@test.com");
        request.setPassword("password123");
        AtomicReference<UUID> eventId = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Long userId = userService.create(request).getId();
            OutboxEvent event = repository.findAll().stream()
                    .filter(e -> e.getPayload().contains("\"userId\":" + userId + ","))
                    .findFirst().orElseThrow();
            eventId.set(event.getId());
            status.setRollbackOnly();
        });
        assertThat(users.existsByName(name)).isFalse();
        assertThat(repository.findById(eventId.get())).isEmpty();
    }

    @Test
    void bookingOutboxParticipatesInRollback() {
        AtomicReference<UUID> eventId = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishBookingCreated(987654321L, 7L, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
            eventId.set(repository.findAll().stream()
                    .filter(e -> e.getPayload().contains("987654321"))
                    .findFirst().orElseThrow().getId());
            status.setRollbackOnly();
        });
        assertThat(repository.findById(eventId.get())).isEmpty();
    }

    @Test
    void concurrentTransactionsSkipLockedOutboxRows() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            repository.deleteAll();
            publisher.publishUserRegistered(1L);
            publisher.publishUserRegistered(2L);
        });
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<UUID> first = executor.submit(() -> tx.execute(status -> {
                UUID id = repository.lockPendingBatch(1).get(0).getId();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return id;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<OutboxEvent> remaining = tx.execute(status -> repository.lockPendingBatch(10));
            assertThat(remaining).hasSize(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNotEqualTo(remaining.get(0).getId());
        } finally {
            release.countDown();
            executor.shutdownNow();
            repository.deleteAll();
        }
    }

    @Test
    void unpublishedEventSurvivesFailedAckThenPublishesWithSameId() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            repository.deleteAll();
            publisher.publishUserRegistered(42L);
        });
        OutboxEvent event = repository.findAll().get(0);
        // Real PostgreSQL transaction with a deterministic failed Kafka ack, independent of broker timing.
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> failing = org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(failing.send(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(event.getId().toString()), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));
        new OutboxEventDispatcher(repository, failing, objectMapper, transactionManager, 20, 5).dispatch();
        OutboxEvent pending = repository.findById(event.getId()).orElseThrow();
        assertThat(pending.getPublishedAt()).isNull();
        assertThat(pending.getAttempts()).isEqualTo(1);
        tx.executeWithoutResult(status -> repository.findById(event.getId()).orElseThrow()
                .setNextAttemptAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1)));
        new OutboxEventDispatcher(repository, kafka, objectMapper, transactionManager, 20, 10).dispatch();
        assertThat(repository.findById(event.getId()).orElseThrow().getPublishedAt()).isNotNull();
        repository.deleteAll();
    }

    @Test
    void cancellationRequiresCallerTransactionAndRollsBackWithIt() {
        LocalDate checkIn = LocalDate.of(2027, 1, 1);
        assertThatThrownBy(() -> publisher.publishBookingCancelled(99L, 42L, 7L, checkIn, checkIn.plusDays(2)))
                .isInstanceOf(IllegalTransactionStateException.class);
        AtomicReference<UUID> eventId = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishBookingCancelled(99L, 42L, 7L, checkIn, checkIn.plusDays(2));
            eventId.set(repository.findAll().stream()
                    .filter(event -> event.getEventType() == com.kryukow.HotelReservationService.model.EventType.BOOKING_CANCELLED)
                    .findFirst().orElseThrow().getId());
            status.setRollbackOnly();
        });
        assertThat(repository.findById(eventId.get())).isEmpty();
    }

}
