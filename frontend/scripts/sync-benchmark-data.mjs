// Copies the benchmark results into the frontend so Vite can bundle them.
//
// The numbers live in docs/ because that is where BenchmarkSuite writes them and where the README
// reads them from. Rather than reaching outside the Vite root at build time, they are copied in
// explicitly -- which also makes the dependency visible: re-run the benchmark, re-run this.
import { copyFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const source = resolve(here, '../../docs/benchmark-data.json')
const target = resolve(here, '../src/data/benchmark-data.json')

if (!existsSync(source)) {
  console.error(`\nBenchmark data not found at ${source}`)
  console.error('Generate it with:  ./mvnw test-compile exec:exec@benchmark\n')
  process.exit(1)
}

mkdirSync(dirname(target), { recursive: true })
copyFileSync(source, target)
console.log(`synced benchmark data -> ${target.replace(process.cwd(), '.')}`)
