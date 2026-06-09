"use client";

/**
 * 全局错题本 — 聚合所有测验中的错误题目
 *
 * 功能:
 * - 按错误次数降序展示错题
 * - 支持逐题重做（重新作答模式）
 * - 答对自动标记掌握，答错保留并显示解析
 * - 标记已掌握后隐藏
 */

import { useState, useEffect, useCallback, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import type { ErrorBookItem } from "@/lib/api";
import { listErrorBook } from "@/lib/api";
import {
  BookX,
  RotateCw,
  Check,
  X,
  ArrowLeft,
  Loader2,
  AlertTriangle,
  Trophy,
  PlayCircle,
} from "lucide-react";

interface Props {
  docId: number | null;
  onBack: () => void;
  onContextChange?: (hint: string) => void;
}

const MASTERED_KEY = (docId: number) => `edumerge_mastered_${docId}`;

function loadMastered(docId: number): Set<number> {
  try {
    const raw = localStorage.getItem(MASTERED_KEY(docId));
    if (raw) return new Set(JSON.parse(raw));
  } catch { /* ignore */ }
  return new Set();
}

function saveMastered(docId: number, ids: Set<number>) {
  try { localStorage.setItem(MASTERED_KEY(docId), JSON.stringify([...ids])); } catch { /* ignore */ }
}

/** 填空题答案模糊匹配: trim + 忽略大小写 + 去除中英文标点 + 合并空格 */
function isAnswerMatch(input: string | null | undefined, answer: string | null | undefined): boolean {
  const normalize = (s: string) =>
    s.trim().toLowerCase().replace(/[.,;:!?，。；：！？、""''「」【】（）()\[\]{}]/g, "").replace(/\s+/g, " ").trim();
  return normalize(input ?? "") === normalize(answer ?? "");
}

export function ErrorBookView({ docId, onBack, onContextChange }: Props) {
  const [items, setItems] = useState<ErrorBookItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [mastered, setMastered] = useState<Set<number>>(() => (docId ? loadMastered(docId) : new Set()));

  // --- Browse mode state (original) ---
  const [reviewIdx, setReviewIdx] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  // --- Re-take mode state ---
  const [retakeMode, setRetakeMode] = useState(false);
  const [retakeQueue, setRetakeQueue] = useState<ErrorBookItem[]>([]);
  const [retakeIdx, setRetakeIdx] = useState(0);
  const [retakeSelected, setRetakeSelected] = useState<string | null>(null);
  const [retakeSubmitted, setRetakeSubmitted] = useState(false);
  const [retakeCorrect, setRetakeCorrect] = useState(0);
  const [retakeTotal, setRetakeTotal] = useState(0);
  const [retakeDone, setRetakeDone] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!docId) return;
    // Abort any in-flight request before starting a new one
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    setMastered(loadMastered(docId));
    listErrorBook(docId, controller.signal)
      .then((data) => {
        setItems(data);
        setLoading(false);
      })
      .catch((err) => {
        if ((err as Error).name !== "AbortError") {
          toast.error("加载错题本失败");
          setLoading(false);
        }
      });
    return () => { controller.abort(); };
  }, [docId]);

  useEffect(() => {
    onContextChange?.(`用户在错题本 (${items.length} 道错题)`);
  }, [items.length, onContextChange]);

  const visibleItems = items.filter((i) => !mastered.has(i.quizId));

  const parseOptions = useCallback((opts: string): string[] => {
    try { return JSON.parse(opts); } catch { console.warn("选项数据解析失败"); return []; }
  }, []);

  const persistMastered = useCallback(
    (next: Set<number>) => {
      setMastered(next);
      if (docId) saveMastered(docId, next);
    },
    [docId],
  );

  // ---------- Browse mode handlers ----------

  const current = visibleItems[reviewIdx];

  const isFillBlank = (item: ErrorBookItem | undefined) => item?.quizType === "FILL_BLANK";

  const handleBrowseSubmit = () => {
    if (!selected || !current) return;
    setSubmitted(true);
    const correct = isFillBlank(current)
      ? isAnswerMatch(selected, current.answer)
      : selected === current.answer;
    if (correct) {
      toast.success("答对了！");
    }
  };

  const handleMaster = (quizId: number) => {
    const next = new Set(mastered).add(quizId);
    persistMastered(next);
    if (reviewIdx >= visibleItems.length - 1) {
      setReviewIdx(Math.max(0, reviewIdx - 1));
    }
    toast.success("已标记掌握");
  };

  const goNext = () => {
    if (reviewIdx < visibleItems.length - 1) {
      setReviewIdx((i) => i + 1);
      setSelected(null);
      setSubmitted(false);
    }
  };

  const goPrev = () => {
    if (reviewIdx > 0) {
      setReviewIdx((i) => i - 1);
      setSelected(null);
      setSubmitted(false);
    }
  };

  // ---------- Re-take mode handlers ----------

  const startRetake = useCallback(
    (all: boolean) => {
      const pool = all ? [...items] : [...visibleItems];
      if (pool.length === 0) {
        toast.info("没有需要重做的错题");
        return;
      }
      // Shuffle
      for (let i = pool.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [pool[i], pool[j]] = [pool[j], pool[i]];
      }
      setRetakeQueue(pool);
      setRetakeIdx(0);
      setRetakeSelected(null);
      setRetakeSubmitted(false);
      setRetakeCorrect(0);
      setRetakeTotal(pool.length);
      setRetakeDone(false);
      setRetakeMode(true);
    },
    [items, visibleItems],
  );

  const retakeCurrent = retakeQueue[retakeIdx];

  const handleRetakeSubmit = useCallback(() => {
    if (!retakeSelected || !retakeCurrent) return;
    setRetakeSubmitted(true);
    const isCorrect = isFillBlank(retakeCurrent)
      ? isAnswerMatch(retakeSelected, retakeCurrent.answer)
      : retakeSelected === retakeCurrent.answer;
    if (isCorrect) {
      setRetakeCorrect((c) => c + 1);
      // Auto-mark mastered
      const next = new Set(mastered).add(retakeCurrent.quizId);
      persistMastered(next);
      toast.success("答对了！已自动标记掌握");
    }
  }, [retakeSelected, retakeCurrent, mastered, persistMastered]);

  const handleRetakeNext = useCallback(() => {
    const nextIdx = retakeIdx + 1;
    if (nextIdx >= retakeQueue.length) {
      setRetakeDone(true);
    } else {
      setRetakeIdx(nextIdx);
      setRetakeSelected(null);
      setRetakeSubmitted(false);
    }
  }, [retakeIdx, retakeQueue.length]);

  const exitRetake = useCallback(() => {
    setRetakeMode(false);
    setRetakeDone(false);
    setRetakeQueue([]);
    setReviewIdx(0);
    setSelected(null);
    setSubmitted(false);
  }, []);

  // ---------- Render ----------

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" aria-label="加载中" />
        <span className="sr-only">加载中...</span>
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-4 text-muted-foreground">
        <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
          <BookX className="h-7 w-7 text-muted-foreground/40" />
        </div>
        <div className="text-center space-y-1.5">
          <p className="text-sm font-medium">暂无错题</p>
          <p className="text-xs text-muted-foreground/50">完成测验后，错题会自动汇总到这里</p>
        </div>
        <Button variant="outline" size="sm" className="rounded-xl h-8" onClick={onBack}>
          <ArrowLeft className="h-3.5 w-3.5 mr-1" />
          返回
        </Button>
      </div>
    );
  }

  // All mastered — show congratulations
  if (visibleItems.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-4 text-muted-foreground">
        <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-emerald-50 dark:bg-emerald-950/20">
          <Trophy className="h-7 w-7 text-emerald-500" />
        </div>
        <div className="text-center space-y-1.5">
          <p className="text-sm font-medium">全部掌握</p>
          <p className="text-xs text-muted-foreground/50">所有错题已标记为「已掌握」，可以重做全部题目巩固</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" className="rounded-xl h-8" onClick={() => startRetake(true)}>
            <RotateCw className="h-3.5 w-3.5 mr-1" />
            全部重做
          </Button>
          <Button variant="outline" size="sm" className="rounded-xl h-8" onClick={onBack}>
            <ArrowLeft className="h-3.5 w-3.5 mr-1" />
            返回
          </Button>
        </div>
      </div>
    );
  }

  // ---- Re-take: summary screen ----
  if (retakeMode && retakeDone) {
    const wrongCount = retakeTotal - retakeCorrect;
    return (
      <div className="flex flex-col h-full">
        <HeaderBar onBack={exitRetake} label="重做完成" visibleCount={visibleItems.length} />
        <div className="flex-1 overflow-y-auto">
          <div className="max-w-2xl mx-auto px-6 py-10 flex flex-col items-center gap-6 text-center">
            <div className="flex h-20 w-20 items-center justify-center rounded-full bg-primary/10">
              <Trophy className="h-10 w-10 text-primary" />
            </div>
            <div className="space-y-2">
              <h2 className="text-lg font-semibold">本次重做完成</h2>
              <p className="text-sm text-muted-foreground">
                共 {retakeTotal} 题，答对{" "}
                <span className="font-medium text-emerald-600 dark:text-emerald-400">{retakeCorrect}</span> 题
                {wrongCount > 0 && (
                  <>
                    ，答错 <span className="font-medium text-destructive">{wrongCount}</span> 题
                  </>
                )}
              </p>
              {retakeCorrect > 0 && (
                <p className="text-xs text-muted-foreground/60">答对的题目已自动标记为「已掌握」</p>
              )}
            </div>
            <div className="flex flex-col sm:flex-row gap-3 w-full max-w-xs">
              {wrongCount > 0 && (
                <Button
                  className="flex-1 rounded-xl h-12 text-sm"
                  onClick={() => startRetake(false)}
                >
                  <RotateCw className="h-4 w-4 mr-1.5" />
                  重做错题 ({wrongCount})
                </Button>
              )}
              <Button variant="outline" className="flex-1 rounded-xl h-12 text-sm" onClick={exitRetake}>
                返回错题本
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ---- Re-take: question screen ----
  if (retakeMode && retakeCurrent) {
    const opts = parseOptions(retakeCurrent.options);
    const isCorrect = retakeSubmitted && (isFillBlank(retakeCurrent)
      ? isAnswerMatch(retakeSelected, retakeCurrent.answer)
      : retakeSelected === retakeCurrent.answer);
    const progress = ((retakeIdx + (retakeSubmitted ? 1 : 0)) / retakeTotal) * 100;

    return (
      <div className="flex flex-col h-full">
        <HeaderBar
          onBack={exitRetake}
          label="重新作答"
          visibleCount={visibleItems.length}
          extra={
            <span className="ml-auto text-[11px] text-muted-foreground tabular-nums">
              {retakeIdx + 1} / {retakeTotal}
            </span>
          }
        />
        {/* Progress bar */}
        <div className="h-1 bg-muted/30 shrink-0">
          <div
            className="h-full bg-primary transition-all duration-300"
            style={{ width: `${progress}%` }}
          />
        </div>

        <div className="flex-1 overflow-y-auto">
          <div className="max-w-2xl mx-auto px-6 py-6 space-y-5">
            {/* Error count badge */}
            <div className="flex items-center gap-1 text-[11px] text-muted-foreground/50">
              <AlertTriangle className="h-3 w-3 text-amber-500" />
              历史错误 {retakeCurrent.errorCount} 次
            </div>

            {/* Question */}
            <Card className="rounded-2xl border-border/60 shadow-sm">
              <CardContent className="p-6">
                <p className="text-sm leading-relaxed text-foreground/85">{retakeCurrent.question}</p>
              </CardContent>
            </Card>

            {/* Options */}
            <div className="space-y-2">
              {isFillBlank(retakeCurrent) ? (
                <div className="space-y-3">
                  <input
                    type="text"
                    value={retakeSelected ?? ""}
                    onChange={(e) => { if (!retakeSubmitted) setRetakeSelected(e.target.value); }}
                    disabled={retakeSubmitted}
                    placeholder="输入你的答案..."
                    className={cn(
                      "flex-1 w-full rounded-xl border px-4 py-2.5 text-sm outline-none transition-all",
                      !retakeSubmitted && "border-border/60 bg-background focus:border-primary/50 focus:ring-2 focus:ring-primary/10",
                      retakeSubmitted && isCorrect && "border-emerald-500 bg-emerald-50 dark:bg-emerald-950/20",
                      retakeSubmitted && !isCorrect && "border-destructive bg-destructive/5",
                    )}
                    onKeyDown={(e) => { if (e.key === "Enter" && !retakeSubmitted && retakeSelected) handleRetakeSubmit(); }}
                    aria-label="填空题答案输入"
                  />
                  {retakeSubmitted && (
                    <div className={cn(
                      "flex items-center gap-2 rounded-xl px-4 py-3 text-sm",
                      isCorrect
                        ? "bg-emerald-50 dark:bg-emerald-950/20 text-emerald-700 dark:text-emerald-300"
                        : "bg-destructive/5 text-destructive"
                    )}>
                      {isCorrect
                        ? <><Check className="h-4 w-4 shrink-0" /><span>正确！</span></>
                        : <><X className="h-4 w-4 shrink-0" /><span>正确答案: <strong>{retakeCurrent.answer}</strong></span></>
                      }
                    </div>
                  )}
                </div>
              ) : (
                opts.map((opt) => {
                  const isSel = retakeSelected === opt;
                  const showCorrect = retakeSubmitted && opt === retakeCurrent.answer;
                  const showWrong = retakeSubmitted && isSel && opt !== retakeCurrent.answer;
                  return (
                    <button
                      key={opt}
                      onClick={() => {
                        if (!retakeSubmitted) setRetakeSelected(opt);
                      }}
                      disabled={retakeSubmitted}
                      className={cn(
                        "w-full text-left px-4 py-3.5 rounded-xl border text-sm transition-all min-h-[44px]",
                        !retakeSubmitted && "active:bg-muted/50 cursor-pointer",
                        retakeSubmitted && "cursor-default",
                        isSel && !retakeSubmitted && "border-primary bg-primary/5",
                        showCorrect &&
                          "border-emerald-500 bg-emerald-50 dark:bg-emerald-950/20 text-emerald-700 dark:text-emerald-300",
                        showWrong && "border-destructive bg-destructive/5 text-destructive",
                        !isSel && !showCorrect && retakeSubmitted && "text-muted-foreground/50",
                      )}
                    >
                      <div className="flex items-center gap-2">
                        {showCorrect && <Check className="h-4 w-4 shrink-0 text-emerald-500" />}
                        {showWrong && <X className="h-4 w-4 shrink-0 text-destructive" />}
                        <span>{opt}</span>
                      </div>
                    </button>
                  );
                })
              )}
            </div>

            {/* Actions */}
            <div className="flex items-center gap-3 pt-2">
              {!retakeSubmitted ? (
                <Button
                  className="rounded-xl h-11 px-6 text-sm"
                  disabled={!retakeSelected}
                  onClick={handleRetakeSubmit}
                >
                  提交答案
                </Button>
              ) : (
                <Button
                    className="rounded-xl h-11 px-6 text-sm"
                    onClick={handleRetakeNext}
                  >
                    {retakeIdx < retakeQueue.length - 1 ? "下一题" : "查看结果"}
                  </Button>
              )}
            </div>

            {/* Explanation (shown after submit, especially for wrong answers) */}
            {retakeSubmitted && retakeCurrent.explanation && (
              <Card
                className={cn(
                  "rounded-2xl border-border/40",
                  isCorrect ? "bg-emerald-50/50 dark:bg-emerald-950/10" : "bg-destructive/5",
                )}
              >
                <CardContent className="p-4">
                  <span className="text-[10px] font-medium text-muted-foreground/50 uppercase tracking-wider">
                    {isCorrect ? "解析" : "答错了，看看解析"}
                  </span>
                  <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {retakeCurrent.explanation}
                  </p>
                </CardContent>
              </Card>
            )}
          </div>
        </div>
      </div>
    );
  }

  // ---- Browse mode (default) ----
  return (
    <div className="flex flex-col h-full">
      <HeaderBar onBack={onBack} label="错题本" visibleCount={visibleItems.length} />

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-2xl mx-auto px-6 py-8 space-y-6">
          {/* Re-take entry buttons */}
          <div className="flex flex-col sm:flex-row gap-2">
            <Button
              className="flex-1 rounded-xl h-12 text-sm gap-2"
              onClick={() => startRetake(true)}
            >
              <PlayCircle className="h-4 w-4" />
              全部重做 ({items.length} 题)
            </Button>
            {visibleItems.length < items.length && visibleItems.length > 0 && (
              <Button
                variant="outline"
                className="flex-1 rounded-xl h-12 text-sm gap-2"
                onClick={() => startRetake(false)}
              >
                <RotateCw className="h-4 w-4" />
                重做未掌握 ({visibleItems.length} 题)
              </Button>
            )}
          </div>

          {/* Progress */}
          <div className="flex items-center justify-between text-[11px] text-muted-foreground/50">
            <span>
              {reviewIdx + 1} / {visibleItems.length}
            </span>
            <span className="flex items-center gap-1">
              <AlertTriangle className="h-3 w-3 text-amber-500" />
              错误 {current?.errorCount ?? 0} 次
            </span>
          </div>

          {/* Question */}
          <Card className="rounded-2xl border-border/60 shadow-sm">
            <CardContent className="p-6">
              <p className="text-sm leading-relaxed text-foreground/85">{current?.question}</p>
            </CardContent>
          </Card>

          {/* Options */}
          <div className="space-y-2">
            {current && (
              isFillBlank(current) ? (
                <div className="space-y-3">
                  <input
                    type="text"
                    value={selected ?? ""}
                    onChange={(e) => { if (!submitted) setSelected(e.target.value); }}
                    disabled={submitted}
                    placeholder="输入你的答案..."
                    className={cn(
                      "flex-1 w-full rounded-xl border px-4 py-2.5 text-sm outline-none transition-all",
                      !submitted && "border-border/60 bg-background focus:border-primary/50 focus:ring-2 focus:ring-primary/10",
                      submitted && isAnswerMatch(selected, current.answer) && "border-emerald-500 bg-emerald-50 dark:bg-emerald-950/20",
                      submitted && !isAnswerMatch(selected, current.answer) && "border-destructive bg-destructive/5",
                    )}
                    onKeyDown={(e) => { if (e.key === "Enter" && !submitted && selected) handleBrowseSubmit(); }}
                    aria-label="填空题答案输入"
                  />
                  {submitted && (
                    <div className={cn(
                      "flex items-center gap-2 rounded-xl px-4 py-3 text-sm",
                      isAnswerMatch(selected, current.answer)
                        ? "bg-emerald-50 dark:bg-emerald-950/20 text-emerald-700 dark:text-emerald-300"
                        : "bg-destructive/5 text-destructive"
                    )}>
                      {isAnswerMatch(selected, current.answer)
                        ? <><Check className="h-4 w-4 shrink-0" /><span>正确！</span></>
                        : <><X className="h-4 w-4 shrink-0" /><span>正确答案: <strong>{current.answer}</strong></span></>
                      }
                    </div>
                  )}
                </div>
              ) : (
                parseOptions(current.options).map((opt) => {
                  const isSelected = selected === opt;
                  const showCorrect = submitted && opt === current.answer;
                  const showWrong = submitted && isSelected && opt !== current.answer;
                  return (
                    <button
                      key={opt}
                      onClick={() => {
                        if (!submitted) setSelected(opt);
                      }}
                      disabled={submitted}
                      className={cn(
                        "w-full text-left px-4 py-3.5 rounded-xl border text-sm transition-all min-h-[44px]",
                        !submitted && "active:bg-muted/50 cursor-pointer",
                        submitted && "cursor-default",
                        isSelected && !submitted && "border-primary bg-primary/5",
                        showCorrect &&
                          "border-emerald-500 bg-emerald-50 dark:bg-emerald-950/20 text-emerald-700 dark:text-emerald-300",
                        showWrong && "border-destructive bg-destructive/5 text-destructive",
                        !isSelected && !showCorrect && submitted && "text-muted-foreground/50",
                      )}
                    >
                      <div className="flex items-center gap-2">
                        {showCorrect && <Check className="h-4 w-4 shrink-0 text-emerald-500" />}
                        {showWrong && <X className="h-4 w-4 shrink-0 text-destructive" />}
                        <span>{opt}</span>
                      </div>
                    </button>
                  );
                })
              )
            )}
          </div>

          {/* Actions */}
          <div className="flex items-center gap-3 pt-2">
            {!submitted ? (
              <Button
                className="rounded-xl h-11 px-6 text-sm"
                disabled={!selected}
                onClick={handleBrowseSubmit}
              >
                提交答案
              </Button>
            ) : (
              <>
                {(isFillBlank(current)
                  ? isAnswerMatch(selected, current?.answer)
                  : selected === current?.answer) && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="rounded-lg text-xs h-8 border-emerald-200 dark:border-emerald-800 text-emerald-600 dark:text-emerald-400"
                    onClick={() => handleMaster(current.quizId)}
                  >
                    <Check className="h-3 w-3 mr-1" />
                    已掌握，移除
                  </Button>
                )}
                {reviewIdx < visibleItems.length - 1 && (
                  <Button className="rounded-xl h-11 px-6 text-sm" onClick={goNext}>
                    下一错题
                  </Button>
                )}
              </>
            )}
            {reviewIdx > 0 && (
              <Button variant="ghost" size="sm" className="rounded-lg text-xs h-8" onClick={goPrev}>
                上一题
              </Button>
            )}
          </div>

          {/* Explanation */}
          {submitted && current?.explanation && (
            <Card className="rounded-2xl border-border/40 bg-muted/10">
              <CardContent className="p-4">
                <span className="text-[10px] font-medium text-muted-foreground/50 uppercase tracking-wider">
                  解析
                </span>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{current.explanation}</p>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

// ---- Shared header bar ----
function HeaderBar({
  onBack,
  label,
  visibleCount,
  extra,
}: {
  onBack: () => void;
  label: string;
  visibleCount: number;
  extra?: React.ReactNode;
}) {
  return (
    <div className="flex items-center gap-2 px-4 py-2 border-b bg-muted/20 shrink-0">
      <Button
        variant="ghost"
        size="sm"
        className="h-7 rounded-lg text-xs gap-1.5"
        onClick={onBack}
      >
        <ArrowLeft className="h-3 w-3" />
        返回
      </Button>
      <span className="text-[11px] text-muted-foreground/40">/</span>
      <span className="text-[11px] text-muted-foreground">{label}</span>
      {extra ?? (
        <span className="ml-auto text-[11px] text-muted-foreground">
          {visibleCount} 道错题
        </span>
      )}
    </div>
  );
}
