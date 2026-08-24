#!/usr/bin/env bash
set -euo pipefail

: "${QA_PASSWORD:?Set QA_PASSWORD without writing it to the repository}"
QA_USERNAME="${QA_USERNAME:-showcase-admin}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
stamp="$(date +%Y%m%d%H%M%S)"
response_file="$(mktemp /tmp/lims-v30-response.XXXXXX)"
trap 'rm -f "$response_file"' EXIT

login_payload="$(jq -nc --arg username "$QA_USERNAME" --arg password "$QA_PASSWORD" '{username:$username,password:$password}')"
login_headers="$(curl -sS -D - -o /dev/null -H 'Content-Type: application/json' -d "$login_payload" "$BASE_URL/api/auth/login")"
session_cookie="$(printf '%s\n' "$login_headers" | sed -n 's/^Set-Cookie: SESSION=\([^;]*\).*/\1/p' | tr -d '\r' | tail -1)"
[[ -n "$session_cookie" ]] || { echo 'login failed: no session cookie returned' >&2; exit 1; }
csrf_headers="$(curl -sS -D - -o /dev/null -H "Cookie: SESSION=$session_cookie" "$BASE_URL/api/auth/me")"
csrf_token="$(printf '%s\n' "$csrf_headers" | sed -n 's/^Set-Cookie: XSRF-TOKEN=\([^;]*\).*/\1/p' | tr -d '\r' | tail -1)"
[[ -n "$csrf_token" ]] || { echo 'login failed: no CSRF token returned' >&2; exit 1; }
cookie_header="SESSION=$session_cookie; XSRF-TOKEN=$csrf_token"

get() { curl -fsS -H "Cookie: $cookie_header" "$BASE_URL$1"; }
write() {
  curl -fsS -X "$1" -H "Cookie: $cookie_header" -H "X-XSRF-TOKEN: $csrf_token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "$4" "$BASE_URL$2"
}
status_write() {
  curl -sS -o "$response_file" -w '%{http_code}' -X "$1" -H "Cookie: $cookie_header" \
    -H "X-XSRF-TOKEN: $csrf_token" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $3" -d "$4" "$BASE_URL$2"
}

summary_options="$(get '/api/quality-summary-documents/options?q=SHOWCASE')"
project_id="$(jq -r '.projects[0].id // empty' <<<"$summary_options")"
customer_id="$(jq -r '.projects[0].customerId // .customers[0].id // empty' <<<"$summary_options")"
section_name="$(jq -r '.projects[0].sectionName // "SHOWCASE 标段"' <<<"$summary_options")"
[[ -n "$project_id" && -n "$customer_id" ]] || { echo 'no SHOWCASE project/customer available' >&2; exit 1; }

stage_payload="$(jq -nc --arg number "SHOWCASE-STAGE-$stamp" --arg project "$project_id" \
  --arg customer "$customer_id" --arg section "${section_name:-SHOWCASE 标段}" '{
  documentType:"STAGE",number:$number,projectId:$project,customerId:$customer,sectionName:$section,
  periodStart:"2026-08-01",periodEnd:"2026-08-20",reportOn:"2026-08-20",
  contentCodes:["FOUNDATION_ENGINEERING","CONCRETE_ENGINEERING"],
  conclusion:"SHOWCASE 阶段成果运行验收",attachmentFileIds:[],expectedVersion:null
}')"
stage="$(write POST /api/quality-summary-documents "qa-v30-stage-create-$stamp" "$stage_payload")"
stage_id="$(jq -r .id <<<"$stage")"; stage_version="$(jq -r .version <<<"$stage")"
publish_payload="$(jq -nc --argjson version "$stage_version" '{action:"PUBLISH",expectedVersion:$version,reasonCode:"QUALITY_SUMMARY_CONTROL",comment:"SHOWCASE V30 发布验收"}')"
published="$(write POST "/api/quality-summary-documents/$stage_id/transitions" "qa-v30-stage-publish-$stamp" "$publish_payload")"
[[ "$(jq -r .state <<<"$published")" == 'PUBLISHED' ]]
stale_status="$(status_write POST "/api/quality-summary-documents/$stage_id/transitions" "qa-v30-stage-stale-$stamp" "$publish_payload")"
[[ "$stale_status" == 409 && "$(jq -r .code "$response_file")" == 'VERSION_CONFLICT' ]]
stage="$(get "/api/quality-summary-documents/$stage_id")"; stage_version="$(jq -r .version <<<"$stage")"
retire_payload="$(jq -nc --argjson version "$stage_version" '{action:"RETIRE",expectedVersion:$version,reasonCode:"QUALITY_SUMMARY_CONTROL",comment:"SHOWCASE V30 发布链验收完成"}')"
retired="$(write POST "/api/quality-summary-documents/$stage_id/transitions" "qa-v30-stage-retire-$stamp" "$retire_payload")"
[[ "$(jq -r .state <<<"$retired")" == 'RETIRED' ]]

comprehensive_payload="$(jq -nc --arg number "SHOWCASE-COMPREHENSIVE-$stamp" --arg project "$project_id" \
  --arg customer "$customer_id" --arg section "${section_name:-SHOWCASE 标段}" '{
  documentType:"COMPREHENSIVE",number:$number,projectId:$project,customerId:$customer,sectionName:$section,
  periodStart:null,periodEnd:null,reportOn:"2026-08-20",contentCodes:["CONCRETE_ENGINEERING"],
  conclusion:"SHOWCASE 综合报告运行验收",attachmentFileIds:[],expectedVersion:null
}')"
comprehensive="$(write POST /api/quality-summary-documents "qa-v30-comprehensive-create-$stamp" "$comprehensive_payload")"
comprehensive_id="$(jq -r .id <<<"$comprehensive")"; comprehensive_version="$(jq -r .version <<<"$comprehensive")"
discard_payload="$(jq -nc --argjson version "$comprehensive_version" '{action:"DISCARD",expectedVersion:$version,reasonCode:"QUALITY_SUMMARY_CONTROL",comment:"SHOWCASE V30 草稿作废验收"}')"
discarded="$(write POST "/api/quality-summary-documents/$comprehensive_id/transitions" "qa-v30-comprehensive-discard-$stamp" "$discard_payload")"
[[ "$(jq -r .state <<<"$discarded")" == 'RETIRED' ]]

strength_options='{}'
strength_project_id=''
while IFS= read -r candidate_project_id; do
  candidate_options="$(get "/api/concrete-strength-assessments/options?q=%E6%8A%97%E5%8E%8B%E5%BC%BA%E5%BA%A6&projectId=$candidate_project_id")"
  if [[ "$(jq -r '((.structureParts // []) | length) > 0 and ([((.parameters // [])[]) | select(((.standards // []) | length) > 0)] | length) > 0' <<<"$candidate_options")" == true ]]; then
    strength_project_id="$candidate_project_id"
    strength_options="$candidate_options"
    break
  fi
done < <(jq -r '.projects[].id' <<<"$summary_options")
part_id="$(jq -r '(.structureParts // [])[0].id // empty' <<<"$strength_options")"
parameter_id="$(jq -r '[((.parameters // [])[]) | select(((.standards // []) | length) > 0)][0].id // empty' <<<"$strength_options")"
revision_id="$(jq -r '[((.parameters // [])[]) | select(((.standards // []) | length) > 0)][0].projectRevisionId // empty' <<<"$strength_options")"
standard_id="$(jq -r '[((.parameters // [])[]) | select(((.standards // []) | length) > 0)][0].standards[0].id // empty' <<<"$strength_options")"
[[ -n "$strength_project_id" && -n "$part_id" && -n "$parameter_id" && -n "$revision_id" && -n "$standard_id" ]] || {
  echo 'no controlled project part/strength parameter/standard available' >&2; exit 1;
}
strength_payload="$(jq -nc --arg project "$strength_project_id" --arg part "$part_id" --arg revision "$revision_id" \
  --arg parameter "$parameter_id" --arg standard "$standard_id" '{
  projectId:$project,structurePartId:$part,detectionProjectRevisionId:$revision,parameterId:$parameter,
  standardId:$standard,designRequirement:"C30",periodStart:"2020-01-01",periodEnd:"2030-12-31",
  concreteQuantity:"100",standardDeviationAverageLevel:"4.5",remark:"SHOWCASE V30 统计门禁验收",expectedVersion:null
}')"
assessment="$(write POST /api/concrete-strength-assessments "qa-v30-assessment-create-$stamp" "$strength_payload")"
assessment_id="$(jq -r .id <<<"$assessment")"; assessment_version="$(jq -r .version <<<"$assessment")"
calculate_payload="$(jq -nc --argjson version "$assessment_version" '{action:"CALCULATE",expectedVersion:$version,reasonCode:"CONCRETE_STRENGTH_CONTROL",comment:"SHOWCASE V30 描述性统计验收"}')"
calculate_status="$(status_write POST "/api/concrete-strength-assessments/$assessment_id/transitions" "qa-v30-assessment-calculate-$stamp" "$calculate_payload")"
if [[ "$calculate_status" == 200 ]]; then
  assessment="$(get "/api/concrete-strength-assessments/$assessment_id")"
  [[ "$(jq -r .state <<<"$assessment")" == 'CALCULATED' && "$(jq -r '.results | length > 0' <<<"$assessment")" == true ]]
  assessment_version="$(jq -r .version <<<"$assessment")"
  submit_payload="$(jq -nc --argjson version "$assessment_version" '{action:"SUBMIT_REVIEW",expectedVersion:$version,reasonCode:"CONCRETE_STRENGTH_CONTROL",comment:"规则阻断验收"}')"
  submit_status="$(status_write POST "/api/concrete-strength-assessments/$assessment_id/transitions" "qa-v30-assessment-submit-$stamp" "$submit_payload")"
  [[ "$submit_status" == 422 && "$(jq -r .code "$response_file")" == 'CONCRETE_ASSESSMENT_RULE_NOT_READY' ]]
  retire_payload="$(jq -nc --argjson version "$assessment_version" '{action:"RETIRE",expectedVersion:$version,reasonCode:"CONCRETE_STRENGTH_CONTROL",comment:"SHOWCASE V30 统计验收完成"}')"
  write POST "/api/concrete-strength-assessments/$assessment_id/transitions" "qa-v30-assessment-retire-$stamp" "$retire_payload" >/dev/null
  strength_result='statistics=frozen rule=blocked422'
else
  [[ "$calculate_status" == 422 && "$(jq -r .code "$response_file")" == 'CONCRETE_ASSESSMENT_NO_RESULTS' ]]
  discard_payload="$(jq -nc --argjson version "$assessment_version" '{action:"DISCARD",expectedVersion:$version,reasonCode:"CONCRETE_STRENGTH_CONTROL",comment:"SHOWCASE V30 无来源记录阻断验收完成"}')"
  write POST "/api/concrete-strength-assessments/$assessment_id/transitions" "qa-v30-assessment-discard-$stamp" "$discard_payload" >/dev/null
  strength_result='no-results=blocked422'
fi

stage_list="$(get '/api/quality-summary-documents?documentType=STAGE&number=SHOWCASE&limit=20')"
strength_list="$(get '/api/concrete-strength-assessments?projectName=SHOWCASE&limit=20')"
[[ "$(jq -r 'has("items") and has("total") and has("hasMore")' <<<"$stage_list")" == true ]]
[[ "$(jq -r 'has("items") and has("total") and has("hasMore")' <<<"$strength_list")" == true ]]
printf 'summary=publish/stale409/retire comprehensive=discard %s lists=cursor200\n' "$strength_result"
