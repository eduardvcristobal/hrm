package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/personnel/positions")
public class PositionController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public PositionController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Position(UUID id,UUID companyId,String code,String name,boolean active) {}
    public record NewPosition(@NotNull UUID companyId,
       @NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String code,
       @NotBlank @Size(max=160) String name) {}
    @GetMapping
    public List<Position> list(@AuthenticationPrincipal HrmUserPrincipal user,@RequestParam UUID companyId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
           SELECT id,company_id,position_code,position_name,active
           FROM personnel_position WHERE tenant_id=? AND company_id=?
           ORDER BY position_code LIMIT 200
           """,(rs,n)->row(rs),user.tenantId(),companyId));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Position create(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody NewPosition input) {
        return tenants.inTenant(user.tenantId(),()->{
            Integer found=jdbc.queryForObject("SELECT count(*) FROM company WHERE tenant_id=? AND id=?",
                Integer.class,user.tenantId(),input.companyId());
            if(found==null||found==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found");
            Position position=jdbc.queryForObject("""
                INSERT INTO personnel_position(tenant_id,company_id,position_code,position_name)
                VALUES(?,?,?,?)
                RETURNING id,company_id,position_code,position_name,active
                """,(rs,n)->row(rs),user.tenantId(),input.companyId(),input.code(),input.name().trim());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'POSITION_CREATED','personnel_position',?)
                """,user.tenantId(),user.id(),position.id());
            return position;
        });
    }
    private static Position row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Position(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getString("position_code"),rs.getString("position_name"),rs.getBoolean("active"));
    }
}
