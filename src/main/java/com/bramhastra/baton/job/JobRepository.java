package com.bramhastra.baton.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Database-backed job queue (LLD L6). Safe with many workers thanks to FOR UPDATE SKIP LOCKED. */
@Repository
public class JobRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JobRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID enqueue(JobKind kind, UUID projectId, UUID createdBy, Map<String, ?> payload) {
        return jdbc.sql("""
                INSERT INTO job (kind, project_id, created_by, payload, progress)
                VALUES (:kind, :projectId, :createdBy, CAST(:payload AS jsonb), CAST(:progress AS jsonb))
                RETURNING id
                """)
                .param("kind", kind.name())
                .param("projectId", projectId)
                .param("createdBy", createdBy)
                .param("payload", json.writeValueAsString(payload))
                .param("progress", json.writeValueAsString(new JobProgress("QUEUED", 0, "Waiting to start", null)))
                .query(UUID.class).single();
    }

    /** Claims the oldest queued job atomically, or nothing. */
    public Optional<Job> claimNext() {
        return jdbc.sql("""
                UPDATE job SET status = 'RUNNING', attempts = attempts + 1, heartbeat_at = now(), updated_at = now()
                WHERE id = (SELECT id FROM job WHERE status = 'QUEUED' ORDER BY created_at
                            FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING *
                """)
                .query(this::map).optional();
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.sql("SELECT * FROM job WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public void saveProgress(UUID id, JobProgress progress) {
        jdbc.sql("""
                UPDATE job SET progress = CAST(:progress AS jsonb), heartbeat_at = now(), updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .param("progress", json.writeValueAsString(progress))
                .update();
    }

    public void heartbeat(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE job SET heartbeat_at = now() WHERE id IN (:ids) AND status = 'RUNNING'")
                .param("ids", ids).update();
    }

    public void succeed(UUID id, JobProgress finalProgress) {
        jdbc.sql("""
                UPDATE job SET status = 'SUCCEEDED', progress = CAST(:progress AS jsonb), updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .param("progress", json.writeValueAsString(finalProgress))
                .update();
    }

    public void fail(UUID id, String error, JobProgress finalProgress) {
        jdbc.sql("""
                UPDATE job SET status = 'FAILED', last_error = :error, progress = CAST(:progress AS jsonb),
                               updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .param("error", error)
                .param("progress", json.writeValueAsString(finalProgress))
                .update();
    }

    /** Requeues jobs whose worker died (no heartbeat for 2 minutes); gives up after 3 attempts. */
    public int reapStuck() {
        return jdbc.sql("""
                UPDATE job SET status = CASE WHEN attempts < 3 THEN 'QUEUED' ELSE 'FAILED' END,
                               last_error = COALESCE(last_error, 'Worker stopped responding'),
                               updated_at = now()
                WHERE status = 'RUNNING' AND heartbeat_at < now() - interval '2 minutes'
                """)
                .update();
    }

    public JobProgress progressOf(Job job) {
        return json.convertValue(job.progress(), JobProgress.class);
    }

    private Job map(ResultSet rs, int row) throws SQLException {
        return new Job(
                rs.getObject("id", UUID.class),
                JobKind.valueOf(rs.getString("kind")),
                Job.JobStatus.valueOf(rs.getString("status")),
                rs.getObject("project_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                json.readValue(rs.getString("payload"), MAP),
                json.readValue(rs.getString("progress"), MAP),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
