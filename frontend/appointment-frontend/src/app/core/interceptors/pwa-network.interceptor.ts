import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { throwError } from 'rxjs';
import { API_AUTH_SCOPE } from '../api/api-auth-context';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/** 인증 응답과 예약 mutation을 Angular Service Worker 캐시 경계 밖으로 유지합니다. */
export const pwaNetworkInterceptor: HttpInterceptorFn = (req, next) => {
  const isMutation = !SAFE_METHODS.has(req.method);
  if (isMutation && globalThis.navigator?.onLine === false) {
    return throwError(
      () =>
        new HttpErrorResponse({
          error: { code: 'OFFLINE_MUTATION' },
          status: 0,
          statusText: 'OFFLINE_MUTATION',
          url: req.url,
        }),
    );
  }

  let networkRequest = req;
  const hasAuthenticationBoundary =
    req.context.get(API_AUTH_SCOPE) !== 'none' || req.withCredentials;
  if (hasAuthenticationBoundary) {
    networkRequest = networkRequest.clone({ setHeaders: { 'ngsw-bypass': 'true' } });
  }
  if (isMutation) {
    networkRequest = networkRequest.clone({
      setHeaders: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
    });
  }

  return next(networkRequest);
};
