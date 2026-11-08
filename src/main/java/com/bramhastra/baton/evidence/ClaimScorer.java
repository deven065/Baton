package com.bramhastra.baton.evidence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.source.SourceType;

/**
 * Status and confidence are computed by code, never by the model (LLD L4 ClaimScorer).
 *
 * confidence = 0.35·maxTrust/3 + 0.25·min(sources,3)/3 + 0.20·avg(matchScore) + 0.20·recency − 0.40·conflict
 */
@Component
public class ClaimScorer {

    public enum ClaimStatus { VERIFIED, SINGLE_SOURCE, INFERRED, CONFLICTING, MISSING_EVIDENCE, STALE }

    /** One evidence row as the scorer needs it. Invalid rows still count for sensitivity. */
    public record Ev(UUID sourceId, SourceType sourceType, Instant authoredAt, double matchScore, boolean valid,
                     Sensitivity sensitivity, List<String> aclGroups) {
    }

    public record Input(boolean hasReasoning, boolean conflict, boolean superseded, Sensitivity floorSensitivity,
                        List<String> floorAcl, List<Ev> evidence, int invalidCitations, List<String> extraReasons) {
    }

    public record ScoreResult(ClaimStatus status, double confidence, Sensitivity sensitivity, List<String> aclGroups,
                              List<String> reasons) {
    }

    private final Clock clock;

    public ClaimScorer() {
        this(Clock.systemUTC());
    }

    ClaimScorer(Clock clock) {
        this.clock = clock;
    }

    public static int trustTier(SourceType type) {
        return switch (type) {
            case GITHUB, TASK_CSV -> 3;          // systems of record
            case DOCUMENT, MEETING_NOTES, TESTIMONY -> 2;
            case CHAT_EXPORT -> 1;
        };
    }

    public ScoreResult score(Input in) {
        List<Ev> valid = in.evidence().stream().filter(Ev::valid).toList();
        int sources = (int) valid.stream().map(Ev::sourceId).distinct().count();
        int maxTrust = valid.stream().mapToInt(e -> trustTier(e.sourceType())).max().orElse(0);
        long ageDays = valid.stream().map(Ev::authoredAt).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(d -> Duration.between(d, clock.instant()).toDays()).orElse(999L);

        ClaimStatus status;
        if (in.conflict()) {
            status = ClaimStatus.CONFLICTING;
        } else if (valid.isEmpty() && in.hasReasoning()) {
            status = ClaimStatus.INFERRED;
        } else if (valid.isEmpty()) {
            status = ClaimStatus.MISSING_EVIDENCE;
        } else if (in.superseded() && ageDays > 60) {
            status = ClaimStatus.STALE;
        } else if (sources >= 2 || maxTrust == 3) {
            status = ClaimStatus.VERIFIED;
        } else {
            status = ClaimStatus.SINGLE_SOURCE;
        }

        double avgMatch = valid.stream().mapToDouble(Ev::matchScore).average().orElse(0);
        double conf = 0.35 * maxTrust / 3.0 + 0.25 * Math.min(sources, 3) / 3.0 + 0.20 * avgMatch
                + 0.20 * recency(ageDays) - (in.conflict() ? 0.40 : 0);
        conf = Math.round(Math.max(0, Math.min(1, conf)) * 1000) / 1000.0;

        // Sensitivity never goes down: max of the floor (batch class / declared) and ALL evidence.
        Sensitivity sens = in.floorSensitivity();
        for (Ev e : in.evidence()) {
            if (e.sensitivity().ordinal() > sens.ordinal()) {
                sens = e.sensitivity();
            }
        }
        Set<String> acl = new TreeSet<>(in.floorAcl());
        in.evidence().forEach(e -> acl.addAll(e.aclGroups()));

        List<String> reasons = new ArrayList<>();
        if (!valid.isEmpty()) {
            String kinds = valid.stream().map(e -> label(e.sourceType())).distinct().collect(Collectors.joining(", "));
            reasons.add(sources + (sources == 1 ? " source" : " sources") + " (" + kinds + ", tier " + maxTrust + ")");
        }
        if (in.conflict()) {
            reasons.add("conflicts with another claim; resolve before approval");
        }
        if (status == ClaimStatus.INFERRED) {
            reasons.add("no direct quote: AI inference, likely but not confirmed");
        }
        if (status == ClaimStatus.MISSING_EVIDENCE) {
            reasons.add("no supporting quote found");
        }
        if (in.invalidCitations() > 0) {
            reasons.add(in.invalidCitations() + (in.invalidCitations() == 1 ? " citation" : " citations")
                    + " could not be verified");
        }
        if (!valid.isEmpty() && ageDays > 120) {
            reasons.add("newest evidence is " + ageDays + " days old");
        }
        reasons.addAll(in.extraReasons());
        return new ScoreResult(status, conf, sens, List.copyOf(acl), reasons);
    }

    static double recency(long days) {
        return days <= 14 ? 1 : days >= 120 ? 0 : 1 - (days - 14) / 106.0;
    }

    private static String label(SourceType t) {
        return switch (t) {
            case GITHUB -> "repo";
            case TASK_CSV -> "task board";
            case DOCUMENT -> "document";
            case MEETING_NOTES -> "meeting notes";
            case CHAT_EXPORT -> "chat";
            case TESTIMONY -> "testimony";
        };
    }
}
