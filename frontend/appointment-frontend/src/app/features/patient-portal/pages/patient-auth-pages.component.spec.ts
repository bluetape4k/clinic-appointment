import { TestBed } from '@angular/core/testing';
import { provideLocationMocks } from '@angular/common/testing';
import { provideRouter } from '@angular/router';
import { Router } from '@angular/router';

import { TenantContextService } from '../../../core/api/tenant-context.service';
import { PatientAuthService } from '../../../core/services/patient-auth.service';
import { PatientLoginPageComponent } from './patient-login-page.component';
import { PatientRegisterPageComponent } from './patient-register-page.component';

describe('환자 인증 페이지', () => {
  const auth = {
    login: vi.fn(),
    register: vi.fn(),
  };
  const tenant = {
    tenantCode: vi.fn().mockReturnValue(null),
    setTenant: vi.fn(),
  };
  let router: Router;

  beforeEach(async () => {
    vi.clearAllMocks();
    auth.login.mockResolvedValue({
      tenantCode: 'tenant-a',
      role: 'PATIENT',
      displayName: '홍길동',
      expiresAt: '2099-01-01T00:00:00Z',
    });
    auth.register.mockResolvedValue({ registered: true });
    await TestBed.configureTestingModule({
      imports: [PatientLoginPageComponent, PatientRegisterPageComponent],
      providers: [
        provideRouter([]),
        provideLocationMocks(),
        { provide: PatientAuthService, useValue: auth },
        { provide: TenantContextService, useValue: tenant },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  it('login은 선택한 key=value 식별자를 backend 계약 그대로 전달한다', async () => {
    const fixture = TestBed.createComponent(PatientLoginPageComponent);
    const page = fixture.componentInstance;
    page.tenantCode.set('tenant-a');
    page.identifierKey.set('EMAIL');
    page.identifierValue.set('patient@example.com');
    page.password.set('correct horse battery staple');

    await page.submit();

    expect(auth.login).toHaveBeenCalledWith('tenant-a', {
      identifier: { key: 'EMAIL', value: 'patient@example.com' },
      password: 'correct horse battery staple',
    });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/portal/appointments');
  });

  it('저장된 tenant scope가 없으면 login form에 기본 tenant를 주입하지 않는다', () => {
    const fixture = TestBed.createComponent(PatientLoginPageComponent);

    expect(fixture.componentInstance.tenantCode()).toBe('');
  });

  it('저장된 tenant scope가 없으면 register form에도 기본 tenant를 주입하지 않는다', () => {
    const fixture = TestBed.createComponent(PatientRegisterPageComponent);

    expect(fixture.componentInstance.tenantCode()).toBe('');
  });

  it('register는 최대 세 가지 식별자를 중복 없이 추가하고 전달한다', async () => {
    const fixture = TestBed.createComponent(PatientRegisterPageComponent);
    const page = fixture.componentInstance;
    page.tenantCode.set('tenant-a');
    page.displayName.set('홍길동');
    page.password.set('correct horse battery staple');
    page.identifiers.set([
      { key: 'PHONE', value: '010-1234-5678' },
      { key: 'EMAIL', value: 'patient@example.com' },
    ]);

    page.addIdentifier();
    expect(page.identifiers()).toEqual([
      { key: 'PHONE', value: '010-1234-5678' },
      { key: 'EMAIL', value: 'patient@example.com' },
      { key: 'LOGIN_ID', value: '' },
    ]);
    page.updateIdentifier(2, { value: 'hong' });
    await page.submit();

    expect(auth.register).toHaveBeenCalledWith('tenant-a', {
      displayName: '홍길동',
      password: 'correct horse battery staple',
      identifiers: [
        { key: 'PHONE', value: '010-1234-5678' },
        { key: 'EMAIL', value: 'patient@example.com' },
        { key: 'LOGIN_ID', value: 'hong' },
      ],
    });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/portal/login');
  });
});
