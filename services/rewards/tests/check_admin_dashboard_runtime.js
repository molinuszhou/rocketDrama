// Standard Node-only regression: execute the real dashboard script with a small DOM fixture.
// No production requests, database, administrator credentials, or third-party dependencies.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const sourcePath = process.argv[2] || path.join(__dirname, '..', 'app', 'admin_dashboard.py');
const source = fs.readFileSync(sourcePath, 'utf8');
const html = source.match(/DASHBOARD_HTML\s*=\s*r'''([\s\S]*?)'''/)[1];
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const nodes = new Map();
function node(id) {
  if (!nodes.has(id)) nodes.set(id, {
    id, value: '', checked: false, hidden: false, disabled: false, innerHTML: '', textContent: '', className: '', style: {},
    dataset: { page: 'overview', operationsTab: 'summary' },
    classList: { add() {}, remove() {}, toggle() {} },
    focus() {}, appendChild() {}, remove() {}, click() {},
  });
  return nodes.get(id);
}
node('workspace').hidden = true;
const requests = [];
const context = vm.createContext({
  console, URLSearchParams, URL, Date, Math, Number, String, Object, JSON, Error, Promise,
  location: { pathname: '/joyedrama-rewards/admin', search: '', origin: 'https://admin.example.test' },
  window: { location: { pathname: '/joyedrama-rewards/admin', search: '', origin: 'https://admin.example.test' }, open() {} },
  document: {
    getElementById: node,
    querySelectorAll: (selector) => selector === '.save-operating' ? [node('save-one'), node('save-two')] : [],
    createElement: (id) => node(id), body: node('body'),
  },
  setTimeout() { return 1; }, clearTimeout() {},
  fetch: async (url, options = {}) => {
    requests.push({ url, options });
    let data = {};
    if (url.includes('/v2/admin/users/metrics')) {
      data = {
        total_users: 87,
        filtered_registration_users: 54,
        matching_users_all_registration_dates: 87,
        start_date: '2026-10-15',
        end_date: '2026-10-15',
        daily: [{
          metric_date: '2026-10-15', registration_count: 54,
          daily_active_users: 41, verified_behavior_active_users: 3,
        }],
        definition: 'fixture metric definition',
        data_coverage: { caveat: 'fixture metric coverage' },
        funnel: { rewarded_distribution: {} },
      };
    } else if (url.includes('/v2/admin/users?')) {
      data = { total: 54, limit: 50, rows: [] };
    }
    return { ok: true, status: 200, text: async () => JSON.stringify({ code: 0, data }) };
  },
});
(async () => {
  new vm.Script(script, { filename: 'real-admin-dashboard.js' }).runInContext(context);
  // Fetch/text/render each use asynchronous promise turns.
  for (let i = 0; i < 12; i++) await Promise.resolve();
  assert.ok(requests.some(({ url }) => url.endsWith('/v2/admin/policy')), 'startup must reach automatic cookie verification');
  assert.equal(node('login').hidden, true, 'a successful existing-cookie verification must hide login');
  assert.equal(node('workspace').hidden, false);
  assert.equal(typeof node('save-one').onclick, 'function', 'saveOperating must exist before bindings execute');
  await vm.runInContext('saveOperating()', context);
  const save = requests.find(({ url }) => url.endsWith('/v2/admin/policy/operating'));
  assert.ok(save, 'save button must call the real operating route');
  assert.equal(save.options.method, 'PUT', 'use api(path, method, body), not an options object as method');
  assert.ok(JSON.parse(save.options.body), 'save payload is JSON');
  await vm.runInContext('loadUsers()', context);
  assert.equal(node('usersTotal').textContent, '87', 'headline must remain the all-registration-date cumulative count');
  assert.equal(node('usersRegistrationTotal').textContent, '54', 'selected registration cohort must remain separately visible');
  assert.match(node('userDailyRows').innerHTML, /<td>41<\/td><td>3<\/td>/, 'legacy Rewards activity and new behavior activity must be separate columns');
  assert.ok(requests.some(({ url }) => url.includes('/v2/admin/users/metrics')), 'user metrics request must be made');
  for (const { options } of requests) {
    assert.equal(options.credentials, 'same-origin');
    assert.ok(!Object.keys(options.headers || {}).some(key => key.toLowerCase() === 'authorization'), 'browser must not read/store bearer token');
  }
  assert.equal(node('save-one').disabled, false);
  console.log('Dashboard runtime regression passed: cookie startup, global bindings, PUT contract, no frontend token.');
})().catch(error => { console.error(error); process.exitCode = 1; });
