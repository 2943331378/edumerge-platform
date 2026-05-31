"use client";

import { useState, useCallback } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";
import { createFlowNote, markChatHelpful } from "@/lib/api";
import { User, Bot, ChevronDown, ChevronUp, FileText, Copy, Check, RefreshCw, AlertCircle, ThumbsUp, ThumbsDown, BookmarkPlus } from "lucide-react";

export interface SourceRef {
  index: number;
  documentId: string;
  chunkIndex: number;
  content: string;
  score: number;
}

export interface MessageData {
  id?: string;
  role: "user" | "assistant";
  content: string;
  sources?: SourceRef[];
  loading?: boolean;
  error?: boolean;
  chatHistoryId?: number;
}

/** 代码块组件 — 带复制按钮 */
function CodeBlock({ code, language }: { code: string; language?: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(async () => {
    await navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }, [code]);

  return (
    <div className="group relative my-2">
      <div className="flex items-center justify-between rounded-t-xl bg-zinc-800 dark:bg-zinc-800 px-4 py-1.5">
        <span className="text-[10px] text-zinc-400 font-mono">
          {language || "code"}
        </span>
        <Button
          variant="ghost"
          size="icon"
          className="h-6 w-6 rounded-md hover:bg-zinc-700"
          onClick={handleCopy}
        >
          {copied ? (
            <Check className="h-3 w-3 text-emerald-400" />
          ) : (
            <Copy className="h-3 w-3 text-zinc-400" />
          )}
        </Button>
      </div>
      <pre className="overflow-x-auto rounded-b-xl bg-zinc-950 dark:bg-zinc-900 px-4 py-3 text-xs text-zinc-100">
        <code>{code}</code>
      </pre>
    </div>
  );
}

function scoreLabel(score: number): string {
  if (score >= 0.9) return "高度相关";
  if (score >= 0.75) return "相关";
  return "部分相关";
}

export function MessageBubble({ message, onRetry, docId }: { message: MessageData; onRetry?: () => void; docId?: number | null }) {
  const isUser = message.role === "user";
  const [sourcesExpanded, setSourcesExpanded] = useState(false);
  const [feedback, setFeedback] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const isLoading = message.loading === true && !message.content;
  const isError = message.error === true;

  const handleFeedback = async (isHelpful: number) => {
    if (feedback !== null || !message.chatHistoryId) return;
    setFeedback(isHelpful);
    try {
      await markChatHelpful(message.chatHistoryId, isHelpful);
      toast.success("感谢反馈");
    } catch {
      setFeedback(null);
    }
  };

  const handleSaveToNotes = async () => {
    if (saving) return;
    if (!docId) {
      toast.info("请先选择文档后再保存笔记");
      return;
    }
    setSaving(true);
    try {
      const title = message.content.slice(0, 30).replace(/\n/g, " ");
      await createFlowNote({
        docId,
        category: "KEY_POINT",
        title: title || "对话要点",
        content: message.content,
      });
      toast.success("已保存为学习日志");
    } catch {
      toast.error("保存失败");
    }
    setSaving(false);
  };

  return (
    <div className={cn("flex gap-3 px-6 py-4", !isUser && "bg-muted/20")}>
      {/* Avatar */}
      <div
        className={cn(
          "flex h-7 w-7 shrink-0 items-center justify-center rounded-lg",
          isUser
            ? "bg-primary/10 text-primary"
            : isError
              ? "bg-destructive/10 text-destructive"
              : "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400"
        )}
      >
        {isUser ? <User className="h-3.5 w-3.5" /> : isError ? <AlertCircle className="h-3.5 w-3.5" /> : <Bot className="h-3.5 w-3.5" />}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0 space-y-2">
        {isLoading ? (
          <div className="space-y-2 py-1">
            <Skeleton className="h-4 w-3/4 rounded-md" />
            <Skeleton className="h-4 w-1/2 rounded-md" />
            <Skeleton className="h-4 w-2/3 rounded-md" />
            <p className="text-[11px] text-muted-foreground/50 pt-1">AI 正在思考...</p>
          </div>
        ) : (
          <>
            {/* Markdown content */}
            <div className="prose prose-sm dark:prose-invert max-w-none text-sm leading-relaxed break-words">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                components={{
                  pre: ({ children }) => {
                    // 提取代码文本用于自定义 CodeBlock
                    const child = children as { props?: { children?: string; className?: string } } | undefined;
                    const code = typeof child?.props?.children === "string" ? child.props.children : "";
                    const lang = child?.props?.className?.replace("language-", "") || undefined;
                    if (code) {
                      return <CodeBlock code={code} language={lang} />;
                    }
                    return (
                      <pre className="overflow-x-auto rounded-xl bg-zinc-950 dark:bg-zinc-900 px-4 py-3 my-2 text-xs text-zinc-100">
                        {children}
                      </pre>
                    );
                  },
                  code: ({ children, ...props }) => {
                    // Inline code only — block code handled by pre
                    return (
                      <code className="rounded-md bg-muted px-1.5 py-0.5 text-[13px] font-mono" {...props}>
                        {children}
                      </code>
                    );
                  },
                  ul: ({ children }) => (
                    <ul className="list-disc pl-5 my-1.5 space-y-0.5">{children}</ul>
                  ),
                  ol: ({ children }) => (
                    <ol className="list-decimal pl-5 my-1.5 space-y-0.5">{children}</ol>
                  ),
                  p: ({ children }) => (
                    <p className="my-1.5 leading-relaxed">{children}</p>
                  ),
                  strong: ({ children }) => (
                    <strong className="font-semibold text-foreground">{children}</strong>
                  ),
                  blockquote: ({ children }) => (
                    <blockquote className="border-l-2 border-primary/30 pl-4 my-2 italic text-muted-foreground">
                      {children}
                    </blockquote>
                  ),
                }}
              >
                {message.content}
              </ReactMarkdown>
            </div>

            {/* Error state — retry button */}
            {isError && onRetry && (
              <Button
                variant="outline"
                size="sm"
                className="rounded-lg text-xs gap-1.5 h-7 mt-1"
                onClick={onRetry}
              >
                <RefreshCw className="h-3 w-3" />
                重新生成
              </Button>
            )}

            {/* Action buttons (AI only, not loading, not error) */}
            {!isUser && !isLoading && !isError && message.content && (
              <div className="flex items-center gap-1 pt-1">
                <button
                  type="button"
                  onClick={handleSaveToNotes}
                  disabled={saving}
                  className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] text-muted-foreground/50 hover:text-foreground hover:bg-muted/50 transition-colors"
                  title="保存为学习日志"
                >
                  <BookmarkPlus className="h-3 w-3" />
                  {saving ? "保存中..." : "保存为笔记"}
                </button>
                {message.chatHistoryId && (
                  <>
                    <span className="text-muted-foreground/20">|</span>
                    <button
                      type="button"
                      onClick={() => handleFeedback(1)}
                      disabled={feedback !== null}
                      className={`inline-flex items-center gap-1 px-1.5 py-1 rounded-md text-[10px] transition-colors ${
                        feedback === 1 ? "text-emerald-500 bg-emerald-50 dark:bg-emerald-950/20" : "text-muted-foreground/50 hover:text-emerald-500 hover:bg-emerald-50 dark:hover:bg-emerald-950/20"
                      }`}
                      title="有帮助"
                    >
                      <ThumbsUp className="h-3 w-3" />
                    </button>
                    <button
                      type="button"
                      onClick={() => handleFeedback(0)}
                      disabled={feedback !== null}
                      className={`inline-flex items-center gap-1 px-1.5 py-1 rounded-md text-[10px] transition-colors ${
                        feedback === 0 ? "text-destructive bg-destructive/10" : "text-muted-foreground/50 hover:text-destructive hover:bg-destructive/10"
                      }`}
                      title="无帮助"
                    >
                      <ThumbsDown className="h-3 w-3" />
                    </button>
                  </>
                )}
              </div>
            )}

            {/* Sources section (AI only) */}
            {!isUser && message.sources && message.sources.length > 0 && (
              <div className="pt-1">
                <button
                  onClick={() => setSourcesExpanded((v) => !v)}
                  className="inline-flex items-center gap-1.5 text-[11px] text-muted-foreground/60 hover:text-muted-foreground transition-colors"
                >
                  <FileText className="h-3 w-3" />
                  参考来源 ({message.sources.length})
                  {sourcesExpanded ? (
                    <ChevronUp className="h-3 w-3" />
                  ) : (
                    <ChevronDown className="h-3 w-3" />
                  )}
                </button>

                {sourcesExpanded && (
                  <div className="mt-2 space-y-1.5">
                    {message.sources.map((s) => (
                      <div
                        key={s.index}
                        className="rounded-lg border border-border/60 bg-background/60 px-3 py-2 text-xs"
                      >
                        <div className="flex items-center gap-2 mb-1">
                          <span className="font-medium text-foreground/80">
                            段落 {s.index}
                          </span>
                          <span className={cn(
                            "text-[10px]",
                            s.score >= 0.9 ? "text-emerald-500" : s.score >= 0.75 ? "text-amber-500" : "text-muted-foreground",
                          )}>
                            {scoreLabel(s.score)}
                          </span>
                        </div>
                        <p className="text-muted-foreground leading-relaxed line-clamp-3">
                          {s.content}
                        </p>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
