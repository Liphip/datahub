import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // TODO: Split the production bundle by route or feature
  // Details: `npm run build` produces a main JavaScript chunk above Vite's 500 kB warning threshold. Lazy-load editor/monitoring features or configure intentional vendor chunks to improve initial load time.
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      // Backend resources are served under /api/..., so the path is forwarded unchanged.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
