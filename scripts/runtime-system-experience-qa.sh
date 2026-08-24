#!/usr/bin/env bash
set -euo pipefail

: "${QA_PASSWORD:?Set QA_PASSWORD without writing it to the repository}"
QA_USERNAME="${QA_USERNAME:-showcase-admin}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
REVISION_ID="${REVISION_ID:-636b2dcc-dde3-507d-b06d-923a69b9777a}"
QUALIFIED_PERSON_ID="${QUALIFIED_PERSON_ID:-00000000-0000-0000-0000-000000000201}"

for command in curl jq; do
  command -v "$command" >/dev/null || { echo "missing command: $command" >&2; exit 1; }
done

login_payload="$(jq -nc --arg username "$QA_USERNAME" --arg password "$QA_PASSWORD" '{username:$username,password:$password}')"
login_headers="$(curl -sS -D - -o /dev/null -H 'Content-Type: application/json' -d "$login_payload" "$BASE_URL/api/auth/login")"
session_cookie="$(printf '%s\n' "$login_headers" | sed -n 's/^Set-Cookie: SESSION=\([^;]*\).*/\1/p' | tr -d '\r' | tail -n 1)"
[[ -n "$session_cookie" ]] || { echo 'login failed: no session cookie returned' >&2; exit 1; }
csrf_headers="$(curl -sS -D - -o /dev/null -H "Cookie: SESSION=$session_cookie" "$BASE_URL/api/auth/me")"
csrf_token="$(printf '%s\n' "$csrf_headers" | sed -n 's/^Set-Cookie: XSRF-TOKEN=\([^;]*\).*/\1/p' | tr -d '\r' | tail -n 1)"
[[ -n "$csrf_token" ]] || { echo 'login failed: no CSRF token returned' >&2; exit 1; }
cookie_header="SESSION=$session_cookie; XSRF-TOKEN=$csrf_token"

get() { curl -fsS -H "Cookie: $cookie_header" "$BASE_URL$1"; }

write() {
  local method="$1" path="$2" key="$3" payload="$4" response_file status
  response_file="$(mktemp /tmp/lims-system-experience-write.XXXXXX)"
  status="$(curl -sS -o "$response_file" -w '%{http_code}' -X "$method" \
    -H "Cookie: $cookie_header" -H "X-XSRF-TOKEN: $csrf_token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$payload" "$BASE_URL$path")"
  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    printf 'unexpected HTTP %s from %s: code=%s detail=%s\n' "$status" "$path" \
      "$(jq -r '.code // "UNKNOWN"' "$response_file" 2>/dev/null)" \
      "$(jq -r '.detail // .title // ""' "$response_file" 2>/dev/null)" >&2
    rm -f "$response_file"
    return 22
  fi
  cat "$response_file"
  rm -f "$response_file"
}

expect_problem() {
  local expected_status="$1" expected_code="$2" method="$3" path="$4" key="$5" payload="$6"
  local response_file status actual_code
  response_file="$(mktemp /tmp/lims-system-experience-problem.XXXXXX)"
  status="$(curl -sS -o "$response_file" -w '%{http_code}' -X "$method" \
    -H "Cookie: $cookie_header" -H "X-XSRF-TOKEN: $csrf_token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$payload" "$BASE_URL$path")"
  actual_code="$(jq -r '.code // empty' "$response_file")"
  rm -f "$response_file"
  [[ "$status" == "$expected_status" && "$actual_code" == "$expected_code" ]] || {
    echo "expected HTTP $expected_status/$expected_code from $path, got $status/$actual_code" >&2
    exit 1
  }
}

transition() {
  local resource="$1" id="$2" action="$3" version="$4" stamp="$5"
  write POST "/api/$resource/$id/transitions" "qa-system-$resource-$action-$stamp" \
    "$(jq -nc --arg action "$action" --argjson version "$version" \
      '{action:$action,expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"SHOWCASE 系统体验运行验收"}')"
}

stamp="$(date +%Y%m%d%H%M%S)"

# Theme create/edit/apply/revision/restore, with the active visual tokens restored at the end.
original_theme="$(get /api/system-experience/desktop-themes/active)"
original_id="$(jq -r .id <<<"$original_theme")"
original_code="$(jq -r .code <<<"$original_theme")"
theme_payload="$(jq -nc --arg stamp "$stamp" --argjson original "$original_theme" '{
  code:("SHOWCASE-THEME-"+$stamp),name:("SHOWCASE 主题验收 "+$stamp),remark:"SHOWCASE 受控主题运行验收",
  brand900:$original.brand900,brand600:$original.brand600,brand100:$original.brand100,
  surfaceBackground:$original.surfaceBackground,density:$original.density,radiusPx:$original.radiusPx,expectedVersion:null
}')"
expect_problem 422 THEME_CONTRAST_INVALID POST /api/system-experience/desktop-themes \
  "qa-system-theme-contrast-$stamp" \
  "$(jq -nc --arg stamp "$stamp" '{code:("SHOWCASE-CONTRAST-"+$stamp),name:"SHOWCASE 对比度拒绝",brand900:"#FFFFFF",brand600:"#FFFFFF",brand100:"#FFFFFF",surfaceBackground:"#FFFFFF",density:"COMPACT",radiusPx:10,expectedVersion:null}')"
theme="$(write POST /api/system-experience/desktop-themes "qa-system-theme-create-$stamp" "$theme_payload")"
theme_id="$(jq -r .id <<<"$theme")"
theme_version="$(jq -r .version <<<"$theme")"
theme_updated="$(write PUT "/api/system-experience/desktop-themes/$theme_id" "qa-system-theme-update-$stamp" \
  "$(jq --argjson version "$theme_version" '.name += " 已编辑" | .expectedVersion=$version' <<<"$theme_payload")")"
expect_problem 409 VERSION_CONFLICT PUT "/api/system-experience/desktop-themes/$theme_id" \
  "qa-system-theme-stale-$stamp" "$(jq --argjson version "$theme_version" '.expectedVersion=$version' <<<"$theme_payload")"
theme_active="$(transition system-experience/desktop-themes "$theme_id" ACTIVATE "$(jq -r .version <<<"$theme_updated")" "$stamp")"
[[ "$(jq -r .state <<<"$theme_active")" == "ACTIVE" ]]
original_current="$(get /api/system-experience/desktop-themes | jq -c --arg id "$original_id" '[.[] | select(.id==$id)][0]')"
restored_theme="$(write POST "/api/system-experience/desktop-themes/$original_id/revisions" \
  "qa-system-theme-restore-revision-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$original_current")" '{expectedVersion:$version}')")"
restored_id="$(jq -r .id <<<"$restored_theme")"
transition system-experience/desktop-themes "$restored_id" ACTIVATE "$(jq -r .version <<<"$restored_theme")" "$stamp-restore" >/dev/null
[[ "$(jq -r .code <<<"$(get /api/system-experience/desktop-themes/active)")" == "$original_code" ]]

discard_payload="$(jq '.code += "-DISCARD" | .name += " 作废验证"' <<<"$theme_payload")"
discard_theme="$(write POST /api/system-experience/desktop-themes "qa-system-theme-discard-create-$stamp" "$discard_payload")"
transition system-experience/desktop-themes "$(jq -r .id <<<"$discard_theme")" DISCARD "$(jq -r .version <<<"$discard_theme")" "$stamp-discard" >/dev/null

# Transition settings use optimistic locking and are restored to their original value.
setting="$(get /api/system-experience/transition-time-settings | jq -c '[.[] | select(.stageCode=="SAMPLE_INBOUND")][0]')"
setting_id="$(jq -r .id <<<"$setting")"
original_days="$(jq -r .allowedDays <<<"$setting")"
changed_days="$((original_days + 1))"
setting_changed="$(write PUT "/api/system-experience/transition-time-settings/$setting_id" "qa-system-time-change-$stamp" \
  "$(jq -nc --argjson enabled "$(jq -r .enabled <<<"$setting")" --argjson days "$changed_days" --argjson version "$(jq -r .version <<<"$setting")" '{enabled:$enabled,allowedDays:$days,expectedVersion:$version}')")"
write PUT "/api/system-experience/transition-time-settings/$setting_id" "qa-system-time-restore-$stamp" \
  "$(jq -nc --argjson enabled "$(jq -r .enabled <<<"$setting")" --argjson days "$original_days" --argjson version "$(jq -r .version <<<"$setting_changed")" '{enabled:$enabled,allowedDays:$days,expectedVersion:$version}')" >/dev/null
get /api/system-experience/transition-warnings >/dev/null

# Reuse the complete commission parity fixture, then advance only its SHOWCASE record to an assigned task.
commission_output="$(QA_USERNAME="$QA_USERNAME" QA_PASSWORD="$QA_PASSWORD" BASE_URL="$BASE_URL" REVISION_ID="$REVISION_ID" \
  "$(dirname "$0")/runtime-commission-qa.sh")"
commission_id="$(sed -n 's/^commission=\([^ ]*\).*/\1/p' <<<"$commission_output" | tail -n 1)"
[[ -n "$commission_id" ]] || { echo 'commission parity fixture did not return an id' >&2; exit 1; }
commission="$(get "/api/commissions/$commission_id")"
commission_version="$(jq -r .version <<<"$commission")"
for action in SUBMIT ACCEPT START_SAMPLING; do
  next="$(transition commissions "$commission_id" "$action" "$commission_version" "$stamp-$action")"
  commission_version="$(jq -r .version <<<"$next")"
done
selection="$(get "/api/commissions/$commission_id/detection-selections")"
parameter_id="$(jq -r '.[0].parameters[0].parameterId // empty' <<<"$selection")"
sample="$(get "/api/samples?commissionId=$commission_id" | jq -c '.[0]')"
sample_id="$(jq -r .id <<<"$sample")"
sample_version="$(jq -r .version <<<"$sample")"
for action in RECEIVE HANDOVER; do
  next="$(transition samples "$sample_id" "$action" "$sample_version" "$stamp-$action")"
  sample_version="$(jq -r .version <<<"$next")"
done
commission_complete="$(transition commissions "$commission_id" COMPLETE_SAMPLING "$commission_version" "$stamp-complete-sampling")"
task="$(write POST /api/tasks "qa-system-task-create-$stamp" \
  "$(jq -nc --arg commission "$commission_id" --arg sample "$sample_id" --arg parameter "$parameter_id" '{commissionId:$commission,sampleId:$sample,parameterId:$parameter,dueAt:null}')")"
task_id="$(jq -r .id <<<"$task")"
task_assigned="$(write POST "/api/tasks/$task_id/transitions" "qa-system-task-assign-$stamp" \
  "$(jq -nc --arg person "$QUALIFIED_PERSON_ID" --argjson version "$(jq -r .version <<<"$task")" '{action:"ASSIGN",expectedVersion:$version,assignedTo:$person,reasonCode:"SHOWCASE_PARITY",comment:"安全交底运行验收"}')")"

briefing_payload="$(jq -nc --arg revision "$REVISION_ID" --arg stamp "$stamp" '{
  detectionProjectRevisionId:$revision,code:("SHOWCASE-SAFETY-"+$stamp),title:("SHOWCASE 安全交底 "+$stamp),
  briefingType:"PROJECT_SAFETY",content:"SHOWCASE 合成安全交底：确认检测环境、个人防护和设备状态后方可接收任务。",
  readSeconds:5,expectedVersion:null
}')"
briefing="$(write POST /api/system-experience/safety-briefings "qa-system-briefing-create-$stamp" "$briefing_payload")"
briefing_id="$(jq -r .id <<<"$briefing")"
briefing_active="$(transition system-experience/safety-briefings "$briefing_id" ACTIVATE "$(jq -r .version <<<"$briefing")" "$stamp")"
expect_problem 422 SAFETY_BRIEFING_REQUIRED POST "/api/tasks/$task_id/transitions" "qa-system-task-accept-blocked-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$task_assigned")" '{action:"ACCEPT",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"未交底时必须阻断"}')"
task_briefing="$(get "/api/tasks/$task_id/safety-briefings" | jq -c --arg id "$briefing_id" '[.[] | select(.id==$id)][0]')"
started="$(write POST "/api/tasks/$task_id/safety-briefings/$briefing_id/read-sessions" "qa-system-briefing-start-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .ruleVersion <<<"$task_briefing")" '{ruleVersion:$version}')")"
expect_problem 422 READ_TIME_NOT_REACHED POST "/api/tasks/$task_id/safety-briefings/$briefing_id/acknowledgements" \
  "qa-system-briefing-early-$stamp" \
  "$(jq -nc --argjson rule "$(jq -r .ruleVersion <<<"$started")" --argjson version "$(jq -r .readVersion <<<"$started")" '{ruleVersion:$rule,expectedVersion:$version}')"
sleep 6
acknowledged="$(write POST "/api/tasks/$task_id/safety-briefings/$briefing_id/acknowledgements" \
  "qa-system-briefing-ack-$stamp" \
  "$(jq -nc --argjson rule "$(jq -r .ruleVersion <<<"$started")" --argjson version "$(jq -r .readVersion <<<"$started")" '{ruleVersion:$rule,expectedVersion:$version}')")"
[[ -n "$(jq -r '.acknowledgedAt // empty' <<<"$acknowledged")" ]]
task_accepted="$(write POST "/api/tasks/$task_id/transitions" "qa-system-task-accept-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$task_assigned")" '{action:"ACCEPT",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"安全交底完成后接收"}')")"
[[ "$(jq -r .state <<<"$task_accepted")" == "ACCEPTED" ]]
transition system-experience/safety-briefings "$briefing_id" DISABLE "$(jq -r .version <<<"$briefing_active")" "$stamp-disable" >/dev/null

printf 'system-experience theme=%s restored=%s contrast=422 transition-time=restored briefing=%s task=%s blocked=422 early=422 accepted=verified commission=%s\n' \
  "$theme_id" "$restored_id" "$briefing_id" "$task_id" "$commission_id"
