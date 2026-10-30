package com.apexmedsuite.hrm.platform.security;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** MFA provisioning and TOTP verification. Pending factors must be confirmed before they can be used. */
@Service
public class MfaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TotpVerifier verifier;
    private final MfaSecretCipher cipher;
    private final TransactionTemplate transactions;
    public MfaService(JdbcTemplate jdbc,TotpVerifier verifier,MfaSecretCipher cipher,
            org.springframework.transaction.PlatformTransactionManager tx) {
        this.jdbc=jdbc;this.verifier=verifier;this.cipher=cipher;this.transactions=new TransactionTemplate(tx);
    }
    public record Enrollment(String secretHex,String type) {}
    public Enrollment enroll(HrmUserPrincipal user) {
        return transactions.execute(status -> {
            // Prevent silent replacement of an active authenticator.
            Integer enrolled=jdbc.queryForObject("""
                SELECT count(*) FROM auth_totp_factor WHERE identity_id=? AND enabled=true
                """,Integer.class,user.id());
            if(enrolled!=null && enrolled>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Authenticator already active");
            byte[] secret=new byte[20];RANDOM.nextBytes(secret);
            String encoded=HexFormat.of().formatHex(secret);
            jdbc.update("""
                INSERT INTO auth_totp_factor(identity_id,secret_ciphertext,enabled,last_counter)
                VALUES (?,?,false,-1)
                ON CONFLICT (identity_id) DO UPDATE SET secret_ciphertext=EXCLUDED.secret_ciphertext,
                    enabled=false,last_counter=-1
                WHERE auth_totp_factor.enabled=false
                """,user.id(),cipher.encrypt(encoded));
            return new Enrollment(encoded,"totp-sha1-30s-6digits");
        });
    }
    public void confirm(HrmUserPrincipal user,String code) {
        transactions.executeWithoutResult(status -> {
            var rows=jdbc.query("""
                SELECT secret_ciphertext,last_counter FROM auth_totp_factor
                WHERE identity_id=? AND enabled=false FOR UPDATE
                """,(rs,n)->new Factor(rs.getString(1),rs.getLong(2)),user.id());
            if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No pending authenticator");
            Factor f=rows.get(0);
            if(!verifier.verify(HexFormat.of().parseHex(cipher.decrypt(f.secret())),code,f.counter()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid code");
            long counter=verifier.matchedCounter(HexFormat.of().parseHex(f.secret()),code,f.counter());
            jdbc.update("""
                UPDATE auth_totp_factor SET enabled=true,last_counter=? WHERE identity_id=?
                """,counter,user.id());
        });
    }
    public boolean isEnabled(UUID userId) {
        Integer n=jdbc.queryForObject("""
            SELECT count(*) FROM auth_totp_factor WHERE identity_id=? AND enabled=true
            """,Integer.class,userId);
        return n!=null&&n>0;
    }
    public void requireVerified(HrmUserPrincipal user,String code) {
        transactions.executeWithoutResult(status -> {
            var rows=jdbc.query("""
                SELECT secret_ciphertext,last_counter FROM auth_totp_factor
                WHERE identity_id=? AND enabled=true FOR UPDATE
                """,(rs,n)->new Factor(rs.getString(1),rs.getLong(2)),user.id());
            if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Authenticator enrollment required");
            Factor f=rows.get(0);
            byte[] secret=HexFormat.of().parseHex(f.secret());
            long counter=verifier.matchedCounter(secret,code,f.counter());
            if(counter<0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid MFA code");
            jdbc.update("UPDATE auth_totp_factor SET last_counter=? WHERE identity_id=?",
                counter,user.id());
        });
    }
    private record Factor(String secret,long counter) {}
}
