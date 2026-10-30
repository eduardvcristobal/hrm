package com.apexmedsuite.hrm.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM envelope. Key must be managed externally; no fallback plaintext storage. */
@Component
public final class MfaSecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;
    private final SecretKeySpec previousKey;
    public MfaSecretCipher(@Value("${HRM_MFA_ENCRYPTION_KEY:}") String encoded,
            @Value("${HRM_MFA_PREVIOUS_ENCRYPTION_KEY:}") String previous) {
        this.previousKey = parseKey(previous);
        this.key=parseKey(encoded);
    }
    private static SecretKeySpec parseKey(String encoded) {
        if(encoded == null || encoded.isBlank()) return null;
        byte[] raw=Base64.getDecoder().decode(encoded);
        if(raw.length!=32) throw new IllegalStateException("MFA encryption key must contain 32 bytes");
        return new SecretKeySpec(raw,"AES");
    }
    public String encrypt(String secret) {
        if(key == null) throw new IllegalStateException("MFA encryption key not configured");
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return "v2:"+Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(encrypted);
        } catch(Exception ex){throw new IllegalStateException("MFA encryption failed",ex);}
    }
    public String decrypt(String payload) {
        if(key == null) throw new IllegalStateException("MFA encryption key not configured");
        if(payload == null || !(payload.startsWith("v1:") || payload.startsWith("v2:")))
            throw new IllegalStateException("Unsupported MFA secret format");
        try {
            String[] parts=payload.split(":",3);
            byte[] iv=Base64.getDecoder().decode(parts[1]);
            if(iv.length != 12) throw new IllegalStateException("Invalid MFA IV");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec candidate=payload.startsWith("v1:") && previousKey!=null ? previousKey : key;
            cipher.init(Cipher.DECRYPT_MODE,candidate,new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8);
        } catch(Exception ex){throw new IllegalStateException("MFA secret cannot be decrypted",ex);}
    }
}
