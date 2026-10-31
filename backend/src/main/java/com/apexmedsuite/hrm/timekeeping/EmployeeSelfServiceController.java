package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2.9 read-only self-service. Identity is resolved only through a tenant
 * administrator-created link; never by email or a client-supplied employee ID.
 * Employee clock punches, geolocation, biometrics and employee leave mutations
 * remain disabled until independently accepted.
 */
@RestController
@RequestMapping("/api/v1/employee-self-service")
public class EmployeeSelfServiceController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public EmployeeSelfServiceController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record BindIdentity(@NotNull UUID identityId,@NotNull UUID employeeId){}
    public record Link(UUID id,UUID employeeId,UUID identityId){}
    public record Profile(UUID employeeId,UUID companyId,String employeeNumber,
                          String firstName,String lastName,String employmentStatus){}
    public record Schedule(UUID id,UUID shiftId,LocalDate effectiveFrom,
                           LocalDate effectiveUntil,int weekdaysMask){}
    public record Leave(UUID id,UUID leaveTypeId,LocalDate startsOn,LocalDate endsOn,String status){}
    public record Summary(Profile employee,List<Schedule> schedules,List<Leave> leaveRequests,
                          boolean clockInEnabled,boolean locationCaptureEnabled,
                          boolean biometricCaptureEnabled,String classification){}

    @PostMapping("/admin/identity-links")
    @ResponseStatus(HttpStatus.CREATED)
    public Link bind(@AuthenticationPrincipal HrmUserPrincipal actor,@Valid @RequestBody BindIdentity body){
        // Defense in depth: route authorization is also enforced at SecurityConfig.
        if(!actor.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_TENANT_ADMIN"))
            ||!actor.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_MFA_VERIFIED")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"MFA tenant administrator required");
        if(actor.id().equals(body.identityId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin cannot bind own administrator identity");
        return tenants.inTenant(actor.tenantId(),()->{
            List<UUID> identities=jdbc.query("""
                SELECT i.id FROM auth_identity i
                JOIN auth_identity_role r ON r.identity_id=i.id
                WHERE i.tenant_id=? AND i.id=? AND i.enabled=true AND r.role='EMPLOYEE'
                """,(rs,n)->rs.getObject(1,UUID.class),actor.tenantId(),body.identityId());
            if(identities.isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Enabled employee-role identity not found");
            List<String> statuses=jdbc.query("""
                SELECT employment_status FROM personnel_employee
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),actor.tenantId(),body.employeeId());
            if(statuses.isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            if(!List.of("ACTIVE","ON_LEAVE").contains(statuses.get(0)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Only active or on-leave employees may be linked");
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM time_employee_identity_link
                WHERE tenant_id=? AND (employee_id=? OR identity_id=?)
                """,Integer.class,actor.tenantId(),body.employeeId(),body.identityId());
            if(existing!=null&&existing>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Identity or employee already linked");
            Link link=jdbc.queryForObject("""
                INSERT INTO time_employee_identity_link(tenant_id,identity_id,employee_id,linked_by)
                VALUES(?,?,?,?) RETURNING id,employee_id,identity_id
                """,(rs,n)->new Link(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                  rs.getObject(3,UUID.class)),actor.tenantId(),body.identityId(),
                  body.employeeId(),actor.id());
            audit(actor,"TIME_ESS_IDENTITY_LINKED","time_employee_identity_link",link.id());
            return link;
        });
    }

    @GetMapping("/admin/identity-links")
    public List<Link> links(@AuthenticationPrincipal HrmUserPrincipal actor,
                            @RequestParam UUID employeeId){
        return tenants.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,employee_id,identity_id FROM time_employee_identity_link
            WHERE tenant_id=? AND employee_id=? AND revoked_at IS NULL LIMIT 1
            """,(rs,n)->new Link(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class)),actor.tenantId(),employeeId));
    }

    @PostMapping("/admin/identity-links/{linkId}/revoke")
    public Link revoke(@AuthenticationPrincipal HrmUserPrincipal actor,
                       @PathVariable UUID linkId){
        return tenants.inTenant(actor.tenantId(),()->{
            List<Link> rows=jdbc.query("""
                SELECT id,employee_id,identity_id FROM time_employee_identity_link
                WHERE tenant_id=? AND id=? AND revoked_at IS NULL FOR UPDATE
                """,(rs,n)->new Link(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getObject(3,UUID.class)),actor.tenantId(),linkId);
            if(rows.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Active identity link not found");
            jdbc.update("""
                UPDATE time_employee_identity_link
                SET revoked_at=now(),revoked_by=?
                WHERE tenant_id=? AND id=? AND revoked_at IS NULL
                """,actor.id(),actor.tenantId(),linkId);
            audit(actor,"TIME_ESS_IDENTITY_REVOKED","time_employee_identity_link",linkId);
            return rows.get(0);
        });
    }

    @GetMapping("/me")
    public Summary me(@AuthenticationPrincipal HrmUserPrincipal actor){
        return tenants.inTenant(actor.tenantId(),()->{
            List<UUID> linked=jdbc.query("""
                SELECT employee_id FROM time_employee_identity_link
                WHERE tenant_id=? AND identity_id=? AND revoked_at IS NULL
                """,(rs,n)->rs.getObject(1,UUID.class),actor.tenantId(),actor.id());
            if(linked.size()!=1)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Employee identity not verified; contact HR");
            UUID employeeId=linked.get(0);
            List<Profile> profile=jdbc.query("""
                SELECT id,company_id,employee_number,first_name,last_name,employment_status
                FROM personnel_employee WHERE tenant_id=? AND id=?
                """,(rs,n)->new Profile(rs.getObject("id",UUID.class),
                    rs.getObject("company_id",UUID.class),rs.getString("employee_number"),
                    rs.getString("first_name"),rs.getString("last_name"),
                    rs.getString("employment_status")),actor.tenantId(),employeeId);
            if(profile.size()!=1||!List.of("ACTIVE","ON_LEAVE").contains(profile.get(0).employmentStatus()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee self-service is inactive");
            List<Schedule> schedules=jdbc.query("""
                SELECT id,shift_id,effective_from,effective_until,weekdays_mask
                FROM time_schedule_assignment
                WHERE tenant_id=? AND employee_id=?
                ORDER BY effective_from DESC LIMIT 100
                """,(rs,n)->new Schedule(rs.getObject("id",UUID.class),rs.getObject("shift_id",UUID.class),
                    rs.getObject("effective_from",LocalDate.class),
                    rs.getObject("effective_until",LocalDate.class),rs.getInt("weekdays_mask")),
                actor.tenantId(),employeeId);
            List<Leave> leaves=jdbc.query("""
                SELECT id,leave_type_id,starts_on,ends_on,status FROM time_leave_request
                WHERE tenant_id=? AND employee_id=?
                ORDER BY requested_at DESC LIMIT 100
                """,(rs,n)->new Leave(rs.getObject("id",UUID.class),
                    rs.getObject("leave_type_id",UUID.class),
                    rs.getObject("starts_on",LocalDate.class),
                    rs.getObject("ends_on",LocalDate.class),rs.getString("status")),
                actor.tenantId(),employeeId);
            return new Summary(profile.get(0),schedules,leaves,
                false,false,false,"READ_ONLY_VERIFIED_EMPLOYEE_NO_DEVICE_CAPTURE");
        });
    }
    private void audit(HrmUserPrincipal actor,String action,String entity,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,?,?)
            """,actor.tenantId(),actor.id(),action,entity,id);
    }
}
