# Issue #93 — Pagination Support for List Endpoints

## 날짜: 2026-05-18

## 요약

4개 list endpoint에 `ExposedPage<T>` 기반 pagination 적용.

## 변경 사항

- `GET /api/clinics` → `ExposedPage<ClinicRecord>`
- `GET /api/clinics/{id}/doctors` → `ExposedPage<DoctorRecord>`
- `GET /api/clinics/{id}/equipments` → `ExposedPage<EquipmentRecord>`
- `GET /api/clinics/{id}/treatment-types` → `ExposedPage<TreatmentTypeRecord>`
- Frontend services updated to unwrap `.content` from paged response

## 주요 교훈

### 1. API 응답 형태 변경 시 프론트엔드 동기화 필수

Backend list endpoint가 배열에서 paged object로 변경될 때, 프론트엔드 consumer가
`res.data`를 직접 배열로 사용하고 있으면 런타임 오류 발생. Codex 리뷰에서 발견.

**대응**: `PagedData<T>` 인터페이스 추가 + 서비스에서 `.content` unwrap.

### 2. 공유 상수 추출

4개 controller에 동일한 `MAX_PAGE_SIZE = 100` 중복 → `PaginationDefaults` object로 추출.
DRY 원칙 + 향후 일괄 변경 용이.

### 3. Page 파라미터 방어적 클램핑

`page` 파라미터에 음수가 들어올 수 있으므로 `coerceAtLeast(0)` 필수.
`size`는 이미 `coerceIn(1, MAX)` 처리되어 있었음.

## 리뷰 결과

| 리뷰어 | P0 | P1 | P2 | P3 |
|--------|----|----|----|----|
| Claude Code Tier 4 | 0 | 2→0 | 2→0 | 1 |
| Codex CLI | 0 | 1→0 | 0 | 0 |
