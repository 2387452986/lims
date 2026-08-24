CREATE TABLE contract_price_template (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    code varchar(80) NOT NULL,
    name varchar(240) NOT NULL,
    description varchar(2000),
    pricing_method varchar(20) NOT NULL CHECK (pricing_method IN ('UNIT_PRICE','FIXED_PRICE','NEGOTIATED')),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency ~ '^[A-Z]{3}$'),
    revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
    supersedes_id uuid REFERENCES contract_price_template(id),
    state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','PUBLISHED','RETIRED')),
    source_system varchar(120),
    source_record_id varchar(160),
    source_path varchar(1000),
    source_captured_at timestamptz,
    source_content_sha256 char(64),
    version bigint NOT NULL DEFAULT 0,
    published_by uuid REFERENCES app_user(id),
    published_at timestamptz,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code, revision),
    UNIQUE (organization_id, source_system, source_record_id),
    CHECK ((state='DRAFT' AND published_at IS NULL)
        OR (state IN ('PUBLISHED','RETIRED') AND published_at IS NOT NULL))
);
CREATE INDEX contract_price_template_list_ix
    ON contract_price_template(organization_id, updated_at DESC, id DESC);

CREATE TABLE contract_price_template_item (
    id uuid PRIMARY KEY,
    template_id uuid NOT NULL REFERENCES contract_price_template(id) ON DELETE CASCADE,
    detection_project_revision_id uuid REFERENCES detection_project_revision(id),
    parameter_id uuid REFERENCES test_parameter(id),
    item_name varchar(240) NOT NULL,
    unit varchar(40) NOT NULL,
    unit_price numeric(20,2) NOT NULL CHECK (unit_price >= 0),
    minimum_quantity numeric(20,6) NOT NULL DEFAULT 0 CHECK (minimum_quantity >= 0),
    sort_order integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (parameter_id IS NULL OR detection_project_revision_id IS NOT NULL)
);
CREATE INDEX contract_price_template_item_ix
    ON contract_price_template_item(template_id, sort_order, id);

CREATE TABLE business_contract (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    number varchar(100) NOT NULL,
    name varchar(300) NOT NULL,
    contract_type varchar(32) NOT NULL CHECK (contract_type IN
        ('TESTING_SERVICE','TECHNICAL_SERVICE','FRAMEWORK','SUBCONTRACT','OTHER')),
    customer_id uuid NOT NULL REFERENCES customer(id),
    project_id uuid NOT NULL REFERENCES project(id),
    counterparty_name varchar(240) NOT NULL,
    signed_on date,
    effective_from date,
    effective_until date,
    delivery_deadline date,
    contract_nature varchar(40),
    price_template_id uuid REFERENCES contract_price_template(id),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency ~ '^[A-Z]{3}$'),
    signed_amount numeric(20,2) NOT NULL DEFAULT 0 CHECK (signed_amount >= 0),
    contract_price numeric(20,2) NOT NULL DEFAULT 0 CHECK (contract_price >= 0),
    settlement_method varchar(80),
    owner_id uuid NOT NULL REFERENCES app_user(id),
    state varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (state IN
        ('DRAFT','SUBMITTED','ACTIVE','COMPLETED','TERMINATED')),
    terminated_reason varchar(2000),
    completed_at timestamptz,
    terminated_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, number),
    CHECK (effective_until IS NULL OR effective_from IS NULL OR effective_until >= effective_from),
    CHECK (state <> 'COMPLETED' OR completed_at IS NOT NULL),
    CHECK (state <> 'TERMINATED' OR (terminated_at IS NOT NULL AND terminated_reason IS NOT NULL))
);
CREATE INDEX business_contract_list_ix
    ON business_contract(organization_id, updated_at DESC, id DESC);
CREATE INDEX business_contract_filter_ix
    ON business_contract(organization_id, state, contract_type, signed_on DESC, id DESC);
CREATE INDEX business_contract_project_ix ON business_contract(project_id, updated_at DESC, id DESC);
CREATE INDEX business_contract_customer_ix ON business_contract(customer_id, updated_at DESC, id DESC);
CREATE INDEX business_contract_owner_ix ON business_contract(owner_id, updated_at DESC, id DESC);

CREATE TABLE contract_financial_entry (
    id uuid PRIMARY KEY,
    contract_id uuid NOT NULL REFERENCES business_contract(id),
    entry_type varchar(24) NOT NULL CHECK (entry_type IN
        ('TARGET_DELIVERY','PRICE_ADJUSTMENT','SETTLEMENT','OTHER_FEE','INVOICE')),
    business_number varchar(120),
    occurred_on date NOT NULL,
    amount numeric(20,2) NOT NULL DEFAULT 0,
    description varchar(2000) NOT NULL,
    state varchar(12) NOT NULL DEFAULT 'VALID' CHECK (state IN ('VALID','VOID')),
    void_reason varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (state <> 'VOID' OR void_reason IS NOT NULL)
);
CREATE UNIQUE INDEX contract_financial_entry_number_ux
    ON contract_financial_entry(contract_id, entry_type, business_number)
    WHERE business_number IS NOT NULL AND state='VALID';
CREATE INDEX contract_financial_entry_list_ix
    ON contract_financial_entry(contract_id, occurred_on DESC, created_at DESC, id DESC);

CREATE TABLE fee_statement (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    contract_id uuid NOT NULL REFERENCES business_contract(id),
    project_id uuid NOT NULL REFERENCES project(id),
    commission_id uuid REFERENCES commission(id),
    sample_id uuid REFERENCES sample(id),
    report_id uuid REFERENCES report(id),
    detection_project_revision_id uuid REFERENCES detection_project_revision(id),
    parameter_id uuid REFERENCES test_parameter(id),
    source_plan_id uuid REFERENCES project_detection_plan(id),
    item_name varchar(300) NOT NULL,
    sample_name varchar(240),
    parameter_name varchar(240),
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit varchar(40) NOT NULL,
    unit_price numeric(20,2) NOT NULL CHECK (unit_price >= 0),
    amount numeric(20,2) NOT NULL CHECK (amount >= 0),
    paid_amount numeric(20,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    settlement_owner_id uuid REFERENCES app_user(id),
    state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','LOCKED','SETTLED')),
    lock_reason varchar(1000),
    locked_by uuid REFERENCES app_user(id),
    locked_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (contract_id, source_plan_id),
    CHECK (paid_amount <= amount),
    CHECK ((state IN ('LOCKED','SETTLED')) = (locked_at IS NOT NULL)),
    CHECK (state <> 'SETTLED' OR paid_amount = amount)
);
CREATE INDEX fee_statement_list_ix
    ON fee_statement(organization_id, updated_at DESC, id DESC);
CREATE INDEX fee_statement_filter_ix
    ON fee_statement(organization_id, state, contract_id, project_id, updated_at DESC, id DESC);

CREATE TABLE finance_receipt (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    number varchar(100) NOT NULL,
    contract_id uuid NOT NULL REFERENCES business_contract(id),
    fee_statement_id uuid REFERENCES fee_statement(id),
    original_receipt_id uuid REFERENCES finance_receipt(id),
    receivable_amount numeric(20,2) NOT NULL CHECK (receivable_amount >= 0),
    actual_amount numeric(20,2) NOT NULL CHECK (actual_amount <> 0),
    received_on date NOT NULL,
    payment_method varchar(32) NOT NULL CHECK (payment_method IN
        ('BANK_TRANSFER','CASH','CHECK','INTERNAL_OFFSET','OTHER')),
    collector_id uuid NOT NULL REFERENCES app_user(id),
    payer_name varchar(240) NOT NULL,
    invoice_number varchar(120),
    state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','CONFIRMED','VOID')),
    confirmed_by uuid REFERENCES app_user(id),
    confirmed_at timestamptz,
    void_reason varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, number),
    CHECK (original_receipt_id IS NULL OR actual_amount < 0),
    CHECK ((state='CONFIRMED') = (confirmed_at IS NOT NULL)),
    CHECK (state <> 'VOID' OR void_reason IS NOT NULL)
);
CREATE INDEX finance_receipt_list_ix
    ON finance_receipt(organization_id, updated_at DESC, id DESC);
CREATE INDEX finance_receipt_contract_ix
    ON finance_receipt(contract_id, state, received_on DESC, id DESC);

CREATE TABLE finance_payment (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    number varchar(100) NOT NULL,
    contract_id uuid NOT NULL REFERENCES business_contract(id),
    payable_amount numeric(20,2) NOT NULL CHECK (payable_amount > 0),
    actual_amount numeric(20,2) CHECK (actual_amount IS NULL OR actual_amount > 0),
    planned_on date NOT NULL,
    paid_on date,
    payment_method varchar(32) CHECK (payment_method IS NULL OR payment_method IN
        ('BANK_TRANSFER','CASH','CHECK','INTERNAL_OFFSET','OTHER')),
    payee_name varchar(240) NOT NULL,
    invoice_number varchar(120),
    purpose varchar(1000) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (state IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','PAID','VOID')),
    approved_by uuid REFERENCES app_user(id),
    approved_at timestamptz,
    decision_comment varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, number),
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    CHECK (state NOT IN ('APPROVED','PAID') OR approved_at IS NOT NULL),
    CHECK (state <> 'PAID' OR (paid_on IS NOT NULL AND actual_amount IS NOT NULL AND payment_method IS NOT NULL))
);
CREATE INDEX finance_payment_list_ix
    ON finance_payment(organization_id, updated_at DESC, id DESC);
CREATE INDEX finance_payment_contract_ix
    ON finance_payment(contract_id, state, planned_on DESC, id DESC);

INSERT INTO permission(code,name,category) VALUES
  ('CONTRACT_MANAGE','合同维护','FINANCE'),
  ('FINANCE_MANAGE','收付款及费用维护','FINANCE'),
  ('FINANCE_APPROVE','收付款复核','FINANCE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission(role_id,permission_code)
SELECT role.id,permission.code FROM app_role role CROSS JOIN permission
WHERE role.code='SYSTEM_ADMIN' AND permission.code IN ('CONTRACT_MANAGE','FINANCE_MANAGE','FINANCE_APPROVE')
ON CONFLICT DO NOTHING;
