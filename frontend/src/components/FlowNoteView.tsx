"use client";

import { useState, useEffect, useCallback } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import type { FlowNoteItem, FlowNoteStats } from "@/lib/api";
import {
  listFlowNotes, extractFlowNotes, createFlowNote,
  deleteFlowNote, markFlowNoteReviewed, getFlowNoteStats,
} from "@/lib/api";
import {
  BookOpen, Lightbulb, MessageCircle, AlertTriangle,
  Sparkles, Plus, RotateCw, Trash2, Check, Loader2,
  FileText, Download, BarChart3,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

interface Props {
  docId: number | null;
  docStatus: string | null;
  sessionId?: number | null;
  docUuid?: string | null;
  embedded?: boolean;
  onContextChange?: (hint: string) => void;
}

const CATEGORIES = [
  { key: "KEY_POINT", label: "章节要点", icon: BookOpen, color: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300" },
  { key: "QUESTION", label: "我的问题", icon: MessageCircle, color: "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300" },
  { key: "EXAMPLE", label: "示例类比", icon: Lightbulb, color: "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300" },
  { key: "REVIEW", label: "待复习", icon: AlertTriangle, color: "bg-rose-100 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300" },
] as const;

const CATEGORY_MAP = new Map(CATEGORIES.map((c) => [c.key, c]));

const ACCENT_MAP: Record<string, string> = {
  KEY_POINT: "from-blue-500 to-indigo-500",
  QUESTION: "from-amber-500 to-orange-500",
  EXAMPLE: "from-emerald-500 to-teal-500",
  REVIEW: "from-rose-500 to-pink-500",
};
const GLOW_MAP: Record<string, string> = {
  KEY_POINT: "shadow-blue-500/5",
  QUESTION: "shadow-amber-500/5",
  EXAMPLE: "shadow-emerald-500/5",
  REVIEW: "shadow-rose-500/5",
};
const HEADER_BG_MAP: Record<string, string> = {
  KEY_POINT: "bg-blue-50/50 dark:bg-blue-950/10",
  QUESTION: "bg-amber-50/50 dark:bg-amber-950/10",
  EXAMPLE: "bg-emerald-50/50 dark:bg-emerald-950/10",
  REVIEW: "bg-rose-50/50 dark:bg-rose-950/10",
};

export function FlowNoteView({ docId, docStatus, embedded, onContextChange }: Props) {
  const [entries, setEntries] = useState<FlowNoteItem[]>([]);
  const [stats, setStats] = useState<FlowNoteStats | null>(null);
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [extracting, setExtracting] = useState(false);
  const [showAddForm, setShowAddForm] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newContent, setNewContent] = useState("");
  const [newCategory, setNewCategory] = useState("KEY_POINT");

  const load = useCallback(async () => {
    if (!docId) return;
    try {
      const [list, s] = await Promise.all([
        listFlowNotes(docId, activeCategory ?? undefined),
        getFlowNoteStats(docId),
      ]);
      setEntries(list);
      setStats(s);
    } catch { /* ignore */ }
    setLoading(false);
  }, [docId, activeCategory]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (activeCategory) {
      const cat = CATEGORY_MAP.get(activeCategory as FlowNoteItem["category"]);
      onContextChange?.(`用户正在浏览${cat?.label ?? activeCategory}类学习日志`);
    } else {
      onContextChange?.("用户正在浏览全部学习日志");
    }
  }, [activeCategory, onContextChange]);

  const handleExtract = async () => {
    if (!docId) return;
    setExtracting(true);
    try {
      const result = await extractFlowNotes(docId);
      if (result.length === 0) {
        toast.info("暂无足够对话记录可供提取");
      } else {
        toast.success(`已从对话中提取 ${result.length} 条笔记`);
        await load();
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "提取失败");
    }
    setExtracting(false);
  };

  const handleAdd = async () => {
    if (!docId || !newTitle.trim() || !newContent.trim()) return;
    try {
      await createFlowNote({ docId, category: newCategory, title: newTitle.trim(), content: newContent.trim() });
      toast.success("笔记已添加");
      setNewTitle(""); setNewContent(""); setNewCategory("KEY_POINT");
      setShowAddForm(false);
      await load();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "添加失败");
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteFlowNote(id);
      setEntries((prev) => prev.filter((e) => e.id !== id));
      toast.success("已删除");
    } catch { toast.error("删除失败"); }
  };

  const handleReview = async (id: number) => {
    try {
      await markFlowNoteReviewed(id);
      setEntries((prev) => prev.map((e) => e.id === id ? { ...e, isReviewed: 1 } : e));
    } catch { /* ignore */ }
  };

  const handleExport = () => {
    const md = entries.map((e) => {
      const cat = CATEGORY_MAP.get(e.category);
      return `## ${cat?.label ?? e.category}: ${e.title}\n\n${e.content}\n\n---\n`;
    }).join("\n");
    const blob = new Blob([`# EduMerge 学习日志\n\n${md}`], { type: "text/markdown" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = "edumerge-flownote.md"; a.click();
    URL.revokeObjectURL(url);
    toast.success("已导出 Markdown");
  };

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      {!embedded && (
        <div className="flex items-center justify-between px-6 py-3 border-b bg-muted/20 shrink-0">
          <h2 className="text-sm font-medium text-foreground/80 flex items-center gap-2">
            <BookOpen className="h-4 w-4 text-muted-foreground" />
            学习日志
          </h2>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 text-xs" onClick={handleExport} disabled={entries.length === 0}>
              <Download className="h-3.5 w-3.5" />导出
            </Button>
            <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 text-xs" onClick={() => setShowAddForm((v) => !v)}>
              <Plus className="h-3.5 w-3.5" />手动添加
            </Button>
            <Button size="sm" className="rounded-xl gap-1.5 h-8 text-xs" onClick={handleExtract} disabled={extracting || !docId || docStatus !== "COMPLETED"}>
              {extracting ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
              {extracting ? "提取中..." : "从对话提取"}
            </Button>
          </div>
        </div>
      )}

      {/* Category filter + stats bar */}
      <div className="flex items-center gap-2 px-4 py-2 border-b border-border/30 shrink-0 overflow-x-auto">
        <button
          type="button"
          onClick={() => setActiveCategory(null)}
          className={cn(
            "rounded-full px-3 py-1 text-[11px] font-medium transition-all shrink-0",
            !activeCategory ? "bg-primary/10 text-primary" : "text-muted-foreground hover:text-foreground",
          )}
        >
          全部{stats ? ` (${stats.total})` : ""}
        </button>
        {CATEGORIES.map((cat) => (
          <button
            key={cat.key}
            type="button"
            onClick={() => setActiveCategory(activeCategory === cat.key ? null : cat.key)}
            className={cn(
              "flex items-center gap-1 rounded-full px-3 py-1 text-[11px] font-medium transition-all shrink-0",
              activeCategory === cat.key ? cat.color : "text-muted-foreground hover:text-foreground",
            )}
          >
            <cat.icon className="h-3 w-3" />
            {cat.label}
            {stats?.byCategory?.[cat.key] ? ` (${stats.byCategory[cat.key]})` : ""}
          </button>
        ))}
        {stats && (
          <span className="ml-auto text-[10px] text-muted-foreground/50 shrink-0">
            复习 {stats.reviewed}/{stats.total} · {Math.round(stats.reviewRate * 100)}%
          </span>
        )}
      </div>

      {/* Add form */}
      {showAddForm && (
        <div className="px-4 py-3 border-b border-border/30 bg-muted/10 space-y-2">
          <div className="flex items-center gap-2">
            <select
              value={newCategory}
              onChange={(e) => setNewCategory(e.target.value)}
              className="rounded-lg border border-border/50 bg-background px-2 py-1 text-[11px]"
            >
              {CATEGORIES.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
            </select>
            <input
              type="text"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              placeholder="标题"
              className="flex-1 rounded-lg border border-border/50 bg-background px-2 py-1 text-xs"
            />
          </div>
          <textarea
            value={newContent}
            onChange={(e) => setNewContent(e.target.value)}
            placeholder="Markdown 内容..."
            rows={3}
            className="w-full rounded-lg border border-border/50 bg-background px-2 py-1 text-xs resize-none"
          />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={() => setShowAddForm(false)}>取消</Button>
            <Button size="sm" className="h-7 text-xs" onClick={handleAdd}>保存</Button>
          </div>
        </div>
      )}

      {/* Entry list */}
      <div className="flex-1 overflow-y-auto">
        {entries.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
            <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
              <BookOpen className="h-7 w-7 text-muted-foreground/40" />
            </div>
            <div className="text-center space-y-1.5">
              <p className="text-sm font-medium">尚无学习日志</p>
              <p className="text-xs text-muted-foreground/50 max-w-xs">
                与 AI 对话 5 轮后系统自动提取，或点击"从对话提取"手动生成
              </p>
            </div>
            <Button onClick={handleExtract} disabled={extracting || !docId} className="rounded-xl gap-2 h-9">
              {extracting ? <RotateCw className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
              从对话提取
            </Button>
          </div>
        ) : (
          <div className="max-w-3xl mx-auto p-4 sm:p-6 space-y-4">
            {entries.map((entry) => {
              const cat = CATEGORY_MAP.get(entry.category);
              const Icon = cat?.icon ?? FileText;
              const accent = ACCENT_MAP[entry.category] ?? "from-gray-400 to-gray-500";
              const glow = GLOW_MAP[entry.category] ?? "";
              const headerBg = HEADER_BG_MAP[entry.category] ?? "";

              return (
                <div
                  key={entry.id}
                  className={cn(
                    "group relative rounded-2xl border transition-all duration-300",
                    "hover:shadow-lg hover:-translate-y-0.5",
                    entry.isReviewed
                      ? "border-emerald-200/60 dark:border-emerald-800/30 bg-emerald-50/20 dark:bg-emerald-950/5"
                      : "border-border/40 bg-card shadow-sm",
                    glow,
                  )}
                >
                  {/* 顶部装饰条 — 分类渐变色 */}
                  <div className={cn("h-1 rounded-t-2xl bg-gradient-to-r", accent)} />

                  <div className="p-5 sm:p-6">
                    {/* 头部: 分类标签 + 来源 + 日期 */}
                    <div className="flex items-center gap-2.5 mb-3.5">
                      <span className={cn(
                        "inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-[10px] font-semibold tracking-wide uppercase",
                        cat?.color,
                      )}>
                        <Icon className="h-3 w-3" />
                        {cat?.label}
                      </span>
                      {entry.sourceType === "CHAT_EXTRACTED" && (
                        <span className="inline-flex items-center gap-1 text-[9px] text-muted-foreground/50 bg-muted/40 rounded-md px-1.5 py-0.5">
                          <Sparkles className="h-2.5 w-2.5" />
                          AI 提取
                        </span>
                      )}
                      {entry.sourceType === "USER_WRITTEN" && (
                        <span className="inline-flex items-center gap-1 text-[9px] text-muted-foreground/50 bg-muted/40 rounded-md px-1.5 py-0.5">
                          手动记录
                        </span>
                      )}
                      <span className="text-[10px] text-muted-foreground/40 ml-auto font-medium tabular-nums">
                        {new Date(entry.createdAt).toLocaleDateString("zh-CN", { month: "short", day: "numeric" })}
                      </span>
                    </div>

                    {/* 标题 — 更大的字体，更强的存在感 */}
                    <h3 className="text-base sm:text-lg font-semibold text-foreground/90 mb-3 leading-snug tracking-tight">
                      {entry.title}
                    </h3>

                    {/* 内容区域 — 带微妙背景色 */}
                    <div className={cn(
                      "rounded-xl px-4 py-3 text-sm leading-relaxed text-foreground/75",
                      "prose prose-sm dark:prose-invert max-w-none",
                      "prose-headings:text-foreground/80 prose-headings:font-semibold",
                      "prose-p:my-1.5 prose-li:my-0.5",
                      "prose-strong:text-foreground/85 prose-strong:font-semibold",
                      "prose-code:text-xs prose-code:bg-muted/60 prose-code:rounded prose-code:px-1.5 prose-code:py-0.5",
                      headerBg,
                    )}>
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {entry.content}
                      </ReactMarkdown>
                    </div>

                    {/* 来源片段 — 引用风格 */}
                    {entry.sourceSegment && (
                      <details className="mt-4 group/source">
                        <summary className="flex items-center gap-1.5 text-[11px] text-muted-foreground/50 cursor-pointer hover:text-muted-foreground transition-colors select-none">
                          <FileText className="h-3 w-3" />
                          <span>查看原文来源</span>
                        </summary>
                        <blockquote className="mt-2 pl-3.5 border-l-2 border-muted-foreground/15 text-[11px] text-muted-foreground/55 leading-relaxed italic">
                          {entry.sourceSegment}
                        </blockquote>
                      </details>
                    )}

                    {/* 底部操作栏 */}
                    <div className="flex items-center gap-2 mt-4 pt-3 border-t border-border/20">
                      <button
                        type="button"
                        onClick={() => handleReview(entry.id)}
                        className={cn(
                          "inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[11px] font-medium transition-all",
                          entry.isReviewed
                            ? "text-emerald-600 dark:text-emerald-400 bg-emerald-100/70 dark:bg-emerald-900/20"
                            : "text-muted-foreground/50 hover:text-emerald-600 hover:bg-emerald-50 dark:hover:bg-emerald-950/20",
                        )}
                        title={entry.isReviewed ? "已复习" : "标记已复习"}
                      >
                        <Check className="h-3 w-3" />
                        {entry.isReviewed ? "已复习" : "标为已复习"}
                      </button>
                      <button
                        type="button"
                        onClick={() => handleDelete(entry.id)}
                        className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[11px] font-medium text-muted-foreground/40 hover:text-destructive hover:bg-destructive/5 transition-all"
                        title="删除"
                      >
                        <Trash2 className="h-3 w-3" />
                        删除
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
