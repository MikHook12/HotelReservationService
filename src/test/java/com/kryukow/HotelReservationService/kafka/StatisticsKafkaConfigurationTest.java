package com.kryukow.HotelReservationService.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.Map;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class StatisticsKafkaConfigurationTest {
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
