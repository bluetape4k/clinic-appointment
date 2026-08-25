package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import io.mockk.mockk

/** 여러 context 테스트가 공유하는 upstream leader factory fixture입니다. */
internal class ReusableLeaderElectorFactory : LeaderElectorFactory {
    var elector: LeaderElector = mockk(relaxed = true)

    override fun create(options: LeaderElectionOptions): LeaderElector = elector
}
