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
 * M2.6: tenant-authored calendar and employee daily override.
 * These records classify days only; they never apply statutory premiums,
 * alter clock punches, settle leave or feed payroll.
 */
@RestController
@RequestMapping("/api/v1/timekeeping")
public class CalendarPolicyController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public CalendarPolicyController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record CompanyDay(UUID id,UUID companyId,LocalDate date,String kind,String title){}
    public record NewCompanyDay(@NotNull UUID companyId,@NotNull LocalDate date,
        @NotBlank @Pattern(regexp="HOLIDAY|SPECIAL_NON_WORKING|COMPANY_CLOSURE") String kind,
        @NotBlank @Size(min=3,max=160) String title) {}
    public record Override(UUID id,UUID employeeId,LocalDate date,String kind,String reason){}
    public record NewOverride(@NotNull LocalDate date,
        @NotBlank @Pattern(regexp="REST_DAY|WORK_DAY") String kind,
        @NotBlank @Size(min=8,max=500) String reason) {}
    public record DayPreview(LocalDate date,UUID shiftId,String timezone,boolean scheduled,
        boolean restDay,String scheduleStatus,String companyDayKind,String companyDayTitle,
        String employeeOverride,boolean provisional,String classification){}

    @PostMapping("/calendar-days")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyDay createDay(@AuthenticationPrincipal HrmUserPrincipal actor,
                                @Valid @RequestBody NewCompanyDay input){
        return tenants.inTenant(actor.tenantId(),()->{
            Integer present=jdbc.queryForObject("SELECT count(*) FROM company WHERE tenant_id=? AND id=?",
                Integer.class,actor.tenantId(),input.companyId());
            if(present==null||present!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found");
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM time_company_calendar_day WHERE tenant_id=? AND company_id=? AND calendar_date=?
                """,Integer.class,actor.tenantId(),input.companyId(),input.date());
            if(existing!=null&&existing>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Calendar date already configured");
            CompanyDay created=jdbc.queryForObject("""
                INSERT INTO time_company_calendar_day
                  (tenant_id,company_id,calendar_date,day_kind,title,created_by)
                VALUES(?,?,?,?,?,?)
                RETURNING id,company_id,calendar_date,day_kind,title
                """,(rs,n)->companyDay(rs),actor.tenantId(),input.companyId(),input.date(),
                input.kind(),input.title().trim(),actor.id());
            audit(actor,"TIME_COMPANY_CALENDAR_CREATED","time_company_calendar_day",created.id());
            return created;
        });
    }
    @GetMapping("/calendar-days")
    public List<CompanyDay> companyDays(@AuthenticationPrincipal HrmUserPrincipal actor,
                                       @RequestParam UUID companyId,
                                       @RequestParam LocalDate from,@RequestParam LocalDate to){
        checkRange(from,to);
        return tenants.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,company_id,calendar_date,day_kind,title
            FROM time_company_calendar_day
            WHERE tenant_id=? AND company_id=? AND calendar_date BETWEEN ? AND ?
            ORDER BY calendar_date LIMIT 367
            """,(rs,n)->companyDay(rs),actor.tenantId(),companyId,from,to));
    }
    @PostMapping("/employees/{employeeId}/day-overrides")
    @ResponseStatus(HttpStatus.CREATED)
    public Override createOverride(@AuthenticationPrincipal HrmUserPrincipal actor,
                                  @PathVariable UUID employeeId,@Valid @RequestBody NewOverride input){
        return tenants.inTenant(actor.tenantId(),()->{
            employeeCompany(actor.tenantId(),employeeId,true);
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM time_employee_day_override
                WHERE tenant_id=? AND employee_id=? AND calendar_date=?
                """,Integer.class,actor.tenantId(),employeeId,input.date());
            if(existing!=null&&existing>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Employee date override already configured");
            Override created=jdbc.queryForObject("""
                INSERT INTO time_employee_day_override(tenant_id,employee_id,calendar_date,day_kind,reason,created_by)
                VALUES(?,?,?,?,?,?)
                RETURNING id,employee_id,calendar_date,day_kind,reason
                """,(rs,n)->overrideRow(rs),actor.tenantId(),employeeId,input.date(),
                input.kind(),input.reason().trim(),actor.id());
            audit(actor,"TIME_EMPLOYEE_DAY_OVERRIDE_CREATED","time_employee_day_override",created.id());
            return created;
        });
    }
    @GetMapping("/employees/{employeeId}/day-overrides")
    public List<Override> overrides(@AuthenticationPrincipal HrmUserPrincipal actor,
                                  @PathVariable UUID employeeId,@RequestParam LocalDate from,
                                  @RequestParam LocalDate to){
        checkRange(from,to);
        return tenants.inTenant(actor.tenantId(),()->{
            employeeCompany(actor.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT id,employee_id,calendar_date,day_kind,reason
                FROM time_employee_day_override WHERE tenant_id=? AND employee_id=?
                AND calendar_date BETWEEN ? AND ? ORDER BY calendar_date LIMIT 367
                """,(rs,n)->overrideRow(rs),actor.tenantId(),employeeId,from,to);
        });
    }
    @GetMapping("/employees/{employeeId}/calendar-preview")
    public List<DayPreview> preview(@AuthenticationPrincipal HrmUserPrincipal actor,
                                  @PathVariable UUID employeeId,@RequestParam LocalDate from,
                                  @RequestParam LocalDate to){
        checkRange(from,to);
        return tenants.inTenant(actor.tenantId(),()->{
            UUID company=employeeCompany(actor.tenantId(),employeeId,false);
            Map<LocalDate,CompanyDay> days=new HashMap<>();
            jdbc.query("""
                SELECT id,company_id,calendar_date,day_kind,title FROM time_company_calendar_day
                WHERE tenant_id=? AND company_id=? AND calendar_date BETWEEN ? AND ?
                """,(rs,n)->companyDay(rs),actor.tenantId(),company,from,to)
                .forEach(day->days.put(day.date(),day));
            Map<LocalDate,Override> overrides=new HashMap<>();
            jdbc.query("""
                SELECT id,employee_id,calendar_date,day_kind,reason FROM time_employee_day_override
                WHERE tenant_id=? AND employee_id=? AND calendar_date BETWEEN ? AND ?
                """,(rs,n)->overrideRow(rs),actor.tenantId(),employeeId,from,to)
                .forEach(override->overrides.put(override.date(),override));
            List<DayPreview> out=new ArrayList<>();
            for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1)){
                int bit=1<<(date.getDayOfWeek().getValue()-1);
                List<ShiftInfo> active=jdbc.query("""
                    SELECT a.shift_id,s.timezone FROM time_schedule_assignment a
                    JOIN time_shift_template s ON s.tenant_id=a.tenant_id AND s.id=a.shift_id
                    WHERE a.tenant_id=? AND a.employee_id=? AND a.effective_from<=?
                      AND (a.effective_until IS NULL OR a.effective_until>=?)
                    AND (a.weekdays_mask & ?)<>0 ORDER BY a.created_at DESC LIMIT 2
                    """,(rs,n)->new ShiftInfo(rs.getObject(1,UUID.class),rs.getString(2)),
                    actor.tenantId(),employeeId,date,date,bit);
                if(active.size()>1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Ambiguous employee schedule");
                // No assignment at all is UNSCHEDULED, not automatically a rest day:
                // a rest-day conclusion needs a matching schedule policy or explicit override.
                boolean anyAssignment=Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM time_schedule_assignment
                        WHERE tenant_id=? AND employee_id=? AND effective_from<=?
                        AND (effective_until IS NULL OR effective_until>=?))
                    """,Boolean.class,actor.tenantId(),employeeId,date,date));
                Override override=overrides.get(date);
                boolean scheduled=active.size()==1;
                boolean restDay=anyAssignment&&!scheduled;
                String status=anyAssignment?(scheduled?"SCHEDULED":"REST_DAY"):"UNSCHEDULED";
                if(override!=null){
                    scheduled="WORK_DAY".equals(override.kind());
                    restDay="REST_DAY".equals(override.kind());
                    status=override.kind();
                }
                CompanyDay day=days.get(date);
                ShiftInfo shift=active.isEmpty()?null:active.get(0);
                out.add(new DayPreview(date,shift==null?null:shift.id(),
                    shift==null?null:shift.zone(),scheduled,restDay,status,
                    day==null?null:day.kind(),day==null?null:day.title(),
                    override==null?null:override.kind(),true,
                    "INFORMATIONAL_NO_LEGAL_HOLIDAY_OR_PAYROLL_CALCULATION"));
            }
            return out;
        });
    }
    private record ShiftInfo(UUID id,String zone){}
    private static void checkRange(LocalDate from,LocalDate to){
        if(to.isBefore(from)||to.isAfter(from.plusDays(30)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date range limited to 31 days");
    }
    private UUID employeeCompany(UUID tenant,UUID employee,boolean lock){
        List<UUID> rows=jdbc.query("""
            SELECT company_id FROM personnel_employee WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->rs.getObject(1,UUID.class),tenant,employee);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        return rows.get(0);
    }
    private void audit(HrmUserPrincipal actor,String action,String entity,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,?,?)
            """,actor.tenantId(),actor.id(),action,entity,id);
    }
    private static CompanyDay companyDay(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new CompanyDay(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getObject("calendar_date",LocalDate.class),rs.getString("day_kind"),rs.getString("title"));
    }
    private static Override overrideRow(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new Override(rs.getObject("id",UUID.class),rs.getObject("employee_id",UUID.class),
            rs.getObject("calendar_date",LocalDate.class),rs.getString("day_kind"),rs.getString("reason"));
    }
}
