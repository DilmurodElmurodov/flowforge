package com.flowforge.modules.execution.repository;

import com.flowforge.modules.execution.domain.ExecutionStepRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExecutionStepRecordRepository extends JpaRepository<ExecutionStepRecord, UUID> {

    List<ExecutionStepRecord> findByExecutionIdOrderByStartedAtAsc(UUID executionId);
}
