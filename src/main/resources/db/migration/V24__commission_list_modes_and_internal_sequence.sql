-- Reference-parity commission list projections and controlled internal sequence editing.
-- The trial page exposes an optional "内部流水" value; no visible source value was captured,
-- so existing rows intentionally remain NULL instead of receiving invented data.

ALTER TABLE commission
    ADD COLUMN internal_sequence bigint CHECK (internal_sequence IS NULL OR internal_sequence > 0);

CREATE INDEX commission_internal_sequence_v24_ix
    ON commission(organization_id, internal_sequence)
    WHERE internal_sequence IS NOT NULL;

CREATE INDEX sample_group_commission_v24_ix
    ON commission_sample_group(commission_id, sort_order, id);

CREATE INDEX sample_group_sample_v24_ix
    ON sample(sample_group_id, updated_at DESC, id DESC)
    WHERE sample_group_id IS NOT NULL;
