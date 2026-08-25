import { describe, expect, it } from 'vitest';

// Angular 테스트 번들은 Node 런타임에서 실행되며 이 파일은 운영 코드가 공통 transport를
// 우회하지 않는지 소스 계약을 검사한다.
// @ts-ignore — 테스트 런타임이 제공하는 Node 내장 모듈이다.
import { readFileSync } from 'node:fs';

const managementServices = [
  'appointment.service.ts',
  'clinic.service.ts',
  'doctor.service.ts',
  'equipment.service.ts',
  'equipment-unavailability.service.ts',
  'slot.service.ts',
  'reschedule.service.ts',
  'treatment-type.service.ts',
  'dashboard-stats.service.ts',
] as const;

const workingDirectory = (globalThis as typeof globalThis & {
  process?: { cwd(): string };
}).process?.cwd() ?? '.';

describe('tenant API source contract', () => {
  it.each(managementServices)('%s는 공통 tenant transport만 사용한다', fileName => {
    const source = readFileSync(`${workingDirectory}/src/app/core/services/${fileName}`, 'utf8');

    expect(source).not.toMatch(/HttpClient/);
    expect(source).not.toMatch(/environment\.apiUrl/);
    expect(source).not.toMatch(/['"]\/api\//);
    expect(source).toContain("from '../api/tenant-api-client'");
    expect(source).toContain("authScope: 'workforce-bearer'");
  });
});
