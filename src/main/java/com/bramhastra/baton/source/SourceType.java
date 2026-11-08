package com.bramhastra.baton.source;

public enum SourceType {
    GITHUB, TASK_CSV, DOCUMENT, MEETING_NOTES, CHAT_EXPORT, TESTIMONY;

    public boolean isUpload() {
        return this == TASK_CSV || this == DOCUMENT || this == MEETING_NOTES || this == CHAT_EXPORT;
    }
}
