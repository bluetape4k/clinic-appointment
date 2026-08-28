import { signal, type WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import type { PluginListenerHandle } from '@capacitor/core';
import { vi } from 'vitest';
import { AuthService } from './auth.service';
import { TenantContextService } from '../api/tenant-context.service';
import {
  NATIVE_APP_PLUGIN,
  NATIVE_PLATFORM,
  NATIVE_WEBVIEW_EVENT_NAME,
  NativeWebViewBridgeService,
} from './native-webview-bridge.service';

describe('NativeWebViewBridgeService', () => {
  let service: NativeWebViewBridgeService;
  let callback: ((event: { url: string }) => void) | undefined;
  let handle: PluginListenerHandle;
  let plugin: {
    addListener: ReturnType<typeof vi.fn>;
    getLaunchUrl: ReturnType<typeof vi.fn>;
  };
  let platform: { isNativePlatform: ReturnType<typeof vi.fn> };
  let router: { navigate: ReturnType<typeof vi.fn> };
  let auth: {
    isAuthenticated: WritableSignal<boolean>;
    allowedTenants: WritableSignal<string[]>;
    markUnauthorized: ReturnType<typeof vi.fn>;
  };
  let tenant: {
    tenantCode: WritableSignal<string | null>;
    setTenant: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    callback = undefined;
    handle = { remove: vi.fn().mockResolvedValue(undefined) };
    plugin = {
      addListener: vi.fn(async (_eventName: string, listener: (event: { url: string }) => void) => {
        callback = listener;
        return handle;
      }),
      getLaunchUrl: vi.fn().mockResolvedValue(undefined),
    };
    platform = { isNativePlatform: vi.fn().mockReturnValue(false) };
    router = { navigate: vi.fn().mockResolvedValue(true) };
    auth = {
      isAuthenticated: signal(true),
      allowedTenants: signal(['clinic-a']),
      markUnauthorized: vi.fn(),
    };
    tenant = {
      tenantCode: signal(null),
      setTenant: vi.fn((value: string) => tenant.tenantCode.set(value)),
    };

    TestBed.configureTestingModule({
      providers: [
        NativeWebViewBridgeService,
        { provide: NATIVE_APP_PLUGIN, useValue: plugin },
        { provide: NATIVE_PLATFORM, useValue: platform },
        { provide: Router, useValue: router },
        { provide: AuthService, useValue: auth },
        { provide: TenantContextService, useValue: tenant },
      ],
    });
    service = TestBed.inject(NativeWebViewBridgeService);
  });

  it('browser에서는 plugin listener를 등록하지 않는 no-op으로 시작한다', async () => {
    await service.start();

    expect(service.status()).toBe('browser-noop');
    expect(plugin.addListener).not.toHaveBeenCalled();
    expect(plugin.getLaunchUrl).not.toHaveBeenCalled();
  });

  it('native launch URL과 appUrlOpen이 같은 auth·tenant·router·event 경로를 사용한다', async () => {
    platform.isNativePlatform.mockReturnValue(true);
    plugin.getLaunchUrl.mockResolvedValue({
      url: 'io.bluetape4k.clinic.appointment://open/clinic-a/calendar?view=week&date=2026-08-27',
    });
    const events: unknown[] = [];
    service.events.subscribe((event) => events.push(event));
    const customEvents: Event[] = [];
    window.addEventListener(NATIVE_WEBVIEW_EVENT_NAME, (event) => customEvents.push(event));

    await service.start();
    await callback?.({
      url: 'io.bluetape4k.clinic.appointment://open/clinic-a/appointments?id=42',
    });

    expect(service.status()).toBe('native-ready');
    expect(plugin.addListener).toHaveBeenCalledOnce();
    expect(tenant.setTenant).toHaveBeenCalledWith('clinic-a');
    expect(router.navigate).toHaveBeenNthCalledWith(1, ['/calendar', 'week', '2026-08-27']);
    expect(router.navigate).toHaveBeenNthCalledWith(2, ['/appointments', '42']);
    expect(events).toHaveLength(2);
    expect(events[0]).toEqual({
      name: NATIVE_WEBVIEW_EVENT_NAME,
      version: 1,
      tenantCode: 'clinic-a',
      route: 'calendar',
      query: { view: 'week', date: '2026-08-27' },
    });
    expect(customEvents).toHaveLength(2);
    expect((customEvents[0] as CustomEvent).detail).toEqual(events[0]);
  });

  it.each([
    'https://open/clinic-a/calendar',
    'io.bluetape4k.clinic.appointment://open/clinic-b/calendar',
    'io.bluetape4k.clinic.appointment://open/clinic-a/portal',
  ])('invalid 또는 unauthorized URL은 tenant/router를 변경하지 않는다: %s', async (url) => {
    if (url.includes('clinic-b')) auth.allowedTenants.set(['clinic-a']);
    const result = await service.handleUrl(url);

    expect(result.accepted).toBe(false);
    expect(tenant.setTenant).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('인증되지 않은 URL은 unauthorized 상태로 거부한다', async () => {
    auth.isAuthenticated.set(false);

    const result = await service.handleUrl(
      'io.bluetape4k.clinic.appointment://open/clinic-a/calendar',
    );

    expect(result).toEqual({ accepted: false, reason: 'unauthorized' });
    expect(auth.markUnauthorized).toHaveBeenCalledOnce();
    expect(tenant.setTenant).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('navigation 실패 시 event를 발행하지 않는다', async () => {
    router.navigate.mockResolvedValue(false);
    const events: unknown[] = [];
    service.events.subscribe((event) => events.push(event));

    const result = await service.handleUrl(
      'io.bluetape4k.clinic.appointment://open/clinic-a/calendar',
    );

    expect(result).toEqual({ accepted: false, reason: 'navigation-failed' });
    expect(events).toHaveLength(0);
  });

  it('start는 idempotent하고 stop/destroy는 listener를 한 번만 제거한다', async () => {
    platform.isNativePlatform.mockReturnValue(true);
    const first = service.start();
    const second = service.start();
    await Promise.all([first, second]);

    expect(plugin.addListener).toHaveBeenCalledOnce();
    await service.stop();
    await service.stop();
    service.ngOnDestroy();
    await Promise.resolve();

    expect(handle.remove).toHaveBeenCalledOnce();
  });

  it('event payload에는 token·raw URL·storage key가 없다', async () => {
    const result = await service.handleUrl(
      'io.bluetape4k.clinic.appointment://open/clinic-a/appointments?id=42',
    );

    expect(result.accepted).toBe(true);
    if (result.accepted) {
      expect(result.event).toEqual({
        name: NATIVE_WEBVIEW_EVENT_NAME,
        version: 1,
        tenantCode: 'clinic-a',
        route: 'appointments',
        query: { id: '42' },
      });
      expect(JSON.stringify(result.event)).not.toContain('secret');
      expect(JSON.stringify(result.event)).not.toContain('auth_token');
    }
  });
});
