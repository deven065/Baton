package com.bramhastra.baton.audit;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes to the append-only audit_log. Call it inside the same transaction as the change,
 * so there is never a change without its audit row.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID actorId, String action, String entityType, UUID entityId, UUID projectId,
                       Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, entity_type, entity_id, project_id, details)
                VALUES (:actor, :action, :entityType, :entityId, :projectId, CAST(:details AS jsonb))
                """)
                .param("actor", actorId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("projectId", projectId)
                .param("details", json.writeValueAsString(details == null ? Map.of() : details))
                .update();
    }
}
