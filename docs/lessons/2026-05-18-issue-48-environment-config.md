# Issue #48 — Angular environment.ts API baseUrl injection

## Root Cause

All Angular services had `/api/...` baseUrl hardcoded as string literals,
making it impossible to change the API root URL at build time for different
deployment targets.

## Decision

Introduce Angular's standard environment file pattern:
- `src/environments/environment.ts` — development (production: false)
- `src/environments/environment.prod.ts` — production (production: true)
- `angular.json` fileReplacements to swap files at `--configuration=production`
- All service `baseUrl` fields replaced with `${environment.apiUrl}/...`

Both files intentionally use `apiUrl: '/api'` (relative path) so that:
- Dev uses the Angular proxy (`proxy.conf.json`) to forward `/api` → backend
- Prod uses Nginx/reverse proxy to forward `/api` → backend
- Future deployments can change only `environment.prod.ts` to use an absolute URL

## Outcome

- `ng build --configuration=development` ✅
- `ng build --configuration=production` ✅ (fileReplacements applied)
- 8 pre-existing spec failures unrelated to this change (response-shape mismatch
  in `HttpTestingController` specs — tracked separately)

## Future Guidance

- When adding new Angular services, always use `environment.apiUrl` as the base,
  never hardcode `/api`.
- If spec files still use hardcoded URL strings in `httpTesting.expectOne()`,
  update them to use `environment.apiUrl` to avoid silent drift when the URL changes.
