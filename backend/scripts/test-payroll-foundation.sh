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
test "$DUP_CFG" = 409 || { echo "Duplicate payroll config accepted: $DUP_CFG"; exit 1; }
BAD_ZONE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","payFrequency":"MONTHLY","currencyCode":"PHP","timezone":"Not/AZone","effectiveFrom":"2026-02-01"}' "$COMPANY_ID")" "$ROOT/configurations")"
test "$BAD_ZONE" = 400 || { echo "Invalid time zone permitted: $BAD_ZONE"; exit 1; }
PERIOD_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m31-period.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","configurationId":"%s","startsOn":"2026-11-01","endsOn":"2026-11-15","payOn":"2026-11-20"}' "$COMPANY_ID" "$CFG")" "$ROOT/periods")"
test "$PERIOD_STATUS" = 201 || { echo "Draft payroll period denied: $PERIOD_STATUS"; cat /tmp/hrm-m31-period.json; exit 1; }
PERIOD="$(python3 -c 'import json;x=json.load(open("/tmp/hrm-m31-period.json"));assert x["status"]=="DRAFT" and "NOT_PAYROLL_APPROVED" in x["classification"];print(x["id"])')"
DUP_PERIOD="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","configurationId":"%s","startsOn":"2026-11-10","endsOn":"2026-11-25","payOn":"2026-11-30"}' "$COMPANY_ID" "$CFG")" "$ROOT/periods")"
test "$DUP_PERIOD" = 409 || { echo "Overlapping period accepted: $DUP_PERIOD"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/periods?companyId=$COMPANY_ID" > /tmp/hrm-m31-periods.json
python3 -c 'import json,sys;assert len([x for x in json.load(open("/tmp/hrm-m31-periods.json")) if x["id"]==sys.argv[1] and x["status"]=="DRAFT"])==1' "$PERIOD"
for path in "$ROOT/periods/$PERIOD/approve" "$ROOT/periods/$PERIOD/calculate" "$ROOT/periods/$PERIOD/release"; do
 CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$path")"
 { test "$CODE" = 403 || test "$CODE" = 404; } || { echo "Unapproved payroll action accessible: $CODE $path"; exit 1; }
done
ANON="$(curl -sS -o /dev/null -w '%{http_code}' "$ROOT/periods?companyId=$COMPANY_ID")"
{ test "$ANON" = 401 || test "$ANON" = 403; } || { echo "Anonymous payroll metadata exposed: $ANON"; exit 1; }
echo "M3.1 MFA admin payroll configuration, period isolation, overlap and no-release gate passed"
