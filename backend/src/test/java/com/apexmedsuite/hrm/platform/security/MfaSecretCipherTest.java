package com.apexmedsuite.hrm.platform.security;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class MfaSecretCipherTest {
    @Test void encryptDecryptAndRandomIv() {
        var cipher=new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String a=cipher.encrypt("deadbeef"),b=cipher.encrypt("deadbeef");
        assertNotEquals(a,b);
        assertEquals("deadbeef",cipher.decrypt(a));
        assertEquals("deadbeef",cipher.decrypt(b));
        assertThrows(IllegalStateException.class,()->cipher.decrypt("deadbeef"));
    }
    @Test void missingKeyFailsClosed() {
        var cipher=new MfaSecretCipher("");
        assertThrows(IllegalStateException.class,()->cipher.encrypt("secret"));
    }
}
