package com.flowforge.modules.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Ids of publishable events, oldest first. Only ids are fetched; each event is then processed in its own tx. */
    @Query("""
            select e.id from OutboxEvent e
            where e.status = com.flowforge.modules.outbox.OutboxStatus.PENDING
              and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            order by e.createdAt asc
            """)
    List<UUID> findPublishableIds(@Param("now") Instant now, Pageable pageable);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    long countByStatus(OutboxStatus status);
}
