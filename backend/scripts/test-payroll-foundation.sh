#!/usr/bin/env bash
# M3.1 live PostgreSQL HTTP gate, sourced before admin bearer is revoked.
: "${BASE:?}" "${BEARER:?}" "${COMPANY_ID:?}" "${DB_PASSWORD:?}"
ROOT="$BASE/api/v1/payroll"
CREATE_CFG="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m31-config.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","payFrequency":"SEMI_MONTHLY","currencyCode":"PHP","timezone":"Asia/Manila","effectiveFrom":"2026-01-01"}' "$COMPANY_ID")" "$ROOT/configurations")"
test "$CREATE_CFG" = 201 || { echo "Payroll configuration denied: $CREATE_CFG"; cat /tmp/hrm-m31-config.json; exit 1; }
CFG="$(python3 -c 'import json;x=json.load(open("/tmp/hrm-m31-config.json"));assert x["payFrequency"]=="SEMI_MONTHLY";print(x["id"])')"
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/configurations?companyId=$COMPANY_ID" > /tmp/hrm-m31-configs.json
python3 -c 'import json,sys;assert any(x["id"]==sys.argv[1] for x in json.load(open("/tmp/hrm-m31-configs.json")))' "$CFG"
DUP_CFG="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","payFrequency":"MONTHLY","currencyCode":"PHP","timezone":"Asia/Manila","effectiveFrom":"2026-01-01"}' "$COMPANY_ID")" "$ROOT/configurations")"
{ test "$DUP_CFG" = 403 || test "$DUP_CFG" = 409; } || { echo "Duplicate payroll config accepted: $DUP_CFG"; exit 1; }
BAD_ZONE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","payFrequency":"MONTHLY","currencyCode":"PHP","timezone":"Not/AZone","effectiveFrom":"2026-02-01"}' "$COMPANY_ID")" "$ROOT/configurations")"
{ test "$BAD_ZONE" = 400 || test "$BAD_ZONE" = 403; } || { echo "Invalid time zone permitted: $BAD_ZONE"; exit 1; }
PERIOD_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m31-period.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","configurationId":"%s","startsOn":"2026-11-01","endsOn":"2026-11-15","payOn":"2026-11-20"}' "$COMPANY_ID" "$CFG")" "$ROOT/periods")"
test "$PERIOD_STATUS" = 201 || { echo "Draft payroll period denied: $PERIOD_STATUS"; cat /tmp/hrm-m31-period.json; exit 1; }
PERIOD="$(python3 -c 'import json;x=json.load(open("/tmp/hrm-m31-period.json"));assert x["status"]=="DRAFT" and "NOT_PAYROLL_APPROVED" in x["classification"];print(x["id"])')"
DUP_PERIOD="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","configurationId":"%s","startsOn":"2026-11-10","endsOn":"2026-11-25","payOn":"2026-11-30"}' "$COMPANY_ID" "$CFG")" "$ROOT/periods")"
{ test "$DUP_PERIOD" = 403 || test "$DUP_PERIOD" = 409; } || { echo "Overlapping period accepted: $DUP_PERIOD"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/periods?companyId=$COMPANY_ID" > /tmp/hrm-m31-periods.json
python3 -c 'import json,sys;assert len([x for x in json.load(open("/tmp/hrm-m31-periods.json")) if x["id"]==sys.argv[1] and x["status"]=="DRAFT"])==1' "$PERIOD"
for path in "$ROOT/periods/$PERIOD/approve" "$ROOT/periods/$PERIOD/calculate" "$ROOT/periods/$PERIOD/release"; do
 CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$path")"
 { test "$CODE" = 403 || test "$CODE" = 404; } || { echo "Unapproved payroll action accessible: $CODE $path"; exit 1; }
done
ANON="$(curl -sS -o /dev/null -w '%{http_code}' "$ROOT/periods?companyId=$COMPANY_ID")"
{ test "$ANON" = 401 || test "$ANON" = 403; } || { echo "Anonymous payroll metadata exposed: $ANON"; exit 1; }
echo "M3.1 MFA admin payroll configuration, period isolation, overlap and no-release gate passed"
# M3.2: catalogue describes components but never applies rates or amounts.
COMPONENT_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m32-component.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","componentCode":"BASIC_PAY","componentName":"Basic pay","componentKind":"EARNING","effectiveFrom":"2026-01-01","description":"Metadata only"}' "$COMPANY_ID")" "$ROOT/components")"
test "$COMPONENT_STATUS" = 201 || { echo "Earning component creation failed: $COMPONENT_STATUS"; cat /tmp/hrm-m32-component.json; exit 1; }
COMPONENT_ID="$(python3 -c 'import json;x=json.load(open("/tmp/hrm-m32-component.json"));assert x["treatment"]=="UNREVIEWED" and "NO_PAYROLL_CALCULATION" in x["classification"];assert "amount" not in x and "rate" not in x;print(x["id"])')"
DEDUCTION_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m32-deduction.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","componentCode":"OTHER_DEDUCTION","componentName":"Other deduction","componentKind":"DEDUCTION","effectiveFrom":"2026-01-01"}' "$COMPANY_ID")" "$ROOT/components")"
test "$DEDUCTION_STATUS" = 201 || { echo "Deduction definition failed: $DEDUCTION_STATUS"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/components?companyId=$COMPANY_ID" > /tmp/hrm-m32-components.json
python3 -c 'import json,sys;items=json.load(open("/tmp/hrm-m32-components.json"));assert len(items)==2 and any(x["id"]==sys.argv[1] for x in items);assert all(x["treatment"]=="UNREVIEWED" and "amount" not in x for x in items)' "$COMPONENT_ID"
DUP_COMPONENT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","componentCode":"BASIC_PAY","componentName":"Duplicate basic pay","componentKind":"EARNING","effectiveFrom":"2026-01-01"}' "$COMPANY_ID")" "$ROOT/components")"
{ test "$DUP_COMPONENT" = 403 || test "$DUP_COMPONENT" = 409; } || { echo "Duplicate component version accepted: $DUP_COMPONENT"; exit 1; }
INVALID_KIND="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","componentCode":"BAD_TYPE","componentName":"Bad component","componentKind":"STATUTORY_AUTO","effectiveFrom":"2026-01-01"}' "$COMPANY_ID")" "$ROOT/components")"
{ test "$INVALID_KIND" = 400 || test "$INVALID_KIND" = 403; } || { echo "Unsupported statutory auto classification accepted: $INVALID_KIND"; exit 1; }
MUTATE_COMPONENT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X PATCH -H 'Content-Type: application/json' --data '{"componentName":"Modified"}' "$ROOT/components/$COMPONENT_ID")"
{ test "$MUTATE_COMPONENT" = 403 || test "$MUTATE_COMPONENT" = 404; } || { echo "Immutable component was changed: $MUTATE_COMPONENT"; exit 1; }
echo "M3.2 immutable earning/deduction definitions, tenant scope and no-amount gate passed"

