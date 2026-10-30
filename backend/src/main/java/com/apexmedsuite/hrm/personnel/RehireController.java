package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Requires separate MFA-verified tenant administrator identities for request and approval. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/rehire-requests")
public class RehireController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public RehireController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record RehireRequest(UUID id, LocalDate effectiveDate, String employmentType,
                                String status, UUID requestedBy, UUID approvedBy) {}
    public record NewRehire(@NotNull @PastOrPresent LocalDate effectiveDate,
      @NotBlank @Pattern(regexp="REGULAR|PROBATIONARY|CONTRACTUAL|PROJECT|SEASONAL|CASUAL|PART_TIME") String employmentType,
      @NotBlank @Size(max=250) String reason) {}
    @GetMapping
    public List<RehireRequest> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,effective_date,employment_type,status,requested_by,approved_by
            FROM personnel_rehire_request WHERE tenant_id=? AND employee_id=?
            ORDER BY requested_at DESC LIMIT 100
            """,(rs,i)->row(rs),user.tenantId(),employeeId));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RehireRequest request(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                                 @Valid @RequestBody NewRehire input) {
        return tenants.inTenant(user.tenantId(),()->{
            List<String> statuses=jdbc.query("""
                SELECT employment_status FROM personnel_employee WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->rs.getString(1),user.tenantId(),employeeId);
            if(statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if(!statuses.get(0).equals("TERMINATED"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Only terminated employees may request rehire");
            LocalDate latest=jdbc.queryForObject("""
                SELECT max(effective_date) FROM personnel_employment_history WHERE tenant_id=? AND employee_id=?
                """,LocalDate.class,user.tenantId(),employeeId);
            if(latest!=null&&!input.effectiveDate().isAfter(latest))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Rehire effective date must follow separation");
            RehireRequest result=jdbc.queryForObject("""
                INSERT INTO personnel_rehire_request
                  (tenant_id,employee_id,effective_date,employment_type,reason,requested_by)
                VALUES(?,?,?,?,?,?)
                RETURNING id,effective_date,employment_type,status,requested_by,approved_by
                """,(rs,i)->row(rs),user.tenantId(),employeeId,input.effectiveDate(),
                input.employmentType(),input.reason().trim(),user.id());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'REHIRE_REQUESTED','personnel_rehire_request',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }
    @PostMapping("/{requestId}/approve")
    public RehireRequest approve(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                                 @PathVariable UUID requestId) {
        return tenants.inTenant(user.tenantId(),()->{
            List<String> statuses=jdbc.query("""
                SELECT employment_status FROM personnel_employee WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->rs.getString(1),user.tenantId(),employeeId);
            if(statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if(!statuses.get(0).equals("TERMINATED"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Employee is not separated");
            List<Pending> pending=jdbc.query("""
                SELECT effective_date,employment_type,reason,requested_by
                FROM personnel_rehire_request WHERE tenant_id=? AND employee_id=? AND id=? AND status='PENDING'
                FOR UPDATE
                """,(rs,i)->new Pending(rs.getObject(1,LocalDate.class),rs.getString(2),
                    rs.getString(3),rs.getObject(4,UUID.class)),user.tenantId(),employeeId,requestId);
            if(pending.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pending request not found");
            Pending request=pending.get(0);
            if(request.requestedBy().equals(user.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent approval required");
            LocalDate latest=jdbc.queryForObject("""
                SELECT max(effective_date) FROM personnel_employment_history WHERE tenant_id=? AND employee_id=?
                """,LocalDate.class,user.tenantId(),employeeId);
            if(latest!=null&&!request.effectiveDate().isAfter(latest))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Stale rehire effective date");
            jdbc.update("""
                UPDATE personnel_employee SET employment_status='ACTIVE',employment_type=?,updated_at=now()
                WHERE tenant_id=? AND id=?
                """,request.employmentType(),user.tenantId(),employeeId);
            jdbc.update("""
                INSERT INTO personnel_employment_history
                (tenant_id,employee_id,effective_date,employment_type,employment_status,reason)
                VALUES(?,?,?,?,'ACTIVE',?)
                """,user.tenantId(),employeeId,request.effectiveDate(),
                    request.employmentType(),"APPROVED_REHIRE: "+request.reason());
            RehireRequest result=jdbc.queryForObject("""
                UPDATE personnel_rehire_request SET status='APPROVED',approved_by=?,approved_at=now()
                WHERE tenant_id=? AND id=? AND employee_id=?
                RETURNING id,effective_date,employment_type,status,requested_by,approved_by
                """,(rs,i)->row(rs),user.id(),user.tenantId(),requestId,employeeId);
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'REHIRE_APPROVED','personnel_rehire_request',?)
                """,user.tenantId(),user.id(),requestId);
            return result;
        });
    }
    @PostMapping("/{requestId}/reject")
    public RehireRequest reject(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                                @PathVariable UUID requestId) {
        return tenants.inTenant(user.tenantId(),()->{
            jdbc.query("SELECT employment_status FROM personnel_employee WHERE tenant_id=? AND id=? FOR UPDATE",
                (rs,i)->rs.getString(1),user.tenantId(),employeeId);
            List<UUID> requesters=jdbc.query("""
                SELECT requested_by FROM personnel_rehire_request
                WHERE tenant_id=? AND employee_id=? AND id=? AND status='PENDING' FOR UPDATE
                """,(rs,i)->rs.getObject(1,UUID.class),user.tenantId(),employeeId,requestId);
            if(requesters.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pending request not found");
            if(requesters.get(0).equals(user.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent rejection required");
            RehireRequest result=jdbc.queryForObject("""
                UPDATE personnel_rehire_request SET status='REJECTED',approved_by=?,approved_at=now()
                WHERE tenant_id=? AND employee_id=? AND id=?
                RETURNING id,effective_date,employment_type,status,requested_by,approved_by
                """,(rs,i)->row(rs),user.id(),user.tenantId(),employeeId,requestId);
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'REHIRE_REJECTED','personnel_rehire_request',?)
                """,user.tenantId(),user.id(),requestId);
            return result;
        });
    }
    private record Pending(LocalDate effectiveDate,String employmentType,String reason,UUID requestedBy) {}
    private static RehireRequest row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RehireRequest(rs.getObject("id",UUID.class),
            rs.getObject("effective_date",LocalDate.class),rs.getString("employment_type"),
            rs.getString("status"),rs.getObject("requested_by",UUID.class),
            rs.getObject("approved_by",UUID.class));
    }
}
