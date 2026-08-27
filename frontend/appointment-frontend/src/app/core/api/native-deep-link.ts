/** Capacitor native app identity를 deep-link scheme으로 재사용한다. */
export const NATIVE_DEEP_LINK_SCHEME = 'io.bluetape4k.clinic.appointment' as const;

export const NATIVE_DEEP_LINK_HOST = 'open' as const;

export type NativeDeepLinkRoute = 'calendar' | 'appointments' | 'management';

export type NativeDeepLinkRejectReason =
  | 'invalid-url'
  | 'unsupported-scheme'
  | 'unsupported-host'
  | 'unsafe-url'
  | 'invalid-path'
  | 'invalid-tenant'
  | 'unsupported-route'
  | 'invalid-query';

export interface ParsedNativeDeepLink {
  readonly tenantCode: string;
  readonly route: NativeDeepLinkRoute;
  readonly query: Readonly<Record<string, string>>;
  readonly routerCommands: readonly string[];
}

export type NativeDeepLinkResult =
  | { readonly ok: true; readonly value: ParsedNativeDeepLink }
  | { readonly ok: false; readonly reason: NativeDeepLinkRejectReason };

const MAX_URL_LENGTH = 2048;
const TENANT_CODE = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const ROUTES = new Set<NativeDeepLinkRoute>(['calendar', 'appointments', 'management']);
const CALENDAR_VIEWS = new Set(['day', 'week', 'month']);
const MANAGEMENT_SECTIONS = new Set([
  'clinics',
  'doctors',
  'treatments',
  'reschedule',
  'equipment-unavailability',
  'admin-dashboard',
]);
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const POSITIVE_ID = /^[1-9]\d{0,8}$/;

/**
 * native custom URL을 인증·라우팅 전에 검증하고 안전한 Angular command로 변환합니다.
 *
 * parser는 raw URL, token, credential을 결과에 보존하지 않으며 지원하지 않는 입력은
 * 예외 대신 구조화된 실패 결과를 반환합니다.
 */
export function parseNativeDeepLink(input: unknown): NativeDeepLinkResult {
  if (typeof input !== 'string') return rejected('invalid-url');

  const raw = input.trim();
  if (!raw || raw.length > MAX_URL_LENGTH) return rejected('invalid-url');

  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return rejected('invalid-url');
  }

  if (url.protocol !== `${NATIVE_DEEP_LINK_SCHEME}:`) {
    return rejected('unsupported-scheme');
  }
  if (url.hostname !== NATIVE_DEEP_LINK_HOST || url.host !== NATIVE_DEEP_LINK_HOST) {
    return rejected('unsupported-host');
  }
  if (url.username || url.password || url.port || url.hash) {
    return rejected('unsafe-url');
  }

  const segments = url.pathname.split('/').slice(1);
  if (segments.length !== 2 || segments.some((segment) => !segment)) {
    return rejected('invalid-path');
  }

  const decodedSegments = segments.map(decodeSegment);
  if (decodedSegments.some((segment) => segment === null)) return rejected('invalid-path');

  const [tenantCode, routeSegment] = decodedSegments as [string, string];
  if (!TENANT_CODE.test(tenantCode)) return rejected('invalid-tenant');
  if (!ROUTES.has(routeSegment as NativeDeepLinkRoute)) {
    return rejected('unsupported-route');
  }

  const route = routeSegment as NativeDeepLinkRoute;
  const query = parseQuery(route, url.searchParams);
  if (query === null) return rejected('invalid-query');

  return {
    ok: true,
    value: {
      tenantCode,
      route,
      query,
      routerCommands: buildRouterCommands(route, query),
    },
  };
}

function decodeSegment(segment: string): string | null {
  try {
    const decoded = decodeURIComponent(segment);
    return /[\u0000-\u001f\u007f]/.test(decoded) ? null : decoded;
  } catch {
    return null;
  }
}

function parseQuery(
  route: NativeDeepLinkRoute,
  searchParams: URLSearchParams,
): Readonly<Record<string, string>> | null {
  const allowedKeys =
    route === 'calendar'
      ? new Set(['view', 'date'])
      : route === 'appointments'
        ? new Set(['id'])
        : new Set(['section']);
  const query: Record<string, string> = {};

  for (const key of searchParams.keys()) {
    const values = searchParams.getAll(key);
    if (values.length !== 1 || !allowedKeys.has(key)) return null;

    const value = values[0];
    if (!value || /[\u0000-\u001f\u007f]/.test(value)) return null;
    query[key] = value;
  }

  if (route === 'calendar') {
    if (query['view'] !== undefined && !CALENDAR_VIEWS.has(query['view'])) return null;
    if (query['date'] !== undefined && !isRealIsoDate(query['date'])) return null;
  } else if (route === 'appointments') {
    if (query['id'] !== undefined && !POSITIVE_ID.test(query['id'])) return null;
  } else if (query['section'] !== undefined && !MANAGEMENT_SECTIONS.has(query['section'])) {
    return null;
  }

  return Object.freeze(query);
}

function isRealIsoDate(value: string): boolean {
  const match = ISO_DATE.exec(value);
  if (!match) return false;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  );
}

function buildRouterCommands(
  route: NativeDeepLinkRoute,
  query: Readonly<Record<string, string>>,
): readonly string[] {
  if (route === 'calendar') {
    if (query['view'] && query['date']) {
      return freezeCommands(['/calendar', query['view'], query['date']]);
    }
    if (query['view']) return freezeCommands(['/calendar', query['view']]);
    if (query['date']) return freezeCommands(['/calendar', 'week', query['date']]);
    return freezeCommands(['/calendar']);
  }
  if (route === 'appointments') {
    return freezeCommands(query['id'] ? ['/appointments', query['id']] : ['/appointments']);
  }
  return freezeCommands(query['section'] ? ['/management', query['section']] : ['/management']);
}

function freezeCommands(commands: string[]): readonly string[] {
  return Object.freeze(commands);
}

function rejected(reason: NativeDeepLinkRejectReason): NativeDeepLinkResult {
  return { ok: false, reason };
}
