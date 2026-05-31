"use client";

import { useEffect, useState, useRef } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { toast } from "sonner";
import { BookOpenCheck, Clipboard, Download, NotebookText, RotateCw, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import type { StudyNoteRecord } from "@/lib/api";
import { generateStudyNote, getStudyNote, listNoteHistory } from "@/lib/api";
import { cn } from "@/lib/utils";

interface Props {
  docId: number | null;
  docStatus?: string | null;
  embedded?: boolean;
  onGenerated?: () => void;
  onContextChange?: (hint: string) => void;
}

const markdownComponents: Components = {
  h1: ({ children }) => (
    <h1 className="text-2xl font-semibold tracking-tight text-foreground">{children}</h1>
  ),
  h2: ({ children }) => (
    <h2 className="mt-8 border-b border-border/60 pb-2 text-base font-semibold text-foreground/90">{children}</h2>
  ),
  h3: ({ children }) => (
    <h3 className="mt-5 text-sm font-semibold text-foreground/85">{children}</h3>
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
  code: ({ children }) => (
    <code className="rounded-md bg-muted px-1.5 py-0.5 text-xs text-foreground/85">{children}</code>
  ),
  blockquote: ({ children }) => (
    <blockquote className="my-4 border-l-2 border-primary/40 pl-4 text-sm text-muted-foreground">{children}</blockquote>
  ),
};

export function StudyNoteView({ docId, docStatus, embedded, onGenerated, onContextChange }: Props) {
  const [note, setNote] = useState<StudyNoteRecord | null>(null);
  const [history, setHistory] = useState<StudyNoteRecord[]>([]);
  const [activeVersionIdx, setActiveVersionIdx] = useState(0);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [requirements, setRequirements] = useState("");
  const abortRef = useRef<AbortController | null>(null);

  const isReady = docStatus === "COMPLETED";
  const generatedAt = note?.createdAt ? new Date(note.createdAt).toLocaleString("zh-CN") : "";
  const hasHistory = history.length > 1;

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

  const handleGenerate = async () => {
    if (!docId || generating) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setGenerating(true);
    try {
      await generateStudyNote(docId, requirements.trim() || undefined, controller.signal);
      // reload history to include newly generated note
      const list = await listNoteHistory(docId);
      loadHistory(list);
      onGenerated?.();
      toast.success("学习笔记生成成功");
    } catch (err) {
      if ((err as Error).name !== "AbortError") {
        toast.error(err instanceof Error ? err.message : "学习笔记生成失败");
      }
    }
    abortRef.current = null;
    setGenerating(false);
  };

  const cancelGeneration = () => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
      setGenerating(false);
      toast.info("已取消生成");
    }
  };

  const switchVersion = (idx: number) => {
    if (idx >= 0 && idx < history.length) {
      setActiveVersionIdx(idx);
      setNote(history[idx]);
    }
  };

  const handleCopy = async () => {
    if (!note?.content) return;
    await navigator.clipboard.writeText(note.content);
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
      {!embedded && (
        <div className="flex shrink-0 items-center justify-between border-b bg-muted/20 px-6 py-3">
          <div className="flex items-center gap-2">
            <NotebookText className="h-4 w-4 text-muted-foreground" />
            <div>
              <h2 className="text-sm font-medium text-foreground/85">学习笔记</h2>
              {generatedAt && <p className="text-[11px] text-muted-foreground/60">生成时间 {generatedAt}</p>}
            </div>
          </div>
        <div className="flex items-center gap-2">
          {note?.content && (
            <>
              <Button size="sm" variant="outline" className="h-8 rounded-xl gap-1.5" onClick={handleCopy}>
                <Clipboard className="h-3.5 w-3.5" />
                复制
              </Button>
              <Button size="sm" variant="outline" className="h-8 rounded-xl gap-1.5" onClick={handleDownload}>
                <Download className="h-3.5 w-3.5" />
                导出
              </Button>
            </>
          )}
          {generating ? (
            <Button size="sm" variant="outline" className="h-8 rounded-xl gap-1.5 border-destructive/30 text-destructive hover:bg-destructive/10" onClick={cancelGeneration}>
              <RotateCw className="h-3.5 w-3.5 animate-spin" />取消生成
            </Button>
          ) : (
            <Button size="sm" className="h-8 rounded-xl gap-1.5" onClick={handleGenerate} disabled={!isReady}>
              <Sparkles className="h-3.5 w-3.5" />
              {note?.content ? "重新生成" : "一键生成"}
            </Button>
          )}
        </div>
      </div>
      )}

      {isReady && (
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
              <button onClick={() => setRequirements("")} className="shrink-0 text-[10px] text-muted-foreground/40 hover:text-muted-foreground">
                清除
              </button>
            )}
            {generating ? (
              <Button size="sm" variant="outline" className="h-7 rounded-lg gap-1 shrink-0 border-destructive/30 text-destructive hover:bg-destructive/10" onClick={cancelGeneration}>
                <RotateCw className="h-3 w-3 animate-spin" />取消
              </Button>
            ) : (
              <Button size="sm" className="h-7 rounded-lg gap-1 shrink-0" onClick={handleGenerate}>
                <Sparkles className="h-3 w-3" />
                {note?.content ? "重新生成" : "生成笔记"}
              </Button>
            )}
          </div>
        </div>
      )}

      {/* Version history tabs */}
      {hasHistory && (
        <div className="shrink-0 flex items-center gap-1 px-6 pb-2">
          <span className="text-[10px] text-muted-foreground/40 mr-1">历史:</span>
          {history.map((h, i) => (
            <button
              key={h.id ?? i}
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
          {note?.requirements && (
            <span className="text-[10px] text-muted-foreground/40 ml-2 truncate max-w-[200px]">
              · {note.requirements}
            </span>
          )}
        </div>
      )}

      <div className="flex-1 overflow-y-auto p-6">
        {!isReady ? (
          <EmptyState title="文档仍在处理中" description="向量化完成后才能基于文档内容生成学习笔记。" />
        ) : !note?.content ? (
          <EmptyState title="尚无学习笔记" description="一键生成后，系统会提炼中文概述、核心知识点、易混淆点和复习问题。" onGenerate={handleGenerate} generating={generating} onCancelGeneration={cancelGeneration} requirements={requirements} onRequirementsChange={setRequirements} />
        ) : (
          <div className="mx-auto grid max-w-6xl grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_280px]">
            <Card className="rounded-2xl border-border/60 bg-card/95 shadow-sm">
              <CardContent className="p-6 sm:p-8">
                <article className="max-w-none">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
                    {note.content}
                  </ReactMarkdown>
                </article>
              </CardContent>
            </Card>

            <aside className="space-y-3">
              <Card className="rounded-2xl border-border/60 bg-muted/20 shadow-sm">
                <CardContent className="p-4">
                  <div className="flex items-center gap-2 text-sm font-medium text-foreground/80">
                    <BookOpenCheck className="h-4 w-4 text-emerald-500" />
                    笔记结构
                  </div>
                  <div className="mt-3 space-y-2 text-xs text-muted-foreground">
                    {["文档概述", "核心知识点", "关键概念解释", "易混淆点", "复习清单", "可自测问题"].map((item) => (
                      <div key={item} className="rounded-lg bg-background/60 px-3 py-2">{item}</div>
                    ))}
                  </div>
                </CardContent>
              </Card>

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
          </div>
        )}
      </div>
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
        <Button variant="outline" onClick={onCancelGeneration} className="h-10 rounded-xl gap-2 border-destructive/30 text-destructive hover:bg-destructive/10">
          <RotateCw className="h-4 w-4 animate-spin" />取消生成
        </Button>
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
