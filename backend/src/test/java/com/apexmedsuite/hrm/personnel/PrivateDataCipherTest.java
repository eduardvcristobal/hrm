package com.apexmedsuite.hrm.personnel;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateDataCipherTest {
    @Test void randomizedCiphertextAndEmployeeBinding() {
        var cipher=new PrivateDataCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String a=cipher.encrypt("example","tenant:employee:mobile");
        String b=cipher.encrypt("example","tenant:employee:mobile");
        assertNotEquals(a,b);
        assertEquals("example",cipher.decrypt(a,"tenant:employee:mobile"));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(a,"other:employee:mobile"));
    }
    @Test void noPlaintextOrMissingKeyFallback() {
        var cipher=new PrivateDataCipher("");
        assertThrows(IllegalStateException.class,()->cipher.encrypt("example","employee"));
    }
}
