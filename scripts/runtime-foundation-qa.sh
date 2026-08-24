#!/usr/bin/env bash
set -euo pipefail

: "${QA_PASSWORD:?Set QA_PASSWORD without writing it to the repository}"
QA_USERNAME="${QA_USERNAME:-showcase-admin}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"

require_command() {
  command -v "$1" >/dev/null || { echo "missing command: $1" >&2; exit 1; }
}
require_command curl
require_command jq

login_payload="$(jq -nc --arg username "$QA_USERNAME" --arg password "$QA_PASSWORD" \
  '{username:$username,password:$password}')"
login_headers="$(curl -sS -D - -o /dev/null -H 'Content-Type: application/json' \
  -d "$login_payload" "$BASE_URL/api/auth/login")"
session_cookie="$(printf '%s\n' "$login_headers" | sed -n 's/^Set-Cookie: SESSION=\([^;]*\).*/\1/p' | tr -d '\r' | tail -n 1)"
if [[ -z "$session_cookie" ]]; then
  echo "login failed: no session cookie returned" >&2
  exit 1
fi

csrf_headers="$(curl -sS -D - -o /dev/null -H "Cookie: SESSION=$session_cookie" "$BASE_URL/api/auth/me")"
csrf_token="$(printf '%s\n' "$csrf_headers" | sed -n 's/^Set-Cookie: XSRF-TOKEN=\([^;]*\).*/\1/p' | tr -d '\r' | tail -n 1)"
if [[ -z "$csrf_token" ]]; then
  echo "login failed: no CSRF token returned" >&2
  exit 1
fi
cookie_header="SESSION=$session_cookie; XSRF-TOKEN=$csrf_token"

get() {
  curl -fsS -H "Cookie: $cookie_header" "$BASE_URL$1"
}

write() {
  local method="$1"
  local path="$2"
  local idempotency_key="$3"
  local payload="$4"
  curl -fsS -X "$method" -H "Cookie: $cookie_header" -H "X-XSRF-TOKEN: $csrf_token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $idempotency_key" \
    -d "$payload" "$BASE_URL$path"
}

expect_status() {
  local expected="$1"
  local method="$2"
  local path="$3"
  local key="$4"
  local payload="$5"
  local status
  status="$(curl -sS -o /dev/null -w '%{http_code}' -X "$method" -H "Cookie: $cookie_header" \
    -H "X-XSRF-TOKEN: $csrf_token" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" -d "$payload" "$BASE_URL$path")"
  [[ "$status" == "$expected" ]] || { echo "expected HTTP $expected from $path, got $status" >&2; exit 1; }
}

stamp="$(date +%Y%m%d%H%M%S)"
me="$(get /api/auth/me)"
catalog="$(get '/api/detection-catalog/tree?query=%E6%B7%B7%E5%87%9D%E5%9C%9F%E6%8A%97%E5%8E%8B%E5%BC%BA%E5%BA%A6&includeHistory=false')"
scope_revision_id="$(jq -r '[.. | objects | select(.type? == "PROJECT" and .projectRevisionId? != null)][0].projectRevisionId' <<<"$catalog")"
[[ -n "$scope_revision_id" && "$scope_revision_id" != "null" ]]
scope_parameter_id="$(get "/api/detection-project-revisions/$scope_revision_id" | jq -r '.parameters[0].id')"
[[ -n "$scope_parameter_id" && "$scope_parameter_id" != "null" ]]

standard_payload="$(jq -nc --arg code "SHOWCASE-STD-$stamp" --arg parameter "$scope_parameter_id" '{
  code:$code,name:"SHOWCASE 运行时标准",ownershipType:"ORGANIZATION",
  basisType:"BOTH",standardType:"OTHER",issuingInstitution:"SHOWCASE 生产验收",
  citedBasis:"仅用于受控闭环验收",effectiveDate:"2026-08-20",repealDate:null,
  standardStatus:"CURRENT",documentFileId:null,parameterIds:[$parameter],expectedVersion:0
}')"
standard="$(write POST /api/standards "qa-standard-$stamp" "$standard_payload")"
standard_id="$(jq -r .id <<<"$standard")"
standard_version="$(jq -r .version <<<"$standard")"
[[ "$(jq -r --arg parameter "$scope_parameter_id" '.parameterIds == [$parameter]' <<<"$standard")" == "true" ]]
standard_update="$(jq -c --argjson version "$standard_version" \
  '.name="SHOWCASE 运行时标准（已校验）"|.expectedVersion=$version' <<<"$standard_payload")"
standard="$(write PUT "/api/standards/$standard_id" unused "$standard_update")"
standard_version="$(jq -r .version <<<"$standard")"
expect_status 409 PUT "/api/standards/$standard_id" unused "$standard_payload"

publish="$(jq -nc --argjson version "$standard_version" \
  '{action:"PUBLISH",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行时发布验收"}')"
write POST "/api/standards/$standard_id/transitions" "qa-standard-publish-$stamp" "$publish" >/dev/null
standard="$(get "/api/standards/$standard_id")"
standard_version="$(jq -r .version <<<"$standard")"
standard_draft="$(write POST "/api/standards/$standard_id/revisions" "qa-standard-revise-$stamp" '{}')"
draft_id="$(jq -r .id <<<"$standard_draft")"
draft_version="$(jq -r .version <<<"$standard_draft")"
[[ "$(jq -r --arg parameter "$scope_parameter_id" '.parameterIds == [$parameter]' <<<"$standard_draft")" == "true" ]]
discard="$(jq -nc --argjson version "$draft_version" \
  '{action:"DISCARD",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行时修订闭环完成后清理草稿"}')"
write POST "/api/standards/$draft_id/transitions" "qa-standard-discard-$stamp" "$discard" >/dev/null
retire="$(jq -nc --argjson version "$standard_version" \
  '{action:"RETIRE",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行时标准验收完成"}')"
write POST "/api/standards/$standard_id/transitions" "qa-standard-retire-$stamp" "$retire" >/dev/null

rule_payload="$(jq -nc --arg code "SHOWCASE-RULE-$stamp" '{
  code:$code,name:"SHOWCASE 流水规则",autoMatchProjectCode:true,projectIds:[],expectedVersion:0,
  patterns:[
    {resourceType:"COMMISSION",generationMode:"SEPARATE",sequenceScope:"SHARED",seed:"0001",pattern:"SHOWCASE-{symbol}-WT-Y-0001"},
    {resourceType:"SAMPLE",generationMode:"SEPARATE",sequenceScope:"SHARED",seed:"0001",pattern:"SHOWCASE-{symbol}-YP-Y-0001"},
    {resourceType:"TASK",generationMode:"SEPARATE",sequenceScope:"SHARED",seed:"0001",pattern:"SHOWCASE-{symbol}-RW-Y-0001"},
    {resourceType:"REPORT",generationMode:"SEPARATE",sequenceScope:"SHARED",seed:"0001",pattern:"SHOWCASE-{symbol}-BG-Y-0001"}
  ]
}')"
rule="$(write POST /api/numbering-flows/rules "qa-rule-$stamp" "$rule_payload")"
rule_id="$(jq -r .id <<<"$rule")"
rule_preview="$(get "/api/numbering-flows/rules/$rule_id/preview?symbol=QA&sequence=7&date=2026-08-20")"
[[ "$(jq -r .COMMISSION <<<"$rule_preview")" == "SHOWCASE-QA-WT-2026-0007" ]]
rule_version="$(jq -r .version <<<"$rule")"
publish="$(jq -nc --argjson version "$rule_version" \
  '{action:"PUBLISH",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行时发布验收"}')"
write POST "/api/numbering-flows/rules/$rule_id/transitions" "qa-rule-publish-$stamp" "$publish" >/dev/null
rule="$(get "/api/numbering-flows/rules/$rule_id")"
rule_version="$(jq -r .version <<<"$rule")"
rule_draft="$(write POST "/api/numbering-flows/rules/$rule_id/revisions" "qa-rule-revise-$stamp" '{}')"
rule_draft_id="$(jq -r .id <<<"$rule_draft")"
rule_draft_version="$(jq -r .version <<<"$rule_draft")"
discard="$(jq -nc --argjson version "$rule_draft_version" \
  '{action:"DISCARD",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行时规则修订闭环完成"}')"
write POST "/api/numbering-flows/rules/$rule_draft_id/transitions" "qa-rule-discard-$stamp" "$discard" >/dev/null
retire="$(jq -nc --argjson version "$rule_version" \
  '{action:"RETIRE",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行时规则验收完成"}')"
write POST "/api/numbering-flows/rules/$rule_id/transitions" "qa-rule-retire-$stamp" "$retire" >/dev/null

template_payload="$(jq -nc --arg code "SHOWCASE-TPL-$stamp" '{
  code:$code,name:"SHOWCASE 编号模板",prefix:"SHOWCASE-",prefixPosition:"BEFORE_DATE",
  datePattern:"Ymd",suffix:"BG-",suffixPosition:"BEFORE_SEQUENCE",sequenceWidth:4,expectedVersion:0
}')"
template_preview="$(write POST '/api/numbering-flows/templates/preview?sequence=7&date=2026-08-20' unused "$template_payload")"
[[ "$(jq -r .preview <<<"$template_preview")" == "SHOWCASE-20260820-BG-0007" ]]
template="$(write POST /api/numbering-flows/templates "qa-template-$stamp" "$template_payload")"
template_id="$(jq -r .id <<<"$template")"
template_version="$(jq -r .version <<<"$template")"
publish="$(jq -nc --argjson version "$template_version" \
  '{action:"PUBLISH",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行时模板发布验收"}')"
write POST "/api/numbering-flows/templates/$template_id/transitions" "qa-template-publish-$stamp" "$publish" >/dev/null
template="$(get "/api/numbering-flows/templates/$template_id")"
template_version="$(jq -r .version <<<"$template")"
retire="$(jq -nc --argjson version "$template_version" \
  '{action:"RETIRE",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行时模板验收完成"}')"
write POST "/api/numbering-flows/templates/$template_id/transitions" "qa-template-retire-$stamp" "$retire" >/dev/null

domain="$(write POST /api/detection-catalog/nodes "qa-domain-$stamp" \
  "$(jq -nc --arg name "SHOWCASE 验收域 $stamp" '{parentId:null,type:"DOMAIN",name:$name,symbol:"QA",sortOrder:9999}')")"
domain_id="$(jq -r .id <<<"$domain")"
category="$(write POST /api/detection-catalog/nodes "qa-category-$stamp" \
  "$(jq -nc --arg parent "$domain_id" '{parentId:$parent,type:"CATEGORY",name:"SHOWCASE 验收分类",symbol:"QA",sortOrder:1}')")"
category_id="$(jq -r .id <<<"$category")"
project="$(write POST /api/detection-catalog/nodes "qa-project-$stamp" \
  "$(jq -nc --arg parent "$category_id" '{parentId:$parent,type:"PROJECT",name:"SHOWCASE 价格验收项目",symbol:"QA",sortOrder:1}')")"
project_id="$(jq -r .id <<<"$project")"
revision_id="$(jq -r .projectRevisionId <<<"$project")"
[[ "$revision_id" != "null" ]]

parameter_payload="$(jq -nc --arg code "SHOWCASE-P-$stamp" '{
  code:$code,name:"SHOWCASE 价格参数",valueType:"NUMBER",definition:{scale:2},formula:null,
  unit:"MPa",sortOrder:1,conclusion:"合格",defaultNote:"",pricingMethod:"按检测参数计价",price:"100.00",
  sampleName:"验收样品",promisedDays:3,sampleDescription:"合成验收数据",sampleQuantity:"1",
  postTestTreatment:"退样",retentionDays:0,retentionQuantity:"0",retentionNature:"无",
  defaultCommissioned:true,required:true,enabled:true,remark:"SHOWCASE",standardIds:[],expectedVersion:0
}')"
parameter="$(write POST "/api/detection-project-revisions/$revision_id/parameters" "qa-parameter-$stamp" "$parameter_payload")"
parameter_id="$(jq -r .id <<<"$parameter")"
parameter_version="$(jq -r .version <<<"$parameter")"
pricing_payload="$(jq -nc --arg id "$parameter_id" --argjson version "$parameter_version" \
  '[{id:$id,pricingMethod:"按检测参数计价",price:"128.50",expectedVersion:$version}]')"
pricing="$(write POST "/api/detection-project-revisions/$revision_id/parameter-prices" "qa-pricing-$stamp" "$pricing_payload")"
price_version="$(jq -r '.[0].version' <<<"$pricing")"
quote_payload="$(jq -nc --arg id "$parameter_id" '[{parameterId:$id,quantity:"2"}]')"
quote="$(write POST "/api/detection-project-revisions/$revision_id/parameter-prices/quote" unused "$quote_payload")"
[[ "$(jq -r .total <<<"$quote")" == "257.00" ]]
expect_status 409 POST "/api/detection-project-revisions/$revision_id/parameter-prices" \
  "qa-pricing-stale-$stamp" "$pricing_payload"

disable_node() {
  local node_id="$1"
  local payload
  payload='{"action":"DISABLE","expectedVersion":0,"reasonCode":"SHOWCASE_PARITY","comment":"运行时价格验收完成"}'
  write POST "/api/detection-catalog/nodes/$node_id/transitions" "qa-node-disable-$node_id" "$payload" >/dev/null
}
disable_node "$project_id"
disable_node "$category_id"
disable_node "$domain_id"

template_bytes="$(get /api/standards/import-template | wc -c | tr -d ' ')"
export_bytes="$(get '/api/standards/export?query=SHOWCASE' | wc -c | tr -d ' ')"
[[ "$template_bytes" -gt 1000 ]]
[[ "$export_bytes" -gt 1000 ]]

printf 'login=%s standard=%s stale=409 rule=%s template=%s quote=%s priceVersion=%s xlsx=%s/%s\n' \
  "$(jq -r .username <<<"$me")" "$standard_id" "$(jq -r .COMMISSION <<<"$rule_preview")" \
  "$(jq -r .preview <<<"$template_preview")" "$(jq -r .total <<<"$quote")" "$price_version" \
  "$template_bytes" "$export_bytes"
