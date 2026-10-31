#!/usr/bin/env bash
# M2.8 real PostgreSQL bounded exports, receipts, and legal-hold-only retention.
# Sourced after existing MFA administrator and synthetic personnel fixtures.
: "${BASE:?}" "${BEARER:?}" "${COMPANY_ID:?}" "${EMP_ID:?}"
ROOT="$BASE/api/v1/timekeeping"
HEADER_FILE="$(mktemp)"
trap 'rm -f "$HEADER_FILE"' RETURN
ATT_HTTP="$(curl -sS -D "$HEADER_FILE" -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m28-attendance.csv -w '%{http_code}' "$ROOT/reports/attendance.csv?companyId=$COMPANY_ID&from=2026-10-01&to=2026-10-31&timezone=Asia%2FManila")"
test "$ATT_HTTP" = 200 || { echo "Attendance CSV failed: $ATT_HTTP"; cat /tmp/hrm-m28-attendance.csv; exit 1; }
grep -qi 'Content-Type: text/csv' "$HEADER_FILE"
grep -qi 'Cache-Control: no-store' "$HEADER_FILE"
python3 -c 'import csv;f=open("/tmp/hrm-m28-attendance.csv",newline=""); rows=list(csv.DictReader(f));assert rows and all(x["classification"]=="RAW_NOT_PAYROLL_APPROVED" for x in rows); assert all(x["employee_id"] for x in rows)'
ATT_NOAUTH="$(curl -sS -o /dev/null -w '%{http_code}' "$ROOT/reports/attendance.csv?companyId=$COMPANY_ID&from=2026-10-01&to=2026-10-31")"
{ test "$ATT_NOAUTH" = 401 || test "$ATT_NOAUTH" = 403; } || { echo "Anonymous CSV download allowed: $ATT_NOAUTH"; exit 1; }
ATT_LONG="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$ROOT/reports/attendance.csv?companyId=$COMPANY_ID&from=2026-01-01&to=2026-12-31")"
{ test "$ATT_LONG" = 400 || test "$ATT_LONG" = 403; } || { echo "Overlong attendance export accepted: $ATT_LONG"; exit 1; }
AUDIT_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m28-audit.csv -w '%{http_code}' "$ROOT/reports/audit.csv?from=2026-10-31&to=2026-10-31")"
test "$AUDIT_HTTP" = 200 || { echo "Audit CSV failed: $AUDIT_HTTP"; cat /tmp/hrm-m28-audit.csv; exit 1; }
python3 -c 'import csv;rows=list(csv.DictReader(open("/tmp/hrm-m28-audit.csv",newline="")));assert all(r["action"].startswith("TIME_") for r in rows)'
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/reports/export-receipts" > /tmp/hrm-m28-receipts.json
python3 -c 'import csv,hashlib,json;att=open("/tmp/hrm-m28-attendance.csv","rb").read();audit=open("/tmp/hrm-m28-audit.csv","rb").read();rows=json.load(open("/tmp/hrm-m28-receipts.json"));a=[r for r in rows if r["exportType"]=="ATTENDANCE_SUMMARY"];b=[r for r in rows if r["exportType"]=="TIMEKEEPING_AUDIT"];assert a and b;assert a[0]["contentSha256"]==hashlib.sha256(att).hexdigest();assert b[0]["contentSha256"]==hashlib.sha256(audit).hexdigest();assert a[0]["recordCount"]==len(list(csv.DictReader(open("/tmp/hrm-m28-attendance.csv",newline=""))))'
RET_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m28-plan.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","attendanceMinDays":1095,"auditMinDays":1825}' "$COMPANY_ID")" "$ROOT/retention/plans")"
test "$RET_HTTP" = 201 || { echo "Retention plan creation failed: $RET_HTTP"; cat /tmp/hrm-m28-plan.json; exit 1; }
python3 -c 'import json;x=json.load(open("/tmp/hrm-m28-plan.json"));assert x["legalHold"] is True and x["attendanceMinDays"]==1095'
RET_REPLAY="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","attendanceMinDays":365,"auditMinDays":365}' "$COMPANY_ID")" "$ROOT/retention/plans")"
{ test "$RET_REPLAY" = 403 || test "$RET_REPLAY" = 409; } || { echo "Retention plan shortening allowed: $RET_REPLAY"; exit 1; }
RET_PREVIEW="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m28-preview.json -w '%{http_code}' "$ROOT/retention/preview?companyId=$COMPANY_ID")"
test "$RET_PREVIEW" = 200 || { echo "Retention preview failed: $RET_PREVIEW"; cat /tmp/hrm-m28-preview.json; exit 1; }
python3 -c 'import json;x=json.load(open("/tmp/hrm-m28-preview.json"));assert x["legalHold"] is True and x["deletionEnabled"] is False and x["auditCandidates"]==0 and "NON_DESTRUCTIVE" in x["classification"]'
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/retention/plans?companyId=$COMPANY_ID" > /tmp/hrm-m28-plans.json
python3 -c 'import json; x=json.load(open("/tmp/hrm-m28-plans.json"));assert len(x)==1 and x[0]["legalHold"]'
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/audit-logs?limit=500" > /tmp/hrm-m28-audit-events.json
python3 -c 'import json;rows=json.load(open("/tmp/hrm-m28-audit-events.json"));assert any(x["action"]=="TIME_RETENTION_PLAN_CREATED" for x in rows);assert sum(x["action"]=="TIME_REPORT_EXPORTED" for x in rows)>=2'
echo "M2.8 bounded reports, CSV digest receipts, tenant MFA, legal-hold and no deletion integration checks passed"
