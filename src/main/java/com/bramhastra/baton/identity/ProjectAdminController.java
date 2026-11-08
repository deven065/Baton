package com.bramhastra.baton.identity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.admin.AdminService;
import com.bramhastra.baton.security.CurrentUserProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class ProjectAdminController {

    private final ProjectAdminService service;
    private final CurrentUserProvider currentUser;
    private final PasswordChangeService passwordChange;

    public ProjectAdminController(ProjectAdminService service, CurrentUserProvider currentUser,
                                  PasswordChangeService passwordChange) {
        this.service = service;
        this.currentUser = currentUser;
        this.passwordChange = passwordChange;
    }

    @GetMapping("/admin/projects")
    public List<ProjectAdminService.ProjectRow> allProjects() {
        AdminService.requireAdmin(currentUser.get());
        return service.listAll();
    }

    @GetMapping("/projects/{projectId}/team")
    public List<ProjectAdminService.TeamMember> team(@PathVariable UUID projectId) {
        return service.team(currentUser.get(), projectId);
    }

    /** People in the workspace, for picking project leads and members. */
    @GetMapping("/directory")
    public List<ProjectAdminService.Person> directory() {
        return service.directory(currentUser.get());
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody NewProject req) {
        Project p = service.create(currentUser.get(), req.name(), req.description(), req.leadUserId());
        return Map.of("id", p.getId(), "name", p.getName());
    }

    @PostMapping("/projects/{projectId}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable UUID projectId, @Valid @RequestBody MemberRequest req) {
        service.addMember(currentUser.get(), projectId, req.userId(), req.role());
    }

    @PatchMapping("/projects/{projectId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@PathVariable UUID projectId, @PathVariable UUID userId,
                           @Valid @RequestBody RoleRequest req) {
        service.changeRole(currentUser.get(), projectId, userId, req.role());
    }

    @DeleteMapping("/projects/{projectId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID projectId, @PathVariable UUID userId) {
        service.removeMember(currentUser.get(), projectId, userId);
    }

    /** Any signed-in person: replace your password (required after an admin creates or resets the account). */
    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody PasswordRequest req) {
        passwordChange.change(currentUser.get(), req.currentPassword(), req.newPassword());
    }

    public record NewProject(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
                             @NotNull UUID leadUserId) {
    }

    public record MemberRequest(@NotNull UUID userId, ProjectRole role) {
    }

    public record RoleRequest(@NotNull ProjectRole role) {
    }

    public record PasswordRequest(@NotBlank String currentPassword,
                                  @NotBlank @Size(min = 12, max = 200) String newPassword) {
    }
}
