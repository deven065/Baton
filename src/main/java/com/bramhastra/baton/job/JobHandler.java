package com.bramhastra.baton.job;

/** One implementation per {@link JobKind}. Throwing marks the job FAILED with the message. */
public interface JobHandler {

    JobKind kind();

    void handle(Job job, ProgressSink progress) throws Exception;

    @FunctionalInterface
    interface ProgressSink {
        void report(JobProgress progress);
    }
}
