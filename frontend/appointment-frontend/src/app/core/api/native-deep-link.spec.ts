import { NATIVE_DEEP_LINK_SCHEME, parseNativeDeepLink } from './native-deep-link';

const deepLink = (path: string): string => `${NATIVE_DEEP_LINK_SCHEME}://open${path}`;

describe('parseNativeDeepLink', () => {
  it('지원 calendar query를 실제 Angular route command로 변환한다', () => {
    const result = parseNativeDeepLink(deepLink('/clinic-a/calendar?view=week&date=2026-08-27'));

    expect(result).toEqual({
      ok: true,
      value: {
        tenantCode: 'clinic-a',
        route: 'calendar',
        query: { view: 'week', date: '2026-08-27' },
        routerCommands: ['/calendar', 'week', '2026-08-27'],
      },
    });
    if (result.ok) {
      expect(Object.isFrozen(result.value.query)).toBe(true);
      expect(Object.isFrozen(result.value.routerCommands)).toBe(true);
    }
  });

  it('appointments와 management query를 각각의 route command로 변환한다', () => {
    const appointment = parseNativeDeepLink(deepLink('/clinic-a/appointments?id=42'));
    const management = parseNativeDeepLink(deepLink('/clinic-a/management?section=doctors'));

    expect(appointment).toMatchObject({
      ok: true,
      value: {
        tenantCode: 'clinic-a',
        route: 'appointments',
        query: { id: '42' },
        routerCommands: ['/appointments', '42'],
      },
    });
    expect(management).toMatchObject({
      ok: true,
      value: {
        tenantCode: 'clinic-a',
        route: 'management',
        query: { section: 'doctors' },
        routerCommands: ['/management', 'doctors'],
      },
    });
  });

  it.each([
    ['wrong scheme', 'https://open/clinic-a/calendar'],
    ['wrong host', `${NATIVE_DEEP_LINK_SCHEME}://other/clinic-a/calendar`],
    ['credentials', `${NATIVE_DEEP_LINK_SCHEME}://user:pass@open/clinic-a/calendar`],
    ['port', `${NATIVE_DEEP_LINK_SCHEME}://open:443/clinic-a/calendar`],
    ['fragment', `${NATIVE_DEEP_LINK_SCHEME}://open/clinic-a/calendar#fragment`],
    ['uppercase tenant', deepLink('/Clinic-A/calendar')],
    ['empty tenant', deepLink('//calendar')],
    ['portal route', deepLink('/clinic-a/portal')],
    ['unknown route', deepLink('/clinic-a/settings')],
    ['invalid date', deepLink('/clinic-a/calendar?date=2026-02-31')],
    ['duplicate query', deepLink('/clinic-a/calendar?view=week&view=month')],
    ['unknown query', deepLink('/clinic-a/calendar?token=secret')],
    ['empty query', deepLink('/clinic-a/calendar?view=')],
    ['encoded path delimiter', deepLink('/clinic-a%2Fother/calendar')],
    ['invalid id zero', deepLink('/clinic-a/appointments?id=0')],
    ['invalid id too long', deepLink('/clinic-a/appointments?id=1234567890')],
    ['invalid management section', deepLink('/clinic-a/management?section=users')],
  ])('%s를 fail-closed 결과로 반환한다', (_name, input) => {
    const result = parseNativeDeepLink(input);

    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBeTruthy();
  });

  it('calendar query가 없으면 기본 calendar route를 반환한다', () => {
    expect(parseNativeDeepLink(deepLink('/clinic-a/calendar'))).toEqual({
      ok: true,
      value: {
        tenantCode: 'clinic-a',
        route: 'calendar',
        query: {},
        routerCommands: ['/calendar'],
      },
    });
  });
});
