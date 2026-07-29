package io.bluetape4k.clinic.appointment.api

/**
 * Task 10의 canonical Gatling 진입점입니다.
 *
 * 일반·최대 Plan proposal latency와 방문 확정 command의 실제 Exposed DB 경로를
 * 한 Gatling HTTP 실행에서 함께 측정합니다.
 *
 * commitment probe는 loopback HTTP handler 안에서 case별 isolated H2 fixture를 만들고
 * [io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandService]를
 * 직접 호출합니다. 따라서 exclusive overlap, capacity bucket exhaustion,
 * practitioner/equipment/treatment-space canonical multi-lock, 동일 idempotency-key
 * replay가 가짜 in-memory model이 아니라 production repository와 transaction 경로를
 * 통과합니다.
 */
class VisitCommitmentSimulation : VisitCommitmentProposalSimulation(includeCommitmentLoad = true)
