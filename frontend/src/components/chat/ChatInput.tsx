"use client";

import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Send, Square } from "lucide-react";
import { useEffect, useRef, KeyboardEvent } from "react";

interface ChatInputProps {
  value: string;
  onChange: (v: string) => void;
  onSend: () => void;
  onStop: () => void;
  loading: boolean;
}

export function ChatInput({ value, onChange, onSend, onStop, loading }: ChatInputProps) {
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    ref.current?.focus();
  }, []);

  // Auto-resize: 随内容撑高, 最大不超过视口 30%
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    const maxH = Math.max(80, window.innerHeight * 0.3);
    el.style.height = Math.min(el.scrollHeight, maxH) + "px";
  }, [value]);

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      if (!loading && value.trim()) onSend();
    }
  };

  return (
    <div className="border-t bg-background px-4 py-3">
      <div className="mx-auto flex max-w-3xl items-end gap-2">
        <Textarea
          ref={ref}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="输入问题，Enter 发送..."
          rows={1}
          disabled={loading}
          className="min-h-[46px] resize-none rounded-2xl border-muted-foreground/15 bg-muted/30 focus-visible:ring-0 focus-visible:border-primary/30 transition-colors"
        />
        {loading ? (
          <Button size="icon" variant="outline" onClick={onStop} className="shrink-0 h-10 w-10 rounded-xl">
            <Square className="h-3.5 w-3.5" />
          </Button>
        ) : (
          <Button size="icon" onClick={onSend} disabled={!value.trim()} className="shrink-0 h-10 w-10 rounded-xl">
            <Send className="h-3.5 w-3.5" />
          </Button>
        )}
      </div>
    </div>
  );
}
