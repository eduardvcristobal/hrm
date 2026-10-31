package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Year;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2.5.2 carryover planning. This controller NEVER posts, expires, or
 * transfers leave. Preview represents a current snapshot, not a year-end
 * reconciliation or a statutory entitlement award.
 */
@RestController
@RequestMapping("/api/v1/timekeeping")
public class LeaveCarryoverController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public LeaveCarryoverController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Config(@NotNull UUID leaveTypeId,@Min(2000) @Max(2200) int destinationYear,
                         @NotNull @DecimalMin("0.000") @DecimalMax("366.000")
                         @Digits(integer=3,fraction=3) BigDecimal maximumDays) {}
    public record Policy(UUID id,UUID leaveTypeId,int destinationYear,BigDecimal maximumDays){}
    public record Preview(UUID employeeId,UUID leaveTypeId,int destinationYear,
                          BigDecimal currentUnsettledCredits,BigDecimal policyCap,
                          BigDecimal proposedCarryoverDays,boolean posted,
                          String classification) {}
    @PostMapping("/leave-carryover-preview-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public Policy configure(@AuthenticationPrincipal HrmUserPrincipal actor,
                            @Valid @RequestBody Config config){
        if(config.destinationYear()<=Year.now(java.time.ZoneOffset.UTC).getValue())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Destination must be a future year");
        return tenants.inTenant(actor.tenantId(),()->{
            Integer types=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_type WHERE tenant_id=? AND id=? AND active=true
                """,Integer.class,actor.tenantId(),config.leaveTypeId());
            if(types==null||types!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Leave type not found");
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_carryover_preview_policy
                WHERE tenant_id=? AND leave_type_id=? AND destination_year=?
                """,Integer.class,actor.tenantId(),config.leaveTypeId(),config.destinationYear());
            if(existing!=null&&existing!=0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Carryover preview policy already exists");
            Policy policy=jdbc.queryForObject("""
                INSERT INTO time_leave_carryover_preview_policy
                 (tenant_id,leave_type_id,destination_year,maximum_days,configured_by)
                VALUES(?,?,?,?,?)
                RETURNING id,leave_type_id,destination_year,maximum_days
                """,(rs,n)->new Policy(rs.getObject("id",UUID.class),
                    rs.getObject("leave_type_id",UUID.class),rs.getInt("destination_year"),
                    rs.getBigDecimal("maximum_days")),
                    actor.tenantId(),config.leaveTypeId(),config.destinationYear(),
                    config.maximumDays(),actor.id());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'TIME_LEAVE_CARRYOVER_PREVIEW_POLICY_CREATED','time_leave_carryover_preview_policy',?)
                """,actor.tenantId(),actor.id(),policy.id());
            return policy;
        });
    }
    @GetMapping("/employees/{employeeId}/leave-carryover-preview")
    public Preview preview(@AuthenticationPrincipal HrmUserPrincipal actor,
                           @PathVariable UUID employeeId,@RequestParam UUID leaveTypeId,
                           @RequestParam int destinationYear){
        if(destinationYear<=Year.now(java.time.ZoneOffset.UTC).getValue())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only future-year planning is supported");
        return tenants.inTenant(actor.tenantId(),()->{
            List<UUID> employees=jdbc.query("""
                SELECT company_id FROM personnel_employee WHERE tenant_id=? AND id=?
                """,(rs,n)->rs.getObject(1,UUID.class),actor.tenantId(),employeeId);
            if(employees.isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            Integer matching=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_type WHERE tenant_id=? AND id=? AND company_id=?
                """,Integer.class,actor.tenantId(),leaveTypeId,employees.get(0));
            if(matching==null||matching!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Leave type not available for employee company");
            List<BigDecimal> caps=jdbc.query("""
                SELECT maximum_days FROM time_leave_carryover_preview_policy
                WHERE tenant_id=? AND leave_type_id=? AND destination_year=?
                """,(rs,n)->rs.getBigDecimal(1),actor.tenantId(),leaveTypeId,destinationYear);
            if(caps.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Configure carryover preview policy first");
            BigDecimal earned=jdbc.queryForObject("""
                SELECT coalesce(sum(credit_days),0) FROM time_leave_credit_entry
                WHERE tenant_id=? AND employee_id=? AND leave_type_id=?
                """,BigDecimal.class,actor.tenantId(),employeeId,leaveTypeId);
            BigDecimal consumed=jdbc.queryForObject("""
                SELECT coalesce(sum(CASE WHEN event_type='DEBIT' THEN days ELSE -days END),0)
                FROM time_leave_balance_event
                WHERE tenant_id=? AND employee_id=? AND leave_type_id=?
                """,BigDecimal.class,actor.tenantId(),employeeId,leaveTypeId);
            BigDecimal unsettled=earned.subtract(consumed).max(BigDecimal.ZERO);
            return new Preview(employeeId,leaveTypeId,destinationYear,unsettled,caps.get(0),
                unsettled.min(caps.get(0)),false,
                "CURRENT_SNAPSHOT_ONLY_NOT_POSTED_OR_LEGALLY_CERTIFIED");
        });
    }
}
