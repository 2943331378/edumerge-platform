"use client";

import { useEffect, useCallback } from "react";
import { useTheme } from "next-themes";

interface GlobalKeyboardOptions {
  /** 跳转到指定学习步骤 (1-6) */
  onGoStep?: (step: number) => void;
  /** 打开/关闭 AI 对话 */
  onToggleChat?: () => void;
  /** 总步骤数 */
  totalSteps?: number;
  /** 当数字键 1-4 已被子组件接管时，跳过全局数字键处理 */
  numberKeysHandled?: boolean;
}

/**
 * 全局键盘快捷键 Hook
 *
 * 快捷键清单:
 * - 1-6: 跳转到对应学习步骤
 * - Ctrl+/: 打开/关闭 AI 对话
 * - Ctrl+Shift+D: 切换暗黑模式
 *
 * 注意: 在输入框/文本域内不触发快捷键
 */
export function useGlobalKeyboard({
  onGoStep,
  onToggleChat,
  totalSteps = 6,
  numberKeysHandled = false,
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

      // 1-6: 跳转到对应步骤 (无 Ctrl/Shift/Alt 修饰)
      // 如果数字键已被子组件接管（如卡片自评），则跳过
      if (!e.ctrlKey && !e.shiftKey && !e.metaKey && !e.altKey && !numberKeysHandled) {
        const num = parseInt(e.key, 10);
        if (num >= 1 && num <= totalSteps) {
          e.preventDefault();
          onGoStep?.(num);
        }
      }
    },
    [onGoStep, onToggleChat, setTheme, resolvedTheme, totalSteps, numberKeysHandled]
  );

  useEffect(() => {
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [handleKeyDown]);
}
