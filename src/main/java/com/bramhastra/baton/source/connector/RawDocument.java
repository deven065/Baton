package com.bramhastra.baton.source.connector;

import java.time.Instant;

/** One document as fetched, BEFORE redaction. Lives only in memory. */
public record RawDocument(String externalRef, String title, String author, Instant authoredAt, String url,
                          String mime, String text) {
}
