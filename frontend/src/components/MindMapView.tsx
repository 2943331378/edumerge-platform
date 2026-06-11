"use client";

/**
 * 思维导图管理视图 — Deck 列表 + 导图查看器
 *
 * 支持多份思维导图并存，按章节选中生成不同版本
 */

import { useState, useEffect, useRef, useCallback } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { GitFork, ArrowLeft, Sparkles, RotateCw, Trash2, Loader2, XCircle } from "lucide-react";
import { toast } from "sonner";
import type { MindMapRecord } from "@/lib/api";
import { listMindMaps, getMindMapDetail, generateMindMap, generateMindMapStream, deleteMindMap } from "@/lib/api";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { MindMapViewer } from "./MindMapViewer";

interface Props {
  docId: number | null;
  docStatus?: string | null;
  embedded?: boolean;
  onContextChange?: (hint: string) => void;
  /** 大纲选中的章节上下文（含 ID 和标题） */
  sectionContext?: string;
  /** 大纲选中章节的 chunk 范围 */
  startChunk?: number;
  endChunk?: number;
  /** 大纲触发生成信号，counter 变化时自动触发 */
  generateTrigger?: { type: string; counter: number };
  generating?: boolean;
  onGeneratingChange?: (v: boolean) => void;
  onGenerated?: () => void;
}

export function MindMapView({ docId, docStatus, embedded, onContextChange, sectionContext, startChunk, endChunk, generateTrigger, generating = false, onGeneratingChange, onGenerated }: Props) {
  const [view, setView] = useState<"list" | "viewer">("list");
  const [mindMaps, setMindMaps] = useState<MindMapRecord[]>([]);
  const [currentMap, setCurrentMap] = useState<MindMapRecord | null>(null);
  const [loading, setLoading] = useState(false);
  const [streamingContent, setStreamingContent] = useState<string | null>(null);
  const [streamingError, setStreamingError] = useState<string | null>(null);
  const [streamingProgress, setStreamingProgress] = useState(0);
  const streamingRef = useRef("");
  const rafIdRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const generatingRef = useRef(generating);
  generatingRef.current = generating;
  const mountedRef = useRef(true);
  const prevCounterRef = useRef<number | undefined>(undefined);

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

  const isReady = docStatus === "COMPLETED";

  // 加载列表
  const reloadList = useCallback(async () => {
    if (!docId) { setMindMaps([]); return; }
    try {
      const list = await listMindMaps(docId);
      setMindMaps(list);
    } catch { setMindMaps([]); toast.error("加载思维导图列表失败"); }
  }, [docId]);

  useEffect(() => { reloadList(); setView("list"); setCurrentMap(null); }, [docId]);

  // 生成思维导图（流式）
  const handleGenerate = useCallback(async () => {
    if (!docId || generating) return;
    const controller = new AbortController();
    abortRef.current = controller;
    const timeoutId = setTimeout(() => controller.abort(), 290_000);
    onGeneratingChange?.(true);
    streamingRef.current = "";
    setStreamingContent("");
    setStreamingError(null);
    setStreamingProgress(0);

    try {
      await generateMindMapStream(docId, {
        sectionContext: sectionContext || undefined,
        startChunk,
        endChunk,
        signal: controller.signal,
        onToken: (token) => {
          if (!mountedRef.current || abortRef.current !== controller) return;
          streamingRef.current += token;
          if (rafIdRef.current === null) {
            rafIdRef.current = requestAnimationFrame(() => {
              rafIdRef.current = null;
              if (mountedRef.current && abortRef.current === controller) {
                setStreamingContent(streamingRef.current);
              }
            });
          }
        },
        onProgress: (p) => {
          if (mountedRef.current && abortRef.current === controller) setStreamingProgress(p);
        },
        onDone: async (meta) => {
          if (rafIdRef.current !== null) { cancelAnimationFrame(rafIdRef.current); rafIdRef.current = null; }
          if (!mountedRef.current || abortRef.current !== controller) return;
          setStreamingContent(null);
          setStreamingError(null);
          setStreamingProgress(0);
          streamingRef.current = "";
          setCurrentMap(meta);
          setView("viewer");
          await reloadList();
          if (!mountedRef.current || abortRef.current !== controller) return;
          toast.success("思维导图生成成功");
          onGenerated?.();
        },
        onError: (msg) => {
          if (rafIdRef.current !== null) { cancelAnimationFrame(rafIdRef.current); rafIdRef.current = null; }
          if (!mountedRef.current || abortRef.current !== controller) return;
          setStreamingError(msg || "生成中断");
          onGeneratingChange?.(false);
        },
      });
    } catch (err) {
      if (rafIdRef.current !== null) { cancelAnimationFrame(rafIdRef.current); rafIdRef.current = null; }
      if (!mountedRef.current || abortRef.current !== controller) return;
      if ((err as Error).name !== "AbortError") {
        setStreamingError(err instanceof Error ? err.message : "生成中断");
        toast.error(err instanceof Error ? err.message : "思维导图生成失败");
      } else {
        setStreamingContent(null);
        streamingRef.current = "";
      }
      onGeneratingChange?.(false);
    }
    clearTimeout(timeoutId);
    if (abortRef.current === controller) {
      abortRef.current = null;
      onGeneratingChange?.(false);
    }
  }, [docId, generating, sectionContext, startChunk, endChunk, reloadList, onGeneratingChange]);

  // 从大纲跳转自动触发
  useEffect(() => {
    const counter = generateTrigger?.counter;
    if (counter !== undefined && counter !== prevCounterRef.current && generateTrigger?.type === "mindmap") {
      prevCounterRef.current = counter;
      handleGenerate();
    } else {
      prevCounterRef.current = counter;
    }
  }, [generateTrigger?.counter]);

  // 查看指定导图
  const handleView = useCallback(async (deckId: number) => {
    try {
      const data = await getMindMapDetail(deckId);
      setCurrentMap(data);
      setView("viewer");
    } catch {
      toast.error("加载思维导图失败");
    }
  }, []);

  // 删除
  const handleDelete = useCallback(async (e: React.MouseEvent, deckId: number) => {
    e.stopPropagation();
    if (!window.confirm("确定要删除此思维导图？")) return;
    try {
      await deleteMindMap(deckId);
      setMindMaps((prev) => prev.filter((m) => m.deckId !== deckId));
      toast.success("思维导图已删除");
    } catch {
      toast.error("删除失败");
    }
  }, []);

  // ═══ 查看器模式 ═══
  if (view === "viewer" && currentMap) {
    return (
      <div className="flex flex-col h-full">
        {/* 顶部栏 */}
        <div className="shrink-0 overflow-hidden transition-all duration-300 ease-in-out">
          <div className="flex items-center gap-2 px-4 py-2 border-b bg-muted/20">
            <Button variant="ghost" size="sm" className="h-7 rounded-lg text-xs gap-1.5" onClick={() => { setView("list"); setCurrentMap(null); }}>
              <ArrowLeft className="h-3 w-3" />
              返回列表
            </Button>
            <span className="text-[11px] text-muted-foreground/40">/</span>
            <span className="text-[11px] text-muted-foreground truncate max-w-[200px]">{currentMap.title}</span>
          </div>
        </div>

        {/* 思维导图 */}
        <div className="flex-1 min-h-0">
          <MindMapViewer markdown={currentMap.content} className="h-full" onContextChange={onContextChange} />
        </div>
      </div>
    );
  }

  // ═══ 列表模式 ═══
  return (
    <div className="flex flex-col h-full">
      {/* 顶部工具栏 */}
      <div className="flex items-center justify-between px-6 py-3 border-b bg-muted/20 shrink-0">
        <h2 className="text-sm font-medium text-foreground/80 flex items-center gap-2">
          <GitFork className="h-4 w-4 text-muted-foreground" />
          思维导图
        </h2>
        <div className="flex items-center gap-2">
          {generating ? (
            <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 border-destructive/30 text-destructive hover:bg-destructive/10" onClick={() => { abortRef.current?.abort(); setStreamingContent(null); setStreamingError(null); streamingRef.current = ""; onGeneratingChange?.(false); toast.info("已取消"); }}>
              <XCircle className="h-3.5 w-3.5" />
              取消生成
            </Button>
          ) : (
            <Button size="sm" className="rounded-xl gap-1.5 h-8" onClick={handleGenerate} disabled={!isReady || generating}>
              {generating ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
              一键生成
            </Button>
          )}
        </div>
      </div>

      {/* 内容 */}
      <div className="flex-1 overflow-y-auto p-6">
        {generating && streamingContent !== null ? (
          /* 流式生成中 — 实时预览 Markdown */
          <div className="mx-auto max-w-4xl">
            <Card className="rounded-2xl border-border/60 bg-card/95 shadow-sm">
              <CardContent className="p-6 sm:p-8">
                {streamingError ? (
                  <div className="mb-4 flex items-center justify-between rounded-lg bg-destructive/10 px-3 py-2 text-xs text-destructive">
                    <span>{streamingError}</span>
                    <Button size="sm" variant="outline" className="h-6 rounded-md gap-1 text-xs border-destructive/30 text-destructive hover:bg-destructive/10" onClick={handleGenerate}>
                      <RotateCw className="h-3 w-3" />重试
                    </Button>
                  </div>
                ) : (
                  <div className="mb-4 space-y-2">
                    <div className="flex items-center gap-2 text-xs text-primary/70">
                      <RotateCw className="h-3.5 w-3.5 animate-spin" />
                      正在生成思维导图...
                      {streamingProgress > 0 && <span className="text-muted-foreground/50">{streamingProgress}%</span>}
                    </div>
                    {streamingProgress > 0 && (
                      <div className="h-1 w-full overflow-hidden rounded-full bg-muted/50">
                        <div className="h-full rounded-full bg-primary/50 transition-all duration-300" style={{ width: `${streamingProgress}%` }} />
                      </div>
                    )}
                  </div>
                )}
                <article className="max-w-none text-sm leading-7 text-foreground/75">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{streamingContent}</ReactMarkdown>
                </article>
              </CardContent>
            </Card>
          </div>
        ) : generating ? (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
            <Loader2 className="h-8 w-8 animate-spin text-primary" />
            <div className="text-center space-y-1">
              <p className="text-sm font-medium">AI 正在生成思维导图...</p>
              <p className="text-xs text-muted-foreground/50">正在解析文档结构，提取知识层级</p>
            </div>
          </div>
        ) : mindMaps.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-6 text-muted-foreground">
            <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
              <GitFork className="h-7 w-7 text-muted-foreground/40" />
            </div>
            <div className="text-center space-y-1.5">
              <p className="text-sm font-medium">暂无思维导图</p>
              <p className="text-xs text-muted-foreground/50 max-w-xs">
                AI 将自动分析文档结构，生成可交互的思维导图
              </p>
            </div>
            <Button onClick={handleGenerate} disabled={!isReady} className="rounded-xl gap-2 h-10">
              <Sparkles className="h-4 w-4" />
              一键生成思维导图
            </Button>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 max-w-5xl mx-auto">
            {mindMaps.map((mm) => (
              <Card
                key={mm.deckId}
                className="group relative cursor-pointer rounded-2xl border-border/60 shadow-sm hover:shadow-md hover:border-primary/30 transition-all"
                onClick={() => handleView(mm.deckId)}
              >
                <button
                  type="button"
                  onClick={(e) => handleDelete(e, mm.deckId)}
                  className="absolute top-3 right-3 opacity-0 group-hover:opacity-60 hover:!opacity-100 hover:text-destructive rounded-md p-1 transition-opacity z-10"
                  title="删除此思维导图"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
                <CardContent className="p-5 space-y-2">
                  <div className="flex items-center gap-2">
                    <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10">
                      <GitFork className="h-4 w-4 text-primary" />
                    </div>
                    <span className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">MINDMAP</span>
                  </div>
                  <p className="text-sm font-medium text-foreground/85 leading-snug pr-6">{mm.title}</p>
                  <p className="text-[11px] text-muted-foreground/50">
                    {mm.createdAt ? new Date(mm.createdAt).toLocaleString("zh-CN") : ""}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
