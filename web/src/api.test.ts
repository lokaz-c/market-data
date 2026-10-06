import { describe, expect, it, vi } from 'vitest';
import { ApiError, checkHealth, fetchAllPages, fetchBars } from './api';

function json(body: unknown, status = 200, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

describe('fetchAllPages', () => {
  it('follows nextAfter until it is null and concatenates rows', async () => {
    const fetchFn = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json({ bars: [{ date: 'a' }, { date: 'b' }], nextAfter: 'b' }))
      .mockResolvedValueOnce(json({ bars: [{ date: 'c' }], nextAfter: null }));

    const result = await fetchAllPages<{ bars: { date: string }[]; nextAfter: string | null }, { date: string }>(
      '/v1/bars/S001?from=2026-01-01&to=2026-10-02',
      (page) => page.bars,
      fetchFn,
    );

    expect(result.rows.map((r) => r.date)).toEqual(['a', 'b', 'c']);
    expect(fetchFn).toHaveBeenCalledTimes(2);
    expect(String(fetchFn.mock.calls[0][0])).toBe('/v1/bars/S001?from=2026-01-01&to=2026-10-02&limit=1000');
    expect(String(fetchFn.mock.calls[1][0])).toContain('&after=b');
  });
});

describe('errors', () => {
  it('surfaces the problem-detail message', async () => {
    const fetchFn = vi
      .fn<typeof fetch>()
      .mockResolvedValue(json({ title: 'Not Found', status: 404, detail: 'Unknown ticker: NOPE' }, 404, 'application/problem+json'));

    const error = await fetchBars('NOPE', '2026-01-01', '2026-10-02', fetchFn).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect((error as ApiError).message).toBe('Unknown ticker: NOPE');
  });
});

describe('checkHealth', () => {
  it('is true only for status UP', async () => {
    expect(await checkHealth(1000, vi.fn<typeof fetch>().mockResolvedValue(json({ status: 'UP' })))).toBe(true);
    expect(await checkHealth(1000, vi.fn<typeof fetch>().mockResolvedValue(json({ status: 'DOWN' }, 503)))).toBe(false);
    expect(await checkHealth(1000, vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')))).toBe(false);
  });
});
