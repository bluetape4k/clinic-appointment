package io.bluetape4k.clinic.appointment.api.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException

class NotificationReNotifyServiceTest {

    private val auditEvents = mutableListOf<NotificationReNotifyAuditEvent>()
    private val rateLimitRequests = mutableListOf<NotificationReNotifyRateLimitRequest>()

    @BeforeEach
    fun reset() {
        auditEvents.clear()
        rateLimitRequests.clear()
    }

    @Test
    fun `전용 플랫폼 실행자와 독립된 MFA 병원 승인자를 검증한다`() {
        runBlocking {
            assertFailsWith<AccessDeniedException> {
                service().reNotify(command(actor = staffPrincipal()))
            }
            assertFailsWith<AccessDeniedException> {
                service().reNotify(command(actor = platformPrincipal().copy(scopes = emptySet())))
            }
            assertFailsWith<AccessDeniedException> {
                service().reNotify(
                    command(
                        actor = platformPrincipal().copy(
                            roles = setOf(SchedulingRole.SYSTEM, SchedulingRole.ADMIN),
                        ),
                    )
                )
            }
            assertFailsWith<AccessDeniedException> {
                service(
                    platformApproval = verifiedPlatform(subjectReference = "another-service"),
                ).reNotify(command())
            }
            assertFailsWith<AccessDeniedException> {
                service(
                    platformApproval = verifiedPlatform(
                        roles = setOf(SchedulingRole.SYSTEM, SchedulingRole.ADMIN),
                    ),
                ).reNotify(command())
            }
            assertFailsWith<AccessDeniedException> {
                service(
                    clinicApproval = verifiedClinic(assurance = AuthenticationAssurance.PASSWORD),
                ).reNotify(command())
            }
            assertFailsWith<AccessDeniedException> {
                service(
                    clinicApproval = verifiedClinic(allowedClinicIds = setOf(99L)),
                ).reNotify(command())
            }
            assertFailsWith<AccessDeniedException> {
                service(
                    clinicApproval = verifiedClinic(subjectReference = "notification-platform-service"),
                ).reNotify(command())
            }
            assertFailsWith<AccessDeniedException> {
                service(
                    clinicApproval = verifiedClinic(
                        roles = setOf(SchedulingRole.STAFF, SchedulingRole.SYSTEM),
                    ),
                ).reNotify(command())
            }
        }
    }

    @Test
    fun `100건 제한과 eligibility 완전성 검증을 적용한다`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                service().reNotify(command(appointmentIds = (1L..101L).toSet()))
            }
            assertFailsWith<IllegalArgumentException> {
                service(eligibility = { listOf(NotificationReNotifyEligibility.accept(1L)) })
                    .reNotify(command(appointmentIds = setOf(1L, 2L)))
            }
        }
    }

    @Test
    fun `dry run은 현재 profile consent template을 재평가하고 enqueue와 rate limit을 소비하지 않는다`() {
        runBlocking {
            var enqueueCalled = false
            val service = service(
                eligibility = {
                    listOf(
                        NotificationReNotifyEligibility.accept(1L),
                        NotificationReNotifyEligibility.accept(
                            appointmentId = 2L,
                            currentStatus = NotificationOutboxStatus.PENDING,
                            profileEligible = false,
                        ),
                        NotificationReNotifyEligibility.accept(
                            appointmentId = 3L,
                            currentStatus = NotificationOutboxStatus.PENDING,
                            consentEligible = false,
                        ),
                        NotificationReNotifyEligibility.accept(
                            appointmentId = 4L,
                            currentStatus = NotificationOutboxStatus.PENDING,
                            templateEligible = false,
                        ),
                    )
                },
                enqueue = { _, _ ->
                    enqueueCalled = true
                    NotificationReNotifyEnqueueResult("gen-1", 1, resumed = false)
                },
            )

            val result = service.reNotify(command(dryRun = true, appointmentIds = setOf(1L, 2L, 3L, 4L)))

            result.acceptedCount shouldBeEqualTo 1
            result.skippedReasons shouldBeEqualTo mapOf(
                "CONSENT_INELIGIBLE" to 1,
                "PROFILE_INELIGIBLE" to 1,
                "TEMPLATE_INELIGIBLE" to 1,
            )
            enqueueCalled.shouldBeFalse()
            rateLimitRequests.isEmpty().shouldBeTrue()
            auditEvents.map { it.phase } shouldBeEqualTo listOf(
                NotificationReNotifyAuditPhase.STARTED,
                NotificationReNotifyAuditPhase.COMPLETED,
            )
        }
    }

    @Test
    fun `완료 결과 불명 suppression은 우회하지 않고 skip으로 집계한다`() {
        runBlocking {
            val service = service(
                eligibility = {
                    listOf(
                        NotificationReNotifyEligibility.accept(
                            appointmentId = 1L,
                            currentStatus = NotificationOutboxStatus.SENT,
                        ),
                        NotificationReNotifyEligibility.accept(
                            appointmentId = 2L,
                            currentStatus = NotificationOutboxStatus.PENDING,
                            failureCode = NotificationFailureCode.DELIVERY_RESULT_UNKNOWN,
                        ),
                        NotificationReNotifyEligibility.accept(
                            appointmentId = 3L,
                            currentStatus = NotificationOutboxStatus.PENDING,
                            suppressionReason = NotificationSuppressionReasonCode.CONSENT_DENIED,
                        ),
                    )
                }
            )

            val result = service.reNotify(command(appointmentIds = setOf(1L, 2L, 3L)))

            result.acceptedCount shouldBeEqualTo 0
            result.skippedCount shouldBeEqualTo 3
            result.skippedReasons shouldBeEqualTo mapOf(
                "DELIVERY_RESULT_UNKNOWN" to 1,
                "SENT" to 1,
                "SUPPRESSED_CONSENT_DENIED" to 1,
            )
            rateLimitRequests.isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `clinic과 provider rate limit을 적용하고 같은 generation으로 재개한다`() {
        runBlocking {
            val service = service(
                eligibility = {
                    listOf(
                        NotificationReNotifyEligibility.accept(
                            appointmentId = 1L,
                            currentStatus = NotificationOutboxStatus.PENDING,
                            providerKey = "sms",
                        ),
                        NotificationReNotifyEligibility.accept(
                            appointmentId = 2L,
                            currentStatus = NotificationOutboxStatus.PENDING,
                            providerKey = "email",
                        ),
                    )
                },
                enqueue = { command, accepted ->
                    NotificationReNotifyEnqueueResult(command.generation, accepted.size, resumed = true)
                },
            )

            val result = service.reNotify(command(generation = "gen-resume-7", appointmentIds = setOf(1L, 2L)))

            result.generation shouldBeEqualTo "gen-resume-7"
            result.acceptedCount shouldBeEqualTo 2
            rateLimitRequests.single() shouldBeEqualTo NotificationReNotifyRateLimitRequest(
                tenantGroupId = 1L,
                clinicId = 10L,
                providerKeys = setOf("email", "sms"),
                requestedCount = 2,
            )
        }
    }

    @Test
    fun `enqueue 실패 시 INTERRUPTED 감사를 기록하고 원래 예외를 전파한다`() {
        runBlocking {
            val service = service(
                enqueue = { _, _ -> error("enqueue failed") },
            )

            assertFailsWith<IllegalStateException> {
                service.reNotify(command())
            }

            auditEvents.map { it.phase } shouldBeEqualTo listOf(
                NotificationReNotifyAuditPhase.STARTED,
                NotificationReNotifyAuditPhase.INTERRUPTED,
            )
            auditEvents.joinToString("\n").contains("appointment").shouldBeFalse()
            auditEvents.joinToString("\n").contains("scope=<redacted>").shouldBeTrue()
        }
    }

    @Test
    fun `실제 작업 취소 시 INTERRUPTED 감사를 남기고 취소를 전파한다`() {
        runBlocking {
            val enqueueStarted = CompletableDeferred<Unit>()
            val service = service(
                enqueue = { _, _ ->
                    enqueueStarted.complete(Unit)
                    awaitCancellation()
                },
            )
            val job = launch {
                service.reNotify(command())
            }

            enqueueStarted.await()
            job.cancelAndJoin()

            job.isCancelled.shouldBeTrue()
            auditEvents.map { it.phase } shouldBeEqualTo listOf(
                NotificationReNotifyAuditPhase.STARTED,
                NotificationReNotifyAuditPhase.INTERRUPTED,
            )
        }
    }

    private fun service(
        eligibility: suspend (NotificationReNotifyCommand) -> List<NotificationReNotifyEligibility> = {
            it.appointmentIds.map(NotificationReNotifyEligibility::accept)
        },
        enqueue: suspend (
            NotificationReNotifyCommand,
            List<NotificationReNotifyEligibility>,
        ) -> NotificationReNotifyEnqueueResult = { command, accepted ->
            NotificationReNotifyEnqueueResult(command.generation, accepted.size, resumed = false)
        },
        platformApproval: VerifiedNotificationApproval = verifiedPlatform(),
        clinicApproval: VerifiedNotificationApproval = verifiedClinic(),
    ): NotificationReNotifyService =
        NotificationReNotifyService(
            eligibilityPort = NotificationReNotifyEligibilityPort(eligibility),
            enqueuePort = NotificationReNotifyEnqueuePort(enqueue),
            auditSink = NotificationReNotifyAuditSink { auditEvents += it },
            approvalVerifier = object : NotificationReNotifyApprovalVerifier {
                override suspend fun verifyPlatform(
                    executor: SchedulingUserPrincipal,
                    reference: ApprovalReference,
                ): VerifiedNotificationApproval = platformApproval

                override suspend fun verifyClinic(
                    tenantGroupId: Long,
                    clinicId: Long,
                    reference: ApprovalReference,
                ): VerifiedNotificationApproval = clinicApproval
            },
            rateLimiter = NotificationReNotifyRateLimiter { rateLimitRequests += it },
        )

    private fun command(
        appointmentIds: Set<Long> = setOf(1L),
        generation: String = "gen-1",
        dryRun: Boolean = false,
        actor: SchedulingUserPrincipal = platformPrincipal(),
    ): NotificationReNotifyCommand =
        NotificationReNotifyCommand(
            tenantGroupId = 1L,
            clinicId = 10L,
            appointmentIds = appointmentIds,
            generation = generation,
            platformApproval = ApprovalReference("platform-approval", "PLAT-1"),
            clinicApproval = ApprovalReference("clinic-approval", "CLINIC-1"),
            dryRun = dryRun,
            actor = actor,
        )

    private fun platformPrincipal(): SchedulingUserPrincipal =
        SchedulingUserPrincipal(
            userId = "notification-platform-service",
            clinicId = 10L,
            roles = setOf(SchedulingRole.SYSTEM),
            allowedTenants = setOf("tenant-default"),
            scopes = setOf("notification:renotify"),
            actorType = ActorType.SYSTEM,
            allowedClinicIds = setOf(10L),
            assurance = AuthenticationAssurance.SERVICE,
            issuer = "issuer",
            tokenId = "token",
        )

    private fun staffPrincipal(): SchedulingUserPrincipal =
        SchedulingUserPrincipal(
            userId = "notification-staff",
            clinicId = 10L,
            roles = setOf(SchedulingRole.STAFF),
            allowedTenants = setOf("tenant-default"),
            scopes = setOf("notification:renotify"),
            actorType = ActorType.STAFF,
            allowedClinicIds = setOf(10L),
            assurance = AuthenticationAssurance.MFA,
            issuer = "issuer",
            tokenId = "token",
        )

    private fun verifiedPlatform(
        subjectReference: String = "notification-platform-service",
        roles: Set<String> = setOf(SchedulingRole.SYSTEM),
    ): VerifiedNotificationApproval =
        VerifiedNotificationApproval(
            subjectReference = subjectReference,
            actorType = ActorType.SYSTEM,
            assurance = AuthenticationAssurance.SERVICE,
            roles = roles,
            allowedClinicIds = setOf(10L),
        )

    private fun verifiedClinic(
        subjectReference: String = "clinic-approver",
        assurance: AuthenticationAssurance = AuthenticationAssurance.MFA,
        allowedClinicIds: Set<Long> = setOf(10L),
        roles: Set<String> = setOf(SchedulingRole.STAFF),
    ): VerifiedNotificationApproval =
        VerifiedNotificationApproval(
            subjectReference = subjectReference,
            actorType = ActorType.STAFF,
            assurance = assurance,
            roles = roles,
            allowedClinicIds = allowedClinicIds,
        )
}
