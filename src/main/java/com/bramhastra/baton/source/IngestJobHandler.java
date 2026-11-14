package com.bramhastra.baton.source;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.bramhastra.baton.sync.ContentHash;
import com.bramhastra.baton.sync.SyncService;

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
    private final SyncService sync;
    private final com.bramhastra.baton.staleness.StalenessService staleness;

    public IngestJobHandler(SourceRepository sources, List<SourceConnector> connectors, Redactor redactor,
                            Chunker chunker, Embedder embedder, CryptoService crypto, TransactionTemplate tx,
                            AuditService audit, SyncService sync, com.bramhastra.baton.staleness.StalenessService staleness) {
        this.staleness = staleness;
        this.sources = sources;
        this.connectors = connectors;
        this.redactor = redactor;
        this.chunker = chunker;
        this.embedder = embedder;
        this.crypto = crypto;
        this.tx = tx;
        this.audit = audit;
        this.sync = sync;
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
        boolean scheduled = "true".equals(job.payloadString("scheduled"));
        try {
            run(job, source, progress, scheduled);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (scheduled) {
                // A background read that fails must not take a good source out of reconstruction: keep the last
                // snapshot, show the error beside it and tell the lead.
                sync.onFailed(source, message);
            } else {
                sources.markFailed(sourceId, message);
            }
            throw e;
        }
    }

    private void run(Job job, SourceRow source, ProgressSink progress, boolean scheduled) throws Exception {
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

        // Fingerprints of the raw text (never stored otherwise): what changed since the last read?
        Map<String, String> fresh = new LinkedHashMap<>();
        for (RawDocument d : docs) {
            fresh.put(refOf(d), ContentHash.of(d.text()));
        }
        Map<String, String> before = sources.documentHashes(source.id());
        String sourceHash = ContentHash.combine(fresh);
        boolean legacy = before.values().stream().anyMatch(h -> h == null); // read before fingerprints existed
        if (scheduled && !before.isEmpty() && !legacy && sourceHash.equals(ContentHash.combine(before))) {
            sources.markChecked(source.id(), sourceHash);
            sync.onRecovered(source);
            progress.report(new JobProgress("INDEX", 100, "No changes", Map.of("unchanged", 1)));
            return;
        }
        SyncService.Change change = scheduled && !before.isEmpty() && !legacy ? diff(docs, fresh, before, source.id()) : null;

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
                UUID documentId = sources.insertDocument(source, doc, title, fresh.get(refOf(doc)));
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

        // A recording's audio stays (encrypted with the workspace key) so a claim can play the moment it cites.
        if (file != null && !(source.type() == SourceType.MEETING_RECORDING
                && com.bramhastra.baton.source.connector.RecordingConnector.isAudio(file))) {
            sources.deleteStaged(source.id());
        }
        sources.markReady(source.id(), docs.size(), chunks, redactions);
        sources.markChanged(source.id(), sourceHash);
        // New code changes or new docs: check the project's documentation against them again.
        try {
            staleness.scan(source.projectId());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(IngestJobHandler.class)
                    .warn("Staleness scan failed: {}", com.bramhastra.baton.common.SafeLog.describe(e));
        }
        if (change != null && change.total() > 0) {
            sync.onChanged(source, change);
        } else if (scheduled) {
            sync.onRecovered(source);
        }
        audit.record(job.createdBy(), "SOURCE_INGESTED", "source", source.id(), source.projectId(),
                Map.of("documents", docs.size(), "chunks", chunks, "redactions", redactions,
                        "embeddings", embedder.available()));
        progress.report(new JobProgress("INDEX", 100, "Ready",
                Map.of("documents", docs.size(), "chunks", chunks, "redactions", redactions)));
    }

    /** The key a document is known by across reads. */
    static String refOf(RawDocument d) {
        return d.externalRef() != null && !d.externalRef().isBlank() ? d.externalRef() : d.title();
    }

    private static SyncService.Change diff(List<RawDocument> docs, Map<String, String> fresh, Map<String, String> before,
                                           UUID sourceId) {
        int added = 0;
        int changed = 0;
        List<String> titles = new ArrayList<>();
        for (RawDocument d : docs) {
            String old = before.get(refOf(d));
            if (old == null) {
                added++;
            } else if (!old.equals(fresh.get(refOf(d)))) {
                changed++;
            } else {
                continue;
            }
            if (titles.size() < 3) {
                titles.add(d.title().length() > 60 ? d.title().substring(0, 57) + "..." : d.title());
            }
        }
        int removed = (int) before.keySet().stream().filter(ref -> !fresh.containsKey(ref)).count();
        return new SyncService.Change(added, changed, removed, titles);
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
