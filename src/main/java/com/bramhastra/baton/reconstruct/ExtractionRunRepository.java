package com.bramhastra.baton.reconstruct;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bramhastra.baton.source.SourceRepository;

import tools.jackson.databind.ObjectMapper;

@Repository
public class ExtractionRunRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ExtractionRunRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Run(UUID id, UUID handoverId, UUID jobId, List<String> stagesDone) {
    }

    /** The run this job already started (a resumed job after a crash), if any. */
    public Optional<Run> findRunningForJob(UUID jobId) {
        return jdbc.sql("SELECT id, handover_id, job_id, stages_done FROM extraction_run WHERE job_id = :j AND status = 'RUNNING'")
                .param("j", jobId)
                .query((rs, n) -> new Run(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), SourceRepository.textArray(rs.getArray(4))))
                .optional();
    }

    public Run start(UUID handoverId, UUID jobId, String promptVersion, String model) {
        // A run left RUNNING by a job that has since failed must not block the unique index.
        jdbc.sql("""
                UPDATE extraction_run SET status = 'FAILED', error = 'Superseded by a new run', finished_at = now()
                WHERE handover_id = :h AND status = 'RUNNING'
                """).param("h", handoverId).update();
        UUID id = jdbc.sql("""
                INSERT INTO extraction_run (handover_id, job_id, prompt_version, model) VALUES (:h, :j, :p, :m)
                RETURNING id
                """).param("h", handoverId).param("j", jobId).param("p", promptVersion).param("m", model)
                .query(UUID.class).single();
        return new Run(id, handoverId, jobId, List.of());
    }

    public void stageStarted(UUID runId, String stage) {
        jdbc.sql("UPDATE extraction_run SET stage = :s WHERE id = :id").param("id", runId).param("s", stage).update();
    }

    public void stageDone(UUID runId, String stage) {
        jdbc.sql("UPDATE extraction_run SET stages_done = array_append(stages_done, :s) WHERE id = :id")
                .param("id", runId).param("s", stage).update();
    }

    public void finish(UUID runId, String status, Map<String, ?> stats, List<String> warnings, String model) {
        jdbc.sql("""
                UPDATE extraction_run SET status = :st, stats = CAST(:stats AS jsonb), warnings = CAST(:w AS jsonb),
                                          model = COALESCE(:m, model), finished_at = now(), stage = 'DONE'
                WHERE id = :id
                """)
                .param("id", runId).param("st", status).param("stats", json.writeValueAsString(stats))
                .param("w", json.writeValueAsString(warnings)).param("m", model).update();
    }

    public void fail(UUID runId, String error, List<String> warnings) {
        jdbc.sql("""
                UPDATE extraction_run SET status = 'FAILED', error = :e, warnings = CAST(:w AS jsonb), finished_at = now()
                WHERE id = :id
                """).param("id", runId).param("e", error).param("w", json.writeValueAsString(warnings)).update();
    }
}
