package com.bramhastra.baton.job;

import java.util.Map;

/** One progress update, as sent over SSE: {stage, percent, message, counts}. */
public record JobProgress(String stage, int percent, String message, Map<String, Integer> counts) {

    public JobProgress {
        percent = Math.max(0, Math.min(100, percent));
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }
}
