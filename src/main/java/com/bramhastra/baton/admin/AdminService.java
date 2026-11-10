package com.bramhastra.baton.admin;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.billing.BillingService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.identity.AppUser;
import com.bramhastra.baton.identity.AppUserRepository;
import com.bramhastra.baton.identity.OrgRole;
import com.bramhastra.baton.identity.PasswordResetService;
import com.bramhastra.baton.identity.UserGroup;
import com.bramhastra.baton.identity.UserGroupRepository;
import com.bramhastra.baton.security.CurrentUser;

/**
 * Organisation administration inside one workspace: people, their clearance and groups, and the groups
 * themselves. Everything is written to the company's own database and audited there.
 */
@Service
public class AdminService {

    private static final Pattern GROUP = Pattern.compile("^[a-z0-9][a-z0-9 _-]{0,59}$");
    // No look-alike characters (0/O, 1/l/I), so a temporary password can be read out or copied safely.
    private static final char[] ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final AppUserRepository users;
    private final UserGroupRepository groups;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final PasswordResetService resets;
    private final BillingService billing;
    private final SecureRandom random = new SecureRandom();

    public AdminService(AppUserRepository users, UserGroupRepository groups, PasswordEncoder passwords,
                        AuditService audit, JdbcClient jdbc, PasswordResetService resets, BillingService billing) {
        this.billing = billing;
        this.users = users;
        this.groups = groups;
        this.passwords = passwords;
        this.audit = audit;
        this.jdbc = jdbc;
        this.resets = resets;
    }

    public static void requireAdmin(CurrentUser me) {
        if (!me.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only organisation admins can do this");
        }
    }

    // ───────────── people ─────────────

    public record UserView(UUID id, String email, String displayName, OrgRole orgRole, Sensitivity clearance,
                           List<String> groups, boolean active, boolean mustChangePassword, Instant createdAt) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getEmail(), u.getDisplayName(), u.getOrgRole(), u.getClearance(),
                    u.getGroups().stream().map(UserGroup::getName).sorted().toList(), u.isActive(),
                    u.isMustChangePassword(), u.getCreatedAt());
        }
    }

    public record NewUser(String displayName, String email, OrgRole orgRole, Sensitivity clearance, List<String> groups) {
    }

    public record Created(UserView user, String temporaryPassword, boolean invitedByEmail) {
    }

    public record UserPatch(String displayName, OrgRole orgRole, Sensitivity clearance, List<String> groups, Boolean active) {
    }

    @Transactional(readOnly = true)
    public List<UserView> listUsers() {
        return users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::isActive).reversed().thenComparing(AppUser::getDisplayName))
                .map(UserView::of).toList();
    }

    @Transactional
    public Created createUser(CurrentUser me, NewUser req) {
        String email = req.email().strip().toLowerCase(Locale.ROOT);
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "Someone in this workspace already uses that email");
        }
        billing.requireRoom(BillingService.Resource.PEOPLE);
        String temp = temporaryPassword();
        AppUser u = new AppUser();
        u.setEmail(email);
        u.setDisplayName(req.displayName().strip());
        u.setOrgRole(req.orgRole() == null ? OrgRole.MEMBER : req.orgRole());
        u.setClearance(req.clearance() == null ? Sensitivity.INTERNAL : req.clearance());
        u.setPasswordHash(passwords.encode(temp));
        u.setMustChangePassword(true);
        u.setGroups(resolveGroups(req.groups()));
        u = users.saveAndFlush(u);
        audit.record(me.id(), "USER_CREATED", "app_user", u.getId(), null,
                Map.of("orgRole", u.getOrgRole().name(), "clearance", u.getClearance().name(), "groups", names(u)));
        // With email set up, they also get a link to choose their own password (the temporary one still works).
        resets.invite(u, me.displayName());
        return new Created(UserView.of(u), temp, resets.emailEnabled());
    }

    @Transactional
    public UserView updateUser(CurrentUser me, UUID id, UserPatch p) {
        AppUser u = users.findById(id).orElseThrow(ApiException::notFound);
        Map<String, Object> changed = new LinkedHashMap<>();
        boolean self = u.getId().equals(me.id());
        if (p.displayName() != null && !p.displayName().isBlank() && !p.displayName().strip().equals(u.getDisplayName())) {
            u.setDisplayName(p.displayName().strip());
            changed.put("displayName", u.getDisplayName());
        }
        if (p.orgRole() != null && p.orgRole() != u.getOrgRole()) {
            if (self || (u.getOrgRole() == OrgRole.ADMIN && otherActiveAdmins(u) == 0)) {
                throw new ApiException(ErrorCode.NOT_ALLOWED,
                        self ? "You can’t change your own role" : "The workspace needs at least one active admin");
            }
            changed.put("orgRole", u.getOrgRole() + " → " + p.orgRole());
            u.setOrgRole(p.orgRole());
        }
        if (p.clearance() != null && p.clearance() != u.getClearance()) {
            changed.put("clearance", u.getClearance() + " → " + p.clearance());
            u.setClearance(p.clearance());
        }
        if (p.groups() != null) {
            Set<UserGroup> next = resolveGroups(p.groups());
            List<String> before = names(u);
            u.setGroups(next);
            if (!before.equals(names(u))) {
                changed.put("groups", names(u));
            }
        }
        if (p.active() != null && p.active() != u.isActive()) {
            if (!p.active() && (self || (u.getOrgRole() == OrgRole.ADMIN && otherActiveAdmins(u) == 0))) {
                throw new ApiException(ErrorCode.NOT_ALLOWED,
                        self ? "You can’t deactivate your own account" : "The workspace needs at least one active admin");
            }
            if (p.active()) {
                billing.requireRoom(BillingService.Resource.PEOPLE);
            }
            u.setActive(p.active());
            changed.put("active", p.active());
        }
        if (!changed.isEmpty()) {
            users.saveAndFlush(u);
            audit.record(me.id(), "USER_UPDATED", "app_user", u.getId(), null, changed);
        }
        return UserView.of(u);
    }

    @Transactional
    public String resetPassword(CurrentUser me, UUID id) {
        AppUser u = users.findById(id).orElseThrow(ApiException::notFound);
        String temp = temporaryPassword();
        u.setPasswordHash(passwords.encode(temp));
        u.setMustChangePassword(true);
        users.saveAndFlush(u);
        audit.record(me.id(), "PASSWORD_RESET", "app_user", u.getId(), null, Map.of());
        return temp;
    }

    private long otherActiveAdmins(AppUser except) {
        return users.findAll().stream()
                .filter(x -> x.isActive() && x.getOrgRole() == OrgRole.ADMIN && !x.getId().equals(except.getId()))
                .count();
    }

    private Set<UserGroup> resolveGroups(List<String> names) {
        Set<UserGroup> out = new HashSet<>();
        if (names == null) {
            return out;
        }
        List<String> unknown = new ArrayList<>();
        for (String n : names) {
            groups.findByName(n.strip().toLowerCase(Locale.ROOT)).ifPresentOrElse(out::add, () -> unknown.add(n));
        }
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("groups", "Unknown groups: " + unknown));
        }
        return out;
    }

    private static List<String> names(AppUser u) {
        return u.getGroups().stream().map(UserGroup::getName).sorted().toList();
    }

    private String temporaryPassword() {
        StringBuilder b = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            b.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return b.toString();
    }

    // ───────────── groups ─────────────

    public record GroupView(UUID id, String name, String description, int members) {
    }

    @Transactional(readOnly = true)
    public List<GroupView> listGroups() {
        return jdbc.sql("""
                SELECT g.id, g.name, g.description, count(m.user_id) AS members
                FROM user_group g LEFT JOIN user_group_member m ON m.group_id = g.id
                GROUP BY g.id ORDER BY g.name
                """)
                .query((rs, n) -> new GroupView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("description"), rs.getInt("members")))
                .list();
    }

    @Transactional
    public GroupView createGroup(CurrentUser me, String rawName, String description) {
        String name = rawName == null ? "" : rawName.strip().toLowerCase(Locale.ROOT);
        if (!GROUP.matcher(name).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("name", "Use 1–60 lowercase letters, digits, spaces, hyphens or underscores"));
        }
        if (groups.findByName(name).isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "That group already exists");
        }
        UserGroup g = groups.saveAndFlush(new UserGroup(name, description == null ? null : description.strip()));
        audit.record(me.id(), "GROUP_CREATED", "user_group", g.getId(), null, Map.of("name", name));
        return new GroupView(g.getId(), g.getName(), g.getDescription(), 0);
    }

    /** Refused while any source or claim is restricted to the group: deleting it would change who can see what. */
    @Transactional
    public void deleteGroup(CurrentUser me, UUID id) {
        UserGroup g = groups.findById(id).orElseThrow(ApiException::notFound);
        int inUse = jdbc.sql("""
                SELECT (SELECT count(*) FROM source WHERE :n = ANY(acl_groups))
                     + (SELECT count(*) FROM claim WHERE :n = ANY(acl_groups))
                """).param("n", g.getName()).query(Integer.class).single();
        if (inUse > 0) {
            throw new ApiException(ErrorCode.NOT_ALLOWED,
                    "Sources or claims are restricted to this group. Change those first, then delete it.");
        }
        groups.delete(g);
        audit.record(me.id(), "GROUP_DELETED", "user_group", id, null, Map.of("name", g.getName()));
    }

    // ───────────── audit ─────────────

    public record AuditRow(long id, Instant at, UUID actorId, String actorName, String action, String entityType,
                           UUID entityId, UUID projectId, String details) {
    }

    @Transactional(readOnly = true)
    public List<AuditRow> audit(Long before, int limit, String action) {
        return audit(new AuditFilter(before, limit, action == null ? List.of() : List.of(action), null, null, null, null));
    }

    /** Filters for the audit log; every field is optional. Dates are inclusive whole days in UTC. */
    public record AuditFilter(Long before, int limit, List<String> actions, UUID actorId, java.time.LocalDate from,
                              java.time.LocalDate to, String text) {
    }

    @Transactional(readOnly = true)
    public List<AuditRow> audit(AuditFilter f) {
        String search = f.text() == null ? "" : f.text().strip();
        if (search.length() > 100) {
            search = search.substring(0, 100);
        }
        // LIKE wildcards in the search are matched literally.
        String text = search.isEmpty() ? null : "%" + search.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                SELECT a.id, a.at, a.actor_id, u.display_name, a.action, a.entity_type, a.entity_id, a.project_id,
                       a.details::text AS details
                FROM audit_log a LEFT JOIN app_user u ON u.id = a.actor_id
                WHERE (CAST(:before AS bigint) IS NULL OR a.id < :before)
                  AND (:anyAction OR a.action IN (:actions))
                  AND (CAST(:actor AS uuid) IS NULL OR a.actor_id = :actor)
                  AND (CAST(:from AS date) IS NULL OR a.at >= CAST(:from AS date))
                  AND (CAST(:to AS date) IS NULL OR a.at < CAST(:to AS date) + 1)
                  AND (CAST(:text AS varchar) IS NULL OR lower(a.details::text) LIKE :text
                       OR lower(coalesce(u.display_name, '')) LIKE :text OR lower(a.action) LIKE :text
                       OR lower(coalesce(a.entity_type, '')) LIKE :text)
                ORDER BY a.id DESC LIMIT :limit
                """)
                .param("before", f.before()).param("anyAction", f.actions().isEmpty())
                .param("actions", f.actions().isEmpty() ? List.of("") : f.actions()).param("actor", f.actorId())
                .param("from", f.from()).param("to", f.to()).param("text", text)
                .param("limit", Math.max(1, Math.min(f.limit(), 200)))
                .query((rs, n) -> new AuditRow(rs.getLong("id"), rs.getTimestamp("at").toInstant(),
                        rs.getObject("actor_id", UUID.class), rs.getString("display_name"), rs.getString("action"),
                        rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                        rs.getObject("project_id", UUID.class), rs.getString("details")))
                .list();
    }

    public record AuditActionCount(String action, long count) {
    }

    /** Every kind of event this workspace has recorded, so the filter offers exactly what exists. */
    @Transactional(readOnly = true)
    public List<AuditActionCount> auditActions() {
        return jdbc.sql("SELECT action, count(*) AS n FROM audit_log GROUP BY action ORDER BY action")
                .query((rs, n) -> new AuditActionCount(rs.getString("action"), rs.getLong("n"))).list();
    }
}
