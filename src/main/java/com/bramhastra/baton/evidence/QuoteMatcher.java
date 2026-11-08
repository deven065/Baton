package com.bramhastra.baton.evidence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Finds a model's quote inside the stored chunk text (LLD L4). Exact match after normalisation scores 1.0;
 * otherwise a sliding window of the quote's length is scored by token F1, refined by Levenshtein ratio.
 * A match is valid at 0.85 or above. Offsets always refer to the ORIGINAL chunk text, so the verified
 * quote shown to users is cut from the source itself, never the model's wording.
 */
@Component
public class QuoteMatcher {

    public static final double VALID_THRESHOLD = 0.85;
    static final int MIN_QUOTE = 12;

    public record MatchResult(double score, int start, int end, boolean valid) {
        static MatchResult invalid() {
            return new MatchResult(0, -1, -1, false);
        }
    }

    public MatchResult match(String quote, String chunkText) {
        if (quote == null || chunkText == null) {
            return MatchResult.invalid();
        }
        Norm q = Norm.of(quote);
        Norm t = Norm.of(chunkText);
        if (q.text.length() < MIN_QUOTE) {
            return MatchResult.invalid();
        }
        int idx = t.text.indexOf(q.text);
        if (idx >= 0) {
            return new MatchResult(1.0, t.origStart(idx), t.origEnd(idx + q.text.length() - 1), true);
        }

        List<Token> qt = tokens(q.text);
        List<Token> tt = tokens(t.text);
        int w = qt.size();
        if (w == 0 || tt.size() < w) {
            return MatchResult.invalid();
        }
        double best = 0;
        int bestPos = -1;
        for (int i = 0; i + w <= tt.size(); i++) {
            double s = tokenF1(qt, tt.subList(i, i + w));
            if (s > best) {
                best = s;
                bestPos = i;
            }
        }
        if (bestPos < 0) {
            return MatchResult.invalid();
        }
        int nStart = tt.get(bestPos).start();
        int nEnd = tt.get(bestPos + w - 1).end();
        if (best >= 0.70) {
            best = Math.max(best, levenshteinRatio(q.text, t.text.substring(nStart, nEnd)));
        }
        best = Math.round(best * 1000) / 1000.0;
        return new MatchResult(best, t.origStart(nStart), t.origEnd(nEnd - 1), best >= VALID_THRESHOLD);
    }

    // ───────────── helpers ─────────────

    private record Token(String text, int start, int end) {
    }

    private static List<Token> tokens(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < s.length() && s.charAt(i) != ' ') {
                i++;
            }
            if (i > start) {
                out.add(new Token(s.substring(start, i), start, i));
            }
        }
        return out;
    }

    static double tokenF1(List<Token> a, List<Token> b) {
        Map<String, Integer> counts = new HashMap<>();
        for (Token t : a) {
            counts.merge(strip(t.text()), 1, Integer::sum);
        }
        int overlap = 0;
        for (Token t : b) {
            String k = strip(t.text());
            Integer c = counts.get(k);
            if (c != null && c > 0) {
                overlap++;
                counts.put(k, c - 1);
            }
        }
        if (overlap == 0) {
            return 0;
        }
        double p = (double) overlap / b.size();
        double r = (double) overlap / a.size();
        return 2 * p * r / (p + r);
    }

    private static String strip(String token) {
        return token.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
    }

    static double levenshteinRatio(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return 1.0 - (double) prev[b.length()] / max;
    }

    /** Lowercase, unify quotes and dashes, collapse whitespace; remembers where each char came from. */
    static final class Norm {
        final String text;
        private final int[] map;

        private Norm(String text, int[] map) {
            this.text = text;
            this.map = map;
        }

        static Norm of(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            int[] map = new int[s.length()];
            boolean space = true; // drops leading whitespace
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (!space) {
                        map[sb.length()] = i;
                        sb.append(' ');
                        space = true;
                    }
                    continue;
                }
                space = false;
                c = switch (c) {
                    case '‘', '’', '‛' -> '\'';
                    case '“', '”' -> '"';
                    case '–', '—' -> '-';
                    default -> Character.toLowerCase(c);
                };
                map[sb.length()] = i;
                sb.append(c);
            }
            int len = sb.length();
            if (len > 0 && sb.charAt(len - 1) == ' ') {
                len--;
            }
            return new Norm(sb.substring(0, len), java.util.Arrays.copyOf(map, len));
        }

        int origStart(int normIndex) {
            return map[normIndex];
        }

        /** Exclusive end in the original text for the normalised char at {@code normIndex}. */
        int origEnd(int normIndex) {
            return map[normIndex] + 1;
        }
    }
}
