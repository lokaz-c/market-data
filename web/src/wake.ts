// Free hosts stop idle services. While the API boots (about a minute on the free tier, plus JVM start-up),
// poll the readiness check and tell the user what is happening instead of showing a broken page.

export type ServerState =
  | { kind: 'checking' }
  | { kind: 'waking'; elapsedSeconds: number }
  | { kind: 'ready' }
  | { kind: 'unreachable'; elapsedSeconds: number };

export interface WaitOptions {
  check: () => Promise<boolean>;
  onState: (state: ServerState) => void;
  intervalMs?: number;
  giveUpAfterMs?: number;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
  signal?: AbortSignal;
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Resolves true once the health check passes, or false after `giveUpAfterMs`. Reports 'checking' first,
 * 'waking' with elapsed time after the first failed check, then 'ready' or 'unreachable'.
 */
export async function waitForServer({
  check,
  onState,
  intervalMs = 3000,
  giveUpAfterMs = 180_000,
  now = Date.now,
  sleep = defaultSleep,
  signal,
}: WaitOptions): Promise<boolean> {
  const started = now();
  onState({ kind: 'checking' });
  for (;;) {
    if (signal?.aborted) return false;
    if (await check()) {
      onState({ kind: 'ready' });
      return true;
    }
    const elapsed = now() - started;
    const elapsedSeconds = Math.round(elapsed / 1000);
    if (elapsed >= giveUpAfterMs) {
      onState({ kind: 'unreachable', elapsedSeconds });
      return false;
    }
    onState({ kind: 'waking', elapsedSeconds });
    await sleep(intervalMs);
  }
}
