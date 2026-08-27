import { Injectable, InjectionToken, OnDestroy, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { App as CapacitorApp, type AppLaunchUrl, type URLOpenListenerEvent } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';
import { Observable, Subject } from 'rxjs';
import { type NativeDeepLinkRoute, parseNativeDeepLink } from '../api/native-deep-link';
import { TenantContextService } from '../api/tenant-context.service';
import { AuthService } from './auth.service';

export const NATIVE_WEBVIEW_EVENT_NAME = 'clinic.native.navigation.v1' as const;

export interface NativeNavigationEvent {
  readonly name: typeof NATIVE_WEBVIEW_EVENT_NAME;
  readonly version: 1;
  readonly tenantCode: string;
  readonly route: NativeDeepLinkRoute;
  readonly query: Readonly<Record<string, string>>;
}

export type NativeWebViewBridgeStatus =
  'idle' | 'browser-noop' | 'native-starting' | 'native-ready' | 'native-unavailable';

export type NativeWebViewBridgeResult =
  | { readonly accepted: true; readonly event: NativeNavigationEvent }
  | {
      readonly accepted: false;
      readonly reason: 'invalid-link' | 'unauthorized' | 'navigation-failed';
    };

export interface NativePlatformAdapter {
  isNativePlatform(): boolean;
}

export interface NativeAppPluginAdapter {
  addListener(
    eventName: 'appUrlOpen',
    listenerFunc: (event: URLOpenListenerEvent) => void,
  ): Promise<PluginListenerHandle>;
  getLaunchUrl(): Promise<AppLaunchUrl | undefined>;
}

export const NATIVE_PLATFORM = new InjectionToken<NativePlatformAdapter>('NATIVE_PLATFORM', {
  providedIn: 'root',
  factory: () => Capacitor,
});

export const NATIVE_APP_PLUGIN = new InjectionToken<NativeAppPluginAdapter>('NATIVE_APP_PLUGIN', {
  providedIn: 'root',
  factory: () => CapacitorApp,
});

/**
 * Capacitor URL lifecycle을 Angular router와 workforce session에 연결합니다.
 *
 * browser에서는 native plugin을 호출하지 않는 no-op으로 동작합니다. native에서
 * 처리하는 URL은 `NATIVE_DEEP_LINK_SCHEME` parser와 `AuthService.allowedTenants`를
 * 통과해야 하며, 성공한 navigation만 versioned event로 발행합니다.
 */
@Injectable({ providedIn: 'root' })
export class NativeWebViewBridgeService implements OnDestroy {
  private readonly platform = inject(NATIVE_PLATFORM);
  private readonly plugin = inject(NATIVE_APP_PLUGIN);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly tenant = inject(TenantContextService);

  private readonly _status = signal<NativeWebViewBridgeStatus>('idle');
  private readonly navigationEvents = new Subject<NativeNavigationEvent>();
  private listener: PluginListenerHandle | null = null;
  private startPromise: Promise<void> | null = null;

  readonly status = this._status.asReadonly();
  readonly events: Observable<NativeNavigationEvent> = this.navigationEvents.asObservable();

  /** 앱 셸에서 한 번 호출하며 중복 호출은 같은 초기화 promise를 재사용합니다. */
  start(): Promise<void> {
    if (this.startPromise) return this.startPromise;

    this.startPromise = this.startInternal();
    return this.startPromise;
  }

  /** 외부 host callback과 테스트가 동일한 검증·라우팅 경계를 사용하도록 공개합니다. */
  async handleUrl(url: string): Promise<NativeWebViewBridgeResult> {
    const parsed = parseNativeDeepLink(url);
    if (!parsed.ok) return { accepted: false, reason: 'invalid-link' };

    if (
      !this.auth.isAuthenticated() ||
      !this.auth.allowedTenants().includes(parsed.value.tenantCode)
    ) {
      this.auth.markUnauthorized();
      return { accepted: false, reason: 'unauthorized' };
    }

    try {
      this.tenant.setTenant(parsed.value.tenantCode);
    } catch {
      this.auth.markUnauthorized();
      return { accepted: false, reason: 'unauthorized' };
    }

    let navigated = false;
    try {
      navigated = await this.router.navigate(parsed.value.routerCommands);
    } catch {
      navigated = false;
    }
    if (!navigated) return { accepted: false, reason: 'navigation-failed' };

    const event = Object.freeze({
      name: NATIVE_WEBVIEW_EVENT_NAME,
      version: 1 as const,
      tenantCode: parsed.value.tenantCode,
      route: parsed.value.route,
      query: parsed.value.query,
    });
    this.navigationEvents.next(event);
    dispatchNavigationEvent(event);
    return { accepted: true, event };
  }

  async stop(): Promise<void> {
    const listener = this.listener;
    this.listener = null;
    this.startPromise = null;
    if (!listener) return;

    try {
      await listener.remove();
    } catch {
      // 앱 종료 시 listener cleanup 실패가 Angular destroy를 실패시키지 않도록 한다.
    }
  }

  ngOnDestroy(): void {
    void this.stop();
    this.navigationEvents.complete();
  }

  private async startInternal(): Promise<void> {
    if (!this.platform.isNativePlatform()) {
      this._status.set('browser-noop');
      return;
    }

    this._status.set('native-starting');
    try {
      this.listener = await this.plugin.addListener('appUrlOpen', (event) => {
        void this.handleUrl(event.url);
      });
      const launchUrl = await this.plugin.getLaunchUrl();
      if (launchUrl?.url) await this.handleUrl(launchUrl.url);
      this._status.set('native-ready');
    } catch {
      this._status.set('native-unavailable');
      await this.stop();
    }
  }
}

function dispatchNavigationEvent(event: NativeNavigationEvent): void {
  if (
    typeof globalThis.dispatchEvent !== 'function' ||
    typeof globalThis.CustomEvent !== 'function'
  ) {
    return;
  }
  globalThis.dispatchEvent(
    new CustomEvent<NativeNavigationEvent>(NATIVE_WEBVIEW_EVENT_NAME, { detail: event }),
  );
}
