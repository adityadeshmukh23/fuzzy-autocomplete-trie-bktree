import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Proxying /api to the Spring app makes development same-origin. The backend also has CORS
    // configured for this origin, so direct calls would work too -- but the proxy mirrors the
    // deployment where both are served from one host, and it keeps the frontend code free of
    // environment-specific URLs.
    //
    // Caveat worth knowing: because dev is same-origin, it does NOT exercise the CORS config.
    // A split-origin deployment must be verified separately (see the Phase 8 notes).
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
