-- PostgreSQL query observability is a deployment concern shared by every domain.
-- The extension is installed in public so isolated Flyway test schemas do not
-- create competing copies. PostgreSQL must still be started with
-- shared_preload_libraries=pg_stat_statements before the view can collect data.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA public;
