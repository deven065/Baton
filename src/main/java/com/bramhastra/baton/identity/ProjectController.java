package com.bramhastra.baton.identity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final AccessPolicy access;

    public ProjectController(ProjectRepository projects, ProjectMemberRepository members,
                             AppUserRepository users, CurrentUserProvider currentUser, AccessPolicy access) {
        this.projects = projects;
        this.members = members;
        this.users = users;
        this.currentUser = currentUser;
        this.access = access;
    }

    /** Only the projects the caller is a member of. */
    @GetMapping
    public List<ProjectSummary> myProjects() {
        CurrentUser me = currentUser.get();
        Map<UUID, ProjectRole> roles = members.findByUserId(me.id()).stream()
                .collect(Collectors.toMap(ProjectMember::getProjectId, ProjectMember::getRole));
        return projects.findAllById(roles.keySet()).stream()
                .sorted(Comparator.comparing(Project::getName))
                .map(p -> new ProjectSummary(p.getId(), p.getName(), p.getDescription(), roles.get(p.getId())))
                .toList();
    }

    @GetMapping("/{projectId}")
    public ProjectDetail project(@PathVariable UUID projectId) {
        CurrentUser me = currentUser.get();
        ProjectRole myRole = access.requireMember(me, projectId);
        Project p = projects.findById(projectId).orElseThrow(ApiException::notFound);

        List<ProjectMember> memberships = members.findByProjectId(projectId);
        Map<UUID, AppUser> byId = users.findAllById(memberships.stream().map(ProjectMember::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        List<MemberView> team = memberships.stream()
                .map(m -> {
                    AppUser u = byId.get(m.getUserId());
                    return new MemberView(u.getId(), u.getDisplayName(), m.getRole(), u.getClearance());
                })
                .sorted(Comparator.comparing(MemberView::displayName))
                .toList();
        return new ProjectDetail(p.getId(), p.getName(), p.getDescription(), myRole, team);
    }

    public record ProjectSummary(UUID id, String name, String description, ProjectRole myRole) {
    }

    public record MemberView(UUID userId, String displayName, ProjectRole role, Sensitivity clearance) {
    }

    public record ProjectDetail(UUID id, String name, String description, ProjectRole myRole,
                                List<MemberView> members) {
    }
}
