#!/usr/bin/env bash
# M2.8 bounded CSV exports and non-destructive retention planning on real PostgreSQL.
: "${BASE:?}" "${BEARER:?}" "${COMPANY_ID:?}" "${EMP_ID:?}"
ROOT="$BASE/api/v1/timekeeping"
ATTENDANCE_CODE="$(curl -sS -D /tmp/hrm-m28-attendance-headers -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m28-attendance.csv -w '%{http_code}' "$ROOT/reports/attendance.csv?companyId=$COMPANY_ID&from=2026-10-31&to=2026-10-31&timezone=Asia%2FManila")"
test "$ATTENDANCE_CODE" = 200 || { echo "M2.8 attendance export failed: $ATTENDANCE_CODE"; cat /tmp/hrm-m28-attendance.csv; exit 1; }
grep -qi 'Content-Disposition: attachment' /tmp/hrm-m28-attendance-headers
grep -qi 'Cache-Control: no-store' /tmp/hrm-m28-attendance-headers
python3 -c 'import csv; r=list(csv.DictReader(open("/tmp/hrm-m28-attendance.csv")));assert r;assert all(x["classification"]=="RAW_NOT_PAYROLL_APPROVED" for x in r);assert all("@" not in str(v) for x in r for v in x.values());assert all(x["employee_id"] for x in r)'
BAD_RANGE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$ROOT/reports/attendance.csv?companyId=$COMPANY_ID&from=2026-01-01&to=2026-12-31")"
{ test "$BAD_RANGE" = 400 || test "$BAD_RANGE" = 403; } || { echo "Unbounded attendance export accepted: $BAD_RANGE"; exit 1; }
BAD_ZONE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$ROOT/reports/attendance.csv?companyId=$COMPANY_ID&from=2026-10-31&to=2026-10-31&timezone=BadZone")"
{ test "$BAD_ZONE" = 400 || test "$BAD_ZONE" = 403; } || { echo "Bad timezone export accepted: $BAD_ZONE"; exit 1; }
ANON_EXPORT="$(curl -sS -o /dev/null -w '%{http_code}' "$ROOT/reports/attendance.csv?companyId=$COMPANY_ID&from=2026-10-31&to=2026-10-31")"
{ test "$ANON_EXPORT" = 401 || test "$ANON_EXPORT" = 403; } || { echo "Unauthenticated attendance exported: $ANON_EXPORT"; exit 1; }
AUDIT_CODE="$(curl -sS -D /tmp/hrm-m28-audit-headers -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m28-audit.csv -w '%{http_code}' "$ROOT/reports/audit.csv?from=2026-10-31&to=2026-10-31")"
test "$AUDIT_CODE" = 200 || { echo "M2.8 audit export failed: $AUDIT_CODE"; cat /tmp/hrm-m28-audit.csv; exit 1; }
python3 -c 'import csv; r=list(csv.DictReader(open("/tmp/hrm-m28-audit.csv")));assert r;assert all(x["action"].startswith("TIME_") for x in r);assert all("actor_email" not in x for x in r)'
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/reports/export-receipts" > /tmp/hrm-m28-receipts.json
python3 -c 'import json,csv,hashlib; receipts=json.load(open("/tmp/hrm-m28-receipts.json")); assert any(x["exportType"]=="ATTENDANCE_SUMMARY" and x["contentSha256"]==hashlib.sha256(open("/tmp/hrm-m28-attendance.csv","rb").read()).hexdigest() for x in receipts);assert any(x["exportType"]=="TIMEKEEPING_AUDIT" and len(x["contentSha256"])==64 for x in receipts)'
RETENTION_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m28-retention.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","attendanceMinDays":730,"auditMinDays":2555}' "$COMPANY_ID")" "$ROOT/retention/plans")"
test "$RETENTION_CODE" = 201 || { echo "M2.8 retention plan failed: $RETENTION_CODE"; cat /tmp/hrm-m28-retention.json; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-m28-retention.json")); assert x["legalHold"] is True and x["attendanceMinDays"]==730'
DUP_PLAN="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","attendanceMinDays":365,"auditMinDays":365}' "$COMPANY_ID")" "$ROOT/retention/plans")"
{ test "$DUP_PLAN" = 403 || test "$DUP_PLAN" = 409; } || { echo "Retention shortening accepted: $DUP_PLAN"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/retention/preview?companyId=$COMPANY_ID" > /tmp/hrm-m28-retention-preview.json
python3 -c 'import json; x=json.load(open("/tmp/hrm-m28-retention-preview.json"));assert x["legalHold"] is True and x["deletionEnabled"] is False and x["auditCandidates"]==0 and "NON_DESTRUCTIVE" in x["classification"]'
ANON_PREVIEW="$(curl -sS -o /dev/null -w '%{http_code}' "$ROOT/retention/preview?companyId=$COMPANY_ID")"
{ test "$ANON_PREVIEW" = 401 || test "$ANON_PREVIEW" = 403; } || { echo "Unauthenticated retention preview accessible: $ANON_PREVIEW"; exit 1; }
echo "M2.8 bounded exports, audit receipts, privacy and non-destructive retention HTTP regression passed"
