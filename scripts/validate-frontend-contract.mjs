#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(SCRIPT_DIRECTORY, '..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function majorVersion(version) {
  const match = String(version).match(/(\d+)/)
  return match ? Number(match[1]) : null
}

function validateFrontendContract() {
  const failures = []
  const packageJson = JSON.parse(read('frontend/appointment-frontend/package.json'))
  const angularVersions = [
    packageJson.dependencies?.['@angular/core'],
    packageJson.dependencies?.['@angular/router'],
    packageJson.devDependencies?.['@angular/cli'],
    packageJson.devDependencies?.['@angular/compiler-cli'],
  ]

  if (angularVersions.some((version) => majorVersion(version) !== 22)) {
    failures.push(`package.json Angular versions are not all 22: ${angularVersions.join(', ')}`)
  }

  const versionDocuments = [
    'README.md',
    'README.ko.md',
    'frontend/appointment-frontend/README.md',
    'frontend/appointment-frontend/README.ko.md',
    'docs/requirements/frontend.md',
    'docs/requirements/README.md',
    'docs/requirements/architecture.md',
    'docs/requirements/assets/architecture-01-module-dependency.mmd',
  ]
  for (const relativePath of versionDocuments) {
    const source = read(relativePath)
    if (!source.includes('Angular 22')) failures.push(`${relativePath}: missing Angular 22`)
    if (source.includes('Angular 18') || source.includes('Angular 21')) {
      failures.push(`${relativePath}: stale Angular version reference`)
    }
  }

  const rootReadme = read('README.md')
  const moduleReadme = read('frontend/appointment-frontend/README.md')
  const requirements = read('docs/requirements/frontend.md')
  const contractDocuments = [
    ['README.md', rootReadme],
    ['README.ko.md', read('README.ko.md')],
    ['frontend/appointment-frontend/README.md', moduleReadme],
    ['frontend/appointment-frontend/README.ko.md', read('frontend/appointment-frontend/README.ko.md')],
    ['docs/requirements/frontend.md', requirements],
  ]
  for (const [relativePath, source] of contractDocuments) {
    for (const required of ['/api/{tenantCode}/...', '#295']) {
      if (!source.includes(required)) failures.push(`${relativePath}: missing ${required}`)
    }
  }
  if (rootReadme.includes('프런트엔드 테넌트 라우팅은 후속 단계')) {
    failures.push('README.md: stale tenant routing completion statement')
  }
  if (requirements.includes('30개 엔드포인트 전체 연결 완료') || requirements.includes('Karma 단위 테스트')) {
    failures.push('docs/requirements/frontend.md: stale endpoint or test-tool claim')
  }
  const requirementsIndex = read('docs/requirements/README.md')
  if (requirementsIndex.includes('Angular 21') || requirementsIndex.includes('30개 엔드포인트 전체 연결')) {
    failures.push('docs/requirements/README.md: stale frontend status claim')
  }
  for (const relativePath of ['docs/requirements/data-flow.md', 'docs/requirements/user-scenarios.md']) {
    const source = read(relativePath)
    if (!source.includes('/api/{tenantCode}/...') || !source.includes('#295')) {
      failures.push(`${relativePath}: missing legacy/scoped endpoint boundary note`)
    }
  }

  const appRoutes = read('frontend/appointment-frontend/src/app/app.routes.ts')
  const portalRoutes = read('frontend/appointment-frontend/src/app/features/patient-portal/patient-portal.routes.ts')
  const tenantContext = read('frontend/appointment-frontend/src/app/core/api/tenant-context.service.ts')
  const portalApi = read('frontend/appointment-frontend/src/app/core/api/portal-api-client.ts')
  const patientAuth = read('frontend/appointment-frontend/src/app/core/services/patient-auth.service.ts')
  const staffAuth = read('frontend/appointment-frontend/src/app/core/services/auth.service.ts')
  const staffAppointments = read('frontend/appointment-frontend/src/app/core/services/appointment.service.ts')

  const sourceChecks = [
    ['app.routes.ts portal route', /path:\s*'portal'/, appRoutes],
    ['patient portal login/register routes', /path:\s*'(login|register)'/, portalRoutes],
    ['patient portal auth guard', /patientAuthGuard/, portalRoutes],
    ['tenant session storage', /sessionStorage/, tenantContext],
    ['tenant-scoped portal client transport', /TenantApiClient[\s\S]*authScope:\s*'patient-cookie'/, portalApi],
    ['tenant-scoped patient auth transport', /TenantApiClient[\s\S]*authScope:\s*'patient-cookie'/, patientAuth],
    ['legacy staff JWT service', /class\s+AuthService/, staffAuth],
    ['tenant-scoped staff transport', /TenantApiClient[\s\S]*authScope:\s*'workforce-bearer'/, staffAppointments],
  ]
  for (const [label, pattern, source] of sourceChecks) {
    if (!pattern.test(source)) failures.push(`source: missing ${label}`)
  }

  return {
    ok: failures.length === 0,
    angularMajor: 22,
    documentsChecked: versionDocuments.length + 2,
    sourceChecks: sourceChecks.length,
    failures,
  }
}

function runSelfTest() {
  if (majorVersion('^22.0.8') !== 22 || majorVersion('~6.0.3') !== 6) {
    throw new Error('version parser self-test failed')
  }
}

if (process.argv.includes('--self-test')) runSelfTest()
const report = validateFrontendContract()
console.log(JSON.stringify(report, null, 2))
process.exitCode = report.ok ? 0 : 1

export { validateFrontendContract }
