package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue

/**
 * API Redis 통합 테스트가 사용하는 명시적 Redis 8.8 이미지 런처입니다.
 *
 * 운영 Redis 버전이나 애플리케이션 의존성을 바꾸지 않고 테스트 이미지 계약만 고정합니다.
 */
internal object Redis88Launcher {

    const val IMAGE = "redis"
    const val TAG = "8.8"
    const val IMAGE_NAME = "$IMAGE:$TAG"

    val redis: RedisServer by lazy {
        RedisServer(image = IMAGE, tag = TAG)
            .apply {
                start()
                ShutdownQueue.register(this)
            }
    }
}
