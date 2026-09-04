import { formatCount } from '../format.js'

/**
 * Splits a prefix match so the typed portion can be emphasised.
 *
 * Only meaningful for PREFIX results: the backend guarantees the normalised query is a literal
 * prefix of the word. A fuzzy match has no contiguous matching region to highlight -- the edits
 * are scattered -- so those show their edit distance instead of a fake highlight.
 */
function splitPrefix(word, query) {
  const typed = query.trim().toLowerCase()
  if (!typed || !word.toLowerCase().startsWith(typed)) return [null, word]
  return [word.slice(0, typed.length), word.slice(typed.length)]
}

export default function ResultList({ results, query, stale }) {
  if (results.length === 0) {
    return <div className="empty">No matches. Try a shorter prefix, or a word with a typo in it.</div>
  }

  return (
    <div className="results" style={{ opacity: stale ? 0.55 : 1 }}>
      {results.map((result) => {
        const isPrefix = result.matchType === 'PREFIX'
        const [matched, rest] = isPrefix ? splitPrefix(result.word, query) : [null, result.word]

        return (
          <div className="result" key={result.word}>
            <span className="word">
              {matched && <mark>{matched}</mark>}
              {rest}
            </span>

            <span className={`pill ${isPrefix ? 'prefix' : 'fuzzy'}`}>
              {isPrefix ? 'prefix' : `fuzzy · ${result.editDistance} edit${result.editDistance === 1 ? '' : 's'}`}
            </span>

            <span className="meta" title="relevance score · corpus frequency">
              {result.score.toFixed(3)} · {formatCount(result.weight)}
            </span>
          </div>
        )
      })}
    </div>
  )
}
