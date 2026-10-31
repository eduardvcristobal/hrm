#!/usr/bin/env bash
# Sourced by test-api-security.sh after a real tenant admin MFA token and personnel fixture exist.
: "${BEARER:?}" "${BASE:?}" "${COMPANY_ID:?}" "${EMP_ID:?}"
TIME_SHIFT_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-shift.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","code":"CI_NIGHT","name":"CI overnight shift","start":"22:00:00","end":"06:00:00","timezone":"Asia/Manila","unpaidBreakMinutes":60,"graceMinutes":5}' "$COMPANY_ID")" "$BASE/api/v1/timekeeping/shifts")"
test "$TIME_SHIFT_CODE" = 201 || { echo "Time shift failed: $TIME_SHIFT_CODE"; cat /tmp/hrm-m2-shift.json; exit 1; }
TIME_SHIFT_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-m2-shift.json"))["id"])')"
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/timekeeping/shifts?companyId=$COMPANY_ID" | grep -q 'CI_NIGHT'
SCHEDULE_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-schedule.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"shiftId":"%s","effectiveFrom":"2026-11-01","effectiveUntil":"2026-12-31","weekdaysMask":31}' "$TIME_SHIFT_ID")" "$BASE/api/v1/timekeeping/employees/$EMP_ID/schedules")"
test "$SCHEDULE_CODE" = 201 || { echo "Schedule failed: $SCHEDULE_CODE"; cat /tmp/hrm-m2-schedule.json; exit 1; }
OVERLAP_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"shiftId":"%s","effectiveFrom":"2026-11-15","effectiveUntil":"2026-12-15","weekdaysMask":1}' "$TIME_SHIFT_ID")" "$BASE/api/v1/timekeeping/employees/$EMP_ID/schedules")"
{ test "$OVERLAP_CODE" = 403 || test "$OVERLAP_CODE" = 409; } || { echo "Overlapping weekday schedule accepted: $OVERLAP_CODE"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/timekeeping/employees/$EMP_ID/schedules" | grep -q "$TIME_SHIFT_ID"
ANON_TIME_CODE="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/schedules")"
{ test "$ANON_TIME_CODE" = 401 || test "$ANON_TIME_CODE" = 403; } || { echo "Anonymous attendance exposed: $ANON_TIME_CODE"; exit 1; }
echo "M2.1 shift and schedule HTTP regression passed"


CLOCK_IN="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-clock-in.json -w '%{http_code}' -X POST "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance/clock-in")"
test "$CLOCK_IN" = 201 || { echo "Clock-in failed: $CLOCK_IN"; cat /tmp/hrm-clock-in.json; exit 1; }
SESSION_ID="$(python3 -c 'import json; x=json.load(open("/tmp/hrm-clock-in.json"));assert x["clockInAt"] and not x["clockOutAt"];print(x["id"])')"
DUP_IN="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance/clock-in")"
{ test "$DUP_IN" = 403 || test "$DUP_IN" = 409; } || { echo "Double clock-in permitted: $DUP_IN"; exit 1; }
CLOCK_OUT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-clock-out.json -w '%{http_code}' -X POST "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance/$SESSION_ID/clock-out")"
test "$CLOCK_OUT" = 200 || { echo "Clock-out failed: $CLOCK_OUT"; cat /tmp/hrm-clock-out.json; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-clock-out.json"));assert x["clockOutAt"] and x["clockOutBy"]'
DUP_OUT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance/$SESSION_ID/clock-out")"
{ test "$DUP_OUT" = 403 || test "$DUP_OUT" = 409; } || { echo "Double clock-out permitted: $DUP_OUT"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance" | grep -q "$SESSION_ID"
echo "M2.2 attendance clock-in/out regression passed"


SUMMARY_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-attendance-summary.json -w '%{http_code}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/summaries?from=2026-10-01&to=2026-10-31&timezone=Asia%2FManila")"
test "$SUMMARY_STATUS" = 200 || { echo "Attendance summary failed: $SUMMARY_STATUS"; cat /tmp/hrm-attendance-summary.json; exit 1; }
python3 -c 'import json; data=json.load(open("/tmp/hrm-attendance-summary.json"));assert isinstance(data,list)'
BAD_SUMMARY="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/summaries?from=2026-01-01&to=2026-12-31")"
{ test "$BAD_SUMMARY" = 400 || test "$BAD_SUMMARY" = 403; } || { echo "Overlong report accepted: $BAD_SUMMARY"; exit 1; }
BAD_ZONE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/summaries?from=2026-10-01&to=2026-10-31&timezone=BadZone")"
{ test "$BAD_ZONE" = 400 || test "$BAD_ZONE" = 403; } || { echo "Invalid timezone accepted: $BAD_ZONE"; exit 1; }


CORRECTION_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-correction.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"clockInAt":"2026-10-29T08:00:00Z","clockOutAt":"2026-10-29T17:00:00Z","reason":"Attendance time adjustment test"}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance/$SESSION_ID/corrections")"
test "$CORRECTION_STATUS" = 201 || { echo "Correction request failed: $CORRECTION_STATUS"; cat /tmp/hrm-correction.json; exit 1; }
CORRECTION_ID="$(python3 -c 'import json; x=json.load(open("/tmp/hrm-correction.json"));assert x["status"]=="PENDING";print(x["id"])')"
CORRECTION_SELF="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance/$SESSION_ID/corrections/$CORRECTION_ID/decision?decision=APPROVED")"
test "$CORRECTION_SELF" = 403 || { echo "Self approval of attendance correction accepted: $CORRECTION_SELF"; exit 1; }
DUP_CORRECTION="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"clockInAt":"2026-10-29T09:00:00Z","clockOutAt":"2026-10-29T18:00:00Z","reason":"Duplicate correction attempt"}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance/$SESSION_ID/corrections")"
{ test "$DUP_CORRECTION" = 403 || test "$DUP_CORRECTION" = 409; } || { echo "Concurrent pending correction allowed: $DUP_CORRECTION"; exit 1; }

# M2.3 non-payroll variance preview must reflect the effective dated shift,
# and must not silently classify unreviewed time as payable or approved overtime.
VARIANCE_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-variance-preview.json -w '%{http_code}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/variance-preview?from=2026-11-02&to=2026-11-02")"
test "$VARIANCE_STATUS" = 200 || { echo "Variance preview failed: $VARIANCE_STATUS"; cat /tmp/hrm-variance-preview.json; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-variance-preview.json")); assert len(x)==1, x; r=x[0]; assert r["provisional"] and r["classification"]=="INFORMATIONAL_NOT_PAYROLL_APPROVED"; assert r["timezone"]=="Asia/Manila"; assert r["variance"]["scheduledMinutes"]==480'
VARIANCE_LONG="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/variance-preview?from=2026-01-01&to=2026-12-31")"
{ test "$VARIANCE_LONG" = 400 || test "$VARIANCE_LONG" = 403; } || { echo "Overlong variance preview accepted: $VARIANCE_LONG"; exit 1; }
echo "M2.3 schedule variance HTTP regression passed"

# M2.4 independent, MFA-verified reviewer positive path.
: "${SECOND_BEARER:?Second MFA-verified reviewer token missing}"
CORRECTION_PATH="$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance/$SESSION_ID/corrections"
APPROVED_CODE="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /tmp/hrm-m2-approved.json -w '%{http_code}' -X POST "$CORRECTION_PATH/$CORRECTION_ID/decision?decision=APPROVED")"
test "$APPROVED_CODE" = 200 || { echo "Independent attendance approval failed: $APPROVED_CODE"; cat /tmp/hrm-m2-approved.json; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-m2-approved.json")); assert x["status"]=="APPROVED" and x["requestedBy"]!=x["reviewedBy"]'
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance" > /tmp/hrm-m2-approved-sessions.json
python3 -c 'import json,sys; x=next(i for i in json.load(open("/tmp/hrm-m2-approved-sessions.json")) if i["id"]==sys.argv[1]); assert x["clockInAt"]=="2026-10-29T08:00:00Z" and x["clockOutAt"]=="2026-10-29T17:00:00Z"' "$SESSION_ID"
REPLAY_DECISION="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /dev/null -w '%{http_code}' -X POST "$CORRECTION_PATH/$CORRECTION_ID/decision?decision=APPROVED")"
{ test "$REPLAY_DECISION" = 409 || test "$REPLAY_DECISION" = 403; } || { echo "Repeated approval allowed: $REPLAY_DECISION"; exit 1; }
REJECT_REQUEST="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-reject-pending.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"clockInAt":"2026-10-29T09:00:00Z","clockOutAt":"2026-10-29T18:00:00Z","reason":"Independent rejection regression"}' "$CORRECTION_PATH")"
test "$REJECT_REQUEST" = 201 || { echo "Rejection fixture failed: $REJECT_REQUEST"; cat /tmp/hrm-m2-reject-pending.json; exit 1; }
REJECT_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-m2-reject-pending.json"))["id"])')"
REJECT_CODE="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /tmp/hrm-m2-rejected.json -w '%{http_code}' -X POST "$CORRECTION_PATH/$REJECT_ID/decision?decision=REJECTED")"
test "$REJECT_CODE" = 200 || { echo "Independent rejection failed: $REJECT_CODE"; cat /tmp/hrm-m2-rejected.json; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-m2-rejected.json"));assert x["status"]=="REJECTED" and x["requestedBy"]!=x["reviewedBy"]'
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance" > /tmp/hrm-m2-rejected-sessions.json
python3 -c 'import json,sys; x=next(i for i in json.load(open("/tmp/hrm-m2-rejected-sessions.json")) if i["id"]==sys.argv[1]); assert x["clockInAt"]=="2026-10-29T08:00:00Z" and x["clockOutAt"]=="2026-10-29T17:00:00Z"' "$SESSION_ID"
echo "M2.4 independent approval/rejection and replay HTTP regression passed"

# M2.6 provisional calendar classification uses the above employee and shift fixtures.
source scripts/test-calendar-policy.sh
