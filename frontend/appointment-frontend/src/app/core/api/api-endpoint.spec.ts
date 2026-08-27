import { describe, expect, it } from 'vitest';

import { normalizeApiOrigin, resolveApiOrigin, resolveTenantApiUrl } from './api-endpoint';

describe('API endpoint contract', () => {
  it('browser same-origin은 빈 origin을 허용한다', () => {
    expect(normalizeApiOrigin('', { production: true, native: false })).toBe('');
  });

  it('native WebView는 명시적인 HTTPS origin을 요구한다', () => {
    expect(() => normalizeApiOrigin('', { production: false, native: true })).toThrow(
      'native WebView',
    );
    expect(() =>
      normalizeApiOrigin('http://api.example.test', { production: false, native: true }),
    ).toThrow('HTTPS');
    expect(
      normalizeApiOrigin('https://api.example.test/', { production: false, native: true }),
    ).toBe('https://api.example.test');
  });

  it('production의 cross-origin HTTP와 origin 외 구성요소를 거부한다', () => {
    expect(() =>
      normalizeApiOrigin('http://api.example.test', { production: true, native: false }),
    ).toThrow('HTTPS');
    expect(() =>
      normalizeApiOrigin('https://api.example.test/api', { production: true, native: false }),
    ).toThrow('origin');
    expect(() =>
      normalizeApiOrigin('https://user:secret@api.example.test', {
        production: true,
        native: false,
      }),
    ).toThrow('credentials');
    expect(() => normalizeApiOrigin('*', { production: false, native: false })).toThrow('origin');
  });

  it('runtime override가 environment origin보다 우선하고 tenant path를 재사용한다', () => {
    expect(
      resolveApiOrigin({
        production: false,
        native: false,
        runtimeConfig: { apiOrigin: 'https://runtime.example.test/' },
      }),
    ).toBe('https://runtime.example.test');

    expect(
      resolveTenantApiUrl('tenant a', '/appointments', {
        production: false,
        native: false,
        runtimeConfig: { apiOrigin: 'https://runtime.example.test' },
      }),
    ).toBe('https://runtime.example.test/api/tenant%20a/appointments');
  });
});
