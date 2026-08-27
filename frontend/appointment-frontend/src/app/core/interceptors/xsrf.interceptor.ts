import { HttpInterceptorFn, HttpXsrfTokenExtractor } from '@angular/common/http';
import { inject } from '@angular/core';

import { API_AUTH_SCOPE } from '../api/api-auth-context';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
const XSRF_HEADER = 'X-XSRF-TOKEN';

/** cross-origin patient mutation에서도 Angular XSRF cookie/header 계약을 유지합니다. */
export const xsrfInterceptor: HttpInterceptorFn = (req, next) => {
  if (
    SAFE_METHODS.has(req.method) ||
    req.context.get(API_AUTH_SCOPE) !== 'patient-cookie' ||
    !req.withCredentials ||
    !isCrossOrigin(req.url)
  ) {
    return next(req);
  }
  if (req.headers.has(XSRF_HEADER)) return next(req);

  const token = inject(HttpXsrfTokenExtractor).getToken();
  return token ? next(req.clone({ setHeaders: { [XSRF_HEADER]: token } })) : next(req);
};

function isCrossOrigin(url: string): boolean {
  const pageLocation = globalThis.location;
  if (!pageLocation?.origin) return false;
  try {
    return new URL(url, pageLocation.href).origin !== pageLocation.origin;
  } catch {
    return false;
  }
}
