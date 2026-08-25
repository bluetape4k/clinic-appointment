import { TestBed } from '@angular/core/testing';
import { HttpContext, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { throwError } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { errorInterceptor } from './error.interceptor';
import { AuthService } from '../services/auth.service';
import { SessionStateService } from '../services/session-state.service';
import { API_AUTH_SCOPE } from '../api/api-auth-context';

describe('errorInterceptor', () => {
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let authService: { removeToken: ReturnType<typeof vi.fn>; markForbidden: ReturnType<typeof vi.fn> };
  let sessionState: SessionStateService;

  const runInterceptor = (next: HttpHandlerFn) => {
    const req = new HttpRequest('GET', '/api/tenant-a/appointments', {
      context: new HttpContext().set(API_AUTH_SCOPE, 'workforce-bearer'),
    });
    let error: unknown;
    TestBed.runInInjectionContext(() =>
      errorInterceptor(req, next).subscribe({ error: (e) => (error = e) })
    );
    return error;
  };

  beforeEach(() => {
    snackBar = { open: vi.fn() };
    authService = { removeToken: vi.fn(), markForbidden: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: MatSnackBar, useValue: snackBar },
        { provide: AuthService, useValue: authService },
        SessionStateService,
      ],
    });
    sessionState = TestBed.inject(SessionStateService);
  });

  it('401 응답에서 현재 세션을 제거한다', () => {
    const next: HttpHandlerFn = () =>
      throwError(() => new HttpErrorResponse({ status: 401 }));

    runInterceptor(next);

    expect(authService.removeToken).toHaveBeenCalledTimes(1);
    expect(sessionState.status('workforce')).toBe('unauthorized');
  });

  it('patient cookie 401은 workforce token을 제거하지 않고 patient 상태만 갱신한다', () => {
    const req = new HttpRequest('GET', '/api/tenant-a/auth/session', {
      context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
    });
    const next: HttpHandlerFn = () => throwError(() => new HttpErrorResponse({ status: 401 }));
    let error: unknown;
    TestBed.runInInjectionContext(() =>
      errorInterceptor(req, next).subscribe({ error: (e) => (error = e) }),
    );

    expect(error).toBeTruthy();
    expect(authService.removeToken).not.toHaveBeenCalled();
    expect(sessionState.status('patient')).toBe('unauthorized');
  });

  it('patient cookie 403은 workforce token을 제거하지 않고 patient forbidden을 기록한다', () => {
    const req = new HttpRequest('GET', '/api/tenant-a/auth/session', {
      context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
    });
    const next: HttpHandlerFn = () => throwError(() => new HttpErrorResponse({ status: 403 }));
    TestBed.runInInjectionContext(() =>
      errorInterceptor(req, next).subscribe({ error: () => undefined }),
    );

    expect(authService.removeToken).not.toHaveBeenCalled();
    expect(sessionState.status('patient')).toBe('forbidden');
  });

  it('workforce 403은 forbidden 상태를 기록하고 token을 유지한다', () => {
    const next: HttpHandlerFn = () =>
      throwError(() => new HttpErrorResponse({ status: 403 }));

    runInterceptor(next);

    expect(authService.removeToken).not.toHaveBeenCalled();
    expect(authService.markForbidden).toHaveBeenCalledOnce();
    expect(sessionState.status('workforce')).toBe('forbidden');
  });

  it('400 상태코드에서 스낵바에 "잘못된 요청" 표시', () => {
    const next: HttpHandlerFn = () =>
      throwError(() => new HttpErrorResponse({ status: 400 }));
    runInterceptor(next);
    expect(snackBar.open).toHaveBeenCalledWith('잘못된 요청', '닫기', { duration: 3000 });
  });

  it('404 상태코드에서 스낵바에 "찾을 수 없음" 표시', () => {
    const next: HttpHandlerFn = () =>
      throwError(() => new HttpErrorResponse({ status: 404 }));
    runInterceptor(next);
    expect(snackBar.open).toHaveBeenCalledWith('찾을 수 없음', '닫기', { duration: 3000 });
  });

  it('500 상태코드에서 스낵바에 "서버 오류" 표시', () => {
    const next: HttpHandlerFn = () =>
      throwError(() => new HttpErrorResponse({ status: 500 }));
    runInterceptor(next);
    expect(snackBar.open).toHaveBeenCalledWith('서버 오류', '닫기', { duration: 3000 });
  });
});
