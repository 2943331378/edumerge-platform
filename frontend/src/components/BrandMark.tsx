"use client";

import Image from "next/image";
import { cn } from "@/lib/utils";

interface BrandMarkProps {
  logoSize?: number;
  variant?: "navbar" | "header" | "hero" | "compact";
  showSubtitle?: boolean;
  className?: string;
}

/**
 * 高级品牌标识
 *
 * 设计语言：
 * - 全大写 + 宽字距 → 杂志/奢侈品感
 * - EDU 粗体 + MERGE 细体 → 戏剧性字重对比
 * - ◆ 菱形分隔符 → 精致的视觉锚点
 * - 渐变装饰线 → 品牌色延伸，暗示知识脉络
 * - 悬浮微交互 → 品牌标识的「呼吸感」
 */
export function BrandMark({
  logoSize = 22,
  variant = "navbar",
  showSubtitle = false,
  className,
}: BrandMarkProps) {
  const isHero = variant === "hero";
  const isCompact = variant === "compact";
  const isHeader = variant === "header";

  return (
    <div className={cn("group flex items-center gap-2.5", className)}>
      {/* Logo */}
      <div className={cn(
        "flex items-center justify-center shrink-0 transition-all duration-500",
        "rounded-[10px]",
        "bg-gradient-to-br from-primary/[0.08] to-primary/[0.03]",
        "ring-1 ring-primary/[0.08]",
        "group-hover:ring-primary/20 group-hover:from-primary/[0.12] group-hover:to-primary/[0.05]",
        isHero ? "h-10 w-10" : isCompact ? "h-7 w-7" : "h-8 w-8",
      )}>
        <Image
          src="/logo_converted.svg"
          alt="EduMerge"
          width={isHero ? 22 : isCompact ? 16 : logoSize}
          height={isHero ? 22 : isCompact ? 16 : logoSize}
          priority
          className="transition-transform duration-500 group-hover:scale-105"
        />
      </div>

      {/* Wordmark */}
      <div className="flex flex-col items-start min-w-0">
        <div className="flex items-center gap-0">
          {/* EDU — 粗体，品牌色，衬线体 */}
          <span
            className={cn(
              "font-brand font-bold tracking-[0.18em] leading-none text-foreground",
              "transition-colors duration-300",
              "group-hover:text-primary",
              isHero ? "text-[17px]" : isCompact ? "text-[11px]" : isHeader ? "text-[13px]" : "text-[14px]",
            )}
          >
            EDU
          </span>

          {/* 菱形分隔符 */}
          <span className={cn(
            "mx-[5px] text-primary/40 leading-none transition-all duration-500",
            "group-hover:text-primary/70 group-hover:scale-110",
            isCompact ? "text-[6px]" : "text-[8px]",
          )}>
            ◆
          </span>

          {/* MERGE — 细体，弱色，衬线体 */}
          <span
            className={cn(
              "font-brand font-extralight tracking-[0.28em] leading-none text-muted-foreground/60",
              "transition-colors duration-300",
              "group-hover:text-muted-foreground",
              isHero ? "text-[17px]" : isCompact ? "text-[11px]" : isHeader ? "text-[13px]" : "text-[14px]",
            )}
          >
            MERGE
          </span>
        </div>

        {/* 渐变装饰线 */}
        <div className={cn(
          "mt-[3px] rounded-full overflow-hidden",
          "bg-gradient-to-r from-primary/50 via-primary/20 to-transparent",
          "transition-all duration-700 ease-out",
          "w-0 group-hover:w-full",
          isHero ? "h-[1.5px]" : isCompact ? "h-px" : "h-[1px]",
          /* 默认宽度 — hero 模式展开，其他模式悬浮展开 */
          isHero && "w-full",
        )} />

        {/* 中文副标 */}
        {showSubtitle && (
          <span className={cn(
            "font-medium tracking-[0.3em] text-muted-foreground/35 leading-none",
            "transition-colors duration-300",
            "group-hover:text-muted-foreground/50",
            isHero ? "text-[9px] mt-[5px]" : "text-[8px] mt-1",
          )}>
            智融
          </span>
        )}
      </div>
    </div>
  );
}
