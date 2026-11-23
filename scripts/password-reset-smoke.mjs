import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const config = {};
for (const line of (await readFile('../api-portal/.env', 'utf8')).split(/\r?\n/)) {
  const match = /^([A-Z_]+)=(.*)$/.exec(line);
  if (match) config[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
}
const bank = 'http://127.0.0.1:3110';
const portal = 'http://127.0.0.1:8092';
const cookies = {};
const id = randomUUID();
const email = 'reset-approval-' + id + '@example.test';
const original = 'BeforeReset-' + id + '!';
const replacement = 'AfterReset-' + id + '!';
const service = 'Bearer ' + config.PORTAL_REGISTRATION_TOKEN;
const basic = password => 'Basic ' + Buffer.from(email + ':' + password).toString('base64');

async function call(origin, path, { body, role, authorization, expected = 200 } = {}) {
  const response = await fetch(origin + path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', 'X-Portal-Request': '1',
      ...(role && { Cookie: cookies[role] }), ...(authorization && { Authorization: authorization }) },
    body: body && JSON.stringify(body),
  });
  const value = await response.json().catch(() => ({}));
  assert.equal(response.status, expected, path + ' ' + JSON.stringify(value));
  return value;
}
for (const role of ['maker', 'checker', 'product-manager']) {
  const response = await fetch(bank + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: role, password: 'Backstage123!' }),
  });
  assert.equal(response.status, 200);
  cookies[role] = response.headers.get('set-cookie').split(';')[0];
}
const partner = await call(bank, '/api/partners', { role: 'maker', body: {
  name: 'Demo reset approval ' + id.slice(0, 8), makerEmail: 'maker@example.test', checkerEmail: 'checker@example.test',
  details: { industry: 'Technology', companyEmail: 'office@example.test', companyPhone: '02150188888',
    managerName: 'Reset Test Manager', managerEmail: email, managerPhone: '08178293800', sameContact: true },
} });
let review = await call(bank, '/api/requests/' + partner.id + '/actions', {
  role: 'maker', body: { action: 'submit', version: partner.history.length },
});
await call(bank, '/api/requests/' + partner.id + '/actions', {
  role: 'checker', body: { action: 'approve', version: review.history.length },
});
await call(portal, '/internal/v1/registrations', { authorization: service, body: {
  referenceId: 'partner-invitation-' + partner.id, organizationId: partner.id, companyName: partner.name,
  name: 'Reset Test Manager', email,
} });
await call(portal, '/api/account/password', { authorization: basic('Portal123!'), body: { password: original } });
const resetPath = '/api/partners/' + partner.id + '/password-resets';
await call(bank, resetPath, { role: 'checker', body: {}, expected: 403 });
await call(bank, resetPath, { role: 'product-manager', body: {}, expected: 403 });
const rejected = await call(bank, resetPath, { role: 'maker', body: { reason: 'User forgot password' } });
await call(bank, '/api/password-resets/' + rejected.id + '/actions', {
  role: 'checker', body: { action: 'reject', note: 'Test rejection path', version: rejected.history.length },
});
const reset = await call(bank, resetPath, { role: 'maker', body: { reason: 'Approved recovery test', email: 'wrong@example.test' } });
assert.equal(reset.email, email);
await call(bank, resetPath, { role: 'maker', body: {}, expected: 409 });
const reference = 'password-reset-' + reset.id;
await call(portal, '/internal/v1/password-resets/' + reference, { authorization: service, expected: 404 });
await call(bank, '/api/password-resets/' + reset.id + '/actions', {
  role: 'maker', body: { action: 'approve', version: reset.history.length }, expected: 403,
});
await call(bank, '/api/requests/' + reset.id + '/actions', {
  role: 'checker', body: { action: 'approve', version: reset.history.length }, expected: 409,
});
await call(bank, '/api/password-resets/' + reset.id + '/actions', {
  role: 'checker', body: { action: 'approve', version: reset.history.length },
});
await call(bank, '/api/password-resets/' + reset.id + '/actions', {
  role: 'checker', body: { action: 'approve', version: reset.history.length }, expected: 409,
});
async function record(wanted) {
  const deadline = Date.now() + 35000;
  while (Date.now() < deadline) {
    const row = (await call(bank, '/api/requests', { role: 'maker' })).find(item => item.id === reset.id);
    if (row.state === wanted) return row;
    await delay(500);
  }
  throw new Error('Reset did not reach ' + wanted);
}
const failed = await record('failed');
assert.equal(failed.resetDelivery.failureStep, 'email');
assert.equal(JSON.stringify(failed).includes('resetUrl'), false);
await call(portal, '/api/account/status', { authorization: basic(original) });
const issued = await call(portal, '/internal/v1/password-resets', { authorization: service, body: {
  referenceId: reference, registrationReference: 'partner-invitation-' + partner.id, organizationId: partner.id, email,
} });
await call(bank, '/api/password-resets/' + reset.id + '/retry', { role: 'product-manager', body: {}, expected: 403 });
await call(bank, '/api/password-resets/' + reset.id + '/retry', { role: 'checker', body: {} });
const sent = await record('sent');
assert.equal(sent.resetDelivery.attempts, 2);
assert.equal((await call(portal, '/internal/v1/password-resets', { authorization: service, body: {
  referenceId: reference, registrationReference: 'partner-invitation-' + partner.id, organizationId: partner.id, email,
} })).resetId, issued.resetId);
const token = new URLSearchParams(new URL(issued.resetUrl).hash.slice(1)).get('token');
assert.equal((await call(portal, '/api/password-resets/inspect', { body: { token } })).email, email);
await call(portal, '/api/password-resets/complete', { body: { token, password: replacement } });
await call(portal, '/api/account/status', { authorization: basic(original), expected: 401 });
assert.equal((await call(portal, '/api/account/status', { authorization: basic(replacement) })).passwordChangeRequired, false);
await record('completed');
const updated = (await call(bank, '/api/requests', { role: 'maker' })).find(row => row.id === partner.id);
assert.ok(updated.history.some(row => row.state === 'password_reset_requested'));
assert.ok(updated.history.some(row => row.state === 'password_reset_approved'));
assert.ok(updated.history.some(row => row.state === 'password_reset_failed'));
assert.ok(updated.history.some(row => row.state === 'password_reset_sent'));
assert.ok(updated.history.some(row => row.state === 'password_reset_completed'));
await call(bank, '/api/password-resets/' + reset.id + '/retry', { role: 'maker', body: {}, expected: 409 });
console.log('Password reset approval test passed: fixed recipient, separate Checker approval, no pre-approval issuance, email retry without duplicate reset, password replacement, and completion history.');
console.log('Fictional partner: ' + partner.name);
