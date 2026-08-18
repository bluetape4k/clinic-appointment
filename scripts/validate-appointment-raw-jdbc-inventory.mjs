import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url))
const DEFAULT_MANIFEST = path.join(SCRIPT_DIRECTORY, "raw-jdbc-allowlist.json")

export const DIRECT_KINDS = new Set([
  "transaction-connection",
  "driver-manager",
  "prepare-statement",
  "create-statement",
  "execute-query",
  "execute-update",
  "execute",
])

const SOURCE_EXTENSIONS = new Set([".kt", ".java"])

function stripLineComment(line) {
  return line.replace(/\/\/.*$/, "")
}

function maskStringLiterals(line) {
  return line
    .replace(/"""[\s\S]*?"""/g, '""')
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
    .replace(/'(?:\\.|[^'\\])*'/g, "''")
}

function nearestSymbol(lines, lineIndex) {
  for (let index = lineIndex; index >= Math.max(0, lineIndex - 80); index -= 1) {
    const match = lines[index].match(/\b(?:fun|class|object|interface|enum\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)/)
    if (match) return match[1]
  }
  return undefined
}

/** 소스 한 파일의 실행 가능한 direct JDBC marker와 경계 marker를 반환합니다. */
export function scanSource(filePath, source) {
  const lines = source.split(/\r?\n/)
  const markers = []

  lines.forEach((rawLine, lineIndex) => {
    const line = stripLineComment(rawLine)
    if (!line.trim() || /^\s*import\s/.test(line) || /^\s*package\s/.test(line)) return

    const code = maskStringLiterals(line)
    const add = (kind) => markers.push({ kind, line: lineIndex + 1, symbol: nearestSymbol(lines, lineIndex), filePath })

    if (/\bTransactionManager\s*\.\s*current\s*\(\s*\)\s*\.\s*connection\b/.test(code)) add("transaction-connection")
    if (/\bDriverManager\s*\.\s*getConnection\s*\(/.test(code)) add("driver-manager")
    if (/\.\s*prepareStatement\s*\(/.test(code)) add("prepare-statement")
    if (/\.\s*createStatement\s*\(/.test(code)) add("create-statement")
    if (/\.\s*executeQuery\s*\(/.test(code)) add("execute-query")
    if (/\.\s*executeUpdate\s*\(/.test(code)) add("execute-update")
    if (/\b(?:connection|statement|query|jdbcConnection|result|it)\s*\.\s*execute\s*\(/.test(code)) add("execute")

    if (/\b(?:DataSource|HikariDataSource|JdbcDataSource|SimpleDriverDataSource|PGSimpleDataSource)\b/.test(code)) add("datasource-boundary")
    if (/\bjdbc:[A-Za-z][A-Za-z0-9+.-]*:/.test(line)) add("jdbc-url")
  })

  return markers
}

/** SQL literal 안에서 값 보간이 발생하는지 검사합니다. */
export function validateSqlBindings(sql) {
  if (!/\b(?:SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|EXPLAIN)\b/i.test(sql)) return []
  // `$sql`, `$schema`, `$table`, and `$column` can be fixed SQL fragments or
  // validated identifiers at a boundary. Flag only simple interpolation in a
  // value position where a bound parameter is required.
  const valueInterpolation =
    /\bVALUES\s*\([^)]*\$\{?[A-Za-z_][A-Za-z0-9_.-]*\}?/i.test(sql) ||
    /\b(?:WHERE|HAVING|ON)\b[^\n]*=\s*\$\{?[A-Za-z_][A-Za-z0-9_.-]*\}?/i.test(sql) ||
    /\b(?:LIMIT|OFFSET|IN)\s*\(?\s*\$\{?[A-Za-z_][A-Za-z0-9_.-]*\}?/i.test(sql)
  return valueInterpolation ? [{ kind: "interpolated-sql-value" }] : []
}

function walkSourceFiles(root, relativeRoot) {
  const absoluteRoot = path.join(root, relativeRoot)
  if (!fs.existsSync(absoluteRoot)) return []

  const files = []
  const visit = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const absolutePath = path.join(directory, entry.name)
      if (entry.isDirectory()) visit(absolutePath)
      else if (SOURCE_EXTENSIONS.has(path.extname(entry.name))) files.push(absolutePath)
    }
  }
  visit(absoluteRoot)
  return files
}

function readManifest(manifestPath) {
  return JSON.parse(fs.readFileSync(manifestPath, "utf8"))
}

function isAllowed(marker, entry) {
  if (!entry) return false
  if (entry.forbidKinds?.includes(marker.kind)) return false
  if (!DIRECT_KINDS.has(marker.kind)) return true
  return entry.allowedKinds?.includes("*") || entry.allowedKinds?.includes(marker.kind)
}

function relativeToRoot(root, absolutePath) {
  return path.relative(root, absolutePath).split(path.sep).join("/")
}

/** 저장소 전체 inventory와 manifest drift를 검증하고 JSON report를 반환합니다. */
export function validateInventory({ root = process.cwd(), manifestPath = DEFAULT_MANIFEST } = {}) {
  const manifest = readManifest(manifestPath)
  const entries = new Map(manifest.files.map((entry) => [entry.path, entry]))
  const scannedPaths = new Set()
  const markersByPath = new Map()
  const violations = []
  const staleEntries = []
  const bindingViolations = []

  for (const sourceRoot of manifest.sourceRoots) {
    for (const absolutePath of walkSourceFiles(root, sourceRoot)) {
      const relativePath = relativeToRoot(root, absolutePath)
      scannedPaths.add(relativePath)
      const source = fs.readFileSync(absolutePath, "utf8")
      const markers = scanSource(relativePath, source)
      if (markers.length > 0) markersByPath.set(relativePath, markers)
      const entry = entries.get(relativePath)

      if (entry?.requiredPattern && !new RegExp(entry.requiredPattern, "m").test(source)) {
        staleEntries.push({ path: relativePath, reason: "required-pattern-missing", pattern: entry.requiredPattern })
      }

      markers.filter((marker) => DIRECT_KINDS.has(marker.kind)).forEach((marker) => {
        if (!entry) {
          violations.push({ path: relativePath, ...marker, reason: "path-not-allowlisted" })
        } else if (!isAllowed(marker, entry)) {
          violations.push({ path: relativePath, ...marker, reason: "marker-not-allowlisted" })
        }
      })

      const directLines = markers.filter((marker) => DIRECT_KINDS.has(marker.kind)).map((marker) => marker.line)
      if (directLines.length > 0 && entry?.checkBindings) {
        source.split(/\r?\n/).forEach((line, index) => {
          const nearDirectCall = directLines.some((lineNumber) => Math.abs(lineNumber - (index + 1)) <= 4)
          if (!nearDirectCall) return
          const bindingErrors = validateSqlBindings(line)
          bindingErrors.forEach((error) => bindingViolations.push({ path: relativePath, line: index + 1, ...error }))
        })
      }
    }
  }

  for (const entry of manifest.files) {
    const absolutePath = path.join(root, entry.path)
    if (!fs.existsSync(absolutePath)) {
      staleEntries.push({ path: entry.path, reason: "file-missing" })
      continue
    }
    if (!scannedPaths.has(entry.path)) staleEntries.push({ path: entry.path, reason: "outside-source-roots" })
    if (!markersByPath.has(entry.path)) {
      const source = fs.readFileSync(absolutePath, "utf8")
      if (entry.requiredPattern && !new RegExp(entry.requiredPattern, "m").test(source)) {
        staleEntries.push({ path: entry.path, reason: "marker-missing" })
      }
    }
  }

  const directMarkerCount = [...markersByPath.values()].flat().filter((marker) => DIRECT_KINDS.has(marker.kind)).length
  const boundaryMarkerCount = [...markersByPath.values()].flat().filter((marker) => !DIRECT_KINDS.has(marker.kind)).length
  return {
    manifestVersion: manifest.version,
    sourceFileCount: scannedPaths.size,
    allowlistedFileCount: manifest.files.length,
    directMarkerCount,
    boundaryMarkerCount,
    violations,
    staleEntries,
    bindingViolations,
    ok: violations.length === 0 && staleEntries.length === 0 && bindingViolations.length === 0,
  }
}

function parseArguments(argv) {
  const options = { root: process.cwd(), manifestPath: DEFAULT_MANIFEST, json: false, selfTest: false }
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === "--root") options.root = path.resolve(argv[++index])
    else if (argument === "--manifest") options.manifestPath = path.resolve(argv[++index])
    else if (argument === "--json") options.json = true
    else if (argument === "--self-test") options.selfTest = true
  }
  return options
}

function runSelfTest() {
  const markers = scanSource("fixture.kt", "val connection = TransactionManager.current().connection")
  if (!markers.some((marker) => marker.kind === "transaction-connection")) throw new Error("self-test transaction marker failed")
  if (scanSource("fixture.kt", 'val guard = "DriverManager.getConnection"').length !== 0) throw new Error("self-test guard literal failed")
  if (validateSqlBindings("INSERT INTO t VALUES ($value)").length !== 1) throw new Error("self-test binding marker failed")
}

function main() {
  const options = parseArguments(process.argv.slice(2))
  if (options.selfTest) runSelfTest()
  const report = validateInventory(options)
  if (options.json) console.log(JSON.stringify(report, null, 2))
  else {
    console.log(`source files: ${report.sourceFileCount}`)
    console.log(`allowlisted files: ${report.allowlistedFileCount}`)
    console.log(`direct markers: ${report.directMarkerCount}`)
    console.log(`boundary markers: ${report.boundaryMarkerCount}`)
    console.log(`violations: ${report.violations.length}`)
    console.log(`stale entries: ${report.staleEntries.length}`)
    console.log(`binding violations: ${report.bindingViolations.length}`)
    if (!report.ok) console.error(JSON.stringify(report, null, 2))
  }
  process.exitCode = report.ok ? 0 : 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
