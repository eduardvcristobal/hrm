package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2.5.2: explicit manual leave consumption with one immutable debit per
 * approved request. This does NOT derive paid/working days, statutory
 * eligibility or payroll. Reversal produces a separate compensating event.
 */
@RestController
@RequestMapping("/api/v1/timekeeping/employees/{employeeId}/leave-balances")
public class LeaveBalanceController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public LeaveBalanceController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record Posting(@NotNull @DecimalMin("0.001") @DecimalMax("366.000")
                          @Digits(integer=3,fraction=3) BigDecimal days,
                          @NotBlank @Size(min=8,max=500) String note) {}
    public record Reversal(@NotBlank @Size(min=8,max=500) String note) {}
    public record Event(UUID id,UUID employeeId,UUID leaveTypeId,UUID leaveRequestId,
                        String eventType,BigDecimal days,UUID reversesEventId) {}
    public record Balance(UUID leaveTypeId,BigDecimal accruedDays,BigDecimal consumedDays,
                          BigDecimal remainingDays,boolean payable,boolean policyCertified,
                          String classification) {}
    private record Leave(UUID employeeId,UUID typeId,String status,LocalDate from,LocalDate to) {}

    @GetMapping
    public List<Balance> balance(@AuthenticationPrincipal HrmUserPrincipal actor,
                                  @PathVariable UUID employeeId){
        return tenants.inTenant(actor.tenantId(),()->{
            assertEmployee(actor.tenantId(),employeeId,false);
            return jdbc.query("""
                WITH credit AS (
                  SELECT leave_type_id,sum(credit_days) amount FROM time_leave_credit_entry
                  WHERE tenant_id=? AND employee_id=? GROUP BY leave_type_id
                ), movement AS (
                  SELECT leave_type_id,sum(CASE WHEN event_type='DEBIT' THEN days ELSE -days END) amount
                  FROM time_leave_balance_event WHERE tenant_id=? AND employee_id=?
                  GROUP BY leave_type_id
                )
                SELECT c.leave_type_id,c.amount AS accrued,coalesce(m.amount,0) consumed
                FROM credit c LEFT JOIN movement m ON m.leave_type_id=c.leave_type_id
                ORDER BY c.leave_type_id
                """,(rs,n)->{
                    BigDecimal accrued=rs.getBigDecimal("accrued");
                    BigDecimal consumed=rs.getBigDecimal("consumed");
                    return new Balance(rs.getObject("leave_type_id",UUID.class),accrued,consumed,
                      accrued.subtract(consumed),false,false,
                      "MANUALLY_RECONCILED_NOT_LEGAL_OR_PAYROLL_CERTIFIED");
                },actor.tenantId(),employeeId,actor.tenantId(),employeeId);
        });
    }
    @GetMapping("/events")
    public List<Event> events(@AuthenticationPrincipal HrmUserPrincipal actor,
                              @PathVariable UUID employeeId,@RequestParam UUID leaveTypeId){
        return tenants.inTenant(actor.tenantId(),()->{
            assertEmployee(actor.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT id,employee_id,leave_type_id,leave_request_id,event_type,days,reverses_event_id
                FROM time_leave_balance_event
                WHERE tenant_id=? AND employee_id=? AND leave_type_id=?
                ORDER BY posted_at DESC,id DESC LIMIT 250
                """,(rs,n)->row(rs),actor.tenantId(),employeeId,leaveTypeId);
        });
    }
    @PostMapping("/requests/{requestId}/consume")
    @ResponseStatus(HttpStatus.CREATED)
    public Event consume(@AuthenticationPrincipal HrmUserPrincipal actor,
                         @PathVariable UUID employeeId,@PathVariable UUID requestId,
                         @Valid @RequestBody Posting posting){
        return tenants.inTenant(actor.tenantId(),()->{
            assertEmployee(actor.tenantId(),employeeId,true);
            Leave leave=request(actor.tenantId(),employeeId,requestId);
            if(!"APPROVED".equals(leave.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Leave request must be approved before consumption");
            // Calendar span is an upper bound only; HR confirms actual chargeable
            // days after reviewing shifts, holidays and relevant legal policy.
            long span=ChronoUnit.DAYS.between(leave.from(),leave.to())+1;
            if(posting.days().compareTo(BigDecimal.valueOf(span))>0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Debit exceeds request calendar span");
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_balance_event
                WHERE tenant_id=? AND leave_request_id=? AND event_type='DEBIT'
                """,Integer.class,actor.tenantId(),requestId);
            if(existing!=null&&existing>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Leave already consumed or reversed");
            BigDecimal accrued=jdbc.queryForObject("""
                SELECT coalesce(sum(credit_days),0) FROM time_leave_credit_entry
                WHERE tenant_id=? AND employee_id=? AND leave_type_id=?
                """,BigDecimal.class,actor.tenantId(),employeeId,leave.typeId());
            BigDecimal used=jdbc.queryForObject("""
                SELECT coalesce(sum(CASE WHEN event_type='DEBIT' THEN days ELSE -days END),0)
                FROM time_leave_balance_event
                WHERE tenant_id=? AND employee_id=? AND leave_type_id=?
                """,BigDecimal.class,actor.tenantId(),employeeId,leave.typeId());
            if(accrued==null||used==null||accrued.subtract(used).compareTo(posting.days())<0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Insufficient informational leave credits");
            Event event=jdbc.queryForObject("""
                INSERT INTO time_leave_balance_event
                 (tenant_id,employee_id,leave_type_id,leave_request_id,event_type,days,note,posted_by)
                VALUES(?,?,?,?,'DEBIT',?,?,?)
                RETURNING id,employee_id,leave_type_id,leave_request_id,event_type,days,reverses_event_id
                """,(rs,n)->row(rs),actor.tenantId(),employeeId,leave.typeId(),
                requestId,posting.days(),posting.note().trim(),actor.id());
            audit(actor,"TIME_LEAVE_BALANCE_DEBIT",event.id());
            return event;
        });
    }
    @PostMapping("/events/{eventId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public Event reverse(@AuthenticationPrincipal HrmUserPrincipal actor,
                         @PathVariable UUID employeeId,@PathVariable UUID eventId,
                         @Valid @RequestBody Reversal input){
        return tenants.inTenant(actor.tenantId(),()->{
            assertEmployee(actor.tenantId(),employeeId,true);
            List<Event> original=jdbc.query("""
                SELECT id,employee_id,leave_type_id,leave_request_id,event_type,days,reverses_event_id
                FROM time_leave_balance_event
                WHERE tenant_id=? AND employee_id=? AND id=? AND event_type='DEBIT'
                FOR UPDATE
                """,(rs,n)->row(rs),actor.tenantId(),employeeId,eventId);
            if(original.isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Debit event not found");
            Event debit=original.get(0);
            Integer reversed=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_balance_event
                WHERE tenant_id=? AND reverses_event_id=?
                """,Integer.class,actor.tenantId(),eventId);
            if(reversed!=null&&reversed>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Debit already reversed");
            Event result=jdbc.queryForObject("""
                INSERT INTO time_leave_balance_event
                 (tenant_id,employee_id,leave_type_id,leave_request_id,event_type,days,reverses_event_id,note,posted_by)
                VALUES(?,?,?,?,'REVERSAL',?,?,?,?)
                RETURNING id,employee_id,leave_type_id,leave_request_id,event_type,days,reverses_event_id
                """,(rs,n)->row(rs),actor.tenantId(),employeeId,debit.leaveTypeId(),
                debit.leaveRequestId(),debit.days(),debit.id(),input.note().trim(),actor.id());
            audit(actor,"TIME_LEAVE_BALANCE_REVERSAL",result.id());
            return result;
        });
    }
    private Leave request(UUID tenant,UUID employee,UUID id){
        List<Leave> rows=jdbc.query("""
            SELECT employee_id,leave_type_id,status,starts_on,ends_on FROM time_leave_request
            WHERE tenant_id=? AND employee_id=? AND id=? FOR UPDATE
            """,(rs,n)->new Leave(rs.getObject("employee_id",UUID.class),
                rs.getObject("leave_type_id",UUID.class),rs.getString("status"),
                rs.getObject("starts_on",LocalDate.class),rs.getObject("ends_on",LocalDate.class)),
                tenant,employee,id);
        if(rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Leave request not found");
        return rows.get(0);
    }
    private void assertEmployee(UUID tenant,UUID employee,boolean lock){
        List<UUID> rows=jdbc.query("""
            SELECT id FROM personnel_employee WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->rs.getObject(1,UUID.class),tenant,employee);
        if(rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
    }
    private static Event row(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new Event(rs.getObject("id",UUID.class),rs.getObject("employee_id",UUID.class),
            rs.getObject("leave_type_id",UUID.class),rs.getObject("leave_request_id",UUID.class),
            rs.getString("event_type"),rs.getBigDecimal("days"),
            rs.getObject("reverses_event_id",UUID.class));
    }
    private void audit(HrmUserPrincipal actor,String action,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'time_leave_balance_event',?)
            """,actor.tenantId(),actor.id(),action,id);
    }
}
