"use client";

import { useState, ChangeEvent, useRef, useCallback, useEffect, useMemo } from "react";
import Image from "next/image";
import { cn } from "@/lib/utils";
import { ScrollArea } from "@/components/ui/scroll-area";
import { ThemeToggle } from "@/components/theme-toggle";
import { toast } from "sonner";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogCancel,
  AlertDialogAction,
} from "@/components/ui/alert-dialog";
import {
  Upload,
  FileText,
  PanelLeftClose,
  PanelLeft,
  X,
  Search,
  RotateCw,
  Pencil,
  Folder,
  FolderOpen,
  Plus,
  ChevronRight,
  GripVertical,
  Check,
  Clock,
  FileUp,
  ArrowUp,
  ArrowDown,
} from "lucide-react";

export interface UploadedDoc {
  id: string;
  sessionId: number;
  name: string;
  size: number;
  status: "uploading" | "processing" | "done" | "error";
  chunks?: number;
  fileType?: string | null;
  pageCount?: number | null;
  folderId?: number | null;
}

export interface FolderInfo {
  id: number;
  name: string;
  color: string;
  docCount: number;
  sortOrder?: number;
}

interface AppSidebarProps {
  documents: UploadedDoc[];
  folders: FolderInfo[];
  activeSessionId: number | null;
  onSelectSession: (sessionId: number) => void;
  onUpload: (file: File | FileList) => Promise<void>;
  onDeleteDocument: (sessionId: number) => void;
  onRetryDocument?: (sessionId: number) => void;
  onRenameDocument?: (sessionId: number, newTitle: string) => void;
  onCreateFolder?: (name: string, color: string) => Promise<void>;
  onDeleteFolder?: (folderId: number) => Promise<void>;
  onRenameFolder?: (folderId: number, name: string) => Promise<void>;
  onUpdateFolderColor?: (folderId: number, color: string) => Promise<void>;
  onMoveDocument?: (sessionId: number, folderId: number | null) => Promise<void>;
  onReorderFolders?: (orderedIds: number[]) => Promise<void>;
  collapsed: boolean;
  onToggleCollapse: () => void;
  loading?: boolean;
}

/** 预定义文件夹颜色 */
const FOLDER_COLORS = [
  "#6366f1", // indigo
  "#f43f5e", // rose
  "#10b981", // emerald
  "#f59e0b", // amber
  "#3b82f6", // blue
  "#8b5cf6", // violet
  "#ec4899", // pink
  "#14b8a6", // teal
  "#f97316", // orange
  "#64748b", // slate
];

/** 长按触发阈值 (ms) */
const LONG_PRESS_MS = 500;

/** 文件类型对应的颜色 (collapsed icon bar 用) */
const FILE_TYPE_COLORS: Record<string, string> = {
  pdf: "#f87171",
  xlsx: "#34d399",
  csv: "#34d399",
  pptx: "#fb923c",
  ppt: "#fb923c",
  md: "#38bdf8",
};

export function AppSidebar({
  documents,
  folders,
  activeSessionId,
  onSelectSession,
  onUpload,
  onDeleteDocument,
  onRetryDocument,
  onRenameDocument,
  onCreateFolder,
  onDeleteFolder,
  onRenameFolder,
  onUpdateFolderColor,
  onMoveDocument,
  onReorderFolders,
  collapsed,
  onToggleCollapse,
  loading = false,
}: AppSidebarProps) {
  const [dragging, setDragging] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);
  const editInputRef = useRef<HTMLInputElement>(null);

  // Folder state
  const [expandedFolders, setExpandedFolders] = useState<Set<number>>(new Set());
  const [showCreateFolder, setShowCreateFolder] = useState(false);
  const [newFolderName, setNewFolderName] = useState("");
  const [newFolderColor, setNewFolderColor] = useState(FOLDER_COLORS[0]);
  const [editingFolderId, setEditingFolderId] = useState<number | null>(null);
  const [editFolderValue, setEditFolderValue] = useState("");
  const [editFolderColorId, setEditFolderColorId] = useState<number | null>(null);
  const editFolderInputRef = useRef<HTMLInputElement>(null);
  const blurCommitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Move document bottom sheet
  const [moveTarget, setMoveTarget] = useState<UploadedDoc | null>(null);

  // Shared confirm dialog state
  const [confirmState, setConfirmState] = useState<{ open: boolean; title: string; description: string; onConfirm: () => void }>({ open: false, title: "", description: "", onConfirm: () => {} });

  // Long-press tracking
  const longPressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const longPressTriggered = useRef(false);

  // Swipe-to-close tracking (mobile)
  const touchStartX = useRef<number | null>(null);

  // Drag and drop state
  const [dragDocId, setDragDocId] = useState<string | null>(null);
  const dragDocIdRef = useRef<string | null>(null);
  const [dropFolderId, setDropFolderId] = useState<number | null | undefined>(undefined);

  // Pointer-based drag-and-drop (more reliable than HTML5 drag)
  const dragStartPos = useRef<{ x: number; y: number } | null>(null);
  const dragGhostRef = useRef<HTMLDivElement | null>(null);
  const dragActive = useRef(false);
  const dragRafId = useRef<number>(0);
  const FOLDER_HEADER_ATTR = "data-folder-id";

  // Recent docs tracking (localStorage)
  const RECENT_KEY = "edumerge_recent_docs";
  const RECENT_MAX = 5;

  const getRecentIds = useCallback((): string[] => {
    try {
      return JSON.parse(localStorage.getItem(RECENT_KEY) ?? "[]");
    } catch {
      return [];
    }
  }, []);

  const trackRecent = useCallback((docId: string) => {
    const ids = getRecentIds().filter((id) => id !== docId);
    ids.unshift(docId);
    localStorage.setItem(RECENT_KEY, JSON.stringify(ids.slice(0, RECENT_MAX)));
  }, [getRecentIds]);

  const recentDocs = useMemo(() => {
    if (searchQuery) return [];
    const ids = getRecentIds();
    return ids
      .map((id) => documents.find((d) => d.id === id))
      .filter((d): d is UploadedDoc => !!d && d.sessionId > 0)
      .slice(0, RECENT_MAX);
  }, [documents, searchQuery, getRecentIds]);

  const cleanupDrag = useCallback(() => {
    dragActive.current = false;
    dragStartPos.current = null;
    dragDocIdRef.current = null;
    cancelAnimationFrame(dragRafId.current);
    setDragDocId(null);
    setDropFolderId(undefined);
    if (dragGhostRef.current) {
      dragGhostRef.current.remove();
      dragGhostRef.current = null;
    }
    document.removeEventListener("pointermove", handlePointerMove);
    document.removeEventListener("pointerup", handlePointerUp);
  }, []);

  const handlePointerMove = useCallback((e: PointerEvent) => {
    if (!dragStartPos.current) return;
    const dx = e.clientX - dragStartPos.current.x;
    const dy = e.clientY - dragStartPos.current.y;

    // Start drag after 5px threshold
    if (!dragActive.current) {
      if (Math.abs(dx) + Math.abs(dy) < 5) return;
      dragActive.current = true;
      setDragDocId(dragDocIdRef.current);

      // Create ghost
      const ghost = document.createElement("div");
      ghost.className = "fixed z-[9999] pointer-events-none rounded-lg bg-primary/10 border border-primary/30 px-3 py-1.5 text-xs font-medium text-primary shadow-lg backdrop-blur-sm";
      ghost.textContent = documents.find((d) => d.id === dragDocIdRef.current)?.name ?? "";
      ghost.style.left = `${e.clientX + 12}px`;
      ghost.style.top = `${e.clientY - 12}px`;
      document.body.appendChild(ghost);
      dragGhostRef.current = ghost;
    }

    // Throttle visual updates via rAF
    cancelAnimationFrame(dragRafId.current);
    dragRafId.current = requestAnimationFrame(() => {
      if (dragGhostRef.current) {
        dragGhostRef.current.style.left = `${e.clientX + 12}px`;
        dragGhostRef.current.style.top = `${e.clientY - 12}px`;
      }
      const el = document.elementFromPoint(e.clientX, e.clientY);
      const folderHeader = el?.closest(`[${FOLDER_HEADER_ATTR}]`);
      const targetFolderId = folderHeader?.getAttribute(FOLDER_HEADER_ATTR);
      if (targetFolderId !== undefined && targetFolderId !== null) {
        setDropFolderId(targetFolderId === "root" ? null : Number(targetFolderId));
      } else {
        setDropFolderId(undefined);
      }
    });
  }, [documents]);

  const handlePointerUp = useCallback(async (e: PointerEvent) => {
    if (!dragActive.current) { cleanupDrag(); return; }

    const currentDragId = dragDocIdRef.current;
    cleanupDrag();
    if (!currentDragId || !onMoveDocument) return;

    // Find drop target
    const el = document.elementFromPoint(e.clientX, e.clientY);
    const folderHeader = el?.closest(`[${FOLDER_HEADER_ATTR}]`);
    const targetFolderId = folderHeader?.getAttribute(FOLDER_HEADER_ATTR);
    if (targetFolderId === undefined || targetFolderId === null) return;

    const folderId = targetFolderId === "root" ? null : Number(targetFolderId);
    const doc = documents.find((d) => d.id === currentDragId);
    if (!doc || doc.folderId === folderId) return;

    try {
      await onMoveDocument(doc.sessionId, folderId);
      toast.success(folderId ? "已移入文件夹" : "已移出文件夹");
    } catch {
      toast.error("移动失败");
    }
  }, [documents, onMoveDocument, cleanupDrag]);

  const handlePointerDown = useCallback((e: React.PointerEvent, doc: UploadedDoc) => {
    if (!onMoveDocument) return;
    e.preventDefault();
    e.stopPropagation();
    dragStartPos.current = { x: e.clientX, y: e.clientY };
    dragDocIdRef.current = doc.id;
    document.addEventListener("pointermove", handlePointerMove);
    document.addEventListener("pointerup", handlePointerUp);
  }, [onMoveDocument, handlePointerMove, handlePointerUp]);

  // Toggle folder expand/collapse
  const toggleFolder = useCallback((folderId: number) => {
    setExpandedFolders((prev) => {
      const next = new Set(prev);
      if (next.has(folderId)) next.delete(folderId);
      else next.add(folderId);
      return next;
    });
  }, []);

  // Folders default to collapsed — user clicks to expand

  const handleFile = (file: File | FileList | null) => {
    if (!file) return;
    if (file instanceof FileList) {
      if (file.length > 0) onUpload(file);
      return;
    }
    const extension = file.name.split(".").pop()?.toLowerCase();
    if (extension && ["pdf", "doc", "docx", "ppt", "pptx", "txt", "md", "html", "htm", "xlsx", "csv", "jpg", "jpeg", "png", "bmp", "tiff"].includes(extension)) {
      onUpload(file);
    } else {
      toast.error(`暂不支持 .${extension ?? "?"} 格式，支持 PDF、DOCX、PPTX、TXT、图片等`);
    }
  };

  const startRename = (doc: UploadedDoc) => {
    setEditingId(doc.id);
    const ext = doc.name.lastIndexOf(".");
    setEditValue(ext > 0 ? doc.name.substring(0, ext) : doc.name);
    setTimeout(() => editInputRef.current?.select(), 0);
  };

  const commitRename = (doc: UploadedDoc) => {
    const trimmed = editValue.trim();
    if (!trimmed) { setEditingId(null); return; }
    const ext = doc.name.lastIndexOf(".");
    const extension = ext > 0 ? doc.name.substring(ext) : "";
    const newName = trimmed + extension;
    if (newName !== doc.name && onRenameDocument) {
      onRenameDocument(doc.sessionId, newName);
    }
    setEditingId(null);
  };

  // Filter documents
  const filteredDocs = useMemo(() => {
    if (!searchQuery) return documents;
    return documents.filter((doc) =>
      doc.name.toLowerCase().includes(searchQuery.toLowerCase()),
    );
  }, [documents, searchQuery]);

  // Group documents by folder
  const { folderGroups, ungroupedDocs } = useMemo(() => {
    const groups = new Map<number, UploadedDoc[]>();
    const ungrouped: UploadedDoc[] = [];

    for (const doc of filteredDocs) {
      if (doc.folderId != null) {
        const list = groups.get(doc.folderId) ?? [];
        list.push(doc);
        groups.set(doc.folderId, list);
      } else {
        ungrouped.push(doc);
      }
    }
    return { folderGroups: groups, ungroupedDocs: ungrouped };
  }, [filteredDocs]);

  // Long-press handlers for documents (mobile move)
  const startLongPress = useCallback((doc: UploadedDoc) => {
    longPressTriggered.current = false;
    longPressTimer.current = setTimeout(() => {
      longPressTriggered.current = true;
      if (onMoveDocument) setMoveTarget(doc);
    }, LONG_PRESS_MS);
  }, [onMoveDocument]);

  const cancelLongPress = useCallback(() => {
    if (longPressTimer.current) {
      clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  }, []);

  // Create folder
  const handleCreateFolder = async () => {
    if (!newFolderName.trim() || !onCreateFolder) return;
    try {
      await onCreateFolder(newFolderName.trim(), newFolderColor);
      setNewFolderName("");
      setShowCreateFolder(false);
      toast.success("文件夹已创建");
    } catch {
      toast.error("创建失败");
    }
  };

  // Rename folder
  const commitRenameFolder = async (folderId: number) => {
    const trimmed = editFolderValue.trim();
    setEditingFolderId(null);
    if (!trimmed || !onRenameFolder) return;
    try {
      await onRenameFolder(folderId, trimmed);
    } catch {
      toast.error("重命名失败");
    }
  };

  // Reorder folder (move up/down)
  const handleReorderFolder = useCallback(async (folderId: number, direction: "up" | "down") => {
    if (!onReorderFolders) return;
    const sorted = [...folders].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
    const idx = sorted.findIndex((f) => f.id === folderId);
    if (idx < 0) return;
    const swapIdx = direction === "up" ? idx - 1 : idx + 1;
    if (swapIdx < 0 || swapIdx >= sorted.length) return;
    const newOrder = [...sorted];
    [newOrder[idx], newOrder[swapIdx]] = [newOrder[swapIdx], newOrder[idx]];
    try {
      await onReorderFolders(newOrder.map((f) => f.id));
    } catch {
      toast.error("排序失败");
    }
  }, [folders, onReorderFolders]);

  // Delete folder
  const handleDeleteFolder = (folderId: number) => {
    if (!onDeleteFolder) return;
    const folder = folders.find((f) => f.id === folderId);
    setConfirmState({
      open: true,
      title: `删除文件夹「${folder?.name ?? ""}」？`,
      description: "文件夹内的文档将回到根目录，不会被删除。",
      onConfirm: async () => {
        try {
          await onDeleteFolder(folderId);
          toast.success("文件夹已删除");
        } catch {
          toast.error("删除失败");
        }
      },
    });
  };

  // Dismiss move sheet and reset long-press flag
  const dismissMoveSheet = useCallback(() => {
    setMoveTarget(null);
    longPressTriggered.current = false;
  }, []);

  // Move document from bottom sheet
  const handleMoveToFolder = async (folderId: number | null) => {
    if (!moveTarget || !onMoveDocument) return;
    try {
      await onMoveDocument(moveTarget.sessionId, folderId);
      toast.success(folderId ? "已移入文件夹" : "已移出文件夹");
    } catch {
      toast.error("移动失败");
    }
    dismissMoveSheet();
  };

  // Render a single document item
  const renderDocItem = (doc: UploadedDoc) => {
    const isActive = doc.sessionId > 0 && activeSessionId === doc.sessionId;
    return (
      <div
        key={doc.id}
        onClick={() => {
          if (longPressTriggered.current) return;
          if (doc.sessionId > 0) {
            trackRecent(doc.id);
            onSelectSession(doc.sessionId);
          }
        }}
        onTouchStart={() => startLongPress(doc)}
        onTouchEnd={cancelLongPress}
        onTouchMove={cancelLongPress}
        className={cn(
          "group flex items-center gap-2 rounded-lg px-3 py-1 max-md:py-2 text-sm select-none",
          doc.sessionId > 0 && "cursor-pointer",
          isActive
            ? "bg-primary/10 dark:bg-primary/15 text-foreground"
            : "text-muted-foreground hover:bg-muted/60 dark:hover:bg-muted/40 active:bg-muted/60 dark:active:bg-muted/40",
          dragDocId === doc.id && "opacity-40",
        )}
      >
        {onMoveDocument && (
          <GripVertical
            className="h-3 w-3 shrink-0 text-muted-foreground/30 cursor-grab active:cursor-grabbing"
            onPointerDown={(e) => handlePointerDown(e, doc)}
          />
        )}
        <FileText
          className={cn(
            "h-3 w-3 shrink-0",
            isActive
              ? "text-primary"
              : doc.fileType === "pdf"
                ? "text-red-400"
                : doc.fileType === "xlsx" || doc.fileType === "csv"
                  ? "text-emerald-400"
                  : doc.fileType === "pptx" || doc.fileType === "ppt"
                    ? "text-orange-400"
                    : doc.fileType === "md"
                      ? "text-sky-400"
                      : "text-muted-foreground/60",
          )}
        />
        <div className="flex-1 min-w-0">
          {editingId === doc.id ? (
            <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
              <input
                ref={editInputRef}
                value={editValue}
                onChange={(e) => setEditValue(e.target.value)}
                onBlur={() => commitRename(doc)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") commitRename(doc);
                  if (e.key === "Escape") setEditingId(null);
                }}
                onClick={(e) => e.stopPropagation()}
                className="flex-1 min-w-0 bg-background border border-primary/30 rounded px-2 py-1 max-md:py-1.5 text-sm outline-none"
              />
              <button
                type="button"
                onMouseDown={(e) => { e.preventDefault(); commitRename(doc); }}
                className="shrink-0 min-w-[44px] min-h-[44px] flex items-center justify-center rounded text-emerald-600 hover:bg-emerald-500/10"
                title="确认"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
              </button>
              <button
                type="button"
                onMouseDown={(e) => { e.preventDefault(); setEditingId(null); }}
                className="shrink-0 h-5 w-5 flex items-center justify-center rounded text-muted-foreground hover:bg-muted"
                title="取消"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
          ) : (
            <>
              <span className="block truncate">{doc.name}</span>
              {doc.status === "done" && doc.pageCount != null && doc.pageCount > 0 && (
                <span className="text-[11px] text-muted-foreground/60">
                  {doc.pageCount}
                  {doc.fileType === "pptx" || doc.fileType === "ppt" ? " 张幻灯片" : doc.fileType === "xlsx" ? " 个工作表" : doc.fileType === "csv" ? " 条记录" : " 页"}
                </span>
              )}
            </>
          )}
        </div>
        {doc.status === "error" && onRetryDocument && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onRetryDocument(doc.sessionId);
            }}
            className="shrink-0 min-w-[44px] min-h-[44px] flex items-center justify-center rounded active:bg-primary/10 hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all"
            title="重新处理"
          >
            <RotateCw className="h-3 w-3" />
          </button>
        )}
        {onRenameDocument && editingId !== doc.id && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              startRename(doc);
            }}
            className="hidden group-hover:flex max-md:flex shrink-0 min-w-[44px] min-h-[44px] items-center justify-center rounded active:bg-primary/10 hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all"
            title="重命名"
          >
            <Pencil className="h-3 w-3" />
          </button>
        )}
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            setConfirmState({
              open: true,
              title: `删除「${doc.name}」？`,
              description: "此操作将删除文档及所有关联的闪卡、测验、笔记等数据，不可恢复。",
              onConfirm: () => onDeleteDocument(doc.sessionId),
            });
          }}
          className="hidden group-hover:flex max-md:flex shrink-0 min-w-[44px] min-h-[44px] items-center justify-center rounded active:bg-destructive/10 hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-all"
          title="删除文档"
        >
          <X className="h-3 w-3" />
        </button>
        {doc.status === "done" ? (
          <span className="inline-block h-2 w-2 rounded-full shrink-0 bg-emerald-400 shadow-[0_0_6px] shadow-emerald-400/30 ring-1 ring-white/20" />
        ) : (
          <span className={cn(
            "inline-flex items-center gap-1 shrink-0 text-[10px] font-medium",
            doc.status === "error" ? "text-destructive" : doc.status === "processing" ? "text-blue-400" : "text-amber-400",
          )}>
            <span className={cn(
              "h-2 w-2 rounded-full animate-pulse",
              doc.status === "error" ? "bg-destructive" : doc.status === "processing" ? "bg-blue-400" : "bg-amber-400",
            )} />
            {doc.status === "error" ? "失败" : doc.status === "processing" ? "处理中" : "上传中"}
          </span>
        )}
      </div>
    );
  };

  // Render a folder section
  const renderFolderSection = (folder: FolderInfo) => {
    const isExpanded = expandedFolders.has(folder.id);
    const docs = folderGroups.get(folder.id) ?? [];
    const isDropping = dropFolderId === folder.id;

    return (
      <div key={folder.id} className="mb-0.5">
        {/* Folder header */}
        <div
          {...{ [FOLDER_HEADER_ATTR]: String(folder.id) }}
          onClick={() => toggleFolder(folder.id)}
          className={cn(
            "group flex items-center gap-2 rounded-lg px-3 py-1 max-md:py-2 text-sm cursor-pointer transition-all duration-200 select-none",
            isDropping
              ? "bg-primary/15 ring-1 ring-primary/40 text-foreground"
              : "text-muted-foreground hover:bg-muted/60 dark:hover:bg-muted/40 active:bg-muted/60 dark:active:bg-muted/40",
          )}
        >
          <ChevronRight
            className={cn(
              "h-3 w-3 shrink-0 transition-transform duration-200",
              isExpanded && "rotate-90",
            )}
          />
          {isExpanded ? (
            <FolderOpen className="h-3.5 w-3.5 shrink-0" style={{ color: folder.color }} />
          ) : (
            <Folder className="h-3.5 w-3.5 shrink-0" style={{ color: folder.color }} />
          )}
          {editingFolderId === folder.id ? (
            <>
              <input
                ref={editFolderInputRef}
                value={editFolderValue}
                onChange={(e) => setEditFolderValue(e.target.value)}
                onBlur={() => {
                  blurCommitTimerRef.current = setTimeout(() => commitRenameFolder(folder.id), 200);
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter") commitRenameFolder(folder.id);
                  if (e.key === "Escape") setEditingFolderId(null);
                }}
                onClick={(e) => {
                  e.stopPropagation();
                  if (blurCommitTimerRef.current) {
                    clearTimeout(blurCommitTimerRef.current);
                    blurCommitTimerRef.current = null;
                  }
                }}
                className="flex-1 min-w-0 bg-background border border-primary/30 rounded px-2 py-1 max-md:py-1.5 text-sm outline-none"
              />
              <button
                type="button"
                onMouseDown={(e) => {
                  e.preventDefault();
                  if (blurCommitTimerRef.current) {
                    clearTimeout(blurCommitTimerRef.current);
                    blurCommitTimerRef.current = null;
                  }
                  commitRenameFolder(folder.id);
                }}
                className="shrink-0 min-w-[44px] min-h-[44px] flex items-center justify-center rounded active:bg-primary/10 text-primary"
                title="确认重命名"
              >
                <Check className="h-3.5 w-3.5" />
              </button>
            </>
          ) : (
            <span className="flex-1 min-w-0 truncate font-medium">{folder.name}</span>
          )}
          <span className="text-[11px] text-muted-foreground/60 tabular-nums">
            {docs.length}
          </span>

          {/* Color picker trigger */}
          {onUpdateFolderColor && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setEditFolderColorId(editFolderColorId === folder.id ? null : folder.id);
              }}
              className="shrink-0 min-w-[44px] min-h-[44px] flex items-center justify-center rounded-full hover:ring-primary/50 active:ring-primary/50 transition-all cursor-pointer"
              title="更换颜色"
            >
              <span
                className="h-3 w-3 rounded-full ring-1 ring-white/20"
                style={{ backgroundColor: folder.color }}
              />
            </button>
          )}

          {/* Reorder buttons */}
          {onReorderFolders && (
            <>
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); handleReorderFolder(folder.id, "up"); }}
                className="hidden group-hover:flex max-md:flex shrink-0 min-w-[44px] min-h-[44px] items-center justify-center rounded active:bg-primary/10 hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all"
                title="上移"
              >
                <ArrowUp className="h-3 w-3" />
              </button>
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); handleReorderFolder(folder.id, "down"); }}
                className="hidden group-hover:flex max-md:flex shrink-0 min-w-[44px] min-h-[44px] items-center justify-center rounded active:bg-primary/10 hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all"
                title="下移"
              >
                <ArrowDown className="h-3 w-3" />
              </button>
            </>
          )}

          {/* Rename button */}
          {onRenameFolder && editingFolderId !== folder.id && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setEditingFolderId(folder.id);
                setEditFolderValue(folder.name);
                setTimeout(() => editFolderInputRef.current?.select(), 0);
              }}
              className="hidden group-hover:flex max-md:flex shrink-0 min-w-[44px] min-h-[44px] items-center justify-center rounded active:bg-primary/10 hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all"
              title="重命名"
            >
              <Pencil className="h-3 w-3" />
            </button>
          )}

          {/* Delete button */}
          {onDeleteFolder && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                handleDeleteFolder(folder.id);
              }}
              className="hidden group-hover:flex max-md:flex shrink-0 min-w-[44px] min-h-[44px] items-center justify-center rounded active:bg-destructive/10 hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-all"
              title="删除文件夹"
            >
              <X className="h-3 w-3" />
            </button>
          )}
        </div>

        {/* Color picker dropdown */}
        {editFolderColorId === folder.id && onUpdateFolderColor && (
          <div className="flex flex-wrap gap-1.5 px-6 py-1.5">
            {FOLDER_COLORS.map((c) => (
              <button
                key={c}
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onUpdateFolderColor(folder.id, c);
                  setEditFolderColorId(null);
                }}
                className={cn(
                  "h-7 w-7 max-md:h-8 max-md:w-8 rounded-full ring-1 ring-white/20 hover:ring-primary/50 hover:scale-110 transition-all",
                  folder.color === c && "ring-2 ring-primary scale-110",
                )}
                style={{ backgroundColor: c }}
              />
            ))}
          </div>
        )}

        {/* Documents in folder */}
        {isExpanded && docs.length > 0 && (
          <div className="ml-3 pl-2 border-l border-white/10 space-y-0.5">
            {docs.map(renderDocItem)}
          </div>
        )}
      </div>
    );
  };

  // First-use gesture hint — SSR-safe
  const [showGestureHint, setShowGestureHint] = useState(false);
  useEffect(() => {
    if (documents.length > 0 && !localStorage.getItem("edumerge_gesture_hint_dismissed")) {
      setShowGestureHint(true);
    }
  }, [documents.length]);

  const dismissGestureHint = useCallback(() => {
    setShowGestureHint(false);
    try { localStorage.setItem("edumerge_gesture_hint_dismissed", "1"); } catch { /* ignore */ }
  }, []);

  const hasAnyFolders = folders.length > 0;

  return (
    <>
      {/* Mobile backdrop */}
      {!collapsed && (
        <div
          className="fixed inset-0 z-40 bg-black/40 backdrop-blur-sm md:hidden"
          onClick={onToggleCollapse}
        />
      )}

      {/* Shared file input (used by both collapsed & expanded upload) */}
      <input
        ref={fileRef}
        type="file"
        aria-label="上传学习资料"
        multiple
        accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,.md,.html,.htm,.xlsx,.csv,.jpg,.jpeg,.png,.bmp,.tiff,image/*,application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation,text/markdown,text/html,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
        onChange={(e: ChangeEvent<HTMLInputElement>) => {
          const files = e.target.files;
          if (files && files.length > 0) handleFile(files.length === 1 ? files[0] : files);
          if (fileRef.current) fileRef.current.value = "";
        }}
        className="hidden"
      />

      <aside
        className={cn(
          "relative flex h-full flex-col shrink-0 overflow-hidden border-r border-border",
          "bg-white/70 dark:bg-slate-900/80 backdrop-blur-xl",
          "transition-all duration-300 ease-in-out",
          // Desktop: inline sidebar
          "hidden md:flex",
          collapsed ? "w-[64px]" : "w-[260px]",
          // Mobile: overlay sidebar
          "max-md:fixed max-md:inset-y-0 max-md:left-0 max-md:z-50 max-md:flex max-md:w-[260px]",
          collapsed ? "max-md:-translate-x-full" : "max-md:translate-x-0",
        )}
        onTouchStart={(e) => { touchStartX.current = e.touches[0].clientX; }}
        onTouchEnd={(e) => {
          if (touchStartX.current != null) {
            const dx = e.changedTouches[0].clientX - touchStartX.current;
            if (dx < -60) onToggleCollapse();
            touchStartX.current = null;
          }
        }}
      >
      {/* 折叠按钮 */}
      <button
        type="button"
        onClick={onToggleCollapse}
        className={cn(
          "absolute z-50 flex h-9 w-9 items-center justify-center rounded-full",
          "bg-white dark:bg-slate-800 border border-border shadow-lg",
          "hover:shadow-xl hover:scale-110",
          "transition-all duration-300",
          // Desktop: right edge, centered vertically
          "-right-3.5 bottom-20 max-md:bottom-auto",
          // Mobile: top-right corner
          "max-md:right-2 max-md:top-2",
        )}
        title={collapsed ? "展开侧边栏" : "收起侧边栏"}
      >
        {collapsed ? (
          <PanelLeft className="h-4 w-4" />
        ) : (
          <PanelLeftClose className="h-4 w-4" />
        )}
      </button>

      {/* Logo 区域 */}
      <div
        className={cn(
          "flex items-center gap-3 border-b border-white/10 transition-all duration-300",
          collapsed ? "flex-col justify-center px-2 py-3" : "px-4 py-4",
        )}
      >
        <div
          className={cn(
            "flex items-center justify-center rounded-2xl overflow-hidden shrink-0 logo-filter transition-all duration-300",
            collapsed ? "h-10 w-10" : "h-16 w-16",
          )}
        >
          <Image
            src="/logo_converted.svg"
            alt="EduMerge"
            width={116}
            height={117}
            priority
            className="h-full w-auto"
          />
        </div>
        {!collapsed && (
          <span className="text-[15px] font-bold tracking-tight text-foreground/90 select-none">
            EduMerge
          </span>
        )}
      </div>

      {/* ── Collapsed: icon quick-access bar ── */}
      {collapsed && (
        <div className="flex-1 min-h-0 flex flex-col items-center gap-1 pt-2 overflow-y-auto overflow-x-hidden">
          {/* Upload icon button */}
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground hover:bg-white/10 hover:text-foreground transition-all"
            title="上传资料"
          >
            <Upload className="h-4 w-4" />
          </button>
          <div className="w-6 border-t border-white/10 my-1" />

          {/* Document icon list */}
          {documents.map((doc) => {
            const isActive = doc.sessionId > 0 && activeSessionId === doc.sessionId;
            const label = doc.name.replace(/\.[^.]+$/, "").slice(0, 2);
            return (
              <button
                key={doc.id}
                type="button"
                onClick={() => {
                  if (doc.sessionId > 0) {
                    trackRecent(doc.id);
                    onSelectSession(doc.sessionId);
                  }
                }}
                title={doc.name}
                className={cn(
                  "flex flex-col items-center gap-0.5 rounded-lg transition-all px-1 py-1",
                  isActive
                    ? "bg-primary/15 text-primary"
                    : "text-muted-foreground/60 hover:bg-muted/60 hover:text-foreground",
                )}
              >
                <FileText
                  className="h-4 w-4 shrink-0"
                  style={{ color: FILE_TYPE_COLORS[doc.fileType ?? ""] }}
                />
                <span className="text-[10px] leading-none truncate max-w-[44px]">{label}</span>
              </button>
            );
          })}
          {documents.length === 0 && (
            <span className="text-[11px] text-muted-foreground/50 px-1 text-center leading-tight">
              暂无
            </span>
          )}
        </div>
      )}

      {/* ── Expanded: upload + search + doc list ── */}
      {!collapsed && (
        <div className="flex-1 min-h-0 flex flex-col overflow-hidden">
          <div className="px-3 pt-1 pb-2 shrink-0">
            <div
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragging(false);
              }}
              onDrop={(e) => {
                e.preventDefault();
                setDragging(false);
                handleFile(e.dataTransfer.files[0] ?? null);
              }}
              onClick={() => fileRef.current?.click()}
              className={cn(
                "group flex items-center justify-center gap-2 rounded-xl border-2 border-dashed p-2.5 text-[11px] font-medium cursor-pointer transition-all",
                "backdrop-blur-sm",
                dragging
                  ? "border-primary bg-primary/10 text-primary scale-[1.02]"
                  : "border-white/20 text-muted-foreground hover:border-white/40 hover:text-foreground hover:bg-white/5",
              )}
            >
              <Upload
                className={cn(
                  "h-3.5 w-3.5 transition-colors",
                  dragging && "text-primary",
                )}
              />
              上传资料
            </div>
            <p className="mt-1.5 text-center text-[11px] text-muted-foreground/40 leading-tight">
              PDF / DOCX / PPTX / TXT / 图片 / Markdown / HTML / Excel / CSV
            </p>
          </div>

          {/* Search + doc count + Create Folder */}
          <div className="px-3 pb-1 flex items-center gap-1.5 shrink-0">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3 w-3 text-muted-foreground/40" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="搜索文档..."
                className="w-full rounded-lg border border-border/50 bg-muted/30 pl-7 pr-2 py-1.5 text-[11px] text-foreground placeholder:text-muted-foreground/40 outline-none focus:border-primary/30 focus:bg-muted/50 transition-all"
              />
            </div>
            <span className="text-[11px] text-muted-foreground/60 tabular-nums shrink-0">
              {documents.length} 份
            </span>
            {onCreateFolder && (
              <button
                type="button"
                onClick={() => setShowCreateFolder(!showCreateFolder)}
                className={cn(
                  "flex h-7 w-7 items-center justify-center rounded-lg transition-all shrink-0",
                  showCreateFolder
                    ? "bg-primary/15 text-primary"
                    : "text-muted-foreground hover:bg-white/10 hover:text-foreground",
                )}
                title="新建文件夹"
              >
                <Plus className="h-3.5 w-3.5" />
              </button>
            )}
          </div>

          {/* Create folder form */}
          {showCreateFolder && onCreateFolder && (
            <div className="px-3 pb-2 space-y-2 shrink-0">
              <div className="flex items-center gap-1.5">
                <input
                  type="text"
                  value={newFolderName}
                  onChange={(e) => setNewFolderName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") handleCreateFolder();
                    if (e.key === "Escape") setShowCreateFolder(false);
                  }}
                  placeholder="文件夹名称..."
                  autoFocus
                  className="flex-1 rounded-lg border border-border/50 bg-muted/30 px-2.5 py-1.5 text-[11px] text-foreground placeholder:text-muted-foreground/40 outline-none focus:border-primary/30 transition-all"
                />
                <button
                  type="button"
                  onClick={handleCreateFolder}
                  disabled={!newFolderName.trim()}
                  className="h-7 px-2.5 rounded-lg bg-primary text-primary-foreground text-[11px] font-medium disabled:opacity-40 transition-all"
                >
                  创建
                </button>
              </div>
              {/* Color picker */}
              <div className="flex flex-wrap gap-1.5">
                {FOLDER_COLORS.map((c) => (
                  <button
                    key={c}
                    type="button"
                    onClick={() => setNewFolderColor(c)}
                    className={cn(
                      "h-5 w-5 rounded-full ring-1 ring-white/20 hover:ring-primary/50 hover:scale-110 transition-all",
                      newFolderColor === c && "ring-2 ring-primary scale-110",
                    )}
                    style={{ backgroundColor: c }}
                  />
                ))}
              </div>
            </div>
          )}

          {/* First-use gesture hint */}
          {showGestureHint && (
            <div className="mx-3 mb-2 flex items-start gap-2 rounded-lg bg-primary/5 border border-primary/20 px-3 py-2 text-[11px] text-primary/80 shrink-0">
              <span className="shrink-0 mt-0.5">💡</span>
              <div className="flex-1 min-w-0">
                <p>拖拽文档到文件夹可归类，长按文档可移动</p>
              </div>
              <button
                type="button"
                onClick={dismissGestureHint}
                className="shrink-0 h-4 w-4 flex items-center justify-center rounded hover:bg-primary/10"
              >
                <X className="h-3 w-3" />
              </button>
            </div>
          )}

          {/* Document list with folders — fills remaining space and scrolls */}
          <ScrollArea className="flex-1 min-h-0">
            <div className="px-2 pb-2 space-y-0.5">
              {/* Loading skeleton */}
              {loading && documents.length === 0 && (
                <div className="space-y-1 px-1">
                  {Array.from({ length: 4 }).map((_, i) => (
                    <div key={i} className="flex items-center gap-2 rounded-lg px-3 py-1.5">
                      <div className="h-3 w-3 rounded bg-muted/50 animate-pulse" />
                      <div className="flex-1 h-3 rounded bg-muted/50 animate-pulse" style={{ width: `${60 + i * 10}%` }} />
                    </div>
                  ))}
                </div>
              )}

              {/* Empty state */}
              {documents.length === 0 && !loading && (
                <div className="flex flex-col items-center gap-3 px-4 py-8 text-center">
                  <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-muted/50">
                    <FileUp className="h-6 w-6 text-muted-foreground/40" />
                  </div>
                  <div>
                    <p className="text-xs font-medium text-foreground/70">还没有学习资料</p>
                    <p className="text-[11px] text-muted-foreground/50 mt-0.5">上传 PDF、DOCX 等开始学习</p>
                  </div>
                </div>
              )}

              {/* Search no results */}
              {filteredDocs.length === 0 && documents.length > 0 && (
                <p className="px-3 py-4 text-center text-[11px] text-muted-foreground/50">
                  无匹配文档
                </p>
              )}

              {/* Recent docs section */}
              {!searchQuery && recentDocs.length > 0 && (
                <div className="mb-1">
                  <div className="flex items-center gap-1.5 px-3 pt-1 pb-0.5">
                    <Clock className="h-3 w-3 text-muted-foreground/40" />
                    <span className="text-[11px] text-muted-foreground/60 font-medium">最近访问</span>
                  </div>
                  {recentDocs.map(renderDocItem)}
                </div>
              )}

              {/* Folder sections */}
              {hasAnyFolders && folders.map(renderFolderSection)}

              {/* Root-level drop zone (for removing from folder) */}
              {hasAnyFolders && dragDocId && (
                <div
                  {...{ [FOLDER_HEADER_ATTR]: "root" }}
                  className={cn(
                    "flex items-center justify-center gap-2 rounded-lg border-2 border-dashed p-2 text-[11px] transition-all",
                    dropFolderId === null
                      ? "border-primary/50 bg-primary/5 text-primary"
                      : "border-white/10 text-muted-foreground/40",
                  )}
                >
                  <Folder className="h-3 w-3" />
                  移出文件夹
                </div>
              )}

              {/* Uncategorized documents */}
              {hasAnyFolders && ungroupedDocs.length > 0 && (
                <>
                  <div className="flex items-center gap-2 px-3 pt-2 pb-0.5">
                    <span className="text-[11px] text-muted-foreground/60 font-medium uppercase tracking-wider">
                      未分类
                    </span>
                    <span className="text-[11px] text-muted-foreground/50 tabular-nums">
                      {ungroupedDocs.length}
                    </span>
                  </div>
                  {ungroupedDocs.map(renderDocItem)}
                </>
              )}

              {/* No folders: flat list (legacy behavior) */}
              {!hasAnyFolders && filteredDocs.map(renderDocItem)}
            </div>
          </ScrollArea>
        </div>
      )}

      {/* Footer */}
      <div
        className={cn(
          "border-t border-white/10 p-2",
          collapsed
            ? "flex justify-center"
            : "flex items-center justify-between px-3",
        )}
      >
        {!collapsed && (
          <span className="text-[11px] text-muted-foreground/60 tracking-wider">
            EDUMERGE AI
          </span>
        )}
        <ThemeToggle />
      </div>
    </aside>

    {/* Move document bottom sheet (mobile-friendly) */}
    {moveTarget && (
      <>
        <div
          className="fixed inset-0 z-[60] bg-black/40 backdrop-blur-sm"
          onClick={dismissMoveSheet}
        />
        <div className="fixed inset-x-0 bottom-0 z-[61] bg-card border-t border-border rounded-t-2xl shadow-2xl max-h-[60vh] flex flex-col animate-in slide-in-from-bottom duration-200">
          <div className="flex items-center justify-between px-4 py-3 border-b border-border/50">
            <div className="min-w-0">
              <p className="text-sm font-medium text-foreground">移动到文件夹</p>
              <p className="text-[11px] text-muted-foreground truncate">{moveTarget.name}</p>
            </div>
            <button
              type="button"
              onClick={dismissMoveSheet}
              className="h-7 w-7 flex items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          <div className="flex-1 overflow-y-auto p-2 space-y-0.5">
            {/* Root (no folder) */}
            <button
              type="button"
              onClick={() => handleMoveToFolder(null)}
              className={cn(
                "w-full flex items-center gap-3 rounded-lg px-3 py-3 text-sm transition-all text-left",
                moveTarget.folderId == null
                  ? "bg-primary/10 text-primary font-medium"
                  : "text-muted-foreground hover:bg-muted",
              )}
            >
              <FileText className="h-4 w-4 shrink-0" />
              <span>根目录（未分类）</span>
            </button>
            {/* Folder options */}
            {folders.map((f) => (
              <button
                key={f.id}
                type="button"
                onClick={() => handleMoveToFolder(f.id)}
                className={cn(
                  "w-full flex items-center gap-3 rounded-lg px-3 py-3 text-sm transition-all text-left",
                  moveTarget.folderId === f.id
                    ? "bg-primary/10 text-primary font-medium"
                    : "text-muted-foreground hover:bg-muted",
                )}
              >
                <Folder className="h-4 w-4 shrink-0" style={{ color: f.color }} />
                <span className="flex-1 truncate">{f.name}</span>
                <span className="text-[11px] text-muted-foreground/40">{f.docCount}</span>
              </button>
            ))}
            {folders.length === 0 && (
              <p className="px-3 py-4 text-center text-[11px] text-muted-foreground/50">
                还没有文件夹，请先创建
              </p>
            )}
          </div>
        </div>
      </>
    )}

    {/* Shared confirm dialog */}
    <AlertDialog open={confirmState.open} onOpenChange={(open) => setConfirmState((s) => ({ ...s, open }))}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{confirmState.title}</AlertDialogTitle>
          <AlertDialogDescription>{confirmState.description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>取消</AlertDialogCancel>
          <AlertDialogAction onClick={() => { confirmState.onConfirm(); setConfirmState((s) => ({ ...s, open: false })); }}>确认删除</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
    </>
  );
}
