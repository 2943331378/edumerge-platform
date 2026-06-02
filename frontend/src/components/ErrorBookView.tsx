"use client";

/**
 * 全局错题本 — 聚合所有测验中的错误题目
 *
 * 功能:
 * - 按错误次数降序展示错题
 * - 支持逐题重做
 * - 标记已掌握后隐藏
 */

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import type { ErrorBookItem } from "@/lib/api";
import { listErrorBook } from "@/lib/api";
import { BookX, RotateCw, Check, X, ArrowLeft, Loader2, AlertTriangle } from "lucide-react";

interface Props {
  docId: number | null;
  onBack: () => void;
  onContextChange?: (hint: string) => void;
}

export function ErrorBookView({ docId, onBack, onContextChange }: Props) {
  const [items, setItems] = useState<ErrorBookItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [reviewIdx, setReviewIdx] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [mastered, setMastered] = useState<Set<number>>(new Set());

  useEffect(() => {
    if (!docId) return;
    setLoading(true);
    listErrorBook(docId)
      .then((data) => { setItems(data); setLoading(false); })
      .catch(() => { setLoading(false); });
  }, [docId]);

  useEffect(() => {
    onContextChange?.(`用户在错题本 (${items.length} 道错题)`);
  }, [items.length, onContextChange]);

  const visibleItems = items.filter((i) => !mastered.has(i.quizId));
  const current = visibleItems[reviewIdx];

  const parseOptions = (opts: string): string[] => {
    try { return JSON.parse(opts); } catch { return []; }
  };

  const handleSubmit = () => {
    if (!selected || !current) return;
    setSubmitted(true);
    if (selected === current.answer) {
      toast.success("答对了！");
    }
  };

  const handleMaster = (quizId: number) => {
    setMastered((prev) => new Set(prev).add(quizId));
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

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
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
          <ArrowLeft className="h-3.5 w-3.5 mr-1" />返回
        </Button>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center gap-2 px-4 py-2 border-b bg-muted/20 shrink-0">
        <Button variant="ghost" size="sm" className="h-7 rounded-lg text-xs gap-1.5" onClick={onBack}>
          <ArrowLeft className="h-3 w-3" />返回
        </Button>
        <span className="text-[11px] text-muted-foreground/40">/</span>
        <span className="text-[11px] text-muted-foreground">错题本</span>
        <span className="ml-auto text-[11px] text-muted-foreground">
          {visibleItems.length} 道错题
        </span>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-2xl mx-auto px-6 py-8 space-y-6">
          {/* Progress */}
          <div className="flex items-center justify-between text-[11px] text-muted-foreground/50">
            <span>{reviewIdx + 1} / {visibleItems.length}</span>
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
            {current && parseOptions(current.options).map((opt) => {
              const isSelected = selected === opt;
              const showCorrect = submitted && opt === current.answer;
              const showWrong = submitted && isSelected && opt !== current.answer;
              return (
                <button
                  key={opt}
                  onClick={() => { if (!submitted) setSelected(opt); }}
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

          {/* Actions */}
          <div className="flex items-center gap-3 pt-2">
            {!submitted ? (
              <Button className="rounded-xl h-9" disabled={!selected} onClick={handleSubmit}>
                提交答案
              </Button>
            ) : (
              <>
                {selected === current?.answer && (
                  <Button variant="outline" size="sm" className="rounded-lg text-xs h-8 border-emerald-200 dark:border-emerald-800 text-emerald-600 dark:text-emerald-400" onClick={() => handleMaster(current.quizId)}>
                    <Check className="h-3 w-3 mr-1" />已掌握，移除
                  </Button>
                )}
                {reviewIdx < visibleItems.length - 1 && (
                  <Button className="rounded-xl h-9" onClick={goNext}>
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
                <span className="text-[10px] font-medium text-muted-foreground/50 uppercase tracking-wider">解析</span>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{current.explanation}</p>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
