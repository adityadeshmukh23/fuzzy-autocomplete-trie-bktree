import { useEffect, useRef, useState } from 'react'
import * as api from '../api.js'
import EngineComparison from './EngineComparison.jsx'
import ResultList from './ResultList.jsx'

const DEBOUNCE_MS = 140
const LIMIT = 10

const EXAMPLES = ['aple', 'recieve', 'sear', 'definately', 'compu', 'acommodation']

export default function SearchPage() {
  const [query, setQuery] = useState('')
  const [compareMode, setCompareMode] = useState(true)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const inputRef = useRef(null)

  useEffect(() => {
    const trimmed = query.trim()
    if (!trimmed) {
      setResult(null)
      setError(null)
      setLoading(false)
      return
    }

    // One controller per keystroke. The cleanup both cancels the pending debounce timer and
    // aborts any request already in flight, which is what keeps a slow earlier response from
    // landing after a faster later one and painting stale results.
    const controller = new AbortController()
    setLoading(true)

    const timer = setTimeout(async () => {
      try {
        const data = compareMode
          ? await api.compare(trimmed, LIMIT, controller.signal)
          : await api.search(trimmed, LIMIT, controller.signal)
        setResult({ ...data, mode: compareMode ? 'compare' : 'search' })
        setError(null)
      } catch (cause) {
        if (cause.name === 'AbortError') return
        setError(cause.message)
        setResult(null)
      } finally {
        setLoading(false)
      }
    }, DEBOUNCE_MS)

    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [query, compareMode])

  const showComparison = result && result.mode === 'compare' && result.results.length > 0

  return (
    <>
      <input
        ref={inputRef}
        className="search-box"
        type="search"
        value={query}
        placeholder="Start typing — try a misspelling"
        onChange={(event) => setQuery(event.target.value)}
        autoFocus
        autoComplete="off"
        spellCheck="false"
        aria-label="Search query"
      />

      <div className="search-controls">
        <label>
          <input
            type="checkbox"
            checked={compareMode}
            onChange={(event) => setCompareMode(event.target.checked)}
          />
          Run the brute-force engine alongside
        </label>

        <span>
          try:{' '}
          {EXAMPLES.map((example, index) => (
            <span key={example}>
              {index > 0 && ', '}
              <a
                href="#"
                onClick={(event) => {
                  event.preventDefault()
                  setQuery(example)
                  inputRef.current?.focus()
                }}
              >
                {example}
              </a>
            </span>
          ))}
        </span>
      </div>

      {error && <div className="error" style={{ marginTop: 20 }}>{error}</div>}

      {showComparison && <EngineComparison data={result} stale={loading} />}

      {result && !error && (
        <div style={{ marginTop: showComparison ? 0 : 20 }}>
          <ResultList results={result.results} query={query} stale={loading} />
          {/*
            Gate on result.mode, not on the compareMode checkbox. Toggling the checkbox
            re-renders immediately while the previous response is still in state, so for one
            frame `compareMode` says "search" but `result` is still a compare payload with no
            latencyMicros field. Rendering from what the data IS, rather than from what the
            control currently says, is the only version that cannot desync.
          */}
          {result.mode === 'search' && result.results.length > 0 && (
            <p className="comparison-note" style={{ marginTop: 10 }}>
              Optimized engine only — {result.latencyMicros.toFixed(1)} µs server-side. Tick the
              box above to race it against the brute-force scan.
            </p>
          )}
        </div>
      )}

      {!result && !error && !query.trim() && (
        <div className="empty" style={{ marginTop: 20 }}>
          Type to search 100,000 English words ranked by corpus frequency. Prefix matches come
          from the trie; typos are caught by the BK-tree.
        </div>
      )}
    </>
  )
}
