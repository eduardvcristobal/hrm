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
    @Test void previousKeyDecryptsExistingFactorsButCannotDecryptWithWrongKey() {
        byte[] a=new byte[32];a[0]=7;
        byte[] b=new byte[32];b[0]=9;
        String aKey=Base64.getEncoder().encodeToString(a);
        String bKey=Base64.getEncoder().encodeToString(b);
        String legacy=new MfaSecretCipher(aKey).encrypt("abcd1234");
        var rotating=new MfaSecretCipher(bKey,aKey);
        assertEquals("abcd1234",rotating.decrypt(legacy));
        assertThrows(IllegalStateException.class,()->new MfaSecretCipher(bKey).decrypt(legacy));
        String newEnvelope=rotating.encrypt("newfactor");
        assertEquals("newfactor",new MfaSecretCipher(bKey).decrypt(newEnvelope));
    }
    @Test void missingKeyFailsClosed() {
        var cipher=new MfaSecretCipher("");
        assertThrows(IllegalStateException.class,()->cipher.encrypt("secret"));
    }
}
