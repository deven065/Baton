package com.bramhastra.baton.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.identity.OrgRole;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Organisation administration. Organisation admins only. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService admin;
    private final CurrentUserProvider currentUser;

    public AdminController(AdminService admin, CurrentUserProvider currentUser) {
        this.admin = admin;
        this.currentUser = currentUser;
    }

    private CurrentUser me() {
        CurrentUser me = currentUser.get();
        AdminService.requireAdmin(me);
        return me;
    }

    @GetMapping("/users")
    public List<AdminService.UserView> users() {
        me();
        return admin.listUsers();
    }

    /** Creates an account with a one-time temporary password, returned once and never stored in clear. */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminService.Created createUser(@Valid @RequestBody NewUserRequest req) {
        return admin.createUser(me(), new AdminService.NewUser(req.displayName(), req.email(), req.orgRole(),
                req.clearance(), req.groups()));
    }

    @PatchMapping("/users/{id}")
    public AdminService.UserView updateUser(@PathVariable UUID id, @Valid @RequestBody UserPatchRequest req) {
        return admin.updateUser(me(), id, new AdminService.UserPatch(req.displayName(), req.orgRole(), req.clearance(),
                req.groups(), req.active()));
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, String> resetPassword(@PathVariable UUID id) {
        return Map.of("temporaryPassword", admin.resetPassword(me(), id));
    }

    @GetMapping("/groups")
    public List<AdminService.GroupView> groups() {
        me();
        return admin.listGroups();
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminService.GroupView createGroup(@Valid @RequestBody GroupRequest req) {
        return admin.createGroup(me(), req.name(), req.description());
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable UUID id) {
        admin.deleteGroup(me(), id);
    }

    /** Newest first. Page with {@code before} = the last id you received. */
    @GetMapping("/audit")
    public List<AdminService.AuditRow> audit(@RequestParam(required = false) Long before,
                                             @RequestParam(defaultValue = "100") int limit,
                                             @RequestParam(required = false) String action,
                                             @RequestParam(required = false) UUID actor,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(required = false) String q) {
        me();
        // One event type, or several separated by commas (a category).
        List<String> actions = action == null ? List.of()
                : java.util.Arrays.stream(action.split(",")).map(String::strip).filter(a -> !a.isEmpty()).limit(60).toList();
        return admin.audit(new AdminService.AuditFilter(before, limit, actions, actor, from, to, q));
    }

    @GetMapping("/audit/actions")
    public List<AdminService.AuditActionCount> auditActions() {
        me();
        return admin.auditActions();
    }

    public record NewUserRequest(@NotBlank @Size(max = 120) String displayName,
                                 @NotBlank @Email @Size(max = 200) String email,
                                 OrgRole orgRole, Sensitivity clearance, List<@Size(max = 60) String> groups) {
    }

    public record UserPatchRequest(@Size(max = 120) String displayName, OrgRole orgRole, Sensitivity clearance,
                                   List<@Size(max = 60) String> groups, Boolean active) {
    }

    public record GroupRequest(@NotBlank @Size(max = 60) String name, @Size(max = 255) String description) {
    }
}
