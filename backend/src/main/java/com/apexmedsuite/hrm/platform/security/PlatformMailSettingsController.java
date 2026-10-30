package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/platform-admin/mail-settings")
public class PlatformMailSettingsController {
    private final JdbcTemplate jdbc;
    private final MfaSecretCipher cipher;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public PlatformMailSettingsController(JdbcTemplate jdbc, MfaSecretCipher cipher) { this.jdbc=jdbc; this.cipher=cipher; }
    public record Settings(boolean enabled, String mode, String fromEmail, String fromName, String replyTo,
            String devRecipient, boolean apiKeyConfigured, String apiKeyMasked, String updatedByEmail, Instant updatedAt) {}
    public record Update(@Email String fromEmail, @NotBlank @Size(max=120) String fromName,
            @Email String replyTo, @Email String devRecipient, @NotBlank String mode, boolean enabled,
            @Size(max=512) String apiKey) {}
    public record TestRequest(@NotBlank @Email String recipient) {}

    @GetMapping
    public Settings get() { return settings(); }

    @PutMapping
    public Settings save(@Valid @RequestBody Update input, @AuthenticationPrincipal PlatformAdminPrincipal admin) {
        String mode=input.mode().trim().toUpperCase(Locale.ROOT);
        if(!List.of("DEVELOPMENT","PRODUCTION").contains(mode)) throw bad("Choose Development or Production mode.");
        String from=trim(input.fromEmail()), name=trim(input.fromName()), reply=blankNull(input.replyTo()), dev=blankNull(input.devRecipient());
        if(from.isBlank()||from.length()>254)throw bad("Enter a valid sender email.");
        String existing=jdbc.query("SELECT api_key_ciphertext FROM platform_mail_settings WHERE id=1",rs->rs.next()?rs.getString(1):null);
        String encrypted=existing;
        if(input.apiKey()!=null&&!input.apiKey().isBlank()) {
            try { encrypted=cipher.encrypt(input.apiKey().trim()); }
            catch(IllegalStateException missingKey) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Set HRM_MFA_ENCRYPTION_KEY before saving a Resend API key."); }
        }
        boolean keyConfigured=encrypted!=null&&!encrypted.isBlank();
        if(input.enabled()&&(!keyConfigured||(mode.equals("DEVELOPMENT")&&dev==null)))throw bad("Add a Resend API key and development recipient before enabling platform email.");
        jdbc.update("""
                INSERT INTO platform_mail_settings(id,enabled,mode,from_email,from_name,reply_to,dev_recipient,api_key_ciphertext,updated_by,updated_by_email,updated_at)
                VALUES(1,?,?,?,?,?,?,?,?,?,now()) ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled,mode=excluded.mode,from_email=excluded.from_email,from_name=excluded.from_name,reply_to=excluded.reply_to,dev_recipient=excluded.dev_recipient,api_key_ciphertext=excluded.api_key_ciphertext,updated_by=excluded.updated_by,updated_by_email=excluded.updated_by_email,updated_at=now()
                """,input.enabled(),mode,from,name,reply,dev,encrypted,admin.id(),admin.email());
        return settings();
    }

    @PostMapping("/test")
    public java.util.Map<String,String> test(@Valid @RequestBody TestRequest input) {
        var stored=jdbc.query("SELECT mode,from_email,from_name,reply_to,dev_recipient,api_key_ciphertext FROM platform_mail_settings WHERE id=1",
                rs->rs.next()?new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)}:null);
        if(stored==null||stored[5]==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Save a Resend API key before sending a test.");
        String key;
        try { key=cipher.decrypt(stored[5]); }
        catch(IllegalStateException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The mail encryption key is missing or does not match the key used to save this setting."); }
        String destination="DEVELOPMENT".equals(stored[0])?stored[4]:input.recipient();
        if(destination==null||destination.isBlank())throw bad("Set a development recipient before sending a test.");
        String from=stored[2]+" <"+stored[1]+">";
        String body="{\"from\":\""+escape(from)+"\",\"to\":[\""+escape(destination)+"\"],\"subject\":\"Apex People · test email\",\"text\":\"This is a test email from Apex People platform mail settings. If you received it, the Resend configuration is working.\""+
                (stored[3]==null?"":",\"reply_to\":\""+escape(stored[3])+"\"")+"}";
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.resend.com/emails")).timeout(Duration.ofSeconds(15))
                    .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .header("Idempotency-Key","apex-platform-test-"+java.util.UUID.randomUUID())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Resend rejected the test email (HTTP "+response.statusCode()+").");
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Email request was interrupted."); }
        catch(java.io.IOException error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Could not reach Resend."); }
        return java.util.Map.of("deliveredTo",destination,"mode",stored[0].toLowerCase(Locale.ROOT));
    }

    private Settings settings() {
        var rows=jdbc.query("SELECT enabled,mode,from_email,from_name,reply_to,dev_recipient,api_key_ciphertext,updated_by_email,updated_at FROM platform_mail_settings WHERE id=1",
                (rs,row)->new Object[]{rs.getBoolean(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant()});
        if(rows.isEmpty())return new Settings(false,"DEVELOPMENT","onboarding@resend.dev","Apex People",null,null,false,null,null,null);
        Object[] r=rows.get(0);String encrypted=(String)r[6];String key=encrypted==null?"":cipher.decrypt(encrypted);
        String mask=key.isBlank()?null:key.length()>4?"••••••••"+key.substring(key.length()-4):"••••";
        return new Settings((boolean)r[0],(String)r[1],(String)r[2],(String)r[3],(String)r[4],(String)r[5],!key.isBlank(),mask,(String)r[7],(Instant)r[8]);
    }
    private static String trim(String value){return value==null?"":value.trim();}
    private static String blankNull(String value){String v=trim(value);return v.isBlank()?null:v;}
    private static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
