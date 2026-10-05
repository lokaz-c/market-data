import type { ServerState } from '../wake';

interface Props {
  state: ServerState;
  onRetry: () => void;
}

/** Shown while the API is not answering yet; free hosts sleep after a period without traffic. */
export function WakeScreen({ state, onRetry }: Props) {
  if (state.kind === 'ready') return null;
  return (
    <section className="wake" aria-live="polite">
      <p className="label">{state.kind === 'unreachable' ? 'Server unreachable' : 'Server waking up'}</p>
      {state.kind === 'checking' && <h2>Connecting to the API.</h2>}
      {state.kind === 'waking' && (
        <>
          <h2>Starting the API. This usually takes about a minute.</h2>
          <p className="muted">
            The demo runs on a free host that stops the service when nobody is using it. Waiting {state.elapsedSeconds} s.
          </p>
          <div className="wake-bar" role="progressbar" aria-label="Waiting for the server" />
        </>
      )}
      {state.kind === 'unreachable' && (
        <>
          <h2>The API did not respond after {state.elapsedSeconds} s.</h2>
          <button type="button" className="button" onClick={onRetry}>
            Try again
          </button>
        </>
      )}
    </section>
  );
}
