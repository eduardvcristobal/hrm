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
