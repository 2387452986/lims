#!/bin/sh
set -eu

base_url=${LIMS_BASE_URL:-http://127.0.0.1:8080}
username=${LIMS_USERNAME:-admin}
password=${LIMS_PASSWORD:?LIMS_PASSWORD is required}
requests=${REQUESTS:-200}
concurrency=${CONCURRENCY:-50}
p95_limit_seconds=${P95_LIMIT_SECONDS:-1.0}

cookie_jar=$(mktemp "${TMPDIR:-/tmp}/lims-perf-cookie.XXXXXX")
results=$(mktemp "${TMPDIR:-/tmp}/lims-perf-results.XXXXXX")
cleanup() { rm -f "$cookie_jar" "$results"; }
trap cleanup EXIT INT TERM

escape_json() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
login_body=$(printf '{"username":"%s","password":"%s"}' "$(escape_json "$username")" "$(escape_json "$password")")

curl --fail --silent --show-error --cookie-jar "$cookie_jar" \
  --header 'Content-Type: application/json' --data "$login_body" \
  "$base_url/api/auth/login" >/dev/null

endpoint="$base_url/api/workbench?status=ALL&from=2000-01-01&to=2100-01-01&q="
export cookie_jar endpoint
seq 1 "$requests" | xargs -P "$concurrency" -I '{}' sh -c \
  'curl --silent --show-error --output /dev/null --write-out "%{http_code} %{time_total}\n" --cookie "$cookie_jar" "$endpoint"' \
  > "$results"

completed=$(wc -l < "$results" | tr -d ' ')
failures=$(awk '$1 !~ /^2/ { count++ } END { print count+0 }' "$results")
p95_index=$(( (completed * 95 + 99) / 100 ))
p95=$(awk '{ print $2 }' "$results" | sort -n | awk -v target="$p95_index" 'NR==target { print; exit }')

printf 'requests=%s concurrency=%s completed=%s failures=%s p95_seconds=%s limit_seconds=%s\n' \
  "$requests" "$concurrency" "$completed" "$failures" "$p95" "$p95_limit_seconds"
test "$completed" -eq "$requests"
test "$failures" -eq 0
awk -v value="$p95" -v limit="$p95_limit_seconds" 'BEGIN { exit !(value <= limit) }'
