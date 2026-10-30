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
@RequestMapping("/api/v1/companies")
public class CompanyController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public CompanyController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }
    public record Company(UUID id, String companyCode, String legalName, String countryCode, String timezone) {}
    public record CreateCompany(
        @NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String companyCode,
        @NotBlank @Size(max=200) String legalName) {}

    @GetMapping
    public List<Company> list(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(), () -> jdbc.query("""
            SELECT id,company_code,legal_name,country_code,timezone
            FROM company ORDER BY company_code
            """, (rs,i) -> new Company(
                rs.getObject("id", UUID.class),rs.getString("company_code"),
                rs.getString("legal_name"),rs.getString("country_code"),rs.getString("timezone"))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Company create(@AuthenticationPrincipal HrmUserPrincipal user, @Valid @RequestBody CreateCompany input) {
        return tenants.inTenant(user.tenantId(), () -> {
            var result = jdbc.queryForObject("""
                INSERT INTO company(tenant_id,company_code,legal_name)
                VALUES(?,?,?)
                RETURNING id,company_code,legal_name,country_code,timezone
                """, (rs,i) -> new Company(
                    rs.getObject("id",UUID.class),rs.getString("company_code"),
                    rs.getString("legal_name"),rs.getString("country_code"),rs.getString("timezone")),
                user.tenantId(),input.companyCode(),input.legalName().trim());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'COMPANY_CREATED','company',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }
}
