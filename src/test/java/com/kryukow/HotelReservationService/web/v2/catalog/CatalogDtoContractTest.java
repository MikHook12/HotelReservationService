package com.kryukow.HotelReservationService.web.v2.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CatalogDtoContractTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void roomSerializesOnlyPublicContractFieldsAndExplicitNullReason() throws Exception {
        var room = new CatalogDtos.RoomView("9223372036854775807", "10", "demo-hotel", "Room", "Description", 2, List.of(),
                CatalogService.pricing(new BigDecimal("100.01"), 2), "AVAILABLE", null,
                CatalogService.limits("Europe/Moscow", Instant.parse("2026-10-20T12:00:00Z")));
        var tree = json.readTree(json.writeValueAsString(room));
        var fields = new java.util.ArrayList<String>(); tree.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "hotelId", "hotelSlug", "name", "description", "capacity", "photos", "pricing", "availability", "unavailableReason", "dateLimits");
        assertThat(tree.get("id").isTextual()).isTrue();
        assertThat(tree.get("pricing").get("nightlyPrice").isTextual()).isTrue();
        assertThat(tree.get("pricing").get("totalPrice").asText()).isEqualTo("200.02");
        assertThat(tree.get("unavailableReason").isNull()).isTrue();
        assertThat(tree.get("dateLimits").get("hotelToday").asText()).isEqualTo("2026-10-20");
    }

    @Test
    void demoAndFictionalLocationUseExactSchemaPropertyNames() throws Exception {
        var detail = new CatalogDtos.HotelDetail("1", "demo", "Name", "city", "City", "Europe/Moscow", true, List.of(),
                "Description", "Address", new CatalogDtos.DemoLocation(new BigDecimal("55.75"), new BigDecimal("37.61"), true),
                CatalogService.limits("Europe/Moscow", Instant.parse("2026-10-20T12:00:00Z")));
        var tree = json.readTree(json.writeValueAsString(detail));
        assertThat(tree.get("isDemo").asBoolean()).isTrue();
        assertThat(tree.has("demo")).isFalse();
        assertThat(tree.get("demoLocation").get("isFictional").asBoolean()).isTrue();
        assertThat(tree.has("pricing")).isFalse();
        assertThat(tree.has("availability")).isFalse();
    }
}
