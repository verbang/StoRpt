/// <reference types="vite/client" />

declare module "virtual:pwa-register" {
  interface RegisterSWOptions {
    immediate?: boolean;
  }

  export function registerSW(options?: RegisterSWOptions): (
    reloadPage?: boolean
  ) => Promise<void>;
}

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>;
  export default component;
}
