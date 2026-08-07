# 소스로 검증한 README 다이어그램

## 배경

Clinic README 다이어그램에 오래된 레이블(`JwtAuthFilter`, `Redis Leader?`)이
남아 있었고, 현재 스케줄링 테이블 일부가 빠진 ERD도 있었습니다.

## 결정

레이블을 현재 소스 이름으로 갱신하고, `appointment-core/model/tables`에서
도출한 현재 테이블 개요로 ERD를 교체합니다.

## 검증

SVG XML을 검증하고 PNG 자산을 다시 렌더링한 뒤, 다이어그램에서 오래되었거나
소스에 없는 테이블·클래스 레이블을 grep으로 찾습니다.

## 향후 지침

ERD 이미지는 오래된 Mermaid 스냅샷이 아니라 현재 Exposed 테이블 객체에서
다시 생성해야 합니다.
