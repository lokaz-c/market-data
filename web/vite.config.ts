import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// In development the API runs on :8080 (./mvnw spring-boot:test-run or docker compose up);
// the proxy keeps requests same-origin, as in production where Spring Boot serves the built files.
const api = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/v1': api,
      '/actuator': api,
      '/docs': api,
      '/swagger-ui': api,
      '/v3': api,
    },
  },
  test: {
    environment: 'node',
  },
});
