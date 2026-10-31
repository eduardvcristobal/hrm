package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2.5.1 controlled employer-configured monthly leave credits.
 * These are NOT spendable leave balances, leave debit calculations or statutory awards.
 * SecurityConfig requires MFA tenant-admin authorization for all timekeeping routes.
 */
@RestController
@RequestMapping("/api/v1/timekeeping")
public class LeaveCreditController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public LeaveCreditController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Policy(UUID id,UUID leaveTypeId,BigDecimal monthlyCreditDays,
                         LocalDate effectiveFrom,boolean active) {}
    public record Configure(@NotNull UUID leaveTypeId,
                            @NotNull @DecimalMin("0.000") @DecimalMax("31.000")
                            @Digits(integer=2,fraction=3) BigDecimal monthlyCreditDays,
                            @NotNull LocalDate effectiveFrom) {}
    public record Credit(UUID id,UUID employeeId,UUID leaveTypeId,UUID policyId,
                         LocalDate periodStart,BigDecimal creditDays) {}
    public record CreditBalance(UUID employeeId,UUID leaveTypeId,BigDecimal accruedCreditDays,
                                boolean spendable, String classification) {}
    private record EmployeeCompany(UUID company,LocalDate hired) {}

    @GetMapping("/leave-credit-policies")
    public List<Policy> policies(@AuthenticationPrincipal HrmUserPrincipal user,
                                 @RequestParam UUID leaveTypeId){
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT p.id,p.leave_type_id,p.monthly_credit_days,p.effective_from,p.active
            FROM time_leave_credit_policy p
            WHERE p.tenant_id=? AND p.leave_type_id=?
            ORDER BY p.effective_from DESC LIMIT 100
            """,(rs,n)->policy(rs),user.tenantId(),leaveTypeId));
    }
    @PostMapping("/leave-credit-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public Policy configure(@AuthenticationPrincipal HrmUserPrincipal user,
                            @Valid @RequestBody Configure input){
        // The start date is the first of a month: policy changes do not silently
        // split an accrual period or rewrite earlier ledger postings.
        if(input.effectiveFrom().getDayOfMonth()!=1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Policy effective date must begin a month");
        return tenants.inTenant(user.tenantId(),()->{
            Integer type=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_type WHERE tenant_id=? AND id=? AND active=true
                """,Integer.class,user.tenantId(),input.leaveTypeId());
            if(type==null||type!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active leave type not found");
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_credit_policy
                WHERE tenant_id=? AND leave_type_id=? AND effective_from=?
                """,Integer.class,user.tenantId(),input.leaveTypeId(),input.effectiveFrom());
            if(existing!=null&&existing!=0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Policy already exists for this month");
            Policy result=jdbc.queryForObject("""
                INSERT INTO time_leave_credit_policy
                 (tenant_id,leave_type_id,monthly_credit_days,effective_from,configured_by)
                VALUES(?,?,?,?,?)
                RETURNING id,leave_type_id,monthly_credit_days,effective_from,active
                """,(rs,n)->policy(rs),user.tenantId(),input.leaveTypeId(),
                input.monthlyCreditDays(),input.effectiveFrom(),user.id());
            audit(user,"TIME_LEAVE_CREDIT_POLICY_CREATED","time_leave_credit_policy",result.id());
            return result;
        });
    }
    @PostMapping("/employees/{employeeId}/leave-credits/accrue")
    @ResponseStatus(HttpStatus.CREATED)
    public Credit accrue(@AuthenticationPrincipal HrmUserPrincipal user,
                         @PathVariable UUID employeeId,
                         @RequestParam UUID leaveTypeId,@RequestParam YearMonth month){
        LocalDate period=month.atDay(1);
        if(month.isAfter(YearMonth.now(java.time.ZoneOffset.UTC)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Future accrual periods are not allowed");
        return tenants.inTenant(user.tenantId(),()->{
            // Lock employee to serialize concurrent ledger postings and employment changes.
            EmployeeCompany employee=employee(user.tenantId(),employeeId,true);
            if(period.isBefore(employee.hired().withDayOfMonth(1)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Accrual predates employment");
            Integer validType=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_type
                WHERE tenant_id=? AND id=? AND company_id=? AND active=true
                """,Integer.class,user.tenantId(),leaveTypeId,employee.company());
            if(validType==null||validType!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Leave category not found in employee company");
            List<Policy> active=jdbc.query("""
                SELECT id,leave_type_id,monthly_credit_days,effective_from,active
                FROM time_leave_credit_policy
                WHERE tenant_id=? AND leave_type_id=? AND effective_from<=? AND active=true
                ORDER BY effective_from DESC LIMIT 1
                """,(rs,n)->policy(rs),user.tenantId(),leaveTypeId,period);
            if(active.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"No configured credit rule for this period");
            Integer posted=jdbc.queryForObject("""
                SELECT count(*) FROM time_leave_credit_entry
                WHERE tenant_id=? AND employee_id=? AND leave_type_id=? AND period_start=?
                """,Integer.class,user.tenantId(),employeeId,leaveTypeId,period);
            if(posted!=null&&posted!=0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Monthly credit already posted");
            Policy selected=active.get(0);
            Credit credit=jdbc.queryForObject("""
                INSERT INTO time_leave_credit_entry
                 (tenant_id,employee_id,leave_type_id,policy_id,period_start,credit_days,posted_by)
                VALUES(?,?,?,?,?,?,?)
                RETURNING id,employee_id,leave_type_id,policy_id,period_start,credit_days
                """,(rs,n)->credit(rs),user.tenantId(),employeeId,leaveTypeId,
                selected.id(),period,selected.monthlyCreditDays(),user.id());
            audit(user,"TIME_LEAVE_CREDIT_POSTED","time_leave_credit_entry",credit.id());
            return credit;
        });
    }
    @GetMapping("/employees/{employeeId}/leave-credits")
    public List<Credit> entries(@AuthenticationPrincipal HrmUserPrincipal user,
                                @PathVariable UUID employeeId,@RequestParam UUID leaveTypeId){
        return tenants.inTenant(user.tenantId(),()->{
            employee(user.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT id,employee_id,leave_type_id,policy_id,period_start,credit_days
                FROM time_leave_credit_entry
                WHERE tenant_id=? AND employee_id=? AND leave_type_id=?
                ORDER BY period_start DESC LIMIT 240
                """,(rs,n)->credit(rs),user.tenantId(),employeeId,leaveTypeId);
        });
    }
    @GetMapping("/employees/{employeeId}/leave-credit-balances")
    public List<CreditBalance> balances(@AuthenticationPrincipal HrmUserPrincipal user,
                                        @PathVariable UUID employeeId){
        return tenants.inTenant(user.tenantId(),()->{
            employee(user.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT leave_type_id,coalesce(sum(credit_days),0) AS credited
                FROM time_leave_credit_entry
                WHERE tenant_id=? AND employee_id=?
                GROUP BY leave_type_id ORDER BY leave_type_id
                """,(rs,n)->new CreditBalance(employeeId,
                    rs.getObject("leave_type_id",UUID.class),rs.getBigDecimal("credited"),
                    false,"INFORMATIONAL_CREDITS_NOT_SPENDABLE"),
                    user.tenantId(),employeeId);
        });
    }
    private EmployeeCompany employee(UUID tenant,UUID id,boolean lock){
        List<EmployeeCompany> rows=jdbc.query("""
            SELECT company_id,hire_date FROM personnel_employee
            WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->new EmployeeCompany(
                rs.getObject(1,UUID.class),rs.getObject(2,LocalDate.class)),tenant,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        return rows.get(0);
    }
    private void audit(HrmUserPrincipal user,String action,String entity,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,?,?)
            """,user.tenantId(),user.id(),action,entity,id);
    }
    private static Policy policy(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Policy(rs.getObject("id",UUID.class),rs.getObject("leave_type_id",UUID.class),
            rs.getBigDecimal("monthly_credit_days"),rs.getObject("effective_from",LocalDate.class),
            rs.getBoolean("active"));
    }
    private static Credit credit(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Credit(rs.getObject("id",UUID.class),rs.getObject("employee_id",UUID.class),
            rs.getObject("leave_type_id",UUID.class),rs.getObject("policy_id",UUID.class),
            rs.getObject("period_start",LocalDate.class),rs.getBigDecimal("credit_days"));
    }
}
