package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/billing")
public class WorkspaceBillingController {
    private final JdbcTemplate jdbc;

    public WorkspaceBillingController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Plan(String code, String name, String description, BigDecimal amount, String currency,
            String billingInterval, int trialDays, int expiryNoticeDays, List<String> featureCodes) {}
    public record Subscription(String planCode, String planName, BigDecimal amount, String currency,
            String billingInterval, String status, Instant trialEndsAt, Instant periodEndsAt, int expiryNoticeDays,
            String paymentQrDataUrl, String paymentQrFileName, List<Plan> availablePlans,
            PaymentRequest pendingRequest) {}
    public record PaymentRequest(UUID id, String planCode, String planName, BigDecimal amount,
            String currency, String billingInterval, String paymentReference, String note,
            String status, Instant submittedAt) {}
    public record RequestInput(@NotBlank @Size(max=32) String planCode,
            @NotBlank @Size(min=4,max=100) String paymentReference, @Size(max=300) String note) {}

    @GetMapping("/subscription")
    public Subscription subscription(@AuthenticationPrincipal HrmUserPrincipal user) {
        var current = jdbc.query("""
                SELECT t.plan_code,p.name,p.amount,p.currency,p.billing_interval,t.subscription_status,
                       t.trial_ends_at,t.period_ends_at,p.expiry_notice_days,q.content_type,q.file_name,q.image_data
                FROM tenant t JOIN platform_subscription_plan p ON p.code=t.plan_code
                LEFT JOIN platform_subscription_payment_qr q ON q.id=1 WHERE t.id=?
                """, rs -> {
            if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found.");
            String qr = rs.getBytes("image_data") == null ? null : "data:" + rs.getString("content_type") + ";base64," +
                    Base64.getEncoder().encodeToString(rs.getBytes("image_data"));
            return new Object[]{rs.getString("plan_code"),rs.getString("name"),rs.getBigDecimal("amount"),
                    rs.getString("currency").trim(),rs.getString("billing_interval"),rs.getString("subscription_status"),
                    rs.getTimestamp("trial_ends_at")==null?null:rs.getTimestamp("trial_ends_at").toInstant(),
                    rs.getTimestamp("period_ends_at")==null?null:rs.getTimestamp("period_ends_at").toInstant(),rs.getInt("expiry_notice_days"),qr,rs.getString("file_name")};
        }, user.tenantId());
        List<Plan> plans = jdbc.query("SELECT * FROM platform_subscription_plan WHERE active=true ORDER BY amount, name",
                (rs,row)->new Plan(rs.getString("code"),rs.getString("name"),rs.getString("description"),rs.getBigDecimal("amount"),
                        rs.getString("currency").trim(),rs.getString("billing_interval"),rs.getInt("trial_days"),rs.getInt("expiry_notice_days"),
                        List.of((String[])rs.getArray("feature_codes").getArray())));
        List<PaymentRequest> pending = jdbc.query("""
                SELECT r.id,r.plan_code,p.name,r.amount,r.currency,r.billing_interval,r.payment_reference,r.note,r.status,r.submitted_at
                FROM platform_payment_request r JOIN platform_subscription_plan p ON p.code=r.plan_code
                WHERE r.tenant_id=? AND r.status='PENDING' ORDER BY r.submitted_at DESC LIMIT 1
                """, (rs,row)->new PaymentRequest(rs.getObject("id",UUID.class),rs.getString("plan_code"),rs.getString("name"),
                        rs.getBigDecimal("amount"),rs.getString("currency").trim(),rs.getString("billing_interval"),rs.getString("payment_reference"),
                        rs.getString("note"),rs.getString("status"),rs.getTimestamp("submitted_at").toInstant()),user.tenantId());
        Object[] c=(Object[])current;
        return new Subscription((String)c[0],(String)c[1],(BigDecimal)c[2],(String)c[3],(String)c[4],(String)c[5],
                (Instant)c[6],(Instant)c[7],(int)c[8],(String)c[9],(String)c[10],plans,pending.isEmpty()?null:pending.get(0));
    }

    @PostMapping("/payment-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentRequest submit(@AuthenticationPrincipal HrmUserPrincipal user, @Valid @RequestBody RequestInput input) {
        Integer isOwner=jdbc.queryForObject("SELECT count(*) FROM auth_identity_role WHERE identity_id=? AND role='TENANT_ADMIN'",Integer.class,user.id());
        if (isOwner==null || isOwner==0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only workspace administrators can submit subscription payments.");
        var plans=jdbc.query("SELECT code,name,amount,currency,billing_interval FROM platform_subscription_plan WHERE code=? AND active=true AND price_configured=true",
                (rs,row)->new Object[]{rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getString(4).trim(),rs.getString(5)},input.planCode().trim().toUpperCase());
        if(plans.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an available plan with a configured price.");
        Object[] p=plans.get(0); BigDecimal amount=(BigDecimal)p[2];
        if(amount.signum()<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Payment requests are only available for paid plans.");
        try {
            UUID id=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform_payment_request(id,tenant_id,submitted_by,plan_code,amount,currency,billing_interval,payment_reference,note)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """,id,user.tenantId(),user.id(),p[0],amount,p[3],p[4],input.paymentReference().trim(),input.note()==null?"":input.note().trim());
            return jdbc.queryForObject("""
                    SELECT r.id,r.plan_code,? AS plan_name,r.amount,r.currency,r.billing_interval,r.payment_reference,r.note,r.status,r.submitted_at
                    FROM platform_payment_request r WHERE r.id=?
                    """,(rs,row)->new PaymentRequest(rs.getObject("id",UUID.class),rs.getString("plan_code"),rs.getString("plan_name"),
                            rs.getBigDecimal("amount"),rs.getString("currency").trim(),rs.getString("billing_interval"),rs.getString("payment_reference"),
                            rs.getString("note"),rs.getString("status"),rs.getTimestamp("submitted_at").toInstant()),p[1],id);
        } catch(org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A payment reference may only be used once, and each workspace can have one pending request.");
        }
    }
}
