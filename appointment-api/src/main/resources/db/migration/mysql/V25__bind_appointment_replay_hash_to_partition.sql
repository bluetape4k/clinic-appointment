-- V25: replay hash 계약 버전과 선택적 partition 범위를 보존한다.

ALTER TABLE scheduling_appointment_consumer_replay_audit
    ADD COLUMN hash_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE scheduling_appointment_consumer_replay_audit
    ADD COLUMN partition_number INTEGER NULL;

ALTER TABLE scheduling_appointment_consumer_replay_audit
    ADD CONSTRAINT ck_appointment_consumer_replay_hash_version
        CHECK (hash_version >= 1),
    ADD CONSTRAINT ck_appointment_consumer_replay_partition
        CHECK (partition_number IS NULL OR partition_number >= 0);
