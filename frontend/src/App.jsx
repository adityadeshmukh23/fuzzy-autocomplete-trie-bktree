import { useState } from 'react'
import { formatCount } from './format.js'
import useApiStatus from './useApiStatus.js'
import ErrorBoundary from './components/ErrorBoundary.jsx'
import SearchPage from './components/SearchPage.jsx'
import BenchmarkPage from './components/BenchmarkPage.jsx'

export default function App() {
  // A single tab-state variable rather than a router: the app has two views, and adding
  // react-router would also mean adding an SPA fallback controller on the Spring side, since the
  // built frontend is served from the jar in production.
  const [tab, setTab] = useState('search')
  const { status, index } = useApiStatus()

  return (
    <div className="app">
      <header>
        <h1>Fuzzy autocomplete search</h1>
        <p className="tagline">
          A trie, a BK-tree, Levenshtein distance and a bounded heap — all hand-written — racing a
          brute-force scan over the same 100,000-word index.
        </p>
      </header>

      {status === 'connecting' && (
        <div className="waking">
          <span className="spinner" aria-hidden="true" />
          Waking the server. Free hosting stops the service when idle, so the first request after
          a quiet spell waits for a container start and a JVM boot — usually 30–60 seconds.
        </div>
      )}

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
        {status === 'up' && index && (
          <>
            Index: {formatCount(index.corpusSize)} terms · {index.indexStats} · built in{' '}
            {index.optimizedBuildMillis} ms
          </>
        )}
        {status === 'connecting' && <>Connecting to the API…</>}
        {status === 'down' && (
          <>
            API unreachable. Running locally? Start the backend with{' '}
            <code>./mvnw spring-boot:run</code>, then reload.
          </>
        )}
      </footer>
    </div>
  )
}
