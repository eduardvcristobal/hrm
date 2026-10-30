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

@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/employment")
public class EmploymentHistoryController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public EmploymentHistoryController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record EmploymentEvent(UUID id,LocalDate effectiveDate,String employmentType,
        String employmentStatus,String jobTitle,String reason) {}
    public record ChangeEmployment(
        @NotNull @PastOrPresent LocalDate effectiveDate,
        @NotBlank @Pattern(regexp="ACTIVE|ON_LEAVE|SUSPENDED|TERMINATED|RETIRED") String status,
        @NotBlank @Pattern(regexp="REGULAR|PROBATIONARY|CONTRACTUAL|PROJECT|SEASONAL|CASUAL|PART_TIME") String type,
        @Size(max=160) String jobTitle,
        @NotBlank @Size(max=250) String reason
    ) {}
    @GetMapping
    public List<EmploymentEvent> history(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureEmployee(user.tenantId(),employeeId);
            return jdbc.query("""
                SELECT id,effective_date,employment_type,employment_status,job_title,reason
                FROM personnel_employment_history WHERE tenant_id=? AND employee_id=?
                ORDER BY effective_date DESC,recorded_at DESC
                """,(rs,i)->map(rs),user.tenantId(),employeeId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmploymentEvent change(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
            @Valid @RequestBody ChangeEmployment input) {
        return tenants.inTenant(user.tenantId(),()->{
            List<EmployeeState> hires=jdbc.query("""
                SELECT hire_date,employment_status,employment_type FROM personnel_employee
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->new EmployeeState(rs.getObject(1,LocalDate.class),rs.getString(2),rs.getString(3)),user.tenantId(),employeeId);
            if(hires.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            if(input.effectiveDate().isBefore(hires.get(0).hireDate()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Employment change predates hire");
            String previous=hires.get(0).status();
            String next=input.status();
            if ((previous.equals("TERMINATED") || previous.equals("RETIRED")) && !next.equals("ACTIVE"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Separated employee requires rehire");
            if (previous.equals("RETIRED") && next.equals("ACTIVE"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Retired employee requires separate rehire case");
            if (previous.equals("ACTIVE") && next.equals("ACTIVE")
                && hires.get(0).type().equals(input.type()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"No lifecycle transition");
            LocalDate latest=jdbc.queryForObject("""
                SELECT max(effective_date) FROM personnel_employment_history
                WHERE tenant_id=? AND employee_id=?
                """,LocalDate.class,user.tenantId(),employeeId);
            if(latest!=null && !input.effectiveDate().isAfter(latest))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Effective date must follow previous change");
            jdbc.update("""
                UPDATE personnel_employee SET employment_status=?,employment_type=?,updated_at=now()
                WHERE tenant_id=? AND id=?
                """,input.status(),input.type(),user.tenantId(),employeeId);
            EmploymentEvent event=jdbc.queryForObject("""
                INSERT INTO personnel_employment_history
                  (tenant_id,employee_id,effective_date,employment_type,employment_status,job_title,reason)
                VALUES(?,?,?,?,?,?,?)
                RETURNING id,effective_date,employment_type,employment_status,job_title,reason
                """,(rs,i)->map(rs),user.tenantId(),employeeId,input.effectiveDate(),input.type(),
                input.status(),input.jobTitle(),input.reason().trim());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'EMPLOYMENT_CHANGED','personnel_employee',?)
                """,user.tenantId(),user.id(),employeeId);
            return event;
        });
    }
    private record EmployeeState(LocalDate hireDate,String status,String type) {}
    private void ensureEmployee(UUID tenantId,UUID employeeId) {
        Integer exists=jdbc.queryForObject("""
            SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?
            """,Integer.class,tenantId,employeeId);
        if(exists==null||exists==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
    }
    private static EmploymentEvent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EmploymentEvent(rs.getObject("id",UUID.class),rs.getObject("effective_date",LocalDate.class),
            rs.getString("employment_type"),rs.getString("employment_status"),rs.getString("job_title"),
            rs.getString("reason"));
    }
}
