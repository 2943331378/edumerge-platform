"use client";

import { useEffect, useState, useRef, useMemo, useCallback } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import { toast } from "sonner";
import { BookOpenCheck, Clipboard, Download, MoreHorizontal, NotebookText, Pencil, RotateCw, Save, Sparkles, Trash2, X, Loader2, XCircle } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ExportBottomSheet } from "@/components/ui/export-bottom-sheet";
import type { StudyNoteRecord } from "@/lib/api";
import { deleteStudyNote, generateStudyNoteStream, listNoteHistory, updateStudyNote } from "@/lib/api";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { cn } from "@/lib/utils";
import { printNote } from "@/lib/printExport";
import { MermaidDiagram } from "@/components/MermaidDiagram";
import { diffLines, type Change } from "diff";

// rehype-sanitize 默认 schema 不保留 code 元素的 class 属性，
// 需要扩展以支持 Mermaid 语言类名检测
const sanitizeSchema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    code: [...(defaultSchema.attributes?.code ?? []), "className"],
  },
};

/** 从 Markdown 中提取标题（跳过代码块） */
function extractHeadings(markdown: string): { level: number; text: string; id: string }[] {
  // 先移除围栏代码块和行内代码，避免误匹配
  const stripped = markdown
    .replace(/```[\s\S]*?```/g, "")
    .replace(/`[^`\n]+`/g, "");
  const headings: { level: number; text: string; id: string }[] = [];
  const slugCount: Record<string, number> = {};
  for (const line of stripped.split("\n")) {
    const m = line.match(/^(#{1,3})\s+(.+)$/);
    if (!m) continue;
    const text = m[2].replace(/\*\*|__|\*|_|`/g, "").trim();
    let slug = text.toLowerCase().replace(/[^\w一-鿿]+/g, "-").replace(/^-|-$/g, "");
    if (slugCount[slug] !== undefined) {
      slugCount[slug]++;
      slug = `${slug}-${slugCount[slug]}`;
    } else {
      slugCount[slug] = 0;
    }
    headings.push({ level: m[1].length, text, id: `heading-${slug}` });
  }
  return headings;
}

interface Props {
  docId: number | null;
  docStatus?: string | null;
  embedded?: boolean;
  onGenerated?: () => void;
  onContextChange?: (hint: string) => void;
  /** 大纲选中的章节 IDs，从 DocumentOutlineView 传入 */
  sectionContext?: string;
  /** 大纲选中章节的 chunk 范围 */
  startChunk?: number;
  endChunk?: number;
  /** 大纲触发生成信号，counter 变化时自动触发 */
  generateTrigger?: { type: string; counter: number };
  /** 生成中状态（持久化在 session cache，跨挂载不丢失） */
  generating?: boolean;
  onGeneratingChange?: (v: boolean) => void;
}

/** 从 React children 中提取纯文本（用于生成 heading slug） */
function extractText(children: React.ReactNode): string {
  if (typeof children === "string") return children;
  if (typeof children === "number") return String(children);
  if (Array.isArray(children)) return children.map(extractText).join("");
  if (children && typeof children === "object" && "props" in children) {
    return extractText((children as React.ReactElement<{ children?: React.ReactNode }>).props.children);
  }
  return "";
}

function headingSlug(children: React.ReactNode): string {
  const text = extractText(children).replace(/\*\*|__|\*|_|`/g, "").trim();
  return text.toLowerCase().replace(/[^\w一-鿿]+/g, "-").replace(/^-|-$/g, "");
}

const markdownComponents: Components = {
  h1: ({ children }) => (
    <h1 id={`heading-${headingSlug(children)}`} className="text-2xl font-semibold tracking-tight text-foreground scroll-mt-20">{children}</h1>
  ),
  h2: ({ children }) => (
    <h2 id={`heading-${headingSlug(children)}`} className="mt-8 border-b border-border/60 pb-2 text-base font-semibold text-foreground/90 scroll-mt-20">{children}</h2>
  ),
  h3: ({ children }) => (
    <h3 id={`heading-${headingSlug(children)}`} className="mt-5 text-sm font-semibold text-foreground/85 scroll-mt-20">{children}</h3>
  ),
  p: ({ children }) => (
    <p className="my-3 text-sm leading-7 text-foreground/75">{children}</p>
  ),
  ul: ({ children }) => (
    <ul className="my-3 space-y-2 pl-5 text-sm leading-7 text-foreground/75 list-disc">{children}</ul>
  ),
  ol: ({ children }) => (
    <ol className="my-3 space-y-2 pl-5 text-sm leading-7 text-foreground/75 list-decimal">{children}</ol>
  ),
  li: ({ children }) => <li className="pl-1">{children}</li>,
  strong: ({ children }) => <strong className="font-semibold text-foreground/90">{children}</strong>,
  pre: ({ children }) => {
    // Mermaid blocks: render MermaidDiagram directly without <pre> wrapper
    const child = children as React.ReactElement<{ className?: string; children?: unknown }> | undefined;
    if (
      child &&
      typeof child === "object" &&
      "props" in child &&
      child.props?.className?.includes("language-mermaid") &&
      typeof child.props?.children === "string"
    ) {
      return <MermaidDiagram code={child.props.children} />;
    }
    return <pre>{children}</pre>;
  },
  code: ({ className, children, node, ...props }) => {
    const isBlock = className?.includes("language-");
    if (isBlock) {
      // Block code — render as styled code block (mermaid handled by pre above)
      return (
        <code className="block overflow-x-auto rounded-lg bg-muted/60 p-4 text-xs leading-5 text-foreground/85 font-mono" {...props}>
          {children}
        </code>
      );
    }
    return (
      <code className="rounded-md bg-muted px-1.5 py-0.5 text-xs text-foreground/85" {...props}>{children}</code>
    );
  },
  blockquote: ({ children }) => (
    <blockquote className="my-4 border-l-2 border-primary/40 pl-4 text-sm text-muted-foreground">{children}</blockquote>
  ),
  table: ({ children }) => (
    <div className="my-4 overflow-x-auto rounded-lg border border-border/40">
      <table className="w-full text-sm">{children}</table>
    </div>
  ),
  thead: ({ children }) => (
    <thead className="bg-muted/50">{children}</thead>
  ),
  tbody: ({ children }) => <tbody>{children}</tbody>,
  tr: ({ children }) => (
    <tr className="border-b border-border/30 last:border-0">{children}</tr>
  ),
  th: ({ children }) => (
    <th className="px-3 py-2 text-left text-xs font-semibold text-foreground/80 whitespace-nowrap">{children}</th>
  ),
  td: ({ children }) => (
    <td className="px-3 py-2 text-sm text-foreground/75">{children}</td>
  ),
};

export function StudyNoteView({ docId, docStatus, embedded, onGenerated, onContextChange, sectionContext, startChunk, endChunk, generateTrigger, generating = false, onGeneratingChange }: Props) {
  const [note, setNote] = useState<StudyNoteRecord | null>(null);
  const [history, setHistory] = useState<StudyNoteRecord[]>([]);
  const [activeVersionIdx, setActiveVersionIdx] = useState(0);
  const [loading, setLoading] = useState(false);
  const [requirements, setRequirements] = useState("");
  const [streamingContent, setStreamingContent] = useState<string | null>(null);
  const [streamingError, setStreamingError] = useState<string | null>(null);
  const [streamingProgress, setStreamingProgress] = useState(0);
  const streamingContentRef = useRef("");
  const rafIdRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const generatingRef = useRef(generating);
  generatingRef.current = generating;
  const mountedRef = useRef(true);

  // Cleanup: 重置 generating 状态（不 abort 请求 — React double-mount 会误杀）
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      prevCounterRef.current = undefined;
      if (rafIdRef.current !== null) cancelAnimationFrame(rafIdRef.current);
      if (generatingRef.current) onGeneratingChange?.(false);
    };
  }, []);

  // 编辑模式状态
  const [editing, setEditing] = useState(false);
  const [editContent, setEditContent] = useState("");
  const [saving, setSaving] = useState(false);
  const [showExportSheet, setShowExportSheet] = useState(false);
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);

  const isReady = docStatus === "COMPLETED";
  const generatedAt = note?.createdAt ? new Date(note.createdAt).toLocaleString("zh-CN") : "";
  const hasHistory = history.length > 1;

  // T1: 动态目录
  const headings = useMemo(() => (note?.content ? extractHeadings(note.content) : []), [note?.content]);
  const scrollToHeading = useCallback((id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, []);

  // T5: 版本 diff
  const [showDiff, setShowDiff] = useState(false);
  const diffChanges = useMemo<Change[]>(() => {
    if (!showDiff || activeVersionIdx === 0 || !history[0]?.content || !note?.content) return [];
    // 对比当前选中版本与最新版本，移除代码块避免干扰
    const stripCode = (s: string) => s.replace(/```[\s\S]*?```/g, "").replace(/`[^`\n]+`/g, "");
    return diffLines(stripCode(history[0].content), stripCode(note.content));
  }, [showDiff, activeVersionIdx, history, note?.content]);

  // T2: 编辑器防抖预览
  const [debouncedContent, setDebouncedContent] = useState(editContent);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (!editing) return;
    if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
    debounceTimerRef.current = setTimeout(() => setDebouncedContent(editContent), 300);
    return () => { if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current); };
  }, [editContent, editing]);

  useEffect(() => {
    if (note?.title) {
      onContextChange?.(`用户正在阅读学习笔记「${note.title}」`);
    } else {
      onContextChange?.("");
    }
  }, [note?.title, onContextChange]);

  const loadHistory = (list: StudyNoteRecord[]) => {
    setHistory(list);
    setActiveVersionIdx(0);
    setNote(list[0] ?? null);
  };

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      if (!docId) {
        if (!cancelled) {
          setNote(null);
          setHistory([]);
        }
        return;
      }
      setLoading(true);
      try {
        const list = await listNoteHistory(docId);
        if (!cancelled) loadHistory(list);
      } catch {
        if (!cancelled) { setNote(null); setHistory([]); }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => { cancelled = true; };
  }, [docId]);

  const handleGenerate = () => {
    if (!docId || generating) return;
    const controller = new AbortController();
    abortRef.current = controller;
    const timeoutId = setTimeout(() => controller.abort(), 290_000);
    onGeneratingChange?.(true);
    streamingContentRef.current = "";
    setStreamingContent("");
    setStreamingError(null);
    setStreamingProgress(0);

    generateStudyNoteStream(docId, {
      requirements: requirements.trim() || undefined,
      sectionContext: sectionContext || undefined,
      startChunk,
      endChunk,
      signal: controller.signal,
      onToken: (token) => {
        if (!mountedRef.current || abortRef.current !== controller) return;
        streamingContentRef.current += token;
        setStreamingContent(streamingContentRef.current);
      },
      onProgress: (progress) => {
        if (mountedRef.current && abortRef.current === controller) setStreamingProgress(progress);
      },
      onDone: async (meta) => {
        clearTimeout(timeoutId);
        if (!mountedRef.current || abortRef.current !== controller) return;
        setStreamingContent(null);
        setStreamingError(null);
        setStreamingProgress(0);
        streamingContentRef.current = "";
        const list = await listNoteHistory(docId);
        if (!mountedRef.current) return;
        loadHistory(list);
        onGenerated?.();
        toast.success("学习笔记生成成功");
        if (abortRef.current === controller) {
          abortRef.current = null;
          onGeneratingChange?.(false);
        }
      },
      onError: (msg) => {
        clearTimeout(timeoutId);
        if (!mountedRef.current || abortRef.current !== controller) return;
        setStreamingError(msg || "生成中断");
        if (abortRef.current === controller) {
          abortRef.current = null;
          onGeneratingChange?.(false);
        }
      },
    });
  };

  // 从大纲页面跳转过来时自动触发生成
  const prevCounterRef = useRef<number | undefined>(undefined);
  useEffect(() => {
    const counter = generateTrigger?.counter;
    if (counter !== undefined && counter !== prevCounterRef.current && generateTrigger?.type === "note") {
      prevCounterRef.current = counter;
      handleGenerate();
    } else {
      prevCounterRef.current = counter;
    }
  }, [generateTrigger?.counter]);

  const cancelGeneration = () => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
      setStreamingContent(null);
      setStreamingError(null);
      streamingContentRef.current = "";
      onGeneratingChange?.(false);
      toast.info("已取消生成");
    }
  };

  const switchVersion = (idx: number) => {
    if (idx >= 0 && idx < history.length) {
      setActiveVersionIdx(idx);
      setNote(history[idx]);
      if (idx === 0) setShowDiff(false);
    }
  };

  const handleCopy = async () => {
    if (!note?.content) return;
    try {
      await navigator.clipboard.writeText(note.content);
    } catch {
      const textarea = document.createElement("textarea");
      textarea.value = note.content;
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand("copy");
      document.body.removeChild(textarea);
    }
    toast.success("已复制 Markdown");
  };

  const handleDownload = () => {
    if (!note?.content) return;
    const blob = new Blob([note.content], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${note.title || "学习笔记"}.md`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const handleExportPDF = () => {
    if (!note?.content) return;
    printNote(note.title || "学习笔记", note.content);
    setShowExportSheet(false);
  };

  const handleStartEdit = () => {
    if (!note?.content) return;
    setEditContent(note.content);
    setEditing(true);
  };

  const handleCancelEdit = () => {
    setEditing(false);
    setEditContent("");
  };

  const handleSave = async () => {
    if (!note?.id || saving) return;
    setSaving(true);
    try {
      const updated = await updateStudyNote(note.id, { content: editContent });
      setNote(updated);
      setEditing(false);
      setEditContent("");
      toast.success("笔记已保存");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    }
    setSaving(false);
  };

  const handleDeleteConfirm = async () => {
    if (!note?.id) return;
    setShowDeleteDialog(false);
    try {
      await deleteStudyNote(note.id);
      toast.success("笔记已删除");
      if (docId) {
        const list = await listNoteHistory(docId);
        loadHistory(list);
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败");
    }
  };

  if (!docId) {
    return (
      <div className="flex h-full items-center justify-center">
        <EmptyState title="请先选择文档" description="选择左侧已上传的资料后，可以生成中文学习笔记。" />
      </div>
    );
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <RotateCw className="h-4 w-4 animate-spin" />
          正在读取学习笔记...
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex shrink-0 items-center justify-between border-b bg-muted/20 px-6 py-3">
        {!embedded && (
          <div className="flex items-center gap-2">
            <NotebookText className="h-4 w-4 text-muted-foreground" />
            <div>
              <h2 className="text-sm font-medium text-foreground/85">学习笔记</h2>
              {generatedAt && <p className="text-[11px] text-muted-foreground/60">生成时间 {generatedAt}</p>}
            </div>
          </div>
        )}
        {embedded && <div />}
        <div className="flex items-center gap-2">
          {note?.content && !editing && (
            <>
              <Button size="sm" variant="outline" className="h-10 rounded-xl gap-1.5" onClick={handleStartEdit}>
                <Pencil className="h-3.5 w-3.5" />
                编辑
              </Button>
              <Button size="sm" variant="outline" className="h-10 rounded-xl gap-1.5" onClick={handleCopy}>
                <Clipboard className="h-3.5 w-3.5" />
                复制
              </Button>
              <DropdownMenu>
                <DropdownMenuTrigger className="inline-flex h-10 w-10 items-center justify-center rounded-xl border border-input bg-background text-sm font-medium hover:bg-accent hover:text-accent-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2">
                  <MoreHorizontal className="h-4 w-4" />
                  <span className="sr-only">更多操作</span>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem onClick={() => setShowExportSheet(true)}>
                    <Download className="h-3.5 w-3.5 mr-2" />
                    导出
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setShowDeleteDialog(true)} variant="destructive">
                    <Trash2 className="h-3.5 w-3.5 mr-2" />
                    删除
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </>
          )}
          {editing && (
            <>
              <Button size="sm" variant="outline" className="h-10 rounded-xl gap-1.5" onClick={handleCancelEdit}>
                <X className="h-3.5 w-3.5" />
                取消
              </Button>
              <Button size="sm" className="h-10 rounded-xl gap-1.5" onClick={handleSave} disabled={saving}>
                <Save className="h-3.5 w-3.5" />
                {saving ? "保存中..." : "保存"}
              </Button>
            </>
          )}
        </div>
      </div>

      {isReady && note?.content && !streamingContent && (
        <div className="shrink-0 px-6 pt-2 pb-1">
          <div className="flex items-center gap-2">
            <Sparkles className="h-3.5 w-3.5 shrink-0 text-primary/60" />
            <input
              type="text"
              value={requirements}
              onChange={(e) => setRequirements(e.target.value)}
              placeholder="自定义生成要求（可选）例如：只整理第3章、重点关注实验方法..."
              className="flex-1 bg-transparent text-xs text-foreground/80 placeholder:text-muted-foreground/35 outline-none"
            />
            {requirements && (
              <button onClick={() => setRequirements("")} className="shrink-0 text-[11px] text-muted-foreground/40 hover:text-muted-foreground">
                清除
              </button>
            )}
            <Button
              size="sm"
              className="h-10 rounded-lg gap-1 shrink-0"
              onClick={handleGenerate}
              disabled={generating}
              aria-busy={generating}
            >
              <Sparkles className="h-3 w-3" />
              重新生成
            </Button>
          </div>
        </div>
      )}

      {/* Version history tabs */}
      {hasHistory && (
        <div className="shrink-0 flex items-center gap-1 px-6 pb-2 overflow-x-auto" role="tablist" aria-label="笔记版本历史">
          <span className="text-[11px] text-muted-foreground/40 mr-1">历史:</span>
          {history.map((h, i) => (
            <button
              key={h.id ?? i}
              role="tab"
              aria-selected={i === activeVersionIdx}
              onClick={() => switchVersion(i)}
              className={cn(
                "text-[11px] rounded-md px-2 py-0.5 transition-all",
                i === activeVersionIdx
                  ? "bg-primary/10 text-primary font-medium"
                  : "text-muted-foreground/60 hover:text-muted-foreground hover:bg-muted/30",
              )}
            >
              {i === 0 ? "最新" : `v${history.length - i}`}
            </button>
          ))}
          {activeVersionIdx > 0 && (
            <button
              onClick={() => setShowDiff(!showDiff)}
              className={cn(
                "text-[11px] rounded-md px-2 py-0.5 transition-all ml-1",
                showDiff ? "bg-primary/10 text-primary font-medium" : "text-muted-foreground/60 hover:text-muted-foreground hover:bg-muted/30",
              )}
            >
              {showDiff ? "隐藏变更" : "查看变更"}
            </button>
          )}
          {note?.requirements && (
            <span className="text-[11px] text-muted-foreground/40 ml-2 truncate max-w-[200px]">
              · {note.requirements}
            </span>
          )}
        </div>
      )}

      {/* T5: 内联 diff 视图 */}
      {showDiff && diffChanges.length > 0 && (
        <div className="shrink-0 mx-6 mb-2 max-h-[30vh] overflow-y-auto rounded-xl border border-border/40 bg-muted/10 p-4 text-xs font-mono leading-6">
          {diffChanges.map((part, i) => {
            const lines = part.value.replace(/\n$/, "").split("\n");
            return lines.map((line, j) => (
              <div
                key={`${i}-${j}`}
                className={cn(
                  "px-2 -mx-2",
                  part.added && "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
                  part.removed && "bg-red-500/10 text-red-700 dark:text-red-400 line-through",
                  !part.added && !part.removed && "text-muted-foreground/60",
                )}
              >
                <span className="inline-block w-4 text-muted-foreground/30 select-none mr-2">
                  {part.added ? "+" : part.removed ? "-" : " "}
                </span>
                {line || " "}
              </div>
            ));
          })}
        </div>
      )}

      <div className="flex-1 overflow-y-auto p-6">
        {!isReady ? (
          <EmptyState title="文档仍在处理中" description="向量化完成后才能基于文档内容生成学习笔记。" />
        ) : streamingContent !== null ? (
          /* 流式生成中（或中断后保留内容） — 实时渲染 */
          <div className="mx-auto max-w-4xl" aria-live="polite" aria-label="正在生成学习笔记">
            <Card className="rounded-2xl border-border/60 bg-card/95 shadow-sm">
              <CardContent className="p-6 sm:p-8">
                {streamingError ? (
                  <div className="mb-4 flex items-center justify-between rounded-lg bg-destructive/10 px-3 py-2 text-xs text-destructive">
                    <span>{streamingError} — 内容为已生成的部分</span>
                    <Button size="sm" variant="outline" className="h-6 rounded-md gap-1 text-xs border-destructive/30 text-destructive hover:bg-destructive/10" onClick={handleGenerate}>
                      <RotateCw className="h-3 w-3" />重试
                    </Button>
                  </div>
                ) : (
                  <div className="mb-4 space-y-2">
                    <div className="flex items-center gap-2 text-xs text-primary/70">
                      <RotateCw className="h-3.5 w-3.5 animate-spin" />
                      正在生成笔记...
                      {streamingProgress > 0 && <span className="text-muted-foreground/50">{streamingProgress}%</span>}
                    </div>
                    {streamingProgress > 0 && (
                      <div
                        className="h-1 w-full overflow-hidden rounded-full bg-muted/50"
                        role="progressbar"
                        aria-valuenow={streamingProgress}
                        aria-valuemin={0}
                        aria-valuemax={100}
                        aria-label="笔记生成进度"
                      >
                        <div className="h-full rounded-full bg-primary/50 transition-all duration-300" style={{ width: `${streamingProgress}%` }} />
                      </div>
                    )}
                  </div>
                )}
                <article className="max-w-none">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[[rehypeSanitize, sanitizeSchema]]} components={markdownComponents}>
                    {streamingContent}
                  </ReactMarkdown>
                </article>
              </CardContent>
            </Card>
          </div>
        ) : !note?.content ? (
          <EmptyState title="尚无学习笔记" description="一键生成后，系统会提炼中文概述、核心知识点、易混淆点和复习问题。" onGenerate={handleGenerate} generating={generating} onCancelGeneration={cancelGeneration} requirements={requirements} onRequirementsChange={setRequirements} />
        ) : (
          <div className="mx-auto grid max-w-6xl grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
            {editing ? (
              /* T2: 分栏编辑器 — 移动端上下、桌面端左右 */
              <div className="col-span-1 lg:col-span-2 grid grid-cols-1 lg:grid-cols-2 gap-4">
                <Card className="rounded-2xl border-border/60 bg-card/95 shadow-sm">
                  <CardContent className="p-6 sm:p-8">
                    <p className="mb-2 text-xs font-medium text-muted-foreground/60">编辑</p>
                    <textarea
                      value={editContent}
                      onChange={(e) => setEditContent(e.target.value)}
                      className="w-full min-h-[50vh] lg:min-h-[60vh] bg-transparent text-sm leading-7 text-foreground/85 outline-none resize-y font-mono"
                      spellCheck={false}
                      aria-label="编辑笔记内容"
                      placeholder="在此编辑 Markdown 笔记内容..."
                    />
                  </CardContent>
                </Card>
                <Card className="rounded-2xl border-border/60 bg-card/95 shadow-sm">
                  <CardContent className="p-6 sm:p-8">
                    <p className="mb-2 text-xs font-medium text-muted-foreground/60">预览</p>
                    <article className="max-w-none">
                      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[[rehypeSanitize, sanitizeSchema]]} components={markdownComponents}>
                        {debouncedContent}
                      </ReactMarkdown>
                    </article>
                  </CardContent>
                </Card>
              </div>
            ) : (
              <>
                <Card className="rounded-2xl border-border/60 bg-card/95 shadow-sm">
                  <CardContent className="p-6 sm:p-8">
                    <article className="max-w-none">
                      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[[rehypeSanitize, sanitizeSchema]]} components={markdownComponents}>
                        {note.content}
                      </ReactMarkdown>
                    </article>
                  </CardContent>
                </Card>

                <aside className="space-y-3">
                  {/* T1: 动态目录 */}
                  {headings.length > 0 && (
                    <Card className="rounded-2xl border-border/60 bg-muted/20 shadow-sm">
                      <CardContent className="p-4">
                        <div className="flex items-center gap-2 text-sm font-medium text-foreground/80">
                          <BookOpenCheck className="h-4 w-4 text-emerald-500" />
                          目录
                        </div>
                        <nav className="mt-3 space-y-0.5 text-xs">
                          {headings.map((h) => (
                            <button
                              key={h.id}
                              onClick={() => scrollToHeading(h.id)}
                              className={cn(
                                "block w-full rounded-lg px-3 py-1.5 text-left transition-colors hover:bg-background/60 hover:text-foreground/80 text-muted-foreground",
                                h.level === 1 && "font-medium text-foreground/80",
                                h.level === 2 && "pl-6",
                                h.level === 3 && "pl-10 text-[11px]",
                              )}
                            >
                              {h.text}
                            </button>
                          ))}
                        </nav>
                      </CardContent>
                    </Card>
                  )}

                  {note.sourceSummary && (
                    <Card className="rounded-2xl border-border/60 bg-muted/10 shadow-sm">
                      <CardContent className="p-4">
                        <p className="text-sm font-medium text-foreground/80">参考片段摘要</p>
                        <pre className="mt-3 max-h-[360px] overflow-y-auto whitespace-pre-wrap text-[11px] leading-5 text-muted-foreground">
                          {note.sourceSummary}
                    </pre>
                  </CardContent>
                </Card>
              )}
            </aside>
              </>
            )}
          </div>
        )}
      </div>

      <ExportBottomSheet
        open={showExportSheet}
        onClose={() => setShowExportSheet(false)}
        options={[
          { icon: Clipboard, title: "复制 Markdown", description: "复制纯文本到剪贴板", onClick: handleCopy },
          { icon: Download, title: "导出 Markdown 文件", description: "下载 .md 文件", onClick: handleDownload },
          { icon: Download, iconColor: "text-primary", title: "导出为 PDF", description: "通过浏览器打印保存为 PDF", onClick: handleExportPDF },
        ]}
      />

      <AlertDialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除笔记</AlertDialogTitle>
            <AlertDialogDescription>确定删除该笔记？删除后无法恢复。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteConfirm} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              确认删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function EmptyState({
  title,
  description,
  onGenerate,
  generating,
  onCancelGeneration,
  requirements,
  onRequirementsChange,
}: {
  title: string;
  description: string;
  onGenerate?: () => void;
  generating?: boolean;
  onCancelGeneration?: () => void;
  requirements?: string;
  onRequirementsChange?: (v: string) => void;
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-5 text-center text-muted-foreground">
      <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
        <NotebookText className="h-7 w-7 text-muted-foreground/45" />
      </div>
      <div className="space-y-1.5">
        <p className="text-sm font-medium text-foreground/75">{title}</p>
        <p className="max-w-sm text-xs leading-6 text-muted-foreground/60">{description}</p>
      </div>
      {onRequirementsChange && (
        <textarea
          value={requirements ?? ""}
          onChange={(e) => onRequirementsChange(e.target.value)}
          placeholder="例如：只整理第3章的内容、重点关注实验方法和结论..."
          rows={3}
          className="w-full max-w-sm rounded-xl border border-border/60 bg-muted/30 px-3 py-2 text-xs text-foreground/80 placeholder:text-muted-foreground/40 resize-none focus:outline-none focus:ring-2 focus:ring-primary/20"
        />
      )}
      {onGenerate && generating && onCancelGeneration && (
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="h-8 w-8 animate-spin text-primary" />
          <div className="text-center space-y-1">
            <p className="text-sm font-medium text-foreground/80">AI 正在生成学习笔记...</p>
            <p className="text-xs text-muted-foreground/50">正在提炼文档概述、核心知识点和复习问题</p>
          </div>
          <Button variant="outline" onClick={onCancelGeneration} className="h-10 rounded-xl gap-2 border-destructive/30 text-destructive hover:bg-destructive/10">
            <XCircle className="h-4 w-4" />取消生成
          </Button>
        </div>
      )}
      {onGenerate && !generating && (
        <Button onClick={onGenerate} className="h-10 rounded-xl gap-2">
          <Sparkles className="h-4 w-4" />
          一键生成学习笔记
        </Button>
      )}
    </div>
  );
}
