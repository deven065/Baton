package com.bramhastra.baton.source.connector;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.bramhastra.baton.integration.IntegrationService;
import com.bramhastra.baton.source.SourceType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only Google Drive connector: the Google Docs and text files in one folder (up to 50, each up to 1 MB).
 * Config: {"folder": folder id or its URL}. Uses the workspace's own service account, which must have been
 * given Viewer access to the folder. Scope: drive.readonly.
 */
@Component
public class GoogleDriveConnector implements SourceConnector {

    static final int MAX_FILES = 50;
    static final int MAX_BYTES = 1_000_000;
    private static final Pattern FOLDER_ID = Pattern.compile("[A-Za-z0-9_-]{10,}");
    private static final Pattern FOLDER_URL = Pattern.compile("/folders/([A-Za-z0-9_-]{10,})");
    private static final Set<String> TEXT = Set.of("text/plain", "text/markdown", "text/csv", "text/x-markdown");
    private static final String DOC = "application/vnd.google-apps.document";

    private final RestClient drive;
    private final String baseUrl;
    private final RestClient oauth;
    private final String tokenUrl;
    private final ObjectMapper json;
    private final IntegrationService integrations;

    public GoogleDriveConnector(ObjectMapper json, IntegrationService integrations,
                                @Value("${baton.google.drive-url:https://www.googleapis.com/drive/v3}") String driveUrl,
                                @Value("${baton.google.token-url:https://oauth2.googleapis.com/token}") String tokenUrl) {
        this.drive = RestClient.create();
        this.baseUrl = driveUrl.replaceAll("/+$", "");
        this.oauth = RestClient.create();
        this.tokenUrl = tokenUrl;
        this.json = json;
        this.integrations = integrations;
    }

    @Override
    public boolean supports(SourceType type) {
        return type == SourceType.GOOGLE_DRIVE;
    }

    @Override
    public void validate(Map<String, Object> config, StagedFile file) {
        config.put("folder", folderId(config.get("folder")));
        integrations.require(IntegrationService.Kind.GOOGLE_DRIVE);
    }

    static String folderId(Object raw) {
        String s = raw == null ? "" : raw.toString().strip();
        Matcher m = FOLDER_URL.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        if (!FOLDER_ID.matcher(s).matches()) {
            throw new IllegalArgumentException("Paste the Drive folder's link, or its id");
        }
        return s;
    }

    /** Checks a service-account key by getting an access token with it. */
    public void test(String serviceAccountJson) {
        accessToken(serviceAccountJson);
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) {
        String token = accessToken(integrations.require(IntegrationService.Kind.GOOGLE_DRIVE));
        String folder = folderId(request.config().get("folder"));

        status.accept("Listing the Drive folder");
        JsonNode list;
        try {
            list = get(token, "/files?q=" + enc("'" + folder + "' in parents and trashed = false")
                    + "&pageSize=200&orderBy=modifiedTime%20desc&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + "&fields=" + enc("files(id,name,mimeType,size,modifiedTime,webViewLink,lastModifyingUser(displayName))"));
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Drive cannot find that folder. Share it with the service account as Viewer");
        }
        List<RawDocument> docs = new ArrayList<>();
        int skipped = 0;
        for (JsonNode f : list.path("files")) {
            if (docs.size() >= MAX_FILES) {
                break;
            }
            String mime = f.path("mimeType").asString("");
            String id = f.path("id").asString();
            String text;
            if (DOC.equals(mime)) {
                text = raw(token, "/files/" + id + "/export?mimeType=text/plain");
            } else if (TEXT.contains(mime) && f.path("size").asLong(0) <= MAX_BYTES) {
                text = raw(token, "/files/" + id + "?alt=media&supportsAllDrives=true");
            } else {
                skipped++;
                continue;
            }
            if (text.length() > MAX_BYTES) {
                text = text.substring(0, MAX_BYTES);
            }
            String modified = f.path("modifiedTime").asString(null);
            docs.add(new RawDocument("drive/" + id, f.path("name").asString("Untitled"),
                    f.path("lastModifyingUser").path("displayName").asString(null),
                    modified == null ? null : Instant.parse(modified), f.path("webViewLink").asString(null),
                    "text/plain", text.strip()));
            status.accept("Read " + docs.size() + " Drive documents");
        }
        if (docs.isEmpty()) {
            throw new IllegalArgumentException(list.path("files").isEmpty()
                    ? "That folder is empty, or it is not shared with the service account (give it Viewer access)"
                    : "No Google Docs or text files in that folder (" + skipped + " other files were skipped)");
        }
        return docs;
    }

    /** Service-account flow: sign a short-lived JWT with the account's key and trade it for an access token. */
    private String accessToken(String serviceAccountJson) {
        try {
            JsonNode key = json.readTree(serviceAccountJson);
            long now = Instant.now().getEpochSecond();
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            // The audience is always Google's token endpoint, never an address taken from the pasted key file.
            String claims = b64.encodeToString(json.writeValueAsBytes(Map.of(
                    "iss", key.path("client_email").asString(), "scope", "https://www.googleapis.com/auth/drive.readonly",
                    "aud", "https://oauth2.googleapis.com/token", "iat", now, "exp", now + 600)));
            String pem = key.path("private_key").asString().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(pk);
            sig.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
            String jwt = header + "." + claims + "." + b64.encodeToString(sig.sign());
            String body = oauth.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + jwt)
                    .retrieve().body(String.class);
            String token = json.readTree(body == null ? "{}" : body).path("access_token").asString("");
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Google did not accept the service-account key");
            }
            return token;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            throw new IllegalArgumentException("Google refused the service-account key (was it deleted or disabled?)");
        } catch (Exception e) {
            throw new IllegalArgumentException("The service-account key could not be used. Reconnect Google Drive");
        }
    }

    private JsonNode get(String token, String uri) {
        try {
            String body = drive.get().uri(java.net.URI.create(baseUrl + uri)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().body(String.class);
            return json.readTree(body == null ? "{}" : body);
        } catch (HttpClientErrorException.Forbidden e) {
            throw new IllegalArgumentException("Google Drive refused access. Enable the Drive API for the service "
                    + "account's project and share the folder with it");
        }
    }

    private String raw(String token, String uri) {
        String body = drive.get().uri(java.net.URI.create(baseUrl + uri)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().body(String.class);
        return body == null ? "" : body;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
