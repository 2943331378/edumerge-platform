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
import { listMindMaps, getMindMapDetail, generateMindMap, deleteMindMap } from "@/lib/api";
import { MindMapViewer } from "./MindMapViewer";

interface Props {
  docId: number | null;
  docStatus?: string | null;
  embedded?: boolean;
  onContextChange?: (hint: string) => void;
  /** 大纲选中的章节 IDs */
  selectedOutlineSections?: string[];
  /** 大纲触发生成信号，counter 变化时自动触发 */
  generateTrigger?: { type: string; counter: number };
}

export function MindMapView({ docId, docStatus, embedded, onContextChange, selectedOutlineSections, generateTrigger }: Props) {
  const [view, setView] = useState<"list" | "viewer">("list");
  const [mindMaps, setMindMaps] = useState<MindMapRecord[]>([]);
  const [currentMap, setCurrentMap] = useState<MindMapRecord | null>(null);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  const isReady = docStatus === "COMPLETED";

  // 加载列表
  const reloadList = useCallback(async () => {
    if (!docId) { setMindMaps([]); return; }
    try {
      const list = await listMindMaps(docId);
      setMindMaps(list);
    } catch { setMindMaps([]); }
  }, [docId]);

  useEffect(() => { reloadList(); setView("list"); setCurrentMap(null); }, [docId]);

  // 生成思维导图
  const handleGenerate = useCallback(async () => {
    if (!docId || generating) return;
    setGenerating(true);
    try {
      const sectionContext = selectedOutlineSections && selectedOutlineSections.length > 0
        ? selectedOutlineSections.join(", ")
        : undefined;
      const data = await generateMindMap(docId, sectionContext);
      setCurrentMap(data);
      setView("viewer");
      await reloadList();
      toast.success("思维导图生成成功");
    } catch {
      toast.error("思维导图生成失败");
    }
    setGenerating(false);
  }, [docId, generating, selectedOutlineSections, reloadList]);

  // 从大纲跳转自动触发
  const triggerRef = useRef(0);
  useEffect(() => {
    if (generateTrigger && generateTrigger.counter > triggerRef.current && generateTrigger.type === "mindmap") {
      triggerRef.current = generateTrigger.counter;
      requestAnimationFrame(() => handleGenerate());
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
            <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 border-destructive/30 text-destructive hover:bg-destructive/10" onClick={() => { abortRef.current?.abort(); setGenerating(false); toast.info("已取消"); }}>
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
        {generating ? (
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
                    <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">MINDMAP</span>
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
