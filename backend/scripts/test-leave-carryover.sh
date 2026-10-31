#!/usr/bin/env bash
# M2.5.2: carryover planning only, no balance mutation.
: "${BASE:?}" "${BEARER:?}" "${EMP_ID:?}" "${LEAVE_TYPE_ID:?}"
ROOT="$BASE/api/v1/timekeeping"
DEST_YEAR="$(( $(date -u +%Y) + 1 ))"
POLICY_RESULT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-carryover-policy.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","destinationYear":%s,"maximumDays":0.500}' "$LEAVE_TYPE_ID" "$DEST_YEAR")" "$ROOT/leave-carryover-preview-policies")"
test "$POLICY_RESULT" = 201 || { echo "Carryover preview policy create failed: $POLICY_RESULT"; cat /tmp/hrm-carryover-policy.json; exit 1; }
python3 -c 'import json,sys; x=json.load(open("/tmp/hrm-carryover-policy.json")); assert x["destinationYear"]==int(sys.argv[1]) and float(x["maximumDays"])==0.5' "$DEST_YEAR"
DUP_POLICY="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","destinationYear":%s,"maximumDays":1.000}' "$LEAVE_TYPE_ID" "$DEST_YEAR")" "$ROOT/leave-carryover-preview-policies")"
{ test "$DUP_POLICY" = 403 || test "$DUP_POLICY" = 409; } || { echo "Duplicate carryover preview policy accepted: $DUP_POLICY"; exit 1; }
PREVIEW_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-carryover-preview.json -w '%{http_code}' "$ROOT/employees/$EMP_ID/leave-carryover-preview?leaveTypeId=$LEAVE_TYPE_ID&destinationYear=$DEST_YEAR")"
test "$PREVIEW_HTTP" = 200 || { echo "Carryover preview failed: $PREVIEW_HTTP"; cat /tmp/hrm-carryover-preview.json; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-carryover-preview.json"));assert float(x["currentUnsettledCredits"])==1.25 and float(x["policyCap"])==0.5 and float(x["proposedCarryoverDays"])==0.5 and x["posted"] is False and "NOT_POSTED" in x["classification"]'
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/employees/$EMP_ID/leave-balances" > /tmp/hrm-carryover-unchanged.json
python3 -c 'import json,sys; x=next(i for i in json.load(open("/tmp/hrm-carryover-unchanged.json")) if i["leaveTypeId"]==sys.argv[1]);assert float(x["remainingDays"])==1.25' "$LEAVE_TYPE_ID"
echo "M2.5.2 carryover preview cap, duplicate denial and no-mutation regression passed"
