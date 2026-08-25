import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const roleGuard: CanActivateFn = (route) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (!authService.isAuthenticated()) {
    authService.markUnauthorized();
    return router.createUrlTree(['/calendar']);
  }

  const requiredRoles: string[] = route.data['requiredRoles'] ?? [];

  if (requiredRoles.length === 0) {
    return true;
  }

  const hasRole = requiredRoles.some((role) => authService.roles().includes(role));

  if (hasRole) return true;
  authService.markForbidden();
  return router.createUrlTree(['/calendar']);
};
