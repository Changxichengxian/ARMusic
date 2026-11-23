import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  clearScreen: false,
  publicDir: mode === "contest-preview" ? "contest-public" : undefined,
  build: {
    emptyOutDir: true,
  },
  server: {
    strictPort: true,
  },
}));
