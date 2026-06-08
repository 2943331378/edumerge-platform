"use client";

import { useEffect, useRef, useState, useCallback } from "react";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { ChatRoom } from "./ChatRoom";

interface ChatDrawerProps {
  open: boolean;
  onClose: () => void;
  docUuid: string | null;
  docId: number | null;
  activityType: string | null;
  contextHint: string | null;
}

const ACTIVITY_LABELS: Record<string, string> = {
  notes: "学习笔记",
  mindmap: "思维导图",
  flashcards: "学习卡片",
  quiz: "测试题",
  flownote: "学习日志",
  knowledgegraph: "知识图谱",
};

export function ChatDrawer({ open, onClose, docUuid, docId, activityType, contextHint }: ChatDrawerProps) {
  // Lock body scroll when open
  useEffect(() => {
    if (open) {
      document.body.style.overflow = "hidden";
    } else {
      document.body.style.overflow = "";
    }
    return () => { document.body.style.overflow = ""; };
  }, [open]);

  // Swipe-to-dismiss for mobile bottom sheet
  const [dragOffset, setDragOffset] = useState(0);
  const touchStartY = useRef<number | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    touchStartY.current = e.touches[0].clientY;
    setDragOffset(0);
  }, []);

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (touchStartY.current === null) return;
    const delta = e.touches[0].clientY - touchStartY.current;
    // Only allow dragging down (positive delta)
    setDragOffset(Math.max(0, delta));
  }, []);

  const handleTouchEnd = useCallback(() => {
    if (touchStartY.current === null) return;
    touchStartY.current = null;
    // If dragged more than 100px or 30% of panel height, close
    const threshold = panelRef.current ? panelRef.current.offsetHeight * 0.3 : 100;
    if (dragOffset > threshold) {
      onClose();
    }
    setDragOffset(0);
  }, [dragOffset, onClose]);

  return (
    <>
      {/* Backdrop */}
      <div
        className={cn(
          "fixed inset-0 z-40 bg-black/40 backdrop-blur-sm transition-opacity duration-300",
          open ? "opacity-100" : "opacity-0 pointer-events-none",
        )}
        onClick={onClose}
      />

      {/* Drawer panel — mobile: bottom sheet, desktop: right slide-in */}
      <div
        ref={panelRef}
        className={cn(
          "fixed z-50 flex flex-col transition-transform duration-300 ease-in-out",
          "bg-white dark:bg-slate-900 shadow-2xl",
          // Desktop: right side panel
          "md:right-0 md:top-0 md:bottom-0 md:w-full md:max-w-lg md:border-l md:border-border",
          open ? "md:translate-x-0" : "md:translate-x-full",
          // Mobile: bottom sheet
          "max-md:bottom-0 max-md:left-0 max-md:right-0 max-md:h-[60vh] max-md:rounded-t-2xl max-md:border-t max-md:border-border",
          open ? "max-md:translate-y-0" : "max-md:translate-y-full",
        )}
        style={dragOffset > 0 ? { transform: `translateY(${dragOffset}px)`, transition: "none" } : undefined}
      >
        {/* Mobile drag handle — swipe down to close */}
        <div
          className="md:hidden flex justify-center pt-2 pb-1 shrink-0 touch-none"
          onTouchStart={handleTouchStart}
          onTouchMove={handleTouchMove}
          onTouchEnd={handleTouchEnd}
        >
          <div className="w-10 h-1 rounded-full bg-muted-foreground/20" />
        </div>

        {/* Header — context-aware */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-border shrink-0">
          <div>
            <h2 className="text-sm font-semibold text-foreground">
              {activityType && ACTIVITY_LABELS[activityType]
                ? `关于「${ACTIVITY_LABELS[activityType]}」的对话`
                : "AI 对话助手"}
            </h2>
            <p className="text-[11px] text-muted-foreground">
              {activityType && ACTIVITY_LABELS[activityType]
                ? `基于当前文档的${ACTIVITY_LABELS[activityType]}内容提问`
                : "基于当前文档提问"}
            </p>
          </div>
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8 rounded-lg"
            onClick={onClose}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Chat content */}
        <div className="flex-1 min-h-0">
          <ChatRoom docUuid={docUuid} docId={docId} activityType={activityType} contextHint={contextHint} />
        </div>
      </div>
    </>
  );
}
