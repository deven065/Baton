package com.bramhastra.baton.source.connector;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.bramhastra.baton.source.SourceType;

/** Strategy per source type. Connectors only read; Baton never writes back to a source system. */
public interface SourceConnector {

    boolean supports(SourceType type);

    /** Checks type-specific config at creation time; throws IllegalArgumentException with a user message. */
    default void validate(Map<String, Object> config, StagedFile file) {
    }

    List<RawDocument> fetch(FetchRequest request, Consumer<String> status) throws Exception;

    record StagedFile(String filename, String mime, byte[] bytes) {
        public String extension() {
            int dot = filename.lastIndexOf('.');
            return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        }
    }

    record FetchRequest(SourceType type, String title, Map<String, Object> config, StagedFile file) {
    }
}
