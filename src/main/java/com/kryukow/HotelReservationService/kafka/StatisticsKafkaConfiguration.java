package com.kryukow.HotelReservationService.kafka;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableScheduling
public class StatisticsKafkaConfiguration {
    @Bean
    public KafkaAdmin.NewTopics statisticsTopics(@Value("${app.kafka.topic-partitions:3}") int partitions,
                                                @Value("${app.kafka.topic-replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("user-events").partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name("booking-events").partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name("user-events.DLT").partitions(partitions).replicas(replicas).build(),
                TopicBuilder.name("booking-events.DLT").partitions(partitions).replicas(replicas).build());
    }

    // Non-default candidate keeps Boot's regular KafkaTemplate auto-configuration intact.
    @Bean(name = "statisticsDltKafkaTemplate", defaultCandidate = false)
    public KafkaTemplate<String, Object> statisticsDltKafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        DefaultKafkaProducerFactory<String, Object> dltFactory = new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(), new StringSerializer(), dltValueSerializer());
        return new KafkaTemplate<>(dltFactory) {
            @Override
            public void destroy() {
                super.destroy();
                dltFactory.destroy();
            }
        };
    }

    static DelegatingByTypeSerializer dltValueSerializer() {
        // Order matters with assignable matching: bytes must precede the Object fallback.
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JsonSerializer<>());
        return new DelegatingByTypeSerializer(delegates, true);
    }

    @Bean
    public DeadLetterPublishingRecoverer statisticsDeadLetterRecoverer(
            @Qualifier("statisticsDltKafkaTemplate") KafkaTemplate<String, Object> dltKafkaTemplate) {
        // The library default suffix can change between versions; use our provisioned topic explicitly.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltKafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        // Never commit an offset if the DLT send failed. DLT retains the original event key.
        recoverer.setFailIfSendResultIsError(true);
        return recoverer;
    }

    @Bean
    public DefaultErrorHandler statisticsErrorHandler(DeadLetterPublishingRecoverer statisticsDeadLetterRecoverer) {
        return new DefaultErrorHandler(statisticsDeadLetterRecoverer, new FixedBackOff(1000L, 2L));
    }
}
