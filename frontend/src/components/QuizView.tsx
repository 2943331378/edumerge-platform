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

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Sparkles, HelpCircle, RotateCw, ArrowLeft, Check, X, Trash2, Sparkle, GitFork } from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import type { DeckRecord, QuizItem } from "@/lib/api";
import { listDecks, listQuizzesByDeck, generateQuizzes as generateApi, deleteDeck, getMindMap } from "@/lib/api";

interface Props {
  docId: number | null;
  docUuid: string | null;
  sessionId: number | null;
  onMindMapGenerated?: () => void;
}

function isNewDeck(createdAt: string): boolean {
  return Date.now() - new Date(createdAt).getTime() < 24 * 60 * 60 * 1000;
}

export function QuizView({ docId, docUuid, sessionId, onMindMapGenerated }: Props) {
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
  const [score, setScore] = useState(0);

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
    try { setDecks(await listDecks(docId, "QUIZ")); } catch { setDecks([]); }
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
      setView("quiz");
    } catch { toast.error("加载测试题失败"); }
    setLoading(false);
  };

  /** 生成后自动加载列表并进入最新 Deck */
  const handleGenerate = async () => {
    if (!sessionId) return;
    setLoading(true);
    try {
      await generateApi(undefined, undefined, sessionId);
      const fresh = await listDecks(docId!, "QUIZ");
      setDecks(fresh);
      toast.success("测试题生成成功");
      if (fresh.length > 0) {
        await enterDeck(fresh[0]);
      }
    } catch { toast.error("测试题生成失败"); }
    setLoading(false);
  };

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
    if (quiz && selected === quiz.answer) setScore((s) => s + 1);
  };

  const goNext = () => {
    if (currentIdx < quizzes.length - 1) {
      setCurrentIdx((i) => i + 1);
      setSelected(null);
      setSubmitted(false);
    }
  };

  // ═══════════════ Deck 列表视图 ═══════════════
  if (view === "decks") {
    return (
      <div className="flex flex-col h-full">
        <div className="flex items-center justify-between px-6 py-3 border-b bg-muted/20 shrink-0">
          <h2 className="text-sm font-medium text-foreground/80 flex items-center gap-2">
            <HelpCircle className="h-4 w-4 text-muted-foreground" />
            测试题组
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
                  <button
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

  // ═══════════════ 答题详情视图 ═══════════════
  const quiz = quizzes[currentIdx];

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center gap-2 px-4 py-2 border-b bg-muted/20 shrink-0">
        <Button variant="ghost" size="sm" className="h-7 rounded-lg text-xs gap-1.5" onClick={() => { setView("decks"); reloadDecks(); }}>
          <ArrowLeft className="h-3 w-3" />
          返回测试题组
        </Button>
        <span className="text-[11px] text-muted-foreground/40">/</span>
        <span className="text-[11px] text-muted-foreground truncate max-w-[200px]">{currentDeck?.title ?? ""}</span>
        {submitted && (
          <span className="ml-auto text-[11px] text-muted-foreground">得分: {score}/{currentIdx + 1}</span>
        )}
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="max-w-2xl mx-auto px-6 py-8 space-y-6">
          <div className="flex items-center justify-between text-[11px] text-muted-foreground/50">
            <span>{currentIdx + 1} / {quizzes.length}</span>
            {quiz?.difficulty && <span>{quiz.difficulty >= 4 ? "综合应用" : "基础概念"}</span>}
          </div>

          <Card className="rounded-2xl border-border/60 shadow-sm">
            <CardContent className="p-6">
              <p className="text-sm leading-relaxed text-foreground/85">{quiz?.question}</p>
            </CardContent>
          </Card>

          <div className="space-y-2">
            {quiz?.options.map((opt) => {
              const isSelected = selected === opt;
              const showCorrect = submitted && opt === quiz.answer;
              const showWrong = submitted && isSelected && opt !== quiz.answer;
              return (
                <button
                  key={opt}
                  onClick={() => handleSelect(opt)}
                  disabled={submitted}
                  className={cn(
                    "w-full text-left px-4 py-3 rounded-xl border text-sm transition-all",
                    !submitted && "hover:border-primary/40 hover:bg-muted/40 cursor-pointer",
                    submitted && "cursor-default",
                    isSelected && !submitted && "border-primary bg-primary/5",
                    showCorrect && "border-emerald-500 bg-emerald-50 dark:bg-emerald-950/20 text-emerald-700 dark:text-emerald-300",
                    showWrong && "border-destructive bg-destructive/5 text-destructive",
                    !isSelected && !showCorrect && submitted && "text-muted-foreground/50"
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

          <div className="flex items-center gap-3 pt-2">
            {!submitted ? (
              <Button className="rounded-xl h-9" disabled={!selected} onClick={handleSubmit}>提交答案</Button>
            ) : (
              <>
                <Button variant="outline" className="rounded-xl h-9" onClick={handleGenerate} disabled={loading}>
                  {loading ? <RotateCw className="h-3.5 w-3.5 animate-spin mr-1.5" /> : <Sparkles className="h-3.5 w-3.5 mr-1.5" />}
                  开启新任务
                </Button>
                {currentIdx < quizzes.length - 1 && (
                  <Button className="rounded-xl h-9" onClick={goNext}>下一题</Button>
                )}
              </>
            )}
            {submitted && currentIdx === quizzes.length - 1 && (
              <p className="text-sm font-medium text-foreground/80">完成! 正确率 {Math.round((score / quizzes.length) * 100)}%</p>
            )}
          </div>

          {submitted && quiz?.explanation && (
            <Card className="rounded-2xl border-border/40 bg-muted/10">
              <CardContent className="p-4">
                <span className="text-[10px] font-medium text-muted-foreground/50 uppercase tracking-wider">解析</span>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{quiz.explanation}</p>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
