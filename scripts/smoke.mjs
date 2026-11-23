import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const api = process.env.BACKSTAGE_API_URL ?? 'http://127.0.0.1:8091';
const ui = process.env.BACKSTAGE_UI_URL ?? 'http://127.0.0.1:3110';
const sessions = {};
const portal = process.env.BACKSTAGE_TOKEN ?? 'local-backstage-portal';

async function call(path, { token, status = 200, method = 'GET', body, key, origin = api } = {}) {
  const response = await fetch(origin + path, {
    method,
    headers: {
      ...(token && (sessions[token] ? { Cookie: sessions[token] } : { Authorization: 'Bearer ' + token })),
      ...(key && { 'Idempotency-Key': key }),
      ...(body && { 'Content-Type': 'application/json' }),
    },
    body: body && JSON.stringify(body),
  });
  const text = await response.text();
  assert.equal(response.status, status, method + ' ' + path + ' ' + text);
  return response.headers.get('content-type')?.includes('application/json') ? JSON.parse(text) : text;
}

for (const username of ['maker', 'checker', 'product-manager']) {
  const response = await fetch(api + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'Backstage123!' }),
  });
  assert.equal(response.status, 200);
  sessions['demo-' + username] = response.headers.get('set-cookie').split(';')[0];
}
await call('/api/requests', { status: 401 });
await call('/api/requests', { token: 'demo-product-manager' });
await call('/api/partners', { token: 'demo-product-manager', method: 'POST', body: {}, status: 403 });
await call('/api/applications/any/rotations', { token: 'demo-product-manager', method: 'POST', body: {}, status: 403 });
await call('/api/requests/any/actions', { token: 'demo-product-manager', method: 'POST',
  body: { action: 'approve' }, status: 403 });
const maker = 'demo-maker';
const checker = 'demo-checker';
const act = (token, request, action, status = 200, note) =>
  call('/api/requests/' + request.id + '/actions', {
    token, method: 'POST', status, body: { action, note, version: request.history.length },
  });
assert.match(await call('/login', { origin: ui }), /Masuk ke Backstage/);
assert.deepEqual(await call('/health'), { ok: true });
assert.ok(Array.isArray(await call('/api/requests', { origin: ui, token: maker })));
const draftName = 'Docker smoke ' + randomUUID().slice(0, 8);
const draft = await call('/api/partners', { token: maker, method: 'POST', body: {
  name: draftName, details: { industry: 'Technology' },
} });
await act(maker, draft, 'submit', 400);
const partner = await call('/api/partners/' + draft.id, { token: maker, method: 'PATCH', body: {
  name: draftName, makerEmail: 'maker@example.test', checkerEmail: 'checker@example.test',
  details: { industry: 'Technology', companyEmail: 'office@example.test', companyPhone: '02150188888',
    managerName: 'Demo Manager', managerEmail: 'manager@example.test', managerPhone: '08178293800',
    sameContact: true },
} });
const reopened = (await call('/api/requests', { origin: ui, token: maker })).find(record => record.id === draft.id);
assert.equal(reopened.details.industry, 'Technology');
assert.equal(reopened.details.supportName, 'Demo Manager');
assert.equal(reopened.details.supportEmail, 'manager@example.test');
assert.equal(reopened.details.supportPhone, '08178293800');
const review = await act(maker, partner, 'submit');
await act(maker, review, 'approve', 403);
const approved = await act(checker, review, 'approve');
const invite = approved.invitations[0];
const redemption = { token: invite.token, email: invite.email, redemptionId: randomUUID() };
const redeem = (body, status) => call('/integration/v1/invitations/redeem', { token: portal, method: 'POST', body, status });
assert.equal((await redeem(redemption)).role, 'partner-maker');
assert.equal((await redeem(redemption)).organizationId, partner.id);
await redeem({ ...redemption, redemptionId: randomUUID() }, 409);

const requests = '/partner/v1/organizations/' + partner.id + '/requests';
const payload = { applicationName: 'Smoke application', description: 'Docker integration check',
  useCase: 'Sandbox check', contactName: 'Tester', contactEmail: 'maker@example.test',
  environment: 'SANDBOX', scopes: ['ACCOUNTS_READ'], credentialMethod: 'CLIENT_SECRET' };
const key = randomUUID();
const submit = (body, status) => call(requests, { token: portal, method: 'POST', body, key, status });
const created = await submit(payload, 201);
assert.equal((await submit(payload, 200)).id, created.id);
await submit({ ...payload, applicationName: 'Different' }, 409);
await call('/partner/v1/organizations/another-org/requests/' + created.id, { token: portal, status: 404 });
const current = async id => (await call('/api/requests', { token: maker })).find(record => record.id === id);
const application = await current(created.id);
await act(checker, application, 'approve', 409);
const active = await act(checker, await act(maker, application, 'verify'), 'approve');
assert.equal(active.gateway.keyVersion, 1);
const partnerView = await call(requests + '/' + active.id, { token: portal });
assert.equal(partnerView.status, 'ACTIVE');
assert.equal(partnerView.gateway, undefined);
assert.equal((await call(requests + '?environment=SANDBOX', { token: portal })).length, 1);

const rotate = body => call('/api/applications/' + active.id + '/rotations', { token: maker, method: 'POST', body });
const immediate = await rotate({ mode: 'immediate' });
assert.equal((await act(checker, await act(maker, immediate, 'verify'), 'approve')).gateway.keyVersion, 2);
const scheduled = await rotate({ mode: 'scheduled', executeAt: new Date(Date.now() + 3000).toISOString() });
assert.equal((await act(checker, await act(maker, scheduled, 'verify'), 'approve')).state, 'scheduled');
const deadline = Date.now() + 10000;
while ((await current(scheduled.id)).state !== 'executed' && Date.now() < deadline) await delay(500);
assert.equal((await current(scheduled.id)).state, 'executed');
assert.equal((await current(active.id)).gateway.keyVersion, 3);
console.log('Backstage Docker smoke passed. Active application: ' + active.id);

await call('/api/auth/logout', { token: 'demo-product-manager', method: 'POST', body: {} });
await call('/api/requests', { token: 'demo-product-manager', status: 401 });
