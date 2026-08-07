package io.bluetape4k.clinic.appointment.messaging

import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.core.ConsumerFactory
import java.time.Duration

/**
 * 운영 consumer group offset을 건드리지 않는 bounded replay adapter입니다.
 * 각 partition을 request range에 assign하고, 동일 logical inbox identity로 runtime을 호출합니다.
 */
class KafkaAppointmentReplaySource(
    private val consumerFactory: ConsumerFactory<String, String>,
    private val topic: AppointmentTopic,
    private val runtime: AppointmentConsumerRuntime,
    private val handler: AppointmentConsumerHandler,
    private val expectedIdentity: AppointmentConsumerIdentity,
    private val pollTimeout: Duration = Duration.ofSeconds(1),
    private val maxDuration: Duration = Duration.ofMinutes(5),
    private val maxRecords: Int = 100_000,
) : AppointmentReplaySource {
    init {
        require(!pollTimeout.isNegative && !pollTimeout.isZero) { "replay pollTimeout must be positive" }
        require(!maxDuration.isNegative && !maxDuration.isZero) { "replay maxDuration must be positive" }
        require(maxRecords in 1..100_000) { "replay maxRecords must be bounded" }
    }

    override fun replay(request: AppointmentReplayRequest, execution: AppointmentReplayExecution): Int {
        require(request.identity == expectedIdentity) {
            "replay request identity is not bound to this source adapter"
        }
        val deadline = System.nanoTime() + maxDuration.toNanos()
        consumerFactory.createConsumer(execution.groupId).use { consumer ->
            val partitions = consumer.partitionsFor(topic.value)
                .filter { request.partition == null || it.partition() == request.partition }
                .map { TopicPartition(topic.value, it.partition()) }
            require(partitions.isNotEmpty()) { "replay topic has no partitions" }
            consumer.assign(partitions)
            val beginningOffsets = consumer.beginningOffsets(partitions)
            val endOffsets = consumer.endOffsets(partitions)
            val targetExclusiveOffsets = buildMap {
                partitions.forEach { partition ->
                    val first = beginningOffsets[partition] ?: 0L
                    val lastExclusive = endOffsets[partition] ?: first
                    val requestedExclusive = request.toOffset
                        .coerceAtMost(Long.MAX_VALUE - 1)
                        .plus(1)
                    put(partition, requestedExclusive.coerceIn(first, lastExclusive))
                }
            }
            partitions.forEach { partition ->
                val first = beginningOffsets[partition] ?: 0L
                val requestedOffset = request.fromOffset.coerceAtLeast(first)
                val targetExclusive = targetExclusiveOffsets.getValue(partition)
                consumer.seek(partition, requestedOffset.coerceAtMost(targetExclusive))
            }

            var replayed = 0
            while (System.nanoTime() < deadline) {
                val records = consumer.poll(pollTimeout)
                records.forEach { record ->
                    if (record.offset() < request.fromOffset || record.offset() > request.toOffset) return@forEach
                    runtime.consume(
                        record = record,
                        acknowledgment = null,
                        identity = execution.identity,
                        handler = handler,
                        expectedScope = AppointmentReplayScope(request.tenantGroupId, request.clinicId),
                    )
                    replayed++
                    require(replayed <= maxRecords) { "replay record limit exceeded" }
                }
                if (partitions.all { partition ->
                        consumer.position(partition) >= targetExclusiveOffsets.getValue(partition)
                    }
                ) {
                    return replayed
                }
                if (replayed >= maxRecords) throw AppointmentReplayException("replay record limit exceeded")
            }
            if (replayed >= maxRecords) throw AppointmentReplayException("replay record limit exceeded")
            throw AppointmentReplayException("replay did not reach the requested offset range before timeout")
        }
    }
}
