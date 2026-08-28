import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

import { MobileViewportDirective } from './mobile-viewport.directive';

@Component({
  standalone: true,
  imports: [MobileViewportDirective],
  template: `<section appMobileViewport><input aria-label="포커스 입력" /></section>`,
})
class HostComponent {}

describe('MobileViewportDirective', () => {
  let viewport: EventTarget & { height: number; offsetTop: number };

  beforeEach(() => {
    viewport = Object.assign(new EventTarget(), { height: 600, offsetTop: 0 });
    vi.stubGlobal('innerHeight', 800);
    vi.stubGlobal('visualViewport', viewport);
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('visualViewport 높이와 keyboard inset을 host CSS 변수로 반영한다', () => {
    const fixture = TestBed.configureTestingModule({ imports: [HostComponent] }).createComponent(
      HostComponent,
    );

    fixture.detectChanges();

    const host = fixture.nativeElement.querySelector('section') as HTMLElement;
    expect(host.style.getPropertyValue('--mobile-viewport-height')).toBe('600px');
    expect(host.style.getPropertyValue('--mobile-keyboard-inset')).toBe('200px');

    viewport.height = 500;
    viewport.dispatchEvent(new Event('resize'));
    expect(host.style.getPropertyValue('--mobile-viewport-height')).toBe('500px');
    expect(host.style.getPropertyValue('--mobile-keyboard-inset')).toBe('300px');
  });

  it('focus된 입력을 중앙으로 스크롤하고 destroy 시 viewport listener를 제거한다', () => {
    const remove = vi.spyOn(viewport, 'removeEventListener');
    const fixture = TestBed.configureTestingModule({ imports: [HostComponent] }).createComponent(
      HostComponent,
    );
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    const scrollIntoView = vi.fn();
    input.scrollIntoView = scrollIntoView;
    input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));

    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'center', inline: 'nearest' });

    fixture.destroy();
    expect(remove).toHaveBeenCalledWith('resize', expect.any(Function));
    expect(remove).toHaveBeenCalledWith('scroll', expect.any(Function));
  });
});
