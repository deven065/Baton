package com.bramhastra.baton.handover;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * A signed record of one approved version: the frozen snapshot exactly as hashed at approval, its checklist,
 * who it was shared with, its audit trail, a printable report, and a manifest of SHA-256 hashes signed with a
 * key derived from the workspace key. Anyone can recheck the file hashes; Baton can confirm the signature.
 */
@Service
public class ExportService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final HandoverAccess access;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TenantRegistry tenants;
    private final ObjectMapper json;

    public ExportService(HandoverAccess access, JdbcClient jdbc, AuditService audit, TenantRegistry tenants,
                         ObjectMapper json) {
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tenants = tenants;
        this.json = json;
    }

    public record Export(String filename, byte[] zip) {
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Export export(CurrentUser me, UUID handoverId, int versionNo) {
        Handover h = access.requireLead(me, handoverId);
        Map<String, Object> v = jdbc.sql("""
                SELECT v.id, v.sha256, v.approved_at, u.display_name, COALESCE(v.snapshot_text, v.snapshot::text) AS text,
                       v.snapshot_text IS NOT NULL AS exact
                FROM handover_version v JOIN app_user u ON u.id = v.approved_by
                WHERE v.handover_id = :h AND v.version_no = :n
                """).param("h", h.id()).param("n", versionNo).query().listOfRows().stream().findFirst()
                .orElseThrow(ApiException::notFound);
        UUID versionId = (UUID) v.get("id");
        String snapshotText = (String) v.get("text");
        Map<String, Object> snapshot = json.readValue(snapshotText, MAP);

        List<Map<String, Object>> checklist = jdbc.sql("""
                SELECT phase, position, text, claim_id FROM checklist_item WHERE handover_version_id = :v ORDER BY phase, position
                """).param("v", versionId).query().listOfRows();
        List<Map<String, Object>> shareRows = jdbc.sql("""
                SELECT s.id, u.display_name AS recipient, s.role_label, s.created_at, s.expires_at, s.revoked_at, s.acknowledged_at
                FROM handover_share s JOIN app_user u ON u.id = s.recipient_user_id
                WHERE s.handover_version_id = :v ORDER BY s.created_at
                """).param("v", versionId).query().listOfRows();
        List<Object> shareIds = shareRows.stream().map(r -> r.get("id")).toList();
        List<Map<String, Object>> auditRows = jdbc.sql("""
                SELECT a.at, u.display_name AS actor, a.action, a.entity_type, a.details::text AS details
                FROM audit_log a LEFT JOIN app_user u ON u.id = a.actor_id
                WHERE a.entity_id = :h OR a.entity_id = ANY(CAST(:shares AS uuid[]))
                ORDER BY a.at
                """).param("h", h.id())
                .param("shares", "{" + String.join(",", shareIds.stream().map(String::valueOf).toList()) + "}")
                .query().listOfRows();

        ObjectMapper pretty = json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("handover.json", snapshotText.getBytes(StandardCharsets.UTF_8));
        files.put("checklist.json", pretty.writeValueAsBytes(checklist));
        files.put("shares.json", pretty.writeValueAsBytes(shareRows));
        files.put("audit.json", pretty.writeValueAsBytes(auditRows));
        files.put("report.html", report(h, versionNo, v, snapshot, checklist).getBytes(StandardCharsets.UTF_8));

        Tenant t = TenantContext.require();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "baton-export-1");
        manifest.put("workspace", t.slug());
        manifest.put("handoverId", h.id().toString());
        manifest.put("title", h.title());
        manifest.put("versionNo", versionNo);
        manifest.put("approvedBy", v.get("display_name"));
        manifest.put("approvedAt", String.valueOf(((java.sql.Timestamp) v.get("approved_at")).toInstant()));
        manifest.put("snapshotSha256", v.get("sha256"));
        manifest.put("snapshotExact", v.get("exact"));
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("exportedBy", me.displayName());
        Map<String, String> hashes = new LinkedHashMap<>();
        files.forEach((name, bytes) -> hashes.put(name, sha256(bytes)));
        manifest.put("files", hashes);
        byte[] manifestBytes = pretty.writeValueAsBytes(manifest);
        files.put("manifest.json", manifestBytes);
        files.put("manifest.sig", sign(t, manifestBytes).getBytes(StandardCharsets.UTF_8));
        files.put("VERIFY.txt", verifyText(v).getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the export", e);
        }
        audit.record(me.id(), "VERSION_EXPORTED", "handover", h.id(), h.projectId(), Map.of("version", versionNo));
        String safe = h.title().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase();
        return new Export("baton-" + (safe.isEmpty() ? "handover" : safe) + "-v" + versionNo + ".zip", out.toByteArray());
    }

    public record Verification(boolean valid, boolean signatureOk, boolean filesOk, boolean matchesRecord,
                               String title, Integer versionNo, String approvedAt, List<String> problems) {
    }

    /** Checks an export: every file against the manifest, the manifest signature, and the hash Baton recorded. */
    @Transactional(readOnly = true)
    public Verification verify(CurrentUser me, byte[] zipBytes) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            long total = 0;
            while ((e = zip.getNextEntry()) != null) {
                byte[] b = zip.readNBytes(20_000_000);
                total += b.length;
                if (total > 50_000_000 || files.size() > 50) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "That file is too large to be a Baton export");
                }
                files.put(e.getName(), b);
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That is not a zip file");
        }
        List<String> problems = new ArrayList<>();
        byte[] manifestBytes = files.get("manifest.json");
        if (manifestBytes == null || !files.containsKey("manifest.sig")) {
            return new Verification(false, false, false, false, null, null, null, List.of("No manifest: not a Baton export"));
        }
        Map<String, Object> manifest;
        try {
            manifest = json.readValue(manifestBytes, MAP);
        } catch (RuntimeException e) {
            return new Verification(false, false, false, false, null, null, null, List.of("The manifest is unreadable"));
        }
        Tenant t = TenantContext.require();
        boolean sigOk = t.slug().equals(manifest.get("workspace"))
                && MessageDigest.isEqual(sign(t, manifestBytes).getBytes(StandardCharsets.UTF_8),
                new String(files.get("manifest.sig"), StandardCharsets.UTF_8).strip().getBytes(StandardCharsets.UTF_8));
        if (!sigOk) {
            problems.add(t.slug().equals(manifest.get("workspace"))
                    ? "The manifest signature does not match: it was changed after export"
                    : "This export comes from another workspace (" + manifest.get("workspace") + ")");
        }
        boolean filesOk = true;
        @SuppressWarnings("unchecked")
        Map<String, String> hashes = (Map<String, String>) manifest.getOrDefault("files", Map.of());
        for (Map.Entry<String, String> f : hashes.entrySet()) {
            byte[] b = files.get(f.getKey());
            if (b == null) {
                filesOk = false;
                problems.add(f.getKey() + " is missing");
            } else if (!sha256(b).equals(f.getValue())) {
                filesOk = false;
                problems.add(f.getKey() + " was changed after export");
            }
        }
        boolean record = false;
        Integer versionNo = manifest.get("versionNo") instanceof Number n ? n.intValue() : null;
        try {
            UUID hid = UUID.fromString(String.valueOf(manifest.get("handoverId")));
            Handover h = access.requireLead(me, hid);
            String stored = jdbc.sql("SELECT sha256 FROM handover_version WHERE handover_id = :h AND version_no = :n")
                    .param("h", h.id()).param("n", versionNo).query(String.class).optional().orElse(null);
            record = stored != null && stored.equals(manifest.get("snapshotSha256"));
            if (record && Boolean.TRUE.equals(manifest.get("snapshotExact")) && files.containsKey("handover.json")) {
                record = stored.equals(sha256(files.get("handover.json")));
            }
            if (!record) {
                problems.add("The snapshot does not match the version Baton recorded");
            }
        } catch (IllegalArgumentException | ApiException e) {
            problems.add("This workspace has no matching handover you lead");
        }
        return new Verification(sigOk && filesOk && record, sigOk, filesOk, record, (String) manifest.get("title"),
                versionNo, (String) manifest.get("approvedAt"), problems);
    }

    private String sign(Tenant t, byte[] data) {
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(tenants.exportDataKey(t), "HmacSHA256"));
            byte[] key = derive.doFinal("baton-export-v1".getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String verifyText(Map<String, Object> v) {
        return """
                How to check this export
                ========================
                1. Every file is listed in manifest.json with its SHA-256. Recompute them, e.g.
                     sha256sum handover.json checklist.json shares.json audit.json report.html
                   and compare.
                2. handover.json is the frozen version exactly as approved. Its SHA-256 is the version hash Baton
                   recorded at approval: %s
                3. manifest.sig signs manifest.json with a key only your Baton workspace holds. To confirm it,
                   upload this zip in Baton: Approval > Versions > Verify an export.
                """.formatted(v.get("sha256"));
    }

    @SuppressWarnings("unchecked")
    private static String report(Handover h, int versionNo, Map<String, Object> v, Map<String, Object> snapshot,
                                 List<Map<String, Object>> checklist) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(esc(h.title())).append(" v").append(versionNo)
                .append("</title><style>body{font:14px/1.5 system-ui,sans-serif;max-width:860px;margin:32px auto;padding:0 16px;color:#111}")
                .append("h1{font-size:24px}h2{font-size:18px;margin-top:28px;border-bottom:1px solid #ddd}")
                .append(".c{margin:10px 0;padding:8px 12px;border-left:3px solid #4f46e5;background:#f7f7fb}")
                .append(".q{color:#444;font-style:italic;margin:4px 0 0}.m{color:#666;font-size:12px}code{font-size:12px}")
                .append("@media print{.c{break-inside:avoid}}</style></head><body>");
        b.append("<h1>").append(esc(h.title())).append("</h1>");
        Map<String, Object> project = (Map<String, Object>) snapshot.getOrDefault("project", Map.of());
        b.append("<p class=\"m\">Project ").append(esc(String.valueOf(project.get("name")))).append(" · person leaving: ")
                .append(esc(String.valueOf(snapshot.get("departingName")))).append(" · version ").append(versionNo)
                .append(" approved by ").append(esc(String.valueOf(v.get("display_name")))).append(" on ")
                .append(((java.sql.Timestamp) v.get("approved_at")).toInstant()).append("<br>SHA-256 <code>")
                .append(v.get("sha256")).append("</code></p>");
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) snapshot.getOrDefault("claims", List.of())) {
            byType.computeIfAbsent((String) c.get("type"), k -> new ArrayList<>()).add(c);
        }
        byType.forEach((type, claims) -> {
            b.append("<h2>").append(esc(type.replace('_', ' ').toLowerCase())).append(" (").append(claims.size()).append(")</h2>");
            for (Map<String, Object> c : claims) {
                b.append("<div class=\"c\"><strong>").append(esc((String) c.get("title"))).append("</strong> <span class=\"m\">")
                        .append(esc((String) c.get("status"))).append("</span>");
                for (Map<String, Object> e : (List<Map<String, Object>>) c.getOrDefault("evidence", List.of())) {
                    b.append("<p class=\"q\">“").append(esc((String) e.get("quote"))).append("” <span class=\"m\">— ")
                            .append(esc(String.valueOf(e.get("source")))).append("</span></p>");
                }
                b.append("</div>");
            }
        });
        b.append("<h2>Checklist</h2>");
        for (String phase : List.of("DAY1", "WEEK1", "MONTH1")) {
            b.append("<h3>").append(phase.equals("DAY1") ? "Day 1" : phase.equals("WEEK1") ? "Week 1" : "Month 1").append("</h3><ul>");
            checklist.stream().filter(i -> phase.equals(i.get("phase")))
                    .forEach(i -> b.append("<li>").append(esc((String) i.get("text"))).append("</li>"));
            b.append("</ul>");
        }
        return b.append("</body></html>").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
