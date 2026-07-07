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
      "/mock": "http://localhost:8888"
    }
  }
});
