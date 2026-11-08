package com.bramhastra.baton.reconstruct;

import java.util.List;

import org.springframework.stereotype.Component;

/** Runs the stages in @Order: Signal, Extract, Verify, Consolidate, Conflict, Score, Gap, Timeline (LLD L5). */
@Component
public class ReconstructionPipeline {

    private final List<PipelineStage> stages;
    private final ExtractionRunRepository runs;

    public ReconstructionPipeline(List<PipelineStage> stages, ExtractionRunRepository runs) {
        this.stages = stages;
        this.runs = runs;
    }

    public List<String> stageNames() {
        return stages.stream().map(PipelineStage::name).toList();
    }

    public void run(PipelineContext ctx) throws Exception {
        int total = stages.stream().mapToInt(PipelineStage::weight).sum();
        int done = 0;
        for (PipelineStage s : stages) {
            int startPercent = done * 100 / total;
            if (ctx.isStageDone(s.name())) {
                done += s.weight();
                continue; // resume after a crash
            }
            ctx.stage(s.name(), startPercent);
            runs.stageStarted(ctx.run().id(), s.name());
            try {
                s.execute(ctx);
            } catch (PipelineStage.StageSoftFailure e) {
                ctx.warn(s.name() + ": " + e.getMessage());
            }
            runs.stageDone(ctx.run().id(), s.name());
            ctx.markStageDone(s.name());
            done += s.weight();
        }
        ctx.stage("DONE", 100);
    }
}
