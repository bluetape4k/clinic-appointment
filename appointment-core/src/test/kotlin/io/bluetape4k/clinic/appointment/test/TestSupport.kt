package io.bluetape4k.clinic.appointment.test

import org.jetbrains.exposed.v1.core.vendors.DatabaseDialect
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** 현재 트랜잭션의 데이터베이스 방언을 반환합니다. */
val currentDialectTest: DatabaseDialect
    get() = TransactionManager.current().db.dialect

/** 현재 트랜잭션이 있는 경우 데이터베이스 방언을 반환하고, 없으면 `null`을 반환합니다. */
val currentDialectIfAvailableTest: DatabaseDialect?
    get() = TransactionManager.currentOrNull()?.db?.dialect

/**
 * 현재 DB 방언에 맞는 식별자 대소문자 형식으로 변환합니다.
 *
 * @return 방언에 맞게 변환된 문자열. 현재 트랜잭션이 없으면 원본 문자열을 반환합니다.
 */
fun String.inProperCase(): String =
    TransactionManager.currentOrNull()?.db?.identifierManager?.inProperCase(this) ?: this
