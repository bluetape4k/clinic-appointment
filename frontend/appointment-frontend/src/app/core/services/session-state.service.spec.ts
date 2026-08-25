import { describe, expect, it, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';

import { SessionStateService } from './session-state.service';

describe('SessionStateService', () => {
  let service: SessionStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(SessionStateService);
  });

  it('scope별 인증 상태를 독립적으로 기록한다', () => {
    service.mark('workforce', 'forbidden');
    service.mark('patient', 'authenticated');

    expect(service.status('workforce')).toBe('forbidden');
    expect(service.status('patient')).toBe('authenticated');
  });

  it('초기 상태는 두 scope 모두 anonymous다', () => {
    expect(service.status('workforce')).toBe('anonymous');
    expect(service.status('patient')).toBe('anonymous');
  });
});
