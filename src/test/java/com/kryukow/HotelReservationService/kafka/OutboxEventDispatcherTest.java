package com.kryukow.HotelReservationService.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import com.kryukow.HotelReservationService.model.EventType;
import com.kryukow.HotelReservationService.model.OutboxEvent;
import com.kryukow.HotelReservationService.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventDispatcherTest {
    @Mock OutboxEventRepository repository;
    @Mock KafkaTemplate<String, Object> kafka;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;
    private OutboxEventDispatcher dispatcher;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        dispatcher = new OutboxEventDispatcher(repository, kafka, mapper, transactionManager, 2, 1);
    }

    private OutboxEvent event() throws Exception {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTopic("user-events");
        event.setEventType(EventType.USER_REGISTERED);
        event.setPayload(mapper.writeValueAsString(new UserRegisteredEvent(1L, LocalDateTime.now(ZoneOffset.UTC))));
        event.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
        return event;
    }

    @Test
    void marksPublishedOnlyAfterAckAndUsesOutboxUuidAsKafkaKey() throws Exception {
        OutboxEvent event = event();
        when(repository.lockPendingBatch(2)).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, Object>> ack = new CompletableFuture<>();
        when(kafka.send(eq("user-events"), eq(event.getId().toString()), any())).thenAnswer(call -> {
            assertThat(event.getPublishedAt()).isNull();
            ack.complete(null);
            return ack;
        });
        dispatcher.dispatch();
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
        verify(transactionManager).commit(transactionStatus);
        verify(kafka).send(eq("user-events"), eq(event.getId().toString()), isA(UserRegisteredEvent.class));
    }

    @Test
    void failedEventRemainsPendingAndDoesNotPreventOtherEventsFromPublishing() throws Exception {
        OutboxEvent failed = event();
        OutboxEvent next = event();
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        when(repository.lockPendingBatch(2)).thenReturn(List.of(failed, next));
        when(kafka.send(anyString(), eq(failed.getId().toString()), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));
        when(kafka.send(anyString(), eq(next.getId().toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        dispatcher.dispatch();
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isAfter(before);
        assertThat(next.getPublishedAt()).isNotNull();
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void missingAckSchedulesRetryThenReusesSameKey() throws Exception {
        OutboxEvent event = event();
        when(repository.lockPendingBatch(2)).thenReturn(List.of(event));
        when(kafka.send(anyString(), eq(event.getId().toString()), any()))
                .thenReturn(new CompletableFuture<>())
                .thenReturn(CompletableFuture.completedFuture(null));
        dispatcher.dispatch();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        dispatcher.dispatch();
        assertThat(event.getPublishedAt()).isNotNull();
        verify(kafka, times(2)).send(anyString(), eq(event.getId().toString()), any());
    }

    @Test
    void malformedPayloadRemainsPendingWithoutPoisoningTheWholeBatch() throws Exception {
        OutboxEvent event = event();
        event.setPayload("not JSON");
        when(repository.lockPendingBatch(2)).thenReturn(List.of(event));
        dispatcher.dispatch();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        verifyNoInteractions(kafka);
    }
}
