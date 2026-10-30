package com.apexmedsuite.hrm.platform.security;

import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceAuditService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public WorkspaceAuditService(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public void record(UUID tenantId, UUID actorId, String action, String entityType, UUID entityId) {
        tenants.inTenant(tenantId, () -> jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES (?,?,?,?,?)
                """, tenantId, actorId, action, entityType, entityId));
    }
}
