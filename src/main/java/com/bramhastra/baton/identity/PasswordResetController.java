package com.bramhastra.baton.identity;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.common.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public: no sign-in needed. */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private final PasswordResetService resets;

    public PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    /** What the sign-in pages can offer on this server. */
    @GetMapping("/options")
    public Map<String, Boolean> options() {
        return Map.of("passwordResetByEmail", resets.emailEnabled());
    }

    /** Always 202: never reveals whether the workspace or account exists. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgot(@Valid @RequestBody ForgotRequest req, HttpServletRequest http) {
        resets.request(req.workspace(), req.email(), RateLimiter.clientIp(http));
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetRequest req, HttpServletRequest http) {
        resets.reset(req.workspace(), req.token(), req.newPassword(), RateLimiter.clientIp(http));
    }

    public record ForgotRequest(@NotBlank @Size(max = 40) String workspace, @NotBlank @Email @Size(max = 200) String email) {
    }

    public record ResetRequest(@NotBlank @Size(max = 40) String workspace, @NotBlank @Size(max = 100) String token,
                               @NotBlank @Size(min = 12, max = 200) String newPassword) {
    }
}
