#!/usr/bin/env bash
# M2.7: live PostgreSQL HTTP certification of INTERNAL-ONLY reconciliation.
: "${BASE:?}" "${BEARER:?}" "${SECOND_BEARER:?}" "${EMP_ID:?}"
ROOT="$BASE/api/v1/timekeeping/employees/$EMP_ID/payroll-reconciliation"
PREVIEW="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m27-preview.json -w '%{http_code}' "$ROOT/preview?from=2026-10-15&to=2026-11-13&timezone=Asia%2FManila")"
test "$PREVIEW" = 200 || { echo "M2.7 preview failed: $PREVIEW"; cat /tmp/hrm-m27-preview.json; exit 1; }
python3 -c 'import json;x=json.load(open("/tmp/hrm-m27-preview.json"));s=x["summary"];assert x["payrollReady"] is False and "NOT_PAYROLL" in x["classification"]; assert s["approvedLeaveRequests"]>=1 and s["unconsumedApprovedLeave"]>=1 and s["calendarEvents"]>=1; assert "UNRECONCILED_APPROVED_LEAVE" in s["blockerCodes"] and "CALENDAR_POLICY_UNCERTIFIED" in s["blockerCodes"]; assert len(s["sourceFingerprint"])==64'
BAD_RANGE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$ROOT/preview?from=2026-01-01&to=2026-12-31")"
{ test "$BAD_RANGE" = 400 || test "$BAD_RANGE" = 403; } || { echo "Overlong reconciliation allowed: $BAD_RANGE"; exit 1; }
BAD_ZONE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$ROOT/preview?from=2026-10-15&to=2026-11-13&timezone=BadZone")"
{ test "$BAD_ZONE" = 400 || test "$BAD_ZONE" = 403; } || { echo "Unknown timezone allowed: $BAD_ZONE"; exit 1; }
DRAFT_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m27-draft.json -w '%{http_code}' -X POST "$ROOT/packages?from=2026-10-15&to=2026-11-13&timezone=Asia%2FManila")"
test "$DRAFT_CODE" = 201 || { echo "M2.7 draft creation failed: $DRAFT_CODE"; cat /tmp/hrm-m27-draft.json; exit 1; }
DRAFT_ID="$(python3 -c 'import json; x=json.load(open("/tmp/hrm-m27-draft.json"));assert x["status"]=="DRAFT" and x["payrollReady"] is False;print(x["id"])')"
DUPLICATE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$ROOT/packages?from=2026-10-15&to=2026-11-13&timezone=Asia%2FManila")"
{ test "$DUPLICATE" = 403 || test "$DUPLICATE" = 409; } || { echo "Repeated reconciliation package accepted: $DUPLICATE"; exit 1; }
SELF_REVIEW="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$ROOT/packages/$DRAFT_ID/review")"
test "$SELF_REVIEW" = 403 || { echo "Self review accepted: $SELF_REVIEW"; exit 1; }
BLOCKED_REVIEW="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /dev/null -w '%{http_code}' -X POST "$ROOT/packages/$DRAFT_ID/review")"
{ test "$BLOCKED_REVIEW" = 403 || test "$BLOCKED_REVIEW" = 409; } || { echo "Blocked package reviewed: $BLOCKED_REVIEW"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/packages" > /tmp/hrm-m27-list.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-m27-list.json"));x=next(i for i in a if i["id"]==sys.argv[1]);assert x["status"]=="DRAFT" and x["payrollReady"] is False' "$DRAFT_ID"
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/audit-logs?limit=500" > /tmp/hrm-m27-audit.json
python3 -c 'import json,sys;a=json.load(open("/tmp/hrm-m27-audit.json"));assert any(x["entityId"]==sys.argv[1] and x["action"]=="TIME_RECONCILIATION_PREPARED" for x in a)' "$DRAFT_ID"
echo "M2.7 internal reconciliation blockers, source fingerprint, independent review denial and audit passed"
