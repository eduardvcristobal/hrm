package com.apexmedsuite.hrm.platform.company;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/branches")
public class BranchController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public BranchController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }
    public record Branch(UUID id, UUID companyId, String branchCode, String branchName, String regionCode) {}
    public record CreateBranch(
        @NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String branchCode,
        @NotBlank @Size(max=200) String branchName,
        @Size(max=40) String regionCode) {}

    @GetMapping
    public List<Branch> list(@PathVariable UUID companyId, @AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(), () -> jdbc.query("""
            SELECT id,company_id,branch_code,branch_name,region_code
            FROM branch WHERE tenant_id = ? AND company_id = ? ORDER BY branch_code
            """, (rs,i) -> new Branch(rs.getObject("id",UUID.class),
                rs.getObject("company_id",UUID.class),rs.getString("branch_code"),
                rs.getString("branch_name"),rs.getString("region_code")),
            user.tenantId(),companyId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Branch create(@PathVariable UUID companyId,
            @AuthenticationPrincipal HrmUserPrincipal user,
            @Valid @RequestBody CreateBranch input) {
        return tenants.inTenant(user.tenantId(), () -> {
            // Explicit ownership check before inserting: never rely solely on an FK error.
            Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM company WHERE id = ? AND tenant_id = ?
                """, Integer.class,companyId,user.tenantId());
            if (found == null || found == 0)
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
            Branch b = jdbc.queryForObject("""
                INSERT INTO branch(tenant_id,company_id,branch_code,branch_name,region_code)
                VALUES(?,?,?,?,?)
                RETURNING id,company_id,branch_code,branch_name,region_code
                """, (rs,i) -> new Branch(rs.getObject("id",UUID.class),
                    rs.getObject("company_id",UUID.class),rs.getString("branch_code"),
                    rs.getString("branch_name"),rs.getString("region_code")),
                user.tenantId(),companyId,input.branchCode(),input.branchName().trim(),input.regionCode());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'BRANCH_CREATED','branch',?)
                """,user.tenantId(),user.id(),b.id());
            return b;
        });
    }
}
