import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  // TODO: Split the production bundle by route or feature
  // Details: `npm run build` produces an 832 kB minified main JavaScript chunk (243 kB gzip), exceeding Vite's 500 kB warning threshold. Lazy-load editor/monitoring features or configure intentional vendor chunks to improve initial load time.
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // BUG: The development proxy removes the API prefix
        // Details: The frontend requests /api/internal/v1 and /api/v1, and the backend resources use those same paths. Rewriting /api to an empty prefix forwards /internal/v1 or /v1 and causes local-development 404 responses; preserve the path or make the backend target include /api.
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
