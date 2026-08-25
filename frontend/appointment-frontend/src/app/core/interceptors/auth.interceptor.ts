import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { API_AUTH_SCOPE } from '../api/api-auth-context';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const token = authService.getToken();

  if (token && req.context.get(API_AUTH_SCOPE) === 'workforce-bearer') {
    const clonedReq = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` }
    });
    return next(clonedReq);
  }
  return next(req);
};
