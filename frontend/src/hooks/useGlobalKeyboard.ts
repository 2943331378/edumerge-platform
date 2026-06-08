"use client";

import { useEffect, useCallback } from "react";
import { useTheme } from "next-themes";

interface GlobalKeyboardOptions {
  /** 跳转到指定学习步骤 (1-6) */
  onGoStep?: (step: number) => void;
  /** 打开/关闭 AI 对话 */
  onToggleChat?: () => void;
  /** 打开/关闭快捷键帮助 */
  onToggleShortcuts?: () => void;
  /** 总步骤数 */
  totalSteps?: number;
  /** 子组件接管的数字键上限（如卡片自评用 1-4，则传 4）；0 或不传表示不接管 */
  numberKeysHandledUpTo?: number;
}

/**
 * 全局键盘快捷键 Hook
 *
 * 快捷键清单:
 * - 1-6: 跳转到对应学习步骤
 * - Ctrl+/: 打开/关闭 AI 对话
 * - Ctrl+Shift+D: 切换暗黑模式
 * - ?: 打开/关闭快捷键帮助
 *
 * 注意: 在输入框/文本域内不触发快捷键
 */
export function useGlobalKeyboard({
  onGoStep,
  onToggleChat,
  onToggleShortcuts,
  totalSteps = 6,
  numberKeysHandledUpTo = 0,
}: GlobalKeyboardOptions) {
  const { setTheme, resolvedTheme } = useTheme();

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      // 在输入框/文本域/可编辑元素内不触发
      const target = e.target as HTMLElement;
      if (
        target.tagName === "INPUT" ||
        target.tagName === "TEXTAREA" ||
        target.tagName === "SELECT" ||
        target.isContentEditable
      ) {
        return;
      }

      // Ctrl+/: 切换 AI 对话
      if ((e.ctrlKey || e.metaKey) && e.key === "/") {
        e.preventDefault();
        onToggleChat?.();
        return;
      }

      // Ctrl+Shift+D: 切换暗黑模式
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === "d") {
        e.preventDefault();
        setTheme(resolvedTheme === "dark" ? "light" : "dark");
        return;
      }

      // ?: 打开/关闭快捷键帮助
      if (!e.ctrlKey && !e.shiftKey && !e.metaKey && !e.altKey && e.key === "?") {
        e.preventDefault();
        onToggleShortcuts?.();
        return;
      }

      // 1-6: 跳转到对应步骤 (无 Ctrl/Shift/Alt 修饰)
      if (!e.ctrlKey && !e.shiftKey && !e.metaKey && !e.altKey) {
        const num = parseInt(e.key, 10);
        if (num >= 1 && num <= totalSteps) {
          // 跳过被子组件接管的数字键（如卡片自评 1-4）
          if (num <= numberKeysHandledUpTo) return;
          e.preventDefault();
          onGoStep?.(num);
        }
      }
    },
    [onGoStep, onToggleChat, onToggleShortcuts, setTheme, resolvedTheme, totalSteps, numberKeysHandledUpTo]
  );

  useEffect(() => {
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [handleKeyDown]);
}
