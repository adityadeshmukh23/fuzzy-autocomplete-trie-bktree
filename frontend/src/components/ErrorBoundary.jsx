import { Component } from 'react'

/**
 * Stops one bad render from blanking the entire page.
 *
 * React unmounts the whole tree on an uncaught render error, so a single undefined field --
 * exactly what happened when a stale response was read with the wrong shape -- takes the app
 * down to a white screen with nothing but a console message. For a demo someone else is going to
 * open, failing visibly and locally beats failing invisibly and globally.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    console.error('render error', error, info)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className="error">
        <strong>Something broke rendering this view.</strong>
        <div style={{ marginTop: 6, fontFamily: 'var(--mono)', fontSize: 12 }}>
          {this.state.error.message}
        </div>
        <button
          className="run"
          style={{ marginTop: 12 }}
          onClick={() => this.setState({ error: null })}
        >
          Try again
        </button>
      </div>
    )
  }
}
