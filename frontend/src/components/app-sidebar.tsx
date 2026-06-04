"use client";

import { useState, ChangeEvent, useRef } from "react";
import Image from "next/image";
import { cn } from "@/lib/utils";
import { ScrollArea } from "@/components/ui/scroll-area";
import { ThemeToggle } from "@/components/theme-toggle";
import {
  Upload,
  FileText,
  PanelLeftClose,
  PanelLeft,
  X,
  Search,
} from "lucide-react";

export interface UploadedDoc {
  id: string;
  sessionId: number;
  name: string;
  size: number;
  status: "uploading" | "processing" | "done" | "error";
  chunks?: number;
}

interface AppSidebarProps {
  documents: UploadedDoc[];
  activeSessionId: number | null;
  onSelectSession: (sessionId: number) => void;
  onUpload: (file: File) => Promise<void>;
  onDeleteDocument: (sessionId: number) => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
}

export function AppSidebar({
  documents,
  activeSessionId,
  onSelectSession,
  onUpload,
  onDeleteDocument,
  collapsed,
  onToggleCollapse,
}: AppSidebarProps) {
  const [dragging, setDragging] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);

  const filteredDocs = documents.filter((doc) =>
    searchQuery ? doc.name.toLowerCase().includes(searchQuery.toLowerCase()) : true,
  );

  const handleFile = (file: File | null) => {
    if (!file) return;
    const extension = file.name.split(".").pop()?.toLowerCase();
    if (extension && ["pdf", "doc", "docx", "ppt", "pptx", "txt"].includes(extension)) {
      onUpload(file);
    }
  };

  return (
    <>
      {/* Mobile backdrop */}
      {!collapsed && (
        <div
          className="fixed inset-0 z-40 bg-black/40 backdrop-blur-sm md:hidden"
          onClick={onToggleCollapse}
        />
      )}

      <aside
        className={cn(
          "relative flex h-full flex-col shrink-0 overflow-hidden border-r border-border",
          "bg-white/70 dark:bg-slate-900/80 backdrop-blur-xl",
          "transition-all duration-300 ease-in-out",
          // Desktop: inline sidebar
          "hidden md:flex",
          collapsed ? "w-[64px]" : "w-[260px]",
          // Mobile: overlay sidebar
          "max-md:fixed max-md:inset-y-0 max-md:left-0 max-md:z-50 max-md:flex max-md:w-[260px]",
          collapsed ? "max-md:-translate-x-full" : "max-md:translate-x-0",
        )}
      >
      {/* 折叠按钮 — 侧边栏下半部分右侧边缘 */}
      <button
        type="button"
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
                aria-label="上传学习资料"
                accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation"
                onChange={(e: ChangeEvent<HTMLInputElement>) => {
                  handleFile(e.target.files?.[0] ?? null);
                  if (fileRef.current) fileRef.current.value = "";
                }}
                className="hidden"
              />
            </div>
          </div>

          {/* Search */}
          {documents.length > 3 && (
            <div className="px-3 pb-1">
              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3 w-3 text-muted-foreground/40" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="搜索文档..."
                  className="w-full rounded-lg border border-border/50 bg-muted/30 pl-7 pr-2 py-1.5 text-[11px] text-foreground placeholder:text-muted-foreground/40 outline-none focus:border-primary/30 focus:bg-muted/50 transition-all"
                />
              </div>
            </div>
          )}

          {/* Document list */}
          <ScrollArea className="flex-1">
            <div className="px-2 pb-2 space-y-0.5">
              {filteredDocs.length === 0 && documents.length > 0 && (
                <p className="px-3 py-4 text-center text-[11px] text-muted-foreground/60">
                  无匹配文档
                </p>
              )}
              {documents.length === 0 && (
                <p className="px-3 py-4 text-center text-[11px] text-muted-foreground/60">
                  尚无文档
                </p>
              )}
              {filteredDocs.map((doc) => {
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
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onDeleteDocument(doc.sessionId);
                      }}
                      className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-all"
                      title="删除文档"
                    >
                      <X className="h-3 w-3" />
                    </button>
                    <span
                      className={cn(
                        "inline-block h-1.5 w-1.5 rounded-full shrink-0 ring-1 ring-white/20",
                        doc.status === "done"
                          ? "bg-emerald-400 shadow-[0_0_6px] shadow-emerald-400/30"
                          : doc.status === "error"
                            ? "bg-destructive"
                            : doc.status === "processing"
                              ? "bg-blue-400 animate-pulse"
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
    </>
  );
}
