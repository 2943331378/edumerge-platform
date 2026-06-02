"use client";

/**
 * 文档大纲视图 — 编辑型目录选择器
 *
 * 设计方向: 温暖学术风 + 杂志排版感
 * - 灵感来自精装书目录页: 舒适的纸张色调、优雅的层级缩进、章节编号的仪式感
 * - 选择交互: 勾选章节后底部浮出生成工具栏，如书签夹般自然
 * - 动效: 节点展开/折叠带微妙的弹性，选中时有墨水晕染般的高亮
 */

import { useState, useEffect, useCallback, useMemo } from "react";
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
  ChevronRight, ChevronDown, Check, Minus, Pencil,
  RotateCw, Sparkles, Layers, GitFork, HelpCircle, NotebookText,
  Loader2, RefreshCw,
} from "lucide-react";

// ═══════ 文档类型配置 ═══════

const DOC_TYPE_CONFIG: Record<string, {
  icon: typeof BookOpen;
  label: string;
  color: string;
  bg: string;
  border: string;
}> = {
  TEXTBOOK:   { icon: BookOpen,       label: "教材/教科书",   color: "text-amber-700 dark:text-amber-400",      bg: "bg-amber-50/60 dark:bg-amber-950/15",     border: "border-amber-200/60 dark:border-amber-800/30" },
  PAPER:      { icon: FileText,       label: "学术论文",      color: "text-blue-700 dark:text-blue-400",        bg: "bg-blue-50/60 dark:bg-blue-950/15",       border: "border-blue-200/60 dark:border-blue-800/30" },
  NOTE:       { icon: NotebookText,   label: "学习笔记",      color: "text-emerald-700 dark:text-emerald-400",  bg: "bg-emerald-50/60 dark:bg-emerald-950/15", border: "border-emerald-200/60 dark:border-emerald-800/30" },
  SLIDE:      { icon: Presentation,   label: "演示文稿",      color: "text-rose-700 dark:text-rose-400",        bg: "bg-rose-50/60 dark:bg-rose-950/15",       border: "border-rose-200/60 dark:border-rose-800/30" },
  MANUAL:     { icon: Wrench,         label: "技术手册",      color: "text-slate-700 dark:text-slate-400",      bg: "bg-slate-50/60 dark:bg-slate-950/15",     border: "border-slate-200/60 dark:border-slate-800/30" },
  OTHER:      { icon: FileText,       label: "其他文档",      color: "text-muted-foreground",                   bg: "bg-muted/30",                              border: "border-border/40" },
};

// ═══════ Props ═══════

interface Props {
  docId: number | null;
  docStatus: string | null;
  embedded?: boolean;
  onContextChange?: (hint: string) => void;
  /** 当用户点击"生成xxx"时回调，传入选中的 section ids */
  onGenerate?: (type: "note" | "mindmap" | "flashcard" | "quiz", selectedSections: string[]) => void;
}

// ═══════ 选择状态枚举 ═══════

type CheckState = "checked" | "indeterminate" | "unchecked";

// ═══════ 辅助函数 ═══════

/** 计算节点的选中状态 */
function getCheckState(
  node: OutlineSection,
  selected: Set<string>,
  totalLeafCount: { current: number },
  checkedLeafCount: { current: number },
): CheckState {
  if (node.children.length === 0) {
    totalLeafCount.current++;
    if (selected.has(node.id)) {
      checkedLeafCount.current++;
      return "checked";
    }
    return "unchecked";
  }
  const childStates = node.children.map((c) =>
    getCheckState(c, selected, totalLeafCount, checkedLeafCount)
  );
  const allChecked = childStates.every((s) => s === "checked");
  const noneChecked = childStates.every((s) => s === "unchecked");
  if (allChecked) return "checked";
  if (noneChecked) return "unchecked";
  return "indeterminate";
}

/** 收集节点及其所有后代的 id */
function collectIds(node: OutlineSection): string[] {
  return [node.id, ...node.children.flatMap(collectIds)];
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

  // 加载大纲
  useEffect(() => {
    if (!docId) { setLoading(false); return; }
    setLoading(true);
    getDocumentOutline(docId)
      .then((data) => {
        setOutline(data);
        // 默认全选 + 全展开
        const allIds = data.outline.sections.flatMap(collectIds);
        setSelected(new Set(allIds));
        const expandable = data.outline.sections
          .filter((s) => s.children.length > 0)
          .map((s) => s.id);
        setExpanded(new Set(expandable));
      })
      .catch(() => setOutline(null))
      .finally(() => setLoading(false));
  }, [docId]);

  // 上报上下文
  useEffect(() => {
    if (outline) {
      onContextChange?.(`用户在查看文档大纲（${outline.outline.docTypeLabel}，${outline.outline.sections.length} 章）`);
    }
  }, [outline, onContextChange]);

  // 统计选中数
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

  // 切换节点选中
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

  // 切换展开
  const toggleExpand = useCallback((id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  // 全选 / 反选
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

  // 编辑标题
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
          : { ...n, children: updateTitle(n.children) }
      );
    const updated = {
      ...outline,
      outline: { ...outline.outline, sections: updateTitle(outline.outline.sections) },
    };
    setOutline(updated);
    setEditingId(null);
    // 异步保存到后端
    if (docId) {
      updateDocumentOutline(docId, updated.outline).catch(() =>
        toast.error("保存编辑失败")
      );
    }
  }, [editingId, editTitle, outline, docId]);

  // 重新生成
  const handleRegenerate = useCallback(async () => {
    if (!docId) return;
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
    setGenerating(false);
  }, [docId]);

  // 获取选中的 section ids
  const selectedSectionIds = useMemo(() => {
    if (!outline) return [];
    return outline.outline.sections.flatMap(collectIds).filter((id) => selected.has(id));
  }, [outline, selected]);

  // ═══════ Loading ═══════
  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <Loader2 className="h-6 w-6 animate-spin" />
          <p className="text-sm">正在解析文档结构...</p>
        </div>
      </div>
    );
  }

  // ═══════ Empty ═══════
  if (!outline) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-5 text-muted-foreground">
        <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
          <BookOpen className="h-7 w-7 text-muted-foreground/40" />
        </div>
        <div className="text-center space-y-1.5">
          <p className="text-sm font-medium">暂无文档大纲</p>
          <p className="text-xs text-muted-foreground/50 max-w-xs">
            文档向量化完成后将自动生成大纲，也可手动触发
          </p>
        </div>
        <Button
          onClick={handleRegenerate}
          disabled={generating || !docId || docStatus !== "COMPLETED"}
          className="rounded-xl gap-2 h-10"
        >
          {generating ? (
            <RotateCw className="h-4 w-4 animate-spin" />
          ) : (
            <Sparkles className="h-4 w-4" />
          )}
          {generating ? "正在生成..." : "生成文档大纲"}
        </Button>
      </div>
    );
  }

  const docTypeConf = DOC_TYPE_CONFIG[outline.outline.docType] ?? DOC_TYPE_CONFIG.OTHER;
  const TypeIcon = docTypeConf.icon;

  // ═══════ 正常渲染 ═══════
  return (
    <div className="flex flex-col h-full">
      {/* ── 顶部栏 ── */}
      {!embedded && (
        <div className="flex items-center justify-between px-6 py-3 border-b bg-muted/20 shrink-0">
          <div className="flex items-center gap-2.5">
            <BookOpen className="h-4 w-4 text-muted-foreground" />
            <h2 className="text-sm font-medium text-foreground/80">文档大纲</h2>
            <span className={cn(
              "inline-flex items-center gap-1.5 rounded-lg px-2 py-0.5 text-[10px] font-semibold tracking-wide uppercase",
              docTypeConf.color, docTypeConf.bg, "border", docTypeConf.border,
            )}>
              <TypeIcon className="h-3 w-3" />
              {docTypeConf.label}
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              className="rounded-xl gap-1.5 h-8 text-xs"
              onClick={handleRegenerate}
              disabled={generating}
            >
              {generating ? (
                <RotateCw className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <RefreshCw className="h-3.5 w-3.5" />
              )}
              重新生成
            </Button>
          </div>
        </div>
      )}

      {/* ── 选择统计栏 ── */}
      <div className="flex items-center gap-2 px-4 py-2 border-b border-border/30 shrink-0">
        <span className="text-[11px] text-muted-foreground/50">
          已选 {checkedLeaves}/{totalLeaves} 节
        </span>
        <div className="flex-1" />
        <button
          type="button"
          onClick={selectAll}
          className="text-[11px] text-muted-foreground/50 hover:text-foreground transition-colors"
        >
          全选
        </button>
        <span className="text-[11px] text-muted-foreground/20">|</span>
        <button
          type="button"
          onClick={invertSelection}
          className="text-[11px] text-muted-foreground/50 hover:text-foreground transition-colors"
        >
          反选
        </button>
      </div>

      {/* ── 大纲树 ── */}
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-3xl mx-auto px-4 sm:px-6 py-4">
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
              animDelay={idx * 60}
            />
          ))}
        </div>
      </div>

      {/* ── 底部生成工具栏 ── */}
      {checkedLeaves > 0 && (
        <div className="shrink-0 border-t bg-background/80 backdrop-blur-md">
          <div className="max-w-3xl mx-auto px-4 sm:px-6 py-3">
            <div className="flex items-center justify-between gap-3">
              <p className="text-xs text-muted-foreground/60">
                将基于选中的 <span className="font-semibold text-foreground/80">{checkedLeaves}</span> 个章节生成内容
              </p>
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  className="rounded-lg gap-1.5 h-8 text-xs"
                  onClick={() => onGenerate?.("note", selectedSectionIds)}
                >
                  <NotebookText className="h-3.5 w-3.5" />
                  笔记
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  className="rounded-lg gap-1.5 h-8 text-xs"
                  onClick={() => onGenerate?.("mindmap", selectedSectionIds)}
                >
                  <GitFork className="h-3.5 w-3.5" />
                  导图
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  className="rounded-lg gap-1.5 h-8 text-xs"
                  onClick={() => onGenerate?.("flashcard", selectedSectionIds)}
                >
                  <Layers className="h-3.5 w-3.5" />
                  卡片
                </Button>
                <Button
                  size="sm"
                  className="rounded-lg gap-1.5 h-8 text-xs"
                  onClick={() => onGenerate?.("quiz", selectedSectionIds)}
                >
                  <HelpCircle className="h-3.5 w-3.5" />
                  测验
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
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
}: NodeProps) {
  const hasChildren = node.children.length > 0;
  const isExpanded = expanded.has(node.id);
  const isEditing = editingId === node.id;

  // 计算当前节点的选中状态
  const checkState = useMemo(() => {
    const t = { current: 0 }, c = { current: 0 };
    return getCheckState(node, selected, t, c);
  }, [node, selected]);

  // 章节编号（去掉 s 前缀）
  const sectionNum = node.id.replace(/^s/, "").replace(/-/g, ".");

  // 深度对应的样式
  const depthStyles = [
    // depth 0 (章)
    "text-[15px] font-semibold text-foreground/90",
    // depth 1 (节)
    "text-[13px] font-medium text-foreground/75",
    // depth 2 (小节)
    "text-[12px] text-foreground/60",
  ];

  const depthPadding = [
    "pl-0",
    "pl-6",
    "pl-12",
  ];

  return (
    <div
      className="animate-fade-in-up"
      style={{ animationDelay: `${animDelay}ms` }}
    >
      {/* 节点行 */}
      <div
        className={cn(
          "group flex items-center gap-2 py-1.5 px-2 rounded-lg transition-all duration-200",
          "hover:bg-muted/40",
          depthPadding[depth] ?? "pl-12",
        )}
      >
        {/* 展开/折叠箭头 */}
        {hasChildren ? (
          <button
            type="button"
            onClick={() => onToggleExpand(node.id)}
            className="flex h-5 w-5 items-center justify-center rounded text-muted-foreground/40 hover:text-foreground hover:bg-muted transition-all shrink-0"
          >
            {isExpanded ? (
              <ChevronDown className="h-3.5 w-3.5" />
            ) : (
              <ChevronRight className="h-3.5 w-3.5" />
            )}
          </button>
        ) : (
          <span className="w-5 shrink-0" />
        )}

        {/* 复选框 */}
        <button
          type="button"
          onClick={() => onToggleSelect(node)}
          className={cn(
            "flex h-[18px] w-[18px] items-center justify-center rounded-[4px] border transition-all shrink-0",
            checkState === "checked"
              ? "bg-primary border-primary text-primary-foreground"
              : checkState === "indeterminate"
                ? "bg-primary/20 border-primary/50 text-primary"
                : "border-muted-foreground/25 hover:border-primary/40",
          )}
        >
          {checkState === "checked" && <Check className="h-3 w-3" />}
          {checkState === "indeterminate" && <Minus className="h-3 w-3" />}
        </button>

        {/* 章节编号 */}
        <span className="text-[10px] font-mono text-muted-foreground/30 w-8 shrink-0 tabular-nums">
          {sectionNum}
        </span>

        {/* 标题 */}
        {isEditing ? (
          <div className="flex items-center gap-1 flex-1 min-w-0">
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
              className="flex-1 rounded border border-primary/30 bg-background px-2 py-0.5 text-xs outline-none focus:ring-1 focus:ring-primary/30"
            />
          </div>
        ) : (
          <span
            className={cn(
              "flex-1 min-w-0 truncate cursor-default",
              depthStyles[depth] ?? depthStyles[2],
            )}
            onDoubleClick={() => onStartEdit(node.id, node.title)}
            title={node.title}
          >
            {node.title}
          </span>
        )}

        {/* 编辑按钮 (hover) */}
        {!isEditing && (
          <button
            type="button"
            onClick={() => onStartEdit(node.id, node.title)}
            className="opacity-0 group-hover:opacity-60 hover:!opacity-100 p-0.5 rounded hover:bg-muted text-muted-foreground transition-all shrink-0"
            title="编辑标题"
          >
            <Pencil className="h-3 w-3" />
          </button>
        )}

        {/* chunk 范围标签 */}
        {node.startChunk != null && node.endChunk != null && (
          <span className="text-[9px] text-muted-foreground/25 font-mono shrink-0">
            [{node.startChunk}-{node.endChunk}]
          </span>
        )}
      </div>

      {/* 子节点（展开时显示） */}
      {hasChildren && isExpanded && (
        <div className="ml-2 border-l border-muted-foreground/10">
          {node.children.map((child, i) => (
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
              animDelay={i * 40}
            />
          ))}
        </div>
      )}
    </div>
  );
}
