"use client";

import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";
import {
  getStats,
  getStatsReport,
  getLearningStats,
  type StatsResponse,
  type LearningStatsResponse,
} from "@/lib/api";
import {
  FileText,
  Layers,
  Brain,
  MessageSquare,
  Target,
  Shield,
  TrendingUp,
  BarChart3,
  Loader2,
  FileWarning,
  Zap,
  Flame,
  BookOpen,
  Trophy,
} from "lucide-react";

export function StatsDashboard() {
  const [stats, setStats] = useState<StatsResponse | null>(null);
  const [learning, setLearning] = useState<LearningStatsResponse | null>(null);
  const [report, setReport] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [reportOpen, setReportOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const [s, r, l] = await Promise.all([
          getStats(),
          getStatsReport(),
          getLearningStats().catch(() => null),
        ]);
        if (!cancelled) {
          setStats(s);
          setReport(r.content);
          setLearning(l);
        }
      } catch {
        /* ignore */
      }
      if (!cancelled) setLoading(false);
    }
    load();
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          加载数据看板...
        </div>
      </div>
    );
  }

  if (!stats) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center space-y-2">
          <FileWarning className="h-8 w-8 text-muted-foreground/40 mx-auto" />
          <p className="text-sm text-muted-foreground">数据看板暂不可用</p>
        </div>
      </div>
    );
  }

  const d = stats.dataAssetMetrics;
  const e = stats.efficiencyMetrics;
  const g = stats.governanceMetrics;
  const eval_ = stats.evalMetrics;

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="max-w-5xl mx-auto p-4 md:p-6 space-y-4 md:space-y-6">
        {/* 标题 */}
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-lg font-semibold text-foreground">数据资产看板</h2>
            <p className="text-xs text-muted-foreground mt-0.5">
              非结构化数据向生产要素转化的量化指标
            </p>
          </div>
          <button
            type="button"
            onClick={() => setReportOpen((v) => !v)}
            className={cn(
              "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all",
              reportOpen
                ? "bg-primary/10 text-primary"
                : "bg-muted text-muted-foreground hover:text-foreground",
            )}
          >
            <FileText className="h-3.5 w-3.5" />
            数据素质自评报告
          </button>
        </div>

        {/* 自评报告（可折叠） */}
        {reportOpen && report && (
          <div className="rounded-xl border border-border/50 bg-muted/30 p-4">
            <pre className="text-xs text-muted-foreground whitespace-pre-wrap font-mono leading-relaxed max-h-80 overflow-y-auto">
              {report}
            </pre>
          </div>
        )}

        {/* RAG 评测指标（最上方突出显示） */}
        {eval_ && (
          <section>
            <div className="flex items-center gap-2 mb-3">
              <Target className="h-4 w-4 text-primary" />
              <h3 className="text-sm font-semibold text-foreground">RAG 质量评测</h3>
              <span className="text-[10px] text-muted-foreground/60">
                基于 {eval_.totalQuestions} 组问答 · 语义空间向量对齐
              </span>
            </div>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              <MetricCard
                label="检索命中率"
                value={`${(eval_.hitRate * 100).toFixed(1)}%`}
                sub="Cosine ≥ 0.75"
                color="blue"
              />
              <MetricCard
                label="内容忠实度"
                value={`${eval_.avgFaithfulness.toFixed(1)}/5`}
                sub="零幻觉验证"
                color="green"
              />
              <MetricCard
                label="回答准确率"
                value={`${eval_.avgCorrectness.toFixed(1)}/5`}
                sub="语义一致性"
                color="emerald"
              />
              <MetricCard
                label="综合数据素质"
                value={`${(eval_.compositeScore * 100).toFixed(1)}%`}
                sub="加权综合得分"
                color="purple"
                highlight
              />
            </div>
          </section>
        )}

        {/* 学习行为统计 */}
        {learning && (
          <section>
            <div className="flex items-center gap-2 mb-3">
              <BookOpen className="h-4 w-4 text-primary" />
              <h3 className="text-sm font-semibold text-foreground">学习行为</h3>
              <span className="text-[10px] text-muted-foreground/60">个人学习数据</span>
            </div>

            {/* 今日概况 + 连续天数 */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
              <MetricCard
                icon={Layers}
                label="今日复习"
                value={String(learning.today.flashcardReviews)}
                sub="张卡片"
                color="blue"
              />
              <MetricCard
                icon={Target}
                label="今日测验"
                value={String(learning.today.quizAttempts)}
                sub="次"
                color="green"
              />
              <MetricCard
                icon={TrendingUp}
                label="今日正确率"
                value={learning.today.totalQuestionsAnswered > 0 ? `${learning.today.quizAccuracy}%` : "-"}
                sub={learning.today.totalQuestionsAnswered > 0 ? `${learning.today.totalCorrect}/${learning.today.totalQuestionsAnswered}` : "暂无数据"}
                color="emerald"
              />
              <MetricCard
                icon={Flame}
                label="连续学习"
                value={String(learning.allTime.streakDays)}
                sub="天"
                color="purple"
                highlight={learning.allTime.streakDays >= 3}
              />
            </div>

            {/* 近 7 天复习趋势 */}
            <div className="rounded-xl border border-border/50 bg-card p-4">
              <div className="flex items-center justify-between mb-3">
                <span className="text-xs font-medium text-foreground/70">近 7 天复习趋势</span>
                <div className="flex items-center gap-3">
                  <span className="flex items-center gap-1 text-[10px] text-muted-foreground/50">
                    <span className="inline-block h-2 w-2 rounded-sm bg-blue-400" />卡片
                  </span>
                  <span className="flex items-center gap-1 text-[10px] text-muted-foreground/50">
                    <span className="inline-block h-2 w-2 rounded-sm bg-emerald-400" />正确率
                  </span>
                </div>
              </div>
              <div className="flex items-end gap-2 h-32">
                {learning.weekly.map((day) => {
                  const maxReviews = Math.max(...learning.weekly.map(d => d.flashcardReviews), 1);
                  const reviewH = (day.flashcardReviews / maxReviews) * 100;
                  const dayLabel = day.date.slice(5); // MM-DD
                  return (
                    <div key={day.date} className="flex-1 flex flex-col items-center gap-1">
                      <div className="relative w-full flex flex-col items-center" style={{ height: "100px" }}>
                        {/* 正确率背景条 */}
                        {day.quizAccuracy > 0 && (
                          <div
                            className="absolute bottom-0 w-full rounded-t-sm bg-emerald-200 dark:bg-emerald-900/30 opacity-40"
                            style={{ height: `${day.quizAccuracy}%` }}
                          />
                        )}
                        {/* 复习卡片条 */}
                        <div
                          className="absolute bottom-0 w-full rounded-t-sm bg-blue-400 dark:bg-blue-500 transition-all duration-500"
                          style={{ height: `${Math.max(reviewH, day.flashcardReviews > 0 ? 8 : 0)}%` }}
                        />
                      </div>
                      <span className="text-[9px] text-muted-foreground/40">{dayLabel}</span>
                    </div>
                  );
                })}
              </div>
              <div className="flex justify-between mt-2 pt-2 border-t border-border/30">
                <span className="text-[10px] text-muted-foreground/50">
                  7 天累计: {learning.weekly.reduce((s, d) => s + d.flashcardReviews, 0)} 张卡片
                </span>
                <span className="text-[10px] text-muted-foreground/50">
                  累计 {learning.allTime.totalFlashcardReviews} 张 · {learning.allTime.totalQuizAttempts} 次测验
                </span>
              </div>
            </div>
          </section>
        )}

        {/* 数据资产指标 */}
        <section>
          <div className="flex items-center gap-2 mb-3">
            <BarChart3 className="h-4 w-4 text-primary" />
            <h3 className="text-sm font-semibold text-foreground">数据资产</h3>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-5 gap-3">
            <MetricCard
              icon={FileText}
              label="处理文档"
              value={String(d.totalDocuments)}
              sub="份"
            />
            <MetricCard
              icon={Layers}
              label="非结构化数据"
              value={formatChars(d.totalCharsProcessed)}
              sub="字符"
            />
            <MetricCard
              icon={Brain}
              label="思维导图"
              value={String(d.totalMindMaps)}
              sub="张"
            />
            <MetricCard
              icon={FileText}
              label="学习笔记"
              value={String(d.totalStudyNotes)}
              sub="份"
            />
            <MetricCard
              icon={Layers}
              label="闪卡"
              value={String(d.totalFlashcards)}
              sub="张"
            />
            <MetricCard
              icon={Target}
              label="测验题"
              value={String(d.totalQuizzes)}
              sub="道"
            />
            <MetricCard
              icon={MessageSquare}
              label="AI 对话"
              value={String(d.totalChatExchanges)}
              sub="次"
            />
            <MetricCard
              icon={Layers}
              label="卡片组"
              value={String(d.totalDecks)}
              sub="个"
            />
            <MetricCard
              label="平均切片"
              value={d.avgChunksPerDocument.toFixed(1)}
              sub="每文档"
            />
            <MetricCard
              label="向量覆盖率"
              value={`${(d.vectorCoverageRate * 100).toFixed(1)}%`}
              sub="已向量化"
            />
          </div>
        </section>

        {/* 效率 + 治理 双列 */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <section className="rounded-xl border border-border/50 bg-card p-4">
            <div className="flex items-center gap-2 mb-3">
              <Zap className="h-4 w-4 text-amber-500" />
              <h3 className="text-sm font-semibold text-foreground">效率提升</h3>
            </div>
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">备课时间缩减</span>
                <span className="text-sm font-bold text-foreground">
                  {e.estimatedPrepTimeReduction}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">学习效率提升</span>
                <span className="text-sm font-bold text-foreground">
                  {e.estimatedLearningEfficiencyGain}
                </span>
              </div>
              <div className="pt-2 border-t border-border/30">
                <p className="text-[11px] text-muted-foreground/70 leading-relaxed">
                  {e.dataToAssetConversionRate}
                </p>
              </div>
            </div>
          </section>

          <section className="rounded-xl border border-border/50 bg-card p-4">
            <div className="flex items-center gap-2 mb-3">
              <Shield className="h-4 w-4 text-emerald-500" />
              <h3 className="text-sm font-semibold text-foreground">合规治理</h3>
            </div>
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">审计通过率</span>
                <span className="text-sm font-bold text-emerald-600 dark:text-emerald-400">
                  {(g.auditPassRate * 100).toFixed(0)}%
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">可溯源回答比例</span>
                <span className="text-sm font-bold text-emerald-600 dark:text-emerald-400">
                  {(g.traceableResponseRate * 100).toFixed(0)}%
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">累计审计日志</span>
                <span className="text-sm font-bold text-foreground">
                  {g.totalAuditLogs}
                </span>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}

function MetricCard({
  icon: Icon,
  label,
  value,
  sub,
  color = "default",
  highlight = false,
}: {
  icon?: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
  sub?: string;
  color?: "default" | "blue" | "green" | "emerald" | "purple";
  highlight?: boolean;
}) {
  const colors: Record<string, string> = {
    blue: "bg-blue-50 dark:bg-blue-950/30 border-blue-200 dark:border-blue-800",
    green: "bg-green-50 dark:bg-green-950/30 border-green-200 dark:border-green-800",
    emerald:
      "bg-emerald-50 dark:bg-emerald-950/30 border-emerald-200 dark:border-emerald-800",
    purple:
      "bg-purple-50 dark:bg-purple-950/30 border-purple-200 dark:border-purple-800",
    default: "bg-card",
  };

  return (
    <div
      className={cn(
        "rounded-xl border border-border/50 p-3 transition-all",
        highlight && "ring-1 ring-primary/30 shadow-sm",
        colors[color],
      )}
    >
      <div className="flex items-center gap-1.5 mb-1.5">
        {Icon && (
          <Icon className="h-3 w-3 text-muted-foreground/60 shrink-0" />
        )}
        <span className="text-[10px] text-muted-foreground/60 uppercase tracking-wider">
          {label}
        </span>
      </div>
      <div className="flex items-baseline gap-1">
        <span
          className={cn(
            "text-xl font-bold tracking-tight",
            highlight ? "text-primary" : "text-foreground",
          )}
        >
          {value}
        </span>
        {sub && (
          <span className="text-[10px] text-muted-foreground/50">{sub}</span>
        )}
      </div>
    </div>
  );
}

function formatChars(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}
