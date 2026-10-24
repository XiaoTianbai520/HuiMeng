import client from "@huimeng/api/client";
type Options = { signal?: AbortSignal };
type Manifest = { type: "node" | "ext" | "skill" | "tool"; name: string; version: string; sha256: string };
type MarketItem = { id: string; type: "node" | "ext" | "skill" | "tool" | "agent"; name: string; displayName: string; author: string; description: string; version: string; fileName: string; isCollected: boolean; installable: boolean; manifest: Manifest | null };
export function isTfRouterProvider(provider: { id: string; apiUrl?: string }) { return provider.id.toLowerCase() === "tfrouter"; }
// Compatibility callers cannot obtain a company credential from the browser.
export function getTfApiKey() { return ""; }
export default {
  async status() { return (await client.get<{ configured: boolean }>("/api/v1/admin/plugin-market/status")).data; },
  async getPlugIn(params: { page: number; limit: number; type: string; searchKeyword?: string }, options: Options = {}) {
    const { data } = await client.post<{ items: MarketItem[]; total: number }>("/api/v1/admin/plugin-market/search", params, options);
    return { total: data.total, list: data.items.map(item => ({ ...item, identifier: item.name, name: item.displayName, supplier: item.author, desc: item.description })) };
  },
  async toggleCollection(pluginId: string, options: Options = {}) {
    return (await client.post<{ collected: boolean }>("/api/v1/admin/plugin-market/collections/toggle", { pluginId }, options)).data;
  },
};
