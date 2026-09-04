import { useState } from 'react'
import * as api from '../api.js'
import benchmarks from '../data/benchmark-data.json'
import { formatCount, formatMicros, formatSpeedup } from '../format.js'
import EngineComparison from './EngineComparison.jsx'
import SpeedupChart from './SpeedupChart.jsx'

const seriesFor = (group, variant, label) => ({
  label,
  points: benchmarks.series
    .filter((row) => row.group === group && row.variant === variant)
    .map((row) => ({ ...row, reliable: row.reliable !== false })),
})

const PREFIX_SERIES = [
  seriesFor('prefix', 1, '1-char query'),
  seriesFor('prefix', 3, '3-char query'),
  seriesFor('prefix', 6, '6-char query'),
]

const FUZZY_SERIES = [
  seriesFor('fuzzy', 1, 'edit distance 1'),
  seriesFor('fuzzy', 2, 'edit distance 2'),
]

const GROUP_LABELS = {
  'prefix-1': 'Prefix, 1-char query',
  'prefix-3': 'Prefix, 3-char query',
  'prefix-6': 'Prefix, 6-char query',
  'fuzzy-1': 'Fuzzy, edit distance 1',
  'fuzzy-2': 'Fuzzy, edit distance 2',
  'combined-0': 'End-to-end, full-word typo',
  'combined-1': 'End-to-end, 4-char prefix',
}

const flaggedCount = benchmarks.series.filter((row) => row.reliable === false).length

export default function BenchmarkPage() {
  const [query, setQuery] = useState('recieve')
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [running, setRunning] = useState(false)

  async function runComparison(event) {
    event.preventDefault()
    setRunning(true)
    setError(null)
    try {
      setResult(await api.compare(query.trim(), 10))
    } catch (cause) {
      setError(cause.message)
      setResult(null)
    } finally {
      setRunning(false)
    }
  }

  return (
    <>
      <section className="panel">
        <h2>Run it live</h2>
        <p>
          Both engines, same query, same index, timed inside the same JVM. This is the Phase 4
          benchmark in miniature — the numbers below it are the rigorous version.
        </p>
        <form className="inline-form" onSubmit={runComparison}>
          <input
            className="search-box"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            aria-label="Query to compare"
          />
          <button className="run" type="submit" disabled={running || !query.trim()}>
            {running ? 'Running…' : 'Compare'}
          </button>
        </form>
        {error && <div className="error" style={{ marginTop: 14 }}>{error}</div>}
        {result && result.results.length > 0 && <EngineComparison data={result} />}
        {result && result.results.length === 0 && (
          <div className="empty" style={{ marginTop: 14 }}>
            No matches for that query, so there is nothing to time.
          </div>
        )}
      </section>

      <section className="panel">
        <h2>Where the optimization stopped paying</h2>
        <p>
          Speedup against the brute-force scan as the corpus grows, from the JMH suite. Both axes
          are logarithmic. The dashed red line is break-even — below it, the naive scan is winning.
        </p>

        <div className="chart-grid">
          <SpeedupChart
            title="Prefix search — trie"
            series={PREFIX_SERIES}
            caption="Climbs steeply: the trie's cost barely moves as the corpus grows, so the gap widens with N."
          />
          <SpeedupChart
            title="Fuzzy search — BK-tree"
            series={FUZZY_SERIES}
            caption="Nearly flat, and edit distance 2 sits on or below break-even."
          />
        </div>

        <div className="callout">
          <strong>The asymmetry is the finding.</strong> The trie changes the complexity class —
          0.37 µs at 1,000 words and 2.56 µs at 100,000, against a scan that grows linearly, so
          prefix speedup keeps climbing with corpus size. The BK-tree only improves a constant
          factor, and at edit distance 2 not even that: it needs the <em>exact</em> distance at
          every visited node to compute its pruning window, so it pays a full O(m·n)
          dynamic-programming pass per node, while the linear scan gets to reject most of the
          corpus in O(1) on a length check alone.
        </div>

        {flaggedCount > 0 && (
          <div className="callout warn">
            <strong>{flaggedCount} of {benchmarks.series.length} measurements are flagged.</strong>{' '}
            Those had a JMH confidence interval wider than 25% of the measured value, from load on
            the machine that ran them. They are drawn as hollow points with dashed segments, and
            should be read as indicative only — not as results.
          </div>
        )}
      </section>

      <section className="panel">
        <h2>All measurements</h2>
        <p>{benchmarks.harness}. Generated {benchmarks.generated}.</p>
        <table className="numbers">
          <thead>
            <tr>
              <th>configuration</th>
              <th>corpus</th>
              <th>naive</th>
              <th>optimized</th>
              <th>speedup</th>
            </tr>
          </thead>
          <tbody>
            {benchmarks.series.map((row) => {
              const flagged = row.reliable === false
              return (
                <tr key={`${row.group}-${row.variant}-${row.datasetSize}`}>
                  <td className={flagged ? 'flagged' : undefined}>
                    {GROUP_LABELS[`${row.group}-${row.variant}`] ?? row.group}
                  </td>
                  <td className={flagged ? 'flagged' : undefined}>
                    {formatCount(row.datasetSize)}
                  </td>
                  <td className={flagged ? 'flagged' : undefined}>
                    {formatMicros(row.naiveMicros)}
                  </td>
                  <td className={flagged ? 'flagged' : undefined}>
                    {formatMicros(row.optimizedMicros)}
                  </td>
                  <td className={flagged ? 'flagged' : undefined}>
                    {flagged ? `${formatSpeedup(row.speedup)} ⚠` : <strong>{formatSpeedup(row.speedup)}</strong>}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </section>
    </>
  )
}
