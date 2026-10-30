# Sourced from test-api-security.sh with existing MFA bearer and employee fixture.
HR_CASE_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-case.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"type":"ONBOARDING","effectiveDate":"2026-09-01","reason":"CI onboarding"}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases")"
test "$HR_CASE_CODE" = 201 || { echo "HR case creation failed: $HR_CASE_CODE"; cat /tmp/hrm-ci-case.json; exit 1; }
HR_CASE_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-case.json"))["id"])')"
HR_SELF_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$HR_CASE_ID/decision?decision=APPROVED")"
test "$HR_SELF_CODE" = 403 || { echo "HR self-approval not blocked: $HR_SELF_CODE"; exit 1; }
DOC_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-document.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"category":"ONBOARDING","filename":"checklist.pdf","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sizeBytes":512}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents")"
test "$DOC_CODE" = 201 || { echo "Document metadata registration failed: $DOC_CODE"; cat /tmp/hrm-ci-document.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/documents" | grep -q 'checklist.pdf'
