import {
  HttpClient,
  HttpContext,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../../environments/environment';
import { API_AUTH_SCOPE, ApiAuthScope } from './api-auth-context';
import { TenantContextService } from './tenant-context.service';

export interface TenantApiRequestOptions {
  body?: unknown;
  headers?: HttpHeaders | Record<string, string | string[]>;
  params?: HttpParams | Record<string, string | number | boolean | readonly (string | number | boolean)[]>;
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
    const normalizedPath = normalizeApiPath(path);
    const tenantCode = this.tenant.requireTenant();
    return `${environment.apiUrl}/${encodeURIComponent(tenantCode)}${normalizedPath}`;
  }

  /** response envelope/domain 변환 전의 HTTP response를 그대로 반환합니다. */
  async request<T>(
    method: string,
    path: string,
    options: TenantApiRequestOptions = {},
  ): Promise<HttpResponse<T>> {
    const context = new HttpContext().set(API_AUTH_SCOPE, options.authScope ?? 'none');
    return firstValueFrom(
      this.http.request<T>(method, this.url(path), {
        body: options.body,
        headers: options.headers,
        params: options.params,
        context,
        observe: 'response',
        withCredentials: options.withCredentials ?? false,
      }),
    );
  }
}

function normalizeApiPath(path: string): string {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('://')) {
    throw new Error('API path는 내부 절대 경로여야 합니다.');
  }
  return path;
}
