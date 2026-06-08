"use client";

import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";
import { getLearnerDashboard, type LearnerDashboardResponse } from "@/lib/api";
import { Card, CardContent } from "@/components/ui/card";
import { Layers, HelpCircle, Flame, X, RotateCcw, ChevronRight, Loader2 } from "lucide-react";

const DISMISS_KEY = "edumerge_today_tasks_dismissed";

function isDismissedToday(): boolean {
  try {
    const val = localStorage.getItem(DISMISS_KEY);
    if (!val) return false;
    // Dismissed for today only
    return val === new Date().toISOString().slice(0, 10);
  } catch {
    return false;
  }
}

function dismissToday() {
  try {
    localStorage.setItem(DISMISS_KEY, new Date().toISOString().slice(0, 10));
  } catch { /* ignore */ }
}

export function TodayTasksCard({
  activeSessionExists,
  onGoFlashcard,
  onGoQuiz,
}: {
  activeSessionExists: boolean;
  /** Navigate to flashcard view for the doc with most due cards */
  onGoFlashcard: (docId: number) => void;
  /** Navigate to quiz view */
  onGoQuiz: () => void;
}) {
  const [data, setData] = useState<LearnerDashboardResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [dismissed, setDismissed] = useState(true);

  useEffect(() => {
    setDismissed(isDismissedToday());
  }, []);

  useEffect(() => {
    if (dismissed || !activeSessionExists) return;
    let cancelled = false;
    setLoading(true);
    getLearnerDashboard()
      .then((d) => { if (!cancelled) setData(d); })
      .catch(() => { /* silent */ })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [dismissed, activeSessionExists]);

  // Hide entirely if dismissed or no session
  if (dismissed || !activeSessionExists) return null;

  const totalDue = data?.dueDocs?.reduce((sum, d) => sum + d.dueCount, 0) ?? 0;
  const streak = data?.rhythm?.streakDays ?? 0;
  const reviewedToday = data?.today?.reviewedCards ?? 0;
  const quizToday = data?.today?.quizAttempts ?? 0;

  // The doc with the most due cards (for the "start review" button)
  const topDueDoc = data?.dueDocs?.length
    ? data.dueDocs.reduce((max, d) => (d.dueCount > max.dueCount ? d : max), data.dueDocs[0])
    : null;

  // Suggest quiz if user has due flashcards but hasn't done quizzes recently
  const suggestQuiz = totalDue > 0 && quizToday === 0;

  const handleDismiss = () => {
    dismissToday();
    setDismissed(true);
  };

  return (
    <Card className="relative mx-2 md:mx-4 mt-2 border-l-4 border-l-primary/60 bg-gradient-to-r from-primary/[0.04] to-transparent">
      {/* Dismiss button */}
      <button
        type="button"
        onClick={handleDismiss}
        className="absolute top-2 right-2 h-7 w-7 flex items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all z-10"
        aria-label="关闭今日任务"
      >
        <X className="h-3.5 w-3.5" />
      </button>

      <CardContent className="px-3 md:px-4 py-2 md:py-3">
        {loading ? (
          <div className="flex items-center justify-center py-2">
            <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
            <span className="ml-2 text-xs text-muted-foreground">加载今日任务...</span>
          </div>
        ) : (
          <div className="flex flex-col gap-2.5">
            {/* Metrics row */}
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
              {/* Due cards */}
              <div className="flex items-center gap-1.5">
                <div className="flex h-6 w-6 items-center justify-center rounded-md bg-orange-500/10">
                  <RotateCcw className="h-3.5 w-3.5 text-orange-500" />
                </div>
                <span className="text-xs">
                  <span className="text-muted-foreground">到期待复习:</span>{" "}
                  <span className={cn("font-semibold", totalDue > 0 ? "text-orange-600 dark:text-orange-400" : "text-muted-foreground/60")}>
                    {totalDue} 张卡片
                  </span>
                </span>
              </div>

              {/* Quiz suggestion */}
              {suggestQuiz && (
                <div className="flex items-center gap-1.5">
                  <div className="flex h-6 w-6 items-center justify-center rounded-md bg-blue-500/10">
                    <HelpCircle className="h-3.5 w-3.5 text-blue-500" />
                  </div>
                  <span className="text-xs text-muted-foreground">
                    待做测验
                  </span>
                </div>
              )}

              {/* Streak */}
              <div className="flex items-center gap-1.5">
                <div className="flex h-6 w-6 items-center justify-center rounded-md bg-amber-500/10">
                  <Flame className="h-3.5 w-3.5 text-amber-500" />
                </div>
                <span className="text-xs">
                  <span className="text-muted-foreground">连续学习:</span>{" "}
                  <span className={cn("font-semibold", streak > 0 ? "text-amber-600 dark:text-amber-400" : "text-muted-foreground/60")}>
                    {streak} 天
                  </span>
                </span>
              </div>
            </div>

            {/* Action buttons + encouragement */}
            <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2">
              <div className="flex gap-2 flex-1">
                {/* Start review button */}
                {totalDue > 0 && topDueDoc && (
                  <button
                    type="button"
                    onClick={() => onGoFlashcard(topDueDoc.docId)}
                    className={cn(
                      "flex items-center justify-center gap-1.5 rounded-lg px-4",
                      "min-h-[44px]", // touch target
                      "bg-primary text-primary-foreground text-sm font-medium",
                      "hover:bg-primary/90 active:scale-[0.97] transition-all",
                    )}
                  >
                    <Layers className="h-4 w-4" />
                    开始复习
                    <ChevronRight className="h-3.5 w-3.5 opacity-60" />
                  </button>
                )}

                {/* Quiz button */}
                <button
                  type="button"
                  onClick={onGoQuiz}
                  className={cn(
                    "flex items-center justify-center gap-1.5 rounded-lg px-4",
                    "min-h-[44px]", // touch target
                    "border border-border bg-background text-foreground text-sm font-medium",
                    "hover:bg-muted active:scale-[0.97] transition-all",
                  )}
                >
                  <HelpCircle className="h-4 w-4" />
                  做测验
                </button>
              </div>

              {/* Encouragement text */}
              {(reviewedToday > 0 || quizToday > 0) && (
                <p className="text-[11px] text-muted-foreground/70 text-center sm:text-right shrink-0">
                  今日已完成:{" "}
                  {reviewedToday > 0 && <span>{reviewedToday} 张复习</span>}
                  {reviewedToday > 0 && quizToday > 0 && ", "}
                  {quizToday > 0 && <span>{quizToday} 道测验</span>}
                </p>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
