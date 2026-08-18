import assert from "node:assert/strict"
import test from "node:test"

import {
  DIRECT_KINDS,
  scanSource,
  validateSqlBindings,
} from "./validate-appointment-raw-jdbc-inventory.mjs"

test("Exposed transaction connection access is detected before migration", () => {
  const markers = scanSource(
    "appointment-api/src/test/kotlin/VisitCommitmentCommandTestSupport.kt",
    `transaction(database) {
  val connection = TransactionManager.current().connection.connection as java.sql.Connection
  connection.prepareStatement("SELECT 1").use { it.executeQuery() }
}`,
  )

  assert.ok(markers.some((marker) => marker.kind === "transaction-connection"))
  assert.ok(markers.some((marker) => marker.kind === "prepare-statement"))
})

test("imports, URLs, exception types, and guard literals are not direct resources", () => {
  const markers = scanSource(
    "appointment-api/src/test/kotlin/DataSourceOwnershipContractTest.kt",
    `import java.sql.Connection
import java.sql.SQLException
val url = "jdbc:h2:mem:test"
val guard = "DriverManager.getConnection"
`,
  )

  assert.equal(markers.some((marker) => DIRECT_KINDS.has(marker.kind)), false)
})

test("bound SQL is accepted while interpolated values are rejected", () => {
  assert.deepEqual(validateSqlBindings("INSERT INTO t (name) VALUES (?)"), [])
  assert.deepEqual(
    validateSqlBindings('INSERT INTO t (name) VALUES ("$name")'),
    [{ kind: "interpolated-sql-value" }],
  )
})

test("allowlist drift reports a missing source marker", () => {
  const markers = scanSource(
    "appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt",
    "val dataSource: DataSource = dataSourceProvider()",
  )

  assert.deepEqual(
    markers.map(({ kind, line }) => ({ kind, line })),
    [{ kind: "datasource-boundary", line: 1 }],
  )
})
