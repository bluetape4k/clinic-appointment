import { Injectable, signal } from '@angular/core';

export type SessionScope = 'patient' | 'workforce';
export type SessionStatus = 'anonymous' | 'authenticated' | 'unauthorized' | 'forbidden' | 'tenant-missing';

export interface SessionState {
  patient: SessionStatus;
  workforce: SessionStatus;
}

/** 환자와 workforce 화면이 공유하는 인증·tenant 상태 저장소입니다. */
@Injectable({ providedIn: 'root' })
export class SessionStateService {
  private readonly _state = signal<SessionState>({
    patient: 'anonymous',
    workforce: 'anonymous',
  });

  readonly state = this._state.asReadonly();

  status(scope: SessionScope): SessionStatus {
    return this._state()[scope];
  }

  mark(scope: SessionScope, status: SessionStatus): void {
    this._state.update(current => ({ ...current, [scope]: status }));
  }
}
