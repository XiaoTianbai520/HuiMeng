import { afterEach, beforeEach, expect, test } from "bun:test";
import client from "../packages/api/client";
import { setActor } from "../packages/api/context";
import { readAll } from "../src/lib/teamAdmin";

const originalAdapter = client.defaults.adapter;
beforeEach(() => setActor({ id: "synthetic-admin", username: "fixture", role: "ADMIN", status: "ACTIVE" }));
afterEach(() => { client.defaults.adapter = originalAdapter; setActor(null); });

test("member choices include every cursor page rather than just the first fifty users", async () => {
  const calls: string[] = [];
  client.defaults.adapter = async config => {
    calls.push(config.url!);
    return { status: 200, statusText: "OK", config, headers: {}, data: calls.length === 1
      ? { items: Array.from({ length: 50 }, (_, id) => ({ id })), nextCursor: "next-page" }
      : { items: [{ id: 50 }], nextCursor: null } };
  };
  expect(await readAll("/api/v1/admin/users")).toHaveLength(51);
  expect(calls[1]).toContain("cursor=next-page");
});
test("a repeated cursor fails instead of looping forever", async () => {
  client.defaults.adapter = async config => ({ status: 200, statusText: "OK", config, headers: {}, data: { items: [], nextCursor: "repeated" } });
  await expect(readAll("/api/v1/admin/users")).rejects.toThrow("游标重复");
});
test("results started by another account are discarded", async () => {
  client.defaults.adapter = async config => {
    setActor({ id: "other-admin", username: "other", role: "ADMIN", status: "ACTIVE" });
    return { status: 200, statusText: "OK", config, headers: {}, data: { items: [{ id: "private" }], nextCursor: null } };
  };
  await expect(readAll("/api/v1/admin/users")).rejects.toThrow("会话已变更");
});
