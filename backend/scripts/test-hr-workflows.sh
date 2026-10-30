# Sourced from test-api-security.sh with existing MFA bearer and employee fixture.
HR_CASE_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-case.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"type":"ONBOARDING","effectiveDate":"2026-09-01","reason":"CI onboarding"}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases")"
test "$HR_CASE_CODE" = 201 || { echo "HR case creation failed: $HR_CASE_CODE"; cat /tmp/hrm-ci-case.json; exit 1; }
HR_CASE_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-case.json"))["id"])')"
CHECK_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-checklist.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"code":"IDENTITY_REVIEW","label":"Identity review","mandatory":true}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$HR_CASE_ID/requirements")"
test "$CHECK_CODE" = 201 || { echo "Onboarding requirement creation failed: $CHECK_CODE"; cat /tmp/hrm-checklist.json; exit 1; }
CHECK_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-checklist.json"))["id"])')"
VERIFY_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-verified.json -w '%{http_code}' -X PATCH -H 'Content-Type: application/json' --data '{"status":"VERIFIED"}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$HR_CASE_ID/requirements/$CHECK_ID/verification")"
test "$VERIFY_CODE" = 200 || { echo "Onboarding verification failed: $VERIFY_CODE"; cat /tmp/hrm-verified.json; exit 1; }
HR_SELF_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$HR_CASE_ID/decision?decision=APPROVED")"
test "$HR_SELF_CODE" = 403 || { echo "HR self-approval not blocked: $HR_SELF_CODE"; exit 1; }
DOC_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-document.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"category":"ONBOARDING","filename":"checklist.pdf","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sizeBytes":512}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents")"
test "$DOC_CODE" = 201 || { echo "Document metadata registration failed: $DOC_CODE"; cat /tmp/hrm-ci-document.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/documents" | grep -q 'checklist.pdf'

SEPARATION_CASE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-sep-case.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"type":"SEPARATION","effectiveDate":"2026-09-02","reason":"CI exit clearance"}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases")"
test "$SEPARATION_CASE" = 201 || { echo "Separation case failed: $SEPARATION_CASE"; cat /tmp/hrm-ci-sep-case.json; exit 1; }
SEPARATION_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-sep-case.json"))["id"])')"
CLEARANCE_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-clearance.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"code":"PROPERTY_RETURN","label":"Property return","required":true}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$SEPARATION_ID/clearances")"
test "$CLEARANCE_CODE" = 201 || { echo "Exit clearance creation failed: $CLEARANCE_CODE"; cat /tmp/hrm-ci-clearance.json; exit 1; }
CLEARANCE_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-clearance.json"))["id"])')"
REVIEW_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-reviewed.json -w '%{http_code}' -X PATCH -H 'Content-Type: application/json' --data '{"status":"CLEARED"}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$SEPARATION_ID/clearances/$CLEARANCE_ID")"
test "$REVIEW_CODE" = 200 || { echo "Clearance review failed: $REVIEW_CODE"; cat /tmp/hrm-ci-reviewed.json; exit 1; }
