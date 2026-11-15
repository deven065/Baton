package com.bramhastra.baton.identity;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.job.JobKind;
import com.bramhastra.baton.job.JobRepository;
import com.bramhastra.baton.security.CryptoService;
import com.bramhastra.baton.source.SourceRepository;
import com.bramhastra.baton.source.SourceType;
import com.bramhastra.baton.source.connector.SourceConnector.StagedFile;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

/**
 * Fills the demo project with the synthetic sample sources when some are missing, so every screen
 * (decisions, experts, risk alerts, stale docs, access map) has real content on the first visit.
 * It only adds samples that are missing (by title) and never touches a company workspace.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "baton.demo.sources", havingValue = "true")
public class DemoSourceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSourceSeeder.class);

    private record Sample(SourceType type, String title, Sensitivity sensitivity, List<String> groups, String file,
                          String filename) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample(SourceType.MEETING_NOTES, "Weekly sync 2026-10-08", Sensitivity.INTERNAL, List.of(),
                    "2026-10-08-weekly-sync.md", "2026-10-08-weekly-sync.md"),
            new Sample(SourceType.CHAT_EXPORT, "Slack #dev", Sensitivity.INTERNAL, List.of(), "dev.json", "dev.json"),
            new Sample(SourceType.TASK_CSV, "Jira export", Sensitivity.INTERNAL, List.of(), "tasks.csv", "tasks.csv"),
            new Sample(SourceType.DOCUMENT, "Architecture notes", Sensitivity.CONFIDENTIAL, List.of("engineering"),
                    "architecture.md", "architecture.md"),
            new Sample(SourceType.DOCUMENT, "Vendor contract", Sensitivity.RESTRICTED, List.of("operations"),
                    "vendor-contract.md", "vendor-contract.md"),
            new Sample(SourceType.CHAT_EXPORT, "WhatsApp Annapurna Core", Sensitivity.INTERNAL, List.of(),
                    "whatsapp-annapurna-core.txt", "WhatsApp Chat with Annapurna Core.txt"),
            new Sample(SourceType.CHAT_EXPORT, "Discord #design", Sensitivity.INTERNAL, List.of(),
                    "discord-design.json", "discord-design.json"),
            new Sample(SourceType.DOCUMENT, "Payments design review (Feb)", Sensitivity.INTERNAL, List.of(),
                    "2026-02-01-payments-review.md", "2026-02-01-payments-review.md"),
            new Sample(SourceType.DOCUMENT, "PR 41: order queue", Sensitivity.INTERNAL, List.of(),
                    "2026-10-12-pr-41-order-queue.md", "2026-10-12-pr-41-order-queue.md"),
            new Sample(SourceType.DOCUMENT, "Vendor contract renewal", Sensitivity.RESTRICTED, List.of("operations"),
                    "2026-10-20-vendor-contract-renewal.md", "2026-10-20-vendor-contract-renewal.md"),
            new Sample(SourceType.DOCUMENT, "Payments chat", Sensitivity.INTERNAL, List.of(),
                    "2026-11-05-payments-chat.md", "2026-11-05-payments-chat.md"),
            new Sample(SourceType.DOCUMENT, "Launch and push notes", Sensitivity.INTERNAL, List.of(),
                    "2026-11-10-launch-and-push.md", "2026-11-10-launch-and-push.md"),
            // A runbook written before four code changes, and the commit log that made parts of it out of date.
            new Sample(SourceType.DOCUMENT, "Deploy runbook (Oct)", Sensitivity.INTERNAL, List.of(),
                    "2026-10-01-deploy-runbook.md", "2026-10-01-deploy-runbook.md"),
            new Sample(SourceType.DOCUMENT, "Repository history", Sensitivity.INTERNAL, List.of(),
                    "repository-history.txt", "repository-history.txt"));

    private final TenantRegistry tenants;
    private final TransactionTemplate tx;
    private final ProjectRepository projects;
    private final AppUserRepository users;
    private final SourceRepository sources;
    private final JobRepository jobs;
    private final CryptoService crypto;
    private final JdbcClient jdbc;

    public DemoSourceSeeder(TenantRegistry tenants, PlatformTransactionManager txManager, ProjectRepository projects,
                            AppUserRepository users, SourceRepository sources, JobRepository jobs,
                            CryptoService crypto, JdbcClient jdbc) {
        this.tenants = tenants;
        this.tx = new TransactionTemplate(txManager);
        this.projects = projects;
        this.users = users;
        this.sources = sources;
        this.jobs = jobs;
        this.crypto = crypto;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            tenants.bySlug(TenantRegistry.DEMO_SLUG).ifPresent(demo ->
                    TenantContext.run(demo, () -> tx.executeWithoutResult(s -> seed())));
        } catch (RuntimeException e) {
            log.warn("Demo sources were not loaded: {}", e.toString()); // never block start-up
        }
    }

    private void seed() {
        Project project = projects.findAll().stream()
                .filter(p -> DemoDataSeeder.PROJECT_NAME.equals(p.getName())).findFirst().orElse(null);
        AppUser lead = users.findByEmailIgnoreCase("neha@annapurna.test").orElse(null);
        if (project == null || lead == null) {
            return;
        }
        java.util.Set<String> have = new java.util.HashSet<>(jdbc.sql("SELECT title FROM source WHERE project_id = :p")
                .param("p", project.getId()).query(String.class).list());
        int added = 0;
        for (Sample s : SAMPLES) {
            if (have.contains(s.title())) {
                continue;
            }
            byte[] bytes = read("demo-seed/" + s.file());
            UUID id = sources.insert(project.getId(), s.type(), s.title(),
                    Map.of("filename", s.filename(), "size", bytes.length), s.sensitivity(), s.groups(), lead.getId());
            StagedFile staged = new StagedFile(s.filename(), "text/plain", bytes);
            sources.stageFile(id, staged, crypto.encrypt(bytes));
            jobs.enqueue(JobKind.INGEST_SOURCE, project.getId(), lead.getId(), Map.of("sourceId", id.toString()));
            added++;
        }
        if (added > 0) {
            log.info("Loaded {} sample sources into the demo project", added);
        }
    }

    private static byte[] read(String path) {
        try (InputStream in = DemoSourceSeeder.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing sample " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
