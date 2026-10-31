#!/usr/bin/env bash
# M2.6 live PostgreSQL calendar classification regression.
# Sourced after M2.1 creates an assigned weekday shift and valid employee.
: "${BASE:?}" "${BEARER:?}" "${COMPANY_ID:?}" "${EMP_ID:?}" "${TIME_SHIFT_ID:?}"
ROOT="$BASE/api/v1/timekeeping"
CAL_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-cal-day.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","date":"2026-11-02","kind":"HOLIDAY","title":"CI employer holiday"}' "$COMPANY_ID")" "$ROOT/calendar-days")"
test "$CAL_STATUS" = 201 || { echo "M2.6 calendar creation failed: $CAL_STATUS"; cat /tmp/hrm-m2-cal-day.json; exit 1; }
CAL_ID="$(python3 -c 'import json; x=json.load(open("/tmp/hrm-m2-cal-day.json"));assert x["kind"]=="HOLIDAY";print(x["id"])')"
DUP_CAL="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","date":"2026-11-02","kind":"COMPANY_CLOSURE","title":"Collision"}' "$COMPANY_ID")" "$ROOT/calendar-days")"
{ test "$DUP_CAL" = 403 || test "$DUP_CAL" = 409; } || { echo "M2.6 duplicate calendar entry accepted: $DUP_CAL"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/calendar-days?companyId=$COMPANY_ID&from=2026-11-01&to=2026-11-10" > /tmp/hrm-m2-calendar-list.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-m2-calendar-list.json"));assert len(a)==1 and a[0]["id"]==sys.argv[1]' "$CAL_ID"
OVERRIDE_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-day-override.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"date":"2026-11-02","kind":"REST_DAY","reason":"Approved employee rest-day exception"}' "$ROOT/employees/$EMP_ID/day-overrides")"
test "$OVERRIDE_STATUS" = 201 || { echo "M2.6 override creation failed: $OVERRIDE_STATUS"; cat /tmp/hrm-m2-day-override.json; exit 1; }
OVERRIDE_ID="$(python3 -c 'import json; x=json.load(open("/tmp/hrm-m2-day-override.json"));assert x["kind"]=="REST_DAY";print(x["id"])')"
DUP_OVERRIDE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"date":"2026-11-02","kind":"WORK_DAY","reason":"Unauthorized replacement attempt"}' "$ROOT/employees/$EMP_ID/day-overrides")"
{ test "$DUP_OVERRIDE" = 403 || test "$DUP_OVERRIDE" = 409; } || { echo "M2.6 duplicate override accepted: $DUP_OVERRIDE"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/employees/$EMP_ID/day-overrides?from=2026-11-01&to=2026-11-10" > /tmp/hrm-m2-override-list.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-m2-override-list.json"));assert len(a)==1 and a[0]["id"]==sys.argv[1]' "$OVERRIDE_ID"
PREVIEW_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-calendar-preview.json -w '%{http_code}' "$ROOT/employees/$EMP_ID/calendar-preview?from=2026-11-02&to=2026-11-08")"
test "$PREVIEW_STATUS" = 200 || { echo "M2.6 preview failed: $PREVIEW_STATUS"; cat /tmp/hrm-m2-calendar-preview.json; exit 1; }
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-m2-calendar-preview.json")); assert len(a)==7; monday=a[0]; friday=a[4];saturday=a[5];assert monday["date"]=="2026-11-02" and monday["companyDayKind"]=="HOLIDAY" and monday["employeeOverride"]=="REST_DAY" and monday["restDay"] is True and monday["scheduled"] is False;assert friday["scheduled"] is True and friday["shiftId"]==sys.argv[1];assert saturday["restDay"] is True and saturday["scheduleStatus"]=="REST_DAY";assert all(x["provisional"] and "NO_LEGAL" in x["classification"] for x in a)' "$TIME_SHIFT_ID"
LONG_RANGE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$ROOT/employees/$EMP_ID/calendar-preview?from=2026-01-01&to=2026-12-31")"
{ test "$LONG_RANGE" = 400 || test "$LONG_RANGE" = 403; } || { echo "M2.6 uncapped range exposed: $LONG_RANGE"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/audit-logs?limit=500" > /tmp/hrm-m2-calendar-audit.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-m2-calendar-audit.json"));assert any(x["entityId"]==sys.argv[1] and x["action"]=="TIME_COMPANY_CALENDAR_CREATED" for x in a);assert any(x["entityId"]==sys.argv[2] and x["action"]=="TIME_EMPLOYEE_DAY_OVERRIDE_CREATED" for x in a)' "$CAL_ID" "$OVERRIDE_ID"
echo "M2.6 holiday, rest-day override, schedule, range cap and audit HTTP regression passed"
