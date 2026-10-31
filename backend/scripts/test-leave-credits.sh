#!/usr/bin/env bash
# M2.5.1: sourced after M2.5 lifecycle integration; uses real PostgreSQL and
# MFA-verified tenant administrator, without asserting statutory entitlements.
: "${BASE:?}" "${BEARER:?}" "${EMP_ID:?}" "${LEAVE_TYPE_ID:?}"
ROOT="$BASE/api/v1/timekeeping"
POLICY_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-credit-policy.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","monthlyCreditDays":1.250,"effectiveFrom":"2026-01-01"}' "$LEAVE_TYPE_ID")" "$ROOT/leave-credit-policies")"
test "$POLICY_CODE" = 201 || { echo "Leave credit policy creation failed: $POLICY_CODE"; cat /tmp/hrm-credit-policy.json; exit 1; }
POLICY_ID="$(python3 -c 'import json; x=json.load(open("/tmp/hrm-credit-policy.json"));assert str(x["monthlyCreditDays"]) in ("1.25","1.250");print(x["id"])')"
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/leave-credit-policies?leaveTypeId=$LEAVE_TYPE_ID" > /tmp/hrm-credit-policies.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-credit-policies.json"));assert len(a)==1 and a[0]["id"]==sys.argv[1]' "$POLICY_ID"
DUP_POLICY="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","monthlyCreditDays":2,"effectiveFrom":"2026-01-01"}' "$LEAVE_TYPE_ID")" "$ROOT/leave-credit-policies")"
{ test "$DUP_POLICY" = 409 || test "$DUP_POLICY" = 403; } || { echo "Duplicate monthly policy accepted: $DUP_POLICY"; exit 1; }
CREDIT_ENDPOINT="$ROOT/employees/$EMP_ID/leave-credits/accrue?leaveTypeId=$LEAVE_TYPE_ID"
FUTURE_POST="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$CREDIT_ENDPOINT&month=2099-01")"
{ test "$FUTURE_POST" = 400 || test "$FUTURE_POST" = 403; } || { echo "Future credits accepted: $FUTURE_POST"; exit 1; }
CREDIT_POST="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-credit-post.json -w '%{http_code}' -X POST "$CREDIT_ENDPOINT&month=2026-10")"
test "$CREDIT_POST" = 201 || { echo "Leave accrual failed: $CREDIT_POST"; cat /tmp/hrm-credit-post.json; exit 1; }
CREDIT_ID="$(python3 -c 'import json; x=json.load(open("/tmp/hrm-credit-post.json")); assert x["periodStart"]=="2026-10-01" and float(x["creditDays"])==1.25; print(x["id"])')"
DUP_CREDIT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$CREDIT_ENDPOINT&month=2026-10")"
{ test "$DUP_CREDIT" = 409 || test "$DUP_CREDIT" = 403; } || { echo "Duplicate leave credit posting accepted: $DUP_CREDIT"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/employees/$EMP_ID/leave-credits?leaveTypeId=$LEAVE_TYPE_ID" > /tmp/hrm-credit-entries.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-credit-entries.json")); assert len(a)==1 and a[0]["id"]==sys.argv[1]' "$CREDIT_ID"
curl -fsS -H "Authorization: Bearer $BEARER" "$ROOT/employees/$EMP_ID/leave-credit-balances" > /tmp/hrm-credit-balances.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-credit-balances.json"));b=next(x for x in a if x["leaveTypeId"]==sys.argv[1]);assert float(b["accruedCreditDays"])==1.25 and b["spendable"] is False and b["classification"]=="INFORMATIONAL_CREDITS_NOT_SPENDABLE"' "$LEAVE_TYPE_ID"
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/audit-logs?limit=500" > /tmp/hrm-credit-audit.json
python3 -c 'import json,sys; a=json.load(open("/tmp/hrm-credit-audit.json"));assert any(x["entityId"]==sys.argv[1] and x["action"]=="TIME_LEAVE_CREDIT_POSTED" for x in a)' "$CREDIT_ID"
echo "M2.5.1 leave policy and once-only monthly credit ledger HTTP regression passed"

# Verify explicit approved-leave debits and compensating reversals.
source scripts/test-leave-balance.sh
