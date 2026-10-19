package com.kryukow.HotelReservationService.integration;

import com.kryukow.HotelReservationService.repository.StatisticEventRepository;
import com.kryukow.HotelReservationService.service.StatisticsService;
import com.opencsv.CSVReader;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Isolate collection-wide assertions from asynchronous Kafka redeliveries in the event tests.
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class StatisticsExportIntegrationTest extends BaseIntegrationTest {
    @Autowired StatisticEventRepository statistics;
    @Autowired StatisticsService service;
    @Autowired MongoTemplate mongoTemplate;

    @Test
    void exportsEveryLegacyNumericStringAndObjectIdAcrossPageBoundariesWithoutRewritingIds() throws Exception {
        statistics.deleteAll();
        List<Document> documents = new ArrayList<>();
        List<String> expectedIds = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            for (Object rawId : List.of((long) i, "legacy-" + String.format("%04d", i), new ObjectId())) {
                documents.add(new Document("_id", rawId).append("eventType", "USER_REGISTERED")
                        .append("payload", new Document("userId", (long) i))
                        .append("timestamp", java.util.Date.from(java.time.Instant.parse("2027-01-01T10:30:00Z"))));
                expectedIds.add(rawId.toString());
            }
        }
        mongoTemplate.getCollection("statistics").insertMany(documents);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.exportToCsv(output);
        try (CSVReader reader = new CSVReader(new StringReader(output.toString(StandardCharsets.UTF_8)))) {
            List<String[]> rows = reader.readAll();
            assertThat(rows).hasSize(1504);
            assertThat(rows.subList(1, rows.size())).extracting(row -> row[0])
                    .containsExactlyInAnyOrderElementsOf(expectedIds).doesNotHaveDuplicates();
            assertThat(rows.subList(1, rows.size())).allSatisfy(row -> {
                assertThat(row[1]).isEqualTo("USER_REGISTERED");
                assertThat(row[2]).isNotBlank();
                assertThat(row[6]).contains("2027-01-01T");
            });
        }
        var collection = mongoTemplate.getCollection("statistics");
        assertThat(collection.countDocuments(new Document("_id", new Document("$type", "long")))).isEqualTo(501);
        assertThat(collection.countDocuments(new Document("_id", new Document("$type", "string")))).isEqualTo(501);
        assertThat(collection.countDocuments(new Document("_id", new Document("$type", "objectId")))).isEqualTo(501);
        statistics.deleteAll();
    }

}
