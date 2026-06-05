"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";
import { Check, ChevronDown, type LucideIcon } from "lucide-react";

export interface StepDef {
  id: number;
  label: string;
  icon: LucideIcon;
}

interface LearningPathProps {
  steps: StepDef[];
  currentStep: number;
  completedSteps: Set<number>;
  onStepClick: (step: number) => void;
}

export function LearningPath({ steps, currentStep, completedSteps, onStepClick }: LearningPathProps) {
  const [collapsed, setCollapsed] = useState(true);

  const currentStepDef = steps.find((s) => s.id === currentStep);
  const CurrentIcon = currentStepDef?.icon;

  // ═══ 折叠态 — 紧凑药丸 ═══
  if (collapsed) {
    return (
      <div className="flex items-center justify-center px-3 py-2">
        <button
          type="button"
          onClick={() => setCollapsed(false)}
          className="group inline-flex items-center gap-2 rounded-full bg-muted/40 hover:bg-muted/70 backdrop-blur-sm border border-border/30 px-4 py-1.5 transition-all duration-200"
          title="展开学习路径"
        >
          {CurrentIcon && (
            <CurrentIcon className="h-3.5 w-3.5 text-primary/70 group-hover:text-primary transition-colors" />
          )}
          <span className="text-[11px] font-medium text-foreground/70 group-hover:text-foreground transition-colors">
            {currentStepDef?.label ?? ""}
          </span>
          <span className="text-[10px] text-muted-foreground/40 tabular-nums">
            {currentStep}/{steps.length}
          </span>
          <ChevronDown className="h-3 w-3 text-muted-foreground/30 group-hover:text-muted-foreground/60 transition-colors" />
        </button>
      </div>
    );
  }

  // ═══ 展开态 — 完整路径 ═══
  return (
    <nav className="relative flex items-center justify-center gap-0 px-3 md:px-6 py-3 md:py-4 overflow-x-auto">
      {steps.map((step, idx) => {
        const isCompleted = completedSteps.has(step.id);
        const isActive = currentStep === step.id;

        return (
          <div key={step.id} className="flex items-center shrink-0">
            {/* Step circle + label */}
            <button
              type="button"
              onClick={() => onStepClick(step.id)}
              className={cn(
                "flex flex-col items-center gap-1 md:gap-1.5 group transition-all cursor-pointer",
              )}
            >
              <div
                className={cn(
                  "flex h-8 w-8 md:h-10 md:w-10 items-center justify-center rounded-full border-2 transition-all duration-300",
                  isCompleted && "border-lime-500 bg-lime-500/10",
                  isActive && "border-primary bg-primary/10 shadow-[0_0_16px_-4px] shadow-primary/30",
                  !isCompleted && !isActive && "border-muted-foreground/25 bg-transparent",
                )}
              >
                {isCompleted ? (
                  <Check className="h-3.5 w-3.5 md:h-4 md:w-4 text-lime-500" />
                ) : (
                  <step.icon
                    className={cn(
                      "h-3.5 w-3.5 md:h-4 md:w-4 transition-colors",
                      isActive ? "text-primary" : "text-muted-foreground/40",
                    )}
                  />
                )}
              </div>
              <span
                className={cn(
                  "text-[10px] md:text-[11px] font-medium transition-colors whitespace-nowrap",
                  isActive && "text-foreground",
                  isCompleted && "text-lime-600",
                  !isActive && !isCompleted && "text-muted-foreground/40",
                )}
              >
                {step.label}
              </span>
            </button>

            {/* Connector line */}
            {idx < steps.length - 1 && (
              <div
                className={cn(
                  "h-0.5 w-6 sm:w-8 md:w-14 mx-0.5 md:mx-1 rounded-full transition-all duration-300",
                  completedSteps.has(step.id) && completedSteps.has(steps[idx + 1].id)
                    ? "bg-lime-500/60"
                    : completedSteps.has(step.id)
                      ? "bg-gradient-to-r from-lime-500/60 to-muted-foreground/20"
                      : "bg-muted-foreground/15",
                )}
              />
            )}
          </div>
        );
      })}

      {/* 收起按钮 — 右侧悬浮 */}
      <button
        type="button"
        onClick={() => setCollapsed(true)}
        className="absolute right-2 top-1/2 -translate-y-1/2 flex h-6 w-6 items-center justify-center rounded-full bg-muted/40 hover:bg-muted/70 text-muted-foreground/40 hover:text-foreground transition-all"
        title="收起学习路径"
      >
        <ChevronDown className="h-3.5 w-3.5 rotate-180" />
      </button>
    </nav>
  );
}
