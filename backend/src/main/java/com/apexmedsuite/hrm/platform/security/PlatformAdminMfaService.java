package com.apexmedsuite.hrm.platform.security;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlatformAdminMfaService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TotpVerifier verifier;
    private final MfaSecretCipher cipher;
    private final TransactionTemplate tx;

    public PlatformAdminMfaService(JdbcTemplate jdbc,TotpVerifier verifier,MfaSecretCipher cipher,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.verifier=verifier;this.cipher=cipher;this.tx=new TransactionTemplate(manager);
    }

    public record Status(boolean enabled,boolean tokenVerified) {}
    public record Enrollment(String secretHex,String type) {}
    private record Factor(String ciphertext,long counter) {}

    public Status status(PlatformAdminPrincipal admin){
        Integer count=jdbc.queryForObject("SELECT count(*) FROM auth_platform_admin_totp_factor WHERE admin_id=? AND enabled=true",Integer.class,admin.id());
        return new Status(count!=null&&count>0,admin.mfaVerified());
    }

    public Enrollment enroll(PlatformAdminPrincipal admin){
        return tx.execute(transaction->{
            Integer active=jdbc.queryForObject("SELECT count(*) FROM auth_platform_admin_totp_factor WHERE admin_id=? AND enabled=true",Integer.class,admin.id());
            if(active!=null&&active>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"An authenticator is already active. Use verification instead.");
            byte[] secret=new byte[20];RANDOM.nextBytes(secret);String encoded=HexFormat.of().formatHex(secret);
            String encrypted;
            try{encrypted=cipher.encrypt(encoded);}catch(IllegalStateException missingKey){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Set HRM_MFA_ENCRYPTION_KEY in the backend environment before setting up platform admin MFA.");}
            jdbc.update("""
                    INSERT INTO auth_platform_admin_totp_factor(admin_id,secret_ciphertext,enabled,last_counter)
                    VALUES(?,?,false,-1) ON CONFLICT(admin_id) DO UPDATE SET secret_ciphertext=excluded.secret_ciphertext,enabled=false,last_counter=-1
                    WHERE auth_platform_admin_totp_factor.enabled=false
                    """,admin.id(),encrypted);
            return new Enrollment(encoded,"totp-sha1-30s-6digits");
        });
    }

    public void confirm(PlatformAdminPrincipal admin,String code,String rawToken){
        tx.executeWithoutResult(transaction->{
            Factor factor=pendingFactor(admin.id());long counter=verify(factor,code);
            jdbc.update("UPDATE auth_platform_admin_totp_factor SET enabled=true,last_counter=? WHERE admin_id=?",counter,admin.id());
            markTokenVerified(admin.id(),rawToken);
        });
    }

    public void verify(PlatformAdminPrincipal admin,String code,String rawToken){
        tx.executeWithoutResult(transaction->{
            Factor factor=enabledFactor(admin.id());long counter=verify(factor,code);
            jdbc.update("UPDATE auth_platform_admin_totp_factor SET last_counter=? WHERE admin_id=?",counter,admin.id());
            markTokenVerified(admin.id(),rawToken);
        });
    }

    public void disable(PlatformAdminPrincipal admin,String code){
        tx.executeWithoutResult(transaction->{
            Factor factor=enabledFactor(admin.id());
            verify(factor,code);
            jdbc.update("DELETE FROM auth_platform_admin_totp_factor WHERE admin_id=?",admin.id());
            jdbc.update("UPDATE auth_platform_admin_token SET revoked_at=now() WHERE admin_id=? AND revoked_at IS NULL",admin.id());
        });
    }

    private Factor pendingFactor(UUID id){return factor(id,false);}
    private Factor enabledFactor(UUID id){return factor(id,true);}
    private Factor factor(UUID id,boolean enabled){
        var rows=jdbc.query("SELECT secret_ciphertext,last_counter FROM auth_platform_admin_totp_factor WHERE admin_id=? AND enabled=? FOR UPDATE",
                (rs,row)->new Factor(rs.getString(1),rs.getLong(2)),id,enabled);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,enabled?"No active authenticator is configured.":"No pending authenticator setup.");
        return rows.get(0);
    }
    private long verify(Factor factor,String code){
        byte[] secret=HexFormat.of().parseHex(cipher.decrypt(factor.ciphertext()));long counter=verifier.matchedCounter(secret,code,factor.counter());
        if(counter<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"That authenticator code is invalid or has already been used.");
        return counter;
    }
    private void markTokenVerified(UUID id,String rawToken){
        int updated=jdbc.update("UPDATE auth_platform_admin_token SET mfa_verified=true WHERE admin_id=? AND token_sha256=? AND revoked_at IS NULL AND expires_at>now()",
                id,ApiTokenService.hash(rawToken));
        if(updated!=1)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Your platform session expired. Sign in again.");
    }
}
