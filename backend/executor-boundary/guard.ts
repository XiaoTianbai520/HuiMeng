import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

type Context = {
  v: number; iat: number; exp: number; nonce: string;
  actorId: string; projectId: string; workspaceKey: string; requestId: string;
  method: string; path: string; bodySha256: string;
};

// One instance, as required by DESIGN. A valid signed request consumes its nonce once.
export class InternalGuard {
  private readonly nonces = new Map<string, number>();
  constructor(private readonly key: Buffer, private readonly now = () => Math.floor(Date.now() / 1000)) {
    if (key.length !== 32) throw new Error('Invalid internal key');
  }
  verify(header: string | null, method: string, path: string, body: Uint8Array): Context {
    if (!header || header.length > 4096) throw new Error('Unauthorized');
    const parts = header.split('.');
    if (parts.length !== 2 || !parts.every(p => /^[A-Za-z0-9_-]+$/.test(p))) throw new Error('Unauthorized');
    const [payload, signature] = parts;
    const expected = createHmac('sha256', this.key).update(payload).digest();
    const actual = Buffer.from(signature, 'base64url');
    if (actual.length !== expected.length || !timingSafeEqual(expected, actual)) throw new Error('Unauthorized');
    const context: Context = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    const fields = ['v','iat','exp','nonce','actorId','projectId','workspaceKey','requestId','method','path','bodySha256'];
    const now = this.now();
    if (Object.keys(context).sort().join() !== fields.sort().join() || context.v !== 1 ||
      !Number.isSafeInteger(context.iat) || !Number.isSafeInteger(context.exp) || context.iat > now + 2 || context.exp <= now || context.exp - context.iat > 30 || context.exp <= context.iat ||
      !['nonce','actorId','projectId','workspaceKey','requestId'].every(k => typeof context[k as keyof Context] === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(String(context[k as keyof Context]))) ||
      context.method !== method || context.path !== path || context.bodySha256 !== createHash('sha256').update(body).digest('hex')) throw new Error('Unauthorized');
    for (const [nonce, expiry] of this.nonces) if (expiry <= now) this.nonces.delete(nonce);
    if (this.nonces.has(context.nonce) || this.nonces.size >= 10000) throw new Error('Unauthorized');
    this.nonces.set(context.nonce, context.exp);
    return context;
  }
}
