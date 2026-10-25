import { readFileSync, lstatSync, realpathSync } from 'node:fs';
import { resolve, sep } from 'node:path';
import { InternalGuard } from './guard';

const file = process.env.HM_INTERNAL_KEY_FILE;
if (!file) throw new Error('Internal key file required');
const guard = new InternalGuard(Buffer.from(readFileSync(file, 'utf8').trim(), 'base64'));
const root = realpathSync(process.env.HM_WORKSPACE_ROOT || '/data/huimeng/workspaces');
Bun.serve({
  hostname: process.env.HM_BIND_ADDRESS || '127.0.0.1',
  port: Number(process.env.HM_PORT || 3000),
  maxRequestBodySize: 65536,
  async fetch(request) {
    try {
      const url = new URL(request.url);
      if (url.search || request.headers.has('X-Workspace-Dir') || request.headers.has('X-Local-Token') || request.headers.has('Authorization')) throw new Error('Unauthorized');
      const context = guard.verify(request.headers.get('X-HuiMeng-Internal'), request.method, url.pathname, new Uint8Array(await request.arrayBuffer()));
      const workspace = resolve(root, context.workspaceKey);
      if (!workspace.startsWith(root + sep) || lstatSync(workspace).isSymbolicLink() || realpathSync(workspace) !== workspace) throw new Error('Unauthorized');
      // M2 health-only boundary. No creative, MCP, A2A or wildcard upstream router is mounted.
      if (request.method === 'GET' && url.pathname === '/internal/ready') return Response.json({ ready: true }, { headers: { 'Cache-Control': 'no-store' } });
      return new Response(null, { status: 404 });
    } catch {
      return new Response(null, { status: 401 });
    }
  },
});
