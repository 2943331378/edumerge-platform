"use client";

import { useState, useEffect } from "react";
import { cn } from "@/lib/utils";
import {
  X, Sparkles, Upload, NotebookText, GitFork, Layers, HelpCircle, BookOpen,
} from "lucide-react";

const HINT_KEY_PREFIX = "edumerge_step_hint_";

interface StepHintDef {
  icon: React.ElementType;
  title: string;
  desc: string;
  tips: string[];
}

const STEP_HINTS: Record<number, StepHintDef> = {
  1: {
    icon: Upload,
    title: "文档大纲",
    desc: "上传学习材料后，AI 会自动解析文档结构，提取章节大纲。你可以勾选指定章节，让 AI 只针对这些内容生成学习资料。",
    tips: ["支持 PDF、Word、PPT、TXT 格式", "大纲支持展开/折叠浏览", "勾选章节可定向生成内容"],
  },
  2: {
    icon: NotebookText,
    title: "AI 学习笔记",
    desc: "AI 基于文档内容自动生成结构化学习笔记，包含核心概念、公式推导、案例分析。支持编辑和版本历史。",
    tips: ["笔记支持 Markdown 格式", "可导出为文件保存", "每次重新生成会保留历史版本"],
  },
  3: {
    icon: GitFork,
    title: "思维导图",
    desc: "将文档的知识结构可视化为交互式思维导图，帮你快速把握知识脉络和概念关系。",
    tips: ["节点可拖拽交互", "支持导出为 PNG 图片", "暗黑模式下自动适配"],
  },
  4: {
    icon: Layers,
    title: "闪卡记忆",
    desc: "AI 生成正反面闪卡，基于 SM-2 遗忘曲线算法安排最佳复习时间。翻转卡片后用 1-4 键自评掌握程度。",
    tips: ["生成后可预览、编辑、删除", "Space 键翻转，←→ 切换卡片", "自评后 AI 自动安排下次复习"],
  },
  5: {
    icon: HelpCircle,
    title: "测验巩固",
    desc: "AI 自动生成选择题和填空题，检验你的掌握程度。错题自动收录到全局错题本，支持按薄弱度查看热力图。",
    tips: ["支持选择题和填空题", "错题自动收录错题本", "可查看历史得分趋势"],
  },
  6: {
    icon: BookOpen,
    title: "学习日志",
    desc: "记录学习过程中的思考和洞察。AI 还能从你的对话中自动提取学习要点，持续积累你的知识体系。",
    tips: ["支持分类筛选（洞察/疑问/关联/待办）", "每 5 轮对话自动提取要点", "可标记已复习"],
  },
};

interface Props {
  step: number;
  visible: boolean;
  onDismiss: () => void;
}

export function StepHint({ step, visible, onDismiss }: Props) {
  const hint = STEP_HINTS[step];
  if (!hint || !visible) return null;

  return (
    <div className="px-4 pt-3 pb-1 shrink-0">
      <div className={cn(
        "relative rounded-xl border border-primary/20 bg-primary/5 p-4",
        "animate-in fade-in slide-in-from-top-2 duration-300",
      )}>
        <button
          type="button"
          onClick={onDismiss}
          className="absolute top-2 right-2 flex h-6 w-6 items-center justify-center rounded-md text-muted-foreground/50 hover:text-foreground hover:bg-muted/50 transition-all"
        >
          <X className="h-3.5 w-3.5" />
        </button>

        <div className="flex items-start gap-3 pr-6">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary/10">
            <hint.icon className="h-4.5 w-4.5 text-primary" />
          </div>
          <div className="space-y-1.5 min-w-0">
            <div className="flex items-center gap-2">
              <h3 className="text-sm font-semibold">{hint.title}</h3>
              <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-medium text-primary">
                <Sparkles className="h-2.5 w-2.5" />
                功能说明
              </span>
            </div>
            <p className="text-xs text-muted-foreground leading-relaxed">{hint.desc}</p>
            <div className="flex flex-wrap gap-1.5 pt-0.5">
              {hint.tips.map((tip, i) => (
                <span
                  key={i}
                  className="inline-flex items-center gap-1 rounded-md bg-background/60 border border-border/50 px-2 py-0.5 text-[10px] text-muted-foreground"
                >
                  <span className="h-0.5 w-0.5 rounded-full bg-primary/50" />
                  {tip}
                </span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Check if a step hint has been dismissed */
export function isStepHintDismissed(step: number): boolean {
  if (typeof window === "undefined") return true;
  return localStorage.getItem(`${HINT_KEY_PREFIX}${step}`) === "1";
}

/** Dismiss a step hint */
export function dismissStepHint(step: number): void {
  localStorage.setItem(`${HINT_KEY_PREFIX}${step}`, "1");
}

/** Reset all step hints (for testing) */
export function resetStepHints(): void {
  for (let i = 1; i <= 6; i++) {
    localStorage.removeItem(`${HINT_KEY_PREFIX}${i}`);
  }
}
