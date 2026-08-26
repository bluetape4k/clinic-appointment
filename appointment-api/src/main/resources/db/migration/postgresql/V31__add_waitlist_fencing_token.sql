-- V31: waitlist scheduler의 Redis fencing token을 additive하게 저장한다.
ALTER TABLE scheduling_waitlist_vacancy_jobs
    ADD COLUMN fence_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE scheduling_waitlist_vacancy_jobs
    ADD COLUMN fence_sequence BIGINT NOT NULL DEFAULT 0;
