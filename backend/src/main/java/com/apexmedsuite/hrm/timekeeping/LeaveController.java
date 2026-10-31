package com.apexmedsuite.hrm.timekeeping;

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

/**
 * M2.5 leave lifecycle foundation. All results are informational and do not
 * represent earned balances, payroll instructions, or statutory leave awards.
 * SecurityConfig restricts /api/v1/timekeeping/** to MFA-verified tenant admins.
 */
@RestController
@RequestMapping("/api/v1/timekeeping")
public class LeaveController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public LeaveController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record LeaveType(UUID id, UUID companyId, String code, String name, boolean active) {}
    public record NewType(@NotNull UUID companyId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]+") @Size(max=40) String code,
        @NotBlank @Size(max=120) String name) {}
    public record Request(UUID id, UUID employeeId, UUID leaveTypeId, LocalDate from,
        LocalDate to, String status, UUID requestedBy, UUID reviewedBy) {}
    public record NewRequest(@NotNull UUID leaveTypeId, @NotNull LocalDate from,
        @NotNull LocalDate to, @NotBlank @Size(min=8,max=500) String reason) {}

    @GetMapping("/leave-types")
    public List<LeaveType> types(@AuthenticationPrincipal HrmUserPrincipal user,
                                 @RequestParam UUID companyId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,company_id,code,name,active FROM time_leave_type
            WHERE tenant_id=? AND company_id=? ORDER BY code LIMIT 150
            """,(rs,n)->type(rs),user.tenantId(),companyId));
    }
    @PostMapping("/leave-types")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveType createType(@AuthenticationPrincipal HrmUserPrincipal user,
                                @Valid @RequestBody NewType input) {
        return tenants.inTenant(user.tenantId(),()->{
            Integer company=jdbc.queryForObject("""
                SELECT count(*) FROM company WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),input.companyId());
            if(company==null||company!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found");
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_type WHERE tenant_id=? AND company_id=? AND code=?
                """,Integer.class,user.tenantId(),input.companyId(),input.code().trim());
            if(existing!=null&&existing!=0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Leave type code already exists");
            LeaveType result=jdbc.queryForObject("""
                INSERT INTO time_leave_type(tenant_id,company_id,code,name)
                VALUES(?,?,?,?)
                RETURNING id,company_id,code,name,active
                """,(rs,n)->type(rs),user.tenantId(),input.companyId(),
                input.code().trim(),input.name().trim());
            audit(user,"TIME_LEAVE_TYPE_CREATED","time_leave_type",result.id());
            return result;
        });
    }
    @GetMapping("/employees/{employeeId}/leave-requests")
    public List<Request> requests(@AuthenticationPrincipal HrmUserPrincipal user,
                                   @PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->{
            employeeCompany(user.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT id,employee_id,leave_type_id,starts_on,ends_on,status,requested_by,reviewed_by
                FROM time_leave_request
                WHERE tenant_id=? AND employee_id=?
                ORDER BY requested_at DESC LIMIT 200
                """,(rs,n)->requestRow(rs),user.tenantId(),employeeId);
        });
    }
    @PostMapping("/employees/{employeeId}/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Request createRequest(@AuthenticationPrincipal HrmUserPrincipal user,
        @PathVariable UUID employeeId, @Valid @RequestBody NewRequest input) {
        if(input.to().isBefore(input.from()) || input.to().isAfter(input.from().plusDays(365)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid leave date range");
        return tenants.inTenant(user.tenantId(),()->{
            UUID company=employeeCompany(user.tenantId(),employeeId,true);
            Integer type=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_type
                WHERE tenant_id=? AND company_id=? AND id=? AND active=true
                """,Integer.class,user.tenantId(),company,input.leaveTypeId());
            if(type==null||type!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active leave type not found in employee company");
            // Employee row lock serializes all competing leave requests for the
            // same person, preventing concurrent overlapping pending/approved ranges.
            Integer overlap=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_request
                WHERE tenant_id=? AND employee_id=? AND status IN ('PENDING','APPROVED')
                  AND starts_on<=? AND ends_on>=?
                """,Integer.class,user.tenantId(),employeeId,input.to(),input.from());
            if(overlap!=null&&overlap>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Overlapping active leave request");
            Request result=jdbc.queryForObject("""
                INSERT INTO time_leave_request
                  (tenant_id,employee_id,leave_type_id,starts_on,ends_on,reason,requested_by)
                VALUES(?,?,?,?,?,?,?)
                RETURNING id,employee_id,leave_type_id,starts_on,ends_on,status,requested_by,reviewed_by
                """,(rs,n)->requestRow(rs),user.tenantId(),employeeId,
                input.leaveTypeId(),input.from(),input.to(),input.reason().trim(),user.id());
            audit(user,"TIME_LEAVE_REQUESTED","time_leave_request",result.id());
            return result;
        });
    }
    @PostMapping("/employees/{employeeId}/leave-requests/{requestId}/decision")
    public Request decide(@AuthenticationPrincipal HrmUserPrincipal user,
        @PathVariable UUID employeeId,@PathVariable UUID requestId,
        @RequestParam @Pattern(regexp="APPROVED|REJECTED") String decision) {
        return tenants.inTenant(user.tenantId(),()->{
            employeeCompany(user.tenantId(),employeeId,true);
            List<UUID> requesters=jdbc.query("""
                SELECT requested_by FROM time_leave_request
                WHERE tenant_id=? AND employee_id=? AND id=? AND status='PENDING'
                FOR UPDATE
                """,(rs,n)->rs.getObject(1,UUID.class),user.tenantId(),employeeId,requestId);
            if(requesters.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Pending request not found");
            if(requesters.get(0).equals(user.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent reviewer required");
            Request result=jdbc.queryForObject("""
                UPDATE time_leave_request SET status=?,reviewed_by=?,reviewed_at=now()
                WHERE tenant_id=? AND employee_id=? AND id=? AND status='PENDING'
                RETURNING id,employee_id,leave_type_id,starts_on,ends_on,status,requested_by,reviewed_by
                """,(rs,n)->requestRow(rs),decision,user.id(),user.tenantId(),employeeId,requestId);
            audit(user,"TIME_LEAVE_"+decision,"time_leave_request",requestId);
            return result;
        });
    }
    @PostMapping("/employees/{employeeId}/leave-requests/{requestId}/cancel")
    public Request cancel(@AuthenticationPrincipal HrmUserPrincipal user,
        @PathVariable UUID employeeId,@PathVariable UUID requestId) {
        return tenants.inTenant(user.tenantId(),()->{
            employeeCompany(user.tenantId(),employeeId,true);
            List<Request> cancelled=jdbc.query("""
                UPDATE time_leave_request SET status='CANCELLED',cancelled_at=now()
                WHERE tenant_id=? AND employee_id=? AND id=? AND status='PENDING'
                RETURNING id,employee_id,leave_type_id,starts_on,ends_on,status,requested_by,reviewed_by
                """,(rs,n)->requestRow(rs),user.tenantId(),employeeId,requestId);
            if(cancelled.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending leave requests can be cancelled");
            audit(user,"TIME_LEAVE_CANCELLED","time_leave_request",requestId);
            return cancelled.get(0);
        });
    }
    private UUID employeeCompany(UUID tenant,UUID employeeId,boolean lock) {
        List<UUID> companies=jdbc.query("""
            SELECT company_id FROM personnel_employee WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->rs.getObject(1,UUID.class),tenant,employeeId);
        if(companies.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        return companies.get(0);
    }
    private void audit(HrmUserPrincipal user,String action,String entity,UUID id) {
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,?,?)
            """,user.tenantId(),user.id(),action,entity,id);
    }
    private static LeaveType type(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new LeaveType(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getString("code"),rs.getString("name"),rs.getBoolean("active"));
    }
    private static Request requestRow(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Request(rs.getObject("id",UUID.class),rs.getObject("employee_id",UUID.class),
            rs.getObject("leave_type_id",UUID.class),rs.getObject("starts_on",LocalDate.class),
            rs.getObject("ends_on",LocalDate.class),rs.getString("status"),
            rs.getObject("requested_by",UUID.class),rs.getObject("reviewed_by",UUID.class));
    }
}
