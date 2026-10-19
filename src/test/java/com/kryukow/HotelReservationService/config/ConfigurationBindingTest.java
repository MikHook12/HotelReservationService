package com.kryukow.HotelReservationService.config;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationBindingTest {
    @Test
    void kafkaJsonSettingsBindToBootAndSwaggerIsAtRoot() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var map = new HashMap<String, Object>();
        yaml.getObject().forEach((key, value) -> map.put(key.toString(), value));
        // Resolve the environment-dependent setting without consulting local .env.
        map.put("spring.kafka.bootstrap-servers", "localhost:9092");
        var config = new Binder(new MapConfigurationPropertySource(map))
                .bindOrCreate("spring.kafka", KafkaProperties.class);
        assertThat(config.getProducer().getValueSerializer()).isEqualTo(JsonSerializer.class);
        assertThat(config.getConsumer().getKeyDeserializer()).isEqualTo(StringDeserializer.class);
        assertThat(config.getConsumer().getValueDeserializer()).isEqualTo(ErrorHandlingDeserializer.class);
        assertThat(config.getConsumer().getProperties().get("spring.json.trusted.packages"))
                .isEqualTo("com.kryukow.HotelReservationService.dto.event");
        assertThat(map).containsKey("springdoc.swagger-ui.enabled");
        assertThat(map).doesNotContainKey("spring.data.kafka.bootstrap-servers");
    }
}
