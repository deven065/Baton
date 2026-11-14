package com.bramhastra.baton.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Fingerprints used to tell, without re-indexing, whether a live source changed since the last read. */
public final class ContentHash {

    private ContentHash() {
    }

    public static String of(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One fingerprint for a whole source: independent of document order. A missing hash gives null. */
    public static String combine(Map<String, String> documentHashes) {
        if (documentHashes.values().stream().anyMatch(h -> h == null)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(documentHashes).forEach((ref, h) -> sb.append(ref).append('\0').append(h).append('\n'));
        return of(sb.toString());
    }
}
