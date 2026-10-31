package com.apexmedsuite.hrm.payroll;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** M3.2: immutable metadata, explicitly not an executable payroll formula. */
@RestController
@RequestMapping("/api/v1/payroll/components")
public class PayrollComponentController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public PayrollComponentController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record NewComponent(@NotNull UUID companyId,@NotBlank String componentCode,
                               @NotBlank String componentName,@NotBlank String componentKind,
                               @NotNull LocalDate effectiveFrom,String description){}
    public record Component(UUID id,UUID companyId,String componentCode,String componentName,
                            String componentKind,LocalDate effectiveFrom,String treatment,
                            String description,String classification){}
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Component create(@AuthenticationPrincipal HrmUserPrincipal actor,
                            @Valid @RequestBody NewComponent input){
        if(!input.componentCode().matches("[A-Z][A-Z0-9_]{1,39}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Component code must be uppercase and contain 2–40 characters");
        if(input.componentName().trim().length()<3||input.componentName().length()>160)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid component name");
        if(!Set.of("EARNING","DEDUCTION").contains(input.componentKind()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported component classification");
        if(input.description()!=null&&input.description().length()>250)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Description too long");
        return tenants.inTenant(actor.tenantId(),()->{
            Integer companies=jdbc.queryForObject("""
                SELECT count(*) FROM company WHERE tenant_id=? AND id=?
                """,Integer.class,actor.tenantId(),input.companyId());
            if(companies==null||companies!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tenant company unavailable");
            Integer exists=jdbc.queryForObject("""
                SELECT count(*) FROM payroll_component_definition
                WHERE tenant_id=? AND company_id=? AND component_code=? AND effective_from=?
                """,Integer.class,actor.tenantId(),input.companyId(),
                input.componentCode(),input.effectiveFrom());
            if(exists!=null&&exists>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Component version already registered");
            Component component=jdbc.queryForObject("""
                INSERT INTO payroll_component_definition
                  (tenant_id,company_id,component_code,component_name,
                   component_kind,effective_from,description,created_by)
                VALUES(?,?,?,?,?,?,?,?)
                RETURNING id,company_id,component_code,component_name,component_kind,
                          effective_from,treatment,description
                """,(rs,n)->fromRow(rs),actor.tenantId(),input.companyId(),
                input.componentCode(),input.componentName().trim(),input.componentKind(),
                input.effectiveFrom(),input.description(),actor.id());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,?,?,?)
                """,actor.tenantId(),actor.id(),"PAYROLL_COMPONENT_REGISTERED",
                "payroll_component_definition",component.id());
            return component;
        });
    }
    @GetMapping
    public List<Component> list(@AuthenticationPrincipal HrmUserPrincipal actor,
                                @RequestParam UUID companyId){
        return tenants.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,company_id,component_code,component_name,component_kind,
                   effective_from,treatment,description
            FROM payroll_component_definition WHERE tenant_id=? AND company_id=?
            ORDER BY component_code,effective_from DESC LIMIT 200
            """,(rs,n)->fromRow(rs),actor.tenantId(),companyId));
    }
    private static Component fromRow(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new Component(rs.getObject("id",UUID.class),
            rs.getObject("company_id",UUID.class),rs.getString("component_code"),
            rs.getString("component_name"),rs.getString("component_kind"),
            rs.getObject("effective_from",LocalDate.class),rs.getString("treatment"),
            rs.getString("description"),"CATALOGUE_ONLY_NO_PAYROLL_CALCULATION");
    }
}
