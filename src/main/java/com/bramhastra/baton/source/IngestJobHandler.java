package com.bramhastra.baton.source;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.job.Job;
import com.bramhastra.baton.job.JobHandler;
import com.bramhastra.baton.job.JobKind;
import com.bramhastra.baton.job.JobProgress;
import com.bramhastra.baton.security.CryptoService;
import com.bramhastra.baton.source.connector.RawDocument;
import com.bramhastra.baton.source.connector.SourceConnector;
import com.bramhastra.baton.source.connector.SourceConnector.FetchRequest;
import com.bramhastra.baton.source.connector.SourceConnector.StagedFile;

/**
 * Flow A: fetch → redact → chunk → embed → store. Raw text exists only in memory inside this method;
 * only redacted text is written. Each document commits on its own, so progress survives a crash.
 */
@Component
public class IngestJobHandler implements JobHandler {

    private static final int EMBED_BATCH = 32;

    private final SourceRepository sources;
    private final List<SourceConnector> connectors;
    private final Redactor redactor;
    private final Chunker chunker;
    private final Embedder embedder;
    private final CryptoService crypto;
    private final TransactionTemplate tx;
    private final AuditService audit;

    public IngestJobHandler(SourceRepository sources, List<SourceConnector> connectors, Redactor redactor,
                            Chunker chunker, Embedder embedder, CryptoService crypto, TransactionTemplate tx,
                            AuditService audit) {
        this.sources = sources;
        this.connectors = connectors;
        this.redactor = redactor;
        this.chunker = chunker;
        this.embedder = embedder;
        this.crypto = crypto;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public JobKind kind() {
        return JobKind.INGEST_SOURCE;
    }

    @Override
    public void handle(Job job, ProgressSink progress) throws Exception {
        UUID sourceId = job.payloadUuid("sourceId");
        SourceRow source = sources.findById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Source no longer exists"));
        try {
            run(job, source, progress);
        } catch (Exception e) {
            sources.markFailed(sourceId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }
    }

    private void run(Job job, SourceRow source, ProgressSink progress) throws Exception {
        sources.setStatus(source.id(), SourceRow.SourceStatus.SYNCING);
        progress.report(new JobProgress("FETCH", 2, "Fetching " + source.title(), null));

        StagedFile file = null;
        if (source.type().isUpload()) {
            SourceRepository.SealedFile sealed = sources.loadStaged(source.id())
                    .orElseThrow(() -> new IllegalStateException("Uploaded file is missing; upload it again"));
            file = new StagedFile(sealed.filename(), sealed.mime(), crypto.decrypt(sealed.ciphertext(), sealed.iv()));
        }
        SourceConnector connector = connectors.stream().filter(c -> c.supports(source.type())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No connector for " + source.type()));
        List<RawDocument> docs = connector.fetch(
                new FetchRequest(source.type(), source.title(), source.config(), file),
                msg -> progress.report(new JobProgress("FETCH", 8, msg, null)));
        docs = docs.stream().filter(d -> d.text() != null && !d.text().isBlank()).toList();
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("No readable text found in this source");
        }

        // Re-sync replaces the previous snapshot of this source.
        sources.deleteDocuments(source.id());

        int chunks = 0;
        int redactions = 0;
        for (int i = 0; i < docs.size(); i++) {
            RawDocument doc = docs.get(i);
            Redactor.Result redacted = redactor.redact(doc.text());
            String title = redactor.redact(doc.title()).text();
            List<Chunker.Piece> pieces = chunker.split(redacted.text());
            List<float[]> vectors = embed(pieces);

            int docChunks = pieces.size();
            tx.executeWithoutResult(status -> {
                UUID documentId = sources.insertDocument(source, doc, title);
                for (Redactor.Redaction r : redacted.redactions()) {
                    sources.insertRedactionToken(documentId, r,
                            crypto.encrypt(r.original().getBytes(StandardCharsets.UTF_8)));
                }
                for (int p = 0; p < pieces.size(); p++) {
                    sources.insertChunk(documentId, source, pieces.get(p), vectors.isEmpty() ? null : vectors.get(p));
                }
            });
            chunks += docChunks;
            redactions += redacted.redactions().size();
            int percent = 10 + (int) (85.0 * (i + 1) / docs.size());
            progress.report(new JobProgress("INDEX", percent, "Indexed " + (i + 1) + " of " + docs.size()
                    + " documents", Map.of("documents", i + 1, "chunks", chunks, "redactions", redactions)));
        }

        if (file != null) {
            sources.deleteStaged(source.id());
        }
        sources.markReady(source.id(), docs.size(), chunks, redactions);
        audit.record(job.createdBy(), "SOURCE_INGESTED", "source", source.id(), source.projectId(),
                Map.of("documents", docs.size(), "chunks", chunks, "redactions", redactions,
                        "embeddings", embedder.available()));
        progress.report(new JobProgress("INDEX", 100, "Ready",
                Map.of("documents", docs.size(), "chunks", chunks, "redactions", redactions)));
    }

    private List<float[]> embed(List<Chunker.Piece> pieces) {
        if (!embedder.available()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i += EMBED_BATCH) {
            List<String> batch = pieces.subList(i, Math.min(i + EMBED_BATCH, pieces.size())).stream()
                    .map(Chunker.Piece::text).toList();
            out.addAll(embedder.embedAll(batch));
        }
        return out;
    }
}
