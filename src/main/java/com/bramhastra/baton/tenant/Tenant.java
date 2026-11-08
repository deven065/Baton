package com.bramhastra.baton.tenant;

import java.time.Instant;
import java.util.UUID;

/** A company workspace. Everything it owns lives in its own database; this record is all Baton keeps. */
public record Tenant(UUID id, String slug, String name, boolean usesHomeDb, boolean active, Instant createdAt) {
}
