package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.StatisticEvent;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class StatisticsExportRepository {
    private final MongoTemplate mongoTemplate;

    public ExportPage findNextPage(Object afterId, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Export page size must be positive");
        }
        // Ordinary _id: {$gt: value} brackets by BSON type and skips legacy ObjectId/numeric IDs.
        // $expr compares all BSON types in the same order as the _id sort; $literal protects string IDs.
        Document filter = afterId == null ? new Document() : new Document("$expr",
                new Document("$gt", List.of("$_id", new Document("$literal", afterId))));
        Query query = new BasicQuery(filter).with(Sort.by(Sort.Direction.ASC, "_id"))
                .limit(size).collation(Collation.of("simple"));
        List<Document> documents = mongoTemplate.find(query, Document.class, "statistics");
        Object lastId = documents.isEmpty() ? null : documents.get(documents.size() - 1).get("_id");
        List<StatisticEvent> events = documents.stream().map(document -> {
            // Normalize only this in-memory copy for the String @Id entity. Keep the original BSON
            // value above as the continuation token and never rewrite historical Mongo documents.
            Document copy = new Document(document);
            copy.put("_id", document.get("_id").toString());
            return mongoTemplate.getConverter().read(StatisticEvent.class, copy);
        }).toList();
        return new ExportPage(events, lastId);
    }

    public record ExportPage(List<StatisticEvent> events, Object lastId) { }
}
