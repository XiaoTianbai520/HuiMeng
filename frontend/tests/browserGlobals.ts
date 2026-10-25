// ACT: 必须在 axios 适配器模块求值前执行；适配器会在加载时固化 Request/Response 引用。
// 仅用于离线测试：i18n 拦截器读取 location，浏览器相对 Request 在 Bun 中补全为绝对地址。
const noop = () => {};
const windowStub = {
  location: new URL("https://huimeng.invalid/"),
  addEventListener: noop,
  removeEventListener: noop,
  dispatchEvent: () => true,
  navigator: { languages: ["zh-CN"], language: "zh-CN" },
};
Object.assign(globalThis, { window: windowStub, navigator: windowStub.navigator });
const NativeRequest = globalThis.Request;
class TestRequest extends NativeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(typeof input === "string" && input.startsWith("/") ? `https://huimeng.invalid${input}` : input, init);
  }
}
globalThis.Request = TestRequest;
export {};
