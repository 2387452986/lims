#!/usr/bin/env bash
set -euo pipefail

: "${QA_PASSWORD:?Set QA_PASSWORD without writing it to the repository}"
QA_USERNAME="${QA_USERNAME:-showcase-admin}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
stamp="$(date +%Y%m%d%H%M%S)"

login_payload="$(jq -nc --arg username "$QA_USERNAME" --arg password "$QA_PASSWORD" \
  '{username:$username,password:$password}')"
login_headers="$(curl -sS -D - -o /dev/null -H 'Content-Type: application/json' \
  -d "$login_payload" "$BASE_URL/api/auth/login")"
session_cookie="$(printf '%s\n' "$login_headers" | sed -n 's/^Set-Cookie: SESSION=\([^;]*\).*/\1/p' | tr -d '\r' | tail -1)"
[[ -n "$session_cookie" ]] || { echo 'login failed: no session cookie returned' >&2; exit 1; }
csrf_headers="$(curl -sS -D - -o /dev/null -H "Cookie: SESSION=$session_cookie" "$BASE_URL/api/auth/me")"
csrf_token="$(printf '%s\n' "$csrf_headers" | sed -n 's/^Set-Cookie: XSRF-TOKEN=\([^;]*\).*/\1/p' | tr -d '\r' | tail -1)"
[[ -n "$csrf_token" ]] || { echo 'login failed: no CSRF token returned' >&2; exit 1; }
cookie_header="SESSION=$session_cookie; XSRF-TOKEN=$csrf_token"

get() { curl -fsS -H "Cookie: $cookie_header" "$BASE_URL$1"; }
post() {
  curl -fsS -X POST -H "Cookie: $cookie_header" -H "X-XSRF-TOKEN: $csrf_token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "$3" "$BASE_URL$1"
}

for path in \
  '/api/quality-ledgers/specimens?view=OVERDUE&limit=20' \
  '/api/quality-ledgers/specimens?view=DUE&limit=20' \
  '/api/quality-ledgers/rebound-assessments?limit=20' \
  '/api/quality-ledgers/field-tests?limit=20' \
  '/api/warnings?limit=20'; do
  printf 'GET %s\n' "$path"
  response="$(get "$path")"
  [[ "$(jq -r 'has("items") and has("total") and has("hasMore")' <<<"$response")" == true ]]
done

warnings="$(get '/api/warnings?state=OPEN&query=SHOWCASE&limit=20')"
warning_id="$(jq -r '.items[0].id // empty' <<<"$warnings")"
warning_version="$(jq -r '.items[0].version // empty' <<<"$warnings")"
[[ -n "$warning_id" ]] || { echo 'no open SHOWCASE warning available' >&2; exit 1; }
resolve_payload="$(jq -nc --argjson version "$warning_version" \
  '{action:"RESOLVE",expectedVersion:$version,description:"SHOWCASE V29 预警处置运行验收",attachmentFileIds:[]}')"
resolved="$(post "/api/warnings/$warning_id/transitions" "qa-v29-warning-resolve-$stamp" "$resolve_payload")"
[[ "$(jq -r .state <<<"$resolved")" == 'RESOLVED' ]]
stale_file="$(mktemp /tmp/lims-v29-stale.XXXXXX)"
stale_status="$(curl -sS -o "$stale_file" -w '%{http_code}' -X POST -H "Cookie: $cookie_header" \
  -H "X-XSRF-TOKEN: $csrf_token" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: qa-v29-warning-stale-$stamp" -d "$resolve_payload" \
  "$BASE_URL/api/warnings/$warning_id/transitions")"
[[ "$stale_status" == 409 && "$(jq -r .code "$stale_file")" == 'VERSION_CONFLICT' ]]

export_payload='{"ledgerType":"OVERDUE_SPECIMEN","from":null,"to":null,"region":"","detectionProject":"","commissionNumber":"","sampleNumber":"","reportNumber":"","projectNumber":"","projectName":"","customerName":"","conclusion":"","taskState":"","curingMethod":"","strengthGrade":"","progress":"","taskNumber":"","testerName":""}'
queued="$(post /api/quality-ledger-exports "qa-v29-quality-export-$stamp" "$export_payload")"
job_id="$(jq -r .jobId <<<"$queued")"
job='{}'
for _ in {1..80}; do
  job="$(get "/api/jobs/$job_id")"
  job_state="$(jq -r .state <<<"$job")"
  [[ "$job_state" == 'COMPLETED' || "$job_state" == 'FAILED' ]] && break
  sleep 0.25
done
[[ "$(jq -r .state <<<"$job")" == 'COMPLETED' ]] || {
  echo "quality export failed: $(jq -r '.lastError // .state' <<<"$job")" >&2; exit 1;
}
file_id="$(jq -r .resultFileId <<<"$job")"
export_file="$(mktemp /tmp/lims-v29-quality.XXXXXX.xlsx)"
curl -fsS -H "Cookie: $cookie_header" "$BASE_URL/api/files/$file_id" -o "$export_file"
unzip -tqq "$export_file"

rm -f "$stale_file" "$export_file"
printf 'quality-ledgers=5x200 warning=resolved/stale409 export=completed/xlsx\n'
