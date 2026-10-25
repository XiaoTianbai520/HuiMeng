import { describe, expect, test } from 'bun:test';
import { createHash, createHmac, randomBytes } from 'node:crypto';
import { InternalGuard } from './guard';

const key = randomBytes(32);
function signed(change: Record<string, unknown> = {}, signingKey = key): string {
  const context = { v: 1, iat: 1000, exp: 1020, nonce: crypto.randomUUID(), actorId: 'synthetic-user', projectId: 'synthetic-project', workspaceKey: 'synthetic-workspace', requestId: 'synthetic-request', method: 'GET', path: '/internal/ready', bodySha256: createHash('sha256').update('').digest('hex'), ...change };
  const payload = Buffer.from(JSON.stringify(context)).toString('base64url');
  return payload + '.' + createHmac('sha256', signingKey).update(payload).digest('base64url');
}
describe('Spring to Bun request identity', () => {
  test('accepts a signed short-lived context once', () => {
    const guard = new InternalGuard(key, () => 1001), token = signed();
    expect(guard.verify(token, 'GET', '/internal/ready', new Uint8Array()).projectId).toBe('synthetic-project');
    expect(() => guard.verify(token, 'GET', '/internal/ready', new Uint8Array())).toThrow();
  });
  test('rejects missing, forged and wrong-key identities', () => {
    const guard = new InternalGuard(key, () => 1001);
    for (const token of [null, 'legacy-global-token', signed({}, randomBytes(32)), signed() + 'x']) expect(() => guard.verify(token, 'GET', '/internal/ready', new Uint8Array())).toThrow();
  });
  test('rejects expired, future, overlong and inverted lifetimes', () => {
    for (const change of [{ exp: 1001 }, { iat: 1004 }, { exp: 1040 }, { exp: 999 }]) expect(() => new InternalGuard(key, () => 1001).verify(signed(change), 'GET', '/internal/ready', new Uint8Array())).toThrow();
  });
  test('binds method, path, body, actor and workspace', () => {
    for (const change of [{ method: 'POST' }, { path: '/creative/ai/image' }, { bodySha256: 'forged' }, { actorId: '../other' }, { workspaceKey: '/etc' }, { directory: '/tmp' }]) expect(() => new InternalGuard(key, () => 1001).verify(signed(change), 'GET', '/internal/ready', new Uint8Array())).toThrow();
  });
});
