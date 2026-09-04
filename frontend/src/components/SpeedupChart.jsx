import { formatSpeedup } from '../format.js'

/**
 * Speedup versus corpus size, log-log, hand-rolled SVG.
 *
 * No charting library: two small line charts do not justify a dependency in a project whose
 * point is the backend.
 *
 * The design carries one specific argument. A horizontal rule sits at 1x -- below it, the
 * brute-force scan is winning. Prefix search climbs steeply away from that line as the corpus
 * grows, because the trie changed the complexity class. Fuzzy search hugs it, because the
 * BK-tree only improved a constant factor. That contrast is the honest finding, so the chart is
 * built to show it rather than to flatter the result.
 *
 * Measurements whose confidence interval was too wide are drawn hollow, with a dashed approach,
 * so a contention artifact can never read as a real data point.
 */

const WIDTH = 380
const HEIGHT = 240
const PAD = { top: 30, right: 14, bottom: 34, left: 46 }

const PLOT_W = WIDTH - PAD.left - PAD.right
const PLOT_H = HEIGHT - PAD.top - PAD.bottom

const SERIES_COLORS = ['#1f7a6f', '#1f6feb', '#a15c00']

export default function SpeedupChart({ title, series, caption }) {
  const points = series.flatMap((line) => line.points)
  if (points.length === 0) return null

  const sizes = [...new Set(points.map((p) => p.datasetSize))].sort((a, b) => a - b)
  const speedups = points.map((p) => p.speedup)

  // Always include 1x in the visible range: the break-even line is the reference the whole
  // chart is read against, so clipping it off would hide the point.
  const minLog = Math.floor(Math.log10(Math.min(...speedups, 1)))
  const maxLog = Math.ceil(Math.log10(Math.max(...speedups, 1)))
  const logSpan = Math.max(maxLog - minLog, 1)

  const xOf = (size) => {
    const lo = Math.log10(sizes[0])
    const hi = Math.log10(sizes[sizes.length - 1])
    return PAD.left + ((Math.log10(size) - lo) / (hi - lo)) * PLOT_W
  }
  const yOf = (speedup) =>
    PAD.top + PLOT_H - ((Math.log10(Math.max(speedup, 10 ** minLog)) - minLog) / logSpan) * PLOT_H

  const decades = []
  for (let d = minLog; d <= maxLog; d++) decades.push(d)

  return (
    <figure style={{ margin: 0 }}>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} width="100%" role="img" aria-label={title}>
        <text x={4} y={12} fontSize="12" fontWeight="600" fill="currentColor">
          {title}
        </text>

        {decades.map((decade) => (
          <g key={decade}>
            <line
              x1={PAD.left}
              y1={yOf(10 ** decade)}
              x2={WIDTH - PAD.right}
              y2={yOf(10 ** decade)}
              stroke="currentColor"
              strokeWidth="0.5"
              opacity="0.18"
            />
            <text
              x={PAD.left - 6}
              y={yOf(10 ** decade) + 3}
              textAnchor="end"
              fontSize="10"
              fill="currentColor"
              opacity="0.6"
            >
              {10 ** decade >= 1 ? `${10 ** decade}×` : `${10 ** decade}×`}
            </text>
          </g>
        ))}

        {/* Break-even: below this line the brute-force scan is faster. */}
        <line
          x1={PAD.left}
          y1={yOf(1)}
          x2={WIDTH - PAD.right}
          y2={yOf(1)}
          stroke="#c0384c"
          strokeWidth="1.2"
          strokeDasharray="4 3"
          opacity="0.85"
        />
        <text x={WIDTH - PAD.right} y={yOf(1) - 5} textAnchor="end" fontSize="9.5" fill="#c0384c">
          break-even
        </text>

        {sizes.map((size) => (
          <text
            key={size}
            x={xOf(size)}
            y={HEIGHT - 14}
            textAnchor="middle"
            fontSize="10"
            fill="currentColor"
            opacity="0.6"
          >
            {size >= 1000 ? `${size / 1000}k` : size}
          </text>
        ))}
        <text
          x={PAD.left + PLOT_W / 2}
          y={HEIGHT - 2}
          textAnchor="middle"
          fontSize="10"
          fill="currentColor"
          opacity="0.6"
        >
          corpus size
        </text>

        {series.map((line, index) => {
          const color = SERIES_COLORS[index % SERIES_COLORS.length]
          const ordered = [...line.points].sort((a, b) => a.datasetSize - b.datasetSize)

          return (
            <g key={line.label}>
              {ordered.slice(1).map((point, i) => {
                const previous = ordered[i]
                const shaky = !point.reliable || !previous.reliable
                return (
                  <line
                    key={point.datasetSize}
                    x1={xOf(previous.datasetSize)}
                    y1={yOf(previous.speedup)}
                    x2={xOf(point.datasetSize)}
                    y2={yOf(point.speedup)}
                    stroke={color}
                    strokeWidth="2"
                    strokeDasharray={shaky ? '3 3' : undefined}
                    opacity={shaky ? 0.5 : 1}
                  />
                )
              })}
              {ordered.map((point) => (
                <circle
                  key={point.datasetSize}
                  cx={xOf(point.datasetSize)}
                  cy={yOf(point.speedup)}
                  r="3.2"
                  fill={point.reliable ? color : 'var(--bg)'}
                  stroke={color}
                  strokeWidth="1.5"
                >
                  <title>
                    {line.label} @ {point.datasetSize.toLocaleString('en-US')}:{' '}
                    {formatSpeedup(point.speedup)}
                    {point.reliable ? '' : ' (unreliable — machine contention)'}
                  </title>
                </circle>
              ))}
            </g>
          )
        })}
      </svg>

      <div className="legend">
        {series.map((line, index) => (
          <span key={line.label}>
            <i style={{ background: SERIES_COLORS[index % SERIES_COLORS.length] }} />
            {line.label}
          </span>
        ))}
      </div>
      {caption && <figcaption className="comparison-note">{caption}</figcaption>}
    </figure>
  )
}
