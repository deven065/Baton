package com.bramhastra.baton.job;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Job(UUID id, JobKind kind, JobStatus status, UUID projectId, UUID createdBy,
                  Map<String, Object> payload, Map<String, Object> progress, int attempts, String lastError,
                  Instant createdAt, Instant updatedAt) {

    public String payloadString(String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }

    public UUID payloadUuid(String key) {
        String v = payloadString(key);
        return v == null ? null : UUID.fromString(v);
    }

    public enum JobStatus {
        QUEUED, RUNNING, SUCCEEDED, FAILED;

        public boolean isFinished() {
            return this == SUCCEEDED || this == FAILED;
        }
    }
}
