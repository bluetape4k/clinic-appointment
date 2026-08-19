package io.bluetape4k.clinic.appointment.repository

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** caller가 소유한 Exposed JDBC transaction을 요구하는 repository 경계입니다. */
internal fun requireCurrentExposedTransaction(repositoryName: String) {
    check(TransactionManager.currentOrNull() != null) {
        "$repositoryName requires a caller-owned Exposed transaction"
    }
}
