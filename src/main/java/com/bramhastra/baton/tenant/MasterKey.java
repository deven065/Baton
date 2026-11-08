package com.bramhastra.baton.tenant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The server master key (baton.crypto.key). It only seals control-plane secrets: each company's database
 * credentials and data key. Company data itself is encrypted with that company's own data key.
 */
@Component
public class MasterKey {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] raw;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public MasterKey(@Value("${baton.crypto.key}") String base64Key) {
        this.raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalStateException("baton.crypto.key must be 32 bytes, base64-encoded");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** The demo workspace keeps using the master key as its data key, so data encrypted before tenancy still opens. */
    byte[] rawForDemo() {
        return raw.clone();
    }

    public String seal(String plaintext) {
        return seal(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public String seal(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Sealing failed", e);
        }
    }

    public byte[] openBytes(String sealed) {
        try {
            String[] parts = sealed.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(parts[0])));
            return cipher.doFinal(Base64.getDecoder().decode(parts[1]));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Opening a sealed value failed (wrong master key?)", e);
        }
    }

    public String open(String sealed) {
        return new String(openBytes(sealed), StandardCharsets.UTF_8);
    }
}
