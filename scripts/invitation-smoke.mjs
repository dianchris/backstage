import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';

const origin = process.env.BACKSTAGE_URL || 'http://127.0.0.1:3110';
const cookies = {};
const invitedEmail = 'pm-' + Date.now() + '@example.test';
async function call(path, role, body, status = 200) {
  const response = await fetch(origin + path, {
    method: body ? 'POST' : 'GET',
    headers: { Cookie: cookies[role] ?? '', 'Content-Type': 'application/json' },
    body: body && JSON.stringify(body),
  });
  const value = await response.json();
  assert.equal(response.status, status, JSON.stringify(value));
  return value;
}
for (const role of ['maker', 'checker', 'product-manager']) {
  const response = await fetch(origin + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: role, password: 'Backstage123!' }),
  });
  assert.equal(response.status, 200);
  cookies[role] = response.headers.get('set-cookie').split(';')[0];
}
const partner = await call('/api/partners', 'maker', {
  name: 'Demo REST Email ' + Date.now(), makerEmail: 'maker@example.test', checkerEmail: 'checker@example.test',
  details: { industry: 'Technology', companyEmail: 'office@example.test', companyPhone: '02150188888',
    managerName: 'Demo Project Manager', managerEmail: invitedEmail, managerPhone: '08178293800',
    sameContact: true },
});
const review = await call('/api/requests/' + partner.id + '/actions', 'maker', {
  action: 'submit', version: partner.history.length,
});
const approved = await call('/api/requests/' + partner.id + '/actions', 'checker', {
  action: 'approve', version: review.history.length,
});
assert.equal(approved.state, 'approved');
assert.equal(approved.invitationDelivery.recipient, invitedEmail);
async function waitFor(status) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    const record = (await call('/api/requests', 'checker')).find(item => item.id === partner.id);
    if (record.invitationDelivery.status === status) return record;
    await delay(500);
  }
  throw new Error('Invitation did not reach ' + status);
}
const failed = await waitFor('failed');
assert.equal(failed.state, 'approved');
assert.equal(failed.invitationDelivery.attempts, 1);
assert.equal(failed.invitationDelivery.failureStep, 'email');
assert.equal(failed.portalRegistration.status, 'registered');
await call('/api/partners/' + partner.id + '/invitation/retry', 'product-manager', {}, 403);
await call('/api/partners/' + partner.id + '/invitation/retry', 'checker', {});
await call('/api/partners/' + partner.id + '/invitation/retry', 'checker', {}, 409);
const sent = await waitFor('sent');
assert.equal(sent.state, 'approved');
assert.equal(sent.invitationDelivery.attempts, 2);
assert.ok(sent.history.some(entry => entry.state === 'invitation_failed'));
assert.ok(sent.history.some(entry => entry.state === 'invitation_sent'));
assert.equal(sent.invitationDelivery.token, undefined);
await call('/api/partners/' + partner.id + '/invitation/retry', 'maker', {}, 409);
console.log('Portal registration contract and REST invitation retry test passed. Partner ID ' + partner.id);
