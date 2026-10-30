package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security/password-reset")
public class PasswordRecoveryController {
    private final JdbcTemplate jdbc;
    private final ApiTokenService tokens;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords;
    private final ObjectProvider<JavaMailSender> mail;
    private static final SecureRandom RANDOM = new SecureRandom();
    @Value("${hrm.invitation.from:}") private String from;
    @Value("${hrm.password-reset.url:}") private String resetUrl;
    public PasswordRecoveryController(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,
            PasswordEncoder passwords,ObjectProvider<JavaMailSender> mail,ApiTokenService tokens) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.passwords=passwords;this.mail=mail;this.tokens=tokens;
    }
    public record Request(@NotBlank @Email @Size(max=254) String email) {}
    public record Confirm(@NotBlank String token,@NotBlank @Size(min=12,max=128) String password) {}
    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(@Valid @RequestBody Request input) {
        String email=input.email().trim().toLowerCase(Locale.ROOT);
        // Respond identically whether the account exists or not, without disclosing account status.
        var identities=jdbc.query("SELECT id FROM auth_identity WHERE email=? AND enabled",
            (rs,i)->rs.getObject("id",UUID.class),email);
        if(identities.isEmpty()) return;
        JavaMailSender sender=mail.getIfAvailable();
        if(sender==null || from.isBlank() || resetUrl.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Password recovery unavailable");
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
        String token=HexFormat.of().formatHex(bytes);
        var message=new SimpleMailMessage();
        message.setFrom(from);message.setTo(email);message.setSubject("HRM password reset");
        message.setText("Your reset link expires in 30 minutes: "+resetUrl
            +(resetUrl.contains("?")?"&":"?")+"token="+token);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO auth_password_reset(identity_id,token_sha256,expires_at)
                VALUES (?,?,?)
                """,identities.get(0),hash(token),java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
            try { sender.send(message); }
            catch(MailException ex) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Password recovery unavailable");
            }
        });
    }
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody Confirm input) {
        tx.executeWithoutResult(status -> {
            var rows=jdbc.query("""
                SELECT id,identity_id FROM auth_password_reset
                WHERE token_sha256=? AND used_at IS NULL AND expires_at>now() FOR UPDATE
                """,(rs,i)->new Reset(rs.getObject("id",UUID.class),rs.getObject("identity_id",UUID.class)),
                hash(input.token()));
            if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired token");
            var row=rows.get(0);
            jdbc.update("UPDATE auth_identity SET password_hash=? WHERE id=?",
                passwords.encode(input.password()),row.identityId());
            tokens.revokeAll(row.identityId());
            jdbc.update("UPDATE auth_password_reset SET used_at=now() WHERE id=?",row.id());
            jdbc.update("UPDATE auth_password_reset SET used_at=now() WHERE identity_id=? AND used_at IS NULL",
                row.identityId());
        });
    }
    private record Reset(UUID id,UUID identityId) {}
}
