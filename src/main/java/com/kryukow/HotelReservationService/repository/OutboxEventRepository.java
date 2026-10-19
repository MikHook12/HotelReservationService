package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    // Locks remain held until the sending transaction commits. Other instances skip them.
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL AND next_attempt_at <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
            ORDER BY next_attempt_at, created_at, id
            LIMIT :batchSize FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("batchSize") int batchSize);
}
