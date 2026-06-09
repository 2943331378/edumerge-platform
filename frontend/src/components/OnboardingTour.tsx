"use client";

import { useState, useEffect, useRef } from "react";
import { cn } from "@/lib/utils";
import {
  FileText, ChevronRight, ChevronLeft, X, Sparkles,
  Upload, NotebookText, GitFork, Layers, HelpCircle, BookOpen,
  MessageSquare, LayoutDashboard,
} from "lucide-react";

const TOUR_KEY = "edumerge_onboarding_done";

interface TourStep {
  id: number;
  icon: React.ElementType;
  title: string;
  desc: string;
  position: "sidebar" | "main" | "full";
}

const TOUR_STEPS: TourStep[] = [
  {
    id: 1,
    icon: Sparkles,
    title: "欢迎使用 EduMerge",
    desc: "这是一款 AI 驱动的学习伴侣。上传一份学习材料，AI 会自动帮你完成 6 步深度学习流程，把被动阅读变成主动学习。",
    position: "full",
  },
  {
    id: 2,
    icon: Upload,
    title: "第一步：上传文档",
    desc: "在左侧侧边栏上传你的学习材料（PDF / Word / PPT / TXT）。上传后 AI 会自动解析文档结构，生成章节大纲。",
    position: "sidebar",
  },
  {
    id: 3,
    icon: LayoutDashboard,
    title: "6 步学习路径",
    desc: "顶部的学习路径是你完整的学习流程：文档大纲 → 生成笔记 → 思维导图 → 闪卡记忆 → 测验巩固 → 学习日志。",
    position: "main",
  },
  {
    id: 4,
    icon: MessageSquare,
    title: "AI 对话助手",
    desc: "随时点击右上角或右下角的「Ask AI」按钮，基于你上传的文档进行智能问答。每个回答都可以追溯到原文出处。",
    position: "main",
  },
  {
    id: 5,
    icon: Sparkles,
    title: "开始你的学习之旅",
    desc: "现在就上传你的第一份学习材料吧！AI 会帮你从多个维度理解和掌握知识。",
    position: "full",
  },
];

interface Props {
  open: boolean;
  onClose: () => void;
}

export function OnboardingTour({ open, onClose }: Props) {
  const [step, setStep] = useState(0);
  const cardRef = useRef<HTMLDivElement>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);
  const current = TOUR_STEPS[step];
  const isLast = step === TOUR_STEPS.length - 1;
  const isFirst = step === 0;

  const handleNext = () => {
    if (isLast) {
      localStorage.setItem(TOUR_KEY, "1");
      onClose();
    } else {
      setStep((v) => v + 1);
    }
  };

  const handleSkip = () => {
    localStorage.setItem(TOUR_KEY, "1");
    onClose();
  };

  // Focus trap: focus close button on open, trap Tab within dialog
  useEffect(() => {
    if (!open) return;
    closeBtnRef.current?.focus();

    const handleTab = (e: KeyboardEvent) => {
      if (e.key !== "Tab" || !cardRef.current) return;
      const focusable = cardRef.current.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", handleTab);
    return () => window.removeEventListener("keydown", handleTab);
  }, [open, step]);

  if (!open) return null;

  return (
    <>
      {/* Backdrop — header 不遮挡 */}
      <div className="fixed inset-x-0 top-11 bottom-0 z-[100] bg-black/50 backdrop-blur-sm" onClick={handleSkip} />

      {/* Card — 居中弹窗，不覆盖 header */}
      <div className="fixed inset-x-0 top-11 bottom-0 z-[101] flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={cardRef}
          role="dialog"
          aria-modal="true"
          aria-label="使用引导"
          className={cn(
            "relative w-full max-w-md rounded-2xl border border-white/10 bg-card/95 backdrop-blur-xl shadow-2xl",
            "animate-in fade-in zoom-in-95 duration-300",
            "pointer-events-auto",
          )}
        >
          {/* Header with step indicator */}
          <div className="flex items-center justify-between px-5 pt-5">
            <div className="flex gap-1">
              {TOUR_STEPS.map((_, i) => (
                <div
                  key={i}
                  className={cn(
                    "h-1 rounded-full transition-all duration-300",
                    i === step ? "w-6 bg-primary" : i < step ? "w-3 bg-primary/40" : "w-3 bg-muted",
                  )}
                />
              ))}
            </div>
            <button
              ref={closeBtnRef}
              type="button"
              onClick={handleSkip}
              aria-label="关闭引导"
              className="flex h-7 w-7 items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          {/* Content */}
          <div className="px-5 pt-6 pb-2 text-center">
            <div className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10 mb-4">
              <current.icon className="h-7 w-7 text-primary" />
            </div>
            <h2 className="text-lg font-semibold tracking-tight">{current.title}</h2>
            <p className="mt-2.5 text-sm text-muted-foreground leading-relaxed">{current.desc}</p>
          </div>

          {/* 6-step visual on the welcome slide */}
          {step === 0 && (
            <div className="flex items-center justify-center gap-1 px-5 pb-2">
              {[Upload, NotebookText, GitFork, Layers, HelpCircle, BookOpen].map((Icon, i) => (
                <div key={i} className="flex items-center">
                  <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/5">
                    <Icon className="h-4 w-4 text-primary/60" />
                  </div>
                  {i < 5 && <ChevronRight className="h-3 w-3 text-muted-foreground/20 mx-0.5" />}
                </div>
              ))}
            </div>
          )}

          {/* Footer */}
          <div className="flex items-center justify-between px-5 pb-5 pt-3">
            <button
              type="button"
              onClick={() => setStep((v) => Math.max(0, v - 1))}
              disabled={isFirst}
              className={cn(
                "flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-all",
                isFirst
                  ? "text-muted-foreground/30 cursor-not-allowed"
                  : "text-muted-foreground hover:text-foreground hover:bg-muted",
              )}
            >
              <ChevronLeft className="h-3.5 w-3.5" />
              上一步
            </button>
            <span className="text-[10px] text-muted-foreground/40">
              {step + 1} / {TOUR_STEPS.length}
            </span>
            <button
              type="button"
              onClick={handleNext}
              className={cn(
                "flex items-center gap-1 rounded-lg px-4 py-1.5 text-xs font-medium transition-all",
                isLast
                  ? "bg-primary text-primary-foreground shadow-md shadow-primary/20 hover:shadow-lg"
                  : "bg-primary/10 text-primary hover:bg-primary/20",
              )}
            >
              {isLast ? "开始使用" : "下一步"}
              {!isLast && <ChevronRight className="h-3.5 w-3.5" />}
            </button>
          </div>

          {/* Skip link */}
          {!isLast && (
            <div className="pb-4 text-center">
              <button
                type="button"
                onClick={handleSkip}
                className="text-[11px] text-muted-foreground/40 hover:text-muted-foreground transition-colors"
              >
                跳过引导
              </button>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

/** Check if onboarding has been completed */
export function isOnboardingDone(): boolean {
  if (typeof window === "undefined") return true;
  return localStorage.getItem(TOUR_KEY) === "1";
}

/** Reset onboarding (for testing or "show help" feature) */
export function resetOnboarding(): void {
  localStorage.removeItem(TOUR_KEY);
}
