"use client";

import { useEffect, useCallback, useRef } from "react";
import { cn } from "@/lib/utils";
import { SHORTCUT_GROUPS } from "@/lib/shortcuts";
import { Keyboard, X } from "lucide-react";

interface ShortcutsHelpProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function ShortcutsHelp({ open, onOpenChange }: ShortcutsHelpProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  // Close on Escape
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === "Escape" && open) {
        e.preventDefault();
        onOpenChange(false);
      }
    },
    [open, onOpenChange],
  );

  useEffect(() => {
    if (open) {
      window.addEventListener("keydown", handleKeyDown);
      return () => window.removeEventListener("keydown", handleKeyDown);
    }
  }, [open, handleKeyDown]);

  // Lock body scroll when open
  useEffect(() => {
    if (open) {
      document.body.style.overflow = "hidden";
      return () => {
        document.body.style.overflow = "";
      };
    }
  }, [open]);

  // Focus trap: focus close button on mount, trap Tab/Shift+Tab
  useEffect(() => {
    if (!open) return;
    closeBtnRef.current?.focus();

    const handleTab = (e: KeyboardEvent) => {
      if (e.key !== "Tab" || !panelRef.current) return;
      const focusable = panelRef.current.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", handleTab);
    return () => window.removeEventListener("keydown", handleTab);
  }, [open]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-label="键盘快捷键"
    >
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm animate-in fade-in duration-200"
        onClick={() => onOpenChange(false)}
      />

      {/* Panel */}
      <div
        ref={panelRef}
        className={cn(
          "relative z-10 w-full max-w-lg rounded-2xl border border-border/60",
          "bg-card shadow-2xl animate-in fade-in zoom-in-95 duration-200",
          "max-h-[85vh] flex flex-col overflow-hidden",
        )}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-border/50">
          <div className="flex items-center gap-2">
            <Keyboard className="h-4 w-4 text-muted-foreground" />
            <h2 className="text-sm font-semibold text-foreground">键盘快捷键</h2>
          </div>
          <button
            ref={closeBtnRef}
            type="button"
            onClick={() => onOpenChange(false)}
            aria-label="关闭"
            className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-5">
          {SHORTCUT_GROUPS.map((group) => (
            <div key={group.title}>
              <h3 className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60 mb-2">
                {group.title}
              </h3>
              <div className="space-y-1">
                {group.shortcuts.map((s) => (
                  <div
                    key={s.keys + s.description}
                    className="flex items-center justify-between rounded-lg px-3 py-1.5 hover:bg-muted/50 transition-colors"
                  >
                    <span className="text-xs text-foreground/80">{s.description}</span>
                    <ShortcutKey keys={s.keys} />
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>

        {/* Footer */}
        <div className="px-5 py-3 border-t border-border/50 text-center">
          <p className="text-[11px] text-muted-foreground/40">
            按 <kbd className="px-1 py-0.5 rounded bg-muted text-[11px] font-mono">?</kbd> 或{" "}
            <kbd className="px-1 py-0.5 rounded bg-muted text-[11px] font-mono">Esc</kbd> 关闭
          </p>
        </div>
      </div>
    </div>
  );
}

/** Render a shortcut key string as styled kbd elements */
function ShortcutKey({ keys }: { keys: string }) {
  // Split by " + " but keep multi-char tokens like "~"
  const parts = keys.split(" + ");
  return (
    <span className="flex items-center gap-0.5 shrink-0 ml-3">
      {parts.map((part, i) => (
        <span key={i} className="flex items-center gap-0.5">
          {i > 0 && <span className="text-[11px] text-muted-foreground/40 mx-0.5">+</span>}
          <kbd
            className={cn(
              "inline-flex items-center justify-center min-w-[22px] h-[22px] px-1.5",
              "rounded-md border border-border/60 bg-muted/60",
              "text-[11px] font-mono font-medium text-foreground/70",
              "shadow-[0_1px_0_0_rgba(0,0,0,0.08)]",
              "dark:shadow-[0_1px_0_0_rgba(255,255,255,0.05)]",
            )}
          >
            {part}
          </kbd>
        </span>
      ))}
    </span>
  );
}

/** Small trigger button for the header */
export function ShortcutsButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label="键盘快捷键"
      className={cn(
        "flex h-8 w-8 items-center justify-center rounded-lg",
        "text-muted-foreground hover:text-foreground hover:bg-muted transition-colors",
      )}
      title="键盘快捷键 (?)"
    >
      <Keyboard className="h-4 w-4" />
    </button>
  );
}
