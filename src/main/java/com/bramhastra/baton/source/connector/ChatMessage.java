package com.bramhastra.baton.source.connector;

import java.time.LocalDateTime;

/**
 * One chat message from any export, before it is grouped into documents. The time is the wall-clock time
 * the export shows (Slack: UTC; WhatsApp and Discord: the exporter's local time), so quotes read as people saw them.
 */
record ChatMessage(String channel, LocalDateTime at, String author, String text) {
}
