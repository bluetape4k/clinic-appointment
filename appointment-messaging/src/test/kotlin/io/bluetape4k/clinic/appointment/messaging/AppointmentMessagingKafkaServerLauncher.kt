package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.testcontainers.mq.KafkaServer

/**
 * Kafka 통합 테스트가 공유하는 singleton broker launcher입니다.
 *
 * 컨테이너는 [kafka]를 처음 참조할 때만 시작되며, 통합 테스트는
 * [RESOURCE_LOCK]을 JUnit `@ResourceLock` 값으로 사용해 순차 실행해야 합니다.
 */
internal object AppointmentMessagingKafkaServerLauncher {

    /** Kafka 통합 테스트를 직렬화하는 JUnit resource 이름입니다. */
    const val RESOURCE_LOCK: String = "appointment-messaging-kafka"

    /** bluetape4k의 공유 Kafka launcher를 통해 재사용되는 broker입니다. */
    val kafka: KafkaServer by lazy { KafkaServer.Launcher.kafka }
}
