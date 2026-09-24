package com.flowforge.modules.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import com.flowforge.core.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point for domain modules to record integration events.
 *
 * <p>{@code Propagation.MANDATORY} is deliberate: an outbox row that is not written in the caller's transaction
 * would defeat the purpose of the pattern, so calling this outside a transaction is a programming error.
 */
@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final Clock clock;

    @Autowired
    public OutboxService(OutboxRepository repository) {
        this(repository, Clock.systemUTC());
    }

    OutboxService(OutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        UUID tenantId = TenantContext.get().orElse(null);
        return repository.save(new OutboxEvent(tenantId, aggregateType, aggregateId, eventType, payload, clock.instant()));
    }
}
