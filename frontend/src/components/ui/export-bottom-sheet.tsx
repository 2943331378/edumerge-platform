"use client";

import { Download } from "lucide-react";

export interface ExportOption {
  icon: React.ComponentType<{ className?: string }>;
  iconColor?: string;
  title: string;
  description: string;
  onClick: () => void;
}

interface Props {
  open: boolean;
  onClose: () => void;
  title?: string;
  options: ExportOption[];
}

export function ExportBottomSheet({ open, onClose, title = "选择导出格式", options }: Props) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-black/40" />
      <div
        className="relative w-full max-w-md rounded-t-2xl bg-background p-4 pb-8 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mx-auto mb-4 h-1 w-10 rounded-full bg-muted-foreground/20" />
        <p className="text-sm font-medium text-foreground/80 mb-3">{title}</p>
        <div className="space-y-2">
          {options.map((opt) => {
            const Icon = opt.icon;
            return (
              <button
                key={opt.title}
                type="button"
                onClick={() => { opt.onClick(); onClose(); }}
                className="flex w-full items-center gap-3 rounded-xl border border-border/60 px-4 py-3.5 text-left text-sm hover:bg-muted/40 active:bg-muted/60 transition-colors min-h-[44px]"
              >
                <Icon className={`h-4 w-4 shrink-0 ${opt.iconColor ?? "text-muted-foreground"}`} />
                <div>
                  <p className="font-medium text-foreground/85">{opt.title}</p>
                  <p className="text-[11px] text-muted-foreground/60">{opt.description}</p>
                </div>
              </button>
            );
          })}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="mt-3 w-full rounded-xl border border-border/60 py-2.5 text-sm text-muted-foreground hover:bg-muted/40 transition-colors min-h-[44px]"
        >
          取消
        </button>
      </div>
    </div>
  );
}
