package com.bramhastra.baton.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Checks an AI provider's base URL before Baton sends anything to it: HTTPS only, and never a private,
 * loopback or link-local address, so an admin cannot point Baton's server at internal services.
 */
public final class ProviderUrl {

    private ProviderUrl() {
    }

    public static String check(String raw, boolean allowPrivate) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Enter the provider's API address");
        }
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That address could not be read");
        }
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Use a plain address like https://api.example.com/v1");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https && !(allowPrivate && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("The address must start with https://");
        }
        if (!allowPrivate && isPrivate(host)) {
            throw new IllegalArgumentException("That address points to a private network; use the provider's public API");
        }
        return uri.toString().replaceAll("/+$", "");
    }

    static boolean isPrivate(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                byte[] b = a.getAddress();
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || (b.length == 16 && (b[0] & 0xfe) == 0xfc)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("That host could not be found: " + host);
        }
    }
}
