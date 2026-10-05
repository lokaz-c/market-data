// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WakeScreen } from './WakeScreen';

afterEach(cleanup);

describe('WakeScreen', () => {
  it('explains the cold start and shows how long it has waited', () => {
    render(<WakeScreen state={{ kind: 'waking', elapsedSeconds: 12 }} onRetry={() => {}} />);
    expect(screen.getByText('Server waking up')).toBeTruthy();
    expect(screen.getByText(/Waiting 12 s/)).toBeTruthy();
    expect(screen.getByRole('progressbar')).toBeTruthy();
  });

  it('offers a retry once the server is unreachable', () => {
    const onRetry = vi.fn();
    render(<WakeScreen state={{ kind: 'unreachable', elapsedSeconds: 180 }} onRetry={onRetry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('renders nothing when the server is ready', () => {
    const { container } = render(<WakeScreen state={{ kind: 'ready' }} onRetry={() => {}} />);
    expect(container.innerHTML).toBe('');
  });
});
