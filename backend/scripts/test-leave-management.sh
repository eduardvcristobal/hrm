#!/usr/bin/env bash
# Sourced after M2.4 regression from test-api-security.sh, with two
# distinct MFA-verified administrators and a real employee fixture.
: "${BASE:?}" "${BEARER:?}" "${SECOND_BEARER:?}" "${COMPANY_ID:?}" "${EMP_ID:?}"
LEAVE_BASE="$BASE/api/v1/timekeeping"
TYPE_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-leave-type.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","code":"PERSONAL","name":"Personal leave"}' "$COMPANY_ID")" "$LEAVE_BASE/leave-types")"
test "$TYPE_CODE" = 201 || { echo "Leave type creation failed: $TYPE_CODE"; cat /tmp/hrm-m2-leave-type.json; exit 1; }
LEAVE_TYPE_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-m2-leave-type.json"))["id"])')"
curl -fsS -H "Authorization: Bearer $BEARER" "$LEAVE_BASE/leave-types?companyId=$COMPANY_ID" | grep -q 'PERSONAL'
CREATE_LEAVE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-leave.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","from":"2026-11-09","to":"2026-11-11","reason":"Leave lifecycle integration fixture"}' "$LEAVE_TYPE_ID")" "$LEAVE_BASE/employees/$EMP_ID/leave-requests")"
test "$CREATE_LEAVE" = 201 || { echo "Leave request failed: $CREATE_LEAVE"; cat /tmp/hrm-m2-leave.json; exit 1; }
LEAVE_ID="$(python3 -c 'import json; x=json.load(open("/tmp/hrm-m2-leave.json"));assert x["status"]=="PENDING";print(x["id"])')"
SELF_LEAVE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$LEAVE_BASE/employees/$EMP_ID/leave-requests/$LEAVE_ID/decision?decision=APPROVED")"
test "$SELF_LEAVE" = 403 || { echo "Self leave approval accepted: $SELF_LEAVE"; exit 1; }
OVERLAP_LEAVE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","from":"2026-11-10","to":"2026-11-12","reason":"This request must conflict"}' "$LEAVE_TYPE_ID")" "$LEAVE_BASE/employees/$EMP_ID/leave-requests")"
{ test "$OVERLAP_LEAVE" = 403 || test "$OVERLAP_LEAVE" = 409; } || { echo "Overlapping leave request accepted: $OVERLAP_LEAVE"; exit 1; }
REVIEW_LEAVE="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /tmp/hrm-m2-leave-approved.json -w '%{http_code}' -X POST "$LEAVE_BASE/employees/$EMP_ID/leave-requests/$LEAVE_ID/decision?decision=APPROVED")"
test "$REVIEW_LEAVE" = 200 || { echo "Independent leave approval failed: $REVIEW_LEAVE"; cat /tmp/hrm-m2-leave-approved.json; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-m2-leave-approved.json")); assert x["status"]=="APPROVED" and x["requestedBy"]!=x["reviewedBy"]'
REPLAY_LEAVE="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /dev/null -w '%{http_code}' -X POST "$LEAVE_BASE/employees/$EMP_ID/leave-requests/$LEAVE_ID/decision?decision=APPROVED")"
{ test "$REPLAY_LEAVE" = 403 || test "$REPLAY_LEAVE" = 409; } || { echo "Leave approval replay accepted: $REPLAY_LEAVE"; exit 1; }
CANCEL_APPROVED="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$LEAVE_BASE/employees/$EMP_ID/leave-requests/$LEAVE_ID/cancel")"
{ test "$CANCEL_APPROVED" = 403 || test "$CANCEL_APPROVED" = 409; } || { echo "Approved leave cancelled without reversal: $CANCEL_APPROVED"; exit 1; }
CREATE_CANCEL="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-leave-cancel.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","from":"2026-11-16","to":"2026-11-17","reason":"Leave cancellation integration fixture"}' "$LEAVE_TYPE_ID")" "$LEAVE_BASE/employees/$EMP_ID/leave-requests")"
test "$CREATE_CANCEL" = 201 || { echo "Cancellation leave fixture failed: $CREATE_CANCEL"; exit 1; }
CANCEL_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-m2-leave-cancel.json"))["id"])')"
CANCEL_RESULT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-leave-cancelled.json -w '%{http_code}' -X POST "$LEAVE_BASE/employees/$EMP_ID/leave-requests/$CANCEL_ID/cancel")"
test "$CANCEL_RESULT" = 200 || { echo "Leave cancellation failed: $CANCEL_RESULT"; exit 1; }
python3 -c 'import json;assert json.load(open("/tmp/hrm-m2-leave-cancelled.json"))["status"]=="CANCELLED"'
CANCEL_REVIEW="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /dev/null -w '%{http_code}' -X POST "$LEAVE_BASE/employees/$EMP_ID/leave-requests/$CANCEL_ID/decision?decision=APPROVED")"
{ test "$CANCEL_REVIEW" = 403 || test "$CANCEL_REVIEW" = 409; } || { echo "Cancelled leave approval accepted: $CANCEL_REVIEW"; exit 1; }
# Verify independent rejection and immutable audit traces in the real database.
REJECT_LEAVE_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m2-leave-reject.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","from":"2026-11-23","to":"2026-11-24","reason":"Independent rejection test"}' "$LEAVE_TYPE_ID")" "$LEAVE_BASE/employees/$EMP_ID/leave-requests")"
test "$REJECT_LEAVE_CODE" = 201 || { echo "Leave reject fixture failed: $REJECT_LEAVE_CODE"; exit 1; }
LEAVE_REJECT_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-m2-leave-reject.json"))["id"])')"
REJECT_LEAVE_HTTP="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /tmp/hrm-m2-leave-rejected.json -w '%{http_code}' -X POST "$LEAVE_BASE/employees/$EMP_ID/leave-requests/$LEAVE_REJECT_ID/decision?decision=REJECTED")"
test "$REJECT_LEAVE_HTTP" = 200 || { echo "Independent leave rejection failed: $REJECT_LEAVE_HTTP"; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-m2-leave-rejected.json"));assert x["status"]=="REJECTED" and x["reviewedBy"]!=x["requestedBy"]'
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/audit-logs?limit=500" > /tmp/hrm-m2-leave-audit.json
python3 -c 'import json,sys; rows=json.load(open("/tmp/hrm-m2-leave-audit.json")); targets=[(sys.argv[1],{"TIME_LEAVE_REQUESTED","TIME_LEAVE_APPROVED"}),(sys.argv[2],{"TIME_LEAVE_REQUESTED","TIME_LEAVE_REJECTED"})]; assert all(expected.issubset({r["action"] for r in rows if r["entityType"]=="time_leave_request" and r["entityId"]==ident}) for ident,expected in targets)' "$LEAVE_ID" "$LEAVE_REJECT_ID"
echo "M2.5 leave type, independent approval/rejection, overlap, replay, cancellation and audit HTTP regression passed"
