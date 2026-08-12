package io.bluetape4k.clinic.appointment.api.config

import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.bluetape4k.testcontainers.infra.ToxiproxyServer
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Network
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Redis 응답이 지연된 상태에서도 near-cache 종료가 bounded wait를 지키는지 검증한다. */
class RedisCacheShutdownToxiproxyTest {

    @Test
    fun `Toxiproxy downstream latency 중에도 cache close는 command timeout 안에 끝난다`() {
        Network.newNetwork().use { network ->
            RedisServer()
                .withNetwork(network)
                .withNetworkAliases("redis")
                .use { redis ->
                    ToxiproxyServer()
                        .withNetwork(network)
                        .use { toxiproxy ->
                            redis.start()
                            toxiproxy.start()

                            val toxiproxyClient = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
                            val proxy = toxiproxyClient.createProxy(
                                "redis-shutdown",
                                "0.0.0.0:8666",
                                "redis:${RedisServer.PORT}",
                            )
                            val config = CacheConfig()
                            val client = config.redisClientWithTimeout(
                                url = "redis://${toxiproxy.host}:${toxiproxy.getMappedPort(8666)}",
                                requireTls = false,
                                commandTimeout = Duration.ofSeconds(1),
                            )
                            val caches = listOf(
                                config.clinicDoctorsCache(client),
                                config.clinicEquipmentsCache(client),
                                config.clinicTreatmentTypesCache(client),
                            )
                            val toxic = proxy.toxics().latency(
                                "redis-shutdown-latency",
                                ToxicDirection.DOWNSTREAM,
                                30_000,
                            )
                            val closeExecutor = Executors.newSingleThreadExecutor()
                            val closeFuture = closeExecutor.submit {
                                caches.forEach { it.close() }
                            }
                            var failure: AssertionError? = null

                            try {
                                closeFuture.get(5, TimeUnit.SECONDS)
                            } catch (timeout: TimeoutException) {
                                failure = AssertionError(
                                    "near-cache close가 Redis command timeout 이후에도 5초 안에 끝나지 않았다",
                                    timeout,
                                )
                            } catch (execution: ExecutionException) {
                                failure = AssertionError("near-cache close가 예외로 종료되었다", execution.cause)
                            } finally {
                                runCatching { toxic.remove() }
                                runCatching { closeFuture.get(5, TimeUnit.SECONDS) }
                                closeExecutor.shutdownNow()
                                runCatching { client.shutdown() }
                                runCatching { proxy.delete() }
                            }

                            failure?.let { throw it }
                        }
                }
        }
    }
}
