"use client";

import { useState, ChangeEvent, useRef } from "react";
import Image from "next/image";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { ThemeToggle } from "@/components/theme-toggle";
import {
  MessageSquare,
  NotebookText,
  Layers,
  HelpCircle,
  GitFork,
  Upload,
  FileText,
  PanelLeftClose,
  PanelLeft,
} from "lucide-react";

export type NavTab = "chat" | "notes" | "flashcards" | "quizzes" | "mindmap";

export interface UploadedDoc {
  id: string;
  sessionId: number;
  name: string;
  size: number;
  status: "uploading" | "done" | "error";
  chunks?: number;
}

interface AppSidebarProps {
  activeTab: NavTab;
  onTabChange: (tab: NavTab) => void;
  documents: UploadedDoc[];
  activeSessionId: number | null;
  onSelectSession: (sessionId: number) => void;
  onUpload: (file: File) => Promise<void>;
  collapsed: boolean;
  onToggleCollapse: () => void;
}

const navItems: { id: NavTab; label: string; icon: typeof MessageSquare }[] = [
  { id: "chat", label: "对话", icon: MessageSquare },
  { id: "notes", label: "笔记", icon: NotebookText },
  { id: "flashcards", label: "卡片", icon: Layers },
  { id: "quizzes", label: "测试", icon: HelpCircle },
  { id: "mindmap", label: "导图", icon: GitFork },
];

export function AppSidebar({
  activeTab,
  onTabChange,
  documents,
  activeSessionId,
  onSelectSession,
  onUpload,
  collapsed,
  onToggleCollapse,
}: AppSidebarProps) {
  const [dragging, setDragging] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const handleFile = (file: File | null) => {
    if (!file) return;
    const extension = file.name.split(".").pop()?.toLowerCase();
    if (extension && ["pdf", "doc", "docx", "ppt", "pptx", "txt"].includes(extension)) {
      onUpload(file);
    }
  };

  return (
    <aside
      className={cn(
        "relative flex h-full flex-col shrink-0 overflow-hidden border-r border-border",
        "bg-white/70 dark:bg-slate-900/80 backdrop-blur-xl",
        "transition-all duration-300 ease-in-out",
        collapsed ? "w-[64px]" : "w-[260px]",
      )}
    >
      {/* 折叠按钮 — 侧边栏下半部分右侧边缘 */}
      <button
        onClick={onToggleCollapse}
        className={cn(
          "absolute -right-3.5 bottom-20 z-50 flex h-9 w-9 items-center justify-center rounded-full",
          "bg-white dark:bg-slate-800 border border-border shadow-lg",
          "hover:shadow-xl hover:scale-110",
          "transition-all duration-300",
        )}
        title={collapsed ? "展开侧边栏" : "收起侧边栏"}
      >
        {collapsed ? (
          <PanelLeft className="h-4 w-4" />
        ) : (
          <PanelLeftClose className="h-4 w-4" />
        )}
      </button>

      {/* Logo 区域 */}
      <div
        className={cn(
          "flex items-center gap-3 border-b border-white/10 transition-all duration-300",
          collapsed ? "flex-col justify-center px-2 py-3" : "px-4 py-4",
        )}
      >
        <div
          className={cn(
            "flex items-center justify-center rounded-2xl overflow-hidden shrink-0 logo-filter transition-all duration-300",
            collapsed ? "h-10 w-10" : "h-16 w-16",
          )}
        >
          <Image
            src="/logo_converted.svg"
            alt="EduMerge"
            width={116}
            height={117}
            priority
            className="h-full w-auto"
          />
        </div>
        {!collapsed && (
          <span className="text-[15px] font-bold tracking-tight text-foreground/90 select-none">
            EduMerge
          </span>
        )}
      </div>

      {/* Navigation */}
      <nav className={cn("px-3 py-4 space-y-1", collapsed && "px-1.5")}>
        {navItems.map((item) => {
          const active = activeTab === item.id;
          return (
            <Button
              key={item.id}
              variant="ghost"
              className={cn(
                "w-full justify-start gap-3 rounded-xl transition-all duration-200",
                collapsed ? "h-10 px-0 justify-center" : "h-9 px-3",
                active
                  ? [
                      "bg-gradient-to-r from-primary/15 via-primary/10 to-transparent",
                      "text-primary shadow-[0_0_12px_-4px] shadow-primary/20",
                      "ring-1 ring-primary/20",
                      "font-medium",
                    ]
                  : "text-muted-foreground hover:text-foreground hover:bg-white/10 dark:hover:bg-white/5",
              )}
              onClick={() => onTabChange(item.id)}
              title={collapsed ? item.label : undefined}
            >
              <item.icon
                className={cn("h-4 w-4 shrink-0", active && "text-primary")}
              />
              {!collapsed && <span className="text-[13px]">{item.label}</span>}
            </Button>
          );
        })}
      </nav>

      {/* Upload zone + Document list */}
      {!collapsed && (
        <>
          <div className="px-3 pt-1 pb-2">
            <div
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(e) => {
                e.preventDefault();
                setDragging(false);
                handleFile(e.dataTransfer.files[0] ?? null);
              }}
              onClick={() => fileRef.current?.click()}
              className={cn(
                "group flex items-center justify-center gap-2 rounded-xl border-2 border-dashed p-2.5 text-[11px] font-medium cursor-pointer transition-all",
                "backdrop-blur-sm",
                dragging
                  ? "border-primary bg-primary/10 text-primary scale-[1.02]"
                  : "border-white/20 text-muted-foreground hover:border-white/40 hover:text-foreground hover:bg-white/5",
              )}
            >
              <Upload
                className={cn(
                  "h-3.5 w-3.5 transition-colors",
                  dragging && "text-primary",
                )}
              />
              上传资料
              <input
                ref={fileRef}
                type="file"
                accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation"
                onChange={(e: ChangeEvent<HTMLInputElement>) => {
                  handleFile(e.target.files?.[0] ?? null);
                  if (fileRef.current) fileRef.current.value = "";
                }}
                className="hidden"
              />
            </div>
          </div>

          {/* Document list */}
          <ScrollArea className="flex-1">
            <div className="px-2 pb-2 space-y-0.5">
              {documents.length === 0 && (
                <p className="px-3 py-4 text-center text-[11px] text-muted-foreground/60">
                  尚无文档
                </p>
              )}
              {documents.map((doc) => {
                const isActive =
                  doc.sessionId > 0 && activeSessionId === doc.sessionId;
                return (
                  <div
                    key={doc.id}
                    onClick={() =>
                      doc.sessionId > 0 && onSelectSession(doc.sessionId)
                    }
                    className={cn(
                      "group flex items-center gap-2 rounded-lg px-3 py-1.5 text-xs transition-all duration-200",
                      doc.sessionId > 0 && "cursor-pointer",
                      isActive
                        ? "bg-white/40 dark:bg-white/10 ring-1 ring-primary/30 text-foreground font-medium"
                        : "text-muted-foreground hover:bg-white/10 dark:hover:bg-white/5",
                    )}
                  >
                    <FileText
                      className={cn(
                        "h-3 w-3 shrink-0",
                        isActive
                          ? "text-primary/70"
                          : "text-muted-foreground/50",
                      )}
                    />
                    <span className="flex-1 truncate">{doc.name}</span>
                    <span
                      className={cn(
                        "inline-block h-1.5 w-1.5 rounded-full shrink-0 ring-1 ring-white/20",
                        doc.status === "done"
                          ? "bg-emerald-400 shadow-[0_0_6px] shadow-emerald-400/30"
                          : doc.status === "error"
                            ? "bg-destructive"
                            : "bg-amber-400 animate-pulse",
                      )}
                    />
                  </div>
                );
              })}
            </div>
          </ScrollArea>
        </>
      )}

      {/* Footer */}
      <div
        className={cn(
          "border-t border-white/10 p-2",
          collapsed
            ? "flex justify-center"
            : "flex items-center justify-between px-3",
        )}
      >
        {!collapsed && (
          <span className="text-[10px] text-muted-foreground/40 tracking-wider">
            EDUMERGE AI
          </span>
        )}
        <ThemeToggle />
      </div>
    </aside>
  );
}
