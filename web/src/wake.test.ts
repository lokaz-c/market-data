import { describe, expect, it } from 'vitest';
import { waitForServer, type ServerState } from './wake';

/** A fake clock: sleep() advances time instantly. */
function fakeTime() {
  let t = 0;
  return { now: () => t, sleep: async (ms: number) => void (t += ms) };
}

describe('waitForServer', () => {
  it('goes straight to ready when the first check passes', async () => {
    const states: ServerState[] = [];
    const ok = await waitForServer({ check: async () => true, onState: (s) => states.push(s), ...fakeTime() });
    expect(ok).toBe(true);
    expect(states).toEqual([{ kind: 'checking' }, { kind: 'ready' }]);
  });

  it('reports waking with elapsed time until the server answers', async () => {
    const states: ServerState[] = [];
    let calls = 0;
    const ok = await waitForServer({
      check: async () => ++calls >= 4,
      onState: (s) => states.push(s),
      intervalMs: 3000,
      ...fakeTime(),
    });
    expect(ok).toBe(true);
    expect(states).toEqual([
      { kind: 'checking' },
      { kind: 'waking', elapsedSeconds: 0 },
      { kind: 'waking', elapsedSeconds: 3 },
      { kind: 'waking', elapsedSeconds: 6 },
      { kind: 'ready' },
    ]);
  });

  it('gives up after the limit', async () => {
    const states: ServerState[] = [];
    const ok = await waitForServer({
      check: async () => false,
      onState: (s) => states.push(s),
      intervalMs: 10_000,
      giveUpAfterMs: 30_000,
      ...fakeTime(),
    });
    expect(ok).toBe(false);
    expect(states.at(-1)).toEqual({ kind: 'unreachable', elapsedSeconds: 30 });
  });

  it('stops when aborted', async () => {
    const controller = new AbortController();
    controller.abort();
    const ok = await waitForServer({ check: async () => true, onState: () => {}, signal: controller.signal, ...fakeTime() });
    expect(ok).toBe(false);
  });
});
