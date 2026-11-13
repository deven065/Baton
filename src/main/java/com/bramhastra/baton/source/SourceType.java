package com.bramhastra.baton.source;

public enum SourceType {
    GITHUB, SLACK, GOOGLE_DRIVE, TASK_CSV, DOCUMENT, MEETING_NOTES, MEETING_RECORDING, CHAT_EXPORT, TESTIMONY;

    /** Read live from a tool with the workspace's own credentials; can be synced again. */
    public boolean isLive() {
        return this == GITHUB || this == SLACK || this == GOOGLE_DRIVE;
    }

    public boolean isUpload() {
        return this == TASK_CSV || this == DOCUMENT || this == MEETING_NOTES || this == MEETING_RECORDING
                || this == CHAT_EXPORT;
    }
}
