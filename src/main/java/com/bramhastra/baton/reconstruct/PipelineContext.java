package com.bramhastra.baton.reconstruct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bramhastra.baton.handover.Handover;
import com.bramhastra.baton.job.JobHandler;
import com.bramhastra.baton.job.JobProgress;

/** Everything a stage needs, plus what stages report back (warnings, counts, model used). */
public class PipelineContext {

    private final Handover handover;
    private final ExtractionRunRepository.Run run;
    private final JobHandler.ProgressSink progress;
    private final Set<String> stagesDone;
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private String stage = "START";
    private int percent;
    private String modelUsed;

    public PipelineContext(Handover handover, ExtractionRunRepository.Run run, JobHandler.ProgressSink progress) {
        this.handover = handover;
        this.run = run;
        this.progress = progress;
        this.stagesDone = new LinkedHashSet<>(run.stagesDone());
    }

    public Handover handover() {
        return handover;
    }

    public ExtractionRunRepository.Run run() {
        return run;
    }

    public List<String> aliases() {
        return handover.departingAliases();
    }

    public boolean isStageDone(String name) {
        return stagesDone.contains(name);
    }

    void markStageDone(String name) {
        stagesDone.add(name);
    }

    public void warn(String message) {
        if (!warnings.contains(message)) {
            warnings.add(message);
        }
    }

    public List<String> warnings() {
        return warnings;
    }

    public void count(String key, int delta) {
        counts.merge(key, delta, Integer::sum);
    }

    public Map<String, Integer> counts() {
        return counts;
    }

    public void modelUsed(String model) {
        this.modelUsed = model;
    }

    public String modelUsed() {
        return modelUsed;
    }

    void stage(String name, int percent) {
        this.stage = name;
        this.percent = percent;
        report(null);
    }

    /** Progress inside the current stage, e.g. "Batch 23/55". */
    public void report(String message) {
        progress.report(new JobProgress(stage, percent, message == null ? stage : message, counts));
    }

    /** Lets a long stage move the bar within its own share. */
    public void reportWithin(int stageStartPercent, int stageWeightPercent, double fraction, String message) {
        this.percent = stageStartPercent + (int) (stageWeightPercent * fraction);
        report(message);
    }

    int percent() {
        return percent;
    }
}
