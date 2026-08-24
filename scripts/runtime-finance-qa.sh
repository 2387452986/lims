#!/usr/bin/env bash
set -euo pipefail

: "${QA_PASSWORD:?Set QA_PASSWORD without writing it to the repository}"
QA_USERNAME="${QA_USERNAME:-showcase-admin}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
REVISION_ID="${REVISION_ID:-636b2dcc-dde3-507d-b06d-923a69b9777a}"

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
  response_file="$(mktemp /tmp/lims-finance-write.XXXXXX)"
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
  response_file="$(mktemp /tmp/lims-finance-problem.XXXXXX)"
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

stamp="$(date +%Y%m%d%H%M%S)"
today="$(date +%Y-%m-%d)"
future="$(date -v+30d +%Y-%m-%d)"

options="$(get /api/finance/options)"
owner_id="$(jq -r --arg username "$QA_USERNAME" '[.users[] | select(.code==$username)][0].id // empty' <<<"$options")"
[[ -n "$owner_id" ]] || { echo 'finance options do not contain the QA account' >&2; exit 1; }

customer_payload="$(jq -nc --arg stamp "$stamp" '{
  code:("SHOWCASE-FIN-"+$stamp),shortName:("SHOWCASE财务"+$stamp),name:("SHOWCASE 财务验收单位 "+$stamp),
  unitType:"ENTRUSTING",unifiedCreditCode:"",commissioningUnit:true,contactName:"SHOWCASE 联系人",
  contactPhone:"13800000000",landline:"",address:"SHOWCASE 合成地址",fax:"",email:"",
  remark:"SHOWCASE 合同财务运行验收",active:true,expectedVersion:null
}')"
customer="$(write POST /api/customers "qa-finance-customer-$stamp" "$customer_payload")"
customer_id="$(jq -r .summary.id <<<"$customer")"

project_payload="$(jq -nc --arg stamp "$stamp" --arg customer "$customer_id" --arg today "$today" '{
  number:("SHOWCASE-GC-"+$stamp),name:("SHOWCASE 财务验收工程 "+$stamp),customerId:$customer,
  location:"SHOWCASE 合成地点",pileSerialNumber:"",projectType:"",projectGrade:"",regulatoryOrganization:"",
  regulatoryProjectCode:"",regulatoryProjectName:"",regulatoryLinked:false,entrusterName:"SHOWCASE 委托人",
  entrusterPhone:"13800000000",settlementContactName:"SHOWCASE 结算人",settlementContactPhone:"13900000000",
  samplingUnitName:"SHOWCASE 取样单位",samplerName:"取样员",samplerPhone:"13700000000",
  samplerCertificateNumber:"SHOWCASE-SAMPLE",province:"",city:"",district:"",detailedAddress:"SHOWCASE 合成地址",
  sectionNumber:"SHOWCASE-01",regionCode:"",defaultDetectionCategory:"",remark:"合同财务运行验收",
  ownerName:"SHOWCASE 建设单位",ownerReportAccess:false,supervisionName:"SHOWCASE 监理单位",
  witnessName:"见证员",witnessPhone:"13600000000",supervisionReportAccess:false,
  constructionName:"SHOWCASE 施工单位",constructionReportAccess:false,surveyName:"",designName:"",
  generalContractorName:"",customerReportAccess:false,cmaSealPolicy:"FOLLOW_PROJECT",
  qualificationSealPolicy:"FOLLOW_PROJECT",electronicSealPolicy:"FOLLOW_PROJECT",customerPrintAllowed:false,
  customerPrintLimit:null,settlementMethod:"按检测项目结算",projectTeamId:null,reportPrintRestricted:false,
  externalContractNumber:"",handledOn:$today,latitude:"",longitude:"",expectedVersion:null,
  parties:[{partyRole:"ENTRUSTING",customerId:$customer,name:("SHOWCASE 财务验收单位 "+$stamp),
    contactName:"SHOWCASE 联系人",contactPhone:"13800000000",certificateNumber:"",manualEntry:false,reportAccess:false}]
}')"
project="$(write POST /api/projects "qa-finance-project-$stamp" "$project_payload")"
project_id="$(jq -r .information.id <<<"$project")"
project_version="$(jq -r .information.version <<<"$project")"
project_active="$(write POST "/api/projects/$project_id/transitions" "qa-finance-project-submit-$stamp" \
  "$(jq -nc --argjson version "$project_version" '{action:"SUBMIT",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"合同财务运行验收"}')")"
[[ "$(jq -r .state <<<"$project_active")" == "ACTIVE" ]]

revision="$(get "/api/detection-project-revisions/$REVISION_ID")"
parameter_id="$(jq -r '[.parameters[] | select(.status=="PUBLISHED" and .enabled)][0].id // empty' <<<"$revision")"
parameter_name="$(jq -r --arg id "$parameter_id" '[.parameters[] | select(.id==$id)][0].name // empty' <<<"$revision")"
project_name="$(jq -r '.name // "SHOWCASE 检测项目"' <<<"$revision")"
[[ -n "$parameter_id" ]] || { echo 'no published parameter available' >&2; exit 1; }
plan_payload="$(jq -nc --arg revision "$REVISION_ID" --arg parameter "$parameter_id" --arg projectName "$project_name" \
  --arg parameterName "$parameter_name" --arg future "$future" '{
  detectionProjectRevisionId:$revision,parameterId:$parameter,categoryName:"SHOWCASE",
  projectName:$projectName,parameterName:$parameterName,plannedQuantity:"2",unit:"项",bindingParameter:"",
  detectionMethod:"受控检测方法",plannedCompletionOn:$future,unitPrice:"125.00",active:true,sortOrder:0,expectedVersion:null
}')"
write POST "/api/projects/$project_id/plans" "qa-finance-plan-$stamp" "$plan_payload" >/dev/null

template_payload="$(jq -nc --arg stamp "$stamp" --arg revision "$REVISION_ID" --arg parameter "$parameter_id" \
  --arg parameterName "$parameter_name" '{
  code:("SHOWCASE-TPL-"+$stamp),name:("SHOWCASE 合同价模板 "+$stamp),description:"合同财务运行验收",
  pricingMethod:"UNIT_PRICE",currency:"CNY",expectedVersion:null,items:[{
    detectionProjectRevisionId:$revision,parameterId:$parameter,itemName:$parameterName,unit:"项",
    unitPrice:"125.00",minimumQuantity:"0",sortOrder:0
  }]
}')"
template="$(write POST /api/finance/price-templates "qa-finance-template-$stamp" "$template_payload")"
template_id="$(jq -r .id <<<"$template")"
template_published="$(write POST "/api/finance/price-templates/$template_id/transitions" "qa-finance-template-publish-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$template")" '{action:"PUBLISH",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"发布验收"}')")"
published_version="$(jq -r .version <<<"$template_published")"
template_revision="$(write POST "/api/finance/price-templates/$template_id/revisions" "qa-finance-template-revise-$stamp" \
  "$(jq -nc --argjson version "$published_version" '{expectedVersion:$version}')")"
[[ "$(jq -r '.revision == 2 and .state == "DRAFT"' <<<"$template_revision")" == "true" ]]

contract_payload="$(jq -nc --arg stamp "$stamp" --arg customer "$customer_id" --arg project "$project_id" \
  --arg template "$template_id" --arg owner "$owner_id" --arg today "$today" --arg future "$future" '{
  number:("SHOWCASE-HT-"+$stamp),name:("SHOWCASE 检测合同 "+$stamp),contractType:"TESTING_SERVICE",
  customerId:$customer,projectId:$project,counterpartyName:("SHOWCASE 财务验收单位 "+$stamp),
  signedOn:$today,effectiveFrom:$today,effectiveUntil:$future,deliveryDeadline:$future,contractNature:"检测服务",
  priceTemplateId:$template,currency:"CNY",signedAmount:"250.00",contractPrice:"250.00",
  settlementMethod:"按检测项目结算",ownerId:$owner,expectedVersion:null
}')"
contract_key="qa-finance-contract-$stamp"
contract="$(write POST /api/finance/contracts "$contract_key" "$contract_payload")"
contract_id="$(jq -r .id <<<"$contract")"
replayed="$(write POST /api/finance/contracts "$contract_key" "$contract_payload")"
[[ "$(jq -r .id <<<"$replayed")" == "$contract_id" ]]
expect_problem 409 IDEMPOTENCY_KEY_REUSED POST /api/finance/contracts "$contract_key" \
  "$(jq '.name += " 不同请求"' <<<"$contract_payload")"

contract_submit="$(write POST "/api/finance/contracts/$contract_id/transitions" "qa-finance-contract-submit-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$contract")" '{action:"SUBMIT",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"提交验收"}')")"
contract_active="$(write POST "/api/finance/contracts/$contract_id/transitions" "qa-finance-contract-activate-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$contract_submit")" '{action:"ACTIVATE",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"生效验收"}')")"
[[ "$(jq -r .state <<<"$contract_active")" == "ACTIVE" ]]

current_version="$(jq -r .version <<<"$(get "/api/finance/contracts/$contract_id")")"
adjustment="$(write POST "/api/finance/contracts/$contract_id/entries" "qa-finance-adjustment-$stamp" \
  "$(jq -nc --arg today "$today" --argjson version "$current_version" '{entryType:"PRICE_ADJUSTMENT",businessNumber:"SHOWCASE-ADJ",occurredOn:$today,amount:"25.00",description:"SHOWCASE 合同价调整",expectedVersion:$version}')")"
current_version="$(jq -r .version <<<"$(get "/api/finance/contracts/$contract_id")")"
adjusted_price="$(jq -r .contractPrice <<<"$(get "/api/finance/contracts/$contract_id")")"
[[ "$adjusted_price" == "275.00" ]]
write POST "/api/finance/contracts/$contract_id/entries/$(jq -r .id <<<"$adjustment")/void" "qa-finance-adjustment-void-$stamp" \
  "$(jq -nc --argjson version "$current_version" --argjson entryVersion "$(jq -r .version <<<"$adjustment")" '{expectedVersion:$version,entryExpectedVersion:$entryVersion,reason:"SHOWCASE 调整作废验收"}')" >/dev/null
current_version="$(jq -r .version <<<"$(get "/api/finance/contracts/$contract_id")")"
invoice="$(write POST "/api/finance/contracts/$contract_id/entries" "qa-finance-invoice-$stamp" \
  "$(jq -nc --arg today "$today" --argjson version "$current_version" '{entryType:"INVOICE",businessNumber:"SHOWCASE-INVOICE",occurredOn:$today,amount:"200.00",description:"SHOWCASE 开票验收",expectedVersion:$version}')")"
[[ "$(jq -r .entryType <<<"$invoice")" == "INVOICE" ]]

current_version="$(jq -r .version <<<"$(get "/api/finance/contracts/$contract_id")")"
fees="$(write POST "/api/finance/contracts/$contract_id/fees/recalculate" "qa-finance-fees-$stamp" \
  "$(jq -nc --argjson version "$current_version" '{expectedVersion:$version}')")"
fee_id="$(jq -r '.[0].id // empty' <<<"$fees")"
fee_version="$(jq -r '.[0].version' <<<"$fees")"
[[ -n "$fee_id" && "$(jq -r '.[0].amount' <<<"$fees")" == "250.00" ]]
fee_locked="$(write POST "/api/finance/fees/$fee_id/transitions" "qa-finance-fee-lock-$stamp" \
  "$(jq -nc --argjson version "$fee_version" '{action:"LOCK",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"运行验收锁定",settlementOwnerId:null}')")"
[[ "$(jq -r .state <<<"$fee_locked")" == "LOCKED" ]]

receipt_payload="$(jq -nc --arg stamp "$stamp" --arg contract "$contract_id" --arg fee "$fee_id" --arg owner "$owner_id" --arg today "$today" '{
  number:("SHOWCASE-SK-"+$stamp),contractId:$contract,feeStatementId:$fee,receivableAmount:"250.00",
  actualAmount:"250.00",receivedOn:$today,paymentMethod:"BANK_TRANSFER",collectorId:$owner,
  payerName:("SHOWCASE 付款方 "+$stamp),invoiceNumber:"SHOWCASE-INVOICE",expectedVersion:null
}')"
receipt="$(write POST /api/finance/receipts "qa-finance-receipt-$stamp" "$receipt_payload")"
receipt_id="$(jq -r .id <<<"$receipt")"
receipt_confirmed="$(write POST "/api/finance/receipts/$receipt_id/transitions" "qa-finance-receipt-confirm-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$receipt")" '{action:"CONFIRM",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"确认收款验收"}')")"
[[ "$(jq -r .state <<<"$receipt_confirmed")" == "CONFIRMED" ]]
fee_after_receipt="$(get "/api/finance/fees?contractId=$contract_id&limit=20")"
[[ "$(jq -r --arg id "$fee_id" '[.items[] | select(.id==$id)][0].state' <<<"$fee_after_receipt")" == "SETTLED" ]]

refund="$(write POST "/api/finance/receipts/$receipt_id/refunds" "qa-finance-refund-$stamp" \
  "$(jq -nc --arg today "$today" --argjson version "$(jq -r .version <<<"$receipt_confirmed")" '{amount:"50.00",receivedOn:$today,reason:"SHOWCASE 退款验收",expectedVersion:$version}')")"
[[ "$(jq -r '.actualAmount == "-50.00" and .state == "CONFIRMED"' <<<"$refund")" == "true" ]]

payment_payload="$(jq -nc --arg stamp "$stamp" --arg contract "$contract_id" --arg today "$today" '{
  number:("SHOWCASE-FK-"+$stamp),contractId:$contract,payableAmount:"80.00",plannedOn:$today,
  payeeName:("SHOWCASE 收款方 "+$stamp),invoiceNumber:"SHOWCASE-PAY-INVOICE",purpose:"合同财务运行验收",expectedVersion:null
}')"
payment="$(write POST /api/finance/payments "qa-finance-payment-$stamp" "$payment_payload")"
payment_submit="$(write POST "/api/finance/payments/$(jq -r .id <<<"$payment")/transitions" "qa-finance-payment-submit-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$payment")" '{action:"SUBMIT",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"提交付款验收",actualAmount:null,paidOn:null,paymentMethod:null}')")"
expect_problem 422 PAYMENT_DUTY_CONFLICT POST "/api/finance/payments/$(jq -r .id <<<"$payment")/transitions" \
  "qa-finance-payment-self-approve-$stamp" \
  "$(jq -nc --argjson version "$(jq -r .version <<<"$payment_submit")" '{action:"APPROVE",expectedVersion:$version,reasonCode:"SHOWCASE_PARITY",comment:"本人审批应被阻断",actualAmount:null,paidOn:null,paymentMethod:null}')"

contract_list="$(get "/api/finance/contracts?q=SHOWCASE-HT-$stamp&limit=20")"
[[ "$(jq -r --arg id "$contract_id" '[.items[].id] | index($id) != null' <<<"$contract_list")" == "true" ]]
analysis="$(get "/api/finance/analysis?from=$today&to=$today")"
[[ "$(jq -r '.contractPrice != null and .receivedAmount != null and .unsettledFees >= 1' <<<"$analysis")" == "true" ]]
my_projects="$(get /api/finance/my-projects)"
[[ "$(jq -r --arg id "$project_id" '[.[].projectId] | index($id) != null' <<<"$my_projects")" == "true" ]]
project_reports="$(get "/api/finance/my-projects/$project_id/reports")"
[[ "$(jq -r 'type' <<<"$project_reports")" == "array" ]]

export_payload="$(jq -nc --arg contract "$contract_id" '{resourceType:"FEE_STATEMENT",query:"SHOWCASE",state:"",contractId:$contract,projectId:null}')"
export_job="$(write POST /api/finance/exports "qa-finance-export-$stamp" "$export_payload")"
export_job_id="$(jq -r .jobId <<<"$export_job")"
export_state="READY"
export_result_file=""
for _ in $(seq 1 60); do
  job_status="$(get "/api/jobs/$export_job_id")"
  export_state="$(jq -r .state <<<"$job_status")"
  export_result_file="$(jq -r '.resultFileId // empty' <<<"$job_status")"
  [[ "$export_state" == "COMPLETED" || "$export_state" == "FAILED" ]] && break
  sleep 1
done
[[ "$export_state" == "COMPLETED" && -n "$export_result_file" ]] || {
  echo "finance export did not complete: $export_state" >&2
  exit 1
}
export_file="$(mktemp /tmp/lims-finance-export.XXXXXX.xlsx)"
curl -fsS -H "Cookie: $cookie_header" "$BASE_URL/api/files/$export_result_file" -o "$export_file"
unzip -t "$export_file" >/dev/null
rm -f "$export_file"

printf 'finance contract=%s template=%s fee=%s receipt=%s refund=%s payment=self-approval-blocked analysis=verified export=%s\n' \
  "$contract_id" "$template_id" "$fee_id" "$receipt_id" "$(jq -r .id <<<"$refund")" "$export_job_id"
