import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    manifest: true,
  },
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      // OIDC redirect URIs must retain the browser-facing host and port.
      '/api/internal/v1/auth/oidc/': {
        target: process.env.API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: false,
      },
      // Backend resources are served under /api/..., so the path is forwarded unchanged.
      '/api/': {
        target: process.env.API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
