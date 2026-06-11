"use client";

/**
 * 文档大纲视图 — 编辑型目录选择器
 *
 * 设计方向: 文学精装书目录 / Editorial Book TOC
 * - 灵感来自精装书目录页与杂志排版: 衬线字体章节标题、装饰性章节编号、温暖纸张色调
 * - 选择交互: 墨水晕染高亮 + 浮动玻璃工具栏
 * - 动效: 交错渐入、弹性展开、微妙悬浮抬升
 */

import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import type { DocumentOutline, OutlineSection } from "@/lib/api";
import {
  getDocumentOutline,
  updateDocumentOutline,
  regenerateDocumentOutline,
} from "@/lib/api";
import {
  BookOpen, FileText, Presentation, Wrench,
  Check, Minus, Pencil,
  RotateCw, Sparkles, Layers, GitFork, HelpCircle, NotebookText,
  Loader2, RefreshCw, Download, MoreHorizontal,
  ChevronDown, ChevronRight,
} from "lucide-react";

// ═══════ 文档类型配置 ═══════

const DOC_TYPE_CONFIG: Record<string, {
  icon: typeof BookOpen;
  label: string;
  accent: string;
  accentBg: string;
}> = {
  TEXTBOOK: { icon: BookOpen,     label: "教材/教科书",  accent: "text-amber-700 dark:text-amber-400",  accentBg: "bg-amber-100/80 dark:bg-amber-900/30" },
  PAPER:    { icon: FileText,     label: "学术论文",      accent: "text-sky-700 dark:text-sky-400",      accentBg: "bg-sky-100/80 dark:bg-sky-900/30" },
  NOTE:     { icon: NotebookText, label: "学习笔记",      accent: "text-emerald-700 dark:text-emerald-400", accentBg: "bg-emerald-100/80 dark:bg-emerald-900/30" },
  SLIDE:    { icon: Presentation, label: "演示文稿",      accent: "text-rose-700 dark:text-rose-400",    accentBg: "bg-rose-100/80 dark:bg-rose-900/30" },
  MANUAL:   { icon: Wrench,       label: "技术手册",      accent: "text-slate-600 dark:text-slate-400",  accentBg: "bg-slate-100/80 dark:bg-slate-800/30" },
  OTHER:    { icon: FileText,     label: "其他文档",      accent: "text-muted-foreground",              accentBg: "bg-muted/50" },
};

// ═══════ Props ═══════

interface Props {
  docId: number | null;
  docStatus: string | null;
  embedded?: boolean;
  onContextChange?: (hint: string) => void;
  onGenerate?: (type: "note" | "mindmap" | "flashcard" | "quiz", sectionContext: string, startChunk?: number, endChunk?: number) => void;
}

// ═══════ 选择状态 ═══════

type CheckState = "checked" | "indeterminate" | "unchecked";

function getCheckState(
  node: OutlineSection,
  selected: Set<string>,
  totalLeafCount: { current: number },
  checkedLeafCount: { current: number },
): CheckState {
  const children = node.children ?? [];
  if (children.length === 0) {
    totalLeafCount.current++;
    if (selected.has(node.id)) {
      checkedLeafCount.current++;
      return "checked";
    }
    return "unchecked";
  }
  const childStates = children.map((c) =>
    getCheckState(c, selected, totalLeafCount, checkedLeafCount)
  );
  const allChecked = childStates.every((s) => s === "checked");
  const noneChecked = childStates.every((s) => s === "unchecked");
  if (allChecked) return "checked";
  if (noneChecked) return "unchecked";
  return "indeterminate";
}

function collectIds(node: OutlineSection): string[] {
  return [node.id, ...(node.children ?? []).flatMap(collectIds)];
}

// ═══════ 深度视觉配置 ═══════

const DEPTH_STYLES = [
  // depth 0: 章 — 大号装饰编号 + 衬线粗体
  {
    numSize: "text-[28px] max-md:text-[22px]",
    titleSize: "text-[15px] max-md:text-[14px]",
    titleWeight: "font-bold",
    titleColor: "text-foreground",
    rowPad: "py-3 max-md:py-3",
    connector: "w-px bg-amber-300/40 dark:bg-amber-700/25",
    indent: "pl-0",
    connectorIndent: "ml-[19px] max-md:ml-[15px]",
  },
  // depth 1: 节 — 中号编号
  {
    numSize: "text-[13px]",
    titleSize: "text-[13px] max-md:text-[13px]",
    titleWeight: "font-medium",
    titleColor: "text-foreground/80",
    rowPad: "py-1.5 max-md:py-2",
    connector: "w-px bg-foreground/8 dark:bg-foreground/5",
    indent: "pl-8 max-md:pl-6",
    connectorIndent: "ml-[19px]",
  },
  // depth 2: 小节 — 小号编号
  {
    numSize: "text-[11px]",
    titleSize: "text-[12px] max-md:text-[12px]",
    titleWeight: "font-normal",
    titleColor: "text-foreground/60",
    rowPad: "py-1 max-md:py-1.5",
    connector: "w-px bg-foreground/5 dark:bg-foreground/3",
    indent: "pl-16 max-md:pl-12",
    connectorIndent: "ml-[19px]",
  },
];

/** 大纲生成等待轮播文案 — 带图标的富文本卡片 */
const OUTLINE_TIPS: { icon: typeof BookOpen; title: string; desc: string }[] = [
  { icon: BookOpen,   title: "逐页精读",   desc: "AI 正在逐页阅读你的文档，理解每一处细节" },
  { icon: GitFork,    title: "结构识别",   desc: "识别章节层级关系与知识脉络" },
  { icon: Layers,     title: "切块分配",   desc: "为每个章节精确分配文本范围" },
  { icon: Sparkles,   title: "标题提炼",   desc: "用简洁有力的关键词概括每个章节核心" },
  { icon: HelpCircle, title: "学科感知",   desc: "根据文档内容自动匹配学科结构模板" },
  { icon: FileText,   title: "深度分析",   desc: "复杂文档需要更精细的层次拆解" },
  { icon: NotebookText, title: "知识图谱", desc: "梳理概念间的关联，构建知识网络" },
  { icon: Check,      title: "质量校验",   desc: "检查章节完整性，确保无遗漏" },
];

function OutlineLoadingTip() {
  const [idx, setIdx] = useState(() => Math.floor(Math.random() * OUTLINE_TIPS.length));
  useEffect(() => {
    const t = setInterval(() => setIdx((v) => (v + 1) % OUTLINE_TIPS.length), 3500);
    return () => clearInterval(t);
  }, []);
  const tip = OUTLINE_TIPS[idx];
  const Icon = tip.icon;
  return (
    <div
      key={idx}
      className="flex items-start gap-3 rounded-xl bg-white/60 dark:bg-white/[0.04] backdrop-blur-sm border border-amber-200/40 dark:border-amber-800/20 px-4 py-3 max-w-xs animate-in fade-in zoom-in-95 duration-500"
    >
      <div className="shrink-0 h-8 w-8 rounded-lg bg-amber-100/80 dark:bg-amber-900/30 flex items-center justify-center">
        <Icon className="h-4 w-4 text-amber-600 dark:text-amber-400" />
      </div>
      <div className="space-y-0.5 min-w-0">
        <p className="text-xs font-semibold text-foreground/80">{tip.title}</p>
        <p className="text-[11px] text-muted-foreground/60 leading-relaxed">{tip.desc}</p>
      </div>
    </div>
  );
}

/** 已耗时计时器 */
function ElapsedTimer() {
  const [sec, setSec] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setSec((v) => v + 1), 1000);
    return () => clearInterval(t);
  }, []);
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return (
    <span className="tabular-nums text-[11px] text-muted-foreground/35 font-medium">
      {m > 0 ? `${m}分` : ""}{s}秒
    </span>
  );
}

/** 装饰性浮动粒子 */
function FloatingParticles() {
  const particles = useMemo(() =>
    Array.from({ length: 12 }, (_, i) => ({
      id: i,
      size: 2 + Math.random() * 3,
      x: 10 + Math.random() * 80,
      delay: Math.random() * 8,
      duration: 6 + Math.random() * 6,
      opacity: 0.08 + Math.random() * 0.12,
    })), []);
  return (
    <div className="absolute inset-0 overflow-hidden pointer-events-none" aria-hidden="true">
      {particles.map((p) => (
        <div
          key={p.id}
          className="absolute rounded-full bg-amber-400 dark:bg-amber-500"
          style={{
            width: p.size,
            height: p.size,
            left: `${p.x}%`,
            bottom: "-5%",
            opacity: 0,
            animation: `outline-float-up ${p.duration}s ${p.delay}s ease-in infinite`,
          }}
        />
      ))}
    </div>
  );
}

/** 多层轨道动画核心 */
function OrbitalCore() {
  return (
    <div className="relative h-32 w-32">
      {/* 最外层光晕 */}
      <div className="absolute -inset-4 rounded-full bg-gradient-to-br from-amber-300/10 to-orange-300/5 dark:from-amber-500/5 dark:to-orange-500/[0.02] blur-xl" />

      {/* 轨道 1 — 最外圈 */}
      <div className="absolute inset-0 rounded-full border border-dashed border-amber-300/25 dark:border-amber-500/10 animate-[spin_12s_linear_infinite]">
        <div className="absolute -top-1.5 left-1/2 -translate-x-1/2 w-3 h-3 rounded-full bg-gradient-to-br from-amber-400 to-orange-400 shadow-md shadow-amber-400/40" />
      </div>

      {/* 轨道 2 */}
      <div className="absolute inset-3 rounded-full border border-dashed border-orange-300/20 dark:border-orange-500/8 animate-[spin_8s_linear_infinite_reverse]">
        <div className="absolute top-1/2 -right-1 -translate-y-1/2 w-2 h-2 rounded-full bg-amber-300 dark:bg-amber-500 shadow-sm shadow-amber-300/30" />
      </div>

      {/* 轨道 3 — 内圈 */}
      <div className="absolute inset-6 rounded-full border border-dotted border-amber-400/15 dark:border-amber-500/8 animate-[spin_5s_linear_infinite]">
        <div className="absolute -bottom-1 left-1/2 -translate-x-1/2 w-1.5 h-1.5 rounded-full bg-orange-400 dark:bg-orange-500 shadow-sm shadow-orange-400/30" />
      </div>

      {/* 轨道 4 — 最内圈虚线 */}
      <div className="absolute inset-9 rounded-full border border-dashed border-amber-200/20 dark:border-amber-600/10 animate-[spin_3s_linear_infinite_reverse]" />

      {/* 中心图标 */}
      <div className="absolute inset-0 flex items-center justify-center">
        <div className="relative">
          <div className="h-14 w-14 rounded-2xl bg-gradient-to-br from-amber-100 via-orange-50 to-amber-100 dark:from-amber-900/50 dark:via-orange-900/30 dark:to-amber-900/40 flex items-center justify-center shadow-lg shadow-amber-200/30 dark:shadow-amber-900/20 border border-amber-200/50 dark:border-amber-700/20">
            <BookOpen className="h-7 w-7 text-amber-700 dark:text-amber-300 animate-pulse" />
          </div>
          {/* 呼吸光环 */}
          <div className="absolute -inset-2 rounded-2xl border border-amber-300/20 dark:border-amber-500/10 animate-ping" />
        </div>
      </div>
    </div>
  );
}

/** 等待页面主体 */
function OutlineWaitingView({ text }: { text: string }) {
  return (
    <div className="flex-1 flex items-center justify-center relative overflow-hidden" style={{ background: "var(--outline-bg)" }}>
      <FloatingParticles />
      <div className="relative z-10 flex flex-col items-center gap-7">
        <OrbitalCore />
        <div className="text-center space-y-3">
          <p className="text-base font-semibold text-foreground/75 tracking-tight" style={{ fontFamily: "var(--font-serif)" }}>
            {text}
          </p>
          <OutlineLoadingTip />
        </div>
        <div className="flex items-center gap-2 text-muted-foreground/30">
          <div className="h-px w-8 bg-gradient-to-r from-transparent to-amber-300/30" />
          <ElapsedTimer />
          <div className="h-px w-8 bg-gradient-to-l from-transparent to-amber-300/30" />
        </div>
      </div>
    </div>
  );
}

// ═══════ 主组件 ═══════

export function DocumentOutlineView({
  docId,
  docStatus,
  embedded,
  onContextChange,
  onGenerate,
}: Props) {
  const [outline, setOutline] = useState<DocumentOutline | null>(null);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState("");
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);

  const handleExportMarkdown = () => {
    if (!outline) return;
    const lines: string[] = [`# 文档大纲`, "", `**类型**: ${outline.outline.docTypeLabel}`, `**总切块数**: ${outline.outline.totalChunks}`, ""];
    const renderSection = (s: OutlineSection, depth: number) => {
      lines.push(`${"#".repeat(depth + 1)} ${s.title}`);
      if (s.startChunk != null && s.endChunk != null) {
        lines.push(`> 切块范围: ${s.startChunk} - ${s.endChunk}`);
      }
      lines.push("");
      (s.children ?? []).forEach((c) => renderSection(c, depth + 1));
    };
    outline.outline.sections.forEach((s) => renderSection(s, 1));
    const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `文档大纲_${new Date().toISOString().slice(0, 10)}.md`;
    a.click();
    URL.revokeObjectURL(url);
    toast.success("已导出 Markdown");
  };

  const generatingRef = useRef(false);
  useEffect(() => {
    // 切换文档时重置生成状态
    generatingRef.current = false;
    setGenerating(false);
    if (!docId) { setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    getDocumentOutline(docId)
      .then((data) => {
        if (cancelled) return;
        setOutline(data);
        const allIds = data.outline.sections.flatMap(collectIds);
        setSelected(new Set(allIds));
        const expandable = data.outline.sections
          .filter((s) => (s.children ?? []).length > 0)
          .map((s) => s.id);
        setExpanded(new Set(expandable));
      })
      .catch(() => {
        if (cancelled) return;
        setOutline(null);
        if (docStatus === "COMPLETED" && !generatingRef.current) {
          generatingRef.current = true;
          setGenerating(true);
          regenerateDocumentOutline(docId)
            .then((data) => {
              if (cancelled) return;
              setOutline(data);
              const allIds = data.outline.sections.flatMap(collectIds);
              setSelected(new Set(allIds));
              const expandable = data.outline.sections
                .filter((s) => (s.children ?? []).length > 0)
                .map((s) => s.id);
              setExpanded(new Set(expandable));
            })
            .catch(() => {})
            .finally(() => {
              generatingRef.current = false;
              if (!cancelled) setGenerating(false);
            });
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [docId, docStatus]);

  const pollCountRef = useRef(0);
  useEffect(() => {
    if (!docId || outline || docStatus !== "COMPLETED") { pollCountRef.current = 0; return; }
    pollCountRef.current = 0;
    const timer = setInterval(() => {
      if (pollCountRef.current >= 60) { clearInterval(timer); return; }
      pollCountRef.current++;
      getDocumentOutline(docId)
        .then((data) => {
          setOutline(data);
          const allIds = data.outline.sections.flatMap(collectIds);
          setSelected(new Set(allIds));
          const expandable = data.outline.sections
            .filter((s) => (s.children ?? []).length > 0)
            .map((s) => s.id);
          setExpanded(new Set(expandable));
        })
        .catch(() => {});
    }, 3000);
    return () => clearInterval(timer);
  }, [docId, outline, docStatus]);

  useEffect(() => {
    if (outline) {
      onContextChange?.(`用户在查看文档大纲（${outline.outline.docTypeLabel}，${outline.outline.sections.length} 章）`);
    }
  }, [outline, onContextChange]);

  useEffect(() => {
    if (!openMenuId) return;
    const handler = (e: PointerEvent) => {
      const target = e.target as HTMLElement;
      if (!target.closest("[data-quick-gen-menu]")) {
        setOpenMenuId(null);
      }
    };
    document.addEventListener("pointerdown", handler);
    return () => document.removeEventListener("pointerdown", handler);
  }, [openMenuId]);

  const { totalLeaves, checkedLeaves } = useMemo(() => {
    if (!outline) return { totalLeaves: 0, checkedLeaves: 0 };
    let total = 0, checked = 0;
    for (const section of outline.outline.sections) {
      const t = { current: 0 }, c = { current: 0 };
      getCheckState(section, selected, t, c);
      total += t.current;
      checked += c.current;
    }
    return { totalLeaves: total, checkedLeaves: checked };
  }, [outline, selected]);

  const toggleSelect = useCallback((node: OutlineSection) => {
    setSelected((prev) => {
      const next = new Set(prev);
      const ids = collectIds(node);
      const allSelected = ids.every((id) => next.has(id));
      if (allSelected) {
        ids.forEach((id) => next.delete(id));
      } else {
        ids.forEach((id) => next.add(id));
      }
      return next;
    });
  }, []);

  const toggleExpand = useCallback((id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const selectAll = useCallback(() => {
    if (!outline) return;
    const allIds = outline.outline.sections.flatMap(collectIds);
    setSelected(new Set(allIds));
  }, [outline]);

  const invertSelection = useCallback(() => {
    if (!outline) return;
    const allIds = outline.outline.sections.flatMap(collectIds);
    setSelected((prev) => {
      const next = new Set<string>();
      allIds.forEach((id) => { if (!prev.has(id)) next.add(id); });
      return next;
    });
  }, [outline]);

  const startEdit = useCallback((id: string, currentTitle: string) => {
    setEditingId(id);
    setEditTitle(currentTitle);
  }, []);

  const saveEdit = useCallback(() => {
    if (!editingId || !outline || !editTitle.trim()) { setEditingId(null); return; }
    const updateTitle = (nodes: OutlineSection[]): OutlineSection[] =>
      nodes.map((n) =>
        n.id === editingId
          ? { ...n, title: editTitle.trim() }
          : { ...n, children: updateTitle(n.children ?? []) }
      );
    const updated = {
      ...outline,
      outline: { ...outline.outline, sections: updateTitle(outline.outline.sections) },
    };
    const previous = outline;
    setOutline(updated);
    setEditingId(null);
    if (docId) {
      updateDocumentOutline(docId, updated.outline).catch(() => {
        setOutline(previous);
        toast.error("保存编辑失败，已恢复原内容");
      });
    }
  }, [editingId, editTitle, outline, docId]);

  const handleRegenerate = useCallback(async () => {
    if (!docId || generatingRef.current) return;
    generatingRef.current = true;
    setGenerating(true);
    try {
      const data = await regenerateDocumentOutline(docId);
      setOutline(data);
      const allIds = data.outline.sections.flatMap(collectIds);
      setSelected(new Set(allIds));
      toast.success("大纲已重新生成");
    } catch {
      toast.error("重新生成失败");
    }
    generatingRef.current = false;
    setGenerating(false);
  }, [docId]);

  const selectedSectionContext = useMemo(() => {
    if (!outline) return "";
    const pairs: string[] = [];
    const walk = (s: OutlineSection) => {
      if (selected.has(s.id)) pairs.push(`${s.id} ${s.title}`);
      (s.children ?? []).forEach(walk);
    };
    outline.outline.sections.forEach(walk);
    return pairs.join("; ");
  }, [outline, selected]);

  const selectedChunkRange = useMemo(() => {
    if (!outline) return undefined;
    let minStart = Infinity, maxEnd = -Infinity;
    const walk = (s: OutlineSection) => {
      if (selected.has(s.id)) {
        if (s.startChunk != null && s.startChunk < minStart) minStart = s.startChunk;
        if (s.endChunk != null && s.endChunk > maxEnd) maxEnd = s.endChunk;
      }
      (s.children ?? []).forEach(walk);
    };
    outline.outline.sections.forEach(walk);
    if (minStart === Infinity || maxEnd === -Infinity) return undefined;
    return { start: minStart, end: maxEnd };
  }, [outline, selected]);

  // ═══════ Generating (包括重新生成) ═══════
  if (generating) {
    return <OutlineWaitingView text="正在生成文档大纲" />;
  }

  // ═══════ Loading ═══════
  if (loading) {
    return <OutlineWaitingView text="正在解析文档结构" />;
  }

  // ═══════ Empty ═══════
  if (!outline) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-6 bg-[var(--outline-bg)]">
        <div className="relative">
          <div className="h-20 w-20 rounded-3xl bg-gradient-to-br from-amber-50 to-orange-50 dark:from-amber-950/30 dark:to-orange-950/20 flex items-center justify-center shadow-sm">
            <BookOpen className="h-8 w-8 text-amber-400/60 dark:text-amber-500/40" />
          </div>
          <div className="absolute -bottom-1 -right-1 h-6 w-6 rounded-full bg-amber-100 dark:bg-amber-900/40 flex items-center justify-center">
            <Sparkles className="h-3 w-3 text-amber-500" />
          </div>
        </div>
        <div className="text-center space-y-2">
          <p className="text-base font-semibold text-foreground/80" style={{ fontFamily: "var(--font-serif)" }}>
            暂无文档大纲
          </p>
          <p className="text-xs text-muted-foreground/50 max-w-xs leading-relaxed">
            文档向量化完成后将自动生成大纲，也可手动触发
          </p>
        </div>
        <Button
          onClick={handleRegenerate}
          disabled={!docId || docStatus !== "COMPLETED"}
          className="rounded-xl gap-2 h-10 px-5 bg-amber-700 hover:bg-amber-800 text-white shadow-sm"
        >
          <Sparkles className="h-4 w-4" />
          生成文档大纲
        </Button>
      </div>
    );
  }

  const docTypeConf = DOC_TYPE_CONFIG[outline.outline.docType] ?? DOC_TYPE_CONFIG.OTHER;
  const TypeIcon = docTypeConf.icon;
  const totalSections = outline.outline.sections.length;

  return (
    <>
      {/* 内联 CSS 变量 + 字体 */}
      <style>{`
        :root {
          --font-serif: 'Noto Serif SC', 'Source Han Serif SC', 'Songti SC', Georgia, serif;
          --outline-bg: linear-gradient(180deg, #fefdf8 0%, #faf8f3 50%, #f5f2eb 100%);
          --outline-bg-dark: linear-gradient(180deg, #1a1814 0%, #1c1915 50%, #1e1b16 100%);
        }
        .dark { --outline-bg: var(--outline-bg-dark); }
        @keyframes outline-fade-in {
          from { opacity: 0; transform: translateY(8px); }
          to   { opacity: 1; transform: translateY(0); }
        }
        .outline-node-enter {
          animation: outline-fade-in 0.4s cubic-bezier(0.22, 1, 0.36, 1) both;
        }
        @keyframes toolbar-slide-up {
          from { opacity: 0; transform: translateY(16px); }
          to   { opacity: 1; transform: translateY(0); }
        }
        .toolbar-enter {
          animation: toolbar-slide-up 0.35s cubic-bezier(0.22, 1, 0.36, 1) both;
        }
        @keyframes check-pop {
          0%   { transform: scale(1); }
          50%  { transform: scale(1.15); }
          100% { transform: scale(1); }
        }
        .check-pop { animation: check-pop 0.2s ease-out; }
        @keyframes outline-float-up {
          0%   { transform: translateY(0);   opacity: 0; }
          15%  { opacity: 0.25; }
          85%  { opacity: 0.15; }
          100% { transform: translateY(-100vh); opacity: 0; }
        }
      `}</style>

      <div className="flex flex-col h-full" style={{ background: "var(--outline-bg)" }}>
        {/* ── 顶部标题栏 ── */}
        <div className="shrink-0 px-6 max-md:px-4 pt-5 pb-4 border-b border-amber-200/30 dark:border-amber-800/15">
          <div className="flex items-start justify-between">
            <div className="space-y-2">
              {!embedded && (
                <h2
                  className="text-xl max-md:text-lg font-bold text-foreground tracking-tight"
                  style={{ fontFamily: "var(--font-serif)" }}
                >
                  目录大纲
                </h2>
              )}
              {embedded && <div className="h-1" />}
              <div className="flex items-center gap-3 flex-wrap">
                {/* 文档类型印章 */}
                <span
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-[11px] font-semibold tracking-wider uppercase border",
                    docTypeConf.accent, docTypeConf.accentBg,
                    "border-current/15",
                  )}
                >
                  <TypeIcon className="h-3.5 w-3.5" />
                  {docTypeConf.label}
                </span>
                {/* 统计标签 */}
                <span className="text-[11px] text-muted-foreground/50 tabular-nums">
                  {totalSections} 章 · {outline.outline.totalChunks} 片段
                </span>
              </div>
            </div>
            <div className="flex items-center gap-1.5">
              <Button
                size="sm"
                variant="ghost"
                className="rounded-lg gap-1.5 h-8 px-2.5 text-xs text-muted-foreground/60 hover:text-foreground"
                onClick={handleExportMarkdown}
              >
                <Download className="h-3.5 w-3.5" />
                <span className="max-md:hidden">导出</span>
              </Button>
              <Button
                size="sm"
                variant="ghost"
                className="rounded-lg gap-1.5 h-8 px-2.5 text-xs text-muted-foreground/60 hover:text-foreground"
                onClick={handleRegenerate}
                disabled={generating}
              >
                {generating ? (
                  <RotateCw className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <RefreshCw className="h-3.5 w-3.5" />
                )}
                <span className="max-md:hidden">重新生成</span>
              </Button>
            </div>
          </div>
        </div>

        {/* ── 选择统计条 ── */}
        <div className="shrink-0 flex items-center justify-between px-6 max-md:px-4 py-2.5 border-b border-foreground/5">
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-1.5">
              <div className="h-1.5 w-8 rounded-full bg-amber-200/50 dark:bg-amber-800/30 overflow-hidden">
                <div
                  className="h-full rounded-full bg-amber-500 dark:bg-amber-400 transition-all duration-500"
                  style={{ width: `${totalLeaves > 0 ? (checkedLeaves / totalLeaves) * 100 : 0}%` }}
                />
              </div>
              <span className="text-[11px] text-muted-foreground/50 tabular-nums">
                {checkedLeaves}/{totalLeaves}
              </span>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={selectAll}
              className="text-[11px] text-muted-foreground/40 hover:text-amber-700 dark:hover:text-amber-400 transition-colors"
            >
              全选
            </button>
            <span className="text-[11px] text-muted-foreground/15">·</span>
            <button
              type="button"
              onClick={invertSelection}
              className="text-[11px] text-muted-foreground/40 hover:text-amber-700 dark:hover:text-amber-400 transition-colors"
            >
              反选
            </button>
          </div>
        </div>

        {/* ── 大纲树 ── */}
        <div className="flex-1 overflow-y-auto overscroll-contain">
          <div className="max-w-3xl mx-auto px-6 max-md:px-4 pt-4 pb-32">
            {outline.outline.sections.map((section, idx) => (
              <OutlineNode
                key={section.id}
                node={section}
                depth={0}
                selected={selected}
                expanded={expanded}
                editingId={editingId}
                editTitle={editTitle}
                onToggleSelect={toggleSelect}
                onToggleExpand={toggleExpand}
                onStartEdit={startEdit}
                onSaveEdit={saveEdit}
                onEditTitleChange={setEditTitle}
                onCancelEdit={() => setEditingId(null)}
                animDelay={idx * 80}
                onGenerate={onGenerate}
                openMenuId={openMenuId}
                onOpenMenuChange={setOpenMenuId}
                isLast={idx === outline.outline.sections.length - 1}
              />
            ))}
          </div>
        </div>

        {/* ── 底部浮动生成工具栏 ── */}
        {checkedLeaves > 0 && (
          <div className="fixed bottom-0 left-0 right-0 z-40 pointer-events-none">
            <div className="toolbar-enter max-w-3xl mx-auto px-4 pb-5">
              <div
                className={cn(
                  "pointer-events-auto rounded-2xl border border-amber-200/40 dark:border-amber-800/20",
                  "bg-white/90 dark:bg-zinc-900/90 backdrop-blur-xl shadow-[0_8px_40px_-12px_rgba(0,0,0,0.12)]",
                  "px-5 py-3.5",
                )}
              >
                <div className="flex items-center justify-between gap-4">
                  <div className="flex items-center gap-3">
                    <div className="flex items-center gap-1.5">
                      <div className="h-2 w-2 rounded-full bg-amber-500 animate-pulse" />
                      <span
                        className="text-[13px] font-semibold text-foreground/80 tabular-nums"
                        style={{ fontFamily: "var(--font-serif)" }}
                      >
                        {checkedLeaves}
                      </span>
                      <span className="text-xs text-muted-foreground/50">章已选</span>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    {[
                      { type: "note" as const, icon: NotebookText, label: "笔记", color: "hover:bg-emerald-50 hover:text-emerald-700 dark:hover:bg-emerald-950/30 dark:hover:text-emerald-400" },
                      { type: "mindmap" as const, icon: GitFork, label: "导图", color: "hover:bg-sky-50 hover:text-sky-700 dark:hover:bg-sky-950/30 dark:hover:text-sky-400" },
                      { type: "flashcard" as const, icon: Layers, label: "卡片", color: "hover:bg-violet-50 hover:text-violet-700 dark:hover:bg-violet-950/30 dark:hover:text-violet-400" },
                      { type: "quiz" as const, icon: HelpCircle, label: "测验", color: "hover:bg-rose-50 hover:text-rose-700 dark:hover:bg-rose-950/30 dark:hover:text-rose-400" },
                    ].map((item) => (
                      <button
                        key={item.type}
                        type="button"
                        onClick={() => onGenerate?.(item.type, selectedSectionContext, selectedChunkRange?.start, selectedChunkRange?.end)}
                        className={cn(
                          "flex items-center gap-1.5 rounded-xl px-3.5 py-2 text-xs font-medium",
                          "text-foreground/70 bg-muted/50 transition-all duration-200",
                          item.color,
                        )}
                      >
                        <item.icon className="h-3.5 w-3.5" />
                        {item.label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </>
  );
}

// ═══════ 大纲节点子组件 ═══════

interface NodeProps {
  node: OutlineSection;
  depth: number;
  selected: Set<string>;
  expanded: Set<string>;
  editingId: string | null;
  editTitle: string;
  onToggleSelect: (node: OutlineSection) => void;
  onToggleExpand: (id: string) => void;
  onStartEdit: (id: string, title: string) => void;
  onSaveEdit: () => void;
  onEditTitleChange: (title: string) => void;
  onCancelEdit: () => void;
  animDelay: number;
  onGenerate?: (type: "note" | "mindmap" | "flashcard" | "quiz", sectionContext: string, startChunk?: number, endChunk?: number) => void;
  openMenuId: string | null;
  onOpenMenuChange: (id: string | null) => void;
  isLast?: boolean;
}

function OutlineNode({
  node,
  depth,
  selected,
  expanded,
  editingId,
  editTitle,
  onToggleSelect,
  onToggleExpand,
  onStartEdit,
  onSaveEdit,
  onEditTitleChange,
  onCancelEdit,
  animDelay,
  onGenerate,
  openMenuId,
  onOpenMenuChange,
  isLast,
}: NodeProps) {
  const hasChildren = (node.children ?? []).length > 0;
  const isExpanded = expanded.has(node.id);
  const isEditing = editingId === node.id;
  const [justChecked, setJustChecked] = useState(false);

  const checkState = useMemo(() => {
    const t = { current: 0 }, c = { current: 0 };
    return getCheckState(node, selected, t, c);
  }, [node, selected]);

  // 章节编号
  const sectionNum = node.id.replace(/^s/, "").replace(/-/g, ".");
  const ds = DEPTH_STYLES[Math.min(depth, DEPTH_STYLES.length - 1)];

  const handleCheck = () => {
    setJustChecked(true);
    setTimeout(() => setJustChecked(false), 250);
    onToggleSelect(node);
  };

  return (
    <div
      className="outline-node-enter relative"
      style={{ animationDelay: `${animDelay}ms` }}
    >
      {/* 节点行 */}
      <div
        className={cn(
          "group relative flex items-center gap-3 rounded-xl transition-all duration-200",
          "hover:bg-amber-50/40 dark:hover:bg-amber-900/10",
          ds.rowPad,
          ds.indent,
        )}
      >
        {/* 章节编号 — 装饰性数字 */}
        <span
          className={cn(
            "font-mono tabular-nums select-none shrink-0 w-9 text-right",
            ds.numSize,
            depth === 0
              ? "text-amber-400/50 dark:text-amber-600/30 font-bold"
              : "text-foreground/15 dark:text-foreground/10",
          )}
          style={depth === 0 ? { fontFamily: "var(--font-serif)" } : undefined}
        >
          {sectionNum}
        </span>

        {/* 复选框 — 墨水风格 */}
        <button
          type="button"
          role="checkbox"
          aria-checked={checkState === "checked" ? "true" : checkState === "indeterminate" ? "mixed" : "false"}
          aria-label={`选择 ${node.title}`}
          onClick={handleCheck}
          className={cn(
            "flex h-4 w-4 shrink-0 items-center justify-center rounded-[4px] border transition-all duration-200",
            justChecked && "check-pop",
            checkState === "checked"
              ? "bg-amber-600 border-amber-600 text-white shadow-sm shadow-amber-600/20 dark:bg-amber-500 dark:border-amber-500"
              : checkState === "indeterminate"
                ? "bg-amber-100 border-amber-400/60 text-amber-700 dark:bg-amber-900/30 dark:border-amber-600/40 dark:text-amber-400"
                : "border-foreground/15 hover:border-amber-400/50 dark:hover:border-amber-600/30",
          )}
        >
          {checkState === "checked" && <Check className="h-2.5 w-2.5" strokeWidth={3} />}
          {checkState === "indeterminate" && <Minus className="h-2.5 w-2.5" strokeWidth={3} />}
        </button>

        {/* 标题 */}
        {isEditing ? (
          <input
            type="text"
            value={editTitle}
            onChange={(e) => onEditTitleChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") onSaveEdit();
              if (e.key === "Escape") onCancelEdit();
            }}
            onBlur={onSaveEdit}
            autoFocus
            title="编辑章节标题"
            placeholder="输入章节标题"
            className={cn(
              "flex-1 min-w-0 rounded-lg border border-amber-300/50 dark:border-amber-700/30",
              "bg-white dark:bg-zinc-900 px-3 py-1.5 text-sm outline-none",
              "focus:ring-2 focus:ring-amber-400/30 focus:border-amber-400/50",
            )}
          />
        ) : (
          <span
            className={cn(
              "flex-1 min-w-0 truncate cursor-default transition-colors duration-150",
              ds.titleSize,
              ds.titleWeight,
              ds.titleColor,
              checkState === "checked" && "text-amber-800 dark:text-amber-300",
            )}
            style={{ fontFamily: depth === 0 ? "var(--font-serif)" : undefined }}
            onDoubleClick={() => onStartEdit(node.id, node.title)}
            title={node.title}
          >
            {node.title}
          </span>
        )}

        {/* 展开/折叠箭头 */}
        {hasChildren && (
          <button
            type="button"
            onClick={() => onToggleExpand(node.id)}
            aria-expanded={isExpanded}
            aria-label={isExpanded ? "折叠" : "展开"}
            className={cn(
              "flex h-7 w-7 shrink-0 items-center justify-center rounded-lg transition-all duration-200",
              "text-muted-foreground/30 hover:text-amber-700 hover:bg-amber-100/60",
              "dark:hover:text-amber-400 dark:hover:bg-amber-900/20",
            )}
          >
            <ChevronDown
              className={cn(
                "h-3.5 w-3.5 transition-transform duration-300",
                !isExpanded && "-rotate-90",
              )}
            />
          </button>
        )}

        {/* 编辑按钮 */}
        {!isEditing && (
          <button
            type="button"
            onClick={() => onStartEdit(node.id, node.title)}
            className={cn(
              "flex h-7 w-7 shrink-0 items-center justify-center rounded-lg transition-all",
              "text-muted-foreground/0 group-hover:text-muted-foreground/40 hover:!text-amber-700 hover:bg-amber-100/60",
              "dark:hover:!text-amber-400 dark:hover:bg-amber-900/20",
              "max-md:text-muted-foreground/30",
            )}
            title="编辑标题"
          >
            <Pencil className="h-3 w-3" />
          </button>
        )}

        {/* 快速生成按钮 */}
        {onGenerate && !isEditing && (
          <div className="relative shrink-0" data-quick-gen-menu>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onOpenMenuChange(openMenuId === node.id ? null : node.id);
              }}
              className={cn(
                "flex h-7 w-7 items-center justify-center rounded-lg transition-all",
                "text-muted-foreground/0 group-hover:text-muted-foreground/40 hover:!text-amber-700 hover:bg-amber-100/60",
                "dark:hover:!text-amber-400 dark:hover:bg-amber-900/20",
                "max-md:text-muted-foreground/30",
                openMenuId === node.id && "!text-amber-700 !bg-amber-100/60 dark:!text-amber-400 dark:!bg-amber-900/20",
              )}
              title="快速生成"
              aria-haspopup="menu"
              aria-expanded={openMenuId === node.id}
            >
              <MoreHorizontal className="h-4 w-4" />
            </button>

            {openMenuId === node.id && (
              <div
                data-quick-gen-menu
                role="menu"
                className={cn(
                  "absolute right-0 z-50 mt-1 min-w-[180px] rounded-xl border border-amber-200/30 dark:border-amber-800/15",
                  "bg-white/95 dark:bg-zinc-900/95 backdrop-blur-xl p-1.5 shadow-xl",
                  depth >= 2 ? "bottom-full mb-1" : "top-full",
                )}
              >
                <div
                  className="px-3 py-2 text-[11px] font-semibold text-foreground/50 truncate border-b border-foreground/5 mb-1"
                  style={{ fontFamily: "var(--font-serif)" }}
                >
                  {node.title}
                </div>
                {[
                  { type: "note" as const, icon: NotebookText, label: "生成此章笔记", color: "text-emerald-600 dark:text-emerald-400" },
                  { type: "mindmap" as const, icon: GitFork, label: "生成此章导图", color: "text-sky-600 dark:text-sky-400" },
                  { type: "flashcard" as const, icon: Layers, label: "生成此章闪卡", color: "text-violet-600 dark:text-violet-400" },
                  { type: "quiz" as const, icon: HelpCircle, label: "生成此章测验", color: "text-rose-600 dark:text-rose-400" },
                ].map((item) => (
                  <button
                    key={item.type}
                    type="button"
                    role="menuitem"
                    onClick={(e) => {
                      e.stopPropagation();
                      const sectionContext = `${node.id} ${node.title}`;
                      onGenerate(item.type, sectionContext, node.startChunk, node.endChunk);
                      onOpenMenuChange(null);
                    }}
                    className={cn(
                      "flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-xs transition-colors text-left",
                      "hover:bg-amber-50/60 dark:hover:bg-amber-900/15",
                    )}
                  >
                    <item.icon className={cn("h-4 w-4 shrink-0", item.color)} />
                    {item.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        {/* chunk 范围标签 */}
        {node.startChunk != null && node.endChunk != null && (
          <span className="text-[11px] text-muted-foreground/20 font-mono shrink-0 tabular-nums">
            {node.startChunk}–{node.endChunk}
          </span>
        )}
      </div>

      {/* 子节点 */}
      {hasChildren && isExpanded && (
        <div className={cn("relative", ds.connectorIndent)}>
          {/* 连接线 */}
          <div className={cn("absolute top-0 bottom-0", ds.connector)} style={{ left: 0 }} />
          <div className="relative">
            {(node.children ?? []).map((child, i) => (
              <OutlineNode
                key={child.id}
                node={child}
                depth={depth + 1}
                selected={selected}
                expanded={expanded}
                editingId={editingId}
                editTitle={editTitle}
                onToggleSelect={onToggleSelect}
                onToggleExpand={onToggleExpand}
                onStartEdit={onStartEdit}
                onSaveEdit={onSaveEdit}
                onEditTitleChange={onEditTitleChange}
                onCancelEdit={onCancelEdit}
                animDelay={i * 50}
                onGenerate={onGenerate}
                openMenuId={openMenuId}
                onOpenMenuChange={onOpenMenuChange}
                isLast={i === (node.children ?? []).length - 1}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
