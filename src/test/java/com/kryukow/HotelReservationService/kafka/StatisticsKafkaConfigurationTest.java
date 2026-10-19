package com.kryukow.HotelReservationService.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatisticsKafkaConfigurationTest {
    @Test
    @SuppressWarnings("unchecked")
    void recovererPublishesToProvisionedDltTopicAndOriginalPartition() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        ProducerFactory<String, Object> factory = mock(ProducerFactory.class);
        when(template.getProducerFactory()).thenReturn(factory);
        when(factory.getConfigurationProperties()).thenReturn(Map.of());
        when(template.partitionsFor("user-events.DLT")).thenReturn(List.of(
                new PartitionInfo("user-events.DLT", 2, null, new Node[0], new Node[0])));
        when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, Object> sent = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new SendResult<>(sent,
                    new RecordMetadata(new TopicPartition("user-events.DLT", 2), 17L, 0, 0L, 0, 0)));
        });
        var recoverer = new StatisticsKafkaConfiguration().statisticsDeadLetterRecoverer(template);
        var payload = new UserRegisteredEvent(null, LocalDateTime.of(2027, 1, 1, 0, 0));
        recoverer.accept(new ConsumerRecord<>("user-events", 2, 17L, "stable-id", payload),
                new IllegalArgumentException("poison"));
        ArgumentCaptor<ProducerRecord<String, Object>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo("user-events.DLT");
        assertThat(record.getValue().partition()).isEqualTo(2);
        assertThat(record.getValue().key()).isEqualTo("stable-id");
        assertThat(record.getValue().value()).isSameAs(payload);
    }

    @Test
    void dedicatedDltTemplateDoesNotReplaceRegularBootTemplate() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(StatisticsKafkaConfiguration.class)
                .withBean(KafkaAdmin.class, () -> {
                    KafkaAdmin admin = new KafkaAdmin(Map.of("bootstrap.servers", "localhost:1"));
                    admin.setAutoCreate(false);
                    return admin;
                })
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("kafkaTemplate").hasBean("statisticsDltKafkaTemplate");
                    assertThat(context.getBean(KafkaTemplate.class))
                            .isSameAs(context.getBean("kafkaTemplate"));
                    assertThat(context.getBean("statisticsDltKafkaTemplate"))
                            .isNotSameAs(context.getBean("kafkaTemplate"));
                });
    }

    @Test
    void dltSerializerPreservesMalformedBytesExactly() {
        byte[] malformed = "{broken-json: привет".getBytes(StandardCharsets.UTF_8);
        try (var serializer = StatisticsKafkaConfiguration.dltValueSerializer()) {
            assertThat(serializer.serialize("user-events.DLT", new RecordHeaders(), malformed))
                    .containsExactly(malformed);
        }
    }

    @Test
    void dltSerializerStillSerializesPoisonDtoAsJson() throws Exception {
        UserRegisteredEvent event = new UserRegisteredEvent(null, LocalDateTime.of(2027, 1, 1, 0, 0));
        try (var serializer = StatisticsKafkaConfiguration.dltValueSerializer()) {
            byte[] serialized = serializer.serialize("user-events.DLT", new RecordHeaders(), event);
            var json = new ObjectMapper().readTree(serialized);
            assertThat(json.get("userId").isNull()).isTrue();
            assertThat(json.has("localDateTime")).isTrue();
        }
    }
}
