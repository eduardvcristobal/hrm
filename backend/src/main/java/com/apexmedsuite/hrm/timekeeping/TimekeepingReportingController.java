package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2.8 internal, tenant-scoped, bounded reports and controlled CSV download.
 * No PII names or free text in exports; no payroll-ready interpretation.
 * All /api/v1/timekeeping/** routes require TENANT_ADMIN + MFA_VERIFIED.
 */
@RestController
@RequestMapping("/api/v1/timekeeping")
public class TimekeepingReportingController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public TimekeepingReportingController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record Receipt(UUID id,String exportType,LocalDate from,LocalDate to,
                          int recordCount,String contentSha256) {}
    public record RetentionPlan(UUID id,UUID companyId,int attendanceMinDays,
                                int auditMinDays,boolean legalHold) {}
    public record NewPlan(@NotNull UUID companyId,@Min(365) @Max(36500) int attendanceMinDays,
                          @Min(365) @Max(36500) int auditMinDays) {}
    public record RetentionPreview(UUID companyId,boolean legalHold,LocalDate attendanceBefore,
        LocalDate auditBefore,long attendanceCandidates,long auditCandidates,boolean deletionEnabled,
        String classification) {}
    private record Session(UUID id,UUID employee,Instant start,Instant end) {}
    private record TimeAudit(long id,Instant time,UUID actor,String action,
                             String entityType,UUID entity) {}
    private static void range(LocalDate from,LocalDate to) {
        if(to.isBefore(from)||to.isAfter(from.plusDays(30)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reporting period limited to 31 days");
    }
    private static ZoneId zone(String input) {
        try { return ZoneId.of(input); }
        catch(DateTimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid reporting timezone");
        }
    }
    @GetMapping("/reports/attendance.csv")
    public ResponseEntity<String> attendanceCsv(@AuthenticationPrincipal HrmUserPrincipal actor,
          @RequestParam UUID companyId,@RequestParam LocalDate from,@RequestParam LocalDate to,
          @RequestParam(defaultValue="Asia/Manila") String timezone) {
        range(from,to);ZoneId namedZone=zone(timezone);
        return tenants.inTenant(actor.tenantId(),()->{
            company(actor.tenantId(),companyId);
            Instant start=from.atStartOfDay(namedZone).toInstant();
            Instant end=to.plusDays(1).atStartOfDay(namedZone).toInstant();
            List<Session> rows=jdbc.query("""
                SELECT a.id,a.employee_id,a.clock_in_at,a.clock_out_at
                FROM time_attendance_session a
                JOIN personnel_employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                WHERE a.tenant_id=? AND e.company_id=? AND a.clock_in_at<?
                  AND (a.clock_out_at IS NULL OR a.clock_out_at>?)
                ORDER BY a.clock_in_at,a.id LIMIT 501
                """,(rs,n)->new Session(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getTimestamp(3).toInstant(),
                    rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toInstant()),
                actor.tenantId(),companyId,Timestamp.from(end),Timestamp.from(start));
            if(rows.size()>500)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Narrow the report: over 500 attendance sessions");
            StringBuilder csv=new StringBuilder(
                "session_id,employee_id,clock_in_utc,clock_out_utc,clipped_raw_minutes,open_session,classification\r\n");
            for(Session row:rows) {
                Instant clipStart=row.start().isAfter(start)?row.start():start;
                Instant clipEnd=row.end()==null?clipStart:(row.end().isBefore(end)?row.end():end);
                long minutes=clipEnd.isAfter(clipStart)?Duration.between(clipStart,clipEnd).toMinutes():0;
                csv.append(row.id()).append(',').append(row.employee()).append(',')
                    .append(row.start()).append(',')
                    .append(row.end()==null?"":row.end()).append(',')
                    .append(minutes).append(',').append(row.end()==null).append(',')
                    .append("RAW_NOT_PAYROLL_APPROVED").append("\r\n");
            }
            String body=csv.toString();
            receipt(actor,"ATTENDANCE_SUMMARY",from,to,rows.size(),body);
            return download("timekeeping-attendance.csv",body);
        });
    }
    @GetMapping("/reports/audit.csv")
    public ResponseEntity<String> auditCsv(@AuthenticationPrincipal HrmUserPrincipal actor,
          @RequestParam LocalDate from,@RequestParam LocalDate to) {
        range(from,to);
        return tenants.inTenant(actor.tenantId(),()->{
            Instant start=from.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end=to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            List<TimeAudit> rows=jdbc.query("""
                SELECT id,occurred_at,actor_id,action,entity_type,entity_id
                FROM audit_event WHERE tenant_id=?
                  AND occurred_at>=? AND occurred_at<? AND action LIKE 'TIME_%'
                ORDER BY occurred_at,id LIMIT 501
                """,(rs,n)->new TimeAudit(rs.getLong(1),rs.getTimestamp(2).toInstant(),
                    rs.getObject(3,UUID.class),rs.getString(4),
                    rs.getString(5),rs.getObject(6,UUID.class)),
                actor.tenantId(),Timestamp.from(start),Timestamp.from(end));
            if(rows.size()>500)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Narrow audit export: more than 500 events");
            StringBuilder csv=new StringBuilder("audit_id,occurred_at_utc,actor_id,action,entity_type,entity_id\r\n");
            for(TimeAudit row:rows) {
                // action/entity_type originate from controlled server-generated values;
                // still neutralize spreadsheet formula injection on string fields.
                csv.append(row.id()).append(',').append(row.time()).append(',')
                  .append(row.actor()).append(',').append(safeCsv(row.action())).append(',')
                  .append(safeCsv(row.entityType())).append(',')
                  .append(row.entity()==null?"":row.entity()).append("\r\n");
            }
            String body=csv.toString();
            receipt(actor,"TIMEKEEPING_AUDIT",from,to,rows.size(),body);
            return download("timekeeping-audit.csv",body);
        });
    }
    @GetMapping("/reports/export-receipts")
    public List<Receipt> receipts(@AuthenticationPrincipal HrmUserPrincipal actor) {
        return tenants.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,export_type,date_from,date_to,record_count,content_sha256
            FROM time_reporting_export_receipt
            WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 100
            """,(rs,n)->new Receipt(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class),
                rs.getInt(5),rs.getString(6)),actor.tenantId()));
    }
    @PostMapping("/retention/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public RetentionPlan createPlan(@AuthenticationPrincipal HrmUserPrincipal actor,
                                     @Valid @RequestBody NewPlan input) {
        return tenants.inTenant(actor.tenantId(),()->{
            company(actor.tenantId(),input.companyId());
            Integer previous=jdbc.queryForObject("""
                SELECT count(*) FROM time_retention_plan WHERE tenant_id=? AND company_id=?
                """,Integer.class,actor.tenantId(),input.companyId());
            if(previous!=null&&previous>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Retention plan already exists; no self-service shortening");
            RetentionPlan plan=jdbc.queryForObject("""
                INSERT INTO time_retention_plan(tenant_id,company_id,attendance_min_days,
                  audit_min_days,created_by)
                VALUES(?,?,?,?,?)
                RETURNING id,company_id,attendance_min_days,audit_min_days,legal_hold
                """,(rs,n)->new RetentionPlan(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getInt(3),rs.getInt(4),rs.getBoolean(5)),
                actor.tenantId(),input.companyId(),input.attendanceMinDays(),
                input.auditMinDays(),actor.id());
            audit(actor,"TIME_RETENTION_PLAN_CREATED","time_retention_plan",plan.id());
            return plan;
        });
    }
    @GetMapping("/retention/plans")
    public List<RetentionPlan> plans(@AuthenticationPrincipal HrmUserPrincipal actor,
                                    @RequestParam UUID companyId) {
        return tenants.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,company_id,attendance_min_days,audit_min_days,legal_hold
            FROM time_retention_plan WHERE tenant_id=? AND company_id=?
            """,(rs,n)->new RetentionPlan(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getInt(3),rs.getInt(4),rs.getBoolean(5)),
            actor.tenantId(),companyId));
    }
    @GetMapping("/retention/preview")
    public RetentionPreview retentionPreview(@AuthenticationPrincipal HrmUserPrincipal actor,
                                              @RequestParam UUID companyId) {
        return tenants.inTenant(actor.tenantId(),()->{
            company(actor.tenantId(),companyId);
            List<RetentionPlan> rules=jdbc.query("""
                SELECT id,company_id,attendance_min_days,audit_min_days,legal_hold
                FROM time_retention_plan WHERE tenant_id=? AND company_id=?
                """,(rs,n)->new RetentionPlan(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getInt(3),rs.getInt(4),rs.getBoolean(5)),
                actor.tenantId(),companyId);
            if(rules.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Configure retention plan before preview");
            RetentionPlan rule=rules.get(0);
            LocalDate attendanceCutoff=LocalDate.now(ZoneOffset.UTC).minusDays(rule.attendanceMinDays());
            LocalDate auditCutoff=LocalDate.now(ZoneOffset.UTC).minusDays(rule.auditMinDays());
            Long attendance=jdbc.queryForObject("""
                SELECT count(*) FROM time_attendance_session a
                JOIN personnel_employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                WHERE a.tenant_id=? AND e.company_id=?
                  AND a.clock_in_at<? AND a.clock_out_at IS NOT NULL
                """,Long.class,actor.tenantId(),companyId,
                Timestamp.from(attendanceCutoff.atStartOfDay(ZoneOffset.UTC).toInstant()));
            // Audit entries may be associated with other companies in a tenant.
            // We intentionally do not infer company-level audit retention counts.
            return new RetentionPreview(companyId,true,attendanceCutoff,auditCutoff,
                attendance==null?0:attendance,0,false,
                "LEGAL_HOLD_ENABLED_NON_DESTRUCTIVE_ADVISORY_ONLY");
        });
    }
    private void company(UUID tenant,UUID id){
        Integer count=jdbc.queryForObject("SELECT count(*) FROM company WHERE tenant_id=? AND id=?",
            Integer.class,tenant,id);
        if(count==null||count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found");
    }
    private void receipt(HrmUserPrincipal actor,String kind,LocalDate from,LocalDate to,
                         int count,String body){
        UUID id=jdbc.queryForObject("""
            INSERT INTO time_reporting_export_receipt(tenant_id,actor_id,export_type,date_from,date_to,
              record_count,content_sha256)
            VALUES(?,?,?,?,?,?,?) RETURNING id
            """,UUID.class,actor.tenantId(),actor.id(),kind,from,to,count,sha256(body));
        audit(actor,"TIME_REPORT_EXPORTED","time_reporting_export_receipt",id);
    }
    private void audit(HrmUserPrincipal actor,String action,String type,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,?,?)
            """,actor.tenantId(),actor.id(),action,type,id);
    }
    private static String sha256(String body){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                 .digest(body.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static String safeCsv(String input){
        if(input==null)return "";
        String escaped=input.replace("\"","\"\"");
        if(!escaped.isEmpty()&&(escaped.charAt(0)=='='||escaped.charAt(0)=='+'
             ||escaped.charAt(0)=='-'||escaped.charAt(0)=='@'))
            escaped="'"+escaped;
        return "\""+escaped+"\"";
    }
    private static ResponseEntity<String> download(String filename,String body) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("X-Content-Type-Options","nosniff")
            .contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
            .body(body);
    }
}
