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

import { useState, useEffect, useRef, useCallback } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Sparkles, Layers, RotateCw, ChevronLeft, ChevronRight, ChevronDown, ArrowLeft, Trash2, Sparkle, GitFork, XCircle, Pencil, X, Download, Loader2, Shuffle, Star } from "lucide-react";
import type { DeckRecord, FlashcardItem } from "@/lib/api";
import { listDecks, listFlashcardsByDeck, generateFlashcards as generateApi, deleteDeck, getMindMap, updateFlashcard, deleteFlashcard, reviewFlashcard, listDueFlashcards, toggleFlashcardImportant } from "@/lib/api";
import { toast } from "sonner";
import { saveProgress, loadProgress, clearProgress, flashcardProgressKey } from "@/lib/progressStorage";

interface Props {
  docId: number | null;
  docUuid: string | null;
  sessionId: number | null;
  onMindMapGenerated?: () => void;
  onGenerated?: () => void;
  onContextChange?: (hint: string) => void;
  embedded?: boolean;
  /** 大纲选中的章节上下文（含 ID 和标题） */
  sectionContext?: string;
  /** 大纲选中章节的 chunk 范围 */
  startChunk?: number;
  endChunk?: number;
  /** 大纲触发生成信号，counter 变化时自动触发 */
  generateTrigger?: { type: string; counter: number };
  generating?: boolean;
  onGeneratingChange?: (v: boolean) => void;
}

/** 判断 deck 是否在 24h 内新建 */
function isNewDeck(createdAt: string): boolean {
  return Date.now() - new Date(createdAt).getTime() < 24 * 60 * 60 * 1000;
}

export function FlashcardView({ docId, docUuid, sessionId, onMindMapGenerated, onGenerated, onContextChange, embedded, sectionContext, startChunk, endChunk, generateTrigger, generating = false, onGeneratingChange }: Props) {
  const [view, setView] = useState<"decks" | "preview" | "cards">("decks");
  const [decks, setDecks] = useState<DeckRecord[]>([]);
  const [cards, setCards] = useState<FlashcardItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [mindMapGenerating, setMindMapGenerating] = useState(false);
  const [mindMapProgress, setMindMapProgress] = useState(0);
  const [currentDeck, setCurrentDeck] = useState<DeckRecord | null>(null);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [flipped, setFlipped] = useState(false);
  const [navCollapsed, setNavCollapsed] = useState(true);

  const mindMapProgressTexts = [
    "正在解构知识要素...",
    "正在提取层级结构...",
    "正在识别核心主题...",
    "正在生成思维导图...",
    "正在组织知识图谱...",
  ];
  const [mindMapTextIdx, setMindMapTextIdx] = useState(0);
  const [editingCardId, setEditingCardId] = useState<number | null>(null);
  const [editForm, setEditForm] = useState({ question: "", answer: "", explanation: "" });
  const [saving, setSaving] = useState(false);
  const [selfAssessed, setSelfAssessed] = useState(false);
  const [shuffled, setShuffled] = useState(false);
  const [dueCount, setDueCount] = useState(0);
  const [showImportantOnly, setShowImportantOnly] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const autoNextTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  /** Fisher-Yates 洗牌算法 */
  const shuffleArray = useCallback((arr: FlashcardItem[]) => {
    const copy = [...arr];
    for (let i = copy.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
  }, []);

  /** 洗牌后的卡片数组（未洗牌时返回原始数组） */
  const shuffledRef = useRef<FlashcardItem[]>([]);
  const displayCards = shuffled ? shuffledRef.current : cards;

  const handleExportCSV = async () => {
    let exportCards = cards;
    if (exportCards.length === 0 && decks.length > 0) {
      // 未进入具体卡片组时，加载第一个有卡片的组
      for (const deck of decks) {
        try {
          const d = await listFlashcardsByDeck(deck.id);
          if (d.length > 0) { exportCards = d; break; }
        } catch { /* skip */ }
      }
    }
    if (exportCards.length === 0) { toast.info("暂无卡片可导出"); return; }
    const escapeCSV = (s: string) => `"${s.replace(/"/g, '""')}"`;
    const header = "问题,答案,解析";
    const rows = exportCards.map((c) => [escapeCSV(c.question), escapeCSV(c.answer), escapeCSV(c.explanation ?? "")].join(","));
    const csv = "﻿" + [header, ...rows].join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `闪卡_${currentDeck?.title ?? "导出"}_${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    toast.success("已导出 CSV");
  };

  // 组件卸载时清理定时器
  useEffect(() => {
    return () => {
      if (autoNextTimerRef.current) clearTimeout(autoNextTimerRef.current);
    };
  }, []);

  const cancelGeneration = () => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
      setLoading(false);
      onGeneratingChange?.(false);
      setMindMapGenerating(false);
      toast.info("已取消生成");
    }
  };

  const toggleShuffle = () => {
    if (!shuffled) {
      shuffledRef.current = shuffleArray(cards);
      setShuffled(true);
    } else {
      setShuffled(false);
    }
    setCurrentIdx(0);
    setFlipped(false);
    setSelfAssessed(false);
  };

  // SM-2 自评处理
  const handleSelfAssess = async (quality: number) => {
    if (!card || selfAssessed) return;
    setSelfAssessed(true);
    try {
      await reviewFlashcard(card.id, quality);
      const labels = ["", "已标记「忘了」", "已标记「模糊」", "已标记「记住」", "已标记「秒答」"];
      toast.success(labels[quality]);
      // 自动跳到下一张
      if (currentIdx < displayCards.length - 1) {
        autoNextTimerRef.current = setTimeout(() => goTo(currentIdx + 1), 300);
      }
    } catch {
      toast.error("保存复习记录失败");
      setSelfAssessed(false);
    }
  };

  // 切换卡片重要标记
  const handleToggleImportant = async (cardId: number) => {
    try {
      const updated = await toggleFlashcardImportant(cardId);
      setCards((prev) => prev.map((c) => c.id === cardId ? { ...c, isImportant: updated.isImportant } : c));
      toast.success(updated.isImportant ? "已标记为重要" : "已取消重要标记");
    } catch {
      toast.error("操作失败");
    }
  };

  // 加载到期复习数量
  useEffect(() => {
    if (!docId || view !== "decks") return;
    listDueFlashcards(docId).then((due) => setDueCount(due.length)).catch(() => {});
  }, [docId, view]);

  // 键盘快捷键: Space 翻转, ←→ 切换, 1-4 自评 (仅在 cards 视图)
  // 必须在条件 return 之前调用，遵守 React Hooks 规则
  const handleCardKeyboard = useCallback((e: KeyboardEvent) => {
    if (view !== "cards") return;
    const target = e.target as HTMLElement;
    if (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable) return;

    switch (e.key) {
      case " ":
      case "Enter":
        e.preventDefault();
        setFlipped((v) => !v);
        break;
      case "ArrowLeft":
        e.preventDefault();
        goTo(currentIdx - 1);
        break;
      case "ArrowRight":
        e.preventDefault();
        goTo(currentIdx + 1);
        break;
      case "1":
      case "2":
      case "3":
      case "4":
        if (flipped) {
          e.preventDefault();
          handleSelfAssess(parseInt(e.key, 10));
        }
        break;
    }
  }, [view, currentIdx, flipped, selfAssessed]);

  useEffect(() => {
    window.addEventListener("keydown", handleCardKeyboard);
    return () => window.removeEventListener("keydown", handleCardKeyboard);
  }, [handleCardKeyboard]);

  // Report current card context to parent for chat context injection
  useEffect(() => {
    if (view === "cards" && displayCards[currentIdx]) {
      const card = displayCards[currentIdx];
      const side = flipped ? "答案" : "问题";
      onContextChange?.(`用户在复习闪卡第${currentIdx + 1}/${displayCards.length}张（${side}面）: "${card.question}"`);
    } else if (view === "decks") {
      onContextChange?.("");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, currentIdx, flipped, displayCards.length, onContextChange]);

  // Persist flashcard position to localStorage on navigation
  useEffect(() => {
    if (view === "cards" && currentDeck) {
      saveProgress(flashcardProgressKey(currentDeck.id), {
        idx: currentIdx,
        flipped,
        count: displayCards.length,
      });
    }
  }, [view, currentDeck, currentIdx, flipped, displayCards.length]);

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
      const loaded = await listFlashcardsByDeck(deck.id, showImportantOnly || undefined);
      setCards(loaded);
      setShuffled(false);

      // Restore saved position from localStorage
      const key = flashcardProgressKey(deck.id);
      const saved = loadProgress<{ idx: number; flipped: boolean; count: number }>(key);
      if (saved && saved.count === loaded.length && saved.idx >= 0 && saved.idx < loaded.length) {
        setCurrentIdx(saved.idx);
        setFlipped(saved.flipped);
      } else {
        // Invalid or missing — clear stale entry and start fresh
        if (saved) clearProgress(key);
        setCurrentIdx(0);
        setFlipped(false);
      }

      setView("cards");
    } catch { toast.error("加载卡片失败"); }
    setLoading(false);
  };

  /** 生成后进入预览模式 */
  const handleGenerate = async () => {
    if (!sessionId || generating) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    onGeneratingChange?.(true);
    try {
      await generateApi(undefined, undefined, sessionId, controller.signal, sectionContext || undefined, startChunk, endChunk);
      const fresh = await listDecks(docId!, "FLASHCARD");
      setDecks(fresh);
      onGenerated?.();
      toast.success("学习卡片生成成功");
      if (fresh.length > 0) {
        setCurrentDeck(fresh[0]);
        setCards(await listFlashcardsByDeck(fresh[0].id));
        setView("preview");
      }
    } catch (err) {
      if ((err as Error).name !== "AbortError") {
        toast.error("卡片生成失败");
      }
    }
    abortRef.current = null;
    setLoading(false);
    onGeneratingChange?.(false);
  };

  // 从大纲页面跳转过来时自动触发生成
  const prevCounterRef = useRef<number | undefined>(undefined);
  useEffect(() => {
    const counter = generateTrigger?.counter;
    if (counter !== undefined && counter !== prevCounterRef.current && generateTrigger?.type === "flashcard") {
      prevCounterRef.current = counter;
      handleGenerate();
    } else {
      prevCounterRef.current = counter;
    }
  }, [generateTrigger?.counter]);

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
    setSelfAssessed(false);
    setCurrentIdx(Math.max(0, Math.min(i, displayCards.length - 1)));
  };

  // ═══════════════ Deck 列表视图 ═══════════════
  if (view === "decks") {
    return (
      <div className="flex flex-col h-full">
        {/* 顶部工具栏 */}
        <div className="flex items-center justify-between px-3 sm:px-6 py-2 sm:py-3 border-b bg-muted/20 shrink-0">
          <h2 className="text-xs sm:text-sm font-medium text-foreground/80 flex items-center gap-1.5 sm:gap-2">
            <Layers className="h-3.5 w-3.5 sm:h-4 sm:w-4 text-muted-foreground" />
            学习卡片组
          </h2>
          <div className="flex items-center gap-1.5 sm:gap-2">
            {dueCount > 0 && (
              <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 border-amber-200 dark:border-amber-800 text-amber-700 dark:text-amber-300 hover:bg-amber-50 dark:hover:bg-amber-950/30" onClick={async () => {
                if (!docId) return;
                try {
                  const due = await listDueFlashcards(docId);
                  if (due.length === 0) { toast.info("暂无到期卡片"); return; }
                  setCards(due);
                  setCurrentIdx(0);
                  setFlipped(false);
                  setSelfAssessed(false);
                  setCurrentDeck({ id: -1, docId, title: "到期复习", type: "FLASHCARD", createdAt: new Date().toISOString() });
                  setView("cards");
                } catch { toast.error("加载失败"); }
              }}>
                <RotateCw className="h-3.5 w-3.5" />
                到期复习 ({dueCount})
              </Button>
            )}
            <Button
              size="sm"
              variant={showImportantOnly ? "default" : "outline"}
              className={`rounded-xl gap-1.5 h-8 ${showImportantOnly ? "bg-amber-500 hover:bg-amber-600 text-white" : "border-amber-200 dark:border-amber-800 text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-950/30"}`}
              onClick={() => setShowImportantOnly((v) => !v)}
            >
              <Star className={`h-3.5 w-3.5 ${showImportantOnly ? "fill-current" : ""}`} />
              {showImportantOnly ? "显示全部" : "仅看重要"}
            </Button>
            {(cards.length > 0 || decks.length > 0) && (
              <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8" onClick={handleExportCSV}>
                <Download className="h-3.5 w-3.5" />
                导出
              </Button>
            )}
            <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 border-purple-200 dark:border-purple-800 text-purple-700 dark:text-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/30" onClick={handleGenerateMindMap} disabled={mindMapGenerating || !docId}>
              {mindMapGenerating ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <GitFork className="h-3.5 w-3.5" />}
              {mindMapGenerating ? "生成大纲中..." : "全书思维大纲"}
            </Button>
            {loading ? (
              <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 border-destructive/30 text-destructive hover:bg-destructive/10" onClick={cancelGeneration}>
                <XCircle className="h-3.5 w-3.5" />
                取消生成
              </Button>
            ) : (
              <Button size="sm" className="rounded-xl gap-1.5 h-8" onClick={handleGenerate} disabled={!sessionId}>
                <Sparkles className="h-3.5 w-3.5" />
                一键生成
              </Button>
            )}
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
              {loading ? (
                <div className="flex flex-col items-center gap-4">
                  <Loader2 className="h-8 w-8 animate-spin text-primary" />
                  <div className="text-center space-y-1">
                    <p className="text-sm font-medium text-foreground/80">AI 正在生成学习卡片...</p>
                    <p className="text-xs text-muted-foreground/50">正在解析文档，提取核心知识点</p>
                  </div>
                  <Button variant="outline" size="sm" className="rounded-xl h-8 border-destructive/30 text-destructive hover:bg-destructive/10" onClick={cancelGeneration}>
                    <XCircle className="h-3.5 w-3.5 mr-1" />取消生成
                  </Button>
                </div>
              ) : (
                <Button onClick={handleGenerate} disabled={!sessionId} className="rounded-xl gap-2 h-10">
                  <Sparkles className="h-4 w-4" />一键生成学习任务
                </Button>
              )}
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

  // ═══════════════ 预览视图 (生成后检视) ═══════════════
  if (view === "preview") {
    return (
      <div className="flex flex-col h-full">
        <div className="flex items-center justify-between px-3 sm:px-6 py-2 sm:py-3 border-b bg-muted/20 shrink-0">
          <div>
            <h2 className="text-xs sm:text-sm font-medium text-foreground/80 flex items-center gap-1.5 sm:gap-2">
              <Sparkles className="h-3.5 w-3.5 sm:h-4 sm:w-4 text-primary" />
              生成预览
            </h2>
            <p className="text-[10px] sm:text-[11px] text-muted-foreground/60">{currentDeck?.title} · {cards.length} 张卡片</p>
          </div>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 text-xs border-destructive/20 text-destructive hover:bg-destructive/10" onClick={async () => {
              if (currentDeck) {
                try {
                  await deleteDeck(currentDeck.id);
                  setDecks((prev) => prev.filter((d) => d.id !== currentDeck.id));
                  toast.success("已放弃该组卡片");
                  setView("decks");
                } catch { toast.error("删除失败"); }
              }
            }}>
              <Trash2 className="h-3.5 w-3.5" />
              放弃重来
            </Button>
            <Button size="sm" className="rounded-xl gap-1.5 h-8 text-xs" onClick={() => {
              setCurrentIdx(0);
              setFlipped(false);
              setView("cards");
            }}>
              <Sparkle className="h-3.5 w-3.5" />
              开始学习
            </Button>
          </div>
        </div>
        <div className="flex-1 overflow-y-auto p-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-w-4xl mx-auto">
            {cards.map((card, i) => (
              <Card key={card.id ?? i} className="relative group rounded-xl border-border/50 bg-card/80 shadow-sm">
                {/* Hover actions */}
                <div className="absolute top-2 right-2 flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity z-10">
                  <button type="button" onClick={(e) => { e.stopPropagation(); handleToggleImportant(card.id); }}
                    className={`p-1 rounded transition-colors ${card.isImportant ? "text-amber-400 hover:text-amber-500" : "text-muted-foreground/50 hover:text-amber-400"}`} title={card.isImportant ? "取消重要标记" : "标记为重要"}>
                    <Star className={`h-3 w-3 ${card.isImportant ? "fill-amber-400" : ""}`} />
                  </button>
                  <button type="button" onClick={() => { setEditingCardId(card.id); setEditForm({ question: card.question, answer: card.answer, explanation: card.explanation ?? "" }); }}
                    className="p-1 rounded hover:bg-muted text-muted-foreground/50 hover:text-foreground" title="编辑">
                    <Pencil className="h-3 w-3" />
                  </button>
                  <button type="button" onClick={() => { if (confirm("确定删除这张卡片？")) { deleteFlashcard(card.id).then(() => { setCards(prev => prev.filter(c => c.id !== card.id)); toast.success("已删除"); }).catch(() => toast.error("删除失败")); } }}
                    className="p-1 rounded hover:bg-destructive/10 text-muted-foreground/50 hover:text-destructive" title="删除">
                    <X className="h-3 w-3" />
                  </button>
                </div>

                {editingCardId === card.id ? (
                  <CardContent className="p-4 space-y-2">
                    <textarea value={editForm.question} onChange={e => setEditForm(f => ({...f, question: e.target.value}))}
                      className="w-full rounded-lg border border-border/50 bg-background px-2 py-1 text-xs" placeholder="问题" rows={2} />
                    <textarea value={editForm.answer} onChange={e => setEditForm(f => ({...f, answer: e.target.value}))}
                      className="w-full rounded-lg border border-border/50 bg-background px-2 py-1 text-xs" placeholder="答案" rows={2} />
                    <input value={editForm.explanation} onChange={e => setEditForm(f => ({...f, explanation: e.target.value}))}
                      className="w-full rounded-lg border border-border/50 bg-background px-2 py-1 text-xs" placeholder="解析（可选）" />
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="sm" className="h-6 text-xs" onClick={() => setEditingCardId(null)} disabled={saving}>取消</Button>
                      <Button size="sm" className="h-6 text-xs" disabled={saving} onClick={async () => {
                        setSaving(true);
                        try {
                          await updateFlashcard(card.id, { question: editForm.question, answer: editForm.answer, explanation: editForm.explanation });
                          setCards(prev => prev.map(c => c.id === card.id ? { ...c, question: editForm.question, answer: editForm.answer, explanation: editForm.explanation } : c));
                          setEditingCardId(null);
                          toast.success("已保存");
                        } catch { toast.error("保存失败"); }
                        setSaving(false);
                      }}>{saving ? "保存中..." : "保存"}</Button>
                    </div>
                  </CardContent>
                ) : (
                  <CardContent className="p-0">
                    {/* 问题区 */}
                    <div className="px-4 pt-4 pb-3">
                      <div className="flex items-center gap-1.5 mb-1.5">
                        <span className="flex h-5 w-5 items-center justify-center rounded bg-primary/10 text-[10px] font-bold text-primary">{i + 1}</span>
                        <span className="text-[9px] font-semibold text-primary/50 uppercase tracking-wider">问题</span>
                        {card.isImportant && (
                          <Star className="h-3 w-3 fill-amber-400 text-amber-400" />
                        )}
                      </div>
                      <p className="text-[13px] text-foreground/85 leading-relaxed font-medium">{card.question}</p>
                    </div>

                    {/* 分割线 */}
                    <div className="mx-4 h-px bg-gradient-to-r from-transparent via-border/50 to-transparent" />

                    {/* 答案区 — 带微妙背景 */}
                    <div className="px-4 pt-3 pb-4 bg-emerald-50/30 dark:bg-emerald-950/5 rounded-b-xl">
                      <div className="flex items-center gap-1.5 mb-1.5">
                        <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                        <span className="text-[9px] font-semibold text-emerald-600/60 dark:text-emerald-400/60 uppercase tracking-wider">答案</span>
                      </div>
                      <p className="text-[13px] text-foreground/70 leading-relaxed">{card.answer}</p>
                      {card.explanation && (
                        <p className="mt-2 text-[11px] text-muted-foreground/50 leading-relaxed italic">
                          💡 {card.explanation}
                        </p>
                      )}
                    </div>
                  </CardContent>
                )}
              </Card>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // ═══════════════ 卡片详情视图 (翻转学习) ═══════════════
  const card = displayCards[currentIdx];
  return (
    <div className="flex flex-col h-full">
      {/* 面包屑导航 — 可折叠 */}
      <div className="shrink-0 overflow-hidden transition-all duration-300 ease-in-out" style={{ height: navCollapsed ? 0 : "auto" }}>
        <div className="flex items-center gap-2 px-4 py-2 border-b bg-muted/20">
          <Button variant="ghost" size="sm" className="h-7 rounded-lg text-xs gap-1.5" onClick={() => { setView("decks"); reloadDecks(); }}>
            <ArrowLeft className="h-3 w-3" />
            返回卡片组
          </Button>
          <span className="text-[11px] text-muted-foreground/40">/</span>
          <span className="text-[11px] text-muted-foreground truncate max-w-[200px]">{currentDeck?.title ?? ""}</span>
          <button
            type="button"
            onClick={toggleShuffle}
            className={`flex h-6 w-6 items-center justify-center rounded-md transition-all ${shuffled ? "text-primary bg-primary/10" : "text-muted-foreground/40 hover:text-foreground hover:bg-muted"}`}
            title={shuffled ? "关闭随机顺序" : "随机顺序"}
          >
            <Shuffle className="h-3.5 w-3.5" />
          </button>
          <div className="flex-1" />
          <button
            type="button"
            onClick={() => setNavCollapsed(true)}
            className="flex h-6 w-6 items-center justify-center rounded-md text-muted-foreground/40 hover:text-foreground hover:bg-muted transition-all"
            title="收起导航栏"
          >
            <ChevronRight className="h-3.5 w-3.5 rotate-90" />
          </button>
        </div>
      </div>

      {/* 折叠态 — 悬浮小药丸 */}
      {navCollapsed && (
        <div className="shrink-0 px-3 py-1.5">
          <button
            type="button"
            onClick={() => setNavCollapsed(false)}
            className="group inline-flex items-center gap-1.5 rounded-full bg-muted/40 hover:bg-muted/70 backdrop-blur-sm border border-border/30 px-3 py-1 transition-all duration-200"
            title="展开导航栏"
          >
            <ArrowLeft
              className="h-3 w-3 text-muted-foreground/50 group-hover:text-foreground transition-colors cursor-pointer"
              onClick={(e) => { e.stopPropagation(); setView("decks"); reloadDecks(); }}
            />
            <span className="text-[11px] text-muted-foreground/60 group-hover:text-foreground/80 truncate max-w-[180px] transition-colors">
              {currentDeck?.title ?? ""}
            </span>
            <Shuffle
              className={`h-3 w-3 cursor-pointer transition-colors ${shuffled ? "text-primary" : "text-muted-foreground/30 group-hover:text-muted-foreground/60"}`}
              onClick={(e) => { e.stopPropagation(); toggleShuffle(); }}
            />
            <ChevronRight className="h-3 w-3 -rotate-90 text-muted-foreground/30 group-hover:text-muted-foreground/60 transition-colors" />
          </button>
        </div>
      )}

      {/* 翻转卡片 — 大尺寸展示 */}
      <div className="flex-1 flex flex-col items-center justify-center gap-3 sm:gap-6 px-3 sm:px-8 overflow-y-auto py-3 sm:py-4">
        <div className="flex items-center gap-2">
          <p className="text-[10px] sm:text-xs text-muted-foreground/40 tracking-wider">{currentIdx + 1} / {displayCards.length}</p>
          {card && (
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); handleToggleImportant(card.id); }}
              className="flex h-7 w-7 sm:h-8 sm:w-8 items-center justify-center rounded-full transition-all hover:bg-amber-100 dark:hover:bg-amber-900/30"
              title={card.isImportant ? "取消重要标记" : "标记为重要"}
            >
              <Star className={`h-4 w-4 sm:h-5 sm:w-5 transition-colors ${card.isImportant ? "fill-amber-400 text-amber-400" : "text-muted-foreground/30 hover:text-amber-400"}`} />
            </button>
          )}
        </div>

        {/* 容器: 宽大 + 渐变边框光晕 + 动画 */}
        <div
          className="w-full max-w-[800px] cursor-pointer [perspective:1200px] animate-card-enter"
          key={currentIdx}
          onClick={() => setFlipped((v) => !v)}
        >
          <div
            className="relative w-full min-h-[260px] sm:min-h-[450px] transition-transform duration-300 [transform-style:preserve-3d]"
            style={{ transform: flipped ? "rotateY(180deg)" : "rotateY(0deg)" }}
          >
            {/* Front — 问题 */}
            <Card className="absolute inset-0 rounded-3xl shadow-2xl [backface-visibility:hidden]
              border border-transparent bg-clip-padding
              before:absolute before:inset-0 before:rounded-3xl before:p-[1px] before:bg-gradient-to-br before:from-primary/40 before:via-primary/10 before:to-transparent before:-z-10 before:[mask:linear-gradient(#fff_0_0)_content-box,linear-gradient(#fff_0_0)] before:[mask-composite:exclude]
              bg-card">
              <CardContent className="flex flex-col items-center justify-center p-4 sm:p-8 h-full min-h-[260px] sm:min-h-[450px]">
                <span className="text-[10px] sm:text-[11px] font-medium text-muted-foreground/40 uppercase tracking-[0.2em] mb-3 sm:mb-6">问题</span>
                <p className="text-base sm:text-3xl text-center leading-relaxed text-foreground/90 font-medium max-w-2xl">
                  {card?.question}
                </p>
                <p className="mt-4 sm:mt-8 text-[10px] sm:text-xs text-muted-foreground/30">点击翻转查看答案</p>
              </CardContent>
            </Card>

            {/* Back — 答案 */}
            <Card className="absolute inset-0 rounded-3xl shadow-2xl [backface-visibility:hidden] [transform:rotateY(180deg)]
              border border-transparent bg-clip-padding
              before:absolute before:inset-0 before:rounded-3xl before:p-[1px] before:bg-gradient-to-br before:from-emerald-400/40 before:via-emerald-400/10 before:to-transparent before:-z-10 before:[mask:linear-gradient(#fff_0_0)_content-box,linear-gradient(#fff_0_0)] before:[mask-composite:exclude]
              bg-card overflow-hidden">
              {/* 顶部装饰区 — 渐变背景 */}
              <div className="absolute inset-x-0 top-0 h-28 bg-gradient-to-b from-emerald-50/60 to-transparent dark:from-emerald-950/20 dark:to-transparent pointer-events-none" />

              <CardContent className="relative flex flex-col h-full min-h-[260px] sm:min-h-[450px] overflow-y-auto">
                {/* 答案区域 — 左对齐，更自然的阅读体验 */}
                <div className="flex-1 flex flex-col justify-center px-4 sm:px-14 py-4 sm:py-14">
                  {/* 标签 */}
                  <div className="flex items-center gap-2 mb-2 sm:mb-5">
                    <span className="inline-flex items-center gap-1.5 text-[9px] sm:text-[10px] font-bold text-emerald-600 dark:text-emerald-400 uppercase tracking-[0.2em] bg-emerald-100/60 dark:bg-emerald-900/20 rounded-md px-2 py-0.5">
                      <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                      答案
                    </span>
                  </div>

                  {/* 核心答案 — 大字醒目 */}
                  <p className="text-base sm:text-2xl leading-relaxed text-foreground/90 font-semibold tracking-tight max-w-xl">
                    {card?.answer}
                  </p>

                  {/* 解析区 — 独立卡片式设计 */}
                  {card?.explanation && (
                    <div className="mt-3 sm:mt-8 rounded-xl bg-muted/30 dark:bg-muted/10 border border-border/20 p-3 sm:p-5">
                      <div className="flex items-center gap-2 mb-2.5">
                        <div className="flex h-5 w-5 items-center justify-center rounded bg-amber-100 dark:bg-amber-900/30">
                          <span className="text-[10px]">💡</span>
                        </div>
                        <span className="text-[10px] sm:text-[11px] font-semibold text-amber-700 dark:text-amber-400 uppercase tracking-wider">解析</span>
                      </div>
                      <p className="text-xs sm:text-sm leading-relaxed text-foreground/65">{card.explanation}</p>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>
          </div>
        </div>

        {/* 导航 */}
        <div className="flex items-center gap-3">
          <Button variant="outline" size="icon" className="h-8 w-8 rounded-lg" disabled={currentIdx === 0} onClick={() => goTo(currentIdx - 1)}>
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="icon" className="h-8 w-8 rounded-lg" disabled={currentIdx >= displayCards.length - 1} onClick={() => goTo(currentIdx + 1)}>
            <ChevronRight className="h-4 w-4" />
          </Button>
          <Button
            variant={shuffled ? "default" : "outline"}
            size="icon"
            className={`h-8 w-8 rounded-lg ${shuffled ? "bg-primary/90 hover:bg-primary" : ""}`}
            onClick={toggleShuffle}
            title={shuffled ? "关闭随机顺序" : "随机顺序（洗牌）"}
          >
            <Shuffle className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="sm" className="rounded-lg text-xs gap-1.5 h-8" onClick={handleGenerate} disabled={loading}>
            {loading ? <RotateCw className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            开启新任务
          </Button>
        </div>

        {/* SM-2 自评按钮 — 翻转后显示 */}
        {flipped && (
          <div className="flex items-center gap-1.5 sm:gap-2 pb-2 flex-wrap justify-center">
            {selfAssessed ? (
              <span className="text-xs text-muted-foreground/50">已评估，继续下一张</span>
            ) : (
              <>
                <span className="text-[10px] sm:text-[11px] text-muted-foreground/40 mr-1">自评:</span>
                <Button size="sm" variant="outline" className="rounded-lg h-7 sm:h-8 text-[11px] sm:text-xs border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/30" onClick={() => handleSelfAssess(1)}>
                  忘了
                </Button>
                <Button size="sm" variant="outline" className="rounded-lg h-7 sm:h-8 text-[11px] sm:text-xs border-orange-200 dark:border-orange-800 text-orange-600 dark:text-orange-400 hover:bg-orange-50 dark:hover:bg-orange-950/30" onClick={() => handleSelfAssess(2)}>
                  模糊
                </Button>
                <Button size="sm" variant="outline" className="rounded-lg h-7 sm:h-8 text-[11px] sm:text-xs border-green-200 dark:border-green-800 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-950/30" onClick={() => handleSelfAssess(3)}>
                  记住
                </Button>
                <Button size="sm" variant="outline" className="rounded-lg h-7 sm:h-8 text-[11px] sm:text-xs border-emerald-200 dark:border-emerald-800 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-50 dark:hover:bg-emerald-950/30" onClick={() => handleSelfAssess(4)}>
                  秒答
                </Button>
                <span className="hidden sm:inline text-[10px] text-muted-foreground/30 ml-1">快捷键 1-4</span>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
