#!/usr/bin/env bash
# Invoked from test-api-security.sh after a rehire request exists.
set -euo pipefail
: "${EMP_ID:?}" "${REHIRE_ID:?}" "${DB_PASSWORD:?}"
BASE="http://localhost:8080"
PGPASSWORD="$DB_PASSWORD" psql -X -v ON_ERROR_STOP=1 -h localhost -p 5433 -U "${DB_USERNAME:-hrm_app}" -d hrm_dev <<'SQL'
INSERT INTO auth_identity(tenant_id,email,password_hash,enabled)
SELECT tenant_id,'second-admin-ci@example.test',crypt('SecondCiPassword123!',gen_salt('bf',10)),true
FROM auth_identity WHERE email='admin-ci@example.test'
ON CONFLICT(email) DO NOTHING;
INSERT INTO auth_identity_role(identity_id,role)
SELECT id,'TENANT_ADMIN' FROM auth_identity WHERE email='second-admin-ci@example.test'
ON CONFLICT DO NOTHING;
SQL
SECOND_AUTH="second-admin-ci@example.test:SecondCiPassword123!"
SECOND_COOKIE="$(mktemp)"
trap 'rm -f "$SECOND_COOKIE"' EXIT
curl -fsS -u "$SECOND_AUTH" -b "$SECOND_COOKIE" -c "$SECOND_COOKIE" "$BASE/api/v1/security/csrf" > /tmp/hrm-ci-second-csrf.json
SECOND_CSRF="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-second-csrf.json"))["token"])')"
SECOND_ENROLL="$(curl -sS -u "$SECOND_AUTH" -b "$SECOND_COOKIE" -c "$SECOND_COOKIE" -o /tmp/hrm-ci-second-mfa.json -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $SECOND_CSRF" "$BASE/api/v1/security/mfa/enroll")"
test "$SECOND_ENROLL" = 201 || { echo "Second admin MFA enrollment failed"; exit 1; }
SECOND_SECRET="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-second-mfa.json"))["secretHex"])')"
export SECOND_SECRET
SECOND_STEP="$(python3 -c 'import time;print(int(time.time()//30))')"
export SECOND_STEP
SECOND_OTP="$(python3 -c 'import os,hmac,hashlib,struct; k=bytes.fromhex(os.environ["SECOND_SECRET"]); c=int(os.environ["SECOND_STEP"]); d=hmac.new(k,struct.pack(">Q",c),hashlib.sha1).digest(); i=d[-1]&15;print("%06d"%((struct.unpack(">I",d[i:i+4])[0]&0x7fffffff)%1000000))')"
SECOND_CONFIRM="$(curl -sS -u "$SECOND_AUTH" -b "$SECOND_COOKIE" -c "$SECOND_COOKIE" -o /dev/null -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $SECOND_CSRF" -H 'Content-Type: application/json' --data "$(printf '{"code":"%s"}' "$SECOND_OTP")" "$BASE/api/v1/security/mfa/confirm")"
test "$SECOND_CONFIRM" = 204 || { echo "Second admin MFA confirmation failed"; exit 1; }
SECOND_NEXT="$(python3 -c 'import os,hmac,hashlib,struct; k=bytes.fromhex(os.environ["SECOND_SECRET"]); c=int(os.environ["SECOND_STEP"])+1; d=hmac.new(k,struct.pack(">Q",c),hashlib.sha1).digest(); i=d[-1]&15;print("%06d"%((struct.unpack(">I",d[i:i+4])[0]&0x7fffffff)%1000000))')"
SECOND_ISSUE="$(curl -sS -u "$SECOND_AUTH" -b "$SECOND_COOKIE" -c "$SECOND_COOKIE" -o /tmp/hrm-ci-second-token.json -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $SECOND_CSRF" -H "X-TOTP-Code: $SECOND_NEXT" "$BASE/api/v1/security/tokens")"
test "$SECOND_ISSUE" = 201 || { echo "Second admin token issuance failed"; exit 1; }
SECOND_BEARER="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-second-token.json"))["value"])')"
APPROVE_HTTP="$(curl -sS -H "Authorization: Bearer $SECOND_BEARER" -o /tmp/hrm-ci-rehire-approved.json -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/rehire-requests/$REHIRE_ID/approve")"
test "$APPROVE_HTTP" = 200 || { echo "Independent rehire approval failed: $APPROVE_HTTP"; cat /tmp/hrm-ci-rehire-approved.json; exit 1; }
python3 -c 'import json; x=json.load(open("/tmp/hrm-ci-rehire-approved.json"));assert x["status"]=="APPROVED" and x["approvedBy"]!=x["requestedBy"]'
echo "Independent dual-control rehire approved"
