package com.bramhastra.baton.source;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.billing.BillingService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.job.JobKind;
import com.bramhastra.baton.job.JobRepository;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CryptoService;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;
import com.bramhastra.baton.source.connector.SourceConnector;
import com.bramhastra.baton.source.connector.SourceConnector.StagedFile;

@RestController
@RequestMapping("/api")
public class SourceController {

    private final SourceRepository sources;
    private final List<SourceConnector> connectors;
    private final JobRepository jobs;
    private final CryptoService crypto;
    private final CurrentUserProvider currentUser;
    private final AccessPolicy access;
    private final AuditService audit;
    private final JdbcClient jdbc;

    private final BillingService billing;

    public SourceController(SourceRepository sources, List<SourceConnector> connectors, JobRepository jobs,
                            CryptoService crypto, CurrentUserProvider currentUser, AccessPolicy access,
                            AuditService audit, JdbcClient jdbc, BillingService billing) {
        this.billing = billing;
        this.sources = sources;
        this.connectors = connectors;
        this.jobs = jobs;
        this.crypto = crypto;
        this.currentUser = currentUser;
        this.access = access;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * Connect a source. Multipart form fields: type, title, sensitivity, aclGroups (repeatable),
     * authorisationAttested, file (uploads) or repo + branch (GitHub).
     */
    @PostMapping(path = "/projects/{projectId}/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public CreatedSource create(@PathVariable UUID projectId,
                                @RequestParam SourceType type,
                                @RequestParam String title,
                                @RequestParam(defaultValue = "INTERNAL") Sensitivity sensitivity,
                                @RequestParam(required = false) List<String> aclGroups,
                                @RequestParam(defaultValue = "false") boolean authorisationAttested,
                                @RequestParam(required = false) String repo,
                                @RequestParam(required = false) String branch,
                                @RequestParam(required = false) String channel,
                                @RequestParam(required = false) String folder,
                                @RequestParam(required = false) MultipartFile file) throws IOException {
        CurrentUser me = currentUser.get();
        access.requireMember(me, projectId);

        if (!authorisationAttested) {
            throw new ApiException(ErrorCode.ATTESTATION_REQUIRED,
                    "Confirm that you are authorised to share this source");
        }
        Map<String, String> problems = new HashMap<>();
        if (type == SourceType.TESTIMONY) {
            problems.put("type", "Testimony is created by answering exit questions");
        }
        if (title.isBlank() || title.length() > 200) {
            problems.put("title", "Title is required (max 200 characters)");
        }
        if (!sensitivity.atMost(me.clearance())) {
            problems.put("sensitivity", "You cannot classify a source above your own clearance");
        }
        List<String> groups = normaliseGroups(aclGroups);
        List<String> unknown = groups.stream().filter(g -> !groupExists(g)).toList();
        if (!unknown.isEmpty()) {
            problems.put("aclGroups", "Unknown groups: " + unknown);
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", problems);
        }

        Map<String, Object> config = new HashMap<>();
        StagedFile staged = null;
        if (type == SourceType.GITHUB) {
            if (repo != null) {
                config.put("repo", repo.strip());
            }
            if (branch != null && !branch.isBlank()) {
                config.put("branch", branch.strip());
            }
        } else if (type == SourceType.SLACK) {
            config.put("channel", channel == null ? "" : channel.strip().toLowerCase(java.util.Locale.ROOT).startsWith("#")
                    ? channel.strip().toLowerCase(java.util.Locale.ROOT) : channel.strip());
        } else if (type == SourceType.GOOGLE_DRIVE) {
            config.put("folder", folder == null ? "" : folder.strip());
        } else {
            if (file == null || file.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                        Map.of("file", "A file is required"));
            }
            String name = file.getOriginalFilename() == null ? "upload" : stripPath(file.getOriginalFilename());
            staged = new StagedFile(name, file.getContentType(), file.getBytes());
            config.put("filename", name);
            config.put("size", file.getSize());
        }

        SourceConnector connector = connectors.stream().filter(c -> c.supports(type)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_TYPE, "Unsupported source type"));
        try {
            connector.validate(config, staged);
        } catch (UnsupportedOperationException e) {
            throw new ApiException(ErrorCode.UNSUPPORTED_TYPE, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }

        billing.requireRoom(BillingService.Resource.SOURCES);
        UUID sourceId = sources.insert(projectId, type, title.strip(), config, sensitivity, groups, me.id());
        if (staged != null) {
            sources.stageFile(sourceId, staged, crypto.encrypt(staged.bytes()));
        }
        UUID jobId = jobs.enqueue(JobKind.INGEST_SOURCE, projectId, me.id(), Map.of("sourceId", sourceId.toString()));
        audit.record(me.id(), "SOURCE_CREATED", "source", sourceId, projectId,
                Map.of("type", type.name(), "sensitivity", sensitivity.name(), "aclGroups", groups,
                        "authorisationAttested", true));
        SourceRow row = sources.findById(sourceId).orElseThrow();
        return new CreatedSource(SourceView.of(row), jobId);
    }

    @GetMapping("/projects/{projectId}/sources")
    public List<SourceView> list(@PathVariable UUID projectId) {
        CurrentUser me = currentUser.get();
        access.requireMember(me, projectId);
        return sources.listVisible(projectId, me).stream().map(SourceView::of).toList();
    }

    @GetMapping("/sources/{sourceId}")
    public SourceView get(@PathVariable UUID sourceId) {
        return SourceView.of(visible(sourceId));
    }

    @GetMapping("/sources/{sourceId}/documents")
    public List<SourceRepository.DocumentSummary> documents(@PathVariable UUID sourceId) {
        return sources.listDocuments(visible(sourceId).id());
    }

    /** Re-sync a live source (GitHub, Slack, Google Drive). Uploaded files are re-synced by uploading again. */
    @PostMapping("/sources/{sourceId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public CreatedSource sync(@PathVariable UUID sourceId) {
        CurrentUser me = currentUser.get();
        SourceRow s = visible(sourceId);
        access.requireLead(me, s.projectId());
        if (!s.type().isLive()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Upload the file again to refresh this source");
        }
        if (s.status() == SourceRow.SourceStatus.SYNCING || s.status() == SourceRow.SourceStatus.PENDING) {
            throw new ApiException(ErrorCode.RUN_IN_PROGRESS, "This source is already syncing");
        }
        sources.setStatus(s.id(), SourceRow.SourceStatus.PENDING);
        UUID jobId = jobs.enqueue(JobKind.INGEST_SOURCE, s.projectId(), me.id(), Map.of("sourceId", s.id().toString()));
        audit.record(me.id(), "SOURCE_SYNC_REQUESTED", "source", s.id(), s.projectId(), Map.of());
        return new CreatedSource(SourceView.of(sources.findById(s.id()).orElseThrow()), jobId);
    }

    /** Member of the project AND allowed by the access policy, else 404. */
    private SourceRow visible(UUID sourceId) {
        CurrentUser me = currentUser.get();
        SourceRow s = sources.findById(sourceId).orElseThrow(ApiException::notFound);
        access.requireMember(me, s.projectId());
        if (!sources.isVisible(s, me)) {
            throw ApiException.notFound();
        }
        return s;
    }

    private boolean groupExists(String name) {
        return jdbc.sql("SELECT count(*) FROM user_group WHERE name = :n").param("n", name)
                .query(Integer.class).single() > 0;
    }

    private static List<String> normaliseGroups(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String entry : raw) {
                for (String g : entry.split(",")) {
                    if (!g.isBlank()) {
                        out.add(g.strip().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String stripPath(String name) {
        String n = name.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1);
    }

    public record CreatedSource(SourceView source, UUID jobId) {
    }

    public record SourceView(UUID id, UUID projectId, SourceType type, String title, Sensitivity sensitivity,
                             List<String> aclGroups, SourceRow.SourceStatus status, int documentCount,
                             int chunkCount, int redactionCount, String lastError, Instant lastSyncedAt,
                             Instant createdAt, Map<String, Object> config) {
        static SourceView of(SourceRow s) {
            return new SourceView(s.id(), s.projectId(), s.type(), s.title(), s.sensitivity(), s.aclGroups(),
                    s.status(), s.documentCount(), s.chunkCount(), s.redactionCount(), s.lastError(),
                    s.lastSyncedAt(), s.createdAt(), s.config());
        }
    }
}
