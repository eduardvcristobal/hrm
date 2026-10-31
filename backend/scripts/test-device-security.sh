#!/usr/bin/env bash
# M2.9.1: source after the synthetic employee has been linked.
: "${BASE:?}" "${EMP_TOKEN:?}" "${BEARER:?}" "${SECOND_BEARER:?}" "${EMP_ID:?}"
DEV_ROOT="$BASE/api/v1/employee-self-service"
PENDING="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /tmp/hrm-device-pending.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"displayName":"Employee test laptop"}' "$DEV_ROOT/devices")"
test "$PENDING" = 201 || { echo "Device enrollment request failed: $PENDING"; cat /tmp/hrm-device-pending.json; exit 1; }
DEVICE_ID="$(python3 -c 'import json;x=json.load(open("/tmp/hrm-device-pending.json"));assert x["status"]=="PENDING" and "credential" not in x;print(x["id"])')"
UNAPPROVED="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' -X POST "$DEV_ROOT/devices/$DEVICE_ID/activate")"
{ test "$UNAPPROVED" = 403 || test "$UNAPPROVED" = 409; } || { echo "Pending device activated: $UNAPPROVED"; exit 1; }
EMP_REVIEW="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' -X POST "$DEV_ROOT/admin/devices/$DEVICE_ID/review?decision=APPROVED")"
test "$EMP_REVIEW" = 403 || { echo "Employee approved own device: $EMP_REVIEW"; exit 1; }
APPROVED="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /tmp/hrm-device-approved.json -w '%{http_code}' -X POST "$DEV_ROOT/admin/devices/$DEVICE_ID/review?decision=APPROVED")"
test "$APPROVED" = 200 || { echo "Independent device approval failed: $APPROVED"; cat /tmp/hrm-device-approved.json; exit 1; }
ACTIVATED="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /tmp/hrm-device-active.json -w '%{http_code}' -X POST "$DEV_ROOT/devices/$DEVICE_ID/activate")"
test "$ACTIVATED" = 200 || { echo "Device activation failed: $ACTIVATED"; cat /tmp/hrm-device-active.json; exit 1; }
DEVICE_SECRET="$(python3 -c 'import json;x=json.load(open("/tmp/hrm-device-active.json")); assert len(x["credential"])==64 and "NO_CLOCK_IN" in x["classification"]; print(x["credential"])')"
ACTIVATE_REPLAY="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' -X POST "$DEV_ROOT/devices/$DEVICE_ID/activate")"
{ test "$ACTIVATE_REPLAY" = 403 || test "$ACTIVATE_REPLAY" = 409; } || { echo "Device secret reissued: $ACTIVATE_REPLAY"; exit 1; }
curl -fsS -H "Authorization: Bearer $EMP_TOKEN" "$DEV_ROOT/devices" > /tmp/hrm-device-list.json
python3 -c 'import json,sys; x=next(d for d in json.load(open("/tmp/hrm-device-list.json")) if d["id"]==sys.argv[1]);assert x["status"]=="ACTIVE" and "credential" not in x' "$DEVICE_ID"
CURRENT_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
NONCE="$(python3 -c 'import uuid;print(uuid.uuid4())')"
ATTEST_BODY="$(printf '{"nonce":"%s","timestamp":"%s"}' "$NONCE" "$CURRENT_TIME")"
INVALID="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -H "X-Device-Credential: $(openssl rand -hex 32)" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$ATTEST_BODY" "$DEV_ROOT/devices/$DEVICE_ID/attest")"
test "$INVALID" = 403 || { echo "Invalid credential accepted: $INVALID"; exit 1; }
ATTESTED="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -H "X-Device-Credential: $DEVICE_SECRET" -o /tmp/hrm-device-attested.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$ATTEST_BODY" "$DEV_ROOT/devices/$DEVICE_ID/attest")"
test "$ATTESTED" = 200 || { echo "Attestation failed: $ATTESTED"; cat /tmp/hrm-device-attested.json; exit 1; }
python3 -c 'import json,sys;x=json.load(open("/tmp/hrm-device-attested.json"));assert x["nonce"]==sys.argv[1] and "NO_ATTENDANCE" in x["classification"]' "$NONCE"
REPLAY="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -H "X-Device-Credential: $DEVICE_SECRET" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$ATTEST_BODY" "$DEV_ROOT/devices/$DEVICE_ID/attest")"
{ test "$REPLAY" = 403 || test "$REPLAY" = 409; } || { echo "Device nonce replay accepted: $REPLAY"; exit 1; }
OLD_NONCE="$(python3 -c 'import uuid;print(uuid.uuid4())')"
STALE="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -H "X-Device-Credential: $DEVICE_SECRET" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"nonce":"%s","timestamp":"2020-01-01T00:00:00Z"}' "$OLD_NONCE")" "$DEV_ROOT/devices/$DEVICE_ID/attest")"
{ test "$STALE" = 403 || test "$STALE" = 409; } || { echo "Stale device proof accepted: $STALE"; exit 1; }
REVOKED="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-device-revoked.json -w '%{http_code}' -X POST "$DEV_ROOT/admin/devices/$DEVICE_ID/revoke")"
test "$REVOKED" = 200 || { echo "Admin device revoke failed: $REVOKED"; exit 1; }
AFTER="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -H "X-Device-Credential: $DEVICE_SECRET" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"nonce":"%s","timestamp":"%s"}' "$OLD_NONCE" "$CURRENT_TIME")" "$DEV_ROOT/devices/$DEVICE_ID/attest")"
test "$AFTER" = 403 || { echo "Revoked credential was accepted: $AFTER"; exit 1; }
echo "M2.9.1 device request, independent review, one-time credential, nonce replay, skew and revocation gate passed"
