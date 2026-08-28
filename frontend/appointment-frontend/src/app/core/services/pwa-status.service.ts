import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SwUpdate } from '@angular/service-worker';
import { filter } from 'rxjs/operators';
import { environment } from '../../../environments/environment';

/** PWA 연결 상태와 Service Worker 갱신·캐시 제어 상태를 앱 셸에 제공합니다. */
@Injectable({ providedIn: 'root' })
export class PwaStatusService {
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly swUpdate = inject(SwUpdate, { optional: true });
  private readonly window = this.document.defaultView;

  readonly isOnline = signal(this.window?.navigator.onLine ?? true);
  readonly updateAvailable = signal(false);
  readonly notice = signal<string | null>(null);
  readonly resetting = signal(false);

  constructor() {
    this.window?.addEventListener('online', this.handleOnline);
    this.window?.addEventListener('offline', this.handleOffline);
    this.destroyRef.onDestroy(() => {
      this.window?.removeEventListener('online', this.handleOnline);
      this.window?.removeEventListener('offline', this.handleOffline);
    });

    if (this.swUpdate?.isEnabled) {
      this.swUpdate.versionUpdates
        .pipe(
          filter((event) => event.type === 'VERSION_READY'),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe(() => {
          this.updateAvailable.set(true);
          this.notice.set('새 버전을 사용할 수 있습니다.');
        });
    }
  }

  async applyUpdate(): Promise<void> {
    if (!this.swUpdate?.isEnabled) {
      this.notice.set('Service Worker 업데이트를 사용할 수 없습니다.');
      return;
    }

    try {
      const activated = await this.swUpdate.activateUpdate();
      this.updateAvailable.set(false);
      this.notice.set(
        activated
          ? '새 버전을 적용했습니다. 다음 탐색부터 최신 화면을 사용합니다.'
          : '적용할 새 버전이 없습니다.',
      );

      // 개발 서버와 단위 테스트에서는 새로고침을 생략하고, 운영 배포에서만
      // Angular가 안내하는 전체 페이지 전환으로 해시가 섞인 lazy chunk를 함께 갱신합니다.
      if (activated && environment.production) {
        this.window?.location.reload();
      }
    } catch {
      this.notice.set('새 버전을 적용하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    }
  }

  async resetCache(): Promise<void> {
    const cacheStorage = globalThis.caches;
    if (!cacheStorage) {
      this.notice.set('이 브라우저에서는 PWA 캐시를 초기화할 수 없습니다.');
      return;
    }

    this.resetting.set(true);
    try {
      const keys = await cacheStorage.keys();
      await Promise.all(
        keys.filter((key) => key.startsWith('ngsw:')).map((key) => cacheStorage.delete(key)),
      );
      this.notice.set('PWA 캐시를 초기화했습니다.');
    } catch {
      this.notice.set('PWA 캐시를 초기화하지 못했습니다.');
    } finally {
      this.resetting.set(false);
    }
  }

  private readonly handleOnline = (): void => {
    this.isOnline.set(true);
    this.notice.set(null);
  };

  private readonly handleOffline = (): void => {
    this.isOnline.set(false);
    this.notice.set('오프라인 상태입니다. 예약 변경은 온라인 복귀 후 시도해 주세요.');
  };
}
