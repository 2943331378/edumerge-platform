"use client";

/**
 * 学习卡片组件 — 分层治理与生命周期管理
 *
 * 架构设计遵循数据素质要求:
 * - 分层组织: Deck(组) → Flashcard(卡片), 实现学习资源的层级化管理
 * - 生命周期: 创建(一键生成) → 使用(翻转学习) → 归档/删除
 * - 可追溯性: 每张卡片记录 sourceSegment, 可溯源至原始文档片段
 * - 数据治理: 通过 deck_id 外键实现卡片与组的关联, 支持批量删除与分组查询
 */

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Sparkles, Layers, RotateCw, ChevronLeft, ChevronRight, ArrowLeft, Trash2, Sparkle, GitFork } from "lucide-react";
import type { DeckRecord, FlashcardItem } from "@/lib/api";
import { listDecks, listFlashcardsByDeck, generateFlashcards as generateApi, deleteDeck, getMindMap } from "@/lib/api";
import { toast } from "sonner";

interface Props {
  docId: number | null;
  docUuid: string | null;
  sessionId: number | null;
  onMindMapGenerated?: () => void;
}

/** 判断 deck 是否在 24h 内新建 */
function isNewDeck(createdAt: string): boolean {
  return Date.now() - new Date(createdAt).getTime() < 24 * 60 * 60 * 1000;
}

export function FlashcardView({ docId, docUuid, sessionId, onMindMapGenerated }: Props) {
  const [view, setView] = useState<"decks" | "cards">("decks");
  const [decks, setDecks] = useState<DeckRecord[]>([]);
  const [cards, setCards] = useState<FlashcardItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [mindMapGenerating, setMindMapGenerating] = useState(false);
  const [mindMapProgress, setMindMapProgress] = useState(0);
  const [currentDeck, setCurrentDeck] = useState<DeckRecord | null>(null);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [flipped, setFlipped] = useState(false);

  const mindMapProgressTexts = [
    "正在解构知识要素...",
    "正在提取层级结构...",
    "正在识别核心主题...",
    "正在生成思维导图...",
    "正在组织知识图谱...",
  ];
  const [mindMapTextIdx, setMindMapTextIdx] = useState(0);

  const reloadDecks = async () => {
    if (!docId) return;
    try { setDecks(await listDecks(docId, "FLASHCARD")); } catch { setDecks([]); }
  };

  // docId 变化时自动重载 deck 列表并回到列表视图
  useEffect(() => { reloadDecks(); setView("decks"); }, [docId]);

  // 思维导图生成进度文字轮播
  useEffect(() => {
    if (!mindMapGenerating) return;
    const timer = setInterval(() => {
      setMindMapTextIdx((i) => (i + 1) % mindMapProgressTexts.length);
      setMindMapProgress((p) => Math.min(p + 8, 90));
    }, 2000);
    return () => clearInterval(timer);
  }, [mindMapGenerating]); // eslint-disable-line react-hooks/exhaustive-deps

  /** 生成全书思维大纲 → 自动跳转导图页 */
  const handleGenerateMindMap = async () => {
    if (!docId || mindMapGenerating) return;
    setMindMapGenerating(true);
    setMindMapProgress(0);
    setMindMapTextIdx(0);
    try {
      await getMindMap(docId);
      setMindMapProgress(100);
      toast.success("思维导图生成成功");
      setTimeout(() => onMindMapGenerated?.(), 400);
    } catch {
      toast.error("思维导图生成失败");
    }
    setMindMapGenerating(false);
  };

  const enterDeck = async (deck: DeckRecord) => {
    setCurrentDeck(deck);
    setLoading(true);
    try {
      setCards(await listFlashcardsByDeck(deck.id));
      setCurrentIdx(0);
      setFlipped(false);
      setView("cards");
    } catch { toast.error("加载卡片失败"); }
    setLoading(false);
  };

  /** 生成后自动加载列表并进入最新 Deck */
  const handleGenerate = async () => {
    if (!sessionId) return;
    setLoading(true);
    try {
      await generateApi(undefined, undefined, sessionId);
      const fresh = await listDecks(docId!, "FLASHCARD");
      setDecks(fresh);
      toast.success("学习卡片生成成功");
      // 自动进入最新创建的 Deck
      if (fresh.length > 0) {
        await enterDeck(fresh[0]);
      }
    } catch { toast.error("卡片生成失败"); }
    setLoading(false);
  };

  const handleDeleteDeck = async (e: React.MouseEvent, deckId: number) => {
    e.stopPropagation();
    try {
      await deleteDeck(deckId);
      setDecks((prev) => prev.filter((d) => d.id !== deckId));
      toast.success("卡片组已删除");
    } catch { toast.error("删除失败"); }
  };

  const goTo = (i: number) => {
    setFlipped(false);
    setCurrentIdx(Math.max(0, Math.min(i, cards.length - 1)));
  };

  // ═══════════════ Deck 列表视图 ═══════════════
  if (view === "decks") {
    return (
      <div className="flex flex-col h-full">
        {/* 顶部工具栏 */}
        <div className="flex items-center justify-between px-6 py-3 border-b bg-muted/20 shrink-0">
          <h2 className="text-sm font-medium text-foreground/80 flex items-center gap-2">
            <Layers className="h-4 w-4 text-muted-foreground" />
            学习卡片组
          </h2>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 border-purple-200 dark:border-purple-800 text-purple-700 dark:text-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/30" onClick={handleGenerateMindMap} disabled={mindMapGenerating || !docId}>
              {mindMapGenerating ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <GitFork className="h-3.5 w-3.5" />}
              {mindMapGenerating ? "生成大纲中..." : "全书思维大纲"}
            </Button>
            <Button size="sm" className="rounded-xl gap-1.5 h-8" onClick={handleGenerate} disabled={loading || !sessionId}>
              {loading ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
              {loading ? "生成中..." : "一键生成"}
            </Button>
          </div>
        </div>

        {/* 思维导图生成进度条 */}
        {mindMapGenerating && (
          <div className="px-6 py-2 border-b bg-purple-50/30 dark:bg-purple-950/10">
            <div className="flex items-center gap-3 max-w-3xl mx-auto">
              <GitFork className="h-3.5 w-3.5 text-purple-500 animate-pulse shrink-0" />
              <span className="text-[11px] text-purple-700 dark:text-purple-300 shrink-0 transition-all duration-500">
                {mindMapProgressTexts[mindMapTextIdx]}
              </span>
              <div className="flex-1 h-1 rounded-full bg-purple-200 dark:bg-purple-900 overflow-hidden">
                <div className="h-full rounded-full bg-purple-500 transition-all duration-700 ease-out" style={{ width: `${mindMapProgress}%` }} />
              </div>
              <span className="text-[10px] text-purple-400 shrink-0">{mindMapProgress}%</span>
            </div>
          </div>
        )}

        {/* 内容: 空状态 或 卡片组网格 */}
        <div className="flex-1 overflow-y-auto p-6">
          {decks.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full gap-6 text-muted-foreground">
              <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
                <Layers className="h-7 w-7 text-muted-foreground/40" />
              </div>
              <div className="text-center space-y-1.5">
                <p className="text-sm font-medium">尚无学习卡片组</p>
                <p className="text-xs text-muted-foreground/50 max-w-xs">
                  AI 将从文档中提取核心知识点生成卡片组，帮助你高效记忆
                </p>
              </div>
              <Button onClick={handleGenerate} disabled={loading || !sessionId} className="rounded-xl gap-2 h-10">
                {loading
                  ? <><RotateCw className="h-4 w-4 animate-spin" />生成中...</>
                  : <><Sparkles className="h-4 w-4" />一键生成学习任务</>}
              </Button>
            </div>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 max-w-5xl mx-auto">
              {decks.map((deck) => (
                <Card
                  key={deck.id}
                  className="group relative cursor-pointer rounded-2xl border-border/60 shadow-sm hover:shadow-md hover:border-primary/30 transition-all"
                  onClick={() => enterDeck(deck)}
                >
                  {/* 删除按钮 — 悬停显示 */}
                  <button
                    onClick={(e) => handleDeleteDeck(e, deck.id)}
                    className="absolute top-3 right-3 opacity-0 group-hover:opacity-60 hover:!opacity-100 hover:text-destructive rounded-md p-1 transition-opacity z-10"
                    title="删除此卡片组"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>

                  <CardContent className="p-5 space-y-2">
                    <div className="flex items-center gap-2">
                      <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10">
                        <Layers className="h-4 w-4 text-primary" />
                      </div>
                      <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">FLASHCARD</span>
                    </div>
                    <p className="text-sm font-medium text-foreground/85 leading-snug pr-6">{deck.title}</p>
                    <div className="flex items-center gap-2">
                      <p className="text-[11px] text-muted-foreground/50">{new Date(deck.createdAt).toLocaleString("zh-CN")}</p>
                      {/* 24h 内新建标签 */}
                      {isNewDeck(deck.createdAt) && (
                        <span className="inline-flex items-center gap-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400 bg-amber-100 dark:bg-amber-900/30 px-1.5 py-0.5 rounded-md">
                          <Sparkle className="h-2.5 w-2.5" />
                          New
                        </span>
                      )}
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          )}
        </div>
      </div>
    );
  }

  // ═══════════════ 卡片详情视图 (翻转学习) ═══════════════
  const card = cards[currentIdx];
  return (
    <div className="flex flex-col h-full">
      {/* 面包屑导航 */}
      <div className="flex items-center gap-2 px-4 py-2 border-b bg-muted/20 shrink-0">
        <Button variant="ghost" size="sm" className="h-7 rounded-lg text-xs gap-1.5" onClick={() => { setView("decks"); reloadDecks(); }}>
          <ArrowLeft className="h-3 w-3" />
          返回卡片组
        </Button>
        <span className="text-[11px] text-muted-foreground/40">/</span>
        <span className="text-[11px] text-muted-foreground truncate max-w-[200px]">{currentDeck?.title ?? ""}</span>
      </div>

      {/* 翻转卡片 — 大尺寸展示 */}
      <div className="flex-1 flex flex-col items-center justify-center gap-6 px-4 sm:px-8">
        <p className="text-xs text-muted-foreground/40 tracking-wider">{currentIdx + 1} / {cards.length}</p>

        {/* 容器: 宽大 + 渐变边框光晕 + 动画 */}
        <div
          className="w-full max-w-[800px] cursor-pointer [perspective:1200px] animate-card-enter"
          key={currentIdx}
          onClick={() => setFlipped((v) => !v)}
        >
          <div
            className="relative w-full min-h-[360px] sm:min-h-[450px] transition-transform duration-700 [transform-style:preserve-3d]"
            style={{ transform: flipped ? "rotateY(180deg)" : "rotateY(0deg)" }}
          >
            {/* Front — 问题 */}
            <Card className="absolute inset-0 rounded-3xl shadow-2xl [backface-visibility:hidden]
              border border-transparent bg-clip-padding
              before:absolute before:inset-0 before:rounded-3xl before:p-[1px] before:bg-gradient-to-br before:from-primary/40 before:via-primary/10 before:to-transparent before:-z-10 before:[mask:linear-gradient(#fff_0_0)_content-box,linear-gradient(#fff_0_0)] before:[mask-composite:exclude]
              bg-card">
              <CardContent className="flex flex-col items-center justify-center p-8 sm:p-12 h-full min-h-[360px] sm:min-h-[450px]">
                <span className="text-[11px] font-medium text-muted-foreground/40 uppercase tracking-[0.2em] mb-6">问题</span>
                <p className="text-xl sm:text-3xl text-center leading-relaxed text-foreground/90 font-medium max-w-2xl">
                  {card?.question}
                </p>
                <p className="mt-8 text-xs text-muted-foreground/30">点击翻转查看答案</p>
              </CardContent>
            </Card>

            {/* Back — 答案 */}
            <Card className="absolute inset-0 rounded-3xl shadow-2xl [backface-visibility:hidden] [transform:rotateY(180deg)]
              border border-transparent bg-clip-padding
              before:absolute before:inset-0 before:rounded-3xl before:p-[1px] before:bg-gradient-to-br before:from-emerald-400/40 before:via-emerald-400/10 before:to-transparent before:-z-10 before:[mask:linear-gradient(#fff_0_0)_content-box,linear-gradient(#fff_0_0)] before:[mask-composite:exclude]
              bg-card">
              <CardContent className="flex flex-col items-center p-8 sm:p-12 h-full min-h-[360px] sm:min-h-[450px] overflow-y-auto">
                <span className="text-[11px] font-medium text-emerald-500 dark:text-emerald-400 uppercase tracking-[0.2em] mb-6">答案</span>
                <p className="text-xl sm:text-3xl text-center leading-relaxed text-foreground/90 font-medium max-w-2xl">
                  {card?.answer}
                </p>
                {card?.explanation && (
                  <div className="mt-8 pt-6 border-t border-border/30 w-full max-w-2xl">
                    <span className="text-[10px] font-medium text-muted-foreground/40 uppercase tracking-wider">解析</span>
                    <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{card.explanation}</p>
                  </div>
                )}
              </CardContent>
            </Card>
          </div>
        </div>

        {/* 导航 */}
        <div className="flex items-center gap-3">
          <Button variant="outline" size="icon" className="h-8 w-8 rounded-lg" disabled={currentIdx === 0} onClick={() => goTo(currentIdx - 1)}>
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="icon" className="h-8 w-8 rounded-lg" disabled={currentIdx >= cards.length - 1} onClick={() => goTo(currentIdx + 1)}>
            <ChevronRight className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="sm" className="rounded-lg text-xs gap-1.5 h-8" onClick={handleGenerate} disabled={loading}>
            {loading ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            开启新任务
          </Button>
        </div>
      </div>
    </div>
  );
}
