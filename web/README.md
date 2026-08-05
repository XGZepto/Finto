# Finto web application

Angular frontend for the Finto API. The production build is served by Vercel;
the development server proxies `/api` requests to FastAPI on port 8000.

## Commands

```bash
npm ci
npm start
npm run build
npm test
```

Use Node.js 22. Production output is written to `dist/web/browser`.

## Structure

```text
src/app/core/        API client, data models, preferences
src/app/features/    route-level screens
src/app/shared/      shared controls and filters
public/              manifest, service worker, icons
```

The application uses cookie sessions for the browser. API keys are created and
revoked from Settings and are intended for maintenance clients, not browser
storage.
