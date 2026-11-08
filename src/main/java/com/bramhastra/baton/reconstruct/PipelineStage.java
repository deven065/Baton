package com.bramhastra.baton.reconstruct;

/**
 * One step of Flow B. Stages are idempotent per run, so a resumed job can re-enter any unfinished stage.
 * Throw {@link StageSoftFailure} when the run can still continue without this stage's full result.
 */
public interface PipelineStage {

    String name();

    /** Share of the progress bar. */
    int weight();

    void execute(PipelineContext ctx) throws Exception;

    class StageSoftFailure extends RuntimeException {
        public StageSoftFailure(String message) {
            super(message);
        }
    }
}
