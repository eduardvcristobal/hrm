package com.apexmedsuite.hrm.platform.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform-admin")
public class PlatformAdminController {
    private final JdbcTemplate jdbc;
    public PlatformAdminController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Workspace(UUID id, String slug, String name, String status, String subscriptionStatus,
            String planCode, String planName, java.math.BigDecimal planAmount, String planCurrency,
            String billingInterval, boolean planPriceConfigured, Instant trialEndsAt, Instant periodStartsAt,
            Instant periodEndsAt, long version, Instant createdAt,
            long userCount, String ownerEmail) {}
    public record AdminProfile(String email) {}

    @GetMapping("/workspaces")
    public List<Workspace> workspaces(@AuthenticationPrincipal PlatformAdminPrincipal admin) {
        return jdbc.query("""
                SELECT t.id, t.slug, t.display_name, t.status, t.subscription_status, t.plan_code,
                       p.name AS plan_name, p.amount AS plan_amount, p.currency AS plan_currency,
                       p.billing_interval, p.price_configured, t.trial_ends_at, t.period_starts_at,
                       t.period_ends_at, t.subscription_version, t.created_at,
                       count(i.id) AS user_count,
                       (SELECT owner.email
                        FROM auth_identity owner
                        JOIN auth_identity_role owner_role ON owner_role.identity_id=owner.id
                        WHERE owner.tenant_id=t.id AND owner_role.role='TENANT_ADMIN'
                        ORDER BY owner.created_at ASC LIMIT 1) AS owner_email
                FROM tenant t JOIN platform_subscription_plan p ON p.code=t.plan_code
                LEFT JOIN auth_identity i ON i.tenant_id=t.id
                GROUP BY t.id, t.slug, t.display_name, t.status, t.subscription_status, t.plan_code,
                         p.name, p.amount, p.currency, p.billing_interval, p.price_configured, t.trial_ends_at,
                         t.period_starts_at, t.period_ends_at, t.subscription_version, t.created_at
                ORDER BY t.created_at DESC
                """, (rs, row) -> new Workspace(rs.getObject("id", UUID.class), rs.getString("slug"),
                rs.getString("display_name"), rs.getString("status"), rs.getString("subscription_status"),
                rs.getString("plan_code"), rs.getString("plan_name"), rs.getBigDecimal("plan_amount"),
                rs.getString("plan_currency").trim(), rs.getString("billing_interval"), rs.getBoolean("price_configured"),
                rs.getTimestamp("trial_ends_at") == null ? null : rs.getTimestamp("trial_ends_at").toInstant(),
                rs.getTimestamp("period_starts_at") == null ? null : rs.getTimestamp("period_starts_at").toInstant(),
                rs.getTimestamp("period_ends_at") == null ? null : rs.getTimestamp("period_ends_at").toInstant(),
                rs.getLong("subscription_version"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("user_count"), rs.getString("owner_email")));
    }

    @GetMapping("/profile")
    public AdminProfile profile(@AuthenticationPrincipal PlatformAdminPrincipal admin) {
        return new AdminProfile(admin.email());
    }
}
