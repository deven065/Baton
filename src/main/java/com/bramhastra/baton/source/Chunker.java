package com.bramhastra.baton.source;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Splits (already redacted) document text into overlapping chunks. Each chunk's text is an exact
 * substring of the document text at [start, end), which is what the citation verifier relies on.
 */
@Component
public class Chunker {

    static final int TARGET = 1000;
    static final int MAX = 1400;
    static final int OVERLAP = 150;

    public List<Piece> split(String text) {
        List<Piece> pieces = new ArrayList<>();
        int len = text.length();
        int start = skipWhitespace(text, 0);
        while (start < len) {
            int end = Math.min(start + MAX, len);
            if (end < len) {
                end = bestBreak(text, start + TARGET / 2, end);
            }
            int trimmedEnd = end;
            while (trimmedEnd > start && Character.isWhitespace(text.charAt(trimmedEnd - 1))) {
                trimmedEnd--;
            }
            if (trimmedEnd > start) {
                pieces.add(new Piece(pieces.size(), text.substring(start, trimmedEnd), start, trimmedEnd));
            }
            if (end >= len) {
                break;
            }
            int next = Math.max(end - OVERLAP, start + 1);
            // start the overlap on a word boundary
            while (next < end && !Character.isWhitespace(text.charAt(next - 1))) {
                next++;
            }
            start = skipWhitespace(text, next);
        }
        return pieces;
    }

    /** Prefer a paragraph break, then a line break, then a sentence end, then any space. */
    private static int bestBreak(String text, int from, int to) {
        String window = text.substring(from, to);
        int i = window.lastIndexOf("\n\n");
        if (i >= 0) {
            return from + i + 2;
        }
        i = window.lastIndexOf('\n');
        if (i >= 0) {
            return from + i + 1;
        }
        i = Math.max(window.lastIndexOf(". "), Math.max(window.lastIndexOf("? "), window.lastIndexOf("! ")));
        if (i >= 0) {
            return from + i + 2;
        }
        i = window.lastIndexOf(' ');
        return i >= 0 ? from + i + 1 : to;
    }

    private static int skipWhitespace(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    public record Piece(int seq, String text, int start, int end) {
    }
}
