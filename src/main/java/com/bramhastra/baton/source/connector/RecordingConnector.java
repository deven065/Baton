package com.bramhastra.baton.source.connector;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.bramhastra.baton.ai.SpeakerLabeller;
import com.bramhastra.baton.ai.Transcriber;
import com.bramhastra.baton.source.SourceType;

/**
 * Meeting recordings. An audio file is transcribed with Whisper (through the workspace's AI provider) and speaker
 * turns are suggested by the model; a transcript file exported by Zoom, Teams or Meet (.vtt, .srt) is read as is,
 * with its speaker names. Either way the stored text is one {@code [hh:mm:ss] Speaker: text} line per segment, so
 * every quote knows the moment it was said. The audio is kept (encrypted) by the ingest job for playback.
 */
@Component
public class RecordingConnector implements SourceConnector {

    public static final Set<String> AUDIO = Set.of("mp3", "m4a", "wav", "webm", "ogg", "oga", "flac", "mp4", "mpeg", "mpga");
    static final String SUGGESTED = "Speaker names were suggested by AI from the conversation; check them before relying on who said what.";

    private final Transcriber transcriber;
    private final SpeakerLabeller speakers;

    public RecordingConnector(Transcriber transcriber, SpeakerLabeller speakers) {
        this.transcriber = transcriber;
        this.speakers = speakers;
    }

    public static boolean isAudio(StagedFile file) {
        return file != null && AUDIO.contains(file.extension());
    }

    @Override
    public boolean supports(SourceType type) {
        return type == SourceType.MEETING_RECORDING;
    }

    @Override
    public void validate(Map<String, Object> config, StagedFile file) {
        if (file == null) {
            throw new IllegalArgumentException("Upload the recording (audio) or its transcript file");
        }
        String ext = file.extension();
        if (Transcript.isTranscriptFile(ext)) {
            return;
        }
        if (!AUDIO.contains(ext)) {
            throw new IllegalArgumentException("Upload an audio file (mp3, m4a, wav, webm, ogg, flac) or a transcript (.vtt, .srt)");
        }
        try {
            if (!transcriber.available()) {
                throw new IllegalArgumentException(Transcriber.NEEDS_PROVIDER);
            }
        } catch (Transcriber.TranscriptionException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) {
        StagedFile file = request.file();
        List<Transcript.Segment> segments;
        String note = null;
        if (Transcript.isTranscriptFile(file.extension())) {
            status.accept("Reading the transcript");
            segments = Transcript.parse(new String(file.bytes(), StandardCharsets.UTF_8));
        } else {
            status.accept("Transcribing the recording");
            segments = transcriber.transcribe(file.filename(), file.mime(), file.bytes());
            status.accept("Marking who said what");
            List<Transcript.Segment> labelled = speakers.label(segments);
            if (labelled != segments) {
                note = SUGGESTED;
            }
            segments = labelled;
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("No speech found in this recording");
        }
        String text = (note == null ? "" : note + "\n\n") + Transcript.render(segments);
        String title = request.title();
        return List.of(new RawDocument("recording:" + file.filename(), title, null, meetingDate(request.config()), null,
                "text/plain", text));
    }

    /** The date the lead gave for the meeting (yyyy-mm-dd), else now. Dates drive recency and the timeline. */
    static Instant meetingDate(Map<String, Object> config) {
        Object d = config == null ? null : config.get("date");
        if (d != null) {
            try {
                return java.time.LocalDate.parse(d.toString()).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);
            } catch (java.time.format.DateTimeParseException ignored) {
                // fall through
            }
        }
        return Instant.now();
    }
}
