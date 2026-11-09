package com.bramhastra.baton.mail;

/**
 * Sends Baton's few emails (password reset, invitations, share notices). Sending is fire-and-forget on a
 * background thread, so a response never takes longer because an account exists, and a mail outage never
 * fails the action that triggered it. When no provider is configured, {@link #enabled()} is false and the
 * UI offers the admin-reset path instead.
 */
public interface Mailer {

    boolean enabled();

    void send(String to, String subject, String text);

    /** An absolute link into the web app, e.g. {@code link("/reset-password?ws=acme&token=…")}. */
    String link(String pathAndQuery);
}
