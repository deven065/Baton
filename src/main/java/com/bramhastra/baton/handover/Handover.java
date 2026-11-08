package com.bramhastra.baton.handover;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Handover(UUID id, UUID projectId, UUID departingUserId, String departingName, String title,
                       String scenario, HandoverStatus status, Integer currentVersionNo, List<String> departingAliases,
                       UUID createdBy, Instant createdAt, Instant updatedAt, int version) {
}
