package com.bramhastra.baton.tenant;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A company's Postgres connection, parsed from what they paste: {@code postgres://user:pass@host/db?sslmode=require}
 * (Neon, Supabase, Render…) or {@code jdbc:postgresql://host:5432/db}, with optional separate user and password.
 */
public record DatabaseUrl(String host, int port, String database, String username, String password,
                          Map<String, String> params) {

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");
    private static final Pattern DB = Pattern.compile("^[A-Za-z0-9_-]{1,63}$");
    // pgjdbc does not understand these libpq-only parameters; drop them instead of failing.
    private static final List<String> LIBPQ_ONLY = List.of("channel_binding", "options", "target_session_attrs");

    public static DatabaseUrl parse(String url, String user, String password) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Database URL is required");
        }
        String u = url.strip();
        if (u.startsWith("jdbc:")) {
            u = u.substring(5);
        }
        if (!u.startsWith("postgres://") && !u.startsWith("postgresql://")) {
            throw new IllegalArgumentException("Use a PostgreSQL URL (postgres://… or postgresql://…)");
        }
        URI uri;
        try {
            uri = URI.create(u.replaceFirst("^postgres(ql)?://", "postgresql://"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That database URL could not be read");
        }
        String host = uri.getHost();
        if (host == null || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("The database URL needs a valid host name");
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (!DB.matcher(db).matches()) {
            throw new IllegalArgumentException("The database URL needs a database name, e.g. …/baton");
        }
        String uriUser = null, uriPass = null;
        if (uri.getRawUserInfo() != null) {
            String[] up = uri.getUserInfo().split(":", 2);
            uriUser = up[0];
            uriPass = up.length > 1 ? up[1] : null;
        }
        String finalUser = user != null && !user.isBlank() ? user.strip() : uriUser;
        String finalPass = password != null && !password.isEmpty() ? password : uriPass;
        if (finalUser == null || finalUser.isBlank() || finalPass == null || finalPass.isEmpty()) {
            throw new IllegalArgumentException("A database user and password are required");
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String kv : uri.getQuery().split("&")) {
                String[] p = kv.split("=", 2);
                if (!p[0].isBlank() && !LIBPQ_ONLY.contains(p[0])) {
                    params.put(p[0], p.length > 1 ? p[1] : "");
                }
            }
        }
        // Neon's "-pooler" host runs PgBouncer in transaction mode, which breaks migrations' session locks.
        if (host.contains("-pooler.")) {
            host = host.replace("-pooler.", ".");
        }
        return new DatabaseUrl(host.toLowerCase(Locale.ROOT), port, db, finalUser, finalPass, params);
    }

    /** True when the host is this machine or a private network: blocked for self-registration to prevent SSRF. */
    public boolean isPrivateHost() {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || isUniqueLocalV6(a)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("The database host could not be found: " + host);
        }
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    public String jdbcUrl(boolean requireSsl) {
        Map<String, String> p = new LinkedHashMap<>(params);
        if (requireSsl) {
            p.putIfAbsent("sslmode", "require");
        }
        p.putIfAbsent("connectTimeout", "10");
        p.putIfAbsent("socketTimeout", "120");
        p.putIfAbsent("ApplicationName", "baton");
        List<String> q = new ArrayList<>();
        p.forEach((k, v) -> q.add(k + "=" + v));
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + "?" + String.join("&", q);
    }
}
