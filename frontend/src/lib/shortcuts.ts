/**
 * Keyboard shortcut definitions — single source of truth.
 * Imported by both useGlobalKeyboard (to register) and ShortcutsHelp (to display).
 */

export interface ShortcutDef {
  keys: string;
  description: string;
}

export interface ShortcutGroup {
  title: string;
  shortcuts: ShortcutDef[];
}

export const SHORTCUT_GROUPS: ShortcutGroup[] = [
  {
    title: "全局",
    shortcuts: [
      { keys: "1 ~ 6", description: "跳转到对应学习步骤" },
      { keys: "Ctrl + /", description: "打开 / 关闭 AI 对话" },
      { keys: "Ctrl + Shift + D", description: "切换暗黑模式" },
      { keys: "?", description: "显示 / 隐藏快捷键帮助" },
    ],
  },
  {
    title: "闪卡",
    shortcuts: [
      { keys: "Space", description: "翻转卡片" },
      { keys: "← →", description: "切换上 / 下一张卡片" },
      { keys: "1", description: "自评：忘了" },
      { keys: "2", description: "自评：模糊" },
      { keys: "3", description: "自评：记住" },
      { keys: "4", description: "自评：秒答" },
    ],
  },
  {
    title: "测验",
    shortcuts: [
      { keys: "1 ~ 4", description: "选择选项 A / B / C / D" },
      { keys: "Enter", description: "确认 / 下一题" },
    ],
  },
];
