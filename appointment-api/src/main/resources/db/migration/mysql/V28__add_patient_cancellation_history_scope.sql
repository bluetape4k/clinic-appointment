-- V28: expand only. Existing writers can ignore these nullable snapshot columns.

ALTER TABLE scheduling_appointment_cancellation_details
    ADD COLUMN from_commitment_status VARCHAR(32),
    ADD COLUMN patient_scope_fingerprint VARCHAR(128);
