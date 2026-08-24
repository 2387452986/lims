DROP INDEX sample_shelf_root_cursor_ix;
DROP INDEX sample_shelf_tree_order_ix;

CREATE INDEX sample_shelf_root_cursor_ix
  ON sample_shelf(organization_id, sort_order, upper(shelf_number), id)
  WHERE parent_id IS NULL;

CREATE INDEX sample_shelf_tree_order_ix
  ON sample_shelf(organization_id, parent_id, sort_order, upper(shelf_number), id)
  INCLUDE (active);
