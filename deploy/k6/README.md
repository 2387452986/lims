# Production performance gate

`lims-production.js` is a test-only k6 workload. It does not add a runtime
component. The default `full` profile covers login, compound workbench/list
queries, stable cursor paging, commission writes, task transitions, test
submission and report reads. `K6_LOAD=baseline` sustains 50 VUs after the
one-shot workflow checks; `K6_LOAD=stress` sustains 200 VUs.

The workload refuses to mutate fixtures unless their owning commission contains
`SHOWCASE`. Prepare isolated fixture records in the benchmark database and
provide secrets through the environment:

```sh
K6_BASE_URL=https://lims-benchmark.internal.example \
K6_USERNAME=showcase-performance \
K6_PASSWORD='from-secret-store' \
K6_TEMPLATE_COMMISSION_ID='uuid' \
K6_TASK_IDS='uuid' \
K6_TASK_ACTION='ACCEPT' \
K6_TEST_RECORD_IDS='uuid' \
K6_REPORT_IDS='uuid' \
k6 run deploy/k6/lims-production.js
```

Provide one task/test fixture per corresponding one-shot VU. Run `baseline`
first, archive `k6-summary.json`, `pg_stat_statements`, relevant
`EXPLAIN (ANALYZE, BUFFERS)` output and `/tmp/lims.jfr`, then repeat with
`K6_LOAD=stress`. A run is not production evidence unless it was executed on the
fixed 8-core/32-GB/NVMe benchmark host and its artifacts identify the build,
database snapshot and fixture manifest.
