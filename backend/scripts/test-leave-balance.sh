#!/usr/bin/env bash
# M2.5.2 PostgreSQL HTTP verification, sourced after leave approval + M2.5.1 credits.
: "${BASE:?}" "${BEARER:?}" "${EMP_ID:?}" "${LEAVE_ID:?}" "${LEAVE_TYPE_ID:?}"
ROOT="$BASE/api/v1/timekeeping/employees/$EMP_ID/leave-balances"
BEFORE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-leave-before.json -w '%{http_code}' "$ROOT")"
test "$BEFORE" = 200 || { echo "Leave balance read failed: $BEFORE"; exit 1; }
python3 -c 'import json,sys; b=next(x for x in json.load(open("/tmp/hrm-leave-before.json")) if x["leaveTypeId"]==sys.argv[1]); assert float(b["accruedDays"])==1.25 and float(b["consumedDays"])==0 and float(b["remainingDays"])==1.25;assert b["payable"] is False and b["policyCertified"] is False' "$LEAVE_TYPE_ID"
DEBIT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-leave-debit.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"days":1.000,"note":"Manually reviewed chargeable leave day"}' "$ROOT/requests/$LEAVE_ID/consume")"
test "$DEBIT" = 201 || { echo "Approved leave debit failed: $DEBIT"; cat /tmp/hrm-leave-debit.json; exit 1; }
DEBIT_ID="$(python3 -c 'import json; r=json.load(open("/tmp/hrm-leave-debit.json"));assert r["eventType"]=="DEBIT" and float(r["days"])==1;print(r["id"])')"
REPLAY="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"days":1.000,"note":"Illegal repeated consumption posting"}' "$ROOT/requests/$LEAVE_ID/consume")"
{ test "$REPLAY" = 403 || test "$REPLAY" = 409; } || { echo "Duplicate leave debit accepted: $REPLAY"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT" > /tmp/hrm-leave-debited.json
python3 -c 'import json,sys; b=next(x for x in json.load(open("/tmp/hrm-leave-debited.json")) if x["leaveTypeId"]==sys.argv[1]);assert float(b["consumedDays"])==1 and float(b["remainingDays"])==0.25 and b["payable"] is False' "$LEAVE_TYPE_ID"
REVERSE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-leave-reverse.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"note":"Approved manual reconciliation reversal"}' "$ROOT/events/$DEBIT_ID/reverse")"
test "$REVERSE" = 201 || { echo "Debit reversal failed: $REVERSE"; cat /tmp/hrm-leave-reverse.json; exit 1; }
REVERSAL_ID="$(python3 -c 'import json,sys; r=json.load(open("/tmp/hrm-leave-reverse.json"));assert r["eventType"]=="REVERSAL" and r["reversesEventId"]==sys.argv[1];print(r["id"])' "$DEBIT_ID")"
REVERSE_AGAIN="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"note":"Illegal repeat reversal attempted"}' "$ROOT/events/$DEBIT_ID/reverse")"
{ test "$REVERSE_AGAIN" = 403 || test "$REVERSE_AGAIN" = 409; } || { echo "Repeat reversal accepted: $REVERSE_AGAIN"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT" > /tmp/hrm-leave-restored.json
python3 -c 'import json,sys; b=next(x for x in json.load(open("/tmp/hrm-leave-restored.json")) if x["leaveTypeId"]==sys.argv[1]);assert float(b["consumedDays"])==0 and float(b["remainingDays"])==1.25' "$LEAVE_TYPE_ID"
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/events?leaveTypeId=$LEAVE_TYPE_ID" > /tmp/hrm-leave-events.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-leave-events.json"));assert {sys.argv[1],sys.argv[2]}.issubset({v["id"] for v in a}) and len(a)==2' "$DEBIT_ID" "$REVERSAL_ID"
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/audit-logs?limit=500" > /tmp/hrm-leave-journal-audit.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-leave-journal-audit.json"));assert all(any(x["entityId"]==i and x["action"]==action for x in a) for i,action in [(sys.argv[1],"TIME_LEAVE_BALANCE_DEBIT"),(sys.argv[2],"TIME_LEAVE_BALANCE_REVERSAL")])' "$DEBIT_ID" "$REVERSAL_ID"
echo "M2.5.2 approved consumption, reversal, duplicate protection and audit HTTP regression passed"
