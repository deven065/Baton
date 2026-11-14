package com.bramhastra.baton.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.identity.OrgRole;
import com.bramhastra.baton.reconstruct.stage.ScoreStage;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;

/**
 * Per-team access control for a project's knowledge. Each source has a sensitivity level and, optionally, the teams
 * (groups) allowed to see it; a person sees it only when their clearance covers the level and, if teams are set,
 * they are in one of them. This service lets the lead change that after a source is added, and shows the whole
 * picture: which person on the project can see which source, and why not when they cannot.
 *
 * <p>A change is applied everywhere the source's text lives (documents, passages, and every claim built on it, which
 * are re-scored so a claim stays as strict as its strictest evidence), at once and for people already signed in.
 * Approved versions already shared keep the snapshot their recipients were given.
 */
@Service
public class SourceAccessService {

    private final JdbcClient jdbc;
    private final SourceRepository sources;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ScoreStage scorer;

    public SourceAccessService(JdbcClient jdbc, SourceRepository sources, AccessPolicy access, AuditService audit,
                               ScoreStage scorer) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.access = access;
        this.audit = audit;
        this.scorer = scorer;
    }

    public record SourceAccess(UUID id, String title, String type, String sensitivity, List<String> teams, boolean editable) {
    }

    /** One person on the project: what they can see, and for each source they cannot, the reason. */
    public record PersonAccess(UUID userId, String name, String email, String role, String clearance, List<String> teams,
                               List<UUID> canSee, Map<UUID, String> blocked) {
    }

    public record AccessMap(List<SourceAccess> sources, List<PersonAccess> people, List<String> teams) {
    }

    @Transactional(readOnly = true)
    public AccessMap map(CurrentUser me, UUID projectId) {
        access.requireLead(me, projectId);
        // Only sources the lead can see themselves: the map never reveals what is hidden from the person asking.
        List<SourceRow> visible = sources.listVisible(projectId, me);
        List<SourceAccess> srcs = visible.stream().map(s -> new SourceAccess(s.id(), s.title(), s.type().name(),
                s.sensitivity().name(), s.aclGroups(), s.type() != SourceType.TESTIMONY)).toList();

        Map<UUID, Set<String>> teamsOf = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT m.user_id, g.name FROM user_group_member m JOIN user_group g ON g.id = m.group_id
                WHERE m.user_id IN (SELECT user_id FROM project_member WHERE project_id = :p)
                """).param("p", projectId)
                .query((rs, n) -> teamsOf.computeIfAbsent(rs.getObject(1, UUID.class), k -> new TreeSet<>()).add(rs.getString(2)))
                .list();
        List<PersonAccess> people = jdbc.sql("""
                SELECT u.id, u.display_name, u.email, m.role, u.clearance
                FROM project_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.project_id = :p AND u.active ORDER BY m.role DESC, u.display_name
                """).param("p", projectId)
                .query((rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    Sensitivity clearance = Sensitivity.ofLevel(rs.getInt(5));
                    Set<String> teams = teamsOf.getOrDefault(id, Set.of());
                    CurrentUser who = new CurrentUser(id, rs.getString(3), rs.getString(2), OrgRole.MEMBER, clearance, teams, false);
                    List<UUID> can = new ArrayList<>();
                    Map<UUID, String> blocked = new LinkedHashMap<>();
                    for (SourceRow s : visible) {
                        if (access.canSee(who, s.sensitivity(), s.aclGroups())) {
                            can.add(s.id());
                        } else {
                            blocked.put(s.id(), reason(clearance, teams, s));
                        }
                    }
                    return new PersonAccess(id, rs.getString(2), rs.getString(3), rs.getString(4), clearance.name(),
                            List.copyOf(teams), can, blocked);
                })
                .list();
        List<String> teams = jdbc.sql("SELECT name FROM user_group ORDER BY name").query(String.class).list();
        return new AccessMap(srcs, people, teams);
    }

    /** Changes who can see a source. Lead only; the lead must still be able to see it afterwards. */
    @Transactional
    public SourceRow change(CurrentUser me, SourceRow s, Object sensitivityIn, Object teamsIn) {
        access.requireLead(me, s.projectId());
        if (s.type() == SourceType.TESTIMONY) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "Exit-interview answers carry their own level, chosen by the person who answered");
        }
        Map<String, String> problems = new LinkedHashMap<>();
        Sensitivity level = s.sensitivity();
        if (sensitivityIn != null) {
            try {
                level = Sensitivity.valueOf(sensitivityIn.toString().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.put("sensitivity", "PUBLIC, INTERNAL, CONFIDENTIAL or RESTRICTED");
            }
        }
        List<String> teams = s.aclGroups();
        if (teamsIn != null) {
            if (!(teamsIn instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof String))) {
                problems.put("aclGroups", "A list of team names");
            } else {
                teams = list.stream().map(x -> ((String) x).strip()).filter(x -> !x.isEmpty()).distinct().sorted().toList();
                List<String> known = jdbc.sql("SELECT name FROM user_group").query(String.class).list();
                List<String> unknown = teams.stream().filter(t -> !known.contains(t)).toList();
                if (!unknown.isEmpty()) {
                    problems.put("aclGroups", "No such team: " + String.join(", ", unknown));
                }
            }
        }
        if (problems.isEmpty() && !access.canSee(me, level, teams)) {
            problems.put("sensitivity", "You would lose access to this source yourself. Keep it within your clearance"
                    + (teams.isEmpty() ? "" : " and include one of your teams"));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed", problems);
        }
        if (level == s.sensitivity() && teams.equals(s.aclGroups())) {
            return s;
        }
        String acl = AccessPolicy.toPgArray(teams);
        jdbc.sql("UPDATE source SET sensitivity = :s, acl_groups = CAST(:a AS text[]) WHERE id = :id")
                .param("s", level.level()).param("a", acl).param("id", s.id()).update();
        jdbc.sql("UPDATE document SET sensitivity = :s, acl_groups = CAST(:a AS text[]) WHERE source_id = :id")
                .param("s", level.level()).param("a", acl).param("id", s.id()).update();
        int passages = jdbc.sql("""
                UPDATE chunk SET sensitivity = :s, acl_groups = CAST(:a AS text[])
                WHERE document_id IN (SELECT id FROM document WHERE source_id = :id)
                """).param("s", level.level()).param("a", acl).param("id", s.id()).update();
        // Claims take the strictest level of their evidence: re-score every handover in the project now.
        List<UUID> handovers = jdbc.sql("SELECT id FROM handover WHERE project_id = :p AND status <> 'ARCHIVED'")
                .param("p", s.projectId()).query(UUID.class).list();
        handovers.forEach(scorer::rescore);
        audit.record(me.id(), "SOURCE_ACCESS_CHANGED", "source", s.id(), s.projectId(), Map.of(
                "before", s.sensitivity().name() + (s.aclGroups().isEmpty() ? "" : " · " + String.join(", ", s.aclGroups())),
                "after", level.name() + (teams.isEmpty() ? "" : " · " + String.join(", ", teams)),
                "passages", passages));
        return sources.findById(s.id()).orElseThrow();
    }

    private static String reason(Sensitivity clearance, Set<String> teams, SourceRow s) {
        boolean level = !s.sensitivity().atMost(clearance);
        boolean team = !s.aclGroups().isEmpty() && s.aclGroups().stream().noneMatch(teams::contains);
        String needLevel = "needs " + s.sensitivity().name().charAt(0) + s.sensitivity().name().substring(1).toLowerCase(Locale.ROOT)
                + " clearance";
        String needTeam = "only for " + String.join(" or ", s.aclGroups());
        return level && team ? needLevel + " and is " + needTeam : level ? needLevel : "is " + needTeam;
    }
}
