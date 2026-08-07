# 예약 신뢰성 카나리 증거

병원 하나의 rollout마다 이 템플릿을 복사해 사용합니다. 회원 이름, 전화번호,
이메일 주소, 자유 텍스트, token, raw payload는 입력하지 않습니다.

| 항목 | 값 |
|---|---|
| 날짜 / 운영자 | |
| Tenant / clinic | opaque operational ID만 입력 |
| Policy version / hash | |
| Mode and allowlist | `OFF` / `SHADOW` / `ENFORCE` |
| Observation window | UTC start/end |
| Decision count | ≥ 1,000 |
| p95 / p99 latency | p95 ≤ 250ms, p99 ≤ 500ms |
| Duplicate decisions | 0 |
| Unavailable backlog | 0 |
| Attribution-missing ratio | < 1% |
| Raw PII findings | 0 |
| Lease loss / failed jobs | 0 or explained |
| Existing `CONFIRMED` mutations | 0 |
| Health/readiness evidence | link or artifact ID |
| Query-plan evidence | link or artifact ID |
| Rollback tested | yes/no + evidence |
| Decision | promote / hold / rollback |
| Correlation IDs | bounded list |

## 승격 규칙

관찰 기간이 24시간 이상이고 decision count가 1,000 이상이며 위의 수치·개인정보
gate가 모두 통과할 때만 승격합니다. 필드가 비어 있으면 암묵적으로 통과한 것이
아니라 gate 실패로 처리합니다.
