"use client";

import { useEffect, useState } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { toast } from "sonner";
import { BookOpenCheck, Clipboard, Download, NotebookText, RotateCw, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import type { StudyNoteRecord } from "@/lib/api";
import { generateStudyNote, getStudyNote } from "@/lib/api";
import { cn } from "@/lib/utils";

interface Props {
  docId: number | null;
  docStatus?: string | null;
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

export function StudyNoteView({ docId, docStatus }: Props) {
  const [note, setNote] = useState<StudyNoteRecord | null>(null);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);

  const isReady = docStatus === "COMPLETED";
  const generatedAt = note?.createdAt ? new Date(note.createdAt).toLocaleString("zh-CN") : "";

  useEffect(() => {
    let cancelled = false;
    const loadNote = async () => {
      if (!docId) {
        if (!cancelled) setNote(null);
        return;
      }
      setLoading(true);
      try {
        const cached = await getStudyNote(docId);
        if (!cancelled) setNote(cached);
      } catch {
        if (!cancelled) setNote(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void loadNote();
    return () => {
      cancelled = true;
    };
  }, [docId]);

  const handleGenerate = async () => {
    if (!docId || generating) return;
    setGenerating(true);
    try {
      const generated = await generateStudyNote(docId);
      setNote(generated);
      toast.success("学习笔记生成成功");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "学习笔记生成失败");
    }
    setGenerating(false);
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
          <Button size="sm" className="h-8 rounded-xl gap-1.5" onClick={handleGenerate} disabled={generating || !isReady}>
            {generating ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            {note?.content ? "重新生成" : generating ? "生成中..." : "一键生成"}
          </Button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-6">
        {!isReady ? (
          <EmptyState title="文档仍在处理中" description="向量化完成后才能基于文档内容生成学习笔记。" />
        ) : !note?.content ? (
          <EmptyState title="尚无学习笔记" description="一键生成后，系统会提炼中文概述、核心知识点、易混淆点和复习问题。" onGenerate={handleGenerate} generating={generating} />
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
}: {
  title: string;
  description: string;
  onGenerate?: () => void;
  generating?: boolean;
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
      {onGenerate && (
        <Button onClick={onGenerate} disabled={generating} className={cn("h-10 rounded-xl gap-2", generating && "opacity-80")}>
          {generating ? <RotateCw className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
          {generating ? "生成中..." : "一键生成学习笔记"}
        </Button>
      )}
    </div>
  );
}
