import { formatMicros, formatSpeedup } from '../format.js'

/**
 * The headline panel: the same query, run through both engines, timed inside the same JVM on the
 * same index.
 *
 * Timing happens on the server rather than in the browser on purpose. If the page called both
 * endpoints and timed them with `performance.now()`, it would be measuring the network round-trip
 * -- milliseconds either way -- which would completely swamp the difference between a 5 µs trie
 * descent and a 550 µs linear scan. The whole comparison would show two identical bars.
 */
export default function EngineComparison({ data, stale }) {
  const { optimizedMicros, naiveMicros, speedup, identicalResults } = data
  const scale = Math.max(optimizedMicros, naiveMicros, 0.001)
  const optimizedWins = speedup >= 1

  return (
    <section className="comparison" style={{ opacity: stale ? 0.55 : 1 }}>
      <div className="comparison-head">
        <h2>Same query, both engines, timed server-side</h2>
        <div className="speedup">
          {formatSpeedup(speedup)}
          <small>{optimizedWins ? 'faster' : 'slower than naive'}</small>
        </div>
      </div>

      <div className="bar-row">
        <span className="name">trie + BK-tree</span>
        <div className="bar-track">
          <div
            className="bar-fill optimized"
            style={{ width: `${(optimizedMicros / scale) * 100}%` }}
          />
        </div>
        <span className="value">{formatMicros(optimizedMicros)}</span>
      </div>

      <div className="bar-row">
        <span className="name">brute-force scan</span>
        <div className="bar-track">
          <div className="bar-fill naive" style={{ width: `${(naiveMicros / scale) * 100}%` }} />
        </div>
        <span className="value">{formatMicros(naiveMicros)}</span>
      </div>

      <p className="comparison-note">
        {identicalResults ? (
          <span className="badge identical">✓ identical results</span>
        ) : (
          <span className="badge diverged">✗ results differ</span>
        )}{' '}
        Both engines return the same ranked list, so the speed difference is a pure win rather
        than a trade against quality. Timings are the best of 3 in-process runs, excluding
        network and JSON serialisation.
      </p>
    </section>
  )
}
