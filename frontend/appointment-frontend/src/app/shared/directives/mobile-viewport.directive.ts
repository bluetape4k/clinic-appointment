import { DOCUMENT } from '@angular/common';
import { Directive, ElementRef, HostListener, OnDestroy, inject } from '@angular/core';

/**
 * 모바일 WebView의 실제 visual viewport와 focus scroll을 host scroll 경계에 연결한다.
 */
@Directive({
  selector: '[appMobileViewport]',
  standalone: true,
})
export class MobileViewportDirective implements OnDestroy {
  private readonly host = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly document = inject(DOCUMENT);
  private readonly window = this.document.defaultView;
  private readonly viewport = this.window?.visualViewport;
  private readonly onViewportChange = (): void => this.updateViewport();

  constructor() {
    this.updateViewport();
    this.viewport?.addEventListener('resize', this.onViewportChange, { passive: true });
    this.viewport?.addEventListener('scroll', this.onViewportChange, { passive: true });
  }

  @HostListener('focusin', ['$event'])
  onFocusIn(event: FocusEvent): void {
    const target = event.target;
    if (!(target instanceof HTMLElement) || !this.host.contains(target)) return;

    const scroll = (): void => target.scrollIntoView({ block: 'center', inline: 'nearest' });
    if (this.window?.requestAnimationFrame) {
      this.window.requestAnimationFrame(scroll);
    } else {
      scroll();
    }
  }

  ngOnDestroy(): void {
    this.viewport?.removeEventListener('resize', this.onViewportChange);
    this.viewport?.removeEventListener('scroll', this.onViewportChange);
  }

  private updateViewport(): void {
    const height = this.viewport?.height ?? this.window?.innerHeight ?? 0;
    if (height <= 0) return;

    const layoutHeight = this.window?.innerHeight ?? height;
    const offsetTop = this.viewport?.offsetTop ?? 0;
    const keyboardInset = Math.max(0, layoutHeight - offsetTop - height);
    this.host.style.setProperty('--mobile-viewport-height', `${Math.round(height)}px`);
    this.host.style.setProperty('--mobile-keyboard-inset', `${Math.round(keyboardInset)}px`);
  }
}
