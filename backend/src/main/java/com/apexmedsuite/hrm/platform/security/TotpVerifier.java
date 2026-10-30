package com.apexmedsuite.hrm.platform.security;

import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** RFC 6238 HMAC-SHA1 30-second TOTP, limited to +/- one time step. */
@Component
public class TotpVerifier {
    public boolean verify(byte[] secret,String submitted,long previousCounter) {
        if(secret==null || submitted==null || !submitted.matches("[0-9]{6}")) return false;
        long counter=Instant.now().getEpochSecond()/30;
        int expected;
        for(long c=counter-1;c<=counter+1;c++) {
            if(c<=previousCounter) continue;
            expected=code(secret,c);
            if(String.format(java.util.Locale.ROOT,"%06d",expected).equals(submitted)) return true;
        }
        return false;
    }
    public int code(byte[] secret,long step) {
        try {
            Mac mac=Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret,"HmacSHA1"));
            byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset=digest[digest.length-1]&15;
            int n=((digest[offset]&127)<<24)|((digest[offset+1]&255)<<16)
                    |((digest[offset+2]&255)<<8)|(digest[offset+3]&255);
            return n%1_000_000;
        } catch(Exception e){throw new IllegalStateException("TOTP unavailable",e);}
    }
}
