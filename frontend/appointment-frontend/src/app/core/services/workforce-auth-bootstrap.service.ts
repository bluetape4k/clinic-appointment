import { Injectable, inject } from '@angular/core';

import { AuthService } from './auth.service';

const WORKFORCE_AUTH_HANDOFF_KEY = '__CLINIC_WORKFORCE_AUTH__';

/** Gateway가 Angular 부팅 전에 전달하는 비영속 workforce 인증 handoff입니다. */
export interface WorkforceAuthHandoff {
  readonly token: string;
  readonly tenantCode?: string;
}

declare global {
  /** 호스트가 bootstrapApplication 전에 한 번 설정하는 인증 handoff입니다. */
  var __CLINIC_WORKFORCE_AUTH__: WorkforceAuthHandoff | undefined;
}

/**
 * 호스트가 주입한 workforce 인증을 앱 셸 초기화 경계에서 복원합니다.
 *
 * handoff는 소비 즉시 전역에서 제거하고, JWT 자체는 AuthService 메모리에만 둡니다.
 */
@Injectable({ providedIn: 'root' })
export class WorkforceAuthBootstrapService {
  private readonly auth = inject(AuthService);

  restore(): void {
    const handoff = globalThis.__CLINIC_WORKFORCE_AUTH__;
    if (handoff === undefined) return;

    Reflect.deleteProperty(globalThis, WORKFORCE_AUTH_HANDOFF_KEY);

    if (!isWorkforceAuthHandoff(handoff)) {
      this.auth.markUnauthorized();
      return;
    }

    try {
      this.auth.bootstrap(handoff.token, handoff.tenantCode);
    } catch {
      // 잘못된 호스트 인증은 앱 전체 부팅 실패 대신 로그인되지 않은 상태로 남긴다.
      this.auth.markUnauthorized();
    }
  }
}

function isWorkforceAuthHandoff(value: unknown): value is WorkforceAuthHandoff {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;

  const candidate = value as { token?: unknown; tenantCode?: unknown };
  return (
    typeof candidate.token === 'string' &&
    candidate.token.trim().length > 0 &&
    (candidate.tenantCode === undefined || typeof candidate.tenantCode === 'string')
  );
}
