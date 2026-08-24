CREATE TABLE equipment_category (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    parent_id uuid REFERENCES equipment_category(id),
    code varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code)
);
CREATE INDEX equipment_category_parent_sort_ix
    ON equipment_category(organization_id, parent_id, sort_order, name);

ALTER TABLE equipment
    ADD COLUMN asset_number varchar(80),
    ADD COLUMN category_id uuid REFERENCES equipment_category(id),
    ADD COLUMN purchased_on date,
    ADD COLUMN purchase_price numeric(20,2) CHECK (purchase_price IS NULL OR purchase_price >= 0),
    ADD COLUMN manufacturer varchar(240),
    ADD COLUMN verification_cycle_months integer CHECK (verification_cycle_months IS NULL OR verification_cycle_months > 0),
    ADD COLUMN verification_type varchar(32),
    ADD COLUMN technical_specification varchar(1000),
    ADD COLUMN nominal_range varchar(240),
    ADD COLUMN measurement_range varchar(240),
    ADD COLUMN resolution varchar(120),
    ADD COLUMN accuracy varchar(120),
    ADD COLUMN primary_equipment boolean NOT NULL DEFAULT false,
    ADD COLUMN enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN performance_status varchar(16) NOT NULL DEFAULT 'NORMAL'
        CHECK (performance_status IN ('NORMAL','ABNORMAL')),
    ADD COLUMN equipment_type varchar(40),
    ADD COLUMN pile_equipment_type varchar(40),
    ADD COLUMN attachment_file_id uuid REFERENCES stored_file(id),
    ADD COLUMN photo_file_id uuid REFERENCES stored_file(id),
    ADD COLUMN remark varchar(2000),
    ADD COLUMN approval_state varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (approval_state IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
    ADD COLUMN approved_by uuid REFERENCES app_user(id),
    ADD COLUMN approved_at timestamptz,
    ADD COLUMN inventory_status varchar(12) NOT NULL DEFAULT 'IN'
        CHECK (inventory_status IN ('IN','OUT'));

UPDATE equipment SET approval_state='APPROVED' WHERE approval_state='DRAFT';
CREATE UNIQUE INDEX equipment_org_asset_number_ux
    ON equipment(organization_id, asset_number) WHERE asset_number IS NOT NULL;
CREATE INDEX equipment_org_updated_ix ON equipment(organization_id, updated_at DESC, id DESC);
CREATE INDEX equipment_filter_ix
    ON equipment(organization_id, approval_state, enabled, performance_status, inventory_status, state);
CREATE INDEX equipment_certificate_validity_ix
    ON equipment_certificate(equipment_id, valid_until DESC) WHERE result IN ('PASS','LIMITED');

CREATE TABLE equipment_detection_project (
    equipment_id uuid NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    project_node_id uuid NOT NULL REFERENCES detection_catalog_node(id),
    PRIMARY KEY (equipment_id, project_node_id)
);

ALTER TABLE equipment_certificate
    ADD COLUMN inspection_type varchar(32),
    ADD COLUMN issuing_organization varchar(240),
    ADD COLUMN confirmed_on date,
    ADD COLUMN file_id uuid REFERENCES stored_file(id),
    ADD COLUMN remark varchar(2000),
    ADD COLUMN state varchar(16) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (state IN ('DRAFT','CONFIRMED','VOID')),
    ADD COLUMN operator_id uuid REFERENCES app_user(id),
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

CREATE TABLE equipment_inspection (
    id uuid PRIMARY KEY,
    equipment_id uuid NOT NULL REFERENCES equipment(id),
    outbound_person_id uuid NOT NULL REFERENCES person(id),
    outbound_at timestamptz NOT NULL,
    outbound_accessory_quantity numeric(20,3) NOT NULL DEFAULT 0
        CHECK (outbound_accessory_quantity >= 0),
    destination varchar(500),
    photo_file_id uuid REFERENCES stored_file(id),
    attachment_file_id uuid REFERENCES stored_file(id),
    received_by uuid REFERENCES person(id),
    received_at timestamptz,
    inspection_type varchar(32),
    inspection_number varchar(120),
    inspection_organization varchar(240),
    inspection_date date,
    valid_until date,
    result varchar(20) CHECK (result IS NULL OR result IN ('PASS','LIMITED','FAIL')),
    certificate_file_id uuid REFERENCES stored_file(id),
    filled_by uuid REFERENCES person(id),
    filled_at timestamptz,
    confirmed_by uuid REFERENCES person(id),
    confirmed_at timestamptz,
    reviewed_by uuid REFERENCES person(id),
    reviewed_at timestamptz,
    stock_in_by uuid REFERENCES person(id),
    stock_in_at timestamptz,
    stock_in_accessory_quantity numeric(20,3)
        CHECK (stock_in_accessory_quantity IS NULL OR stock_in_accessory_quantity >= 0),
    state varchar(20) NOT NULL CHECK (state IN
        ('OUTBOUND','RECEIVED','REPORTED','CONFIRMED','APPROVED','RETURNED','VOID')),
    remark varchar(2000),
    void_reason varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_until IS NULL OR inspection_date IS NULL OR valid_until >= inspection_date),
    CHECK (state NOT IN ('REPORTED','CONFIRMED','APPROVED','RETURNED') OR
      (inspection_type IS NOT NULL AND inspection_number IS NOT NULL AND inspection_organization IS NOT NULL
       AND inspection_date IS NOT NULL AND valid_until IS NOT NULL AND result IS NOT NULL
       AND filled_by IS NOT NULL AND filled_at IS NOT NULL)),
    CHECK (state NOT IN ('CONFIRMED','APPROVED','RETURNED') OR
      (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CHECK (state NOT IN ('APPROVED','RETURNED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CHECK (state <> 'RETURNED' OR
      (stock_in_by IS NOT NULL AND stock_in_at IS NOT NULL AND stock_in_accessory_quantity IS NOT NULL)),
    CHECK (state <> 'VOID' OR void_reason IS NOT NULL)
);
CREATE UNIQUE INDEX equipment_one_open_inspection_ux ON equipment_inspection(equipment_id)
    WHERE state NOT IN ('RETURNED','VOID');
CREATE INDEX equipment_inspection_list_ix ON equipment_inspection(updated_at DESC, id DESC);

CREATE TABLE equipment_borrow (
    id uuid PRIMARY KEY,
    equipment_id uuid NOT NULL REFERENCES equipment(id),
    borrower_id uuid NOT NULL REFERENCES person(id),
    borrowed_at timestamptz NOT NULL,
    borrowed_period varchar(8) NOT NULL CHECK (borrowed_period IN ('AM','PM')),
    planned_return_at timestamptz NOT NULL,
    planned_return_period varchar(8) NOT NULL CHECK (planned_return_period IN ('AM','PM')),
    before_condition varchar(16) NOT NULL CHECK (before_condition IN ('NORMAL','ABNORMAL')),
    outbound_accessory_quantity numeric(20,3) NOT NULL DEFAULT 0
        CHECK (outbound_accessory_quantity >= 0),
    province varchar(80),
    city varchar(80),
    district varchar(80),
    address varchar(500),
    destination varchar(500),
    photo_file_id uuid REFERENCES stored_file(id),
    returned_by uuid REFERENCES person(id),
    returned_at timestamptz,
    return_period varchar(8) CHECK (return_period IS NULL OR return_period IN ('AM','PM')),
    after_condition varchar(16) CHECK (after_condition IS NULL OR after_condition IN ('NORMAL','ABNORMAL')),
    returned_accessory_quantity numeric(20,3)
        CHECK (returned_accessory_quantity IS NULL OR returned_accessory_quantity >= 0),
    transferred_to uuid REFERENCES person(id),
    transferred_at timestamptz,
    transfer_reason varchar(1000),
    state varchar(16) NOT NULL CHECK (state IN ('BORROWED','TRANSFERRED','RETURNED','VOID')),
    remark varchar(2000),
    void_reason varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (planned_return_at >= borrowed_at),
    CHECK (state <> 'TRANSFERRED' OR
      (transferred_to IS NOT NULL AND transferred_at IS NOT NULL AND transfer_reason IS NOT NULL)),
    CHECK (state <> 'RETURNED' OR
      (returned_by IS NOT NULL AND returned_at IS NOT NULL AND return_period IS NOT NULL
       AND after_condition IS NOT NULL AND returned_accessory_quantity IS NOT NULL)),
    CHECK (state <> 'VOID' OR void_reason IS NOT NULL)
);
CREATE UNIQUE INDEX equipment_one_open_borrow_ux ON equipment_borrow(equipment_id)
    WHERE state IN ('BORROWED','TRANSFERRED');
CREATE INDEX equipment_borrow_list_ix ON equipment_borrow(updated_at DESC, id DESC);

CREATE TABLE equipment_borrow_companion (
    borrow_id uuid NOT NULL REFERENCES equipment_borrow(id) ON DELETE CASCADE,
    person_id uuid NOT NULL REFERENCES person(id),
    PRIMARY KEY (borrow_id, person_id)
);

CREATE TABLE equipment_usage_record (
    id uuid PRIMARY KEY,
    equipment_id uuid NOT NULL REFERENCES equipment(id),
    task_id uuid REFERENCES test_task(id),
    sample_name varchar(240),
    commission_number varchar(80),
    used_by uuid NOT NULL REFERENCES person(id),
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    temperature numeric(8,3),
    humidity numeric(8,3) CHECK (humidity IS NULL OR (humidity >= 0 AND humidity <= 100)),
    location varchar(240),
    before_condition varchar(16) NOT NULL CHECK (before_condition IN ('NORMAL','ABNORMAL')),
    after_condition varchar(16) CHECK (after_condition IS NULL OR after_condition IN ('NORMAL','ABNORMAL')),
    state varchar(16) NOT NULL CHECK (state IN ('IN_PROGRESS','COMPLETED','VOID')),
    remark varchar(2000),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    CHECK (state = 'IN_PROGRESS' OR ended_at IS NOT NULL),
    CHECK (state <> 'COMPLETED' OR after_condition IS NOT NULL)
);
CREATE UNIQUE INDEX equipment_one_open_usage_ux ON equipment_usage_record(equipment_id)
    WHERE state='IN_PROGRESS';
CREATE INDEX equipment_usage_list_ix ON equipment_usage_record(updated_at DESC, id DESC);

CREATE TABLE equipment_movement (
    id uuid PRIMARY KEY,
    equipment_id uuid NOT NULL REFERENCES equipment(id),
    operation_type varchar(8) NOT NULL CHECK (operation_type IN ('IN','OUT')),
    movement_type varchar(24) NOT NULL CHECK (movement_type IN
        ('BORROW_RETURN','INSPECTION','INVENTORY','MAINTENANCE','OTHER')),
    accessory_quantity numeric(20,3) NOT NULL DEFAULT 0 CHECK (accessory_quantity >= 0),
    occurred_at timestamptz NOT NULL,
    person_id uuid NOT NULL REFERENCES person(id),
    reference_type varchar(32),
    reference_id uuid,
    remark varchar(1000),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX equipment_movement_list_ix ON equipment_movement(occurred_at DESC, id DESC);
CREATE INDEX equipment_movement_equipment_ix ON equipment_movement(equipment_id, occurred_at DESC);

INSERT INTO equipment_movement(id, equipment_id, operation_type, movement_type,
  accessory_quantity, occurred_at, person_id, reference_type, reference_id, remark, created_by)
SELECT gen_random_uuid(), event.equipment_id,
  CASE WHEN event.event_type IN ('STOCK_OUT','BORROW','SEND_INSPECTION') THEN 'OUT' ELSE 'IN' END,
  CASE WHEN event.event_type IN ('BORROW','RETURN') THEN 'BORROW_RETURN'
       WHEN event.event_type IN ('SEND_INSPECTION','VERIFIED','SEND_CALIBRATION','CALIBRATED') THEN 'INSPECTION'
       ELSE 'INVENTORY' END,
  0, event.occurred_at, COALESCE(user_account.person_id, equipment.custodian_id),
  'LEGACY_EVENT', event.id, '由历史设备事件回填', event.actor_id
FROM equipment_event event
JOIN equipment ON equipment.id=event.equipment_id
JOIN app_user user_account ON user_account.id=event.actor_id
WHERE event.event_type IN ('STOCK_IN','STOCK_OUT','BORROW','RETURN','SEND_INSPECTION','VERIFIED','SEND_CALIBRATION','CALIBRATED')
  AND COALESCE(user_account.person_id, equipment.custodian_id) IS NOT NULL;
