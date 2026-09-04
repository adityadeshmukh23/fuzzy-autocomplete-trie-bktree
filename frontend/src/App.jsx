import { useEffect, useState } from 'react'
import * as api from './api.js'
import { formatCount } from './format.js'
import ErrorBoundary from './components/ErrorBoundary.jsx'
import SearchPage from './components/SearchPage.jsx'
import BenchmarkPage from './components/BenchmarkPage.jsx'

export default function App() {
  const [tab, setTab] = useState('search')
  const [index, setIndex] = useState(null)

  // A single tab-state variable rather than a router: the app has two views, and adding
  // react-router would also mean adding an SPA fallback controller on the Spring side if the
  // built frontend is ever served from the jar. Not worth it for two tabs.
  useEffect(() => {
    const controller = new AbortController()
    api.health(controller.signal).then(setIndex).catch(() => setIndex(null))
    return () => controller.abort()
  }, [])

  return (
    <div className="app">
      <header>
        <h1>Fuzzy autocomplete search</h1>
        <p className="tagline">
          A trie, a BK-tree, Levenshtein distance and a bounded heap — all hand-written — racing a
          brute-force scan over the same 100,000-word index.
        </p>
      </header>

      <nav role="tablist">
        <button role="tab" aria-selected={tab === 'search'} onClick={() => setTab('search')}>
          Search
        </button>
        <button role="tab" aria-selected={tab === 'benchmark'} onClick={() => setTab('benchmark')}>
          Benchmarks
        </button>
      </nav>

      <ErrorBoundary key={tab}>
        {tab === 'search' ? <SearchPage /> : <BenchmarkPage />}
      </ErrorBoundary>

      <footer>
        {index ? (
          <>
            Index: {formatCount(index.corpusSize)} terms · {index.indexStats} · built in{' '}
            {index.optimizedBuildMillis} ms
          </>
        ) : (
          <>
            API unreachable. Start the backend with <code>./mvnw spring-boot:run</code>, then
            reload.
          </>
        )}
      </footer>
    </div>
  )
}
