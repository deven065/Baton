package com.bramhastra.baton.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bramhastra.baton.knowledge.MessageParser.Message;

/**
 * Finds decisions in ordinary writing (chat, pull-request threads, meeting notes, ADR-style records) together with
 * the reasoning, the alternatives that were turned down, who decided and when.
 *
 * <p>Plain rules, no AI, so it works offline, costs nothing and every result can be explained: the quote is cut
 * word for word from the passage, and so is the reasoning. A sentence counts only when it states something decided
 * ("we decided to…", "we chose X over Y", "going with…", "switched from X to Y", "pushed the launch to 29 Nov"); questions,
 * hedges ("should we…", "maybe we could…"), negations ("haven't decided") and requests are skipped.
 */
public final class DecisionDetector {

    private DecisionDetector() {
    }

    /** One passage of text to read. {@code at} is its date, {@code author} its own author if it has one. */
    public record Passage(String title, String author, Instant at, String text) {
    }

    public record Found(String title, String reasoning, List<String> alternatives, String decidedBy, Instant decidedAt,
                        String quote, String topic, double confidence) {
        public boolean hasReason() {
            return reasoning != null && !reasoning.isBlank();
        }
    }

    static final double MIN_CONFIDENCE = 0.65;
    static final int MAX_QUOTE = 600;

    private record Cue(Pattern pattern, double strength, String verb) {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final List<Cue> CUES = List.of(
            new Cue(p("^(?:final\\s+)?decision(?:\\s+made)?\\s*[:\\-–—]\\s*(.+)$"), 0.92, ""),
            new Cue(p("^(?:decided|agreed|resolved|outcome|resolution|verdict)\\s*[:\\-–—]\\s*(.+)$"), 0.88, ""),
            new Cue(p("\\b(?:decided|agreed|settled|resolved|voted)\\s+(?:to|on|that|for|upon)\\s+(.+)$"), 0.86, ""),
            new Cue(p("\\b(?:made\\s+the\\s+call|the\\s+call\\s+was)\\s+(?:to|that|on)\\s+(.+)$"), 0.82, ""),
            new Cue(p("\\b(?:chose|chosen|picked|selected|opted\\s+for|opted\\s+to|adopted|standardi[sz]ed\\s+on)\\s+(.+)$"), 0.82, "choose"),
            new Cue(p("\\b(?:went|gone|going|go)\\s+with\\s+(.+)$"), 0.76, "go with"),
            new Cue(p("\\b(?:sticking|staying|stay|stick|sticks)\\s+with\\s+(.+)$"), 0.74, "keep"),
            new Cue(p("\\b(?:will|'ll|are\\s+going\\s+to|'re\\s+going\\s+to|going\\s+to|shall)\\s+use\\s+(.+)$"), 0.62, "use"),
            new Cue(p("\\b(?:switch(?:ed|ing)?|migrat(?:e|ed|ing)|mov(?:e|ed|ing))\\s+(?:\\w+\\s+){0,4}?((?:from|off|away\\s+from)\\s+.+)$"), 0.78, "switch"),
            new Cue(p("\\b(pushed|postponed|delayed|rescheduled|brought\\s+forward|pulled\\s+in|moved)\\s+(.+?\\s+(?:to|until|back\\s+to|by)\\s+.+)$"), 0.72, "reschedule"),
            new Cue(p("\\b(?:replac(?:ed|ing)|swapped|ditched|dropped|scrapped|abandoned|deprecated|retired|sunset|ruled\\s+out|killed)\\s+(.+)$"), 0.7, "drop"),
            new Cue(p("\\b(?:approved|signed\\s+off\\s+on|green-?lit|rejected|vetoed|declined)\\s+(.+)$"), 0.66, "approve"),
            new Cue(p("\\b(?:the\\s+plan\\s+is\\s+to|the\\s+plan\\s+is)\\s+(.+)$"), 0.64, ""));

    /** Words that make a sentence a question, a hypothesis, a request or a denial. */
    private static final Pattern NOT_A_DECISION = p("\\b(?:should\\s+we|shall\\s+we|can\\s+we|could\\s+we|what\\s+if|whether|"
            + "if\\s+(?:we|you|they)\\b|need(?:s)?\\s+to\\s+(?:decide|choose|pick|agree)|to\\s+be\\s+decided|tbd|"
            + "still\\s+(?:deciding|open)|undecided|under\\s+discussion|open\\s+question|deciding\\s+(?:between|whether)|"
            + "let'?s\\s+decide|waiting\\s+(?:for|on)\\s+(?:a\\s+)?decision|pending\\s+decision|"
            + "maybe|perhaps|probably|might|thinking\\s+(?:about|of)|proposal|propos(?:e|ed|ing)|suggest(?:ed|ing|ion)?|"
            + "wondering|not\\s+sure|please|pls|could\\s+you|can\\s+you|would\\s+you|any\\s+thoughts)\\b");

    /** "haven't decided", "not agreed", "never chose": the opposite of a decision. No leading word boundary, so "n't" works. */
    private static final Pattern DENIED = p("(?:n't|\\bnot\\b|\\bnever\\b|\\bno\\b)\\s+(?:\\w+\\s+){0,2}"
            + "(?:decided|agreed|chose|chosen|picked|selected|settled|resolved|going\\s+with|sure)\\b");

    /** What introduces the reason, in order of how clear each is. "since" counts unless it names a point in time. */
    private static final Pattern REASON_IN_SENTENCE = p("\\b(?:because(?:\\s+of)?|since\\s+(?!\\d|last\\b|then\\b|yesterday\\b|today\\b|now\\b|"
            + "monday|tuesday|wednesday|thursday|friday|saturday|sunday|january|february|march|april|may\\b|june|july|august|september|"
            + "october|november|december)[a-z]{2,}\\b|"
            + "due\\s+to|owing\\s+to|thanks\\s+to|so\\s+that|in\\s+order\\s+to|to\\s+avoid|to\\s+(?:keep|allow|let|make|ensure|reduce|cut|save|stop|prevent)\\b|"
            + "given\\s+that|as\\s+(?:we|it|they|this|that|our|the)\\b|the\\s+reason(?:\\s+is|:)?|reason\\s*:|which\\s+(?:means|lets|allows|gives|keeps)|"
            + "after\\s+[a-z]{3,}\\b|for\\s+the\\s+sake\\s+of|that\\s+way)\\b");

    /** A later sentence or message that explains the decision. */
    private static final Pattern REASON_LEAD = p("^(?:\\(?\\s*)?(?:reason|why|rationale|context|because|since|the\\s+reason|it'?s\\s+because|that'?s\\s+because|"
            + "this\\s+is\\s+because|this\\s+(?:way|lets|keeps|means|avoids)|that\\s+(?:way|lets|keeps|means|avoids)|bc|cuz|due\\s+to|"
            + "so\\s+that|it\\s+(?:is|'s)\\s+(?:cheaper|faster|simpler|safer|easier)|otherwise)\\b\\s*[:\\-–—,]?\\s*(.*)$");

    private static final Pattern ALTERNATIVE = Pattern.compile(
            "(?:\\bover|\\binstead\\s+of|\\brather\\s+than|\\bversus|\\bvs\\.?|\\bnot\\s+(?:to\\s+use)?|\\bfrom|\\breplac(?:e|ed|ing)|\\bdropp?(?:ed|ing)?|"
                    + "\\bditch(?:ed|ing)?|\\bscrapp?(?:ed|ing)?|\\bruled\\s+out|\\babandon(?:ed|ing)?)\\s+"
                    + "(`[^`]{2,60}`|\"[^\"]{2,60}\"|'[^']{2,60}'|[A-Z][\\w.+#/-]*(?:\\s+[A-Z][\\w.+#/-]*)?|[a-z][\\w.+#-]*(?:[-_.][\\w]+)+)");

    private static final Set<String> SENTENCE_START_WORDS = Set.of("we", "i", "the", "a", "an", "it", "this", "that", "so", "and", "but",
            "then", "also", "ok", "okay", "yes", "no", "for", "to", "in", "on", "after", "before", "since", "because", "decision", "final",
            "decided", "agreed", "outcome", "use", "using", "go", "going", "let", "lets", "our", "my", "your", "their", "there", "here",
            "next", "today", "yesterday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    private static final Set<String> CALENDAR = Set.of("january", "february", "march", "april", "may", "june", "july", "august",
            "september", "october", "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct",
            "nov", "dec", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    private static final Set<String> FILLER = Set.of("the", "a", "an", "to", "of", "for", "on", "in", "at", "and", "or", "our", "we", "is",
            "are", "was", "be", "it", "this", "that", "with", "from", "as", "by", "use", "using", "will", "going", "go", "all", "new",
            "old", "up", "down", "out", "off", "back", "now", "then", "so", "not", "than", "over", "instead");

    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9\"'`(\\[])");
    private static final Pattern ADR_HEADING = Pattern.compile("(?im)^#{1,4}\\s*(context|decision|status|consequences|rationale|why|background|"
            + "alternatives(?:\\s+considered)?|options(?:\\s+considered)?|drivers?)\\b[^\\n]*$");

    // ───────────── entry point ─────────────

    public static List<Found> detect(Passage passage) {
        List<Found> out = new ArrayList<>();
        if (passage.text() == null || passage.text().isBlank()) {
            return out;
        }
        out.addAll(adr(passage));
        List<Message> messages = MessageParser.parse(passage.text(), passage.author(), passage.at());
        Set<String> seen = new LinkedHashSet<>();
        out.forEach(f -> seen.add(key(f.title())));
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            for (Sentence s : sentences(passage.text(), m)) {
                Found f = fromSentence(passage, messages, i, s);
                if (f != null && f.confidence() >= MIN_CONFIDENCE && seen.add(key(f.title()))) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Stable identity of a decision: the same words in the same document are the same decision after a re-read. */
    public static String key(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    // ───────────── one sentence ─────────────

    private record Sentence(String text, int start, int end) {
    }

    private static List<Sentence> sentences(String whole, Message m) {
        List<Sentence> out = new ArrayList<>();
        String t = m.text();
        // Where the message's own text begins inside the passage (after a "[14:05] Priya: " prefix, if any).
        int base = whole.indexOf(t, Math.max(0, m.start()));
        boolean contiguous = base >= 0 && base < m.end() + 1;
        int pos = 0;
        Matcher sp = SENTENCES.matcher(t);
        List<int[]> spans = new ArrayList<>();
        while (sp.find()) {
            spans.add(new int[] {pos, sp.start()});
            pos = sp.end();
        }
        spans.add(new int[] {pos, t.length()});
        for (int[] sp2 : spans) {
            String s = t.substring(sp2[0], sp2[1]).strip();
            if (s.length() < 8) {
                continue;
            }
            int from = contiguous ? base + sp2[0] : m.start();
            int to = contiguous ? base + sp2[1] : m.end();
            // A markdown bullet or number in front of the sentence is not part of it.
            String stripped = s.replaceFirst("^(?:[-*•>]+|\\d+[.)])\\s+", "").replaceFirst("^\\*\\*([^*]+)\\*\\*\\s*", "$1 ");
            out.add(new Sentence(stripped, from, Math.max(from, to)));
        }
        return out;
    }

    private static Found fromSentence(Passage p, List<Message> messages, int index, Sentence s) {
        String text = s.text().replaceAll("\\*\\*|`{3}", "");
        String head = text.substring(0, Math.min(text.length(), 400));
        if (text.endsWith("?") || (!startsWithLabel(text) && (NOT_A_DECISION.matcher(head).find() || DENIED.matcher(head).find()))) {
            return null;
        }
        Cue best = null;
        Matcher bestMatch = null;
        for (Cue c : CUES) {
            Matcher m = c.pattern().matcher(text);
            if (m.find() && (best == null || c.strength() > best.strength())) {
                // A hedge before the cue ("maybe we decided…") already failed above; one after it is part of the reason.
                best = c;
                bestMatch = m;
            }
        }
        if (best == null) {
            return null;
        }
        // The object of the decision, cut where the reason starts.
        String object = bestMatch.groupCount() >= 2 && best.verb().equals("reschedule")
                ? bestMatch.group(1).toLowerCase(Locale.ROOT) + " " + bestMatch.group(2) : bestMatch.group(1);
        int objectStartInSentence = bestMatch.start(best.verb().equals("reschedule") ? 2 : 1);
        String reason = null;
        Matcher rm = REASON_IN_SENTENCE.matcher(object);
        // "after" only counts as a reason once the decision itself has some words ("push the launch ... after feedback").
        if (rm.find() && rm.start() > (rm.group().toLowerCase(Locale.ROOT).startsWith("after") ? 12 : 2)) {
            reason = clean(object.substring(rm.start()));
            object = object.substring(0, rm.start());
        } else {
            // The reason may come before the decision ("Because X, we decided to Y") or stand alone after it.
            String before = text.substring(0, Math.max(0, bestMatch.start()));
            Matcher pre = REASON_IN_SENTENCE.matcher(before);
            if (pre.find() && pre.start() == 0 && before.length() > 12) {
                reason = clean(before.substring(pre.end()).replaceFirst("(?i)[,;:\\s]+(?:we|i|they|the\\s+team|everyone)\\s*$", ""));
            }
        }
        object = clean(object);
        if (object.length() < 3) {
            return null;
        }
        String title = title(best, object);
        List<String> alternatives = alternatives(text, bestMatch.start(), best);
        int end = s.end();
        int quoteStart = s.start();

        // Reasoning that stands apart: the rest of this message, then the next message or two.
        if (reason == null || reason.length() < 6) {
            reason = null;
            Message m = messages.get(index);
            String rest = m.text().substring(Math.min(m.text().length(), Math.max(0, m.text().indexOf(s.text()) + s.text().length()))).strip();
            for (String next : SENTENCES.split(rest)) {
                Matcher lead = REASON_LEAD.matcher(next.strip());
                if (lead.find() && lead.group(1).length() >= 6) {
                    reason = clean(lead.group(1));
                    int at = p.text().indexOf(next.strip(), s.end());
                    end = at >= 0 ? Math.min(p.text().length(), at + next.strip().length()) : end;
                    break;
                }
            }
            for (int j = index + 1; reason == null && j < Math.min(messages.size(), index + 3); j++) {
                Message nx = messages.get(j);
                Matcher lead = REASON_LEAD.matcher(nx.text());
                if (lead.find() && lead.group(1).length() >= 6 && (nx.author() == null || nx.author().equals(messages.get(index).author())
                        || startsWithBecause(nx.text()))) {
                    reason = clean(lead.group(1));
                    end = Math.max(end, nx.end());
                }
            }
        }
        if (reason != null && reason.length() > 320) {
            reason = reason.substring(0, 320).replaceFirst("\\s+\\S*$", "") + "…";
        }
        if (reason != null && reason.length() < 6) {
            reason = null;
        }
        // The reason, taken from the same sentence, must still be words from the passage.
        String whole = p.text();
        int qs = Math.max(0, Math.min(quoteStart, whole.length()));
        int qe = Math.max(qs, Math.min(Math.max(end, s.end()), whole.length()));
        String quote = whole.substring(qs, qe).strip();
        if (quote.length() > MAX_QUOTE) {
            quote = quote.substring(0, MAX_QUOTE).replaceFirst("\\s+\\S*$", "");
        }
        if (reason != null && !whole.contains(reason.replace("…", ""))) {
            reason = firstPresent(whole, reason);
        }

        double conf = best.strength() + (reason != null ? 0.14 : 0) + (alternatives.isEmpty() ? 0 : 0.08)
                - (text.length() > 260 ? 0.1 : 0);
        Message author = messages.get(index);
        return new Found(title, reason, alternatives, author.author() != null ? author.author() : p.author(),
                author.at() != null ? author.at() : p.at(), quote, topic(title, object, alternatives), Math.min(0.97, conf));
    }

    private static boolean startsWithLabel(String t) {
        return t.matches("(?i)^(?:final\\s+)?(?:decision|decided|agreed|resolved|outcome|resolution|verdict)\\b.*");
    }

    private static boolean startsWithBecause(String t) {
        return t.matches("(?i)^(?:because|since|reason|the reason|it'?s because|that'?s because|bc|cuz)\\b.*");
    }

    /** The reason as found in the passage, in case cleaning changed spacing or punctuation. */
    private static String firstPresent(String whole, String reason) {
        String plain = reason.replace("…", "").strip();
        int cut = Math.min(plain.length(), 60);
        String head = plain.substring(0, cut);
        int at = whole.indexOf(head);
        if (at < 0) {
            return null;
        }
        int end = whole.indexOf('\n', at);
        String line = whole.substring(at, end < 0 ? whole.length() : end).strip();
        return line.length() > 320 ? line.substring(0, 320).replaceFirst("\\s+\\S*$", "") + "…" : line;
    }

    // ───────────── pieces ─────────────

    private static String title(Cue cue, String object) {
        String o = object.replaceFirst("^(?:to\\s+)", "");
        String t = switch (cue.verb()) {
            case "" -> o;
            case "switch" -> "switch " + o;
            case "reschedule" -> o.replaceFirst("^(\\w+)\\s+", "").isEmpty() ? o : verbFromReschedule(o);
            default -> cue.verb() + " " + o;
        };
        t = t.replaceFirst("(?i)^(we|i|they)\\s+(will|'ll|are|'re)\\s+", "").strip();
        if (t.length() > 160) {
            t = t.substring(0, 160).replaceFirst("\\s+\\S*$", "") + "…";
        }
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String verbFromReschedule(String o) {
        Matcher m = Pattern.compile("^(pushed|postponed|delayed|rescheduled|brought forward|pulled in|moved)\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(o);
        if (!m.matches()) {
            return o;
        }
        String v = m.group(1).toLowerCase(Locale.ROOT);
        String base = switch (v) {
            case "pushed" -> "push";
            case "postponed" -> "postpone";
            case "delayed" -> "delay";
            case "rescheduled" -> "reschedule";
            case "brought forward" -> "bring forward";
            case "pulled in" -> "pull in";
            default -> "move";
        };
        return base + " " + m.group(2);
    }

    private static List<String> alternatives(String sentence, int cueStart, Cue cue) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = ALTERNATIVE.matcher(sentence.substring(Math.max(0, Math.min(cueStart, sentence.length()))));
        while (m.find() && out.size() < 3) {
            String a = m.group(1).replaceAll("^[`\"']|[`\"']$", "").replaceAll("[.,;:]+$", "").strip();
            String lead = a.toLowerCase(Locale.ROOT);
            if (a.length() >= 2 && !FILLER.contains(lead) && !SENTENCE_START_WORDS.contains(lead)) {
                out.add(a);
            }
        }
        return new ArrayList<>(out);
    }

    /** What the decision is about, in a word or two, so decisions on the same subject can be grouped. */
    static String topic(String title, String object, List<String> alternatives) {
        Set<String> rejected = new LinkedHashSet<>();
        alternatives.forEach(a -> rejected.add(a.toLowerCase(Locale.ROOT)));
        Matcher id = Pattern.compile("`([^`]{2,40})`|\\b([A-Z][a-z]+[A-Z]\\w*|[A-Z]{2,}[A-Z0-9_]*|[A-Z][a-z]{2,}|\\w+(?:[._-]\\w+)+)\\b").matcher(object);
        while (id.find()) {
            String w = id.group(1) != null ? id.group(1) : id.group(2);
            String lw = w.toLowerCase(Locale.ROOT);
            boolean first = id.start() == 0;
            if (rejected.contains(lw) || SENTENCE_START_WORDS.contains(lw) || FILLER.contains(lw) || CALENDAR.contains(lw)
                    || (first && !w.matches(".*[A-Z0-9._-].*"))) {
                continue;
            }
            if (Character.isUpperCase(w.charAt(0)) || w.matches(".*[._-].*") || id.group(1) != null) {
                return w;
            }
        }
        List<String> words = new ArrayList<>();
        for (String w : title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() > 2 && !FILLER.contains(w) && !Set.of("push", "move", "switch", "choose", "drop", "keep", "approve", "reschedule").contains(w)) {
                words.add(w);
            }
            if (words.size() == 2) {
                break;
            }
        }
        return String.join(" ", words);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").replaceFirst("^[\\s,;:–—-]+", "").replaceFirst("[\\s,;:–—-]+$", "")
                .replaceFirst("\\.+$", "").strip();
    }

    // ───────────── ADR-style records ─────────────

    /** "## Context / ## Decision / ## Consequences": the decision section is the decision, the context is the why. */
    private static List<Found> adr(Passage p) {
        String t = p.text();
        Matcher h = ADR_HEADING.matcher(t);
        List<int[]> heads = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (h.find()) {
            heads.add(new int[] {h.start(), h.end()});
            names.add(h.group(1).toLowerCase(Locale.ROOT));
        }
        int decision = names.indexOf("decision");
        if (decision < 0) {
            return List.of();
        }
        String body = section(t, heads, decision);
        String first = firstParagraph(body);
        if (first.length() < 8) {
            return List.of();
        }
        String reasoning = null;
        for (String why : List.of("context", "rationale", "why", "background", "drivers", "driver")) {
            int i = names.indexOf(why);
            if (i >= 0) {
                String para = firstParagraph(section(t, heads, i));
                if (para.length() >= 10) {
                    reasoning = para.length() > 320 ? para.substring(0, 320).replaceFirst("\\s+\\S*$", "") + "…" : para;
                    break;
                }
            }
        }
        List<String> alts = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith("alternatives") || name.startsWith("options")) {
                for (String line : section(t, heads, names.indexOf(name)).split("\n")) {
                    String a = line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s+", "").strip();
                    if (!a.isEmpty() && a.length() <= 80 && !line.equals(a) && alts.size() < 3) {
                        alts.add(a.replaceAll("[.,;:]+$", ""));
                    }
                }
            }
        }
        String title = clean(first.replaceFirst("(?i)^(?:we|the\\s+team)\\s+(?:will|'ll|have\\s+decided\\s+to|decided\\s+to)\\s+", "")
                .split("(?<=[.!?])\\s")[0]);
        String quote = first.length() > MAX_QUOTE ? first.substring(0, MAX_QUOTE) : first;
        Found f = new Found(title.isEmpty() ? first : Character.toUpperCase(title.charAt(0)) + title.substring(1), reasoning, alts,
                p.author(), p.at(), quote, topic(title, title, alts), reasoning != null ? 0.95 : 0.88);
        return List.of(f);
    }

    private static String section(String text, List<int[]> heads, int i) {
        int from = heads.get(i)[1];
        int to = i + 1 < heads.size() ? heads.get(i + 1)[0] : text.length();
        return text.substring(from, Math.max(from, to)).strip();
    }

    private static String firstParagraph(String body) {
        for (String para : body.split("\n\\s*\n")) {
            String s = para.replaceAll("\\s*\n\\s*", " ").strip();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }
}
