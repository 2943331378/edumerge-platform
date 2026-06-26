"use client";

import { useState } from "react";
import Link from "next/link";
import { cn } from "@/lib/utils";
import { BrandMark } from "@/components/BrandMark";
import { ThemeToggle } from "@/components/theme-toggle";
import {
  demoOutline, demoNote, demoMindMap, demoFlashcards, demoQuizzes, demoFlowNotes,
} from "@/data/demo-data";
import {
  Upload, NotebookText, GitFork, Layers, HelpCircle, BookOpen,
  ChevronLeft, ChevronRight, Check, RotateCcw, ArrowRight, ArrowLeft,
  Sparkles, X, FileText, Lightbulb, HelpCircleIcon, Link2, ListTodo,
  Lock, Play,
} from "lucide-react";

/* ─── Step definitions ─── */
const STEPS = [
  { id: 1, label: "文档大纲", icon: Upload, color: "text-primary", bg: "bg-primary/10" },
  { id: 2, label: "生成笔记", icon: NotebookText, color: "text-purple-500", bg: "bg-purple-500/10" },
  { id: 3, label: "思维导图", icon: GitFork, color: "text-primary", bg: "bg-primary/10" },
  { id: 4, label: "练卡片",   icon: Layers, color: "text-amber-500", bg: "bg-amber-500/10" },
  { id: 5, label: "做测验",   icon: HelpCircle, color: "text-rose-500", bg: "bg-rose-500/10" },
  { id: 6, label: "学习日志", icon: BookOpen, color: "text-amber-500", bg: "bg-amber-500/10" },
];

const STEP_TIPS: Record<number, string> = {
  1: "这是 AI 从文档中自动提取的章节结构，勾选章节可以指定 AI 生成内容的范围。",
  2: "AI 基于文档内容生成的结构化学习笔记，包含核心概念、公式和案例分析。",
  3: "思维导图帮你可视化知识脉络，节点可拖拽交互，支持导出为 PNG 图片。",
  4: "AI 生成的正反面闪卡，基于 SM-2 遗忘曲线算法安排最佳复习时间。",
  5: "自动出题检验你的掌握程度，错题会自动收录到全局错题本中。",
  6: "学习日志记录你的思考和洞察，AI 还能从对话中自动提取学习要点。",
};

/* ═══════════════════════════════════════════
   Demo Banner
   ═══════════════════════════════════════════ */
function DemoBanner() {
  return (
    <div className="bg-gradient-to-r from-primary/10 via-primary/5 to-primary/10 border-b border-primary/20 px-4 py-2.5 flex items-center justify-between gap-3 shrink-0">
      <div className="flex items-center gap-2 min-w-0">
        <Play className="h-3.5 w-3.5 text-primary shrink-0" />
        <p className="text-xs sm:text-[13px] text-foreground/80 truncate">
          <span className="font-semibold text-primary">演示模式</span>
          <span className="hidden sm:inline"> — 你正在体验「贝叶斯定理」的学习流程，注册后可上传自己的文档</span>
        </p>
      </div>
      <Link
        href="/register"
        className="inline-flex items-center gap-1.5 shrink-0 rounded-lg bg-primary px-3 py-1.5 text-[11px] sm:text-xs font-medium text-primary-foreground shadow-sm hover:shadow-md hover:opacity-90 transition-all"
      >
        <Lock className="h-3 w-3" />
        注册解锁
      </Link>
    </div>
  );
}

/* ═══════════════════════════════════════════
   Step 1: 文档大纲
   ═══════════════════════════════════════════ */
function DemoOutline() {
  const [expanded, setExpanded] = useState<Set<string>>(new Set(["s1", "s2", "s3", "s4"]));
  const toggle = (id: string) => setExpanded((prev) => {
    const next = new Set(prev);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  });

  return (
    <div className="flex-1 overflow-auto p-4 md:p-6">
      <div className="max-w-2xl mx-auto space-y-4">
        <div className="flex items-center gap-3 mb-6">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
            <FileText className="h-5 w-5 text-primary" />
          </div>
          <div>
            <h2 className="text-base font-semibold">{demoOutline.title}</h2>
            <p className="text-xs text-muted-foreground">AI 自动提取的文档结构 · {demoOutline.sections.length} 个主章节</p>
          </div>
        </div>

        <div className="space-y-1">
          {demoOutline.sections.map((section) => (
            <div key={section.id}>
              <button
                type="button"
                onClick={() => toggle(section.id)}
                className={cn(
                  "w-full flex items-center gap-2 rounded-lg px-3 py-2.5 text-left transition-all",
                  "hover:bg-muted/60 group",
                )}
              >
                <ChevronRight className={cn(
                  "h-3.5 w-3.5 text-muted-foreground/50 transition-transform",
                  expanded.has(section.id) && "rotate-90",
                )} />
                <span className="text-sm font-medium">{section.title}</span>
              </button>
              {expanded.has(section.id) && section.children && (
                <div className="ml-6 border-l border-border/50 pl-3 space-y-0.5">
                  {section.children.map((child) => (
                    <div
                      key={child.id}
                      className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm text-muted-foreground hover:text-foreground hover:bg-muted/40 transition-all cursor-default"
                    >
                      <div className="h-1 w-1 rounded-full bg-muted-foreground/30" />
                      {child.title}
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   Step 2: 学习笔记
   ═══════════════════════════════════════════ */
function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function DemoNote() {
  // Simple markdown-like rendering
  const lines = demoNote.content.split("\n");

  const renderLine = (line: string, i: number) => {
    if (line.startsWith("# ")) return <h1 key={i} className="text-xl font-bold mt-6 mb-3">{line.slice(2)}</h1>;
    if (line.startsWith("## ")) return <h2 key={i} className="text-base font-semibold mt-5 mb-2 text-primary">{line.slice(3)}</h2>;
    if (line.startsWith("### ")) return <h3 key={i} className="text-sm font-semibold mt-4 mb-1.5">{line.slice(4)}</h3>;
    if (line.startsWith("```")) return null; // skip code fences
    if (line.startsWith("> ")) return (
      <blockquote key={i} className="border-l-2 border-primary/40 pl-3 py-1 my-2 text-sm text-muted-foreground italic bg-primary/5 rounded-r-lg">
        {line.slice(2)}
      </blockquote>
    );
    if (line.startsWith("- ")) {
      const content = line.slice(2);
      return (
        <div key={i} className="flex gap-2 ml-2 my-0.5">
          <span className="text-primary mt-1.5 text-xs">●</span>
          <span className="text-sm" dangerouslySetInnerHTML={{ __html: escapeHtml(content).replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>').replace(/`(.*?)`/g, '<code class="bg-muted px-1 rounded text-xs">$1</code>') }} />
        </div>
      );
    }
    if (line.match(/^\d+\.\s/)) {
      const content = line.replace(/^\d+\.\s/, "");
      const num = line.match(/^(\d+)\./)?.[1];
      return (
        <div key={i} className="flex gap-2 ml-2 my-0.5">
          <span className="text-primary text-xs font-mono mt-0.5 min-w-[1rem]">{num}.</span>
          <span className="text-sm" dangerouslySetInnerHTML={{ __html: escapeHtml(content).replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>') }} />
        </div>
      );
    }
    if (line.startsWith("|")) {
      // Simple table row
      const cells = line.split("|").filter(Boolean).map((c) => c.trim());
      if (cells.every((c) => c.match(/^[-]+$/))) return null; // separator
      return (
        <div key={i} className="grid grid-cols-2 gap-2 my-0.5 text-sm border-b border-border/30 py-1.5">
          {cells.map((c, j) => (
            <span key={j} className={cn(j === 0 ? "font-medium" : "text-muted-foreground")}>
              <span dangerouslySetInnerHTML={{ __html: escapeHtml(c).replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>') }} />
            </span>
          ))}
        </div>
      );
    }
    if (line.trim() === "") return <div key={i} className="h-2" />;
    return (
      <p key={i} className="text-sm leading-relaxed my-1" dangerouslySetInnerHTML={{
        __html: escapeHtml(line)
          .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
          .replace(/`(.*?)`/g, '<code class="bg-muted px-1 rounded text-xs">$1</code>'),
      }} />
    );
  };

  return (
    <div className="flex-1 overflow-auto p-4 md:p-6">
      <div className="max-w-2xl mx-auto">
        <div className="flex items-center gap-3 mb-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-purple-500/10">
            <NotebookText className="h-5 w-5 text-purple-500" />
          </div>
          <div>
            <h2 className="text-base font-semibold">{demoNote.title}</h2>
            <p className="text-xs text-muted-foreground">AI 自动生成的学习笔记</p>
          </div>
        </div>
        <div className="rounded-xl border border-border/50 bg-card/50 p-4 md:p-6">
          {lines.map(renderLine)}
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   Step 3: 思维导图 (CSS Tree)
   ═══════════════════════════════════════════ */
function MindMapNode({ node, depth = 0 }: { node: { name: string; children?: any[] }; depth?: number }) {
  const bgColors = ["bg-primary/10", "bg-amber-500/10", "bg-rose-500/10", "bg-orange-500/10"];
  const textColors = ["text-primary", "text-amber-600 dark:text-amber-400", "text-rose-600 dark:text-rose-400", "text-orange-600 dark:text-orange-400"];
  const colorIdx = depth % 4;

  return (
    <div className={cn("flex flex-col", depth > 0 && "ml-4 sm:ml-6 mt-1")}>
      <div className={cn(
        "inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs sm:text-sm font-medium w-fit",
        depth === 0
          ? "bg-primary/10 border-primary/30 text-primary font-semibold text-sm sm:text-base px-3 py-2"
          : cn(bgColors[colorIdx], "border-current/10", textColors[colorIdx]),
      )}>
        {depth === 0 && <Sparkles className="h-3.5 w-3.5" />}
        {node.name}
      </div>
      {node.children && node.children.length > 0 && (
        <div className="relative">
          <div className="absolute left-3 top-0 bottom-0 w-px bg-border/50" />
          {node.children.map((child, i) => (
            <MindMapNode key={i} node={child} depth={depth + 1} />
          ))}
        </div>
      )}
    </div>
  );
}

function DemoMindMap() {
  return (
    <div className="flex-1 overflow-auto p-4 md:p-6">
      <div className="max-w-3xl mx-auto">
        <div className="flex items-center gap-3 mb-6">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
            <GitFork className="h-5 w-5 text-primary" />
          </div>
          <div>
            <h2 className="text-base font-semibold">知识结构 · 思维导图</h2>
            <p className="text-xs text-muted-foreground">AI 提取的知识脉络可视化</p>
          </div>
        </div>
        <div className="rounded-xl border border-border/50 bg-card/50 p-4 md:p-6 overflow-x-auto">
          <MindMapNode node={demoMindMap} />
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   Step 4: 闪卡
   ═══════════════════════════════════════════ */
function DemoFlashcards() {
  const [current, setCurrent] = useState(0);
  const [flipped, setFlipped] = useState(false);
  const card = demoFlashcards[current];

  const next = () => { setCurrent((v) => Math.min(v + 1, demoFlashcards.length - 1)); setFlipped(false); };
  const prev = () => { setCurrent((v) => Math.max(v - 1, 0)); setFlipped(false); };

  return (
    <div className="flex-1 overflow-auto p-4 md:p-6">
      <div className="max-w-xl mx-auto space-y-5">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-amber-500/10">
            <Layers className="h-5 w-5 text-amber-500" />
          </div>
          <div>
            <h2 className="text-base font-semibold">闪卡练习</h2>
            <p className="text-xs text-muted-foreground">共 {demoFlashcards.length} 张卡片 · 点击卡片翻转</p>
          </div>
        </div>

        {/* Card */}
        <div
          className="relative cursor-pointer perspective-1000"
          onClick={() => setFlipped((v) => !v)}
          style={{ perspective: "1000px" }}
        >
          <div className={cn(
            "rounded-2xl border-2 p-6 md:p-8 min-h-[200px] flex flex-col justify-center transition-all duration-500",
            flipped
              ? "bg-primary/5 border-primary/30"
              : "bg-card border-border/50 hover:border-primary/20",
          )} style={{
            transform: flipped ? "rotateY(2deg)" : "none",
            transformStyle: "preserve-3d",
          }}>
            <div className="flex items-center gap-2 mb-4">
              <span className={cn(
                "inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11px] font-medium",
                flipped ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground",
              )}>
                {flipped ? "答案" : "问题"}
              </span>
              <span className="text-[11px] text-muted-foreground/50">
                {current + 1} / {demoFlashcards.length}
              </span>
            </div>
            <p className="text-sm md:text-base leading-relaxed whitespace-pre-wrap">
              {flipped ? card.answer : card.question}
            </p>
            {!flipped && (
              <p className="mt-4 text-[11px] text-muted-foreground/40">点击翻转查看答案</p>
            )}
          </div>
        </div>

        {/* Navigation */}
        <div className="flex items-center justify-between">
          <button
            type="button"
            onClick={prev}
            disabled={current === 0}
            className="flex items-center gap-1.5 rounded-lg px-3 py-2 text-xs text-muted-foreground hover:text-foreground hover:bg-muted disabled:opacity-30 disabled:cursor-not-allowed transition-all"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            上一张
          </button>
          <div className="flex items-center gap-1">
            {demoFlashcards.map((_, i) => (
              <div key={i} className={cn(
                "h-1.5 rounded-full transition-all",
                i === current ? "w-6 bg-primary" : "w-1.5 bg-muted-foreground/20",
              )} />
            ))}
          </div>
          <button
            type="button"
            onClick={next}
            disabled={current === demoFlashcards.length - 1}
            className="flex items-center gap-1.5 rounded-lg px-3 py-2 text-xs text-muted-foreground hover:text-foreground hover:bg-muted disabled:opacity-30 disabled:cursor-not-allowed transition-all"
          >
            下一张
            <ArrowRight className="h-3.5 w-3.5" />
          </button>
        </div>

        {/* Self-assessment hint */}
        <div className="text-center">
          <p className="text-[11px] text-muted-foreground/40">
            注册后可使用 SM-2 间隔重复算法，AI 根据你的记忆情况智能安排复习时间
          </p>
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   Step 5: 测验
   ═══════════════════════════════════════════ */
function DemoQuiz() {
  const [current, setCurrent] = useState(0);
  const [selected, setSelected] = useState<number | null>(null);
  const [showResult, setShowResult] = useState(false);
  const [score, setScore] = useState(0);
  const [finished, setFinished] = useState(false);
  const [answers, setAnswers] = useState<(number | null)[]>(new Array(demoQuizzes.length).fill(null));

  const quiz = demoQuizzes[current];
  const isCorrect = selected === quiz.answer;

  const handleSelect = (idx: number) => {
    if (showResult) return;
    setSelected(idx);
    setShowResult(true);
    const newAnswers = [...answers];
    newAnswers[current] = idx;
    setAnswers(newAnswers);
    if (idx === quiz.answer) setScore((v) => v + 1);
  };

  const handleNext = () => {
    if (current < demoQuizzes.length - 1) {
      setCurrent((v) => v + 1);
      setSelected(answers[current + 1]);
      setShowResult(answers[current + 1] !== null);
    } else {
      setFinished(true);
    }
  };

  const handleRetry = () => {
    setCurrent(0);
    setSelected(null);
    setShowResult(false);
    setScore(0);
    setFinished(false);
    setAnswers(new Array(demoQuizzes.length).fill(null));
  };

  if (finished) {
    const pct = Math.round((score / demoQuizzes.length) * 100);
    return (
      <div className="flex-1 overflow-auto p-4 md:p-6">
        <div className="max-w-xl mx-auto space-y-6">
          <div className="text-center space-y-3">
            <div className={cn(
              "inline-flex h-16 w-16 items-center justify-center rounded-full mx-auto",
              pct >= 80 ? "bg-primary/10" : pct >= 60 ? "bg-amber-500/10" : "bg-rose-500/10",
            )}>
              <span className="text-2xl font-bold">{pct}%</span>
            </div>
            <h2 className="text-lg font-semibold">
              {pct >= 80 ? "🎉 太棒了！" : pct >= 60 ? "👍 不错！" : "💪 继续加油！"}
            </h2>
            <p className="text-sm text-muted-foreground">
              答对 {score} / {demoQuizzes.length} 题
            </p>
          </div>

          {/* Wrong answers review */}
          {answers.some((a, i) => a !== demoQuizzes[i].answer) && (
            <div className="space-y-3">
              <h3 className="text-sm font-semibold text-rose-500">错题回顾</h3>
              {demoQuizzes.map((q, i) => {
                if (answers[i] === q.answer) return null;
                return (
                  <div key={q.id} className="rounded-xl border border-rose-500/20 bg-rose-500/5 p-4 space-y-2">
                    <p className="text-sm font-medium">{q.question}</p>
                    <p className="text-xs text-muted-foreground">
                      你的答案：<span className="text-rose-500">{q.options[answers[i]!]}</span>
                    </p>
                    <p className="text-xs text-muted-foreground">
                      正确答案：<span className="text-primary">{q.options[q.answer]}</span>
                    </p>
                    <p className="text-xs text-muted-foreground">{q.explanation}</p>
                  </div>
                );
              })}
            </div>
          )}

          <div className="flex justify-center gap-3">
            <button
              type="button"
              onClick={handleRetry}
              className="flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm border border-border hover:bg-muted transition-all"
            >
              <RotateCcw className="h-3.5 w-3.5" />
              再做一次
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-auto p-4 md:p-6">
      <div className="max-w-xl mx-auto space-y-5">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-rose-500/10">
            <HelpCircle className="h-5 w-5 text-rose-500" />
          </div>
          <div>
            <h2 className="text-base font-semibold">知识测验</h2>
            <p className="text-xs text-muted-foreground">共 {demoQuizzes.length} 题 · 当前第 {current + 1} 题</p>
          </div>
        </div>

        {/* Progress */}
        <div className="flex gap-1">
          {demoQuizzes.map((_, i) => (
            <div key={i} className={cn(
              "h-1 flex-1 rounded-full transition-all",
              i < current ? "bg-primary" : i === current ? "bg-primary/50" : "bg-muted",
            )} />
          ))}
        </div>

        {/* Question */}
        <div className="rounded-xl border border-border/50 bg-card/50 p-5 space-y-4">
          <p className="text-sm font-medium leading-relaxed">{quiz.question}</p>

          <div className="space-y-2">
            {quiz.options.map((opt, idx) => {
              const isSelected = selected === idx;
              const isAnswer = idx === quiz.answer;
              return (
                <button
                  key={idx}
                  type="button"
                  onClick={() => handleSelect(idx)}
                  disabled={showResult}
                  className={cn(
                    "w-full flex items-center gap-3 rounded-lg border px-4 py-3 text-left text-sm transition-all",
                    !showResult && "hover:border-primary/30 hover:bg-primary/5 cursor-pointer",
                    showResult && isAnswer && "border-primary/50 bg-primary/10 text-primary dark:text-primary",
                    showResult && isSelected && !isAnswer && "border-rose-500/50 bg-rose-500/10 text-rose-700 dark:text-rose-400",
                    showResult && !isSelected && !isAnswer && "opacity-50",
                    !showResult && !isSelected && "border-border/50",
                  )}
                >
                  <span className={cn(
                    "flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-xs font-medium",
                    showResult && isAnswer && "border-primary bg-primary text-white",
                    showResult && isSelected && !isAnswer && "border-rose-500 bg-rose-500 text-white",
                    !showResult && "border-muted-foreground/30",
                  )}>
                    {showResult && isAnswer ? <Check className="h-3 w-3" /> :
                     showResult && isSelected && !isAnswer ? <X className="h-3 w-3" /> :
                     String.fromCharCode(65 + idx)}
                  </span>
                  <span>{opt}</span>
                </button>
              );
            })}
          </div>

          {/* Explanation */}
          {showResult && (
            <div className="rounded-lg bg-muted/30 p-3 space-y-2 animate-in fade-in duration-300">
              <p className="text-xs font-medium text-primary">
                {isCorrect ? "✅ 回答正确！" : "❌ 回答错误"}
              </p>
              <p className="text-xs text-muted-foreground leading-relaxed">{quiz.explanation}</p>
            </div>
          )}
        </div>

        {/* Navigation */}
        {showResult && (
          <div className="flex justify-end">
            <button
              type="button"
              onClick={handleNext}
              className="flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 transition-all"
            >
              {current < demoQuizzes.length - 1 ? "下一题" : "查看成绩"}
              <ArrowRight className="h-3.5 w-3.5" />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   Step 6: 学习日志
   ═══════════════════════════════════════════ */
const CATEGORY_MAP: Record<string, { label: string; icon: any; color: string; bg: string }> = {
  insight: { label: "洞察", icon: Lightbulb, color: "text-amber-500", bg: "bg-amber-500/10" },
  question: { label: "疑问", icon: HelpCircleIcon, color: "text-primary", bg: "bg-primary/10" },
  connection: { label: "关联", icon: Link2, color: "text-primary", bg: "bg-primary/10" },
  todo: { label: "待办", icon: ListTodo, color: "text-purple-500", bg: "bg-purple-500/10" },
};

function DemoFlowNote() {
  const [filter, setFilter] = useState<string | null>(null);
  const filtered = filter ? demoFlowNotes.filter((n) => n.category === filter) : demoFlowNotes;

  return (
    <div className="flex-1 overflow-auto p-4 md:p-6">
      <div className="max-w-2xl mx-auto space-y-4">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-amber-500/10">
            <BookOpen className="h-5 w-5 text-amber-500" />
          </div>
          <div>
            <h2 className="text-base font-semibold">学习日志</h2>
            <p className="text-xs text-muted-foreground">记录学习过程中的思考与洞察</p>
          </div>
        </div>

        {/* Category filter */}
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => setFilter(null)}
            className={cn(
              "rounded-full px-3 py-1 text-xs font-medium transition-all",
              !filter ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground hover:text-foreground",
            )}
          >
            全部
          </button>
          {Object.entries(CATEGORY_MAP).map(([key, cat]) => (
            <button
              key={key}
              type="button"
              onClick={() => setFilter(key)}
              className={cn(
                "inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-medium transition-all",
                filter === key ? cn(cat.bg, cat.color) : "bg-muted text-muted-foreground hover:text-foreground",
              )}
            >
              <cat.icon className="h-3 w-3" />
              {cat.label}
            </button>
          ))}
        </div>

        {/* Notes list */}
        <div className="space-y-3">
          {filtered.map((note) => {
            const cat = CATEGORY_MAP[note.category];
            return (
              <div
                key={note.id}
                className="rounded-xl border border-border/50 bg-card/50 p-4 space-y-2 hover:border-primary/20 transition-all"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className={cn("inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium", cat.bg, cat.color)}>
                      <cat.icon className="h-2.5 w-2.5" />
                      {cat.label}
                    </span>
                    {note.reviewed && (
                      <span className="text-[11px] text-primary">已复习</span>
                    )}
                  </div>
                  <span className="text-[11px] text-muted-foreground/50">{note.createdAt}</span>
                </div>
                <p className="text-sm leading-relaxed text-foreground/90">{note.content}</p>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   Main Demo Page
   ═══════════════════════════════════════════ */
export default function DemoPage() {
  const [currentStep, setCurrentStep] = useState(1);

  const renderStep = () => {
    switch (currentStep) {
      case 1: return <DemoOutline />;
      case 2: return <DemoNote />;
      case 3: return <DemoMindMap />;
      case 4: return <DemoFlashcards />;
      case 5: return <DemoQuiz />;
      case 6: return <DemoFlowNote />;
      default: return null;
    }
  };

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <DemoBanner />

      {/* Header */}
      <header className="flex items-center justify-between px-4 py-2 border-b border-border/50 bg-background/50 backdrop-blur shrink-0">
        <div className="flex items-center gap-2.5">
          <Link href="/landing" className="hover:opacity-80 transition-opacity">
            <BrandMark variant="compact" />
          </Link>
          <span className="text-xs text-muted-foreground/50">·</span>
          <span className="text-xs text-muted-foreground">贝叶斯定理与概率推理</span>
        </div>
        <div className="flex items-center gap-2">
          <ThemeToggle />
          <Link
            href="/register"
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:opacity-90 transition-all"
          >
            注册
          </Link>
          <Link
            href="/login"
            className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs font-medium hover:bg-muted transition-all"
          >
            登录
          </Link>
        </div>
      </header>

      {/* Step Navigation */}
      <nav className="flex items-center justify-center gap-0 px-2 md:px-6 py-3 overflow-x-auto shrink-0 border-b border-border/30">
        {STEPS.map((step, idx) => {
          const isActive = currentStep === step.id;
          return (
            <div key={step.id} className="flex items-center shrink-0">
              <button
                type="button"
                onClick={() => setCurrentStep(step.id)}
                className={cn(
                  "flex flex-col items-center gap-1 group transition-all px-1.5 sm:px-2",
                  "cursor-pointer",
                )}
              >
                <div className={cn(
                  "flex h-8 w-8 md:h-10 md:w-10 items-center justify-center rounded-full border-2 transition-all duration-300",
                  isActive ? cn("border-current", step.bg, step.color, "shadow-lg shadow-current/10") : "border-muted-foreground/20 bg-transparent",
                )}>
                  <step.icon className={cn(
                    "h-3.5 w-3.5 md:h-4 md:w-4 transition-colors",
                    isActive ? step.color : "text-muted-foreground/40",
                  )} />
                </div>
                <span className={cn(
                  "text-[11px] font-medium transition-colors whitespace-nowrap",
                  isActive ? step.color : "text-muted-foreground/40",
                )}>
                  {step.label}
                </span>
              </button>
              {idx < STEPS.length - 1 && (
                <div className="h-0.5 w-4 sm:w-8 md:w-12 mx-0.5 md:mx-1 rounded-full bg-muted-foreground/10" />
              )}
            </div>
          );
        })}
      </nav>

      {/* Step Tip */}
      <div className="px-4 py-2 bg-primary/5 border-b border-primary/10 shrink-0">
        <div className="max-w-2xl mx-auto flex items-start gap-2">
          <Sparkles className="h-3.5 w-3.5 text-primary shrink-0 mt-0.5" />
          <p className="text-xs text-muted-foreground leading-relaxed">{STEP_TIPS[currentStep]}</p>
        </div>
      </div>

      {/* Step Content */}
      <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
        {renderStep()}
      </div>

      {/* Bottom Navigation */}
      <div className="flex items-center justify-between px-4 md:px-6 py-2 border-t border-border/50 bg-background/50 shrink-0">
        <button
          type="button"
          onClick={() => setCurrentStep((v) => Math.max(1, v - 1))}
          disabled={currentStep <= 1}
          className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs text-muted-foreground hover:text-foreground hover:bg-muted disabled:opacity-30 disabled:cursor-not-allowed transition-all"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          上一步
        </button>
        <span className="text-[11px] text-muted-foreground/50">{currentStep} / {STEPS.length}</span>
        <button
          type="button"
          onClick={() => setCurrentStep((v) => Math.min(STEPS.length, v + 1))}
          disabled={currentStep >= STEPS.length}
          className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs text-muted-foreground hover:text-foreground hover:bg-muted disabled:opacity-30 disabled:cursor-not-allowed transition-all"
        >
          下一步
          <ChevronRight className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}
