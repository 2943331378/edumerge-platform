"use client";

/**
 * localStorage helper for persisting flashcard / quiz progress.
 *
 * Key format: edumerge_<type>_progress_<deckId>
 * Each entry is wrapped with a timestamp so stale entries (>7 days) are cleaned up.
 */

interface Wrapped<T> {
  data: T;
  ts: number;       // Date.now() when saved
  version: number;  // bump to invalidate old entries
}

const CURRENT_VERSION = 1;
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000; // 7 days

/** Save progress to localStorage (no-op on server or quota errors). */
export function saveProgress<T>(key: string, data: T): void {
  try {
    const wrapped: Wrapped<T> = { data, ts: Date.now(), version: CURRENT_VERSION };
    localStorage.setItem(key, JSON.stringify(wrapped));
  } catch {
    // quota exceeded or SSR — silently ignore
  }
}

/**
 * Load progress from localStorage.
 * Returns `null` if missing, expired, wrong version, or SSR.
 */
export function loadProgress<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const wrapped: Wrapped<T> = JSON.parse(raw);
    if (wrapped.version !== CURRENT_VERSION) return null;
    if (Date.now() - wrapped.ts > MAX_AGE_MS) {
      localStorage.removeItem(key);
      return null;
    }
    return wrapped.data;
  } catch {
    return null;
  }
}

/** Remove a specific progress entry. */
export function clearProgress(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}

/**
 * Scan localStorage and remove any edumerge progress entries older than MAX_AGE.
 * Safe to call on app mount — it only touches keys with the known prefix.
 */
export function cleanStaleEntries(): void {
  try {
    const keysToRemove: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.startsWith("edumerge_") && k.includes("_progress_")) {
        // broader match — also covers "edumerge_flashcard_progress_" etc.
        const raw = localStorage.getItem(k);
        if (!raw) continue;
        try {
          const wrapped: Wrapped<unknown> = JSON.parse(raw);
          if (Date.now() - wrapped.ts > MAX_AGE_MS) {
            keysToRemove.push(k);
          }
        } catch {
          keysToRemove.push(k); // corrupt entry
        }
      }
    }
    keysToRemove.forEach((k) => localStorage.removeItem(k));
  } catch {
    // ignore
  }
}

// ── Convenience key builders ──────────────────────────────────

export function flashcardProgressKey(deckId: number): string {
  return `edumerge_flashcard_progress_${deckId}`;
}

export function quizProgressKey(deckId: number): string {
  return `edumerge_quiz_progress_${deckId}`;
}
