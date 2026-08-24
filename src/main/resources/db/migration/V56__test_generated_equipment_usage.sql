CREATE UNIQUE INDEX equipment_usage_task_ux
    ON equipment_usage_record(task_id)
    WHERE task_id IS NOT NULL;

COMMENT ON INDEX equipment_usage_task_ux IS
    'A submitted test task creates exactly one equipment usage history row.';
