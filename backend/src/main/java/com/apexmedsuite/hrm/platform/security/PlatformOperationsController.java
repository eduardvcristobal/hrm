package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/platform-admin")
public class PlatformOperationsController {
    private static final Set<String> FEATURES = Set.of("people", "time-attendance", "payroll", "reports", "workspace-settings");
    private static final Set<String> SUBSCRIPTION_STATUSES = Set.of("TRIALING", "ACTIVE", "PAST_DUE", "SUSPENDED", "CANCELLED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PlatformOperationsController(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    public record Plan(String code, String name, String description, BigDecimal amount, String currency,
            String billingInterval, int trialDays, int expiryNoticeDays, boolean active, boolean priceConfigured, long version,
            long workspaceCount, Instant updatedAt, List<String> featureCodes) {}
    public record PlanInput(@NotBlank @Size(max=32) String code, @NotBlank @Size(min=2,max=80) String name,
            @Size(max=300) String description, @NotNull @DecimalMin("0.00") @Digits(integer=10,fraction=2) BigDecimal amount,
            @NotBlank @Pattern(regexp="[A-Za-z]{3}") String currency,
            @NotBlank @Pattern(regexp="MONTHLY|ANNUAL") String billingInterval,
            @Min(0) @Max(365) int trialDays, @Min(0) @Max(365) int expiryNoticeDays, boolean active, @NotNull Boolean priceConfirmed,
            @NotNull List<@NotBlank String> featureCodes, @NotBlank @Size(min=8,max=500) String reason) {}
    public record Module(String code, String name, String description, boolean enabled, Instant updatedAt,
            String updatedByEmail, String updateReason) {}
    public record ModuleInput(boolean enabled, @NotBlank @Size(min=8,max=500) String reason) {}
    public record SubscriptionInput(@NotBlank String status, @NotBlank String planCode,
            LocalDate trialEndsAt, LocalDate periodStartsAt, LocalDate periodEndsAt,
            @NotBlank @Size(min=8,max=500) String reason, @NotNull Long expectedVersion) {}
    public record PaymentRequest(UUID id, UUID tenantId, String workspaceName, String workspaceSlug,
            String planCode, String planName, BigDecimal amount, String currency, String billingInterval,
            String paymentReference, String note, String status, Instant submittedAt,
            Instant decidedAt, String decidedByEmail, String decisionReason) {}
    public record PaymentDecision(@NotBlank @Pattern(regexp="APPROVE|REJECT") String decision,
            @NotBlank @Size(min=8,max=500) String reason) {}
    public record PaymentQr(String dataUrl, String fileName, Instant updatedAt, String uploadedByEmail) {}
    public record QrUpload(@NotBlank @Size(max=2_800_000) String dataUrl, @NotBlank @Size(max=160) String fileName) {}

    @GetMapping("/plans")
    public List<Plan> plans() {
        return jdbc.query("""
                SELECT p.*, (SELECT count(*) FROM tenant t WHERE t.plan_code=p.code) AS workspace_count
                FROM platform_subscription_plan p ORDER BY p.active DESC,p.name
                """, (rs, row) -> new Plan(rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getBigDecimal("amount"), rs.getString("currency").trim(), rs.getString("billing_interval"),
                rs.getInt("trial_days"), rs.getInt("expiry_notice_days"), rs.getBoolean("active"), rs.getBoolean("price_configured"), rs.getLong("version"),
                rs.getLong("workspace_count"), rs.getTimestamp("updated_at").toInstant(), List.of((String[])rs.getArray("feature_codes").getArray())));
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Plan createPlan(@Valid @RequestBody PlanInput input, @AuthenticationPrincipal PlatformAdminPrincipal admin) {
        String code = normalizeCode(input.code());
        List<String> features = validateFeatures(input.featureCodes());
        String reason = validateReason(input.reason());
        try {
            jdbc.update("""
                    INSERT INTO platform_subscription_plan(code,name,description,amount,currency,billing_interval,trial_days,expiry_notice_days,active,price_configured,feature_codes,updated_by,updated_by_email)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, code, input.name().trim(), clean(input.description()), input.amount(), input.currency().toUpperCase(Locale.ROOT),
                    input.billingInterval(), input.trialDays(), input.expiryNoticeDays(), input.active(), input.priceConfirmed(), features.toArray(String[]::new), admin.id(), admin.email());
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A plan with this code already exists.");
        }
        jdbc.update("INSERT INTO platform_plan_event(plan_code,action,reason,admin_id,admin_email) VALUES(?,'CREATED',?,?,?)",
                code, reason, admin.id(), admin.email());
        return plans().stream().filter(plan -> plan.code().equals(code)).findFirst().orElseThrow();
    }

    @PutMapping("/plans/{code}")
    public Plan updatePlan(@PathVariable String code, @Valid @RequestBody PlanInput input,
            @AuthenticationPrincipal PlatformAdminPrincipal admin) {
        String normalized = normalizeCode(code);
        List<String> features = validateFeatures(input.featureCodes());
        String reason = validateReason(input.reason());
        int count = jdbc.update("""
                UPDATE platform_subscription_plan SET name=?,description=?,amount=?,currency=?,billing_interval=?,trial_days=?,expiry_notice_days=?,active=?,price_configured=?,feature_codes=?,version=version+1,updated_at=now(),updated_by=?,updated_by_email=?
                WHERE code=?
                """, input.name().trim(), clean(input.description()), input.amount(), input.currency().toUpperCase(Locale.ROOT),
                input.billingInterval(), input.trialDays(), input.expiryNoticeDays(), input.active(), input.priceConfirmed(), features.toArray(String[]::new),
                admin.id(), admin.email(), normalized);
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found.");
        jdbc.update("INSERT INTO platform_plan_event(plan_code,action,reason,admin_id,admin_email) VALUES(?,'UPDATED',?,?,?)",
                normalized, reason, admin.id(), admin.email());
        return plans().stream().filter(plan -> plan.code().equals(normalized)).findFirst().orElseThrow();
    }

    @GetMapping("/modules")
    public List<Module> modules() {
        return jdbc.query("SELECT code,name,description,enabled,updated_at,updated_by_email,update_reason FROM platform_module ORDER BY name",
                (rs,row) -> new Module(rs.getString("code"),rs.getString("name"),rs.getString("description"),rs.getBoolean("enabled"),
                        rs.getTimestamp("updated_at").toInstant(),rs.getString("updated_by_email"),rs.getString("update_reason")));
    }

    @PutMapping("/modules/{code}")
    public Module updateModule(@PathVariable String code, @Valid @RequestBody ModuleInput input,
            @AuthenticationPrincipal PlatformAdminPrincipal admin) {
        int changed = jdbc.update("UPDATE platform_module SET enabled=?,updated_at=now(),updated_by=?,updated_by_email=?,update_reason=? WHERE code=?",
                input.enabled(),admin.id(),admin.email(),validateReason(input.reason()),code);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Module not found.");
        return modules().stream().filter(module -> module.code().equals(code)).findFirst().orElseThrow();
    }

    @GetMapping("/payment-qr")
    public PaymentQr getPaymentQr() {
        return jdbc.query("SELECT content_type,file_name,image_data,updated_at,uploaded_by_email FROM platform_subscription_payment_qr WHERE id=1", rs ->
                rs.next() ? new PaymentQr("data:"+rs.getString("content_type")+";base64,"+Base64.getEncoder().encodeToString(rs.getBytes("image_data")),
                        rs.getString("file_name"),rs.getTimestamp("updated_at").toInstant(),rs.getString("uploaded_by_email"))
                        : new PaymentQr(null,null,null,null));
    }

    @PutMapping("/payment-qr")
    public PaymentQr savePaymentQr(@Valid @RequestBody QrUpload input, @AuthenticationPrincipal PlatformAdminPrincipal admin) {
        var match = java.util.regex.Pattern.compile("^data:(image/(?:png|jpeg|webp));base64,([A-Za-z0-9+/]*={0,2})$").matcher(input.dataUrl());
        if (!match.matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a PNG, JPG, or WebP image.");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(match.group(2)); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Image data is invalid."); }
        if (bytes.length == 0 || bytes.length > 2_097_152 || !matchesImage(match.group(1),bytes))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a valid image no larger than 2 MB.");
        String name = input.fileName().replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        jdbc.update("""
                INSERT INTO platform_subscription_payment_qr(id,content_type,file_name,image_data,updated_at,uploaded_by,uploaded_by_email)
                VALUES(1,?,?,?,now(),?,?) ON CONFLICT(id) DO UPDATE SET content_type=excluded.content_type,file_name=excluded.file_name,image_data=excluded.image_data,updated_at=now(),uploaded_by=excluded.uploaded_by,uploaded_by_email=excluded.uploaded_by_email
                """,match.group(1),name.isBlank()?"subscription-payment-qr":name,bytes,admin.id(),admin.email());
        return getPaymentQr();
    }

    @DeleteMapping("/payment-qr")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePaymentQr() { jdbc.update("DELETE FROM platform_subscription_payment_qr WHERE id=1"); }

    @PutMapping("/workspaces/{workspaceId}/subscription")
    public Object updateSubscription(@PathVariable UUID workspaceId, @Valid @RequestBody SubscriptionInput input,
            @AuthenticationPrincipal PlatformAdminPrincipal admin) {
        String status = input.status().toUpperCase(Locale.ROOT);
        String code = normalizeCode(input.planCode());
        String reason = validateReason(input.reason());
        if (!SUBSCRIPTION_STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid subscription status.");
        if (status.equals("TRIALING") && input.trialEndsAt() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Set a trial end date.");
        if (status.equals("ACTIVE") && (input.periodStartsAt() == null || input.periodEndsAt() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Set both billing period dates for an active subscription.");
        if (input.periodStartsAt() != null && input.periodEndsAt() != null && input.periodEndsAt().isBefore(input.periodStartsAt()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The period end date must be on or after the start date.");
        return tx.execute(transaction -> {
            var rows = jdbc.query("SELECT plan_code,subscription_status,subscription_version,trial_ends_at,period_starts_at,period_ends_at FROM tenant WHERE id=? FOR UPDATE",
                    (rs,row) -> new Object[]{rs.getString(1),rs.getString(2),rs.getLong(3),rs.getTimestamp(4),rs.getTimestamp(5),rs.getTimestamp(6)},workspaceId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Workspace not found.");
            Object[] old=rows.get(0); long version=(long)old[2];
            if (version != input.expectedVersion()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Workspace subscription changed. Refresh and try again.");
            if (jdbc.queryForObject("SELECT count(*) FROM platform_subscription_plan WHERE code=? AND active=true",Integer.class,code)==0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an active plan.");
            jdbc.update("UPDATE tenant SET plan_code=?,subscription_status=?,subscription_version=subscription_version+1,status=?,trial_ends_at=?,period_starts_at=?,period_ends_at=? WHERE id=?",
                    code,status,status.equals("SUSPENDED")?"SUSPENDED":status.equals("CANCELLED")?"CLOSED":"ACTIVE",
                    status.equals("TRIALING") ? dateAtUtc(input.trialEndsAt()) : null,
                    dateAtUtc(input.periodStartsAt()),dateAtUtc(input.periodEndsAt()),workspaceId);
            jdbc.update("INSERT INTO platform_subscription_event(tenant_id,from_status,to_status,from_plan_code,to_plan_code,reason,admin_id,admin_email,from_trial_ends_at,to_trial_ends_at,from_period_starts_at,to_period_starts_at,from_period_ends_at,to_period_ends_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    workspaceId,old[1],status,old[0],code,reason,admin.id(),admin.email(),old[3],status.equals("TRIALING") ? dateAtUtc(input.trialEndsAt()) : null,
                    old[4],dateAtUtc(input.periodStartsAt()),old[5],dateAtUtc(input.periodEndsAt()));
            return java.util.Map.of("updated",true);
        });
    }

    @GetMapping("/payment-requests")
    public List<PaymentRequest> paymentRequests() {
        return jdbc.query("""
                SELECT r.id,r.tenant_id,t.display_name,t.slug,r.plan_code,p.name,r.amount,r.currency,r.billing_interval,r.payment_reference,r.note,r.status,r.submitted_at,r.decided_at,r.decided_by_email,r.decision_reason
                FROM platform_payment_request r JOIN tenant t ON t.id=r.tenant_id JOIN platform_subscription_plan p ON p.code=r.plan_code
                ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END,r.submitted_at DESC LIMIT 500
                """, (rs,row) -> new PaymentRequest(rs.getObject("id",UUID.class),rs.getObject("tenant_id",UUID.class),rs.getString("display_name"),rs.getString("slug"),
                rs.getString("plan_code"),rs.getString("name"),rs.getBigDecimal("amount"),rs.getString("currency").trim(),rs.getString("billing_interval"),
                rs.getString("payment_reference"),rs.getString("note"),rs.getString("status"),rs.getTimestamp("submitted_at").toInstant(),
                rs.getTimestamp("decided_at")==null?null:rs.getTimestamp("decided_at").toInstant(),rs.getString("decided_by_email"),rs.getString("decision_reason")));
    }

    @PutMapping("/payment-requests/{requestId}")
    public java.util.Map<String,Boolean> decidePayment(@PathVariable UUID requestId,@Valid @RequestBody PaymentDecision input,
            @AuthenticationPrincipal PlatformAdminPrincipal admin) {
        String reason=validateReason(input.reason());
        return tx.execute(transaction -> {
            var rows=jdbc.query("SELECT r.tenant_id,r.plan_code,r.status,t.plan_code,t.subscription_status FROM platform_payment_request r JOIN tenant t ON t.id=r.tenant_id WHERE r.id=? FOR UPDATE OF r,t",
                    (rs,row)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)},requestId);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment request not found.");
            Object[] row=rows.get(0);
            if(!"PENDING".equals(row[2]))throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment request already reviewed.");
            String decision=input.decision().toUpperCase(Locale.ROOT);
            if("APPROVE".equals(decision)) {
                jdbc.update("UPDATE tenant SET plan_code=?,subscription_status='ACTIVE',status='ACTIVE',period_starts_at=now(),period_ends_at=CASE (SELECT billing_interval FROM platform_subscription_plan WHERE code=?) WHEN 'ANNUAL' THEN now()+interval '1 year' ELSE now()+interval '1 month' END,trial_ends_at=NULL,subscription_version=subscription_version+1 WHERE id=?",
                    row[1],row[1],row[0]);
                jdbc.update("INSERT INTO platform_subscription_event(tenant_id,from_status,to_status,from_plan_code,to_plan_code,reason,admin_id,admin_email) VALUES(?,?,'ACTIVE',?,?,?, ?,?)",
                    row[0],row[4],row[3],row[1],"Payment request approved: "+reason,admin.id(),admin.email());
            }
            jdbc.update("UPDATE platform_payment_request SET status=?,decided_at=now(),decided_by=?,decided_by_email=?,decision_reason=? WHERE id=?",
                    "APPROVE".equals(decision)?"APPROVED":"REJECTED",admin.id(),admin.email(),reason,requestId);
            return java.util.Map.of("updated",true);
        });
    }

    private static String normalizeCode(String value) { String code=value.trim().toUpperCase(Locale.ROOT); if(!code.matches("[A-Z0-9_-]{1,32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Plan code may use letters, numbers, _ and -."); return code; }
    private static String validateReason(String value) { String reason=value==null?"":value.trim(); if(reason.length()<8||reason.length()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter an administrative note of 8 to 500 characters."); return reason; }
    private static String clean(String value) { return value==null?"":value.trim(); }
    private static java.sql.Timestamp dateAtUtc(LocalDate value) { return value == null ? null : java.sql.Timestamp.from(value.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()); }
    private static List<String> validateFeatures(List<String> values) { if(values==null||values.isEmpty()||!FEATURES.containsAll(values))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose one or more supported features."); return values.stream().distinct().sorted().toList(); }
    private static boolean matchesImage(String type,byte[] data) {
        if(type.equals("image/png"))return data.length>=8&&(data[0]&255)==137&&data[1]==80&&data[2]==78&&data[3]==71;
        if(type.equals("image/jpeg"))return data.length>=3&&(data[0]&255)==255&&(data[1]&255)==216&&(data[2]&255)==255;
        return type.equals("image/webp")&&data.length>=12&&data[0]=='R'&&data[1]=='I'&&data[2]=='F'&&data[3]=='F'&&data[8]=='W'&&data[9]=='E'&&data[10]=='B'&&data[11]=='P';
    }
}
