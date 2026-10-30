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
const timelineHtml = source.match(/USER_TIMELINE_HTML\s*=\s*r'''([\s\S]*?)'''/)[1];
const timelineScript = timelineHtml.match(/<script>([\s\S]*?)<\/script>/)[1];
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
const openedWindows = [];
const context = vm.createContext({
  console, URLSearchParams, URL, Date, Math, Number, String, Object, JSON, Error, Promise,
  location: { pathname: '/joyedrama-rewards/admin', search: '', origin: 'https://admin.example.test' },
  window: {
    location: { pathname: '/joyedrama-rewards/admin', search: '', origin: 'https://admin.example.test' },
    open(url, target, features) { openedWindows.push({ url, target, features }); },
  },
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
    } else if (url.includes('/v2/admin/analytics/behavior-timeline/')) {
      data = {
        user_id: 'user-demo', start_date: '2026-10-15', end_date: '2026-10-15',
        total: 1, page: 1, page_size: 500,
        rows: [{
          user_id: 'user-demo', session_id: 'session-demo', event_category: 'UI', event_name: 'TAB_CHANGED',
          properties: { tab: 'home' }, app_version: '1.7.4.beta',
          client_occurred_at: '2026-10-15T08:00:00+00:00', accepted_at: '2026-10-15T08:00:01+00:00',
        }],
      };
    } else if (url.includes('/v2/admin/analytics/behavior-flow')) {
      data = {
        start_date: '2026-10-15', end_date: '2026-10-15', total: 3, unique_users: 1,
        user_total: 1, user_page: 1, user_page_size: 100,
        journey: { session_count: 1, ui_action_count: 1, ad_clicked_count: 0, ad_host_resumed_count: 0, ad_external_returned_count: 0 },
        breakdown: [{ event_category: 'UI', event_name: 'TAB_CHANGED', event_count: 1, user_count: 1 }],
        version_breakdown: [{
          app_version: '1.7.4.beta', event_count: 3, user_count: 1, session_count: 1,
          ui_action_count: 1, ad_event_count: 0, last_event_at: '2026-10-15T08:00:00+00:00',
        }],
        early_videos: [],
        user_rows: [{
          user_id: 'user-demo', latest_app_version: '1.7.4.beta', event_count: 3, session_count: 1,
          onboarding_event_count: 0, ui_action_count: 1, early_video_started_count: 0, early_video_completed_count: 0,
          ad_shown_count: 0, ad_clicked_count: 0, ad_unavailable_count: 0, ad_reward_verified_count: 0,
          latest_event_name: 'TAB_CHANGED', last_event_at: '2026-10-15T08:00:00+00:00',
        }],
      };
    } else if (url.includes('/v2/admin/operations/first-day-ad-scene-ipu')) {
      data = {
        start_date: '2026-10-15', end_date: '2026-10-15', cohort_users: 54,
        shown_count: 110, reached_users: 37, first_day_ipu: 2.037037, reach_rate: 0.685185,
        definition: 'fixture first-day definition', coverage: 'fixture behavior coverage',
        rows: [{
          logical_placement: 'rv_welcome_bonus', ad_format: 'rewarded',
          attribution_segment: 'PENDING', attribution_segment_label: '待归因（客户端默认 A）',
          shown_count: 44, reached_users: 24, cohort_ipu_contribution: 0.814815,
          reach_rate: 0.444444, reached_user_ipu: 1.833333,
        }],
      };
    } else if (url.includes('/v2/admin/operations/reporting-delivery-versioned')) {
      data = {
        start_date: '2026-10-29', end_date: '2026-10-29', page: 1, page_size: 100, total: 1,
        totals: { success_count: 1, failure_count: 0, retrying_count: 0 },
        version_options: [{ app_version: '1.7.5.0', event_count: 1 }], unknown_version_count: 0,
        disclaimer: 'fixture association caveat',
        rows: [{ client_occurred_at: '2026-10-29T08:14:26+00:00', user_uid: 'user-demo', app_version: '1.7.5.0', provider: 'AppsFlyer', event_name: 'ad_revenue', status: 'SUCCESS', logical_placement: 'rv_release_boost', ad_unit_id: 'n6a8693bbc29bb', currency: 'USD', ecpm: '5.2', value: '0.0052', error_code: '' }],
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
	await vm.runInContext('loadReporting()', context);
  assert.ok(requests.some(({ url }) => url.includes('/v2/admin/operations/reporting-delivery-versioned')), 'reporting page must use the Admin-only versioned endpoint');
  assert.match(node('reportingRows').innerHTML, /1\.7\.5\.0/, 'reporting page must render the associated client version');
  assert.match(node('reportingVersion').innerHTML, /1\.7\.5\.0/, 'reporting page must offer the associated version as a filter');
	await vm.runInContext('loadOperationsScene()', context);
  assert.ok(requests.some(({ url }) => url.includes('/v2/admin/operations/first-day-ad-scene-ipu')), 'operations page must request Admin-only first-day scene IPU');
  assert.equal(node('operationsSceneIpu').textContent, '2.037', 'scene IPU headline must render the cohort-level IPU');
  assert.match(node('operationsSceneRows').innerHTML, /rv_welcome_bonus/, 'scene table must render the logical placement');
  assert.match(node('operationsSceneRows').innerHTML, /待归因（客户端默认 A）/, 'scene table must distinguish pending attribution from confirmed A');
  await vm.runInContext('loadBehavior()', context);
  assert.match(node('behaviorVersionRows').innerHTML, /1\.7\.4\.beta/, 'behavior page must render client-version coverage');
  assert.match(node('behaviorRows').innerHTML, /user-demo/, 'behavior page must render per-user summaries instead of the global event timeline');
  await vm.runInContext('openBehaviorTimeline("user-demo")', context);
  assert.equal(openedWindows.length, 1, 'opening a user must create a dedicated browser window');
  assert.match(openedWindows[0].url, /\/joyedrama-rewards\/admin\/timeline\?user_id=user-demo/, 'timeline window must include the selected user ID');
  assert.equal(openedWindows[0].target, '_blank');
  for (const { options } of requests) {
    assert.equal(options.credentials, 'same-origin');
    assert.ok(!Object.keys(options.headers || {}).some(key => key.toLowerCase() === 'authorization'), 'browser must not read/store bearer token');
  }
  assert.equal(node('save-one').disabled, false);

  const timelineNodes = new Map();
  function timelineNode(id) {
    if (!timelineNodes.has(id)) timelineNodes.set(id, { id, hidden: false, innerHTML: '', textContent: '', className: '', onclick: null });
    return timelineNodes.get(id);
  }
  const timelineContext = vm.createContext({
    URLSearchParams, URL, Date, Math, Number, String, Object, JSON, Error, Promise,
    window: {
      location: { pathname: '/joyedrama-rewards/admin/timeline', search: '?user_id=user-demo' },
      close() {},
    },
    document: { getElementById: timelineNode },
    fetch: async (url, options = {}) => {
      assert.match(url, /\/v2\/admin\/analytics\/behavior-flow\?.*user_id=user-demo/, 'dedicated page must reuse the existing user-filtered behavior API');
      assert.equal(options.credentials, 'same-origin');
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ code: 0, data: {
          start_date: '2026-10-12', end_date: '2026-10-18', total: 501,
          journey: { session_count: 8 },
          user_rows: [{ session_count: 8, latest_app_version: '1.7.4.beta', latest_event_name: 'TAB_CHANGED' }],
          rows: [{ client_occurred_at: '2026-10-18T05:50:56+00:00', session_id: 'session-demo', event_category: 'UI', event_name: 'TAB_CHANGED', properties: { tab: 'home' }, app_version: '1.7.4.beta' }],
        } }),
      };
    },
  });
  new vm.Script(timelineScript, { filename: 'real-user-timeline-page.js' }).runInContext(timelineContext);
  for (let i = 0; i < 12; i++) await Promise.resolve();
  assert.equal(timelineNode('timelineUser').textContent, 'user-demo', 'dedicated page must show the anonymous user at the top');
  assert.match(timelineNode('summaryLine').textContent, /事件：501.*会话：8.*最新版本：1\.7\.4\.beta/, 'dedicated page must show a one-line user summary');
  assert.match(timelineNode('timelineRows').innerHTML, /TAB_CHANGED/, 'dedicated page must render the selected user timeline');
  assert.match(timelineNode('timelinePageStatus').textContent, /第 1\/2 页/, 'more than 500 events must be paginated');
  assert.match(timelineNode('exportCsv').href, /\/v2\/admin\/analytics\/behavior-flow\/export\?.*user_id=user-demo/, 'timeline page must expose the existing user-filtered CSV export');
  await timelineNode('nextTimelinePage').onclick();
  assert.match(timelineNode('timelinePageStatus').textContent, /第 2\/2 页/, 'next page must update the timeline page state');
  console.log('Dashboard runtime regression passed: cookie startup, global bindings, PUT contract, no frontend token.');
})().catch(error => { console.error(error); process.exitCode = 1; });
