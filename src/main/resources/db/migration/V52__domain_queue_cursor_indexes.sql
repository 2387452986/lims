-- Stable cursor projections for the final sample/task/test/report work queues.
CREATE INDEX IF NOT EXISTS sample_updated_cursor_v52_ix
    ON sample (updated_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS sample_retained_cursor_v52_ix
    ON sample (updated_at DESC, id DESC) WHERE state = 'RETAINED';
CREATE INDEX IF NOT EXISTS sample_age_schedule_cursor_v52_ix
    ON sample (planned_test_date, updated_at DESC, id DESC)
    WHERE planned_test_date IS NOT NULL;

CREATE INDEX IF NOT EXISTS test_task_updated_cursor_v52_ix
    ON test_task (updated_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS test_task_due_cursor_v52_ix
    ON test_task (due_at, updated_at DESC, id DESC) WHERE due_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS test_task_returned_cursor_v52_ix
    ON test_task (updated_at DESC, id DESC) WHERE state = 'RETURNED';

CREATE INDEX IF NOT EXISTS test_record_updated_cursor_v52_ix
    ON test_record (updated_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS test_record_state_result_cursor_v52_ix
    ON test_record (state, result, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS report_updated_cursor_v52_ix
    ON report (updated_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS report_state_updated_cursor_v52_ix
    ON report (state, updated_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS report_version_report_revision_v52_ix
    ON report_version (report_id, revision DESC, id DESC);
