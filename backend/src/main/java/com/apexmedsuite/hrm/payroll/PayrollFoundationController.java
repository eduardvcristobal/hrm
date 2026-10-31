package com.apexmedsuite.hrm.payroll;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;

/** M3.1 metadata only: no payroll computation, editable payroll run or payout. */
@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollFoundationController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenant;
    public PayrollFoundationController(JdbcTemplate jdbc,TenantJdbcExecutor tenant){
        this.jdbc=jdbc;this.tenant=tenant;
    }
    public record NewConfig(@NotNull UUID companyId,@NotBlank String payFrequency,
                            @NotBlank String currencyCode,@NotBlank String timezone,
                            @NotNull LocalDate effectiveFrom){}
    public record Config(UUID id,UUID companyId,String payFrequency,String currencyCode,
                         String timezone,LocalDate effectiveFrom){}
    public record NewPeriod(@NotNull UUID companyId,@NotNull UUID configurationId,
                            @NotNull LocalDate startsOn,@NotNull LocalDate endsOn,
                            @NotNull LocalDate payOn){}
    public record Period(UUID id,UUID companyId,UUID configurationId,LocalDate startsOn,
                         LocalDate endsOn,LocalDate payOn,String status,String classification){}
    private void writeAudit(HrmUserPrincipal actor,String action,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,?,?)
            """,actor.tenantId(),actor.id(),action,
            action.startsWith("PAYROLL_PERIOD")?"payroll_period":"payroll_configuration",id);
    }
    @PostMapping("/configurations")
    @ResponseStatus(HttpStatus.CREATED)
    public Config createConfig(@AuthenticationPrincipal HrmUserPrincipal actor,
                               @Valid @RequestBody NewConfig input){
        if(!Set.of("SEMI_MONTHLY","MONTHLY","WEEKLY","BIWEEKLY").contains(input.payFrequency()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported pay frequency");
        if(!input.currencyCode().matches("[A-Z]{3}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ISO currency code must have three uppercase letters");
        try { ZoneId.of(input.timezone()); }
        catch(DateTimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid time zone");}
        return tenant.inTenant(actor.tenantId(),()->{
            Integer company=jdbc.queryForObject("""
                SELECT count(*) FROM company WHERE tenant_id=? AND id=?
                """,Integer.class,actor.tenantId(),input.companyId());
            if(company==null||company!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found in tenant");
            Integer duplicates=jdbc.queryForObject("""
                SELECT count(*) FROM payroll_configuration
                WHERE tenant_id=? AND company_id=? AND effective_from=?
                """,Integer.class,actor.tenantId(),input.companyId(),input.effectiveFrom());
            if(duplicates!=null&&duplicates>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Configuration effective date already exists");
            Config config=jdbc.queryForObject("""
                INSERT INTO payroll_configuration
                  (tenant_id,company_id,pay_frequency,currency_code,timezone,effective_from,created_by)
                VALUES(?,?,?,?,?,?,?)
                RETURNING id,company_id,pay_frequency,currency_code,timezone,effective_from
                """,(rs,n)->configRow(rs),actor.tenantId(),input.companyId(),
                input.payFrequency(),input.currencyCode(),input.timezone(),input.effectiveFrom(),actor.id());
            writeAudit(actor,"PAYROLL_CONFIGURATION_CREATED",config.id());
            return config;
        });
    }
    @GetMapping("/configurations")
    public List<Config> configurations(@AuthenticationPrincipal HrmUserPrincipal actor,
                                        @RequestParam UUID companyId){
        return tenant.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,company_id,pay_frequency,currency_code,timezone,effective_from
            FROM payroll_configuration
            WHERE tenant_id=? AND company_id=? ORDER BY effective_from DESC LIMIT 100
            """,(rs,n)->configRow(rs),actor.tenantId(),companyId));
    }
    @PostMapping("/periods")
    @ResponseStatus(HttpStatus.CREATED)
    public Period createPeriod(@AuthenticationPrincipal HrmUserPrincipal actor,
                               @Valid @RequestBody NewPeriod input){
        if(input.endsOn().isBefore(input.startsOn())
            ||input.endsOn().isAfter(input.startsOn().plusDays(31))
            ||input.payOn().isBefore(input.startsOn())
            ||input.payOn().isAfter(input.endsOn().plusDays(31)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid bounded payroll period/payment date");
        return tenant.inTenant(actor.tenantId(),()->{
            Integer configs=jdbc.queryForObject("""
                SELECT count(*) FROM payroll_configuration
                WHERE tenant_id=? AND company_id=? AND id=? AND effective_from<=?
                """,Integer.class,actor.tenantId(),input.companyId(),
                input.configurationId(),input.startsOn());
            if(configs==null||configs!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Effective company payroll configuration not found");
            Integer overlaps=jdbc.queryForObject("""
                SELECT count(*) FROM payroll_period
                WHERE tenant_id=? AND company_id=?
                AND starts_on<=? AND ends_on>=?
                """,Integer.class,actor.tenantId(),input.companyId(),
                input.endsOn(),input.startsOn());
            if(overlaps!=null&&overlaps>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Payroll period overlaps an existing draft");
            Period period=jdbc.queryForObject("""
                INSERT INTO payroll_period
                  (tenant_id,company_id,configuration_id,starts_on,ends_on,pay_on,created_by)
                VALUES(?,?,?,?,?,?,?)
                RETURNING id,company_id,configuration_id,starts_on,ends_on,pay_on,status
                """,(rs,n)->periodRow(rs),actor.tenantId(),input.companyId(),
                input.configurationId(),input.startsOn(),input.endsOn(),input.payOn(),actor.id());
            writeAudit(actor,"PAYROLL_PERIOD_DRAFT_CREATED",period.id());
            return period;
        });
    }
    @GetMapping("/periods")
    public List<Period> periods(@AuthenticationPrincipal HrmUserPrincipal actor,
                               @RequestParam UUID companyId){
        return tenant.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,company_id,configuration_id,starts_on,ends_on,pay_on,status
            FROM payroll_period WHERE tenant_id=? AND company_id=?
            ORDER BY starts_on DESC LIMIT 100
            """,(rs,n)->periodRow(rs),actor.tenantId(),companyId));
    }
    private static Config configRow(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new Config(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getString("pay_frequency"),rs.getString("currency_code"),rs.getString("timezone"),
            rs.getObject("effective_from",LocalDate.class));
    }
    private static Period periodRow(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new Period(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getObject("configuration_id",UUID.class),rs.getObject("starts_on",LocalDate.class),
            rs.getObject("ends_on",LocalDate.class),rs.getObject("pay_on",LocalDate.class),
            rs.getString("status"),"CONFIGURATION_ONLY_NOT_PAYROLL_APPROVED");
    }
}
