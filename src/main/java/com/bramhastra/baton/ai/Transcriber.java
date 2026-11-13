package com.bramhastra.baton.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.bramhastra.baton.source.connector.Transcript;
import com.bramhastra.baton.tenant.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Speech to text with Whisper, through the workspace's own AI provider (Groq or OpenAI, both OpenAI-compatible
 * {@code /audio/transcriptions}), or the server's Groq key when there is one. Returns timed segments; Whisper does
 * not tell speakers apart, so speakers are left empty here (see SpeakerLabeller).
 */
@Component
public class Transcriber {

    public static final String NEEDS_PROVIDER = "Transcribing a recording needs an AI provider. Connect one in "
            + "Organisation settings → AI (Groq or OpenAI), or upload the meeting's transcript file (.vtt or .srt) instead.";

    private final AiSettingsService settings;
    private final ObjectMapper json;
    private final boolean mock;
    private final String serverKey;
    private final String serverBaseUrl;
    private final String modelOverride;

    public Transcriber(AiSettingsService settings, ObjectMapper json,
                       @Value("${baton.ai.mock:true}") boolean mock,
                       @Value("${baton.groq.api-key:}") String serverKey,
                       @Value("${baton.groq.base-url}") String serverBaseUrl,
                       @Value("${baton.transcribe.model:}") String modelOverride) {
        this.settings = settings;
        this.json = json;
        this.mock = mock;
        this.serverKey = serverKey;
        this.serverBaseUrl = serverBaseUrl;
        this.modelOverride = modelOverride;
    }

    public static class TranscriptionException extends RuntimeException {
        public TranscriptionException(String message) {
            super(message);
        }
    }

    record Target(String baseUrl, String apiKey, String model, String label) {
    }

    /** Whether a recording can be transcribed right now in this workspace. */
    public boolean available() {
        return target().isPresent();
    }

    Optional<Target> target() {
        Optional<AiSettingsService.Active> own = TenantContext.current().flatMap(t -> settings.active());
        if (own.isPresent()) {
            AiSettingsService.Active a = own.get();
            String model = !modelOverride.isBlank() ? modelOverride : switch (a.provider()) {
                case GROQ -> "whisper-large-v3-turbo";
                case OPENROUTER -> null; // no audio endpoint
                default -> "whisper-1";
            };
            if (model == null) {
                throw new TranscriptionException("OpenRouter cannot transcribe audio. Use Groq or OpenAI for recordings, "
                        + "or upload the meeting's transcript file (.vtt or .srt).");
            }
            return Optional.of(new Target(a.baseUrl(), a.apiKey(), model, a.provider().name().toLowerCase()));
        }
        if (!mock && serverKey != null && !serverKey.isBlank()) {
            return Optional.of(new Target(serverBaseUrl, serverKey,
                    modelOverride.isBlank() ? "whisper-large-v3-turbo" : modelOverride, "groq"));
        }
        return Optional.empty();
    }

    public List<Transcript.Segment> transcribe(String filename, String mime, byte[] audio) {
        Target t = target().orElseThrow(() -> new TranscriptionException(NEEDS_PROVIDER));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
        factory.setReadTimeout(Duration.ofMinutes(5));
        RestClient http = RestClient.builder().baseUrl(t.baseUrl()).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + t.apiKey()).build();

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        form.add("model", t.model());
        form.add("response_format", "verbose_json");
        form.add("temperature", "0");

        record Reply(int status, String body) {
        }
        Reply r;
        try {
            r = http.post().uri("/audio/transcriptions").contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                    .exchange((req, res) -> new Reply(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new TranscriptionException("Cannot reach the AI provider (" + t.label() + ") to transcribe the recording");
        }
        if (r.status() != 200) {
            String said = null;
            try {
                said = json.readTree(r.body()).path("error").path("message").asString();
            } catch (RuntimeException ignored) {
                // not JSON
            }
            throw new TranscriptionException("The AI provider (" + t.label() + ") could not transcribe the recording: HTTP "
                    + r.status() + (said == null || said.isBlank() ? "" : ". It said: " + abbreviate(said)));
        }
        JsonNode root = json.readTree(r.body());
        List<Transcript.Segment> out = new ArrayList<>();
        for (JsonNode s : root.path("segments")) {
            String text = s.path("text").asString("").strip();
            if (!text.isEmpty()) {
                out.add(new Transcript.Segment(s.path("start").asDouble(0), null, text));
            }
        }
        if (out.isEmpty()) {
            String text = root.path("text").asString("").strip();
            if (!text.isEmpty()) {
                out.add(new Transcript.Segment(0, null, text)); // provider returned no segments
            }
        }
        return out;
    }

    private static String abbreviate(String s) {
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
