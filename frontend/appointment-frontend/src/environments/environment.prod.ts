export const environment = {
  production: true,
  // 같은 origin으로 배포하면 빈 값으로 두고, native/cross-origin 배포에서는
  // 이 값을 HTTPS API origin으로 바꾼다. native runtime override가 있으면 그것이 우선한다.
  apiOrigin: '',
  apiBasePath: '/api',
};
