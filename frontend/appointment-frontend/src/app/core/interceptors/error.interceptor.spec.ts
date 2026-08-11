import { TestBed } from '@angular/core/testing';
import { HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { throwError } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { errorInterceptor } from './error.interceptor';
import { AuthService } from '../services/auth.service';

describe('errorInterceptor', () => {
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let authService: { removeToken: ReturnType<typeof vi.fn> };

  const runInterceptor = (next: HttpHandlerFn) => {
    const req = new HttpRequest('GET', '/api/test');
    let error: unknown;
    TestBed.runInInjectionContext(() =>
      errorInterceptor(req, next).subscribe({ error: (e) => (error = e) })
    );
    return error;
  };

  beforeEach(() => {
    snackBar = { open: vi.fn() };
    authService = { removeToken: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: MatSnackBar, useValue: snackBar },
        { provide: AuthService, useValue: authService },
      ],
    });
  });

  it('401 응답에서 현재 세션을 제거한다', () => {
    const next: HttpHandlerFn = () =>
      throwError(() => new HttpErrorResponse({ status: 401 }));

    runInterceptor(next);

    expect(authService.removeToken).toHaveBeenCalledTimes(1);
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
