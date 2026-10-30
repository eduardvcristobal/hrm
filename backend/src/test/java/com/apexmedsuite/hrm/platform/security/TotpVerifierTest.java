package com.apexmedsuite.hrm.platform.security;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TotpVerifierTest {
    @Test void rfc6238HmacSha1SixDigitProjection() {
        TotpVerifier verifier=new TotpVerifier();
        byte[] secret="12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals(287082,verifier.code(secret,59/30));
        assertEquals(81804,verifier.code(secret,1111111109L/30));
    }
    @Test void wrongLengthAndReplayRejected() {
        TotpVerifier verifier=new TotpVerifier();
        byte[] secret="12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals(-1,verifier.matchedCounter(secret,"not-numeric",-1));
        long current=java.time.Instant.now().getEpochSecond()/30;
        String code=String.format(java.util.Locale.ROOT,"%06d",verifier.code(secret,current));
        assertEquals(current,verifier.matchedCounter(secret,code,current-1));
        assertEquals(-1,verifier.matchedCounter(secret,code,current+1));
    }
}
