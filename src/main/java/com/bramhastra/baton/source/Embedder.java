package com.bramhastra.baton.source;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

/**
 * Local all-MiniLM-L6-v2 embeddings (384 dimensions) inside the JVM, so chunk text never leaves the
 * server for embedding. If the model cannot load, search falls back to keywords only.
 */
@Component
public class Embedder {

    private static final Logger log = LoggerFactory.getLogger(Embedder.class);
    public static final int DIMENSIONS = 384;

    private final boolean enabled;
    private volatile EmbeddingModel model;
    private volatile boolean failed;

    public Embedder(@Value("${baton.embeddings.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean available() {
        return model() != null;
    }

    public Optional<float[]> embed(String text) {
        EmbeddingModel m = model();
        return m == null ? Optional.empty() : Optional.of(m.embed(text).content().vector());
    }

    /** Embeddings in input order, or an empty list when the model is unavailable. */
    public List<float[]> embedAll(List<String> texts) {
        EmbeddingModel m = model();
        if (m == null || texts.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        return m.embedAll(segments).content().stream().map(Embedding::vector).toList();
    }

    /** pgvector text form: [0.1,0.2,...] */
    public static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private EmbeddingModel model() {
        if (!enabled || failed) {
            return null;
        }
        if (model == null) {
            synchronized (this) {
                if (model == null && !failed) {
                    try {
                        model = new AllMiniLmL6V2EmbeddingModel();
                        log.info("Loaded local MiniLM embedding model");
                    } catch (RuntimeException | LinkageError e) {
                        failed = true;
                        log.warn("Embedding model unavailable, search will be keyword-only: {}", e.toString());
                    }
                }
            }
        }
        return model;
    }
}
