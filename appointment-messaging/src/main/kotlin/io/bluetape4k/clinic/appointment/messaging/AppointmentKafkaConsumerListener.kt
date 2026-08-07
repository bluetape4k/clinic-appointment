package io.bluetape4k.clinic.appointment.messaging

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.listener.AcknowledgingConsumerAwareMessageListener
import org.springframework.kafka.support.Acknowledgment
import org.apache.kafka.clients.consumer.Consumer

/** consumer callback에서 block하지 않고 사전에 계산한 lag를 전달하는 sampler port입니다. */
fun interface AppointmentConsumerLagSampler {
    fun sample(record: ConsumerRecord<String, String>): Long?
}

object NoopAppointmentConsumerLagSampler : AppointmentConsumerLagSampler {
    override fun sample(record: ConsumerRecord<String, String>): Long? = null
}

/**
 * Spring Kafka container callback을 bounded consumer runtime으로 연결합니다.
 *
 * runtime이 처리 transaction과 inbox 상태를 먼저 확정한 뒤에만 acknowledgment를
 * 호출하므로, container crash/rebalance가 발생해도 broker redelivery가 가능합니다.
 */
class AppointmentKafkaConsumerListener(
    private val runtime: AppointmentConsumerRuntime,
    private val identity: AppointmentConsumerIdentity,
    private val handler: AppointmentConsumerHandler,
    private val metrics: AppointmentConsumerMetrics = NoopAppointmentConsumerMetrics,
    private val lagSampler: AppointmentConsumerLagSampler = NoopAppointmentConsumerLagSampler,
) : AcknowledgingConsumerAwareMessageListener<String, String> {

    override fun onMessage(
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment?,
        consumer: Consumer<*, *>?,
    ) {
        // Kafka Consumer는 callback thread 외부에서 호출하지 않습니다. lag는 AdminClient
        // sampler 또는 별도 poller가 계산해 주입해야 하므로 callback에서는 즉시 기록합니다.
        try {
            lagSampler.sample(record)?.let(metrics::recordLag)
        } catch (_: Exception) {
            metrics.lagUnavailable()
        }
        runtime.consume(record, acknowledgment, identity, handler)
    }

    /** broker Consumer handle이 없는 direct/unit caller도 같은 adapter를 사용할 수 있습니다. */
    override fun onMessage(record: ConsumerRecord<String, String>, acknowledgment: Acknowledgment?) {
        runtime.consume(record, acknowledgment, identity, handler)
    }

}
