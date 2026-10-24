export {};
declare global {
  interface WindowEventMap {
    "toonflow:plugin-installed": CustomEvent<{ type: string; name: string }>;
  }
}
