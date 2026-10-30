package com.apexmedsuite.hrm.platform.security;

import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public AuditLogController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public record AuditLog(long id, Instant occurredAt, String actorEmail, String action,
            String entityType, UUID entityId) {}

    @GetMapping
    public List<AuditLog> list(@AuthenticationPrincipal HrmUserPrincipal actor,
            @RequestParam(defaultValue = "250") int limit) {
        int safeLimit = Math.min(500, Math.max(1, limit));
        return tenants.inTenant(actor.tenantId(), () -> jdbc.query("""
                SELECT event.id,event.occurred_at,identity.email AS actor_email,
                       event.action,event.entity_type,event.entity_id
                FROM audit_event event
                LEFT JOIN auth_identity identity
                  ON identity.id=event.actor_id AND identity.tenant_id=event.tenant_id
                WHERE event.tenant_id=?
                ORDER BY event.occurred_at DESC,event.id DESC
                LIMIT ?
                """, (rs, row) -> new AuditLog(rs.getLong("id"),rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor_email"),rs.getString("action"),rs.getString("entity_type"),
                        rs.getObject("entity_id",UUID.class)), actor.tenantId(),safeLimit));
    }
}
