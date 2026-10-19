package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.StatisticEvent;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatisticsExportRepositoryTest {
    private final MongoTemplate template = mock(MongoTemplate.class);
    private final StatisticsExportRepository repository = new StatisticsExportRepository(template);

    @Test
    void firstPageIsBoundedAndSortedByRawId() {
        when(template.find(any(Query.class), eq(Document.class), eq("statistics"))).thenReturn(List.of());
        assertThat(repository.findNextPage(null, 500).events()).isEmpty();
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(template).find(query.capture(), eq(Document.class), eq("statistics"));
        assertThat(query.getValue().getQueryObject()).isEmpty();
        assertThat(query.getValue().getSortObject()).isEqualTo(new Document("_id", 1));
        assertThat(query.getValue().getLimit()).isEqualTo(500);
    }

    @Test
    void continuationComparesAcrossBsonTypesWithoutConvertingCursor() {
        when(template.find(any(Query.class), eq(Document.class), eq("statistics"))).thenReturn(List.of());
        ObjectId objectId = new ObjectId();
        for (Object rawId : List.of(42L, "$literal-string-id", objectId)) {
            repository.findNextPage(rawId, 500);
        }
        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        verify(template, times(3)).find(queries.capture(), eq(Document.class), eq("statistics"));
        List<Object> ids = List.of(42L, "$literal-string-id", objectId);
        for (int i = 0; i < ids.size(); i++) {
            assertThat(queries.getAllValues().get(i).getQueryObject()).isEqualTo(
                    new Document("$expr", new Document("$gt", List.of("$_id", new Document("$literal", ids.get(i))))));
        }
    }

    @Test
    void mapsDisplayIdInMemoryAndPreservesRawObjectIdAsCursorWithoutRewritingDocument() {
        MongoConverter converter = mock(MongoConverter.class);
        ObjectId rawId = new ObjectId();
        Document original = new Document("_id", rawId).append("eventType", "USER_REGISTERED");
        StatisticEvent event = new StatisticEvent();
        event.setId(rawId.toHexString());
        when(template.getConverter()).thenReturn(converter);
        when(template.find(any(Query.class), eq(Document.class), eq("statistics"))).thenReturn(List.of(original));
        when(converter.read(eq(StatisticEvent.class), any(Document.class))).thenReturn(event);
        var page = repository.findNextPage(null, 500);
        assertThat(page.lastId()).isSameAs(rawId);
        assertThat(page.events()).containsExactly(event);
        assertThat(original.get("_id")).isSameAs(rawId);
        verify(converter).read(StatisticEvent.class,
                new Document("_id", rawId.toHexString()).append("eventType", "USER_REGISTERED"));
    }
}
