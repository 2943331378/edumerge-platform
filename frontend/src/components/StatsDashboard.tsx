"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  getLearnerDashboard,
  type LearnerDashboardResponse,
} from "@/lib/api";
import {
  Flame, Layers, Target, BookOpen, Clock, Trophy, Star, Zap,
  Loader2, FileWarning, Upload, GitFork, RotateCcw,
  AlertCircle, ChevronRight, ChevronDown, BarChart3, Settings2, Calendar,
  CheckCircle2, Medal, Crown, Rocket, TrendingUp, TrendingDown, Minus,
} from "lucide-react";

const DAILY_GOAL_KEY = "edumerge_daily_goal";
const DAILY_GOAL_DEFAULT = 30;
const WEEKLY_GOAL_KEY = "edumerge_weekly_goal";
const WEEKLY_GOAL_DEFAULT = 200;
const MONTHLY_GOAL_KEY = "edumerge_monthly_goal";
const MONTHLY_GOAL_DEFAULT = 600;

function getGoal(key: string, fallback: number): number {
  try {
    const v = localStorage.getItem(key);
    if (v) { const n = parseInt(v, 10); if (n > 0) return n; }
  } catch { /* ignore */ }
  return fallback;
}

function setGoal(key: string, value: number) {
  try { localStorage.setItem(key, String(value)); } catch { /* ignore */ }
}

const GOAL_PRESETS = [
  { name: "轻松", daily: 15, weekly: 100, monthly: 300, desc: "适合碎片化学习" },
  { name: "标准", daily: 30, weekly: 200, monthly: 600, desc: "日常学习节奏" },
  { name: "挑战", daily: 50, weekly: 350, monthly: 1000, desc: "高强度冲刺" },
] as const;

interface BadgeDef {
  id: string;
  name: string;
  desc: string;
  icon: React.ComponentType<{ className?: string }>;
  color: string;
  bg: string;
  check: (data: LearnerDashboardResponse) => boolean;
  progress?: (data: LearnerDashboardResponse) => { current: number; target: number };
}

const BADGES: BadgeDef[] = [
  {
    id: "first-review",
    name: "初学者",
    desc: "完成首次卡片复习",
    icon: Star,
    color: "text-blue-500",
    bg: "bg-blue-500/10",
    check: (d) => d.achievement.totalReviews >= 1,
  },
  {
    id: "review-100",
    name: "勤奋学徒",
    desc: "累计复习 100 张卡片",
    icon: Medal,
    color: "text-emerald-500",
    bg: "bg-emerald-500/10",
    check: (d) => d.achievement.totalReviews >= 100,
    progress: (d) => ({ current: d.achievement.totalReviews, target: 100 }),
  },
  {
    id: "review-500",
    name: "复习大师",
    desc: "累计复习 500 张卡片",
    icon: Crown,
    color: "text-amber-500",
    bg: "bg-amber-500/10",
    check: (d) => d.achievement.totalReviews >= 500,
    progress: (d) => ({ current: d.achievement.totalReviews, target: 500 }),
  },
  {
    id: "streak-3",
    name: "三日之约",
    desc: "连续学习 3 天",
    icon: Flame,
    color: "text-orange-500",
    bg: "bg-orange-500/10",
    check: (d) => d.rhythm.streakDays >= 3,
    progress: (d) => ({ current: d.rhythm.streakDays, target: 3 }),
  },
  {
    id: "streak-7",
    name: "周周坚持",
    desc: "连续学习 7 天",
    icon: Zap,
    color: "text-amber-500",
    bg: "bg-amber-500/10",
    check: (d) => d.rhythm.streakDays >= 7,
    progress: (d) => ({ current: d.rhythm.streakDays, target: 7 }),
  },
  {
    id: "streak-30",
    name: "月度之星",
    desc: "连续学习 30 天",
    icon: Trophy,
    color: "text-yellow-500",
    bg: "bg-yellow-500/10",
    check: (d) => d.rhythm.streakDays >= 30,
    progress: (d) => ({ current: d.rhythm.streakDays, target: 30 }),
  },
  {
    id: "quiz-accuracy",
    name: "测验高手",
    desc: "平均正确率 ≥ 80%",
    icon: Target,
    color: "text-emerald-500",
    bg: "bg-emerald-500/10",
    check: (d) => d.achievement.avgAccuracy >= 80 && d.achievement.totalQuizzes >= 3,
  },
  {
    id: "perfect-score",
    name: "满分达成",
    desc: "单次测验 100% 正确率",
    icon: CheckCircle2,
    color: "text-green-500",
    bg: "bg-green-500/10",
    check: (d) => d.today.quizAccuracy === 100 && d.today.totalAnswered > 0,
  },
  {
    id: "doc-explorer",
    name: "知识探索者",
    desc: "上传 5 份学习材料",
    icon: Rocket,
    color: "text-violet-500",
    bg: "bg-violet-500/10",
    check: (d) => d.achievement.totalDocs >= 5,
    progress: (d) => ({ current: d.achievement.totalDocs, target: 5 }),
  },
  {
    id: "card-master",
    name: "卡片大师",
    desc: "创建 100 张闪卡",
    icon: Layers,
    color: "text-indigo-500",
    bg: "bg-indigo-500/10",
    check: (d) => d.achievement.totalFlashcards >= 100,
    progress: (d) => ({ current: d.achievement.totalFlashcards, target: 100 }),
  },
];

interface StatsDashboardProps {
  onAction?: (action: "upload" | "flashcard" | "quiz" | "knowledge-graph" | { type: "flashcard-doc"; docId: number }) => void;
}

export function StatsDashboard({ onAction }: StatsDashboardProps) {
  const [data, setData] = useState<LearnerDashboardResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [expandedError, setExpandedError] = useState<number | null>(null);
  const [selectedHeatDay, setSelectedHeatDay] = useState<{ date: string; reviews: number; quizzes: number } | null>(null);
  const [hoveredHeat, setHoveredHeat] = useState<{ date: string; reviews: number; quizzes: number; x: number; y: number } | null>(null);
  const heatGridRef = useRef<HTMLDivElement>(null);
  const justTouched = useRef(false);
  const [dailyGoal, setDailyGoal] = useState(() => getGoal(DAILY_GOAL_KEY, DAILY_GOAL_DEFAULT));
  const [weeklyGoal, setWeeklyGoal] = useState(() => getGoal(WEEKLY_GOAL_KEY, WEEKLY_GOAL_DEFAULT));
  const [monthlyGoal, setMonthlyGoal] = useState(() => getGoal(MONTHLY_GOAL_KEY, MONTHLY_GOAL_DEFAULT));
  const [editingGoal, setEditingGoal] = useState(false);

  const fetchData = useCallback(() => {
    setLoading(true);
    setError(false);
    getLearnerDashboard()
      .then((d) => { setData(d); setError(false); })
      .catch(() => { setError(true); })
      .finally(() => { setLoading(false); });
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center py-16">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          加载中...
        </div>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="flex-1 flex items-center justify-center py-16">
        <div className="text-center space-y-3">
          <FileWarning className="h-8 w-8 text-muted-foreground/40 mx-auto" />
          <p className="text-sm text-muted-foreground">数据暂不可用</p>
          {error && (
            <Button variant="outline" size="sm" className="rounded-xl h-8" onClick={fetchData}>
              <RotateCcw className="h-3.5 w-3.5 mr-1" />
              重试
            </Button>
          )}
        </div>
      </div>
    );
  }

  const { today, rhythm, achievement, dueDocs, topErrors, deckWeaknesses, docProgress, todayTimeline, weeklySummary } = data;
  const totalDue = dueDocs.reduce((s, d) => s + d.dueCount, 0);

  return (
    <div className="flex-1 overflow-y-auto px-4 py-4 space-y-5">

      {/* === 今日目标进度环 + 待办 === */}
      <section>
        <SectionTitle icon={RotateCcw} label="今日待办" />
        <div className="rounded-xl bg-primary/5 border border-primary/20 p-4">
          {/* 进度环 + 统计 */}
          <div className="flex items-center gap-4 mb-3">
            <ProgressRing value={today.reviewedCards} max={dailyGoal} />
            <div className="flex-1 min-w-0">
              <div className="flex items-baseline gap-1.5">
                <span className="text-2xl font-bold text-primary">{today.reviewedCards}</span>
                <span className="text-sm text-primary/60">/ {dailyGoal}</span>
              </div>
              <p className="text-xs text-muted-foreground/60 mt-0.5">
                {today.reviewedCards >= dailyGoal
                  ? "今日目标已完成！"
                  : `还差 ${dailyGoal - today.reviewedCards} 张达成目标`}
              </p>
              <button
                type="button"
                onClick={() => setEditingGoal(!editingGoal)}
                className="flex items-center gap-0.5 mt-1 text-[11px] text-muted-foreground/40 hover:text-muted-foreground transition-colors"
              >
                <Settings2 className="h-2.5 w-2.5" />调整目标
              </button>
            </div>
          </div>

          {/* 待复习文档列表 */}
          {totalDue > 0 ? (
            <div className="space-y-1.5 pt-2 border-t border-primary/10">
              {dueDocs.slice(0, 4).map((doc) => {
                const urgency = doc.dueCount >= 15 ? "destructive" : doc.dueCount >= 8 ? "amber" : "normal";
                return (
                  <button
                    key={doc.docId}
                    type="button"
                    onClick={() => onAction?.({ type: "flashcard-doc", docId: doc.docId })}
                    className="w-full flex items-center justify-between text-xs py-2 px-1 -mx-1 rounded hover:bg-primary/5 transition-colors"
                  >
                    <span className="text-foreground/70 truncate flex-1 mr-2 text-left">{doc.docName}</span>
                    <span className={cn(
                      "shrink-0 font-medium px-1.5 py-0.5 rounded-full text-[11px]",
                      urgency === "destructive" ? "bg-destructive/10 text-destructive" :
                      urgency === "amber" ? "bg-amber-500/10 text-amber-600 dark:text-amber-400" :
                      "bg-primary/10 text-primary",
                    )}>
                      {doc.dueCount} 张
                    </span>
                  </button>
                );
              })}
              {dueDocs.length > 4 && (
                <p className="text-[11px] text-muted-foreground/50 pl-1">
                  还有 {dueDocs.length - 4} 份文档...
                </p>
              )}
            </div>
          ) : (
            <div className="pt-2 border-t border-primary/10">
              <p className="text-xs text-muted-foreground/50 text-center">
                {today.reviewedCards > 0 ? "所有卡片已复习完毕" : "暂无待复习卡片"}
              </p>
            </div>
          )}
        </div>
      </section>

      {/* === 目标设置面板 === */}
      {editingGoal && (
        <section className="animate-in slide-in-from-top-2 duration-200">
          <div className="rounded-xl border border-primary/20 bg-primary/5 p-3.5 space-y-3">
            <p className="text-xs font-medium text-foreground/80">学习目标</p>

            {/* 预设按钮 */}
            <div className="flex gap-1.5">
              {GOAL_PRESETS.map((preset) => (
                <button
                  key={preset.name}
                  type="button"
                  onClick={() => {
                    setDailyGoal(preset.daily);
                    setWeeklyGoal(preset.weekly);
                    setMonthlyGoal(preset.monthly);
                    setGoal(DAILY_GOAL_KEY, preset.daily);
                    setGoal(WEEKLY_GOAL_KEY, preset.weekly);
                    setGoal(MONTHLY_GOAL_KEY, preset.monthly);
                  }}
                  className={cn(
                    "flex-1 rounded-lg border px-2 py-1.5 text-center transition-all",
                    "hover:border-primary/40 hover:bg-primary/10",
                    "border-border/50 bg-card",
                  )}
                >
                  <p className="text-xs font-medium">{preset.name}</p>
                  <p className="text-[11px] text-muted-foreground/50 mt-0.5">{preset.desc}</p>
                </button>
              ))}
            </div>

            {/* 自定义输入 */}
            <div className="grid grid-cols-3 gap-2">
              <GoalInput label="日目标" value={dailyGoal} onChange={(n) => { setDailyGoal(n); setGoal(DAILY_GOAL_KEY, n); }} unit="张/日" />
              <GoalInput label="周目标" value={weeklyGoal} onChange={(n) => { setWeeklyGoal(n); setGoal(WEEKLY_GOAL_KEY, n); }} unit="张/周" />
              <GoalInput label="月目标" value={monthlyGoal} onChange={(n) => { setMonthlyGoal(n); setGoal(MONTHLY_GOAL_KEY, n); }} unit="张/月" />
            </div>
          </div>
        </section>
      )}

      {/* === 今日学习概况 === */}
      <section>
        <SectionTitle icon={Target} label="今日学习" />
        <div className="grid grid-cols-3 gap-2">
          <StatBox
            label="已复习"
            value={String(today.reviewedCards)}
            unit="张"
          />
          <StatBox
            label="做测验"
            value={String(today.quizAttempts)}
            unit="次"
          />
          <StatBox
            label="正确率"
            value={today.totalAnswered > 0 ? `${today.quizAccuracy}%` : "-"}
            unit={today.totalAnswered > 0 ? `${today.correctCount}/${today.totalAnswered}` : ""}
            highlight={today.quizAccuracy >= 80 && today.totalAnswered > 0}
          />
        </div>
      </section>

      {/* === 周度学习报告 === */}
      {weeklySummary && (
        <section>
          <SectionTitle icon={BarChart3} label="本周报告" />
          <div className="rounded-xl border border-border/50 bg-card p-3.5 space-y-3">
            {/* 核心指标 */}
            <div className="grid grid-cols-3 gap-2">
              <TrendStat
                label="复习"
                value={weeklySummary.reviews}
                unit="张"
                prev={weeklySummary.prevReviews}
              />
              <TrendStat
                label="测验"
                value={weeklySummary.quizzes}
                unit="次"
                prev={weeklySummary.prevQuizzes}
              />
              <TrendStat
                label="正确率"
                value={weeklySummary.accuracy}
                unit="%"
                prev={weeklySummary.prevAccuracy}
              />
            </div>

            {/* 活跃天数 + 热门文档 */}
            <div className="flex items-center justify-between text-[11px] text-muted-foreground/60 pt-2 border-t border-border/30">
              <span>活跃 <span className="font-semibold text-foreground/70">{weeklySummary.activeDays}</span>/7 天</span>
              {weeklySummary.topDocName && (
                <span className="truncate ml-2">
                  主攻: <span className="font-semibold text-foreground/70">{weeklySummary.topDocName}</span>
                  {weeklySummary.topDocReviews > 0 && ` (${weeklySummary.topDocReviews}张)`}
                </span>
              )}
            </div>
          </div>
        </section>
      )}

      {/* === 学习节奏 === */}
      <section>
        <SectionTitle icon={Flame} label="学习节奏" />
        <div className="rounded-xl border border-border/50 bg-card p-3.5">
          {/* 连续天数 */}
          <div className="flex items-center gap-3 mb-3">
            <div className={cn(
              "h-10 w-10 rounded-full flex items-center justify-center text-sm font-bold",
              rhythm.streakDays >= 7
                ? "bg-amber-500/15 text-amber-600 dark:text-amber-400"
                : rhythm.streakDays >= 3
                  ? "bg-primary/10 text-primary"
                  : "bg-muted text-muted-foreground",
            )}>
              {rhythm.streakDays}
            </div>
            <div>
              <p className="text-sm font-medium text-foreground">
                {rhythm.streakDays === 0
                  ? "开始今天的学习吧"
                  : rhythm.streakDays >= 7
                    ? "太棒了！坚持一周了"
                    : `连续学习 ${rhythm.streakDays} 天`}
              </p>
              <p className="text-[11px] text-muted-foreground/60">
                {rhythm.streakDays >= 3 ? "保持节奏，你做得很好" : "每天学一点，积少成多"}
              </p>
            </div>
          </div>

          {/* 30 天热力图 */}
          {rhythm.monthly && rhythm.monthly.length > 0 && (() => {
            const monthly = rhythm.monthly;
            const today = new Date();
            const todayStr = `${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
            const firstDate = new Date(today);
            firstDate.setDate(firstDate.getDate() - 29);
            const leadingBlanks = firstDate.getDay();
            const totalRows = Math.ceil((leadingBlanks + 30) / 7);

            const getLevel = (reviews: number, quizzes: number) => {
              const total = reviews + quizzes;
              if (total <= 0) return 0;
              if (total <= 2) return 1;
              if (total <= 5) return 2;
              if (total <= 8) return 3;
              return 4;
            };

            // 月份标签：检测每月第一天所在的行
            const monthLabels: { row: number; label: string }[] = [];
            let lastMonth = -1;
            for (let i = 0; i < 30; i++) {
              const parts = monthly[i].date.split("-");
              const m = parseInt(parts[0], 10);
              if (m >= 1 && m <= 12 && m !== lastMonth) {
                monthLabels.push({ row: Math.floor((i + leadingBlanks) / 7), label: `${m}月` });
                lastMonth = m;
              }
            }

            const gridW = 7 * 18 + 6 * 2; // 138px

            return (
              <div className="overflow-hidden">
                {/* 左侧月份标签 + 右侧(表头+网格) */}
                <div className="flex justify-center">
                  {/* 月份标签列 */}
                  <div
                    className="relative w-8 mr-1.5 shrink-0"
                    style={{ height: 18 + 2 + totalRows * 18 + (totalRows - 1) * 2 }}
                  >
                    {monthLabels.map((m, i) => (
                      <span
                        key={i}
                        className="absolute right-0 text-[11px] text-muted-foreground/50 leading-none select-none whitespace-nowrap flex items-center"
                        style={{ top: 18 + 2 + m.row * 20, height: 18 }}
                      >
                        {m.label}
                      </span>
                    ))}
                  </div>

                  {/* 表头 + 网格共享列宽 */}
                  <div>
                    {/* 星期列头 */}
                    <div className="grid grid-cols-7 gap-[2px] mb-1">
                      {["日", "一", "二", "三", "四", "五", "六"].map((d) => (
                        <span key={d} className="w-[18px] text-[11px] text-muted-foreground/40 text-center leading-none select-none">
                          {d}
                        </span>
                      ))}
                    </div>
                    {/* 热力图网格 */}
                    <div
                      ref={heatGridRef}
                      className="grid grid-cols-7 gap-[2px]"
                    >
                    {Array.from({ length: totalRows * 7 }, (_, i) => {
                      const dayIndex = i - leadingBlanks;
                      if (dayIndex < 0 || dayIndex >= 30) {
                        return <div key={i} className="w-[18px] h-[18px]" />;
                      }
                      const day = monthly[dayIndex];
                      const level = getLevel(day.reviews, day.quizzes);
                      const isSelected = selectedHeatDay?.date === day.date;
                      const isToday = day.date === todayStr;
                      return (
                        <button
                          key={i}
                          type="button"
                          aria-label={`${day.date}: ${day.reviews}张卡片, ${day.quizzes}次测验`}
                          className={cn(
                            "w-[18px] h-[18px] rounded-[3px] cursor-pointer border-0 p-0 transition-all",
                            HEAT_COLORS[level],
                            "hover:brightness-110 hover:scale-110",
                            isSelected && "ring-2 ring-primary ring-offset-1 ring-offset-background",
                            isToday && !isSelected && "ring-2 ring-amber-500 ring-offset-1 ring-offset-background",
                          )}
                          onTouchStart={() => { justTouched.current = true; }}
                          onMouseEnter={(e) => {
                            if (justTouched.current) return;
                            const r = e.currentTarget.getBoundingClientRect();
                            setHoveredHeat({ ...day, x: r.left + r.width / 2, y: r.top });
                          }}
                          onMouseLeave={() => { if (!justTouched.current) setHoveredHeat(null); }}
                          onClick={() => {
                            if (justTouched.current) {
                              justTouched.current = false;
                              setHoveredHeat(null);
                            }
                            setSelectedHeatDay(isSelected ? null : day);
                          }}
                        />
                      );
                    })}
                  </div>
                </div>
                </div>

                {/* 悬浮 Tooltip */}
                {hoveredHeat && (
                  <HeatTooltip
                    date={hoveredHeat.date}
                    reviews={hoveredHeat.reviews}
                    quizzes={hoveredHeat.quizzes}
                    x={hoveredHeat.x}
                    y={hoveredHeat.y}
                  />
                )}

                {/* 图例 + 详情 + 进度条 */}
                <div className="mt-3 pt-2.5 border-t border-border/30 space-y-2" style={{ maxWidth: gridW + 40, marginLeft: "auto", marginRight: "auto" }}>
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] text-muted-foreground/50">
                      30 天: {monthly.reduce((s, d) => s + d.reviews, 0)} 张卡片
                    </span>
                    <div className="flex items-center gap-1">
                      <span className="text-[10px] text-muted-foreground/40 mr-0.5">少</span>
                      {HEAT_COLORS.map((c, i) => (
                        <div key={i} className={cn("w-3 h-3 rounded-[2px]", c)} />
                      ))}
                      <span className="text-[10px] text-muted-foreground/40 ml-0.5">多</span>
                    </div>
                  </div>
                  {selectedHeatDay && (
                    <div className="flex items-center gap-2.5 py-1.5 px-2.5 rounded-lg bg-muted/40">
                      <span className="text-[11px] font-medium text-foreground/80">{selectedHeatDay.date.split("-")[0]}月{selectedHeatDay.date.split("-")[1]}日</span>
                      <span className="text-[11px] text-muted-foreground/60">{selectedHeatDay.reviews} 张卡片</span>
                      <span className="text-[11px] text-muted-foreground/60">{selectedHeatDay.quizzes} 次测验</span>
                      <button type="button" onClick={() => setSelectedHeatDay(null)} className="ml-auto text-[11px] text-muted-foreground/40 hover:text-muted-foreground">关闭</button>
                    </div>
                  )}
                  {(() => {
                    const weeklyTotal = rhythm.weekly.reduce((s, d) => s + d.reviews, 0);
                    const monthlyTotal = monthly.reduce((s, d) => s + d.reviews, 0);
                    return (
                      <div className="space-y-1.5">
                        <MiniProgress label="本周" current={weeklyTotal} goal={weeklyGoal} />
                        <MiniProgress label="本月" current={monthlyTotal} goal={monthlyGoal} />
                      </div>
                    );
                  })()}
                </div>
              </div>
            );
          })() || (
            <div className="py-6 text-center">
              <Calendar className="h-8 w-8 mx-auto mb-2 text-muted-foreground/25" />
              <p className="text-xs text-muted-foreground/50">开始学习后，这里会显示你的学习节奏</p>
            </div>
          )}
        </div>
      </section>

      {/* === 累计成就 === */}
      <section>
        <SectionTitle icon={BookOpen} label="累计成就" />
        <div className="grid grid-cols-2 gap-2">
          <StatBox label="学习材料" value={String(achievement.totalDocs)} unit="份" />
          <StatBox label="闪卡总量" value={String(achievement.totalFlashcards)} unit="张" />
          <StatBox label="累计复习" value={String(achievement.totalReviews)} unit="次" />
          <StatBox label="平均正确率" value={`${achievement.avgAccuracy}%`} unit="" />
        </div>
      </section>

      {/* === 成就徽章 === */}
      <section>
        <SectionTitle icon={Trophy} label="成就徽章" />
        {(() => {
          const earned = BADGES.filter(b => b.check(data));
          const locked = BADGES.filter(b => !b.check(data));
          return (
            <div className="rounded-xl border border-border/50 bg-card p-3 space-y-3">
              {/* 已解锁 */}
              {earned.length > 0 && (
                <div>
                  <p className="text-[11px] text-muted-foreground/50 mb-2">
                    已解锁 {earned.length}/{BADGES.length}
                  </p>
                  <div className="grid grid-cols-4 gap-2">
                    {earned.map((badge) => {
                      const Icon = badge.icon;
                      return (
                        <div
                          key={badge.id}
                          title={`${badge.name}: ${badge.desc}`}
                          className="flex flex-col items-center gap-1 py-2 rounded-lg bg-muted/30 hover:bg-muted/50 transition-colors"
                        >
                          <div className={cn("h-8 w-8 rounded-full flex items-center justify-center", badge.bg)}>
                            <Icon className={cn("h-4 w-4", badge.color)} />
                          </div>
                          <span className="text-[11px] text-foreground/70 text-center leading-tight">{badge.name}</span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
              {/* 未解锁 */}
              {locked.length > 0 && (
                <div>
                  <p className="text-[11px] text-muted-foreground/50 mb-2">待解锁</p>
                  <div className="grid grid-cols-4 gap-2">
                    {locked.map((badge) => {
                      const Icon = badge.icon;
                      const prog = badge.progress?.(data);
                      return (
                        <div
                          key={badge.id}
                          title={`${badge.name}: ${badge.desc}`}
                          className="flex flex-col items-center gap-1 py-2 rounded-lg opacity-50 hover:opacity-70 transition-opacity"
                        >
                          <div className="h-8 w-8 rounded-full flex items-center justify-center bg-muted/40">
                            <Icon className="h-4 w-4 text-muted-foreground/50" />
                          </div>
                          <span className="text-[11px] text-muted-foreground/50 text-center leading-tight">{badge.name}</span>
                          {prog && (
                            <span className="text-[11px] text-muted-foreground/30 tabular-nums">
                              {prog.current}/{prog.target}
                            </span>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          );
        })()}
      </section>

      {/* === 薄弱知识点：错题 Top === */}
      <section>
        <SectionTitle icon={AlertCircle} label="薄弱知识点" />
        {topErrors && topErrors.length > 0 ? (
          <div className="rounded-xl border border-border/50 bg-card divide-y divide-border/30">
            {topErrors.slice(0, 3).map((err) => (
              <div key={err.quizId} className="p-3">
                <button
                  type="button"
                  className="w-full text-left flex items-start gap-2"
                  onClick={() => setExpandedError(expandedError === err.quizId ? null : err.quizId)}
                >
                  <div className="flex-1 min-w-0">
                    <p className="text-xs text-foreground/80 line-clamp-2">{err.question}</p>
                    <div className="flex items-center gap-2 mt-1.5">
                      <span className="text-[11px] text-muted-foreground/50 truncate">{err.docName}</span>
                      <span className="shrink-0 text-[11px] px-1.5 py-0.5 rounded-full bg-destructive/10 text-destructive font-medium">
                        错 {err.errorCount} 次
                      </span>
                    </div>
                  </div>
                  <div className="shrink-0 mt-0.5 text-muted-foreground/40">
                    {expandedError === err.quizId
                      ? <ChevronDown className="h-3.5 w-3.5" />
                      : <ChevronRight className="h-3.5 w-3.5" />}
                  </div>
                </button>
                {expandedError === err.quizId && (
                  <div className="mt-2 pt-2 border-t border-border/30 space-y-1.5">
                    <p className="text-[11px] text-primary font-medium">答案：{err.answer}</p>
                    {err.explanation && (
                      <p className="text-[11px] text-muted-foreground/70">{err.explanation}</p>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        ) : (
          <div className="rounded-xl bg-muted/30 border border-border/50 p-4 text-center">
            <AlertCircle className="h-6 w-6 text-muted-foreground/30 mx-auto mb-1.5" />
            <p className="text-sm text-muted-foreground">暂无错题记录</p>
            <p className="text-[11px] text-muted-foreground/50 mt-1">完成测验后，错题会自动出现在这里</p>
          </div>
        )}
      </section>

      {/* === 薄弱文档：按正确率排列 === */}
      <section>
        <SectionTitle icon={BarChart3} label="文档掌握度" />
        {deckWeaknesses && deckWeaknesses.length > 0 ? (
          <div className="rounded-xl border border-border/50 bg-card p-3 space-y-2.5">
            {deckWeaknesses.slice(0, 5).map((dw) => {
              const color = dw.accuracyRate >= 80
                ? "bg-emerald-500"
                : dw.accuracyRate >= 60
                  ? "bg-amber-500"
                  : "bg-destructive";
              const textColor = dw.accuracyRate >= 80
                ? "text-emerald-600 dark:text-emerald-400"
                : dw.accuracyRate >= 60
                  ? "text-amber-600 dark:text-amber-400"
                  : "text-destructive";
              return (
                <div key={dw.docId}>
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-xs text-foreground/70 truncate flex-1 mr-2">{dw.docName}</span>
                    <span className={cn("text-xs font-semibold tabular-nums", textColor)}>
                      {dw.accuracyRate}%
                    </span>
                  </div>
                  <div className="h-1.5 rounded-full bg-muted/50 overflow-hidden">
                    <div
                      className={cn("h-full rounded-full transition-all duration-500", color)}
                      style={{ width: `${dw.accuracyRate}%` }}
                    />
                  </div>
                  <p className="text-[11px] text-muted-foreground/40 mt-0.5">
                    {dw.correctCount}/{dw.totalQuestions} 题答对
                  </p>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="rounded-xl bg-muted/30 border border-border/50 p-4 text-center">
            <BarChart3 className="h-6 w-6 text-muted-foreground/30 mx-auto mb-1.5" />
            <p className="text-sm text-muted-foreground">暂无测验数据</p>
            <p className="text-[11px] text-muted-foreground/50 mt-1">对文档生成测验并答题后，掌握度会显示在这里</p>
          </div>
        )}
      </section>

      {/* === 文档学习进度 === */}
      {docProgress && docProgress.length > 0 && (
        <section>
          <SectionTitle icon={BookOpen} label="文档学习进度" />
          <div className="rounded-xl border border-border/50 bg-card divide-y divide-border/30">
            {docProgress.filter(d => d.totalCards > 0).slice(0, 5).map((dp) => {
              const cardPct = dp.totalCards > 0 ? Math.round(dp.reviewedCards * 100 / dp.totalCards) : 0;
              const hasQuiz = dp.quizAccuracy >= 0;
              return (
                <div key={dp.docId} className="p-3">
                  <p className="text-xs text-foreground/70 truncate mb-2">{dp.docName}</p>
                  <div className="flex items-center gap-3">
                    {/* 闪卡进度 */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-[11px] text-muted-foreground/50">闪卡复习</span>
                        <span className="text-[11px] text-muted-foreground/60 tabular-nums">
                          {dp.reviewedCards}/{dp.totalCards}
                        </span>
                      </div>
                      <div className="h-1.5 rounded-full bg-muted/50 overflow-hidden">
                        <div
                          className={cn(
                            "h-full rounded-full transition-all duration-500",
                            cardPct >= 80 ? "bg-emerald-500" : cardPct >= 40 ? "bg-primary" : "bg-amber-500",
                          )}
                          style={{ width: `${cardPct}%` }}
                        />
                      </div>
                    </div>
                    {/* 测验正确率 */}
                    {hasQuiz && (
                      <div className="shrink-0 text-right">
                        <p className="text-[11px] text-muted-foreground/50">测验</p>
                        <p className={cn(
                          "text-xs font-semibold tabular-nums",
                          dp.quizAccuracy >= 80 ? "text-emerald-600 dark:text-emerald-400"
                            : dp.quizAccuracy >= 60 ? "text-amber-600 dark:text-amber-400"
                              : "text-destructive",
                        )}>
                          {dp.quizAccuracy}%
                        </p>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
            {docProgress.filter(d => d.totalCards > 0).length === 0 && (
              <div className="p-4 text-center">
                <p className="text-xs text-muted-foreground/50">暂无学习数据</p>
              </div>
            )}
          </div>
        </section>
      )}

      {/* === 今日学习时间线 === */}
      {todayTimeline && todayTimeline.length > 0 && (
        <section>
          <SectionTitle icon={Clock} label="今日时间线" />
          <div className="rounded-xl border border-border/50 bg-card p-3">
            <div className="relative pl-5">
              {/* 竖线 */}
              <div className="absolute left-[7px] top-1.5 bottom-1.5 w-px bg-border/50" />
              <div className="space-y-3">
                {todayTimeline.map((entry, i) => (
                  <div key={i} className="relative">
                    {/* 圆点 */}
                    <div className={cn(
                      "absolute -left-5 top-1 h-3.5 w-3.5 rounded-full border-2 flex items-center justify-center",
                      entry.type === "quiz"
                        ? "border-amber-500 bg-amber-500/15"
                        : "border-primary bg-primary/15",
                    )}>
                      {entry.type === "quiz" ? (
                        <CheckCircle2 className="h-2 w-2 text-amber-600 dark:text-amber-400" />
                      ) : (
                        <BookOpen className="h-2 w-2 text-primary" />
                      )}
                    </div>
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <p className="text-xs text-foreground/80">{entry.description}</p>
                        <p className="text-[11px] text-muted-foreground/50 truncate mt-0.5">{entry.docName}</p>
                      </div>
                      <span className="shrink-0 text-[11px] text-muted-foreground/40 tabular-nums">{entry.time}</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>
      )}

      {/* === 情境化行动卡 === */}
      <section>
        <div className="space-y-2">
          {/* 有待复习卡片时 — 主行动 */}
          {totalDue > 0 && (
            <button
              type="button"
              onClick={() => onAction?.("flashcard")}
              className="w-full flex items-center gap-3 rounded-xl bg-primary text-primary-foreground p-3.5 hover:opacity-90 transition-opacity"
            >
              <div className="h-9 w-9 rounded-lg bg-primary-foreground/15 flex items-center justify-center shrink-0">
                <Layers className="h-4.5 w-4.5" />
              </div>
              <div className="flex-1 text-left min-w-0">
                <p className="text-sm font-medium">复习 {totalDue} 张卡片</p>
                <p className="text-[11px] text-primary-foreground/60">到期卡片需要及时复习</p>
              </div>
              <ChevronRight className="h-4 w-4 text-primary-foreground/40 shrink-0" />
            </button>
          )}

          {/* 有错题时 — 次行动 */}
          {topErrors && topErrors.length > 0 && (
            <button
              type="button"
              onClick={() => onAction?.("quiz")}
              className="w-full flex items-center gap-3 rounded-xl border border-amber-200 dark:border-amber-800 bg-amber-50/50 dark:bg-amber-950/20 p-3.5 hover:bg-amber-50 dark:hover:bg-amber-950/30 transition-colors"
            >
              <div className="h-9 w-9 rounded-lg bg-amber-100 dark:bg-amber-900/30 flex items-center justify-center shrink-0">
                <AlertCircle className="h-4.5 w-4.5 text-amber-600 dark:text-amber-400" />
              </div>
              <div className="flex-1 text-left min-w-0">
                <p className="text-sm font-medium text-amber-800 dark:text-amber-300">重做 {topErrors.length} 道错题</p>
                <p className="text-[11px] text-amber-600/60 dark:text-amber-400/60">巩固薄弱知识点</p>
              </div>
              <ChevronRight className="h-4 w-4 text-amber-400/40 shrink-0" />
            </button>
          )}

          {/* 常驻入口 */}
          <div className="grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => onAction?.("upload")}
              className="flex items-center gap-2.5 rounded-xl border border-border/50 bg-card p-3 hover:bg-muted/50 hover:border-primary/20 transition-all"
            >
              <Upload className="h-4 w-4 text-muted-foreground shrink-0" />
              <span className="text-xs text-muted-foreground">上传文档</span>
            </button>
            <button
              type="button"
              onClick={() => onAction?.("knowledge-graph")}
              className="flex items-center gap-2.5 rounded-xl border border-border/50 bg-card p-3 hover:bg-muted/50 hover:border-primary/20 transition-all"
            >
              <GitFork className="h-4 w-4 text-muted-foreground shrink-0" />
              <span className="text-xs text-muted-foreground">知识图谱</span>
            </button>
          </div>
        </div>
      </section>
    </div>
  );
}

/* ====== 子组件 ====== */

function SectionTitle({ icon: Icon, label }: { icon: React.ComponentType<{ className?: string }>; label: string }) {
  return (
    <div className="flex items-center gap-1.5 mb-2.5">
      <Icon className="h-3.5 w-3.5 text-primary/70" />
      <span className="text-xs font-semibold text-foreground/80">{label}</span>
    </div>
  );
}

function StatBox({
  label,
  value,
  unit,
  highlight = false,
}: {
  label: string;
  value: string;
  unit?: string;
  highlight?: boolean;
}) {
  return (
    <div className={cn(
      "rounded-lg border border-border/50 p-2.5 text-center",
      highlight ? "bg-primary/5 border-primary/20" : "bg-card",
    )}>
      <p className="text-[11px] text-muted-foreground/60 mb-1">{label}</p>
      <p className={cn(
        "text-lg font-bold tracking-tight",
        highlight ? "text-primary" : "text-foreground",
      )}>
        {value}
      </p>
      {unit && <p className="text-[11px] text-muted-foreground/40">{unit}</p>}
    </div>
  );
}


function ProgressRing({ value, max, size = 72, strokeWidth = 6 }: { value: number; max: number; size?: number; strokeWidth?: number }) {
  const pct = max > 0 ? Math.min(value / max, 1) : 0;
  const r = (size - strokeWidth) / 2;
  const circ = 2 * Math.PI * r;
  const offset = circ * (1 - pct);
  const color = pct >= 1 ? "text-emerald-500" : pct >= 0.5 ? "text-primary" : "text-amber-500";
  return (
    <div className="relative" style={{ width: size, height: size }} role="img" aria-label={`今日复习进度 ${Math.round(pct * 100)}%`}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={strokeWidth}
          className="stroke-muted/30" />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={strokeWidth}
          strokeDasharray={circ} strokeDashoffset={offset} strokeLinecap="round"
          className={cn("transition-all duration-700 ease-out", color)} />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className={cn("text-lg font-bold leading-none", color)}>{Math.round(pct * 100)}%</span>
      </div>
    </div>
  );
}

function GoalInput({ label, value, onChange, unit }: { label: string; value: number; onChange: (n: number) => void; unit: string }) {
  const [input, setInput] = useState("");
  const [editing, setEditing] = useState(false);
  return (
    <div>
      <p className="text-[11px] text-muted-foreground/60 mb-1">{label}</p>
      {editing ? (
        <div className="flex items-center gap-0.5">
          <input
            type="number"
            min={1}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") { const n = parseInt(input, 10); if (n > 0) onChange(n); setEditing(false); }
              else if (e.key === "Escape") setEditing(false);
            }}
            className="w-full h-7 rounded border border-border/50 bg-background px-1.5 text-xs text-center outline-none focus:border-primary/50"
            autoFocus
          />
        </div>
      ) : (
        <button
          type="button"
          onClick={() => { setInput(String(value)); setEditing(true); }}
          className="w-full h-7 rounded border border-border/50 bg-card px-1.5 text-xs text-center hover:border-primary/30 transition-colors"
        >
          {value} <span className="text-[11px] text-muted-foreground/40">{unit}</span>
        </button>
      )}
    </div>
  );
}

const WEEKDAY_NAMES = ["日", "一", "二", "三", "四", "五", "六"];

function HeatTooltip({ date, reviews, quizzes, x, y }: {
  date: string; reviews: number; quizzes: number; x: number; y: number;
}) {
  // date 格式 "MM-DD"，推算星期几
  const [mm, dd] = date.split("-").map(Number);
  const year = new Date().getFullYear();
  const weekday = WEEKDAY_NAMES[new Date(year, mm - 1, dd).getDay()];

  return (
    <div
      className="fixed z-50 pointer-events-none"
      style={{ left: x, top: y - 8, transform: "translate(-50%, -100%)" }}
    >
      <div className="rounded-lg border border-border bg-popover px-2.5 py-1.5 shadow-lg text-[11px] leading-relaxed whitespace-nowrap">
        <div className="font-medium text-foreground">{mm}月{dd}日 周{weekday}</div>
        <div className="text-muted-foreground/70">
          {reviews > 0 && `${reviews} 张闪卡`}
          {reviews > 0 && quizzes > 0 && " · "}
          {quizzes > 0 && `${quizzes} 次测验`}
          {reviews === 0 && quizzes === 0 && "暂无学习"}
        </div>
      </div>
    </div>
  );
}

const HEAT_COLORS = [
  "bg-stone-100 dark:bg-zinc-800",   // 0
  "bg-amber-200 dark:bg-amber-900",  // 1-2
  "bg-amber-300 dark:bg-amber-700",  // 3-5
  "bg-amber-400 dark:bg-amber-600",  // 6-8
  "bg-amber-500 dark:bg-amber-500",  // 9+
];

function MiniProgress({ label, current, goal }: { label: string; current: number; goal: number }) {
  const pct = goal > 0 ? Math.min(current / goal, 1) : 0;
  const done = current >= goal;
  return (
    <div className="flex items-center gap-2">
      <span className="text-[11px] text-muted-foreground/50 w-7 shrink-0">{label}</span>
      <div className="flex-1 h-1.5 rounded-full bg-muted/40 overflow-hidden">
        <div
          className={cn(
            "h-full rounded-full transition-all duration-500",
            done ? "bg-emerald-500" : pct >= 0.5 ? "bg-primary" : "bg-amber-500",
          )}
          style={{ width: `${pct * 100}%` }}
        />
      </div>
      <span className="text-[11px] tabular-nums text-muted-foreground/50 shrink-0">
        {current}/{goal}
      </span>
    </div>
  );
}

function TrendStat({ label, value, unit, prev }: { label: string; value: number; unit: string; prev: number }) {
  const diff = value - prev;
  const pctChange = prev > 0 ? Math.round((diff / prev) * 100) : value > 0 ? 100 : 0;
  const trend = diff > 0 ? "up" : diff < 0 ? "down" : "flat";
  const TrendIcon = trend === "up" ? TrendingUp : trend === "down" ? TrendingDown : Minus;
  const trendColor = trend === "up" ? "text-emerald-500" : trend === "down" ? "text-destructive" : "text-muted-foreground/40";
  return (
    <div className="rounded-lg bg-muted/30 p-2 text-center">
      <p className="text-[11px] text-muted-foreground/60 mb-1">{label}</p>
      <p className="text-lg font-bold tabular-nums leading-none">
        {typeof value === "number" && value % 1 !== 0 ? value.toFixed(1) : value}
        <span className="text-[11px] text-muted-foreground/40 ml-0.5">{unit}</span>
      </p>
      {prev > 0 ? (
        <div className={cn("flex items-center justify-center gap-0.5 mt-1", trendColor)}>
          <TrendIcon className="h-2.5 w-2.5" />
          <span className="text-[11px] tabular-nums">
            {trend === "flat" ? "持平" : `${trend === "up" ? "+" : ""}${pctChange}%`}
          </span>
        </div>
      ) : value > 0 ? (
        <div className="flex items-center justify-center gap-0.5 mt-1 text-primary">
          <span className="text-[11px] font-semibold px-1.5 py-0.5 rounded-full bg-primary/10">新</span>
        </div>
      ) : null}
    </div>
  );
}
