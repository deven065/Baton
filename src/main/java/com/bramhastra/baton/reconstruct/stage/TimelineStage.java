package com.bramhastra.baton.reconstruct.stage;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.reconstruct.ClaimRepository;
import com.bramhastra.baton.reconstruct.PipelineContext;
import com.bramhastra.baton.reconstruct.PipelineStage;

/** Dated decisions, changes, risks and unresolved discussions, rebuilt each run. */
@Component
@Order(80)
public class TimelineStage implements PipelineStage {

    private final ClaimRepository claims;
    private final JdbcClient jdbc;

    public TimelineStage(ClaimRepository claims, JdbcClient jdbc) {
        this.claims = claims;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "TIMELINE";
    }

    @Override
    public int weight() {
        return 2;
    }

    @Override
    public void execute(PipelineContext ctx) {
        UUID h = ctx.handover().id();
        jdbc.sql("DELETE FROM timeline_event WHERE handover_id = :h").param("h", h).update();
        Map<UUID, Instant> firstEvidence = claims.evidence(h).stream()
                .filter(e -> e.valid() && e.authoredAt() != null)
                .collect(Collectors.toMap(ClaimRepository.EvidenceRow::claimId, ClaimRepository.EvidenceRow::authoredAt,
                        (a, b) -> a.isBefore(b) ? a : b));
        Set<UUID> conflicted = claims.links(h).stream().filter(l -> l.kind().equals("CONFLICTS_WITH"))
                .flatMap(l -> java.util.stream.Stream.of(l.from(), l.to())).collect(Collectors.toSet());
        int n = 0;
        for (ClaimRepository.ClaimRow c : claims.claims(h)) {
            String kind = switch (c.type()) {
                case "DECISION" -> conflicted.contains(c.id()) ? "CHANGE" : "DECISION";
                case "OPEN_QUESTION" -> "OPEN_DISCUSSION";
                case "RISK" -> c.critical() ? "RISK" : null;
                default -> null;
            };
            if (kind == null || c.reviewState().equals("REJECTED")) {
                continue;
            }
            Instant at = parseDate(c.attr("eventDate"));
            if (at == null) {
                at = firstEvidence.get(c.id());
            }
            if (at == null) {
                continue;
            }
            jdbc.sql("INSERT INTO timeline_event (handover_id, claim_id, at, kind, title) VALUES (:h, :c, :at, :k, :t)")
                    .param("h", h).param("c", c.id()).param("at", Timestamp.from(at)).param("k", kind)
                    .param("t", c.title().length() > 300 ? c.title().substring(0, 300) : c.title())
                    .update();
            n++;
        }
        ctx.count("timelineEvents", n);
    }

    static Instant parseDate(String s) {
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10)).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
