"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { cn } from "@/lib/utils";
import { BrandMark } from "@/components/BrandMark";
import { ThemeToggle } from "@/components/theme-toggle";
import {
  FileText, NotebookText, GitFork, Layers, HelpCircle, BookOpen,
  ArrowRight, Sparkles, Shield, Zap, Eye, ChevronRight, Play,
  Menu, X,
} from "lucide-react";

/* ─── 动态打字效果 ─── */
function TypeWriter({ texts, className }: { texts: string[]; className?: string }) {
  const [idx, setIdx] = useState(0);
  const [charIdx, setCharIdx] = useState(0);
  const [del, setDel] = useState(false);

  useEffect(() => {
    const current = texts[idx];
    if (!del && charIdx < current.length) {
      const t = setTimeout(() => setCharIdx((v) => v + 1), 80);
      return () => clearTimeout(t);
    }
    if (!del && charIdx === current.length) {
      const t = setTimeout(() => setDel(true), 2000);
      return () => clearTimeout(t);
    }
    if (del && charIdx > 0) {
      const t = setTimeout(() => setCharIdx((v) => v - 1), 40);
      return () => clearTimeout(t);
    }
    if (del && charIdx === 0) {
      setDel(false);
      setIdx((v) => (v + 1) % texts.length);
    }
  }, [charIdx, del, idx, texts]);

  return (
    <span className={className}>
      {texts[idx].slice(0, charIdx)}
      <span className="animate-pulse text-primary">|</span>
    </span>
  );
}

/* ─── 浮动粒子背景 ─── */
function FloatingOrbs() {
  return (
    <div className="absolute inset-0 overflow-hidden pointer-events-none">
      <div className="orb-warm" />
      <div className="orb-earth" />
      <div
        className="absolute inset-0 opacity-[0.03] dark:opacity-[0.05]"
        style={{
          backgroundImage:
            "radial-gradient(circle, currentColor 1px, transparent 1px)",
          backgroundSize: "32px 32px",
        }}
      />
    </div>
  );
}

/* ─── 流程步骤可视化（赤陶色系辅色） ─── */
const FLOW_STEPS = [
  { icon: FileText, label: "上传文档", color: "text-primary", bg: "bg-primary/10" },
  { icon: NotebookText, label: "生成笔记", color: "text-amber-700 dark:text-amber-400", bg: "bg-amber-500/10" },
  { icon: GitFork, label: "思维导图", color: "text-rose-600 dark:text-rose-400", bg: "bg-rose-500/10" },
  { icon: Layers, label: "闪卡记忆", color: "text-amber-600 dark:text-amber-400", bg: "bg-amber-500/10" },
  { icon: HelpCircle, label: "测验巩固", color: "text-teal-600 dark:text-teal-400", bg: "bg-teal-500/10" },
  { icon: BookOpen, label: "学习日志", color: "text-violet-600 dark:text-violet-400", bg: "bg-violet-500/10" },
];

/* ─── 特性卡片数据（赤陶色系辅色） ─── */
const FEATURES = [
  {
    icon: FileText,
    title: "智能大纲提取",
    desc: "AI 自动识别文档章节结构，勾选指定范围生成内容",
    color: "from-primary to-primary/80",
  },
  {
    icon: NotebookText,
    title: "AI 学习笔记",
    desc: "自动提炼关键概念、公式、案例，生成结构化笔记",
    color: "from-amber-500 to-amber-600",
  },
  {
    icon: GitFork,
    title: "思维导图",
    desc: "可视化知识脉络，支持拖拽交互与 PNG 导出",
    color: "from-rose-500 to-rose-600",
  },
  {
    icon: Layers,
    title: "闪卡记忆",
    desc: "AI 生成正反面闪卡，基于 SM-2 算法间隔重复",
    color: "from-amber-600 to-amber-700",
  },
  {
    icon: HelpCircle,
    title: "测验巩固",
    desc: "自动出题检验掌握程度，错题收录到全局错题本",
    color: "from-teal-500 to-teal-600",
  },
  {
    icon: Sparkles,
    title: "AI 对话助手",
    desc: "基于文档内容的 RAG 问答，回答可追溯到原文",
    color: "from-violet-500 to-violet-600",
  },
];

/* ─── Landing Page ─── */
export default function LandingPage() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  return (
    <div className="relative min-h-screen bg-background text-foreground">
      <FloatingOrbs />

      {/* ════════ Navbar ════════ */}
      <nav className="relative z-10 flex items-center justify-between px-4 sm:px-6 lg:px-8 py-4">
        <BrandMark variant="navbar" />

        {/* Desktop nav */}
        <div className="hidden md:flex items-center gap-3">
          <Link
            href="/demo"
            className="text-sm text-muted-foreground hover:text-foreground transition-colors px-3 py-2"
          >
            体验演示
          </Link>
          <Link
            href="/login"
            className="text-sm text-muted-foreground hover:text-foreground transition-colors px-3 py-2"
          >
            登录
          </Link>
          <Link
            href="/register"
            className="inline-flex items-center gap-1.5 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground shadow-md shadow-primary/20 hover:shadow-lg hover:shadow-primary/30 hover:opacity-90 transition-all"
          >
            免费注册
            <ArrowRight className="h-3.5 w-3.5" />
          </Link>
          <ThemeToggle />
        </div>

        {/* Mobile menu button */}
        <div className="flex md:hidden items-center gap-2">
          <ThemeToggle />
          <button
            type="button"
            onClick={() => setMobileMenuOpen((v) => !v)}
            className="p-2 rounded-lg hover:bg-muted transition-colors"
            aria-label={mobileMenuOpen ? "关闭菜单" : "打开菜单"}
          >
            {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </button>
        </div>
      </nav>

      {/* Mobile full-screen menu */}
      {mobileMenuOpen && (
        <div className="fixed inset-0 z-50 flex flex-col bg-background md:hidden">
          <FloatingOrbs />

          {/* Header */}
          <div className="relative z-10 flex items-center justify-between px-5 py-4">
            <BrandMark variant="navbar" />
            <button
              type="button"
              onClick={() => setMobileMenuOpen(false)}
              className="p-2 rounded-full border border-border/60 hover:bg-muted transition-colors"
              aria-label="关闭菜单"
            >
              <X className="h-5 w-5" />
            </button>
          </div>

          {/* Nav links — centered vertically */}
          <div className="relative z-10 flex-1 flex flex-col items-center justify-center gap-2 px-8">
            {[
              { href: "/demo", label: "体验演示", icon: Play },
              { href: "/login", label: "登录", icon: null },
            ].map((item) => (
              <Link
                key={item.href}
                href={item.href}
                onClick={() => setMobileMenuOpen(false)}
                className="w-full max-w-xs text-center text-lg font-medium py-3.5 rounded-xl hover:bg-muted/60 transition-colors flex items-center justify-center gap-2"
              >
                {item.icon && <item.icon className="h-4 w-4 text-muted-foreground" />}
                {item.label}
              </Link>
            ))}

            <div className="w-12 h-px bg-border my-4" />

            <Link
              href="/register"
              onClick={() => setMobileMenuOpen(false)}
              className={cn(
                "w-full max-w-xs text-center text-base font-semibold py-3.5 rounded-xl",
                "bg-primary text-primary-foreground shadow-lg shadow-primary/20",
                "hover:shadow-xl hover:shadow-primary/30 active:scale-[0.98] transition-all",
                "flex items-center justify-center gap-2",
              )}
            >
              免费注册
              <ArrowRight className="h-4 w-4" />
            </Link>
          </div>

          {/* Footer */}
          <div className="relative z-10 px-5 py-5 text-center text-xs text-muted-foreground/50">
            AI 驱动的智能学习平台
          </div>
        </div>
      )}

      {/* ════════ Hero Section ════════ */}
      <section className="relative z-10 flex flex-col items-center justify-center px-4 pt-12 sm:pt-20 pb-16 sm:pb-24 text-center">
        {/* Badge */}
        <div className="inline-flex items-center gap-2 rounded-full border border-primary/20 bg-primary/5 px-4 py-1.5 mb-6">
          <Sparkles className="h-3.5 w-3.5 text-primary" />
          <span className="text-xs font-medium text-primary">AI 驱动的智能学习平台</span>
        </div>

        {/* Title */}
        <h1 className="text-3xl sm:text-4xl md:text-5xl lg:text-6xl font-bold tracking-tight leading-tight max-w-4xl">
          上传一份学习材料
          <br />
          <span className="text-primary">AI 帮你 6 步吃透它</span>
        </h1>

        {/* Subtitle with typewriter */}
        <p className="mt-5 text-base sm:text-lg text-muted-foreground max-w-2xl leading-relaxed">
          自动提取大纲 → 生成笔记 → 思维导图 → 闪卡记忆 → 测验巩固 → 智能复习
        </p>
        <p className="mt-2 text-sm text-muted-foreground/70">
          <TypeWriter
            texts={[
              "试试上传你的课堂笔记……",
              "试试上传你的教材 PDF……",
              "试试上传你的考试大纲……",
              "试试上传你的论文文献……",
            ]}
          />
        </p>

        {/* CTAs */}
        <div className="mt-8 flex flex-col sm:flex-row items-center gap-3">
          <Link
            href="/register"
            className={cn(
              "inline-flex items-center gap-2 rounded-xl px-6 py-3 text-sm font-semibold",
              "bg-primary text-primary-foreground shadow-lg shadow-primary/25",
              "hover:shadow-xl hover:shadow-primary/35 hover:scale-[1.02] active:scale-[0.98]",
              "transition-all duration-200",
            )}
          >
            <Zap className="h-4 w-4" />
            免费开始使用
          </Link>
          <Link
            href="/demo"
            className={cn(
              "inline-flex items-center gap-2 rounded-xl px-6 py-3 text-sm font-medium",
              "border border-border bg-background/50 backdrop-blur",
              "hover:bg-muted hover:border-primary/30",
              "transition-all duration-200",
            )}
          >
            <Play className="h-4 w-4" />
            先看看效果
          </Link>
        </div>
      </section>

      {/* ════════ 6-Step Flow Visual ════════ */}
      <section className="relative z-10 px-4 pb-16 sm:pb-24">
        <div className="max-w-4xl mx-auto">
          <div className="flex flex-wrap items-center justify-center gap-2 sm:gap-0">
            {FLOW_STEPS.map((step, i) => (
              <div key={step.label} className="flex items-center">
                <div className={cn(
                  "flex flex-col items-center gap-1.5 px-3 py-2 rounded-xl",
                  "hover:bg-muted/50 transition-all group cursor-default",
                )}>
                  <div className={cn(
                    "flex h-10 w-10 sm:h-12 sm:w-12 items-center justify-center rounded-xl border transition-all",
                    step.bg, "border-current/10",
                    "group-hover:scale-110 group-hover:shadow-lg",
                  )}>
                    <step.icon className={cn("h-5 w-5 sm:h-6 sm:w-6", step.color)} />
                  </div>
                  <span className="text-[11px] sm:text-xs font-medium text-muted-foreground group-hover:text-foreground transition-colors">
                    {step.label}
                  </span>
                </div>
                {i < FLOW_STEPS.length - 1 && (
                  <ChevronRight className="h-4 w-4 text-muted-foreground/30 hidden sm:block mx-1" />
                )}
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ════════ Features Grid ════════ */}
      <section className="relative z-10 px-4 pb-16 sm:pb-24">
        <div className="max-w-5xl mx-auto">
          <div className="text-center mb-10 sm:mb-14">
            <h2 className="text-2xl sm:text-3xl font-bold tracking-tight">
              一份材料，<span className="text-primary">六种学习方式</span>
            </h2>
            <p className="mt-3 text-sm sm:text-base text-muted-foreground max-w-xl mx-auto">
              从被动阅读到主动学习，AI 帮你把任何学习材料变成完整的知识体系
            </p>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-5">
            {FEATURES.map((f) => (
              <div
                key={f.title}
                className={cn(
                  "group relative rounded-2xl border border-border/50 bg-card/50 backdrop-blur p-5 sm:p-6",
                  "hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5",
                  "transition-all duration-300",
                )}
              >
                <div className={cn(
                  "inline-flex h-10 w-10 items-center justify-center rounded-xl mb-4",
                  "bg-gradient-to-br text-white shadow-md",
                  f.color,
                )}>
                  <f.icon className="h-5 w-5" />
                </div>
                <h3 className="text-sm font-semibold mb-1.5">{f.title}</h3>
                <p className="text-[13px] leading-relaxed text-muted-foreground">{f.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ════════ Trust Section ════════ */}
      <section className="relative z-10 px-4 pb-16 sm:pb-24">
        <div className="max-w-3xl mx-auto">
          <div className="rounded-2xl border border-border/50 bg-card/30 backdrop-blur p-6 sm:p-8">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-6 sm:gap-8">
              {[
                { icon: Eye, title: "回答可溯源", desc: "基于 RAG 检索增强，每个回答都能追溯到原文出处" },
                { icon: Shield, title: "文档安全", desc: "你的文档仅用于生成学习资料，不会被分享给第三方" },
                { icon: Zap, title: "多格式支持", desc: "支持 PDF、DOCX、PPTX、TXT、Markdown、HTML、Excel、CSV" },
              ].map((t) => (
                <div key={t.title} className="flex flex-col items-center text-center gap-2">
                  <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10">
                    <t.icon className="h-4 w-4 text-primary" />
                  </div>
                  <h4 className="text-sm font-semibold">{t.title}</h4>
                  <p className="text-xs text-muted-foreground leading-relaxed">{t.desc}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* ════════ CTA Section ════════ */}
      <section className="relative z-10 px-4 pb-16 sm:pb-24">
        <div className="max-w-2xl mx-auto text-center">
          <h2 className="text-2xl sm:text-3xl font-bold tracking-tight">
            准备好了吗？
          </h2>
          <p className="mt-3 text-muted-foreground">
            上传你的第一份学习材料，让 AI 帮你高效学习
          </p>
          <div className="mt-6 flex flex-col sm:flex-row items-center justify-center gap-3">
            <Link
              href="/register"
              className="inline-flex items-center gap-2 rounded-xl bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/25 hover:shadow-xl hover:shadow-primary/35 transition-all"
            >
              立即免费注册
              <ArrowRight className="h-4 w-4" />
            </Link>
            <Link
              href="/demo"
              className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              <Play className="h-4 w-4" />
              先体验演示
            </Link>
          </div>
        </div>
      </section>

      {/* ════════ Footer ════════ */}
      <footer className="relative z-10 border-t border-border/50 px-4 py-6">
        <div className="max-w-5xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-3 text-xs text-muted-foreground">
          <div className="flex items-center gap-2">
            <BrandMark variant="compact" />
            <span className="text-muted-foreground/60">· © 2026 AI 学习伴侣</span>
          </div>
          <div className="flex items-center gap-4">
            <Link href="/login" className="hover:text-foreground transition-colors">登录</Link>
            <Link href="/register" className="hover:text-foreground transition-colors">注册</Link>
            <Link href="/demo" className="hover:text-foreground transition-colors">演示</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
