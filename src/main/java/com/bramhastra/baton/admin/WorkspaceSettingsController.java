package com.bramhastra.baton.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.security.CurrentUserProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin/workspace")
public class WorkspaceSettingsController {

    private final WorkspaceSettingsService settings;
    private final CurrentUserProvider currentUser;

    public WorkspaceSettingsController(WorkspaceSettingsService settings, CurrentUserProvider currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    @GetMapping
    public WorkspaceSettingsService.WorkspaceView get() {
        return settings.get(currentUser.get());
    }

    /** Irreversible for Baton: every token for this workspace stops working at once. */
    @PostMapping("/disconnect")
    public WorkspaceSettingsService.Disconnected disconnect(@Valid @RequestBody DisconnectRequest req) {
        return settings.disconnect(currentUser.get(), req.confirm(), req.password());
    }

    public record DisconnectRequest(@NotBlank @Size(max = 40) String confirm, @NotBlank @Size(max = 200) String password) {
    }
}
