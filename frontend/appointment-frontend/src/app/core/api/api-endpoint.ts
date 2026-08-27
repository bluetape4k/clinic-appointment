import { Capacitor } from '@capacitor/core';

import { environment } from '../../../environments/environment';

export interface ApiRuntimeConfig {
  apiOrigin?: string;
}

export interface ApiEndpointContext {
  production?: boolean;
  native?: boolean;
  runtimeConfig?: ApiRuntimeConfig | null;
}

declare global {
  var __CLINIC_API_CONFIG__: ApiRuntimeConfig | undefined;
}

/** API origin을 build/runtime 설정에서 읽어 browser와 native가 공유합니다. */
export function resolveApiOrigin(context: ApiEndpointContext = {}): string {
  const production = context.production ?? environment.production;
  const native = context.native ?? Capacitor.isNativePlatform();
  const runtimeOrigin =
    context.runtimeConfig?.apiOrigin ?? globalThis.__CLINIC_API_CONFIG__?.apiOrigin;
  const configuredOrigin = runtimeOrigin ?? environment.apiOrigin;

  return normalizeApiOrigin(configuredOrigin, { production, native });
}

/** 호출자가 보낸 내부 path에 tenant segment와 API base path를 붙입니다. */
export function resolveTenantApiUrl(
  tenantCode: string,
  path: string,
  context: ApiEndpointContext = {},
): string {
  if (!tenantCode) throw new Error('tenant scope가 설정되지 않았습니다.');
  const normalizedPath = normalizeApiPath(path);
  return `${resolveApiOrigin(context)}${environment.apiBasePath}/${encodeURIComponent(tenantCode)}${normalizedPath}`;
}

export function normalizeApiOrigin(
  origin: string,
  context: Pick<ApiEndpointContext, 'production' | 'native'> = {},
): string {
  if (typeof origin !== 'string') {
    throw new Error('API origin은 문자열이어야 합니다.');
  }
  const value = origin.trim();
  const production = context.production ?? environment.production;
  const native = context.native ?? Capacitor.isNativePlatform();

  if (!value) {
    if (native) {
      throw new Error('native WebView는 명시적인 HTTPS API origin이 필요합니다.');
    }
    return '';
  }
  if (value === '*') throw new Error('API origin은 wildcard일 수 없습니다.');

  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch (error) {
    throw new Error('API origin은 유효한 absolute origin이어야 합니다.', { cause: error });
  }

  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error('API origin은 HTTP 또는 HTTPS scheme이어야 합니다.');
  }
  if (parsed.username || parsed.password) {
    throw new Error('API origin에 credentials를 포함할 수 없습니다.');
  }
  if (parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('API origin에는 path, query, fragment를 포함할 수 없습니다.');
  }
  if ((production || native) && parsed.protocol !== 'https:') {
    throw new Error('production/native API origin은 HTTPS여야 합니다.');
  }

  return parsed.origin;
}

function normalizeApiPath(path: string): string {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('://')) {
    throw new Error('API path는 내부 절대 경로여야 합니다.');
  }
  return path;
}
