import { HttpContextToken } from '@angular/common/http';

/** 요청이 사용할 인증 주체를 transport에 명시합니다. */
export type ApiAuthScope = 'none' | 'patient-cookie' | 'workforce-bearer';

/** URL이나 현재 token 상태를 추측하지 않고 호출자가 요청 인증 경계를 전달합니다. */
export const API_AUTH_SCOPE = new HttpContextToken<ApiAuthScope>(() => 'none');
