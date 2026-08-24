import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = (__ENV.K6_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const PROFILE = __ENV.K6_PROFILE || 'full';
const LOAD = __ENV.K6_LOAD || 'baseline';
const DURATION = __ENV.K6_DURATION || '2m';
const USERNAME = __ENV.K6_USERNAME || '';
const PASSWORD = __ENV.K6_PASSWORD || '';
const TEMPLATE_COMMISSION_ID = __ENV.K6_TEMPLATE_COMMISSION_ID || '';
const TASK_IDS = csv(__ENV.K6_TASK_IDS);
const TEST_RECORD_IDS = csv(__ENV.K6_TEST_RECORD_IDS);
const REPORT_IDS = csv(__ENV.K6_REPORT_IDS);

const queryDuration = new Trend('lims_query_duration', true);
const writeDuration = new Trend('lims_write_duration', true);
const loginDuration = new Trend('lims_login_duration', true);

const baseline = { login: 5, query: 22, page: 10, commission: 5, report: 8 };
const stress = { login: 20, query: 100, page: 30, commission: 20, report: 30 };
const load = LOAD === 'stress' ? stress : baseline;

const readScenarios = {
  login: constantScenario('loginFlow', load.login),
  composite_query: constantScenario('compositeQuery', load.query),
  large_pagination: constantScenario('largePagination', load.page),
  report_read: constantScenario('reportRead', load.report),
};

const fullScenarios = {
  ...readScenarios,
  commission_write: constantScenario('commissionWrite', load.commission),
  task_transition: oneShotScenario('taskTransition', Math.max(TASK_IDS.length, 1)),
  test_submit: oneShotScenario('testSubmit', Math.max(TEST_RECORD_IDS.length, 1)),
};

export const options = {
  scenarios: PROFILE === 'read' ? readScenarios : fullScenarios,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    lims_query_duration: ['p(95)<1000'],
    lims_write_duration: ['p(95)<2000'],
    lims_login_duration: ['p(95)<2000'],
  },
  noConnectionReuse: false,
  userAgent: 'LIMS-k6-production-gate/1.0',
};

let vuSession;

export function setup() {
  requireValue('K6_USERNAME', USERNAME);
  requireValue('K6_PASSWORD', PASSWORD);
  if (PROFILE === 'full') {
    requireValue('K6_TEMPLATE_COMMISSION_ID', TEMPLATE_COMMISSION_ID);
    requireList('K6_TASK_IDS', TASK_IDS);
    requireList('K6_TEST_RECORD_IDS', TEST_RECORD_IDS);
    requireList('K6_REPORT_IDS', REPORT_IDS);
    requireValue('K6_TASK_ACTION', __ENV.K6_TASK_ACTION);
  } else {
    requireList('K6_REPORT_IDS', REPORT_IDS);
  }

  const session = login();
  if (!session) fail('Unable to establish a benchmark session');
  if (PROFILE === 'full') {
    assertSyntheticCommission(session, TEMPLATE_COMMISSION_ID, 'commission template');
    TASK_IDS.forEach((id) => assertTaskFixture(session, id));
    TEST_RECORD_IDS.forEach((id) => assertTestFixture(session, id));
  }
  REPORT_IDS.forEach((id) => assertReportFixture(session, id));
  return { validated: true };
}

export function loginFlow() {
  const session = login();
  check(session, { 'login and CSRF succeeded': (value) => Boolean(value) });
  sleep(1);
}

export function compositeQuery() {
  const session = authenticated();
  const started = Date.now();
  const responses = http.batch([
    ['GET', `${BASE_URL}/api/workbench?status=ALL&from=2000-01-01&to=2100-01-01&q=`, null, session.params],
    ['GET', `${BASE_URL}/api/commissions?viewMode=COMMISSION&flowStates=PENDING&limit=100`, null, session.params],
    ['GET', `${BASE_URL}/api/samples/ledger?view=SAMPLE_LEDGER&limit=100`, null, session.params],
    ['GET', `${BASE_URL}/api/tasks/queue?view=TASKS&limit=100`, null, session.params],
    ['GET', `${BASE_URL}/api/tests/ledger?view=TEST_LEDGER&limit=100`, null, session.params],
    ['GET', `${BASE_URL}/api/reports/queue?view=REPORT_LEDGER&limit=100`, null, session.params],
  ]);
  queryDuration.add(Date.now() - started);
  check(responses, {
    'composite queries succeeded': (values) => values.every((response) => response.status === 200),
  });
  sleep(0.25);
}

export function largePagination() {
  const session = authenticated();
  let cursor = '';
  let pages = 0;
  const started = Date.now();
  do {
    const suffix = cursor ? `&cursor=${encodeURIComponent(cursor)}` : '';
    const response = http.get(`${BASE_URL}/api/samples/ledger?view=SAMPLE_LEDGER&limit=100${suffix}`, session.params);
    if (!check(response, { 'cursor page succeeded': (value) => value.status === 200 })) break;
    const body = response.json();
    cursor = body.hasMore ? body.nextCursor : '';
    pages += 1;
  } while (cursor && pages < 5);
  queryDuration.add(Date.now() - started);
  sleep(0.25);
}

export function commissionWrite() {
  const session = authenticated();
  const detail = getJson(session, `/api/commissions/${TEMPLATE_COMMISSION_ID}`);
  const key = idempotencyKey('commission-copy');
  const started = Date.now();
  const response = write(session, 'POST', `/api/commissions/${TEMPLATE_COMMISSION_ID}/copies`, {
    expectedVersion: detail.version,
  }, key);
  writeDuration.add(Date.now() - started);
  check(response, {
    'synthetic commission copied': (value) => value.status === 200 || value.status === 201,
  });
  sleep(0.25);
}

export function taskTransition() {
  const session = authenticated();
  const id = fixtureForVu(TASK_IDS, 'K6_TASK_IDS');
  const task = getJson(session, `/api/tasks/${id}`);
  const body = {
    action: __ENV.K6_TASK_ACTION,
    expectedVersion: task.version,
    reasonCode: 'SHOWCASE_PARITY_PERFORMANCE',
    comment: 'SHOWCASE k6 task transition',
  };
  if (__ENV.K6_TASK_ASSIGNEE_ID) body.assignedTo = __ENV.K6_TASK_ASSIGNEE_ID;
  const started = Date.now();
  const response = write(session, 'POST', `/api/tasks/${id}/transitions`, body, idempotencyKey('task'));
  writeDuration.add(Date.now() - started);
  check(response, { 'task transition succeeded': (value) => value.status === 200 });
}

export function testSubmit() {
  const session = authenticated();
  const id = fixtureForVu(TEST_RECORD_IDS, 'K6_TEST_RECORD_IDS');
  const record = getJson(session, `/api/tests/${id}`);
  const started = Date.now();
  const response = write(session, 'POST', `/api/tests/${id}/transitions`, {
    action: 'SUBMIT',
    expectedVersion: record.version,
    reasonCode: 'SHOWCASE_PARITY_PERFORMANCE',
    comment: 'SHOWCASE k6 controlled test submission',
    result: __ENV.K6_TEST_RESULT || 'PASS',
  }, idempotencyKey('test-submit'));
  writeDuration.add(Date.now() - started);
  check(response, { 'test submission succeeded': (value) => value.status === 200 });
}

export function reportRead() {
  const session = authenticated();
  const id = REPORT_IDS[(__VU - 1) % REPORT_IDS.length];
  const started = Date.now();
  const response = http.get(`${BASE_URL}/api/reports/${id}`, session.params);
  queryDuration.add(Date.now() - started);
  check(response, { 'report read succeeded': (value) => value.status === 200 });
  sleep(0.25);
}

export function handleSummary(data) {
  const path = __ENV.K6_SUMMARY_PATH || 'k6-summary.json';
  const summary = JSON.stringify(data, null, 2);
  return {
    [path]: summary,
    stdout: `k6 profile=${PROFILE} load=${LOAD} summary=${path}\n`,
  };
}

function constantScenario(exec, vus) {
  return { executor: 'constant-vus', exec, vus, duration: DURATION, startTime: '15s', gracefulStop: '15s' };
}

function oneShotScenario(exec, vus) {
  return { executor: 'per-vu-iterations', exec, vus, iterations: 1, maxDuration: '12s', gracefulStop: '3s' };
}

function csv(value) {
  return (value || '').split(',').map((item) => item.trim()).filter(Boolean);
}

function requireValue(name, value) {
  if (!value) fail(`${name} is required`);
}

function requireList(name, values) {
  if (!values.length) fail(`${name} must contain at least one fixture id`);
}

function login() {
  const jar = http.cookieJar();
  const started = Date.now();
  const response = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ username: USERNAME, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
  });
  loginDuration.add(Date.now() - started);
  if (response.status !== 200 && response.status !== 204) return null;
  const me = http.get(`${BASE_URL}/api/auth/me`);
  if (me.status !== 200) return null;
  const tokens = jar.cookiesForURL(BASE_URL)['XSRF-TOKEN'];
  if (!tokens || !tokens.length) return null;
  return {
    csrf: tokens[0],
    params: { headers: { Accept: 'application/json' } },
  };
}

function authenticated() {
  if (!vuSession) vuSession = login();
  if (!vuSession) fail('benchmark authentication failed');
  return vuSession;
}

function write(session, method, path, body, key) {
  return http.request(method, `${BASE_URL}${path}`, JSON.stringify(body), {
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': session.csrf,
      'Idempotency-Key': key,
    },
  });
}

function getJson(session, path) {
  const response = http.get(`${BASE_URL}${path}`, session.params);
  if (response.status !== 200) fail(`GET ${path} returned ${response.status}`);
  return response.json();
}

function idempotencyKey(prefix) {
  return `k6-${prefix}-${__VU}-${__ITER}-${Date.now()}`;
}

function fixtureForVu(values, name) {
  const value = values[__VU - 1];
  if (!value) fail(`${name} needs one unique fixture id for VU ${__VU}`);
  return value;
}

function assertSyntheticCommission(session, id, label) {
  const detail = getJson(session, `/api/commissions/${id}`);
  if (!JSON.stringify(detail).includes('SHOWCASE')) {
    fail(`${label} ${id} is not a SHOWCASE synthetic record`);
  }
  return detail;
}

function assertTaskFixture(session, id) {
  const task = getJson(session, `/api/tasks/${id}`);
  assertSyntheticCommission(session, task.commissionId, `task fixture ${id}`);
}

function assertTestFixture(session, id) {
  const record = getJson(session, `/api/tests/${id}`);
  assertTaskFixture(session, record.taskId);
}

function assertReportFixture(session, id) {
  const report = getJson(session, `/api/reports/${id}`);
  assertSyntheticCommission(session, report.commissionId, `report fixture ${id}`);
}
