package com.bramhastra.baton.source;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bramhastra.baton.common.Sensitivity;

public record SourceRow(UUID id, UUID projectId, SourceType type, String title, Map<String, Object> config,
                        Sensitivity sensitivity, List<String> aclGroups, UUID attestedBy, SourceStatus status,
                        int documentCount, int chunkCount, int redactionCount, String lastError,
                        Instant lastSyncedAt, Instant createdAt) {

    public enum SourceStatus { PENDING, SYNCING, READY, FAILED }
}
