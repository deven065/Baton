package com.bramhastra.baton.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.RateLimiter;
import com.bramhastra.baton.mail.Mailer;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

/**
 * One-time links to set a password: "forgot password" (30 minutes) and first sign-in for people an admin
 * added (72 hours). The link carries a random token; the company's database stores only its SHA-256.
 */
@Service
public class PasswordResetService {

    public static final Duration RESET_TTL = Duration.ofMinutes(30);
    public static final Duration INVITE_TTL = Duration.ofHours(72);

    private final TenantRegistry tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final Mailer mailer;
    private final RateLimiter limits;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(TenantRegistry tenants, AppUserRepository users, PasswordEncoder passwords,
                                AuditService audit, JdbcClient jdbc, Mailer mailer, RateLimiter limits,
                                PlatformTransactionManager txManager) {
        this.tenants = tenants;
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.limits = limits;
        this.tx = new TransactionTemplate(txManager);
    }

    public boolean emailEnabled() {
        return mailer.enabled();
    }

    /**
     * "Forgot password". Always answers the same way, whether or not the workspace or account exists;
     * the email goes out on a background thread so timing does not tell either.
     */
    public void request(String workspace, String email, String ip) {
        String slug = workspace.strip().toLowerCase(Locale.ROOT);
        String addr = email.strip().toLowerCase(Locale.ROOT);
        limits.acquire("reset-ip:" + ip, 10, Duration.ofMinutes(15));
        limits.acquire("reset-account:" + slug + ":" + addr, 3, Duration.ofHours(1));
        if (!mailer.enabled()) {
            return;
        }
        tenants.bySlug(slug).filter(Tenant::active).ifPresent(t -> TenantContext.run(t, () -> tx.executeWithoutResult(s ->
                users.findByEmailIgnoreCase(addr).filter(AppUser::isActive).ifPresent(u -> {
                    String token = issue(u.getId(), "RESET", RESET_TTL);
                    mailer.send(u.getEmail(), "Reset your Baton password", """
                            Hi %s,

                            Someone asked to reset your password for the %s workspace on Baton.
                            Choose a new one here (the link works once, for 30 minutes):

                            %s

                            If this wasn't you, ignore this email; your password stays the same.
                            """.formatted(u.getDisplayName(), t.name(), link(t, token)));
                    audit.record(u.getId(), "PASSWORD_RESET_REQUESTED", "app_user", u.getId(), null, Map.of());
                }))));
    }

    /** Emails a new person a link to set their first password. Call inside the workspace. */
    public void invite(AppUser u, String invitedBy) {
        if (!mailer.enabled()) {
            return;
        }
        Tenant t = TenantContext.require();
        String token = issue(u.getId(), "INVITE", INVITE_TTL);
        mailer.send(u.getEmail(), invitedBy + " added you to " + t.name() + " on Baton", """
                Hi %s,

                %s added you to the %s workspace on Baton, where your team hands over project knowledge.
                Set your password here (the link works once, for 3 days):

                %s

                Your workspace name is: %s
                """.formatted(u.getDisplayName(), invitedBy, t.name(), link(t, token), t.slug()));
    }

    /** Sets a new password from a link. The token is used up, and every other open link for that person too. */
    public void reset(String workspace, String token, String newPassword, String ip) {
        limits.acquire("reset-submit-ip:" + ip, 20, Duration.ofMinutes(15));
        ApiException invalid = new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                Map.of("token", "This link is invalid, already used, or expired. Ask for a new one."));
        Tenant t = tenants.bySlug(workspace.strip().toLowerCase(Locale.ROOT)).filter(Tenant::active)
                .orElseThrow(() -> invalid);
        TenantContext.run(t, () -> tx.executeWithoutResult(s -> {
            UUID userId = jdbc.sql("""
                    SELECT user_id FROM password_reset
                    WHERE token_hash = :h AND used_at IS NULL AND expires_at > now() FOR UPDATE
                    """).param("h", hash(token)).query(UUID.class).optional().orElseThrow(() -> invalid);
            AppUser u = users.findById(userId).filter(AppUser::isActive).orElseThrow(() -> invalid);
            u.setPasswordHash(passwords.encode(newPassword));
            u.setMustChangePassword(false);
            users.saveAndFlush(u);
            jdbc.sql("UPDATE password_reset SET used_at = now() WHERE user_id = :u AND used_at IS NULL")
                    .param("u", userId).update();
            audit.record(u.getId(), "PASSWORD_RESET", "app_user", u.getId(), null, Map.of());
        }));
    }

    private String issue(UUID userId, String purpose, Duration ttl) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("""
                INSERT INTO password_reset (user_id, token_hash, purpose, expires_at) VALUES (:u, :h, :p, :e)
                """).param("u", userId).param("h", hash(token)).param("p", purpose)
                .param("e", Timestamp.from(Instant.now().plus(ttl))).update();
        return token;
    }

    private String link(Tenant t, String token) {
        return mailer.link("/reset-password?ws=" + t.slug() + "&token=" + token);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
