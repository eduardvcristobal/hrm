#!/usr/bin/env bash
# M2.9 authentication and employee IDOR test against real PostgreSQL.
# A synthetic MFA-verified test token is inserted ONLY in disposable CI DB.
: "${BASE:?}" "${BEARER:?}" "${COMPANY_ID:?}" "${EMP_ID:?}" "${DB_PASSWORD:?}"
ES_BASE="$BASE/api/v1/employee-self-service"
EMP_TOKEN="$(openssl rand -hex 32)"
EMP_HASH="$(printf '%s' "$EMP_TOKEN" | sha256sum | cut -d' ' -f1)"
PGPASSWORD="$DB_PASSWORD" psql -X -v ON_ERROR_STOP=1 -h localhost -p 5433 -U "${DB_USERNAME:-hrm_app}" -d hrm_dev -v token_hash="$EMP_HASH" <<'SQL'
INSERT INTO auth_identity(tenant_id,email,password_hash,enabled)
SELECT tenant_id,'employee-m29@example.test',crypt('CiOnlyEmployeePassword123!',gen_salt('bf',10)),true
FROM auth_identity WHERE email='admin-ci@example.test';
INSERT INTO auth_identity_role(identity_id,role)
SELECT id,'EMPLOYEE' FROM auth_identity WHERE email='employee-m29@example.test';
INSERT INTO auth_api_token(identity_id,token_sha256,expires_at,mfa_verified)
SELECT id,:'token_hash',now()+interval '1 hour',true
FROM auth_identity WHERE email='employee-m29@example.test';
SQL
# MFA verified token without an HR identity link cannot see an employee profile.
UNLINKED="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' "$ES_BASE/me")"
test "$UNLINKED" = 403 || { echo "Unlinked employee was granted ESS access: $UNLINKED"; exit 1; }
ADMIN_ONLY="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' "$ES_BASE/admin/identity-links")"
{ test "$ADMIN_ONLY" = 403 || test "$ADMIN_ONLY" = 404; } || { echo "Employee accessed admin links: $ADMIN_ONLY"; exit 1; }
EMP_IDENTITY="$(PGPASSWORD="$DB_PASSWORD" psql -X -t -A -h localhost -p 5433 -U "${DB_USERNAME:-hrm_app}" -d hrm_dev -c "SELECT id FROM auth_identity WHERE email='employee-m29@example.test'")"
LINK_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m29-link.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"identityId":"%s","employeeId":"%s"}' "$EMP_IDENTITY" "$EMP_ID")" "$ES_BASE/admin/identity-links")"
test "$LINK_STATUS" = 201 || { echo "Admin identity link failed: $LINK_STATUS"; cat /tmp/hrm-m29-link.json; exit 1; }
LINK_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-m29-link.json"))["id"])')"
REPLAY_LINK="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"identityId":"%s","employeeId":"%s"}' "$EMP_IDENTITY" "$EMP_ID")" "$ES_BASE/admin/identity-links")"
{ test "$REPLAY_LINK" = 403 || test "$REPLAY_LINK" = 409; } || { echo "Duplicate ESS link accepted: $REPLAY_LINK"; exit 1; }
curl -fsS -H "Authorization: Bearer $EMP_TOKEN" "$ES_BASE/me" > /tmp/hrm-m29-me.json
python3 -c 'import json,sys;x=json.load(open("/tmp/hrm-m29-me.json")); assert x["employee"]["employeeId"]==sys.argv[1];assert isinstance(x["schedules"],list) and isinstance(x["leaveRequests"],list) and isinstance(x["attendanceSessions"],list) and len(x["attendanceSessions"])>=1;assert x["clockInEnabled"] is False and x["locationCaptureEnabled"] is False and x["biometricCaptureEnabled"] is False;assert "READ_ONLY" in x["classification"]' "$EMP_ID"
# No employee-chosen identifier or mutation endpoint exists on this surface.
OTHER="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' "$ES_BASE/employees/$EMP_ID/attendance")"
{ test "$OTHER" = 403 || test "$OTHER" = 404; } || { echo "Client-supplied employee path accepted: $OTHER"; exit 1; }
CLOCK="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' -X POST "$ES_BASE/me/clock-in")"
{ test "$CLOCK" = 403 || test "$CLOCK" = 404; } || { echo "Uncertified device clock-in accepted: $CLOCK"; exit 1; }
ADMIN_PATH="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/leave-requests")"
test "$ADMIN_PATH" = 403 || { echo "Employee bypassed tenant-admin timekeeping: $ADMIN_PATH"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/audit-logs?limit=500" > /tmp/hrm-m29-audit.json
python3 -c 'import json,sys;a=json.load(open("/tmp/hrm-m29-audit.json"));assert any(x["entityId"]==sys.argv[1] and x["action"]=="TIME_ESS_IDENTITY_LINKED" for x in a)' "$LINK_ID"
# Verify employee-only leave creation before link revocation.
NEW_LEAVE_STATUS="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /tmp/hrm-m29-newleave.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"leaveTypeId":"%s","from":"2026-12-01","to":"2026-12-02","reason":"Employee-submitted time off request"}' "$LEAVE_TYPE_ID")" "$ES_BASE/leave-requests")"
test "$NEW_LEAVE_STATUS" = 201 || { echo "Employee leave creation failed: $NEW_LEAVE_STATUS"; cat /tmp/hrm-m29-newleave.json; exit 1; }
SELF_LEAVE_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-m29-newleave.json"))["id"])')"
APPROVAL_STATUS="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/timekeeping/employees/$EMP_ID/leave-requests/$SELF_LEAVE_ID/decision?decision=APPROVED")"
test "$APPROVAL_STATUS" = 200 || { echo "Independent HR review failed: $APPROVAL_STATUS"; exit 1; }
# Revoking the link must instantly deny the same still-valid employee bearer token.
REVOKE_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-m29-revoked.json -w '%{http_code}' -X POST "$ES_BASE/admin/identity-links/$LINK_ID/revoke")"
test "$REVOKE_STATUS" = 200 || { echo "Link revocation failed: $REVOKE_STATUS"; cat /tmp/hrm-m29-revoked.json; exit 1; }
AFTER_REVOKE="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' "$ES_BASE/me")"
test "$AFTER_REVOKE" = 403 || { echo "Revoked employee still read ESS data: $AFTER_REVOKE"; exit 1; }
REVOKE_REPLAY="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$ES_BASE/admin/identity-links/$LINK_ID/revoke")"
{ test "$REVOKE_REPLAY" = 403 || test "$REVOKE_REPLAY" = 409; } || { echo "Repeated ESS link revocation accepted: $REVOKE_REPLAY"; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/audit-logs?limit=500" > /tmp/hrm-m29-audit.json
python3 -c 'import json,sys;a=json.load(open("/tmp/hrm-m29-audit.json"));assert any(x["entityId"]==sys.argv[1] and x["action"]=="TIME_ESS_IDENTITY_REVOKED" for x in a)' "$LINK_ID"
echo 'M2.9 employee link, IDOR, device denial, immediate revocation and audit checks passed'
