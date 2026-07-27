# Catalog payload hash contract

Catalog sync callers must send `payloadHash` as lowercase hex SHA-256 over the
typed, validated catalog definition. The hash is not calculated from raw JSON.
Whitespace, object-member order, and request-only fields outside the catalog
definition do not participate.

The canonical byte stream is built by appending each field as:

```text
utf8(fieldName) 0x00 utf8(valueByteLength) 0x00 utf8(valueText) 0x00
```

For `null` values, append:

```text
utf8(fieldName) 0x00 0xff 0x00
```

Values use their domain text form after validation:

- numbers use base-10 text with no leading zeroes
- `Instant` uses ISO-8601 UTC text, for example `2026-07-26T05:00:00Z`
- enums use their enum name, for example `ACTIVE`
- `items` and `dependencies` preserve request order; their indexes are semantic
- order-insensitive string lists inside an item are sorted lexicographically
  before hashing

Fields are appended in this order:

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

The request order of items and dependencies is preserved exactly. Reordering
either list changes `payloadHash`. Within an item,
`detailedTreatmentCodes`, `practitionerQualifications`, `equipmentTypes`, and
`roomTypes` are sorted lexicographically because those lists are
order-insensitive sets in the catalog contract.

## Fixture

Request path:

```text
PUT /api/tenant-default/clinics/2/catalog-sources/product-catalog/catalog-products/laser-care/versions/7
```

Catalog definition:

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

Expected `payloadHash`:

```text
664839668b617f88b14a92091b092266642b5f739b15d9218828825aa5431046
```
