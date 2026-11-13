package com.bramhastra.baton.source.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A meeting transcript as timed segments, and the text form Baton stores: one line per segment,
 * {@code [hh:mm:ss] Speaker: text}. The leading time is how a cited quote finds its moment in the recording.
 * Parses WebVTT (Zoom, Teams, Google Meet: speakers as {@code <v Name>} or {@code Name: }) and SubRip (.srt).
 */
public final class Transcript {

    public record Segment(double start, String speaker, String text) {
    }

    /** "[00:12:34]" at the start of a stored line. */
    public static final Pattern STAMP = Pattern.compile("\\[(\\d{1,2}):(\\d{2}):(\\d{2})]");

    private static final Pattern CUE_TIME = Pattern.compile(
            "^(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*.*$");
    private static final Pattern VOICE = Pattern.compile("^<v(?:\\.[^ >]*)?\\s+([^>]+)>(.*?)(?:</v>)?$");
    private static final Pattern NAMED = Pattern.compile("^([\\p{L}][\\p{L} .'-]{0,40}?):\\s+(.+)$");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private Transcript() {
    }

    public static boolean isTranscriptFile(String extension) {
        return extension.equals("vtt") || extension.equals("srt");
    }

    /** Parses a .vtt or .srt file. Consecutive cues by the same speaker are joined into one segment. */
    public static List<Segment> parse(String content) {
        List<Segment> out = new ArrayList<>();
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        Double start = null;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i].strip() : "";
            Matcher t = CUE_TIME.matcher(line);
            if (t.matches()) {
                start = seconds(t.group(1), t.group(2), t.group(3));
                text.setLength(0);
                continue;
            }
            if (start == null) {
                continue; // header, cue number or NOTE before a cue
            }
            if (!line.isEmpty()) {
                text.append(text.isEmpty() ? "" : " ").append(line);
                continue;
            }
            if (!text.isEmpty()) {
                add(out, start, text.toString());
            }
            start = null;
            text.setLength(0);
        }
        return out;
    }

    private static void add(List<Segment> out, double start, String raw) {
        String speaker = null;
        String body = raw;
        Matcher v = VOICE.matcher(raw);
        if (v.matches()) {
            speaker = v.group(1).strip();
            body = v.group(2);
        } else {
            Matcher n = NAMED.matcher(raw);
            if (n.matches() && !n.group(1).toLowerCase(Locale.ROOT).matches("note|http|https")) {
                speaker = n.group(1).strip();
                body = n.group(2);
            }
        }
        body = TAGS.matcher(body).replaceAll("").replaceAll("\\s+", " ").strip();
        if (body.isEmpty()) {
            return;
        }
        if (!out.isEmpty()) {
            Segment last = out.get(out.size() - 1);
            boolean same = speaker == null ? last.speaker() == null : speaker.equals(last.speaker());
            if (same && start - last.start() < 60 && last.text().length() < 600) {
                out.set(out.size() - 1, new Segment(last.start(), last.speaker(), last.text() + " " + body));
                return;
            }
        }
        out.add(new Segment(start, speaker, body));
    }

    static double seconds(String h, String m, String s) {
        return (h == null ? 0 : Integer.parseInt(h)) * 3600 + Integer.parseInt(m) * 60 + Integer.parseInt(s);
    }

    /** The stored text: {@code [hh:mm:ss] Speaker: text}, one segment per line. */
    public static String render(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            sb.append('[').append(clock(s.start())).append("] ");
            if (s.speaker() != null && !s.speaker().isBlank()) {
                sb.append(s.speaker()).append(": ");
            }
            sb.append(s.text()).append('\n');
        }
        return sb.toString();
    }

    public static String clock(double seconds) {
        long t = (long) Math.floor(seconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60);
    }

    /** The moment a quote at {@code offset} in a stored transcript chunk starts: the last stamp before it. */
    public static Integer secondsAt(String chunkText, Integer offset) {
        if (chunkText == null) {
            return null;
        }
        int limit = offset == null ? 0 : Math.min(Math.max(offset, 0), chunkText.length());
        Matcher m = STAMP.matcher(chunkText);
        Integer found = null;
        while (m.find()) {
            if (m.start() > limit) {
                break;
            }
            found = (int) seconds(m.group(1), m.group(2), m.group(3));
        }
        if (found == null && m.reset().find()) {
            found = (int) seconds(m.group(1), m.group(2), m.group(3)); // quote before the first stamp in this chunk
        }
        return found;
    }
}
