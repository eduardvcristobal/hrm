# Sourced from test-api-security.sh with existing MFA bearer and employee fixture.
HR_CASE_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-case.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"type":"ONBOARDING","effectiveDate":"2026-09-01","reason":"CI onboarding"}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases")"
test "$HR_CASE_CODE" = 201 || { echo "HR case creation failed: $HR_CASE_CODE"; cat /tmp/hrm-ci-case.json; exit 1; }
HR_CASE_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-case.json"))["id"])')"
CHECK_CODE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-checklist.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"code":"IDENTITY_REVIEW","label":"Identity review","mandatory":true}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$HR_CASE_ID/requirements")"
test "$CHECK_CODE" = 201 || { echo "Onboarding requirement creation failed: $CHECK_CODE"; cat /tmp/hrm-checklist.json; exit 1; }
TEMPLATE_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-template.json -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$HR_CASE_ID/requirements/apply-template")"
test "$TEMPLATE_HTTP" = 200 || { echo "Onboarding template failed: $TEMPLATE_HTTP"; cat /tmp/hrm-template.json; exit 1; }
python3 -c 'import json; rows=json.load(open("/tmp/hrm-template.json"));assert len(rows)==4,rows;assert all(r["mandatory"] for r in rows)'
TEMPLATE_AGAIN="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-template-repeat.json -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$HR_CASE_ID/requirements/apply-template")"
test "$TEMPLATE_AGAIN" = 200 || exit 1
python3 -c 'import json;assert len(json.load(open("/tmp/hrm-template-repeat.json")))==4'
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

FINAL_PAY_PENDING="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$SEPARATION_ID/final-pay")"
{ test "$FINAL_PAY_PENDING" = 403 || test "$FINAL_PAY_PENDING" = 409; } || { echo "Unapproved final-pay handoff accepted: $FINAL_PAY_PENDING"; exit 1; }


ASSET_CREATE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-asset.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"assetTag":"CI-LAPTOP-001","description":"Test laptop return"}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$SEPARATION_ID/assets")"
test "$ASSET_CREATE" = 201 || { echo "Asset registration failed: $ASSET_CREATE"; cat /tmp/hrm-ci-asset.json; exit 1; }
ASSET_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-ci-asset.json"))["id"])')"
ASSET_RETURN="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-asset-return.json -w '%{http_code}' -X PATCH -H 'Content-Type: application/json' --data '{"status":"RETURNED"}' "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$SEPARATION_ID/assets/$ASSET_ID")"
test "$ASSET_RETURN" = 200 || { echo "Asset return failed: $ASSET_RETURN"; cat /tmp/hrm-ci-asset-return.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/hr-cases/$SEPARATION_ID/assets" | grep -q 'RETURNED'


# Deliberately minimal test PDF; this validates encryption round-trip, not document safety.
printf '%s' '%PDF-1.4 dummy-ci-only\n%%EOF\n' > /tmp/hrm-document-ci.pdf
UPLOAD_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-uploaded-document.json -w '%{http_code}' -F 'category=ONBOARDING' -F 'file=@/tmp/hrm-document-ci.pdf;type=application/pdf' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/upload")"
test "$UPLOAD_STATUS" = 201 || { echo "Document upload failed: $UPLOAD_STATUS"; cat /tmp/hrm-uploaded-document.json; exit 1; }
DOC_CONTENT_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-uploaded-document.json"))["id"])')"
QUARANTINE_DOWNLOAD="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/content")"
{ test "$QUARANTINE_DOWNLOAD" = 404 || test "$QUARANTINE_DOWNLOAD" = 403; } || { echo "Quarantined document accessible: $QUARANTINE_DOWNLOAD"; exit 1; }
SCAN_ERROR="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-scan-result.txt -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/scan")"
test "$SCAN_ERROR" = 200 || { echo "Scan endpoint failed: $SCAN_ERROR"; exit 1; }
grep -q 'ERROR' /tmp/hrm-scan-result.txt || { echo "No scanner should fail closed"; exit 1; }
SELF_REVIEW="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"status":"RELEASED"}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/review")"
test "$SELF_REVIEW" = 403 || { echo "Document uploader self-reviewed: $SELF_REVIEW"; exit 1; }
ANON_DOCUMENT="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/content")"
{ test "$ANON_DOCUMENT" = 401 || test "$ANON_DOCUMENT" = 403; } || { echo "Anonymous document access allowed: $ANON_DOCUMENT"; exit 1; }

VERSION_UPLOAD="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-version.json -w '%{http_code}' -F 'file=@/tmp/hrm-document-ci.pdf;filename=revised.pdf;type=application/pdf' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/versions")"
test "$VERSION_UPLOAD" = 201 || { echo "Replacement document failed: $VERSION_UPLOAD"; cat /tmp/hrm-version.json; exit 1; }
VERSION_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-version.json"))["id"])')"
VERSION_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$VERSION_ID/content")"
{ test "$VERSION_STATUS" = 403 || test "$VERSION_STATUS" = 404; } || { echo "Unreviewed replacement was downloadable: $VERSION_STATUS"; exit 1; }
VERSION_OLD="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -F 'file=@/tmp/hrm-document-ci.pdf;filename=invalid.pdf;type=application/pdf' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/versions")"
{ test "$VERSION_OLD" = 403 || test "$VERSION_OLD" = 409; } || { echo "Old document version accepted replacement: $VERSION_OLD"; exit 1; }


LIFECYCLE_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-lifecycle.json -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/lifecycle")"
test "$LIFECYCLE_STATUS" = 200 || { echo "Lifecycle read failed: $LIFECYCLE_STATUS"; exit 1; }
HOLD_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-held.json -w '%{http_code}' -X PUT -H 'Content-Type: application/json' --data '{"enabled":true,"reason":"CI legal hold"}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/hold")"
test "$HOLD_STATUS" = 200 || { echo "Legal hold failed: $HOLD_STATUS"; exit 1; }
TOMBSTONE_DENIED="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/tombstone")"
{ test "$TOMBSTONE_DENIED" = 409 || test "$TOMBSTONE_DENIED" = 403; } || { echo "Held document could be deleted: $TOMBSTONE_DENIED"; exit 1; }
RETENTION_DENIED="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' --data '{"retainUntil":"2027-12-31"}' "$BASE/api/v1/personnel/employees/$EMP_ID/documents/$DOC_CONTENT_ID/retention")"
{ test "$RETENTION_DENIED" = 409 || test "$RETENTION_DENIED" = 403; } || { echo "Held document retention changed: $RETENTION_DENIED"; exit 1; }
