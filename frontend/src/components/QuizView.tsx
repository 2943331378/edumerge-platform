"use client";

/**
 * 测试题组件 — 分层治理与生命周期管理
 *
 * 架构设计遵循数据素质要求:
 * - 分层组织: Deck(组) → Quiz(题目), 实现评测资源的层级化管理
 * - 生命周期: 创建(一键生成) → 使用(答题评分) → 归档/删除
 * - 可追溯性: 每题记录 sourceSegment, 可溯源至原始文档片段
 * - 数据治理: 通过 deck_id 外键实现题目与组的关联, 支持分组查询与清理
 */

import { useState, useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Sparkles, HelpCircle, RotateCw, ArrowLeft, ChevronRight, Check, X, Trash2, Sparkle, GitFork, Target, XCircle, Pencil } from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import type { DeckRecord, QuizItem, QuizAttemptRecord, WeaknessItem } from "@/lib/api";
import { listDecks, listQuizzesByDeck, generateQuizzes as generateApi, deleteDeck, getMindMap, saveQuizAttempt, listQuizAttempts, updateQuiz, deleteQuiz, listWeakness } from "@/lib/api";
import { ErrorBookView } from "@/components/ErrorBookView";

interface Props {
  docId: number | null;
  docUuid: string | null;
  sessionId: number | null;
  onMindMapGenerated?: () => void;
  onGenerated?: () => void;
  onContextChange?: (hint: string) => void;
  embedded?: boolean;
  /** 大纲选中的章节 IDs */
  selectedOutlineSections?: string[];
  /** 大纲触发生成信号，counter 变化时自动触发 */
  generateTrigger?: { type: string; counter: number };
}

function isNewDeck(createdAt: string): boolean {
  return Date.now() - new Date(createdAt).getTime() < 24 * 60 * 60 * 1000;
}

export function QuizView({ docId, docUuid, sessionId, onMindMapGenerated, onGenerated, onContextChange, embedded, selectedOutlineSections, generateTrigger }: Props) {
  const [view, setView] = useState<"decks" | "quiz">("decks");
  const [decks, setDecks] = useState<DeckRecord[]>([]);
  const [quizzes, setQuizzes] = useState<QuizItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [mindMapGenerating, setMindMapGenerating] = useState(false);
  const [mindMapProgress, setMindMapProgress] = useState(0);
  const [currentDeck, setCurrentDeck] = useState<DeckRecord | null>(null);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [navCollapsed, setNavCollapsed] = useState(true);
  const [score, setScore] = useState(0);
  const [attempts, setAttempts] = useState<QuizAttemptRecord[]>([]);
  const [answers, setAnswers] = useState<{ quizId: number; selectedAnswer: string; correct: boolean }[]>([]);

  // Wrong-answer review mode
  const [reviewMode, setReviewMode] = useState(false);
  const [reviewIdx, setReviewIdx] = useState(0);
  const [reviewScore, setReviewScore] = useState(0);
  const [reviewAnswers, setReviewAnswers] = useState<{ quizId: number; selectedAnswer: string; correct: boolean }[]>([]);
  const [reviewSubmitted, setReviewSubmitted] = useState(false);
  const [reviewSelected, setReviewSelected] = useState<string | null>(null);

  const [manageMode, setManageMode] = useState(false);
  const [editingQuizId, setEditingQuizId] = useState<number | null>(null);
  const [editQuizForm, setEditQuizForm] = useState({ question: "", options: "", answer: "", explanation: "" });
  const [saving, setSaving] = useState(false);
  const [showErrorBook, setShowErrorBook] = useState(false);
  const [weakness, setWeakness] = useState<WeaknessItem[]>([]);

  const wrongQuizzes = quizzes.filter((q) => answers.some((a) => a.quizId === q.id && !a.correct));

  const startReview = () => {
    setReviewMode(true);
    setReviewIdx(0);
    setReviewScore(0);
    setReviewAnswers([]);
    setReviewSelected(null);
    setReviewSubmitted(false);
  };

  const endReview = () => {
    setReviewMode(false);
    setReviewIdx(0);
  };

  const mindMapProgressTexts = [
    "正在解构知识要素...",
    "正在提取层级结构...",
    "正在识别核心主题...",
    "正在生成思维导图...",
    "正在组织知识图谱...",
  ];
  const [mindMapTextIdx, setMindMapTextIdx] = useState(0);
  const abortRef = useRef<AbortController | null>(null);

  const cancelGeneration = () => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
      setLoading(false);
      setMindMapGenerating(false);
      toast.info("已取消生成");
    }
  };

  // Report current quiz context to parent for chat context injection
  useEffect(() => {
    if (reviewMode && wrongQuizzes[reviewIdx]) {
      const q = wrongQuizzes[reviewIdx];
      onContextChange?.(`用户在回顾错题第${reviewIdx + 1}/${wrongQuizzes.length}题: "${q.question}"`);
    } else if (view === "quiz" && quizzes[currentIdx]) {
      const q = quizzes[currentIdx];
      const status = submitted ? (selected === q.answer ? "已答对" : "已答错") : (selected ? "已选择答案" : "未作答");
      onContextChange?.(`用户在测验第${currentIdx + 1}/${quizzes.length}题（${status}）: "${q.question}"`);
    } else if (view === "decks") {
      onContextChange?.("");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, reviewMode, currentIdx, reviewIdx, selected, submitted, reviewSelected, reviewSubmitted, quizzes.length, wrongQuizzes.length, onContextChange]);

  const reloadDecks = async () => {
    if (!docId) return;
    try {
      const [deckList, attemptList, weaknessData] = await Promise.all([
        listDecks(docId, "QUIZ"),
        listQuizAttempts(docId),
        listWeakness(docId).catch(() => []),
      ]);
      setDecks(deckList);
      setAttempts(attemptList);
      setWeakness(weaknessData);
    } catch { setDecks([]); setAttempts([]); }
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
      setQuizzes(await listQuizzesByDeck(deck.id));
      setCurrentIdx(0);
      setSelected(null);
      setSubmitted(false);
      setScore(0);
      setAnswers([]);
      setView("quiz");
    } catch { toast.error("加载测试题失败"); }
    setLoading(false);
  };

  /** 生成后自动加载列表并进入最新 Deck */
  const handleGenerate = async () => {
    if (!sessionId) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    try {
      await generateApi(undefined, undefined, sessionId, controller.signal);
      const fresh = await listDecks(docId!, "QUIZ");
      setDecks(fresh);
      onGenerated?.();
      toast.success("测试题生成成功");
      if (fresh.length > 0) {
        await enterDeck(fresh[0]);
      }
    } catch (err) {
      if ((err as Error).name !== "AbortError") {
        toast.error("测试题生成失败");
      }
    }
    abortRef.current = null;
    setLoading(false);
  };

  // 从大纲页面跳转过来时自动触发生成
  const triggerRef = useRef(0);
  useEffect(() => {
    if (generateTrigger && generateTrigger.counter > triggerRef.current && generateTrigger.type === "quiz") {
      triggerRef.current = generateTrigger.counter;
      requestAnimationFrame(() => handleGenerate());
    }
  }, [generateTrigger?.counter]);

  const handleDeleteDeck = async (e: React.MouseEvent, deckId: number) => {
    e.stopPropagation();
    try {
      await deleteDeck(deckId);
      setDecks((prev) => prev.filter((d) => d.id !== deckId));
      toast.success("测试题组已删除");
    } catch { toast.error("删除失败"); }
  };

  const handleSelect = (opt: string) => { if (!submitted) setSelected(opt); };

  const handleSubmit = () => {
    if (!selected || submitted) return;
    setSubmitted(true);
    const quiz = quizzes[currentIdx];
    const isCorrect = quiz && selected === quiz.answer;
    if (isCorrect) setScore((s) => s + 1);
    if (quiz) {
      setAnswers((prev) => [...prev, { quizId: quiz.id, selectedAnswer: selected!, correct: !!isCorrect }]);
    }
  };

  const saveCurrentAttempt = async () => {
    if (!docId || !currentDeck || answers.length === 0) return;
    const correctCount = answers.filter((a) => a.correct).length;
    try {
      await saveQuizAttempt({
        docId,
        deckId: currentDeck.id,
        totalQuestions: quizzes.length,
        correctCount,
        scorePercent: Math.round((correctCount / quizzes.length) * 100),
        answerDetails: JSON.stringify(answers),
      });
      await reloadDecks();
    } catch { /* ignore save errors */ }
  };

  const goNext = async () => {
    if (currentIdx < quizzes.length - 1) {
      setCurrentIdx((i) => i + 1);
      setSelected(null);
      setSubmitted(false);
    } else {
      await saveCurrentAttempt();
    }
  };

  // ═══════════════ 错题本视图 ═══════════════
  if (showErrorBook) {
    return <ErrorBookView docId={docId} onBack={() => setShowErrorBook(false)} onContextChange={onContextChange} />;
  }

  // ═══════════════ Deck 列表视图 ═══════════════
  if (view === "decks") {
    return (
      <div className="flex flex-col h-full">
        {!embedded && (
          <div className="flex items-center justify-between px-3 sm:px-6 py-2 sm:py-3 border-b bg-muted/20 shrink-0">
            <h2 className="text-xs sm:text-sm font-medium text-foreground/80 flex items-center gap-1.5 sm:gap-2">
              <HelpCircle className="h-3.5 w-3.5 sm:h-4 sm:w-4 text-muted-foreground" />
              测试题组
            </h2>
            <div className="flex items-center gap-1.5 sm:gap-2">
              <Button size="sm" variant="outline" className="rounded-xl gap-1.5 h-8 border-amber-200 dark:border-amber-800 text-amber-700 dark:text-amber-300 hover:bg-amber-50 dark:hover:bg-amber-950/30" onClick={() => setShowErrorBook(true)}>
                <Target className="h-3.5 w-3.5" />
                错题本
              </Button>
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
        )}

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

        <div className="flex-1 overflow-y-auto p-6">
          {decks.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full gap-6 text-muted-foreground">
              <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
                <HelpCircle className="h-7 w-7 text-muted-foreground/40" />
              </div>
              <div className="text-center space-y-1.5">
                <p className="text-sm font-medium">尚无测试题组</p>
                <p className="text-xs text-muted-foreground/50 max-w-xs">
                  AI 将从文档中提取核心概念生成测试题，帮助你验证学习效果
                </p>
              </div>
              {loading ? (
                <div className="flex items-center gap-2">
                  <RotateCw className="h-4 w-4 animate-spin text-muted-foreground" />
                  <span className="text-sm text-muted-foreground">AI 正在生成...</span>
                  <Button variant="outline" size="sm" className="rounded-xl h-8 border-destructive/30 text-destructive hover:bg-destructive/10" onClick={cancelGeneration}>
                    <XCircle className="h-3.5 w-3.5 mr-1" />取消
                  </Button>
                </div>
              ) : (
                <Button onClick={handleGenerate} disabled={!sessionId} className="rounded-xl gap-2 h-10">
                  <Sparkles className="h-4 w-4" />一键生成学习任务
                </Button>
              )}
            </div>
          ) : (
            <div className="space-y-6 max-w-5xl mx-auto">
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                {decks.map((deck) => (
                  <Card
                    key={deck.id}
                    className="group relative cursor-pointer rounded-2xl border-border/60 shadow-sm hover:shadow-md hover:border-primary/30 transition-all"
                    onClick={() => enterDeck(deck)}
                  >
                    <button
                      type="button"
                      onClick={(e) => handleDeleteDeck(e, deck.id)}
                      className="absolute top-3 right-3 opacity-0 group-hover:opacity-60 hover:!opacity-100 hover:text-destructive rounded-md p-1 transition-opacity z-10"
                      title="删除此测试题组"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>

                    <CardContent className="p-5 space-y-2">
                      <div className="flex items-center gap-2">
                        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-amber-100 dark:bg-amber-900/30">
                          <HelpCircle className="h-4 w-4 text-amber-600 dark:text-amber-400" />
                        </div>
                        <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">QUIZ</span>
                      </div>
                      <p className="text-sm font-medium text-foreground/85 leading-snug pr-6">{deck.title}</p>
                      <div className="flex items-center gap-2">
                        <p className="text-[11px] text-muted-foreground/50">{new Date(deck.createdAt).toLocaleString("zh-CN")}</p>
                        {isNewDeck(deck.createdAt) && (
                          <span className="inline-flex items-center gap-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400 bg-amber-100 dark:bg-amber-900/30 px-1.5 py-0.5 rounded-md">
                            <Sparkle className="h-2.5 w-2.5" />
                            New
                          </span>
                        )}
                        {/* 薄弱度指示器 */}
                        {(() => {
                          const w = weakness.find((w) => w.deckId === deck.id);
                          if (!w || w.totalQuestions === 0) return null;
                          const color = w.accuracyRate >= 80 ? "bg-emerald-500" : w.accuracyRate >= 60 ? "bg-amber-500" : "bg-red-500";
                          const textColor = w.accuracyRate >= 80 ? "text-emerald-600 dark:text-emerald-400" : w.accuracyRate >= 60 ? "text-amber-600 dark:text-amber-400" : "text-red-600 dark:text-red-400";
                          return (
                            <span className={`inline-flex items-center gap-1 text-[10px] font-medium ${textColor}`}>
                              <span className={`inline-block h-1.5 w-1.5 rounded-full ${color}`} />
                              {w.accuracyRate}%
                            </span>
                          );
                        })()}
                      </div>
                    </CardContent>
                  </Card>
                ))}
              </div>

              {/* 答题历史 */}
              {attempts.length > 0 && (
                <div className="rounded-xl border border-border/50 bg-muted/20 p-4">
                  <h3 className="text-xs font-semibold text-foreground/70 mb-3 flex items-center gap-1.5">
                    <Target className="h-3 w-3" />
                    答题历史
                  </h3>
                  <div className="space-y-1.5">
                    {attempts.slice(0, 10).map((a) => (
                      <div
                        key={a.id}
                        className="flex items-center justify-between rounded-lg px-3 py-2 text-xs bg-background/50"
                      >
                        <span className="text-muted-foreground">
                          {new Date(a.createdAt).toLocaleString("zh-CN")}
                        </span>
                        <span className="text-muted-foreground/60">
                          {a.correctCount}/{a.totalQuestions} 正确
                        </span>
                        <span
                          className={cn(
                            "font-semibold tabular-nums",
                            a.scorePercent >= 80
                              ? "text-emerald-600"
                              : a.scorePercent >= 60
                                ? "text-amber-600"
                                : "text-destructive",
                          )}
                        >
                          {a.scorePercent}%
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }

  // ═══════════════ 答题详情视图 ═══════════════
  const quiz = reviewMode ? wrongQuizzes[reviewIdx] : quizzes[currentIdx];
  const activeIdx = reviewMode ? reviewIdx : currentIdx;
  const totalItems = reviewMode ? wrongQuizzes.length : quizzes.length;
  const activeSelected = reviewMode ? reviewSelected : selected;
  const activeSubmitted = reviewMode ? reviewSubmitted : submitted;

  const handleReviewSelect = (opt: string) => {
    if (reviewSubmitted) return;
    setReviewSelected(opt);
  };

  const handleReviewSubmit = () => {
    if (!reviewSelected || reviewSubmitted) return;
    setReviewSubmitted(true);
    const isCorrect = quiz && reviewSelected === quiz.answer;
    if (isCorrect) setReviewScore((s) => s + 1);
    if (quiz) {
      setReviewAnswers((prev) => [...prev, { quizId: quiz.id, selectedAnswer: reviewSelected, correct: !!isCorrect }]);
    }
  };

  const reviewGoNext = () => {
    if (reviewIdx < wrongQuizzes.length - 1) {
      setReviewIdx((i) => i + 1);
      setReviewSelected(null);
      setReviewSubmitted(false);
    }
  };

  const handleSelectOpt = reviewMode ? handleReviewSelect : handleSelect;
  const handleSubmitOpt = reviewMode ? handleReviewSubmit : handleSubmit;
  const handleGoNext = () => {
    if (reviewMode) {
      reviewGoNext();
    } else {
      goNext();
    }
  };

  return (
    <div className="flex flex-col h-full">
      {/* 导航栏 — 可折叠 */}
      <div className="shrink-0 overflow-hidden transition-all duration-300 ease-in-out" style={{ height: navCollapsed ? 0 : "auto" }}>
        <div className="flex items-center gap-2 px-4 py-2 border-b bg-muted/20">
          <Button variant="ghost" size="sm" className="h-7 rounded-lg text-xs gap-1.5" onClick={() => { if (reviewMode) { endReview(); } else { setView("decks"); reloadDecks(); } }}>
            <ArrowLeft className="h-3 w-3" />
            {reviewMode ? "返回成绩" : "返回测试题组"}
          </Button>
          <span className="text-[11px] text-muted-foreground/40">/</span>
          <span className="text-[11px] text-muted-foreground truncate max-w-[200px]">
            {reviewMode ? "错题回顾" : (currentDeck?.title ?? "")}
          </span>
          {!reviewMode && !manageMode && (
            <Button variant="ghost" size="sm" className="h-7 rounded-lg text-xs gap-1.5 ml-auto" onClick={() => setManageMode(true)}>
              管理题目
            </Button>
          )}
          {manageMode && (
            <Button variant="ghost" size="sm" className="h-7 rounded-lg text-xs gap-1.5 ml-auto" onClick={() => setManageMode(false)}>
              返回答题
            </Button>
          )}
          {!reviewMode && !manageMode && submitted && (
            <span className="ml-auto text-[11px] text-muted-foreground">得分: {score}/{currentIdx + 1}</span>
          )}
          {reviewMode && (
            <span className="ml-auto text-[11px] text-muted-foreground">错题 {reviewIdx + 1}/{wrongQuizzes.length}</span>
          )}
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
              onClick={(e) => { e.stopPropagation(); if (reviewMode) { endReview(); } else { setView("decks"); reloadDecks(); } }}
            />
            <span className="text-[11px] text-muted-foreground/60 group-hover:text-foreground/80 truncate max-w-[180px] transition-colors">
              {reviewMode ? "错题回顾" : (currentDeck?.title ?? "")}
            </span>
            {!reviewMode && !manageMode && submitted && (
              <span className="text-[10px] text-emerald-600/60 dark:text-emerald-400/60 font-medium">{score}/{currentIdx + 1}</span>
            )}
            {reviewMode && (
              <span className="text-[10px] text-muted-foreground/40">{reviewIdx + 1}/{wrongQuizzes.length}</span>
            )}
            <ChevronRight className="h-3 w-3 -rotate-90 text-muted-foreground/30 group-hover:text-muted-foreground/60 transition-colors" />
          </button>
        </div>
      )}

      {manageMode ? (
        <div className="flex-1 overflow-y-auto p-6">
          <div className="max-w-3xl mx-auto space-y-3">
            <p className="text-xs text-muted-foreground/60 mb-4">共 {quizzes.length} 道题目，点击编辑修改题目内容</p>
            {quizzes.map((quiz, i) => (
              <Card key={quiz.id ?? i} className="rounded-xl border-border/50 bg-card/80 shadow-sm">
                {editingQuizId === quiz.id ? (
                  <CardContent className="p-4 space-y-2">
                    <textarea value={editQuizForm.question} onChange={e => setEditQuizForm(f => ({...f, question: e.target.value}))}
                      className="w-full rounded-lg border border-border/50 bg-background px-2 py-1 text-xs" placeholder="题目" rows={2} />
                    <textarea value={editQuizForm.options} onChange={e => setEditQuizForm(f => ({...f, options: e.target.value}))}
                      className="w-full rounded-lg border border-border/50 bg-background px-2 py-1 text-xs" placeholder="每行一个选项" rows={4} />
                    <textarea value={editQuizForm.answer} onChange={e => setEditQuizForm(f => ({...f, answer: e.target.value}))}
                      className="w-full rounded-lg border border-border/50 bg-background px-2 py-1 text-xs" placeholder="正确答案（与选项文本完全一致）" rows={2} />
                    <input value={editQuizForm.explanation} onChange={e => setEditQuizForm(f => ({...f, explanation: e.target.value}))}
                      className="w-full rounded-lg border border-border/50 bg-background px-2 py-1 text-xs" placeholder="解析（可选）" />
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="sm" className="h-6 text-xs" onClick={() => setEditingQuizId(null)} disabled={saving}>取消</Button>
                      <Button size="sm" className="h-6 text-xs" disabled={saving} onClick={async () => {
                        setSaving(true);
                        try {
                          await updateQuiz(quiz.id, {
                            question: editQuizForm.question,
                            options: editQuizForm.options.split("\n").filter(o => o.trim()),
                            answer: editQuizForm.answer,
                            explanation: editQuizForm.explanation,
                          });
                          setQuizzes(prev => prev.map(q => q.id === quiz.id ? { ...q, question: editQuizForm.question, options: editQuizForm.options.split("\n").filter(o => o.trim()), answer: editQuizForm.answer, explanation: editQuizForm.explanation } : q));
                          setEditingQuizId(null);
                          toast.success("已保存");
                        } catch { toast.error("保存失败"); }
                        setSaving(false);
                      }}>{saving ? "保存中..." : "保存"}</Button>
                    </div>
                  </CardContent>
                ) : (
                  <CardContent className="p-4">
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex-1 space-y-1">
                        <span className="text-[10px] font-medium text-primary/60 uppercase tracking-wider">Q{i + 1}</span>
                        <p className="text-xs text-foreground/85 leading-relaxed">{quiz.question}</p>
                        <div className="flex flex-wrap gap-1 mt-1">
                          {quiz.options.map((opt) => (
                            <span key={opt} className={`text-[10px] px-1.5 py-0.5 rounded ${opt === quiz.answer ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-300' : 'bg-muted text-muted-foreground/70'}`}>{opt}</span>
                          ))}
                        </div>
                        {quiz.explanation && (
                          <p className="text-[10px] text-muted-foreground/50 mt-1">解析: {quiz.explanation}</p>
                        )}
                      </div>
                      <div className="flex items-center gap-1 shrink-0">
                        <button type="button" onClick={() => {
                          setEditingQuizId(quiz.id);
                          let optionsStr = "";
                          try {
                            const raw = (quiz as unknown as Record<string, unknown>).options;
                            const parsed = typeof raw === "string" ? JSON.parse(raw as string) : raw;
                            optionsStr = Array.isArray(parsed) ? parsed.join("\n") : String(raw ?? "");
                          } catch { optionsStr = String((quiz as unknown as Record<string, unknown>).options ?? ""); }
                          setEditQuizForm({ question: quiz.question, options: optionsStr, answer: quiz.answer, explanation: quiz.explanation ?? "" });
                        }}
                          className="p-1 rounded hover:bg-muted text-muted-foreground/50 hover:text-foreground" title="编辑">
                          <Pencil className="h-3 w-3" />
                        </button>
                        <button type="button" onClick={() => { if (confirm("确定删除这道题目？")) { deleteQuiz(quiz.id).then(() => { setQuizzes(prev => prev.filter(q => q.id !== quiz.id)); toast.success("已删除"); }).catch(() => toast.error("删除失败")); } }}
                          className="p-1 rounded hover:bg-destructive/10 text-muted-foreground/50 hover:text-destructive" title="删除">
                          <X className="h-3 w-3" />
                        </button>
                      </div>
                    </div>
                  </CardContent>
                )}
              </Card>
            ))}
          </div>
        </div>
      ) : (
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-2xl mx-auto px-4 py-4 sm:px-6 sm:py-8 space-y-4 sm:space-y-6">
          <div className="flex items-center justify-between text-[11px] text-muted-foreground/50">
            <span>{activeIdx + 1} / {totalItems}</span>
            <div className="flex items-center gap-2">
              {quiz?.quizType === "FILL_BLANK" && (
                <span className="inline-flex items-center rounded-md bg-blue-50 dark:bg-blue-900/20 px-1.5 py-0.5 text-[10px] font-medium text-blue-700 dark:text-blue-300">填空题</span>
              )}
              {quiz?.quizType === "SINGLE" && (
                <span className="inline-flex items-center rounded-md bg-purple-50 dark:bg-purple-900/20 px-1.5 py-0.5 text-[10px] font-medium text-purple-700 dark:text-purple-300">选择题</span>
              )}
              {quiz?.difficulty && <span>{quiz.difficulty >= 4 ? "综合应用" : "基础概念"}</span>}
            </div>
            {reviewMode && <span className="text-amber-500 font-medium">错题回顾</span>}
          </div>

          <Card className="rounded-2xl border-border/60 shadow-sm">
            <CardContent className="p-4 sm:p-6">
              <p className="text-[13px] sm:text-sm leading-relaxed text-foreground/85">{quiz?.question}</p>
            </CardContent>
          </Card>

          {/* 填空题 — 文字输入 */}
          {quiz?.quizType === "FILL_BLANK" ? (
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={activeSelected ?? ""}
                  onChange={(e) => handleSelectOpt(e.target.value)}
                  disabled={activeSubmitted}
                  placeholder="输入你的答案..."
                  className={cn(
                    "flex-1 rounded-xl border px-4 py-2.5 text-sm outline-none transition-all",
                    !activeSubmitted && "border-border/60 bg-background focus:border-primary/50 focus:ring-2 focus:ring-primary/10",
                    activeSubmitted && activeSelected === quiz?.answer && "border-emerald-500 bg-emerald-50 dark:bg-emerald-950/20",
                    activeSubmitted && activeSelected !== quiz?.answer && "border-destructive bg-destructive/5",
                  )}
                  onKeyDown={(e) => { if (e.key === "Enter" && !activeSubmitted && activeSelected) handleSubmitOpt(); }}
                  aria-label="填空题答案输入"
                />
              </div>
              {activeSubmitted && (
                <div className={cn(
                  "flex items-center gap-2 rounded-xl px-4 py-3 text-sm",
                  activeSelected === quiz?.answer
                    ? "bg-emerald-50 dark:bg-emerald-950/20 text-emerald-700 dark:text-emerald-300"
                    : "bg-destructive/5 text-destructive"
                )}>
                  {activeSelected === quiz?.answer
                    ? <><Check className="h-4 w-4 shrink-0" /><span>正确！</span></>
                    : <><X className="h-4 w-4 shrink-0" /><span>正确答案: <strong>{quiz?.answer}</strong></span></>
                  }
                </div>
              )}
            </div>
          ) : (
            /* 选择题 — 选项按钮 */
            <div className="space-y-2">
              {quiz?.options.map((opt) => {
                const isSelected = activeSelected === opt;
                const showCorrect = activeSubmitted && opt === quiz.answer;
                const showWrong = activeSubmitted && isSelected && opt !== quiz.answer;
                return (
                  <button
                    key={opt}
                    onClick={() => handleSelectOpt(opt)}
                    disabled={activeSubmitted}
                    className={cn(
                      "w-full text-left px-3 py-2.5 sm:px-4 sm:py-3 rounded-xl border text-[13px] sm:text-sm transition-all",
                      !activeSubmitted && "hover:border-primary/40 hover:bg-muted/40 cursor-pointer",
                      activeSubmitted && "cursor-default",
                      isSelected && !activeSubmitted && "border-primary bg-primary/5",
                      showCorrect && "border-emerald-500 bg-emerald-50 dark:bg-emerald-950/20 text-emerald-700 dark:text-emerald-300",
                      showWrong && "border-destructive bg-destructive/5 text-destructive",
                      !isSelected && !showCorrect && activeSubmitted && "text-muted-foreground/50"
                    )}
                  >
                    <div className="flex items-center gap-2">
                      {showCorrect && <Check className="h-4 w-4 shrink-0 text-emerald-500" />}
                      {showWrong && <X className="h-4 w-4 shrink-0 text-destructive" />}
                      <span>{opt}</span>
                    </div>
                  </button>
                );
              })}
            </div>
          )}

          <div className="flex items-center gap-3 pt-2">
            {!activeSubmitted ? (
              <Button className="rounded-xl h-9" disabled={!activeSelected} onClick={handleSubmitOpt}>
                {reviewMode ? "确认答案" : "提交答案"}
              </Button>
            ) : (
              <>
                {!reviewMode && (
                  <Button variant="outline" className="rounded-xl h-9" onClick={handleGenerate} disabled={loading}>
                    {loading ? <RotateCw className="h-3.5 w-3.5 animate-spin mr-1.5" /> : <Sparkles className="h-3.5 w-3.5 mr-1.5" />}
                    开启新任务
                  </Button>
                )}
                {activeIdx < totalItems - 1 && (
                  <Button className="rounded-xl h-9" onClick={handleGoNext}>
                    {reviewMode ? "下一错题" : "下一题"}
                  </Button>
                )}
                {activeIdx === totalItems - 1 && activeSubmitted && (
                  <>
                    <p className="text-sm font-medium text-foreground/80">
                      {reviewMode
                        ? `错题完成! 重新正确 ${reviewScore}/${wrongQuizzes.length}`
                        : `完成! 正确率 ${Math.round((score / quizzes.length) * 100)}%`}
                    </p>
                    {reviewMode && (
                      <Button variant="ghost" size="sm" className="rounded-lg text-xs h-8" onClick={endReview}>
                        返回成绩
                      </Button>
                    )}
                  </>
                )}
              </>
            )}
            {!reviewMode && submitted && currentIdx === quizzes.length - 1 && wrongQuizzes.length > 0 && (
              <Button variant="outline" size="sm" className="rounded-lg text-xs h-8 border-amber-200 dark:border-amber-800 text-amber-700 dark:text-amber-300" onClick={startReview}>
                回顾 {wrongQuizzes.length} 道错题
              </Button>
            )}
          </div>

          {activeSubmitted && quiz?.explanation && (
            <Card className="rounded-2xl border-border/40 bg-muted/10">
              <CardContent className="p-4">
                <span className="text-[10px] font-medium text-muted-foreground/50 uppercase tracking-wider">解析</span>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{quiz.explanation}</p>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
      )}
    </div>
  );
}
