package com.bramhastra.baton.reconstruct;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.handover.Handover;
import com.bramhastra.baton.handover.HandoverRepository;
import com.bramhastra.baton.handover.HandoverStateMachine;
import com.bramhastra.baton.handover.HandoverStatus;
import com.bramhastra.baton.job.Job;
import com.bramhastra.baton.job.JobHandler;
import com.bramhastra.baton.job.JobKind;

/** Flow B: reconstruct and verify. Moves the handover RECONSTRUCTING → IN_REVIEW, or back on failure. */
@Component
public class ReconstructJobHandler implements JobHandler {

    public static final String PROMPT_VERSION = "extract.v1";

    private final HandoverRepository handovers;
    private final ExtractionRunRepository runs;
    private final ClaimRepository claims;
    private final ReconstructionPipeline pipeline;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final String model;

    public ReconstructJobHandler(HandoverRepository handovers, ExtractionRunRepository runs, ClaimRepository claims,
                                 ReconstructionPipeline pipeline, AuditService audit, JdbcClient jdbc,
                                 @Value("${baton.groq.extract-model}") String model) {
        this.handovers = handovers;
        this.runs = runs;
        this.claims = claims;
        this.pipeline = pipeline;
        this.audit = audit;
        this.jdbc = jdbc;
        this.model = model;
    }

    @Override
    public JobKind kind() {
        return JobKind.RECONSTRUCT;
    }

    @Override
    public void handle(Job job, ProgressSink progress) throws Exception {
        UUID handoverId = job.payloadUuid("handoverId");
        Handover h = handovers.findById(handoverId).orElseThrow(() -> new IllegalStateException("Handover is gone"));

        ExtractionRunRepository.Run run = runs.findRunningForJob(job.id()).orElse(null);
        if (run == null) {
            run = runs.start(h.id(), job.id(), PROMPT_VERSION, model);
            claims.deleteUnreviewedMachineClaims(h.id());
        }
        PipelineContext ctx = new PipelineContext(h, run, progress);
        try {
            pipeline.run(ctx);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            runs.fail(run.id(), msg, ctx.warnings());
            boolean hadClaims = jdbc.sql("SELECT count(*) FROM claim WHERE handover_id = :h").param("h", h.id())
                    .query(Integer.class).single() > 0;
            handovers.transition(h.id(), HandoverStatus.RECONSTRUCTING, HandoverStateMachine.afterFailure(hadClaims));
            throw e;
        }

        Map<String, Object> stats = new LinkedHashMap<>(ctx.counts());
        stats.put("byStatus", countBy(h.id(), "status"));
        stats.put("byType", countBy(h.id(), "type"));
        String status = ctx.warnings().stream().anyMatch(w -> w.contains("not analysed")) ? "PARTIAL" : "SUCCEEDED";
        runs.finish(run.id(), status, stats, ctx.warnings(), ctx.modelUsed());
        handovers.transition(h.id(), HandoverStatus.RECONSTRUCTING,
                HandoverStateMachine.next(HandoverStatus.RECONSTRUCTING, HandoverStateMachine.Event.JOB_DONE));
        audit.record(job.createdBy(), "RECONSTRUCTED", "handover", h.id(), h.projectId(),
                Map.of("runId", run.id().toString(), "status", status, "warnings", ctx.warnings().size()));
    }

    private Map<String, Integer> countBy(UUID handoverId, String column) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("SELECT " + column + ", count(*) FROM claim WHERE handover_id = :h GROUP BY 1 ORDER BY 1")
                .param("h", handoverId)
                .query((rs, n) -> out.put(rs.getString(1), rs.getInt(2)))
                .list();
        return out;
    }
}
