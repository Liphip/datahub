import { useEffect, useState } from 'react';

/**
 * Current time in epoch milliseconds, refreshed every `intervalMs`.
 * Keeps render pure (no Date.now() during render) while relative times stay current.
 */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);
  return now;
}
