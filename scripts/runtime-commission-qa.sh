#!/usr/bin/env bash
set -euo pipefail

: "${QA_PASSWORD:?Set QA_PASSWORD without writing it to the repository}"
QA_USERNAME="${QA_USERNAME:-admin}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
REVISION_ID="${REVISION_ID:-636b2dcc-dde3-507d-b06d-923a69b9777a}"

for command in curl jq unzip; do
  command -v "$command" >/dev/null || { echo "missing command: $command" >&2; exit 1; }
done

login_payload="$(jq -nc --arg username "$QA_USERNAME" --arg password "$QA_PASSWORD" \
  '{username:$username,password:$password}')"
login_headers="$(curl -sS -D - -o /dev/null -H 'Content-Type: application/json' \
  -d "$login_payload" "$BASE_URL/api/auth/login")"
session_cookie="$(printf '%s\n' "$login_headers" | sed -n 's/^Set-Cookie: SESSION=\([^;]*\).*/\1/p' | tr -d '\r' | tail -n 1)"
[[ -n "$session_cookie" ]] || { echo 'login failed: no session cookie returned' >&2; exit 1; }
csrf_headers="$(curl -sS -D - -o /dev/null -H "Cookie: SESSION=$session_cookie" "$BASE_URL/api/auth/me")"
csrf_token="$(printf '%s\n' "$csrf_headers" | sed -n 's/^Set-Cookie: XSRF-TOKEN=\([^;]*\).*/\1/p' | tr -d '\r' | tail -n 1)"
[[ -n "$csrf_token" ]] || { echo 'login failed: no CSRF token returned' >&2; exit 1; }
cookie_header="SESSION=$session_cookie; XSRF-TOKEN=$csrf_token"

get() {
  curl -fsS -H "Cookie: $cookie_header" "$BASE_URL$1"
}

write() {
  local method="$1" path="$2" key="$3" payload="$4"
  local response_file status
  response_file="$(mktemp /tmp/lims-commission-write.XXXXXX)"
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
  response_file="$(mktemp /tmp/lims-commission-problem.XXXXXX)"
  status="$(curl -sS -o "$response_file" -w '%{http_code}' -X "$method" \
    -H "Cookie: $cookie_header" -H "X-XSRF-TOKEN: $csrf_token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
    -d "$payload" "$BASE_URL$path")"
  actual_code="$(jq -r '.code // empty' "$response_file")"
  rm -f "$response_file"
  [[ "$status" == "$expected_status" && "$actual_code" == "$expected_code" ]] || {
    echo "expected HTTP $expected_status/$expected_code from $path, got $status/$actual_code" >&2
    exit 1
  }
}

stamp="$(date +%Y%m%d%H%M%S)"
today="$(date +%Y-%m-%d)"
due_on="$(date -v+15d +%Y-%m-%d)"

references="$(get /api/reference-data)"
project_id="$(jq -r '.projects[0].id // empty' <<<"$references")"
[[ -n "$project_id" ]] || { echo 'no active project available' >&2; exit 1; }
defaults="$(get "/api/commissions/project-defaults/$project_id")"
customer_id="$(jq -r '.customerId // empty' <<<"$defaults")"
[[ -n "$customer_id" ]] || { echo 'project has no customer' >&2; exit 1; }

revision="$(get "/api/detection-project-revisions/$REVISION_ID")"
parameter_id="$(jq -r '[.parameters[] | select(.status=="PUBLISHED" and .enabled)][0].id // empty' <<<"$revision")"
judge_standard="$(jq -r '[.sampleFields[] | select(.code=="judge_standard")][0].standards[0].id // empty' <<<"$revision")"
test_standard="$(jq -r '[.sampleFields[] | select(.code=="test_standard")][0].standards[0].id // empty' <<<"$revision")"
[[ -n "$parameter_id" && -n "$judge_standard" && -n "$test_standard" ]] || {
  echo 'water-cement parameter or controlled standards are incomplete' >&2
  exit 1
}

details="$(jq -nc --arg stamp "$stamp" '{
  projectProgress:"FOUNDATION_FACILITIES",testingType:"PARALLEL_SUPERVISION",
  testingStandard:"NATIONAL",testingPlace:"LAB",sampleHandling:"CLIENT_DISPOSE",
  reportDelivery:"PICKUP",reportDeliveryAddress:"",reportCopies:2,
  projectPart:("SHOWCASE 结构部位 "+$stamp),monomer:"SHOWCASE 单体",
  settlementContact:"SHOWCASE 结算联系人",witnessUnit:"SHOWCASE 见证单位",
  witnessName:"见证员",witnessPhone:"13800000000",witnessCertificate:"SHOWCASE-QA-WITNESS",
  samplingUnit:"SHOWCASE 取样单位",samplingPerson:"取样员",samplingPhone:"13900000000",
  samplingCertificate:"SHOWCASE-QA-SAMPLE",constructionUnit:"SHOWCASE 施工单位",
  needSampling:false,electronicSignature:true,cmaSeal:true,qualificationSeal:false,
  externalCommission:false,externalOrganization:"",externalNumber:"",
  sampleReceivingAddress:"SHOWCASE 样品接收地址",expectedReportOn:null,
  remark:("SHOWCASE 委托闭环验收 "+$stamp),sourceRemark:"仅用于生产验收",
  ageReportValues:["3"],attachmentFileIds:[]
}')"

selection="$(jq -nc --arg revision "$REVISION_ID" --arg parameter "$parameter_id" \
  --arg judge "$judge_standard" --arg test "$test_standard" --arg today "$today" --arg stamp "$stamp" '{
  projectRevisionId:$revision,parameterIds:[$parameter],sampleGroups:[{
    clientKey:("SHOWCASE-SG-"+$stamp),name:"水泥",description:"SHOWCASE 合成验收样品",
    quantity:"1",unit:"组",parameterIds:[$parameter],fieldValues:{
      sample_name:[],money:["125.50"],design_grade:["P·O42.5"],produce_batch:[("SHOWCASE-"+$stamp)],
      project_part:[("SHOWCASE 结构部位 "+$stamp)],producer_name:["SHOWCASE 厂家"],
      sampling_time:[$today],forming_date:[$today],smaple_number:["50"],represent_number:["200"],
      sampling_location:["SHOWCASE 取样点"],sample_status:["正常"],sample_desc:[],
      remark:["闭环验收"],sample_person:["取样员"],sg_unit_person:["施工代表"],
      from_sample_sn:[("SHOWCASE-SAMPLE-"+$stamp)],judge_standard:[$judge],test_standard:[$test]
    }
  }]
}')"

payload="$(jq -nc --arg project "$project_id" --arg customer "$customer_id" --arg today "$today" \
  --arg due "$due_on" --argjson details "$details" --argjson selection "$selection" '{
    projectId:$project,customerId:$customer,contactName:"SHOWCASE 委托人",contactPhone:"13700000000",
    commissionedOn:$today,dueOn:$due,details:$details,detectionSelections:[$selection]
  }')"
missing_payload="$(jq '.detectionSelections[0].sampleGroups[0].fieldValues.design_grade=[]' <<<"$payload")"
expect_problem 422 COMMISSION_SAMPLE_FIELD_REQUIRED POST /api/commissions \
  "qa-commission-required-$stamp" "$missing_payload"

create_key="qa-commission-create-$stamp"
created="$(write POST /api/commissions "$create_key" "$payload")"
commission_id="$(jq -r .id <<<"$created")"
commission_number="$(jq -r .number <<<"$created")"
replayed="$(write POST /api/commissions "$create_key" "$payload")"
[[ "$(jq -r .id <<<"$replayed")" == "$commission_id" ]]
expect_problem 409 IDEMPOTENCY_KEY_REUSED POST /api/commissions "$create_key" \
  "$(jq '.contactName="SHOWCASE 其他委托人"' <<<"$payload")"

detail="$(get "/api/commissions/$commission_id")"
version="$(jq -r .version <<<"$detail")"
selections="$(get "/api/commissions/$commission_id/detection-selections")"
[[ "$(jq -r '.[0].sampleGroups[0].fieldValues.design_grade[0]' <<<"$selections")" == "P·O42.5" ]]
[[ "$(jq -r '.[0].sampleGroups[0].fieldValues.judge_standard[0]' <<<"$selections")" == "$judge_standard" ]]
sample_group_id="$(jq -r '.[0].sampleGroups[0].id // empty' <<<"$selections")"
[[ -n "$sample_group_id" ]] || { echo 'created commission has no sample group id' >&2; exit 1; }

update_payload="$(jq --argjson version "$version" '
  del(.projectId,.customerId) | .details.remark += "（已更新）" | .expectedVersion=$version
' <<<"$payload")"
updated="$(write PUT "/api/commissions/$commission_id" "qa-commission-update-$stamp" "$update_payload")"
updated_version="$(jq -r .version <<<"$updated")"
expect_problem 409 VERSION_CONFLICT PUT "/api/commissions/$commission_id" \
  "qa-commission-stale-update-$stamp" "$update_payload"

list="$(get "/api/commissions?commissionNumber=$(printf '%s' "$commission_number" | jq -sRr @uri)&limit=20")"
[[ "$(jq -r --arg id "$commission_id" '[.items[].id] | index($id) != null' <<<"$list")" == true ]]
[[ "$(jq -r --arg id "$commission_id" '[.items[] | select(.id==$id)][0].flowStatus' <<<"$list")" == "PENDING" ]]
sample_list="$(get "/api/commissions?viewMode=SAMPLE&commissionNumber=$(printf '%s' "$commission_number" | jq -sRr @uri)&limit=20")"
[[ "$(jq -r --arg id "$commission_id" '[.items[] | select(.id==$id and .sampleGroupId!=null and .sampleName=="水泥")] | length' <<<"$sample_list")" == 1 ]]

printed="$(write POST "/api/commissions/$commission_id/prints" "qa-commission-print-$stamp" \
  "$(jq -nc --argjson version "$updated_version" '{documentType:"COMMISSION",expectedVersion:$version}')")"
current_version="$(jq -r .version <<<"$printed")"
internal_sequence="$(printf '%s' "$stamp" | tail -c 9)"
sequence_result="$(write POST /api/commissions/batch-internal-sequences "qa-commission-sequence-$stamp" \
  "$(jq -nc --arg id "$commission_id" --argjson version "$current_version" --argjson sequence "$internal_sequence" \
    '{targets:[{id:$id,expectedVersion:$version,internalSequence:$sequence}],comment:"SHOWCASE 内部流水验收"}')")"
[[ "$(jq -r .completed <<<"$sequence_result")" == 1 ]]
current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
sequence_list="$(get "/api/commissions?commissionNumber=$(printf '%s' "$commission_number" | jq -sRr @uri)&limit=20")"
[[ "$(jq -r --arg id "$commission_id" '[.items[] | select(.id==$id)][0].internalSequence' <<<"$sequence_list")" == "$internal_sequence" ]]
copied="$(write POST "/api/commissions/$commission_id/copies" "qa-commission-copy-$stamp" \
  "$(jq -nc --argjson version "$current_version" '{expectedVersion:$version}')")"
copy_id="$(jq -r .id <<<"$copied")"
copy_version="$(jq -r .version <<<"$copied")"

relation="$(write POST "/api/commissions/$commission_id/relations" "qa-commission-relation-$stamp" \
  "$(jq -nc --arg target "$copy_id" --argjson version "$current_version" \
    '{targetCommissionId:$target,relationType:"RELATED",comment:"SHOWCASE 关联验收",expectedVersion:$version}')")"
[[ "$(jq -r .targetCommissionId <<<"$relation")" == "$copy_id" ]]
current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"

departments="$(get /api/master-data/departments)"
target_department="$(jq -r '[.[] | select(.code=="SHOWCASE-QA")][0].id // empty' <<<"$departments")"
if [[ -z "$target_department" ]]; then
  department="$(write POST /api/master-data/departments "qa-commission-department-$stamp" \
    '{"code":"SHOWCASE-QA","name":"SHOWCASE 转交验收部门","parentId":null}')"
  target_department="$(jq -r .id <<<"$department")"
fi
transfer="$(write POST "/api/commissions/$commission_id/transfers" "qa-commission-transfer-$stamp" \
  "$(jq -nc --arg target "$target_department" --argjson version "$current_version" \
    '{toDepartmentId:$target,comment:"SHOWCASE 转交验收",expectedVersion:$version}')")"
[[ "$(jq -r '.allowedActions | index("CANCEL") != null' <<<"$transfer")" == true ]]
transfer_id="$(jq -r .id <<<"$transfer")"
transfer_version="$(jq -r .version <<<"$transfer")"
write POST "/api/commissions/transfers/$transfer_id/transitions" "qa-commission-transfer-cancel-$stamp" \
  "$(jq -nc --argjson version "$transfer_version" \
    '{action:"CANCEL",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"转交撤销验收"}')" >/dev/null

current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
copy_version="$(jq -r .version <<<"$(get "/api/commissions/$copy_id")")"
stale_copy_version="$((copy_version + 99))"
sequence_rollback_payload="$(jq -nc --arg first "$commission_id" --arg second "$copy_id" \
  --argjson firstVersion "$current_version" --argjson secondVersion "$stale_copy_version" \
  --argjson original "$internal_sequence" '{
    targets:[
      {id:$first,expectedVersion:$firstVersion,internalSequence:($original+1)},
      {id:$second,expectedVersion:$secondVersion,internalSequence:90210}
    ],comment:"内部流水批量事务回滚验收"
  }')"
expect_problem 409 VERSION_CONFLICT POST /api/commissions/batch-internal-sequences \
  "qa-commission-sequence-rollback-$stamp" "$sequence_rollback_payload"
sequence_after_rollback="$(get "/api/commissions?commissionNumber=$(printf '%s' "$commission_number" | jq -sRr @uri)&limit=20")"
[[ "$(jq -r --arg id "$commission_id" '[.items[] | select(.id==$id)][0].internalSequence' <<<"$sequence_after_rollback")" == "$internal_sequence" ]]
batch_payload="$(jq -nc --arg first "$commission_id" --arg second "$copy_id" \
  --argjson firstVersion "$current_version" --argjson secondVersion "$stale_copy_version" '{
    targets:[{id:$first,expectedVersion:$firstVersion},{id:$second,expectedVersion:$secondVersion}],
    action:"SUBMIT",reasonCode:"SHOWCASE_PARITY",comment:"批量事务回滚验收"
  }')"
expect_problem 409 VERSION_CONFLICT POST /api/commissions/batch-transitions \
  "qa-commission-batch-rollback-$stamp" "$batch_payload"
[[ "$(jq -r .state <<<"$(get "/api/commissions/$commission_id")")" == "DRAFT" ]]

current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
submitted="$(write POST "/api/commissions/$commission_id/transitions" "qa-commission-submit-$stamp" \
  "$(jq -nc --argjson version "$current_version" \
    '{action:"SUBMIT",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"委托提交验收"}')")"
[[ "$(jq -r .state <<<"$submitted")" == "PRE_ACCEPTED" ]]
withdrawn="$(write POST "/api/commissions/$commission_id/transitions" "qa-commission-withdraw-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$submitted")" \
    '{action:"WITHDRAW",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"委托撤回验收"}')")"
[[ "$(jq -r .state <<<"$withdrawn")" == "DRAFT" ]]

current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
external_number="SHOWCASE-EXT-$stamp"
external_bound="$(write POST "/api/commissions/$commission_id/external-number-bindings" \
  "qa-commission-external-$stamp" \
  "$(jq -nc --arg number "$external_number" --argjson version "$current_version" \
    '{externalNumber:$number,expectedVersion:$version,comment:"SHOWCASE 外部编号绑定验收"}')")"
[[ "$(jq -r .externalNumber <<<"$external_bound")" == "$external_number" ]]
expect_problem 409 VERSION_CONFLICT POST "/api/commissions/$commission_id/external-number-bindings" \
  "qa-commission-external-stale-$stamp" \
  "$(jq -nc --arg number "SHOWCASE-EXT-STALE-$stamp" --argjson version "$current_version" \
    '{externalNumber:$number,expectedVersion:$version,comment:"过期版本验收"}')"
copy_version="$(jq -r .version <<<"$(get "/api/commissions/$copy_id")")"
expect_problem 409 COMMISSION_EXTERNAL_NUMBER_DUPLICATE POST "/api/commissions/$copy_id/external-number-bindings" \
  "qa-commission-external-duplicate-$stamp" \
  "$(jq -nc --arg number "$external_number" --argjson version "$copy_version" \
    '{externalNumber:$number,expectedVersion:$version,comment:"重复编号验收"}')"

current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
restriction="$(write POST "/api/commissions/$commission_id/report-print-restrictions" \
  "qa-commission-print-restriction-$stamp" \
  "$(jq -nc --argjson version "$current_version" \
    '{restricted:true,printLimit:1,reason:"SHOWCASE 报告打印限制验收",expectedVersion:$version}')")"
[[ "$(jq -r '.restricted and .printLimit==1' <<<"$restriction")" == true ]]
restriction_read="$(get "/api/commissions/$commission_id/report-print-restriction")"
[[ "$(jq -r '.restricted and .printLimit==1' <<<"$restriction_read")" == true ]]

parts="$(get "/api/projects/$project_id/parts")"
part_id="$(jq -r '[.[] | select(.active)][0].id // empty' <<<"$parts")"
if [[ -z "$part_id" ]]; then
  part="$(write POST "/api/projects/$project_id/parts" "qa-project-part-$stamp" \
    "$(jq -nc --arg stamp "$stamp" '{parentId:null,code:("SHOWCASE-"+$stamp),name:("SHOWCASE 分部分项 "+$stamp),sortOrder:999,active:true,expectedVersion:null}')")"
  part_id="$(jq -r .id <<<"$part")"
fi
current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
sample_group_id="$(get "/api/commissions/$commission_id/detection-selections" \
  | jq -r '.[0].sampleGroups[0].id // empty')"
[[ -n "$sample_group_id" ]] || { echo 'updated commission has no sample group id' >&2; exit 1; }
bound_parts="$(write POST "/api/commissions/$commission_id/sample-group-parts" \
  "qa-commission-parts-$stamp" \
  "$(jq -nc --arg group "$sample_group_id" --arg part "$part_id" --argjson version "$current_version" \
    '{bindings:[{sampleGroupId:$group,projectPartIds:[$part]}],expectedVersion:$version,comment:"SHOWCASE 样品组分部分项绑定验收"}')")"
[[ "$(jq -r --arg part "$part_id" '[.[] | select(.projectPartId==$part)] | length' <<<"$bound_parts")" == 1 ]]

current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
copy_version="$(jq -r .version <<<"$(get "/api/commissions/$copy_id")")"
stale_copy_version="$((copy_version + 99))"
expect_problem 409 VERSION_CONFLICT POST /api/commissions/batch-copies \
  "qa-commission-batch-copy-rollback-$stamp" \
  "$(jq -nc --arg first "$commission_id" --arg second "$copy_id" \
    --argjson firstVersion "$current_version" --argjson secondVersion "$stale_copy_version" \
    '{targets:[{id:$first,expectedVersion:$firstVersion},{id:$second,expectedVersion:$secondVersion}],comment:"批量复制事务回滚验收"}')"
batch_copied="$(write POST /api/commissions/batch-copies "qa-commission-batch-copy-$stamp" \
  "$(jq -nc --arg first "$commission_id" --arg second "$copy_id" \
    --argjson firstVersion "$current_version" --argjson secondVersion "$copy_version" \
    '{targets:[{id:$first,expectedVersion:$firstVersion},{id:$second,expectedVersion:$secondVersion}],comment:"SHOWCASE 批量复制验收"}')")"
[[ "$(jq -r '.requested==2 and .completed==2 and (.ids|length)==2' <<<"$batch_copied")" == true ]]

export_job="$(write POST /api/commissions/exports "qa-commission-export-$stamp" \
  "$(jq -nc --arg first "$commission_id" --arg second "$copy_id" \
    --argjson firstVersion "$current_version" --argjson secondVersion "$copy_version" \
    '{targets:[{id:$first,expectedVersion:$firstVersion},{id:$second,expectedVersion:$secondVersion}],comment:"SHOWCASE XLSX 导出验收"}')")"
job_id="$(jq -r .jobId <<<"$export_job")"
job_state=""
job='{}'
for _ in {1..80}; do
  job="$(get "/api/jobs/$job_id")"
  job_state="$(jq -r .state <<<"$job")"
  [[ "$job_state" == "COMPLETED" || "$job_state" == "FAILED" ]] && break
  sleep 0.25
done
[[ "$job_state" == "COMPLETED" ]] || { echo "commission export job ended in $job_state: $(jq -r '.lastError // ""' <<<"$job")" >&2; exit 1; }
result_file_id="$(jq -r .resultFileId <<<"$job")"
[[ -n "$result_file_id" && "$result_file_id" != null ]]
export_file="$(mktemp /tmp/lims-commission-export.XXXXXX.xlsx)"
curl -fsS -H "Cookie: $cookie_header" "$BASE_URL/api/files/$result_file_id" -o "$export_file"
unzip -tqq "$export_file"
if ! unzip -p "$export_file" xl/sharedStrings.xml 2>/dev/null | grep -Fq "$commission_number"; then
  unzip -p "$export_file" xl/worksheets/sheet1.xml | grep -Fq "$commission_number"
fi
rm -f "$export_file"

readiness="$(get "/api/commissions/$commission_id/electronic-document-readiness")"
[[ "$(jq -r '(.ready|not) and (.blockers|length>0)' <<<"$readiness")" == true ]]
current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
expect_problem 422 COMMISSION_TEMPLATE_NOT_READY POST "/api/commissions/$commission_id/electronic-document-refreshes" \
  "qa-commission-electronic-blocked-$stamp" "$(jq -nc --argjson version "$current_version" '{expectedVersion:$version}')"
expect_problem 422 COMMISSION_TEST_TERMINATION_STATE_BLOCKED POST "/api/commissions/$commission_id/test-terminations" \
  "qa-commission-termination-blocked-$stamp" \
  "$(jq -nc --argjson version "$current_version" '{reasonCode:"CLIENT_CANCELLED",comment:"SHOWCASE 草稿阶段禁止终止验收",expectedVersion:$version}')"

flow_events="$(get "/api/commissions/$commission_id/flow-events")"
for action in COMMISSION_EXTERNAL_NUMBER_BOUND COMMISSION_REPORT_PRINT_RESTRICTION_UPDATED \
  COMMISSION_SAMPLE_GROUP_PARTS_BOUND COMMISSION_EXPORT_QUEUED; do
  [[ "$(jq -r --arg action "$action" '[.[] | select(.action==$action)] | length>0' <<<"$flow_events")" == true ]]
done

current_version="$(jq -r .version <<<"$(get "/api/commissions/$commission_id")")"
submitted="$(write POST "/api/commissions/$commission_id/transitions" "qa-commission-resubmit-$stamp" \
  "$(jq -nc --argjson version "$current_version" '{action:"SUBMIT",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"委托变更申请前提交验收"}')")"
copy_version="$(jq -r .version <<<"$(get "/api/commissions/$copy_id")")"
copy_submitted="$(write POST "/api/commissions/$copy_id/transitions" "qa-commission-copy-submit-$stamp" \
  "$(jq -nc --argjson version "$copy_version" '{action:"SUBMIT",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"批量变更申请验收"}')")"
submitted_version="$(jq -r .version <<<"$submitted")"
copy_submitted_version="$(jq -r .version <<<"$copy_submitted")"
stale_change_payload="$(jq -nc --arg first "$commission_id" --arg second "$copy_id" \
  --argjson firstVersion "$submitted_version" --argjson secondVersion "$((copy_submitted_version + 99))" '{
    targets:[{commissionId:$first,expectedVersion:$firstVersion},{commissionId:$second,expectedVersion:$secondVersion}],
    requestType:"OTHER",processingMethod:"MANUAL_RECORD",reason:"SHOWCASE 批量变更回滚验收",
    requestedSummary:"不应生成部分数据"
  }')"
expect_problem 409 VERSION_CONFLICT POST /api/commission-change-requests/batches \
  "qa-commission-change-rollback-$stamp" "$stale_change_payload"
change_list="$(get "/api/commission-change-requests?commissionNumber=$(printf '%s' "$commission_number" | jq -sRr @uri)&limit=20")"
[[ "$(jq -r '.totalCount==0' <<<"$change_list")" == true ]]
batch_change="$(write POST /api/commission-change-requests/batches "qa-commission-change-batch-$stamp" \
  "$(jq -nc --arg first "$commission_id" --arg second "$copy_id" \
    --argjson firstVersion "$submitted_version" --argjson secondVersion "$copy_submitted_version" '{
      targets:[{commissionId:$first,expectedVersion:$firstVersion},{commissionId:$second,expectedVersion:$secondVersion}],
      requestType:"OTHER",processingMethod:"MANUAL_RECORD",reason:"SHOWCASE 批量变更申请验收",
      requestedSummary:"完整事务创建后立即撤回"
    }')")"
[[ "$(jq -r '.requested==2 and (.requests|length)==2' <<<"$batch_change")" == true ]]
while IFS=$'\t' read -r request_id request_version; do
  write POST "/api/commission-change-requests/$request_id/transitions" \
    "qa-commission-change-withdraw-$request_id" \
    "$(jq -nc --argjson version "$request_version" '{action:"WITHDRAW",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"批量变更申请撤回验收"}')" >/dev/null
done < <(jq -r '.requests[] | [.id,.version] | @tsv' <<<"$batch_change")
withdrawn="$(write POST "/api/commissions/$commission_id/transitions" "qa-commission-final-withdraw-$stamp" \
  "$(jq -nc --argjson version "$submitted_version" '{action:"WITHDRAW",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"委托最终撤回验收"}')")"
copy_withdrawn="$(write POST "/api/commissions/$copy_id/transitions" "qa-commission-copy-withdraw-$stamp" \
  "$(jq -nc --argjson version "$copy_submitted_version" '{action:"WITHDRAW",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"复制委托撤回验收"}')")"
[[ "$(jq -r .state <<<"$withdrawn")" == "DRAFT" && "$(jq -r .state <<<"$copy_withdrawn")" == "DRAFT" ]]

printf 'commission=%s copy=%s required=422 idempotency=replayed/mismatch stale=409 list=commission/sample sequence=atomic batch=rollback transfer=cancel external=unique print=controlled parts=bound export=completed electronic=blocked termination=guarded change=batch-atomic submit=withdraw\n' \
  "$commission_id" "$copy_id"
