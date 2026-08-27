import { TestBed } from '@angular/core/testing';
import { SwUpdate } from '@angular/service-worker';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PwaStatusService } from './pwa-status.service';

describe('PwaStatusService', () => {
  let versionUpdates: Subject<unknown>;
  let swUpdate: {
    isEnabled: boolean;
    versionUpdates: Subject<unknown>;
    activateUpdate: ReturnType<typeof vi.fn>;
  };
  let onlineDescriptor: PropertyDescriptor | undefined;

  beforeEach(() => {
    onlineDescriptor = Object.getOwnPropertyDescriptor(window.navigator, 'onLine');
    Object.defineProperty(window.navigator, 'onLine', {
      configurable: true,
      value: true,
    });
    versionUpdates = new Subject<unknown>();
    swUpdate = {
      isEnabled: true,
      versionUpdates,
      activateUpdate: vi.fn().mockResolvedValue(true),
    };
    TestBed.configureTestingModule({
      providers: [{ provide: SwUpdate, useValue: swUpdate }],
    });
  });

  afterEach(() => {
    versionUpdates.complete();
    if (onlineDescriptor) {
      Object.defineProperty(window.navigator, 'onLine', onlineDescriptor);
    }
    vi.unstubAllGlobals();
    TestBed.resetTestingModule();
  });

  it('online/offline 이벤트를 signal에 반영한다', () => {
    const service = TestBed.inject(PwaStatusService);

    expect(service.isOnline()).toBe(true);

    Object.defineProperty(window.navigator, 'onLine', { configurable: true, value: false });
    window.dispatchEvent(new Event('offline'));
    expect(service.isOnline()).toBe(false);

    Object.defineProperty(window.navigator, 'onLine', { configurable: true, value: true });
    window.dispatchEvent(new Event('online'));
    expect(service.isOnline()).toBe(true);
  });

  it('새 버전 이벤트를 사용자 update 상태로 노출한다', () => {
    const service = TestBed.inject(PwaStatusService);

    expect(service.updateAvailable()).toBe(false);
    versionUpdates.next({ type: 'VERSION_READY' });

    expect(service.updateAvailable()).toBe(true);
  });

  it('활성화 가능한 업데이트를 적용하고 상태를 초기화한다', async () => {
    const service = TestBed.inject(PwaStatusService);
    versionUpdates.next({ type: 'VERSION_READY' });

    await service.applyUpdate();

    expect(swUpdate.activateUpdate).toHaveBeenCalledOnce();
    expect(service.updateAvailable()).toBe(false);
    expect(service.notice()).toContain('새 버전');
  });

  it('ngsw cache만 삭제하고 다른 cache는 보존한다', async () => {
    const cacheKeys = vi.fn().mockResolvedValue(['ngsw:db:app', 'api-cache', 'ngsw:db:stale']);
    const deleteCache = vi.fn().mockResolvedValue(true);
    vi.stubGlobal('caches', { keys: cacheKeys, delete: deleteCache });
    const service = TestBed.inject(PwaStatusService);

    await service.resetCache();

    expect(cacheKeys).toHaveBeenCalledOnce();
    expect(deleteCache).toHaveBeenCalledTimes(2);
    expect(deleteCache).toHaveBeenNthCalledWith(1, 'ngsw:db:app');
    expect(deleteCache).toHaveBeenNthCalledWith(2, 'ngsw:db:stale');
    expect(service.notice()).toContain('캐시');
  });

  it('Service Worker가 비활성화된 환경에서도 주입 오류 없이 상태를 만든다', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});

    const service = TestBed.inject(PwaStatusService);

    expect(service.isOnline()).toBe(true);
    expect(service.updateAvailable()).toBe(false);
  });
});
