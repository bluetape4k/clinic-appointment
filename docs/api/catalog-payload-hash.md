# 카탈로그 payload hash 계약

카탈로그 동기화 호출자는 타입을 확인하고 검증한 카탈로그 정의를 대상으로 계산한
소문자 hex SHA-256을 `payloadHash`로 보내야 한다. hash는 원본 JSON에서 계산하지 않는다.
공백, 객체 멤버 순서, 카탈로그 정의에 속하지 않는 요청 전용 필드는 계산에 포함하지 않는다.

정규 바이트 스트림은 각 필드를 다음 형식으로 이어 붙여 만든다.

```text
utf8(fieldName) 0x00 utf8(valueByteLength) 0x00 utf8(valueText) 0x00
```

`null` 값은 다음 형식으로 이어 붙인다.

```text
utf8(fieldName) 0x00 0xff 0x00
```

값은 검증이 끝난 뒤 도메인 텍스트 형식으로 표현한다.

- 숫자는 앞에 0을 붙이지 않은 10진수 텍스트를 사용한다.
- `Instant`는 ISO-8601 UTC 텍스트를 사용한다. 예: `2026-07-26T05:00:00Z`
- enum은 enum 이름을 사용한다. 예: `ACTIVE`
- `items`와 `dependencies`는 요청 순서를 유지하며, 각 인덱스는 의미를 가진다.
- item 내부에서 순서가 중요하지 않은 문자열 목록은 hash 전에 사전순으로 정렬한다.

필드는 다음 순서로 이어 붙인다.

```text
tenantGroupId
clinicId
sourceAuthority
productId
catalogVersion
productName
schemaVersion
sourceUpdatedAt
status
items.size
items[n].bomItemId
items[n].representativeTreatmentName
items[n].detailedTreatmentCodes.size
items[n].detailedTreatmentCodes[n]
items[n].repeatCount
items[n].durationMinutes
items[n].minimumIntervalDays
items[n].preferredIntervalDays
items[n].maximumIntervalDays
items[n].practitionerQualifications.size
items[n].practitionerQualifications[n]
items[n].equipmentTypes.size
items[n].equipmentTypes[n]
items[n].roomTypes.size
items[n].roomTypes[n]
dependencies.size
dependencies[n].predecessorBomItemId
dependencies[n].predecessorSequenceNo
dependencies[n].successorBomItemId
dependencies[n].successorSequenceNo
dependencies[n].minimumIntervalDays
dependencies[n].preferredIntervalDays
dependencies[n].maximumIntervalDays
initialBookingRule.type
initialBookingRule.maximumDays
```

items와 dependencies의 요청 순서는 정확히 보존한다. 어느 한 목록의 순서를 바꾸면
`payloadHash`가 달라진다. item 내부의 `detailedTreatmentCodes`,
`practitionerQualifications`, `equipmentTypes`, `roomTypes`는 카탈로그 계약에서
순서가 중요하지 않은 집합이므로 사전순으로 정렬한다.

## 픽스처

요청 경로:

```text
PUT /api/tenant-default/clinics/2/catalog-sources/product-catalog/catalog-products/laser-care/versions/7
```

카탈로그 정의:

```json
{
  "sourceAuthority": "product-catalog",
  "tenantGroupId": 1,
  "clinicId": 2,
  "productId": "laser-care",
  "catalogVersion": 7,
  "schemaVersion": 1,
  "sourceUpdatedAt": "2026-07-26T05:00:00Z",
  "status": "ACTIVE",
  "productName": "Laser Care",
  "items": [
    {
      "bomItemId": "laser",
      "representativeTreatmentName": "Laser",
      "detailedTreatmentCodes": ["LASER"],
      "repeatCount": 3,
      "durationMinutes": 30,
      "minimumIntervalDays": 21,
      "preferredIntervalDays": 28,
      "maximumIntervalDays": 42,
      "practitionerQualifications": ["DERMATOLOGIST"],
      "equipmentTypes": ["LASER_A"],
      "roomTypes": ["PROCEDURE"]
    }
  ],
  "dependencies": [],
  "initialBookingRule": {
    "type": "WITHIN_DAYS_AFTER_PURCHASE",
    "maximumDays": 14
  }
}
```

예상 `payloadHash`:

```text
664839668b617f88b14a92091b092266642b5f739b15d9218828825aa5431046
```
