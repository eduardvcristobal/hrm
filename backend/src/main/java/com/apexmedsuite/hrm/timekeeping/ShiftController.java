package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** M2.1 shift templates. Local clock times are interpreted in their named IANA zone. */
@RestController
@RequestMapping("/api/v1/timekeeping/shifts")
public class ShiftController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public ShiftController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Shift(UUID id,UUID companyId,String code,String name,LocalTime start,LocalTime end,
        String timezone,int unpaidBreakMinutes,int graceMinutes,boolean active) {}
    public record NewShift(@NotNull UUID companyId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]+") @Size(max=40) String code,
        @NotBlank @Size(max=160) String name,
        @NotNull LocalTime start,@NotNull LocalTime end,
        @NotBlank String timezone,@Min(0) @Max(240) int unpaidBreakMinutes,
        @Min(0) @Max(120) int graceMinutes) {}
    @GetMapping
    public List<Shift> list(@AuthenticationPrincipal HrmUserPrincipal user,@RequestParam UUID companyId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,company_id,shift_code,shift_name,starts_at,ends_at,timezone,
                   unpaid_break_minutes,grace_minutes,active
            FROM time_shift_template WHERE tenant_id=? AND company_id=?
            ORDER BY shift_code LIMIT 200
            """,(rs,n)->map(rs),user.tenantId(),companyId));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Shift create(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody NewShift input) {
        if(input.start().equals(input.end()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Shift cannot have zero duration");
        try{ZoneId.of(input.timezone());}
        catch(DateTimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown timezone");}
        int minutes=(int)Duration.between(input.start(),input.end()).toMinutes();
        if(minutes<=0)minutes+=1440;
        if(minutes>960||input.unpaidBreakMinutes()>=minutes)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Shift must be at most 16 hours with shorter unpaid break");
        return tenants.inTenant(user.tenantId(),()->{
            Integer count=jdbc.queryForObject("SELECT count(*) FROM company WHERE tenant_id=? AND id=?",
                Integer.class,user.tenantId(),input.companyId());
            if(count==null||count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found");
            Shift result=jdbc.queryForObject("""
                INSERT INTO time_shift_template
                 (tenant_id,company_id,shift_code,shift_name,starts_at,ends_at,timezone,unpaid_break_minutes,grace_minutes)
                VALUES(?,?,?,?,?,?,?,?,?)
                RETURNING id,company_id,shift_code,shift_name,starts_at,ends_at,timezone,
                          unpaid_break_minutes,grace_minutes,active
                """,(rs,n)->map(rs),user.tenantId(),input.companyId(),input.code().trim(),
                    input.name().trim(),input.start(),input.end(),input.timezone(),
                    input.unpaidBreakMinutes(),input.graceMinutes());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'TIME_SHIFT_CREATED','time_shift_template',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }
    private static Shift map(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Shift(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getString("shift_code"),rs.getString("shift_name"),
            rs.getObject("starts_at",LocalTime.class),rs.getObject("ends_at",LocalTime.class),
            rs.getString("timezone"),rs.getInt("unpaid_break_minutes"),rs.getInt("grace_minutes"),
            rs.getBoolean("active"));
    }
}
