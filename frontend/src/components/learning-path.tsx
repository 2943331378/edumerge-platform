"use client";

import { cn } from "@/lib/utils";
import { Check, type LucideIcon } from "lucide-react";

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
  return (
    <nav className="flex items-center justify-center gap-0 px-3 md:px-6 py-3 md:py-4 overflow-x-auto">
      {steps.map((step, idx) => {
        const isCompleted = completedSteps.has(step.id);
        const isActive = currentStep === step.id;
        const isClickable = isCompleted || isActive;

        return (
          <div key={step.id} className="flex items-center shrink-0">
            {/* Step circle + label */}
            <button
              type="button"
              onClick={() => isClickable && onStepClick(step.id)}
              disabled={!isClickable}
              className={cn(
                "flex flex-col items-center gap-1 md:gap-1.5 group transition-all",
                isClickable ? "cursor-pointer" : "cursor-default",
              )}
            >
              <div
                className={cn(
                  "flex h-8 w-8 md:h-10 md:w-10 items-center justify-center rounded-full border-2 transition-all duration-300",
                  isCompleted && "border-emerald-400 bg-emerald-400/10",
                  isActive && "border-primary bg-primary/10 shadow-[0_0_16px_-4px] shadow-primary/30",
                  !isCompleted && !isActive && "border-muted-foreground/25 bg-transparent",
                )}
              >
                {isCompleted ? (
                  <Check className="h-3.5 w-3.5 md:h-4 md:w-4 text-emerald-400" />
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
                  isCompleted && "text-emerald-500",
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
                    ? "bg-emerald-400/60"
                    : completedSteps.has(step.id)
                      ? "bg-gradient-to-r from-emerald-400/60 to-muted-foreground/20"
                      : "bg-muted-foreground/15",
                )}
              />
            )}
          </div>
        );
      })}
    </nav>
  );
}
