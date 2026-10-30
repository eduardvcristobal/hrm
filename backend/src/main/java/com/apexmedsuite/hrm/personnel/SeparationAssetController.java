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
@RequestMapping("/api/v1/personnel/employees/{employeeId}/hr-cases/{caseId}/assets")
public class SeparationAssetController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public SeparationAssetController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Asset(UUID id,String assetTag,String description,String status) {}
    public record NewAsset(@NotBlank @Size(max=80) String assetTag,@NotBlank @Size(max=200) String description) {}
    public record Resolve(@NotBlank @Pattern(regexp="RETURNED|EXCEPTION") String status) {}

    @GetMapping
    public List<Asset> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,@PathVariable UUID caseId) {
        return tenants.inTenant(user.tenantId(),()->{
            checkCase(user.tenantId(),employeeId,caseId,false);
            return jdbc.query("""
                SELECT id,asset_tag,description,status FROM personnel_separation_asset
                WHERE tenant_id=? AND hr_case_id=? ORDER BY asset_tag LIMIT 200
                """,(rs,i)->map(rs),user.tenantId(),caseId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Asset add(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,@PathVariable UUID caseId,
                     @Valid @RequestBody NewAsset input) {
        return tenants.inTenant(user.tenantId(),()->{
            checkCase(user.tenantId(),employeeId,caseId,true);
            Asset result=jdbc.queryForObject("""
                INSERT INTO personnel_separation_asset(tenant_id,hr_case_id,asset_tag,description)
                VALUES(?,?,?,?) RETURNING id,asset_tag,description,status
                """,(rs,i)->map(rs),user.tenantId(),caseId,input.assetTag().trim(),input.description().trim());
            audit(user,"SEPARATION_ASSET_RECORDED",result.id());
            return result;
        });
    }
    @PatchMapping("/{assetId}")
    public Asset resolve(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,@PathVariable UUID caseId,
                         @PathVariable UUID assetId,@Valid @RequestBody Resolve input) {
        return tenants.inTenant(user.tenantId(),()->{
            checkCase(user.tenantId(),employeeId,caseId,true);
            List<Asset> results=jdbc.query("""
                UPDATE personnel_separation_asset SET status=?,resolved_by=?,resolved_at=now()
                WHERE tenant_id=? AND hr_case_id=? AND id=? AND status='OUTSTANDING'
                RETURNING id,asset_tag,description,status
                """,(rs,i)->map(rs),input.status(),user.id(),user.tenantId(),caseId,assetId);
            if(results.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Asset missing or already resolved");
            audit(user,"SEPARATION_ASSET_"+input.status(),assetId);
            return results.get(0);
        });
    }
    private void checkCase(UUID tenant,UUID employee,UUID id,boolean pending) {
        Integer n=jdbc.queryForObject("""
            SELECT count(*) FROM personnel_hr_case WHERE tenant_id=? AND employee_id=? AND id=?
            AND case_type='SEPARATION'
            """+(pending?" AND status='PENDING'":""),Integer.class,tenant,employee,id);
        if(n==null||n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pending separation case not found");
    }
    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'personnel_separation_asset',?)
            """,user.tenantId(),user.id(),action,id);
    }
    private static Asset map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Asset(rs.getObject("id",UUID.class),rs.getString("asset_tag"),rs.getString("description"),rs.getString("status"));
    }
}
