package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 예약 리마인더 스케줄러.
 *
 * 매시간 전일 리마인더와 병원 현지 시간 기준 당일 시간창에 해당하는 CONFIRMED 예약의 리마인더를 발송합니다.
 * HA 환경에서는 [LettuceLeaderGroupElector]을 통해 리더로 선출된 인스턴스만 실행합니다.
 * 이미 발송한 리마인더는 중복 방지합니다.
 *
 * @param notificationChannel 알림 발송 채널
 * @param appointmentRepository 예약 Repository
 * @param historyRepository 알림 이력 Repository
 * @param properties 알림 설정
 * @param leaderElection Redis 기반 리더 선출기
 */
@Component
class AppointmentReminderScheduler(
    private val notificationChannel: NotificationChannel,
    private val appointmentRepository: AppointmentRepository,
    private val historyRepository: NotificationHistoryRepository,
    private val properties: NotificationProperties,
    private val leaderElection: LettuceLeaderGroupElector?,
    private val clinicTimezoneService: ClinicTimezoneService = ClinicTimezoneService(ClinicRepository()),
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object : KLogging() {
        private const val LEADER_LOCK_NAME = "clinic:reminder-scheduler"
    }

    @Scheduled(fixedRate = 3600000) // 1시간
    fun checkReminders() {
        if (!properties.enabled || !properties.reminder.enabled) return

        if (leaderElection != null) {
            val elected = leaderElection.runIfLeader(LEADER_LOCK_NAME) {
                log.debug { "리더 선출됨 — 리마인더 실행" }
                doCheckReminders()
            }
            if (elected == null) {
                log.debug { "리더 선출 실패 — 다른 인스턴스가 실행 중" }
            }
        } else {
            doCheckReminders()
        }
    }

    private fun doCheckReminders() {
        val sameDayCandidateDate = LocalDate.now(clock)
        val tomorrow = LocalDate.now().plusDays(1)
        val clinicZones = mutableMapOf<Long, ZoneId>()

        if (properties.reminder.dayBefore) {
            sendReminders(tomorrow, ReminderType.DAY_BEFORE, NotificationEventType.REMINDER_DAY_BEFORE, clinicZones)
        }

        if (properties.reminder.sameDay) {
            (-1L..1L).forEach { offset ->
                sendReminders(
                    sameDayCandidateDate.plusDays(offset),
                    ReminderType.SAME_DAY,
                    NotificationEventType.REMINDER_SAME_DAY,
                    clinicZones,
                )
            }
        }
    }

    private fun sendReminders(
        date: LocalDate,
        reminderType: ReminderType,
        eventType: String,
        clinicZones: MutableMap<Long, ZoneId>,
    ) {
        val confirmedAppointments = transaction {
            appointmentRepository.findActiveByDate(
                date = date,
                activeStatuses = listOf(AppointmentState.CONFIRMED),
            )
        }

        var sent = 0
        for (appointment in confirmedAppointments) {
            if (reminderType == ReminderType.SAME_DAY && !isSameDayReminderDue(appointment, clinicZones)) continue

            val appointmentId = appointment.id ?: continue
            val alreadySent = transaction {
                historyRepository.existsByAppointmentAndEventType(appointmentId, eventType)
            }
            if (alreadySent) continue

            try {
                notificationChannel.sendReminder(appointment, reminderType)
                sent++
            } catch (e: Exception) {
                log.warn(e) { "리마인더 발송 실패: appointmentId=$appointmentId, type=$reminderType" }
            }
        }

        if (sent > 0) {
            log.info { "리마인더 발송 완료: date=$date, type=$reminderType, count=$sent" }
        }
    }

    private fun isSameDayReminderDue(
        appointment: AppointmentRecord,
        clinicZones: MutableMap<Long, ZoneId>,
    ): Boolean {
        val clinicZone = clinicZones.getOrPut(appointment.clinicId) {
            clinicTimezoneService.getZoneId(appointment.clinicId)
        }
        val clinicNow = clock.instant().atZone(clinicZone)
        if (appointment.appointmentDate != clinicNow.toLocalDate()) return false

        val appointmentStart = ZonedDateTime.of(appointment.appointmentDate, appointment.startTime, clinicNow.zone)
        val remaining = Duration.between(clinicNow, appointmentStart)
        val leadTime = Duration.ofHours(properties.reminder.sameDayHoursBefore.toLong())
        return !remaining.isNegative && remaining <= leadTime
    }
}
