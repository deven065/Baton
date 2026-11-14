package com.bramhastra.baton.identity;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.billing.BillingService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.CurrentUser;

/** Creating projects and managing who is on them. Admins can do everything; a project lead manages their own team. */
@Service
public class ProjectAdminService {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final AppUserRepository users;
    private final AuditService audit;
    private final JdbcClient jdbc;

    private final BillingService billing;

    public ProjectAdminService(ProjectRepository projects, ProjectMemberRepository members, AppUserRepository users,
                               AuditService audit, JdbcClient jdbc, BillingService billing) {
        this.billing = billing;
        this.projects = projects;
        this.members = members;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public record ProjectRow(UUID id, String name, String description, List<String> leads, int members,
                             int handovers, Instant createdAt) {
    }

    public record Person(UUID id, String displayName, String email) {
    }

    /** All projects, for the admin overview. */
    @Transactional(readOnly = true)
    public List<ProjectRow> listAll() {
        return jdbc.sql("""
                SELECT p.id, p.name, p.description, p.created_at,
                       COALESCE(array_agg(u.display_name ORDER BY u.display_name)
                                FILTER (WHERE m.role = 'PROJECT_LEAD'), '{}') AS leads,
                       count(m.user_id) AS members,
                       (SELECT count(*) FROM handover h WHERE h.project_id = p.id) AS handovers
                FROM project p
                LEFT JOIN project_member m ON m.project_id = p.id
                LEFT JOIN app_user u ON u.id = m.user_id
                GROUP BY p.id ORDER BY p.name
                """)
                .query((rs, n) -> new ProjectRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("description"), List.of((String[]) rs.getArray("leads").getArray()),
                        rs.getInt("members"), rs.getInt("handovers"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public record TeamMember(UUID userId, String displayName, String email, ProjectRole role, Instant joinedAt,
            String clearance) {
    }

    /**
     * A project's team, for whoever manages it (admin or lead). Names and roles only: admins manage who is on a
     * project without being able to open its sources, claims or handovers.
     */
    @Transactional(readOnly = true)
    public List<TeamMember> team(CurrentUser me, UUID projectId) {
        requireManager(me, projectId);
        return jdbc.sql("""
                SELECT u.id, u.display_name, u.email, m.role, m.joined_at, u.clearance
                FROM project_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.project_id = :p ORDER BY m.role DESC, u.display_name
                """).param("p", projectId)
                .query((rs, n) -> new TeamMember(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("email"), ProjectRole.valueOf(rs.getString("role")),
                        rs.getTimestamp("joined_at").toInstant(),
                        com.bramhastra.baton.common.Sensitivity.ofLevel(rs.getInt("clearance")).name()))
                .list();
    }

    /** Active people in the workspace, for picking leads and members. Admins and project leads only. */
    @Transactional(readOnly = true)
    public List<Person> directory(CurrentUser me) {
        boolean leadSomewhere = members.findByUserId(me.id()).stream()
                .anyMatch(m -> m.getRole() == ProjectRole.PROJECT_LEAD);
        if (!me.isAdmin() && !leadSomewhere) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only admins and project leads can list people");
        }
        return users.findAll().stream().filter(AppUser::isActive)
                .sorted(Comparator.comparing(AppUser::getDisplayName))
                .map(u -> new Person(u.getId(), u.getDisplayName(), u.getEmail())).toList();
    }

    @Transactional
    public Project create(CurrentUser me, String name, String description, UUID leadUserId) {
        if (!me.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only organisation admins can create projects");
        }
        String n = name.strip();
        boolean taken = jdbc.sql("SELECT count(*) FROM project WHERE lower(name) = lower(:n)").param("n", n)
                .query(Integer.class).single() > 0;
        if (taken) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "A project with that name already exists");
        }
        billing.requireRoom(BillingService.Resource.PROJECTS);
        AppUser lead = activeUser(leadUserId);
        Project p = new Project();
        p.setName(n);
        p.setDescription(description == null || description.isBlank() ? null : description.strip());
        p.setCreatedBy(me.id());
        p = projects.saveAndFlush(p);
        members.saveAndFlush(new ProjectMember(p.getId(), lead.getId(), ProjectRole.PROJECT_LEAD));
        audit.record(me.id(), "PROJECT_CREATED", "project", p.getId(), p.getId(),
                Map.of("lead", lead.getId().toString()));
        return p;
    }

    @Transactional
    public void addMember(CurrentUser me, UUID projectId, UUID userId, ProjectRole role) {
        requireManager(me, projectId);
        AppUser u = activeUser(userId);
        if (members.findByProjectIdAndUserId(projectId, u.getId()).isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "Already a member of this project");
        }
        ProjectRole r = role == null ? ProjectRole.MEMBER : role;
        members.saveAndFlush(new ProjectMember(projectId, u.getId(), r));
        audit.record(me.id(), "MEMBER_ADDED", "app_user", u.getId(), projectId, Map.of("role", r.name()));
    }

    @Transactional
    public void changeRole(CurrentUser me, UUID projectId, UUID userId, ProjectRole role) {
        requireManager(me, projectId);
        ProjectMember m = members.findByProjectIdAndUserId(projectId, userId).orElseThrow(ApiException::notFound);
        if (m.getRole() == role) {
            return;
        }
        if (m.getRole() == ProjectRole.PROJECT_LEAD && leads(projectId) == 1) {
            throw new ApiException(ErrorCode.NOT_ALLOWED,
                    "A project needs at least one lead. Make someone else lead first.");
        }
        m.setRole(role);
        members.saveAndFlush(m);
        audit.record(me.id(), "MEMBER_ROLE_CHANGED", "app_user", userId, projectId, Map.of("role", role.name()));
    }

    @Transactional
    public void removeMember(CurrentUser me, UUID projectId, UUID userId) {
        requireManager(me, projectId);
        ProjectMember m = members.findByProjectIdAndUserId(projectId, userId).orElseThrow(ApiException::notFound);
        if (m.getRole() == ProjectRole.PROJECT_LEAD && leads(projectId) == 1) {
            throw new ApiException(ErrorCode.NOT_ALLOWED,
                    "A project needs at least one lead. Make someone else lead first.");
        }
        boolean leaving = jdbc.sql("""
                SELECT count(*) FROM handover
                WHERE project_id = :p AND departing_user_id = :u AND status <> 'ARCHIVED'
                """).param("p", projectId).param("u", userId).query(Integer.class).single() > 0;
        if (leaving) {
            throw new ApiException(ErrorCode.NOT_ALLOWED, "This person has an open handover in the project");
        }
        members.delete(m);
        audit.record(me.id(), "MEMBER_REMOVED", "app_user", userId, projectId, Map.of());
    }

    private void requireManager(CurrentUser me, UUID projectId) {
        projects.findById(projectId).orElseThrow(ApiException::notFound);
        if (me.isAdmin()) {
            return;
        }
        ProjectRole role = members.findByProjectIdAndUserId(projectId, me.id()).map(ProjectMember::getRole)
                .orElseThrow(ApiException::notFound);
        if (role != ProjectRole.PROJECT_LEAD) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only the project lead or an admin can change the team");
        }
    }

    private long leads(UUID projectId) {
        return members.findByProjectId(projectId).stream()
                .filter(m -> m.getRole() == ProjectRole.PROJECT_LEAD).count();
    }

    private AppUser activeUser(UUID id) {
        return users.findById(id).filter(AppUser::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                        Map.of("userId", "No active person with that id in this workspace")));
    }
}
