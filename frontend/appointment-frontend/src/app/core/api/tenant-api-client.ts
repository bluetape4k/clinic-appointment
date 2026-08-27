import {
  HttpClient,
  HttpContext,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API_AUTH_SCOPE, ApiAuthScope } from './api-auth-context';
import { resolveTenantApiUrl } from './api-endpoint';
import { TenantContextService } from './tenant-context.service';

export interface TenantApiRequestOptions {
  body?: unknown;
  headers?: HttpHeaders | Record<string, string | string[]>;
  params?:
    | HttpParams
    | Record<string, string | number | boolean | readonly (string | number | boolean)[]>;
  authScope?: ApiAuthScope;
  withCredentials?: boolean;
}

/** 모든 frontend API가 공유하는 tenant path transport입니다. */
@Injectable({ providedIn: 'root' })
export class TenantApiClient {
  private readonly http = inject(HttpClient);
  private readonly tenant = inject(TenantContextService);

  /** 현재 tenant를 URL에 포함한 backend path를 반환합니다. */
  url(path: string): string {
    const tenantCode = this.tenant.requireTenant();
    return resolveTenantApiUrl(tenantCode, path);
  }

  /** response envelope/domain 변환 전의 HTTP response를 그대로 반환합니다. */
  async request<T>(
    method: string,
    path: string,
    options: TenantApiRequestOptions = {},
  ): Promise<HttpResponse<T>> {
    const authScope = options.authScope ?? 'none';
    if (authScope === 'patient-cookie' && options.withCredentials === false) {
      throw new Error('patient-cookie 요청은 withCredentials=true여야 합니다.');
    }
    if (authScope === 'workforce-bearer' && options.withCredentials === true) {
      throw new Error('workforce-bearer 요청은 withCredentials=false여야 합니다.');
    }
    const context = new HttpContext().set(API_AUTH_SCOPE, authScope);
    return firstValueFrom(
      this.http.request<T>(method, this.url(path), {
        body: options.body,
        headers: options.headers,
        params: options.params,
        context,
        observe: 'response',
        withCredentials: options.withCredentials ?? authScope === 'patient-cookie',
      }),
    );
  }
}
