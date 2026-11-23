import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const path = process.argv[2];
if (!path) throw new Error('Pass a fictional Portal login-event JSON file to this verification script.');
const event = JSON.parse(await readFile(path, 'utf8'));
const config = {};
for (const file of ['../api-portal/.env', '.env']) {
  const text = await readFile(file, 'utf8').catch(() => '');
  for (const line of text.split(/\r?\n/)) {
    const match = /^([A-Z_]+)=(.*)$/.exec(line);
    if (match) config[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
  }
}
const token = process.env.BACKSTAGE_TOKEN || config.BACKSTAGE_TOKEN || 'local-backstage-portal';
const origin = 'http://127.0.0.1:8091';
async function send(body, expected = 200, auth = token) {
  const response = await fetch(origin + '/integration/v1/portal-login-events', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + auth },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  assert.equal(response.status, expected, text);
}
const login = await fetch(origin + '/api/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'maker', password: 'Backstage123!' }),
});
assert.equal(login.status, 200);
const cookie = login.headers.get('set-cookie').split(';')[0];
async function partner() {
  const response = await fetch(origin + '/api/requests', { headers: { Cookie: cookie } });
  assert.equal(response.status, 200);
  return (await response.json()).find(item => item.id === event.organizationId);
}
await send(event, 401, 'wrong-token');
await send(event);
await Promise.all([send(event), send(event)]);
let record = await partner();
assert.equal(record.history.filter(entry => entry.eventId === event.eventId).length, 1);
assert.ok(record.history.some(entry => entry.state === 'portal_registration_accepted'));
const newest = record.portalRegistration.lastLoginAt;
await send({ ...event, email: 'different@example.test' }, 409);
await send({ ...event, eventId: randomUUID(), email: 'different@example.test' }, 409);
await send({ ...event, eventId: randomUUID(), userId: randomUUID() }, 409);
await send({ ...event, eventId: randomUUID(), referenceId: 'partner-invitation-unrelated' }, 409);
await send({ ...event, eventId: randomUUID(), organizationId: randomUUID() }, 409);
const older = { ...event, eventId: randomUUID(), occurredAt: new Date(Date.parse(event.occurredAt) - 60000).toISOString() };
await Promise.all([send(older), send(older)]);
record = await partner();
assert.equal(record.portalRegistration.lastLoginAt, newest);
assert.equal(record.history.filter(entry => entry.eventId === older.eventId).length, 1);
console.log('Portal login-event checks passed: authenticated identity, durable deduplication, conflict rejection, concurrent retries, separate activation, and newest lastLoginAt.');
