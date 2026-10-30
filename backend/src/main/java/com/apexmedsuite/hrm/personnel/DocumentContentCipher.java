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
public class DocumentContentCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;
    public DocumentContentCipher(@Value("${HRM_DOCUMENT_KEY:}") String encoded) {
        if(encoded==null || encoded.isBlank()) { key=null; return; }
        byte[] raw=Base64.getDecoder().decode(encoded);
        if(raw.length!=32) throw new IllegalStateException("HRM_DOCUMENT_KEY must be 32 bytes");
        key=new SecretKeySpec(raw,"AES");
    }
    public record Encrypted(byte[] nonce,byte[] ciphertext) {}
    public Encrypted encrypt(byte[] raw,String context) {
        if(key==null) throw new IllegalStateException("HRM_DOCUMENT_KEY must be configured");
        try {
            byte[] nonce=new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(nonce,cipher.doFinal(raw));
        }catch(Exception ex){throw new IllegalStateException("Document encryption failure",ex);}
    }
    public byte[] decrypt(Encrypted encrypted,String context) {
        if(key==null) throw new IllegalStateException("HRM_DOCUMENT_KEY must be configured");
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypted.nonce()));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(encrypted.ciphertext());
        }catch(Exception ex){throw new IllegalStateException("Document authentication failure",ex);}
    }
}
