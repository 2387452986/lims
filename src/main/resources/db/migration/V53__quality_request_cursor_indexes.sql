CREATE INDEX exception_approval_list_ix
    ON exception_approval(organization_id, created_at DESC, id DESC);

CREATE INDEX data_change_request_list_ix
    ON data_change_request(created_at DESC, id DESC);
