import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/auth": "http://localhost:8888",
      "/cases": "http://localhost:8888",
      "/compliance": "http://localhost:8888",
      "/ops": "http://localhost:8888",
      "/admin": "http://localhost:8888",
      "/config": "http://localhost:8888",
      // 只代理 API 子路径；勿代理 /dashboard 页面路由（否则会返回 JSON Login required）
      "/dashboard/outreach": "http://localhost:8888",
      "/dashboard/recovery": "http://localhost:8888",
      "/dashboard/evaluation": "http://localhost:8888",
      "/catalog": "http://localhost:8888",
      "/plans": "http://localhost:8888",
      "/mock": "http://localhost:8888"
    }
  }
});
