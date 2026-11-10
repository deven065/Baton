package com.bramhastra.baton.source.connector;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WhatsApp "Export chat" text, as written by Android ({@code 12/10/26, 21:14 - Name: text}) and iPhone
 * ({@code [12/10/26, 9:14:05 PM] Name: text}). Lines without a date continue the previous message.
 * Whether dates are day-first or month-first is decided from the whole file; ambiguous files are read
 * day-first, as Indian phones write them.
 */
final class WhatsAppExport {

    // date, time, optional AM/PM; then " - " (Android) or "] " (iPhone)
    private static final Pattern LINE = Pattern.compile(
            "^\\[?(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4}),? (\\d{1,2})[:.](\\d{2})(?:[:.]\\d{2})? ?([AaPp]\\.? ?[Mm]\\.?)?(?:] | - )(.*)$");
    private static final Pattern AUTHOR = Pattern.compile("^([^:]{1,80}?): (.*)$", Pattern.DOTALL);
    private static final Pattern SKIP = Pattern.compile(
            "(?i)^(<media omitted>|<attached: .*>|(image|video|audio|sticker|gif|document) omitted|"
                    + "this message was deleted|you deleted this message|null|<this message was edited>)$");

    private WhatsAppExport() {
    }

    static boolean looksLike(String text) {
        int checked = 0;
        for (String line : text.split("\\R", 20)) {
            String l = normalise(line);
            if (l.isBlank()) {
                continue;
            }
            if (LINE.matcher(l).matches()) {
                return true;
            }
            if (++checked >= 5) {
                break;
            }
        }
        return false;
    }

    static List<ChatMessage> parse(String text, String channel) {
        List<String[]> raw = new ArrayList<>(); // d1, d2, year, hour, minute, ampm, rest
        for (String line : text.split("\\R")) {
            String l = normalise(line);
            Matcher m = LINE.matcher(l);
            if (m.matches()) {
                raw.add(new String[] {m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6), m.group(7)});
            } else if (!raw.isEmpty() && !l.isBlank()) {
                String[] last = raw.get(raw.size() - 1);
                last[6] = last[6] + "\n" + l;
            }
        }
        boolean monthFirst = false;
        for (String[] r : raw) {
            if (Integer.parseInt(r[0]) > 12) {
                monthFirst = false;
                break;
            }
            if (Integer.parseInt(r[1]) > 12) {
                monthFirst = true;
            }
        }
        List<ChatMessage> out = new ArrayList<>();
        for (String[] r : raw) {
            Matcher a = AUTHOR.matcher(r[6]);
            if (!a.matches()) {
                continue; // "Priya created group", "Messages and calls are end-to-end encrypted", …
            }
            String body = a.group(2).strip();
            if (body.isEmpty() || SKIP.matcher(body).matches()) {
                continue;
            }
            int day = Integer.parseInt(monthFirst ? r[1] : r[0]);
            int month = Integer.parseInt(monthFirst ? r[0] : r[1]);
            int year = Integer.parseInt(r[2]);
            if (year < 100) {
                year += 2000;
            }
            int hour = Integer.parseInt(r[3]);
            if (r[5] != null) {
                boolean pm = r[5].toLowerCase(Locale.ROOT).startsWith("p");
                hour = hour % 12 + (pm ? 12 : 0);
            }
            LocalDateTime at;
            try {
                at = LocalDateTime.of(year, month, day, hour, Integer.parseInt(r[4]));
            } catch (java.time.DateTimeException e) {
                continue;
            }
            out.add(new ChatMessage(channel, at, a.group(1).strip(), body));
        }
        return out;
    }

    /** "WhatsApp Chat with Annapurna Core.txt" → "Annapurna Core". */
    static String channelFrom(String filename) {
        String base = filename.replaceAll("(?i)\\.(txt|zip)$", "");
        base = base.replaceFirst("(?i)^WhatsApp Chat (with|-) ", "").strip();
        return base.isEmpty() || base.equalsIgnoreCase("_chat") ? "WhatsApp chat" : base;
    }

    // Invisible direction marks and narrow spaces that WhatsApp puts around names and times.
    private static String normalise(String line) {
        return line.replace("‎", "").replace("‏", "").replace("﻿", "")
                .replace(' ', ' ').replace(' ', ' ').stripTrailing();
    }
}
