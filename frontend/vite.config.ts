import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["app-mark.svg"],
      manifest: {
        name: "StoRpt A 股历史价格回填",
        short_name: "StoRpt",
        description: "A 股历史价格 Excel 回填工具",
        theme_color: "#1f5c45",
        background_color: "#f4f6f5",
        display: "standalone",
        start_url: "/",
        scope: "/",
        icons: [
          {
            src: "/app-mark.svg",
            sizes: "any",
            type: "image/svg+xml",
            purpose: "any maskable"
          }
        ]
      },
      workbox: {
        navigateFallbackDenylist: [/^\/api\//],
        runtimeCaching: []
      }
    })
  ],
  server: {
    proxy: {
      "/api": "http://127.0.0.1:8000",
      "/healthz": "http://127.0.0.1:8000"
    }
  }
});
