package com.bramhastra.baton.reconstruct.stage;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;

/**
 * Turns what the sources cannot answer into a short exit interview (6 to 12 questions): conflicts, work
 * with no successor, critical claims without proof, and standard handover topics nobody wrote down.
 * Questions are built by code from templates, so they are predictable and never invent facts.
 */
@Component
@Order(70)
public class GapStage implements PipelineStage {

    static final int MAX_QUESTIONS = 12;

    private record Q(String kind, UUID claimId, UUID relatedClaimId, String question, int priority) {
    }

    private record Topic(String question, Predicate<ClaimRepository.ClaimRow> covered, int priority) {
    }

    private static final List<Topic> CHECKLIST = List.of(
            new Topic("How do we deploy to production, step by step, and who can do it after you leave?",
                    c -> c.type().equals("PROCESS") && matches(c, "deploy|release|ship"), 2),
            new Topic("Which accounts, admin rights, domains or billing are in your name, and who should take them over?",
                    c -> c.type().equals("ACCESS"), 1),
            new Topic("Where are the API keys and secrets kept, and how does someone rotate them?",
                    c -> matches(c, "secret|api key|\\[api_key|credential|env"), 2),
            new Topic("How does a new engineer get the project running locally?",
                    c -> c.type().equals("PROCESS") && matches(c, "local|setup|install|run "), 3),
            new Topic("What is the next deadline, and what must be done before it?",
                    c -> c.attr("dueDate") != null || (c.type().equals("DECISION") && matches(c, "launch|deadline|release|demo")), 2));

    private final ClaimRepository claims;
    private final JdbcClient jdbc;

    public GapStage(ClaimRepository claims, JdbcClient jdbc) {
        this.claims = claims;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "GAP";
    }

    @Override
    public int weight() {
        return 3;
    }

    @Override
    public void execute(PipelineContext ctx) {
        UUID h = ctx.handover().id();
        jdbc.sql("DELETE FROM gap_question WHERE handover_id = :h AND status IN ('OPEN', 'UNANSWERED')")
                .param("h", h).update();
        Set<String> alreadyAnswered = new HashSet<>(jdbc.sql(
                "SELECT question FROM gap_question WHERE handover_id = :h").param("h", h).query(String.class).list());

        List<ClaimRepository.ClaimRow> all = claims.claims(h).stream()
                .filter(c -> !c.reviewState().equals("REJECTED")).toList();
        Map<UUID, ClaimRepository.ClaimRow> byId = all.stream()
                .collect(Collectors.toMap(ClaimRepository.ClaimRow::id, c -> c));
        Map<UUID, List<ClaimRepository.EvidenceRow>> evidence = claims.evidence(h).stream().filter(ClaimRepository.EvidenceRow::valid)
                .collect(Collectors.groupingBy(ClaimRepository.EvidenceRow::claimId));
        List<Q> questions = new ArrayList<>();

        // 1. Conflicts
        for (ClaimRepository.LinkRow l : claims.links(h)) {
            if (!l.kind().equals("CONFLICTS_WITH") || l.resolved()) {
                continue;
            }
            ClaimRepository.ClaimRow newer = byId.get(l.from());
            ClaimRepository.ClaimRow older = byId.get(l.to());
            if (newer == null || older == null) {
                continue;
            }
            questions.add(new Q("CONFLICT", newer.id(), older.id(),
                    "Two sources disagree about the " + (newer.subject() == null ? "same topic" : newer.subject()) + ": "
                            + cite(older, evidence) + " versus " + cite(newer, evidence)
                            + ". Which is current, and why did it change?", 1));
        }

        // 2. Work that will have no owner
        for (ClaimRepository.ClaimRow c : all) {
            if (!c.type().equals("OPEN_WORK") && !c.type().equals("ACCESS")) {
                continue;
            }
            boolean departingOwns = "true".equals(c.attr("ownerIsDeparting"));
            boolean noOwner = c.attr("owner") == null || c.attr("owner").isBlank();
            if (c.type().equals("ACCESS")) {
                questions.add(new Q("MISSING_OWNER", c.id(), null,
                        "How should this be transferred, and to whom: \"" + c.title() + "\"?", 1));
            } else if (departingOwns) {
                questions.add(new Q("MISSING_OWNER", c.id(), null,
                        "Who should take over \"" + c.title() + "\" after you leave, and what is the next step?",
                        c.critical() ? 1 : 2));
            } else if (noOwner) {
                questions.add(new Q("MISSING_OWNER", c.id(), null,
                        "\"" + c.title() + "\" has no owner in the sources. Who owns it?", c.critical() ? 1 : 2));
            }
        }

        // 3. Critical claims without proof
        for (ClaimRepository.ClaimRow c : all) {
            if (c.critical() && (c.status().equals("INFERRED") || c.status().equals("MISSING_EVIDENCE"))) {
                questions.add(new Q("MISSING_EVIDENCE", c.id(), null,
                        "Can you confirm this: \"" + c.title() + "\"? Where is it written down?", 2));
            }
        }

        // 4. Standard handover topics nobody covered
        for (Topic t : CHECKLIST) {
            if (all.stream().noneMatch(t.covered())) {
                questions.add(new Q("MISSING_CHECKLIST", null, null, t.question(), t.priority()));
            }
        }

        String status = "SUDDEN".equals(ctx.handover().scenario()) ? "UNANSWERED" : "OPEN";
        List<Q> chosen = questions.stream()
                .filter(q -> !alreadyAnswered.contains(q.question()))
                .sorted(Comparator.comparingInt(Q::priority))
                .limit(MAX_QUESTIONS)
                .toList();
        for (Q q : chosen) {
            jdbc.sql("""
                    INSERT INTO gap_question (handover_id, claim_id, related_claim_id, kind, question, priority, status)
                    VALUES (:h, :c, :r, :k, :q, :p, :s)
                    """)
                    .param("h", h).param("c", q.claimId()).param("r", q.relatedClaimId()).param("k", q.kind())
                    .param("q", q.question().length() > 1000 ? q.question().substring(0, 997) + "..." : q.question())
                    .param("p", q.priority()).param("s", status)
                    .update();
        }
        ctx.count("gapQuestions", chosen.size());
    }

    private static String cite(ClaimRepository.ClaimRow c, Map<UUID, List<ClaimRepository.EvidenceRow>> evidence) {
        List<ClaimRepository.EvidenceRow> ev = evidence.getOrDefault(c.id(), List.of());
        if (ev.isEmpty()) {
            return "\"" + c.title() + "\"";
        }
        ClaimRepository.EvidenceRow e = ev.get(0);
        String when = e.authoredAt() == null ? "" : ", " + e.authoredAt().atZone(ZoneOffset.UTC).toLocalDate();
        return "\"" + e.quote() + "\" (" + e.documentTitle() + when + ")";
    }

    private static boolean matches(ClaimRepository.ClaimRow c, String regex) {
        String text = (c.title() + " " + (c.body() == null ? "" : c.body())).toLowerCase(Locale.ROOT);
        return Pattern.compile(regex).matcher(text).find();
    }
}
