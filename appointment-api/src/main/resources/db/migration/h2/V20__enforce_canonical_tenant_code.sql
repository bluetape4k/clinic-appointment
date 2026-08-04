-- Enforce ADR-14's lowercase ASCII tenant slug contract.
ALTER TABLE scheduling_tenant_groups
    ADD CONSTRAINT ck_tenant_groups_canonical_code
        CHECK (REGEXP_LIKE(tenant_code, '^[a-z0-9]+(-[a-z0-9]+)*$'));
