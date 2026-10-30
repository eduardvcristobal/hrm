package com.apexmedsuite.hrm.personnel;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PrivateDataCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;
    public PrivateDataCipher(@Value("${HRM_PERSONNEL_PII_KEY:}") String encoded) {
        if (encoded == null || encoded.isBlank()) { key = null; return; }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != 32) throw new IllegalStateException("Personnel encryption key must be 32 bytes");
        key = new SecretKeySpec(bytes, "AES");
    }
    public String encrypt(String plain, String context) {
        if (plain == null) return null;
        if (key == null) throw new IllegalStateException("Personnel encryption key required");
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(iv) + ":"
                + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Encryption failed", ex); }
    }
    public String decrypt(String envelope, String context) {
        if (envelope == null) return null;
        if (key == null) throw new IllegalStateException("Personnel encryption key required");
        try {
            String[] parts = envelope.split(":", 3);
            if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException("Invalid format");
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            if (iv.length != 12) throw new IllegalArgumentException("Invalid nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Decryption failed", ex); }
    }
}
