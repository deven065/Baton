package com.bramhastra.baton.security;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Service;

import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

/**
 * AES-256-GCM for redacted originals and staged uploads, with the current workspace's own data key,
 * so one company's ciphertext cannot be opened with another's key. Each call uses a fresh 96-bit IV.
 */
@Service
public class CryptoService {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final TenantRegistry registry;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(TenantRegistry registry) {
        this.registry = registry;
    }

    private SecretKey key() {
        return registry.dataKey(TenantContext.require());
    }

    public Sealed encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new Sealed(cipher.doFinal(plaintext), iv);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public record Sealed(byte[] ciphertext, byte[] iv) {
    }
}
