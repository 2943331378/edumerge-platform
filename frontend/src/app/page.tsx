"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { cn } from "@/lib/utils";
import { BrandMark } from "@/components/BrandMark";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";
import { ThemeToggle } from "@/components/theme-toggle";
import { ChatDrawer } from "@/components/chat/ChatDrawer";
import { AppSidebar, type UploadedDoc } from "@/components/app-sidebar";
import { LearningPath, type StepDef } from "@/components/learning-path";
import { StudyNoteView } from "@/components/StudyNoteView";
import { MindMapView } from "@/components/MindMapView";
import { FlashcardView } from "@/components/FlashcardView";
import { QuizView } from "@/components/QuizView";
import { FlowNoteView } from "@/components/FlowNoteView";
import { KnowledgeGraphPage } from "@/components/KnowledgeGraphPage";
import { DocumentOutlineView } from "@/components/DocumentOutlineView";
import { StatsDashboard } from "@/components/StatsDashboard";
import { OnboardingTour, isOnboardingDone } from "@/components/OnboardingTour";
import { StepHint, isStepHintDismissed, dismissStepHint } from "@/components/StepHint";
import { useAuth } from "@/lib/auth-context";
import { useGlobalKeyboard } from "@/hooks/useGlobalKeyboard";
import {
  Upload, NotebookText, GitFork, Layers, HelpCircle,
  MessageSquare, ChevronLeft, ChevronRight,
  FileText, Loader2, X, BarChart3, Menu, Search, LogOut, User, BookOpen, GitBranch,
  CheckCircle2, Clock,
} from "lucide-react";
import type { SessionRecord, MindMapRecord, StudyNoteRecord } from "@/lib/api";
import { listSessions, uploadDocument, deleteDocument, retryDocument, renameDocument } from "@/lib/api";

const STEPS: StepDef[] = [
  { id: 1, label: "文档大纲", icon: Upload },
  { id: 2, label: "生成笔记", icon: NotebookText },
  { id: 3, label: "查看导图", icon: GitFork },
  { id: 4, label: "练卡片",   icon: Layers },
  { id: 5, label: "做测验",   icon: HelpCircle },
  { id: 6, label: "学习日志", icon: BookOpen },
];

const STEP_ACTIVITY_MAP: Record<number, string | null> = {
  1: null,
  2: "notes",
  3: "mindmap",
  4: "flashcards",
  5: "quiz",
  6: "flownote",
};

/** 大纲生成类型 → 目标步骤映射 */
const OUTLINE_STEP_MAP: Record<string, number> = {
  note: 2,
  mindmap: 3,
  flashcard: 4,
  quiz: 5,
};

/** 处理阶段定义 */
const PROCESSING_STAGES = [
  { key: "UPLOADING", label: "上传文件", icon: Upload },
  { key: "PROCESSING", label: "解析文档 & 向量化", icon: Loader2 },
  { key: "COMPLETED", label: "处理完成", icon: CheckCircle2 },
] as const;

/** 文档处理进度卡片 — 展示当前处理阶段 */
function ProcessingStatusCard({ fileName, status, chunkCount }: {
  fileName: string;
  status: string;
  chunkCount: number | null;
}) {
  const activeIdx = status === "UPLOADING" ? 0 : status === "COMPLETED" ? 2 : 1;
  return (
    <div className="w-full max-w-md rounded-2xl border border-border/60 bg-card p-6 shadow-sm space-y-5">
      <div className="text-center space-y-1">
        <h3 className="text-sm font-semibold text-foreground truncate">{fileName}</h3>
        <p className="text-xs text-muted-foreground">正在后台处理，请稍候...</p>
      </div>
      <div className="flex items-center justify-between">
        {PROCESSING_STAGES.map((stage, i) => {
          const Icon = stage.icon;
          const isActive = i === activeIdx;
          const isDone = i < activeIdx;
          return (
            <div key={stage.key} className="flex flex-col items-center gap-1.5 flex-1">
              <div className={cn(
                "relative flex h-9 w-9 items-center justify-center rounded-full transition-all",
                isDone ? "bg-primary/15 text-primary" : isActive ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground/40",
              )}>
                <Icon className={cn("h-4 w-4", isActive && "animate-spin")} />
                {isActive && <span className="absolute inset-0 rounded-full animate-ping bg-primary/30" />}
              </div>
              <span className={cn("text-[10px] font-medium", isActive ? "text-primary" : isDone ? "text-foreground/70" : "text-muted-foreground/40")}>
                {stage.label}
              </span>
            </div>
          );
        })}
      </div>
      {/* 连接线 */}
      <div className="relative mx-4 -mt-8 mb-2 h-0.5">
        <div className="absolute inset-0 bg-muted rounded-full" />
        <div className="absolute inset-y-0 left-0 bg-primary rounded-full transition-all duration-700"
          style={{ width: `${(activeIdx / (PROCESSING_STAGES.length - 1)) * 100}%` }} />
      </div>
      {chunkCount != null && chunkCount > 0 && (
        <p className="text-center text-[11px] text-muted-foreground">
          已切分 <span className="font-medium text-foreground">{chunkCount}</span> 个文本片段
        </p>
      )}
    </div>
  );
}

export default function HomePage() {
  const auth = useAuth();

  const [currentStep, setCurrentStep] = useState(1);

  const [sessions, setSessions] = useState<SessionRecord[]>([]);
  const [activeSession, setActiveSession] = useState<SessionRecord | null>(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);

  // Per-session cache to preserve AI content when switching documents
  const [sessionCache, setSessionCache] = useState<
    Map<number, {
      note?: StudyNoteRecord | null;
      mindMap?: MindMapRecord | null;
      completedSteps?: Set<number>;
      sectionContext?: string;
      startChunk?: number;
      endChunk?: number;
      outlineGenerateTrigger?: { type: string; counter: number };
      noteGenerating?: boolean;
      mindMapGenerating?: boolean;
      flashcardGenerating?: boolean;
      quizGenerating?: boolean;
    }>
  >(new Map());

  const [chatOpen, setChatOpen] = useState(false);
  const [chatContext, setChatContext] = useState("");
  const [showKnowledgeGraph, setShowKnowledgeGraph] = useState(false);
  const [docSearch, setDocSearch] = useState("");
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // 桌面端用侧边面板，移动端跳转全屏页面
  const openDashboard = useCallback(() => {
    if (window.innerWidth >= 768) {
      setUserMenuOpen((v) => !v);
    } else {
      window.location.href = "/dashboard";
    }
  }, []);

  // 窗口缩放到移动端时自动关闭侧边面板
  useEffect(() => {
    const handleResize = () => {
      if (window.innerWidth < 768 && userMenuOpen) {
        setUserMenuOpen(false);
      }
    };
    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, [userMenuOpen]);

  // Onboarding tour — show on first login
  const [tourOpen, setTourOpen] = useState(false);
  useEffect(() => {
    if (!auth.loading && auth.token && !isOnboardingDone()) {
      setTourOpen(true);
    }
  }, [auth.loading, auth.token]);

  // Derive current session's state from cache
  const activeCache = activeSession ? sessionCache.get(activeSession.id) : undefined;
  const note = activeCache?.note ?? null;
  const completedSteps = activeCache?.completedSteps ?? new Set<number>();

  // Step hints — show contextual hint on first visit to each step
  const [stepHintVisible, setStepHintVisible] = useState(false);
  useEffect(() => {
    // Auto-hide if step already has content (user already knows how it works)
    const hasContent =
      (currentStep === 1 && !!activeSession) ||
      (currentStep === 2 && !!note) ||
      (currentStep >= 3 && completedSteps.has(currentStep));
    if (hasContent) {
      dismissStepHint(currentStep);
      setStepHintVisible(false);
    } else {
      setStepHintVisible(!isStepHintDismissed(currentStep));
    }
  }, [currentStep, activeSession, note, sessionCache]);
  const dismissHint = () => {
    dismissStepHint(currentStep);
    setStepHintVisible(false);
  };

  const updateSessionCache = (updates: Partial<NonNullable<typeof activeCache>>) => {
    if (!activeSession) return;
    setSessionCache((prev) => {
      const next = new Map(prev);
      const existing = next.get(activeSession.id) ?? {};
      next.set(activeSession.id, { ...existing, ...updates });
      return next;
    });
  };

  // 大纲底部工具栏 → 导航到目标步骤并传递选中章节
  const handleOutlineGenerate = useCallback((type: "note" | "mindmap" | "flashcard" | "quiz", sectionContext: string, startChunk?: number, endChunk?: number) => {
    const prev = activeCache?.outlineGenerateTrigger?.counter ?? 0;
    updateSessionCache({
      sectionContext,
      startChunk,
      endChunk,
      outlineGenerateTrigger: { type, counter: prev + 1 },
    });
    const targetStep = OUTLINE_STEP_MAP[type];
    if (targetStep) setCurrentStep(targetStep);
  }, [activeSession, sessionCache]);

  const loadSessions = useCallback(async () => {
    try {
      const list = await listSessions();
      setSessions(list);
      setActiveSession((cur) => {
        if (!cur?.id) {
          const completed = list.find((s) => s.docStatus === "COMPLETED");
          return completed ?? list[0] ?? null;
        }
        // 用列表中的最新数据替换，确保 status 等字段同步更新
        const updated = list.find((s) => s.id === cur.id);
        return updated ?? cur;
      });
    } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    const t = setTimeout(loadSessions, 0);
    return () => clearTimeout(t);
  }, [loadSessions]);

  // Auth guard: redirect to landing page if not authenticated
  useEffect(() => {
    if (!auth.loading && !auth.token) {
      window.location.href = "/landing";
    }
  }, [auth.loading, auth.token]);

  // 处理从看板页面带回的待执行操作
  useEffect(() => {
    const pending = sessionStorage.getItem("edumerge_pending_action");
    if (!pending) return;
    sessionStorage.removeItem("edumerge_pending_action");
    try {
      if (pending === "upload") {
        fileInputRef.current?.click();
      } else {
        const action = JSON.parse(pending);
        if (action.step) {
          setCurrentStep(action.step);
          if (action.docId) {
            const s = sessions.find((x) => x.docId === action.docId);
            if (s) setActiveSession(s);
          }
        } else if (action.knowledgeGraph) {
          setShowKnowledgeGraph(true);
        }
      }
    } catch { /* ignore */ }
  }, [sessions]);

  // 文档向量化异步处理中，自适应轮询（处理中 2s，空闲 10s）
  useEffect(() => {
    const hasPending = sessions.some(
      (s) => s.docStatus && s.docStatus !== "COMPLETED" && s.docStatus !== "FAILED",
    );
    if (!hasPending) return;
    const interval = hasPending ? 2000 : 10000;
    const timer = setInterval(loadSessions, interval);
    return () => clearInterval(timer);
  }, [sessions, loadSessions]);

  const sidebarDocs: UploadedDoc[] = sessions.map((s) => ({
    id: String(s.id),
    sessionId: s.id,
    name: s.fileName ?? s.title,
    size: 0,
    status:
      s.docStatus === "COMPLETED"
        ? "done"
        : s.docStatus === "FAILED"
          ? "error"
          : s.docStatus === "PROCESSING"
            ? "processing"
            : "uploading",
    chunks: s.chunkCount ?? 0,
    fileType: s.fileType,
    pageCount: s.pageCount,
  }));

  const handleSelectSession = useCallback((sessionId: number) => {
    const s = sessions.find((x) => x.id === sessionId);
    if (s) {
      setActiveSession(s);
      setCurrentStep(1);
    }
  }, [sessions]);

  const handleDeleteDocument = useCallback(async (sessionId: number) => {
    const session = sessions.find((s) => s.id === sessionId);
    if (!session?.docId) return;
    if (!confirm(`确定删除「${session.fileName ?? session.title}」及其全部关联数据吗？此操作不可撤销。`)) return;
    try {
      await deleteDocument(session.docId);
      toast.success("文档已删除");
      if (activeSession?.id === sessionId) {
        setActiveSession(null);
        setSessionCache((prev) => {
          const next = new Map(prev);
          next.delete(sessionId);
          return next;
        });
      }
      await loadSessions();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败");
    }
  }, [sessions, activeSession, loadSessions]);

  const handleRetryDocument = useCallback(async (sessionId: number) => {
    const session = sessions.find((s) => s.id === sessionId);
    if (!session?.docId) return;
    try {
      await retryDocument(session.docId);
      toast.success("已重新提交处理");
      await loadSessions();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "重试失败");
    }
  }, [sessions, loadSessions]);

  const handleRenameDocument = useCallback(async (sessionId: number, newTitle: string) => {
    const session = sessions.find((s) => s.id === sessionId);
    if (!session?.docId) return;
    try {
      await renameDocument(session.docId, newTitle);
      await loadSessions();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "重命名失败");
    }
  }, [sessions, loadSessions]);

  const handleUpload = useCallback(async (file: File | FileList) => {
    if (uploading) return;
    const files = file instanceof FileList ? Array.from(file) : [file];
    if (files.length === 0) return;
    setUploading(true);
    setUploadProgress(0);
    try {
      for (let i = 0; i < files.length; i++) {
        const result = await uploadDocument(files[i], (p) => {
          const overall = ((i * 100 + p) / files.length);
          setUploadProgress(Math.round(overall));
        });
        toast.success(`${result.fileName} 上传成功，正在后台处理`);
      }
      setUploadProgress(100);
      await loadSessions();
      const list = await listSessions();
      if (list.length > 0) {
        setActiveSession(list[0]);
        setCurrentStep(1);
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "上传失败");
    }
    setUploading(false);
  }, [loadSessions, uploading]);

  const step1Ready = !!activeSession && activeSession.docStatus === "COMPLETED";
  const step2Ready = !!note;

  useEffect(() => {
    const next = new Set(completedSteps);
    if (step1Ready) next.add(1);
    if (step2Ready) next.add(2);
    updateSessionCache({ completedSteps: next });
  }, [step1Ready, step2Ready]); // eslint-disable-line

  const goStep = useCallback((s: number) => {
    if (s >= 1 && s <= STEPS.length) setCurrentStep(s);
  }, []);
  const goNext = useCallback(() => {
    setCurrentStep((prev) => Math.min(prev + 1, STEPS.length));
  }, []);
  const goPrev = useCallback(() => {
    setCurrentStep((prev) => Math.max(prev - 1, 1));
  }, []);

  // 全局键盘快捷键: 1-6 跳转步骤, Ctrl+/ 对话, Ctrl+Shift+D 暗黑模式
  // step 4(卡片) 时禁用数字键 1-4（卡片自评用），5-6 仍可跳转
  const toggleChat = useCallback(() => setChatOpen((v) => !v), []);
  useGlobalKeyboard({
    onGoStep: goStep,
    onToggleChat: toggleChat,
    totalSteps: STEPS.length,
    numberKeysHandledUpTo: currentStep === 4 ? 4 : 0,
  });

  const renderStep = () => {
    const docId = activeSession?.docId ?? null;
    const docStatus = activeSession?.docStatus ?? null;
    const docUuid = activeSession?.docUuid ?? null;
    const sessionId = activeSession?.id ?? null;

    switch (currentStep) {
      case 1:
        // 已选中文档且处理完成 → 显示大纲视图
        if (activeSession && activeSession.docStatus === "COMPLETED") {
          return (
            <DocumentOutlineView
              docId={docId}
              docStatus={docStatus}
              embedded
              onContextChange={setChatContext}
              onGenerate={handleOutlineGenerate}
            />
          );
        }
        // 已选中文档但处理中 → 显示处理进度卡片
        if (activeSession && activeSession.docStatus && activeSession.docStatus !== "FAILED") {
          return (
            <div className="flex-1 flex flex-col items-center justify-center gap-6 p-4 md:p-8">
              <ProcessingStatusCard
                fileName={activeSession.fileName ?? activeSession.title}
                status={activeSession.docStatus}
                chunkCount={activeSession.chunkCount}
              />
            </div>
          );
        }
        // 已选中文档但处理失败 → 显示错误提示
        if (activeSession && activeSession.docStatus === "FAILED") {
          return (
            <div className="flex-1 flex flex-col items-center justify-center gap-4 p-4 md:p-8">
              <div className="text-center space-y-2 max-w-md">
                <div className="inline-flex h-16 w-16 items-center justify-center rounded-2xl bg-destructive/10 mb-2">
                  <X className="h-7 w-7 text-destructive" />
                </div>
                <h2 className="text-lg font-semibold text-foreground">文档处理失败</h2>
                <p className="text-sm text-muted-foreground">「{activeSession.fileName ?? activeSession.title}」处理时出错，请重新上传。</p>
              </div>
            </div>
          );
        }
        // 未选中文档 → 显示上传+文档列表
        return (
          <div className="flex-1 flex flex-col items-center justify-center gap-4 md:gap-6 p-4 md:p-8">
            <div className="text-center space-y-2 max-w-md">
              <div className="inline-flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/10 mb-2">
                <Upload className="h-7 w-7 text-primary" />
              </div>
              <h2 className="text-lg font-semibold text-foreground">上传学习资料</h2>
              <p className="text-sm text-muted-foreground leading-relaxed">
                支持 PDF、Word、PPT、TXT 等格式。上传后 AI 将自动解析文档结构并生成大纲。
              </p>
            </div>

            <label
              className={cn(
                "flex flex-col items-center gap-2 md:gap-3 rounded-2xl border-2 border-dashed p-6 md:p-10 cursor-pointer transition-all",
                "border-muted-foreground/25 hover:border-primary/50 hover:bg-primary/5",
                "w-full max-w-lg",
              )}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                if (e.dataTransfer.files.length > 0) handleUpload(e.dataTransfer.files);
              }}
            >
              <Upload className="h-6 w-6 text-muted-foreground/50" />
              <span className="text-sm text-muted-foreground">
                拖拽文件到此处，或<span className="text-primary font-medium">点击选择</span>
              </span>
              <span className="text-[11px] text-muted-foreground/50">PDF · DOCX · PPTX · TXT · 图片 · Markdown · HTML · Excel · CSV</span>
              <input
                type="file"
                multiple
                accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,.md,.html,.htm,.xlsx,.csv,.jpg,.jpeg,.png,.bmp,.tiff,image/*,application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation,text/markdown,text/html,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
                onChange={(e) => {
                  const files = e.target.files;
                  if (files && files.length > 0) handleUpload(files);
                  e.target.value = "";
                }}
                className="hidden"
              />
            </label>

            {uploading && (
              <div className="w-full max-w-lg space-y-2">
                <div className="flex items-center justify-between text-sm">
                  <span className="flex items-center gap-2 text-muted-foreground">
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    {uploadProgress < 80 ? "正在上传文件..." : "上传完成，准备处理..."}
                  </span>
                  <span className="text-xs text-muted-foreground">{uploadProgress}%</span>
                </div>
                <div className="h-1.5 w-full rounded-full bg-muted overflow-hidden">
                  <div
                    className="h-full rounded-full bg-primary transition-all duration-300 ease-out"
                    style={{ width: `${uploadProgress}%` }}
                  />
                </div>
              </div>
            )}

            {!uploading && sessions.length > 0 && (
              <div className="w-full max-w-lg space-y-2">
                <div className="flex items-center gap-2">
                  <p className="text-xs text-muted-foreground/60 font-medium px-1">已上传文档</p>
                  {sessions.length > 3 && (
                    <div className="relative flex-1">
                      <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 text-muted-foreground/30" />
                      <input
                        type="text"
                        value={docSearch}
                        onChange={(e) => setDocSearch(e.target.value)}
                        placeholder="搜索..."
                        className="w-full rounded-md border border-border/50 bg-muted/20 pl-6 pr-2 py-1 text-[11px] outline-none focus:border-primary/30"
                      />
                    </div>
                  )}
                </div>
                {sessions
                  .filter((s) => !docSearch || (s.fileName ?? s.title).toLowerCase().includes(docSearch.toLowerCase()))
                  .map((s) => (
                  <div
                    key={s.id}
                    onClick={() => handleSelectSession(s.id)}
                    className={cn(
                      "group flex items-center gap-2 rounded-lg px-3 py-2 text-sm cursor-pointer transition-all",
                      activeSession?.id === s.id
                        ? "bg-primary/10 ring-1 ring-primary/30 text-foreground font-medium"
                        : "hover:bg-muted text-muted-foreground",
                    )}
                  >
                    <FileText className="h-3.5 w-3.5 shrink-0" />
                    <span className="flex-1 truncate">{s.fileName ?? s.title}</span>
                    {s.docStatus && s.docStatus !== "COMPLETED" && s.docStatus !== "FAILED" && (
                      <span className="text-[9px] text-muted-foreground/60 shrink-0">
                        {s.docStatus === "UPLOADING" ? "上传中" : s.chunkCount ? `${s.chunkCount}片` : "处理中"}
                      </span>
                    )}
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDeleteDocument(s.id);
                      }}
                      className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-all shrink-0"
                      title="删除文档"
                    >
                      <X className="h-3 w-3" />
                    </button>
                    <span
                      className={cn(
                        "inline-block h-1.5 w-1.5 rounded-full shrink-0",
                        s.docStatus === "COMPLETED"
                          ? "bg-lime-500"
                          : s.docStatus === "FAILED"
                            ? "bg-destructive"
                            : s.docStatus === "PROCESSING"
                              ? "bg-blue-400 animate-pulse"
                              : "bg-amber-400 animate-pulse",
                      )}
                    />
                  </div>
                ))}
              </div>
            )}
          </div>
        );

      case 2:
        return (
          <StudyNoteView
            docId={docId}
            docStatus={docStatus}
            embedded
            onContextChange={setChatContext}
            sectionContext={activeCache?.sectionContext}
            startChunk={activeCache?.startChunk}
            endChunk={activeCache?.endChunk}
            generateTrigger={activeCache?.outlineGenerateTrigger}
            generating={activeCache?.noteGenerating}
            onGeneratingChange={(v) => updateSessionCache({ noteGenerating: v })}
            onGenerated={() => {
              import("@/lib/api").then(({ getStudyNote }) => {
                if (docId) getStudyNote(docId).then((n) => updateSessionCache({ note: n }));
              });
            }}
          />
        );

      case 3:
        return (
          <MindMapView
            docId={docId}
            docStatus={docStatus}
            embedded
            onContextChange={setChatContext}
            sectionContext={activeCache?.sectionContext}
            startChunk={activeCache?.startChunk}
            endChunk={activeCache?.endChunk}
            generateTrigger={activeCache?.outlineGenerateTrigger}
            generating={activeCache?.mindMapGenerating}
            onGeneratingChange={(v) => updateSessionCache({ mindMapGenerating: v })}
          />
        );

      case 4:
        return (
          <FlashcardView
            docId={docId}
            docUuid={docUuid}
            sessionId={sessionId}
            embedded
            onContextChange={setChatContext}
            sectionContext={activeCache?.sectionContext}
            startChunk={activeCache?.startChunk}
            endChunk={activeCache?.endChunk}
            generateTrigger={activeCache?.outlineGenerateTrigger}
            generating={activeCache?.flashcardGenerating}
            onGeneratingChange={(v) => updateSessionCache({ flashcardGenerating: v })}
            onGenerated={() => updateSessionCache({ completedSteps: new Set([...completedSteps, 4]) })}
          />
        );

      case 5:
        return (
          <QuizView
            docId={docId}
            docUuid={docUuid}
            sessionId={sessionId}
            embedded
            onContextChange={setChatContext}
            sectionContext={activeCache?.sectionContext}
            startChunk={activeCache?.startChunk}
            endChunk={activeCache?.endChunk}
            generateTrigger={activeCache?.outlineGenerateTrigger}
            generating={activeCache?.quizGenerating}
            onGeneratingChange={(v) => updateSessionCache({ quizGenerating: v })}
            onGenerated={() => updateSessionCache({ completedSteps: new Set([...completedSteps, 5]) })}
          />
        );

      case 6:
        return (
          <FlowNoteView
            docId={docId}
            docStatus={docStatus}
            sessionId={sessionId}
            docUuid={docUuid}
            embedded
            onContextChange={setChatContext}
          />
        );

      default:
        return null;
    }
  };

  if (auth.loading || !auth.token) {
    return (
      <div className="flex h-full items-center justify-center">
        {auth.loading && <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />}
      </div>
    );
  }

  return (
    <div className="flex h-full overflow-hidden">
      <AppSidebar
        documents={sidebarDocs}
        activeSessionId={activeSession?.id ?? null}
        onSelectSession={handleSelectSession}
        onUpload={handleUpload}
        onDeleteDocument={handleDeleteDocument}
        onRetryDocument={handleRetryDocument}
        onRenameDocument={handleRenameDocument}
        collapsed={sidebarCollapsed}
        onToggleCollapse={() => setSidebarCollapsed((v) => !v)}
      />

      <main className="flex-1 flex flex-col min-w-0">
        <header className="flex items-center justify-between px-3 md:px-4 py-2 border-b border-border/50 bg-background/50 backdrop-blur shrink-0">
          <div className="flex items-center gap-2 md:gap-3">
            <button
              type="button"
              className="md:hidden h-8 w-8 inline-flex items-center justify-center rounded-lg hover:bg-muted transition-colors"
              onClick={() => setSidebarCollapsed((v) => !v)}
              title="菜单"
            >
              <Menu className="h-4 w-4" />
            </button>
            <div className="hidden sm:block">
              <BrandMark variant="header" />
            </div>
            <div>
              {activeSession && (
                <p className="text-[10px] text-muted-foreground truncate max-w-[120px] md:max-w-[200px]">
                  {activeSession.fileName ?? activeSession.title}
                </p>
              )}
            </div>
          </div>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              className={cn("h-8 w-8 rounded-lg", userMenuOpen && "bg-primary/10 text-primary")}
              onClick={openDashboard}
              title="个人中心"
            >
              <BarChart3 className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className={cn("h-8 w-8 rounded-lg", showKnowledgeGraph && "bg-primary/10 text-primary")}
              onClick={() => { setShowKnowledgeGraph((v) => !v); setUserMenuOpen(false); }}
              title="知识图谱"
            >
              <GitBranch className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8 rounded-lg"
              onClick={() => setChatOpen(true)}
              title="AI 对话助手"
            >
              <MessageSquare className="h-4 w-4" />
            </Button>
            <ThemeToggle />

            {/* User menu → 个人中心 */}
            <button
              type="button"
              onClick={openDashboard}
              className="flex items-center gap-1.5 rounded-lg px-2 py-1 text-[11px] text-muted-foreground hover:text-foreground hover:bg-muted transition-all"
            >
              <User className="h-3.5 w-3.5" />
              <span className="hidden sm:inline max-w-[80px] truncate">
                {auth.user?.displayName ?? auth.user?.username ?? "用户"}
              </span>
            </button>
          </div>
        </header>

        {!showKnowledgeGraph && (
          <LearningPath
            steps={STEPS}
            currentStep={currentStep}
            completedSteps={completedSteps}
            onStepClick={goStep}
          />
        )}

        <div className="flex-1 flex flex-col min-h-0 overflow-hidden relative">
          {/* Contextual step hint — shown on first visit to each step */}
          {!showKnowledgeGraph && (
            <StepHint
              step={currentStep}
              visible={stepHintVisible}
              onDismiss={dismissHint}
            />
          )}
          {showKnowledgeGraph ? (
            <KnowledgeGraphPage
              sessions={sessions}
              onSelectSession={(sessionId) => {
                handleSelectSession(sessionId);
                setShowKnowledgeGraph(false);
              }}
              onOpenChat={(contextHint) => {
                setChatContext(contextHint);
                setChatOpen(true);
              }}
            />
          ) : renderStep()}
          {/* Floating Ask AI button — visible in steps 2-6 */}
          {!showKnowledgeGraph && currentStep >= 2 && (
            <button
              type="button"
              onClick={() => setChatOpen(true)}
              className="absolute bottom-4 right-4 z-20 flex items-center gap-2 px-4 py-2.5 rounded-full bg-primary text-primary-foreground shadow-lg hover:shadow-xl hover:scale-105 active:scale-95 transition-all text-sm font-medium"
            >
              <MessageSquare className="h-4 w-4" />
              <span className="hidden sm:inline">Ask AI</span>
            </button>
          )}
        </div>

        {!showKnowledgeGraph && (
          <div className="flex items-center justify-between px-3 md:px-6 py-2 md:py-3 border-t border-border/50 bg-background/50 shrink-0">
            <Button
              variant="ghost"
              size="sm"
              className="gap-1 md:gap-1.5 rounded-xl text-xs md:text-sm h-8"
              onClick={goPrev}
              disabled={currentStep <= 1}
            >
              <ChevronLeft className="h-3.5 w-3.5 md:h-4 md:w-4" />
              <span className="hidden sm:inline">上一步</span>
            </Button>

            <span className="text-[10px] md:text-[11px] text-muted-foreground/50">
              {currentStep} / {STEPS.length}
            </span>

            <Button
              variant="ghost"
              size="sm"
              className="gap-1 md:gap-1.5 rounded-xl text-xs md:text-sm h-8"
              onClick={goNext}
              disabled={currentStep >= STEPS.length}
            >
              <span className="hidden sm:inline">下一步</span>
              <ChevronRight className="h-3.5 w-3.5 md:h-4 md:w-4" />
            </Button>
          </div>
        )}
      </main>

      <ChatDrawer
        open={chatOpen}
        onClose={() => setChatOpen(false)}
        docUuid={activeSession?.docUuid ?? null}
        docId={activeSession?.docId ?? null}
        activityType={STEP_ACTIVITY_MAP[currentStep] ?? null}
        contextHint={chatContext || null}
      />

      {/* 个人中心面板 — 桌面端侧边面板（移动端通过 /dashboard 全屏页面） */}
      {userMenuOpen && (
        <>
          <div className="hidden md:block fixed inset-0 z-40 bg-black/30" onClick={() => setUserMenuOpen(false)} />
          <div className="hidden md:flex fixed right-0 top-0 bottom-0 z-50 w-[340px] max-w-[85vw] bg-card border-l border-border shadow-2xl flex-col animate-in slide-in-from-right duration-200">
            {/* 用户信息 */}
            <div className="flex items-center justify-between px-4 py-3 border-b border-border/50">
              <div className="flex items-center gap-2.5 min-w-0">
                <div className="h-9 w-9 rounded-full bg-primary/10 flex items-center justify-center text-sm font-semibold text-primary shrink-0">
                  {(auth.user?.displayName ?? auth.user?.username ?? "U")[0]}
                </div>
                <div className="min-w-0">
                  <p className="text-sm font-medium text-foreground truncate">
                    {auth.user?.displayName ?? auth.user?.username}
                  </p>
                  <p className="text-[11px] text-muted-foreground truncate">
                    {auth.user?.email}
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setUserMenuOpen(false)}
                className="h-7 w-7 flex items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* 个人看板 */}
            <div className="flex-1 overflow-y-auto">
              <StatsDashboard onAction={(action) => {
                setUserMenuOpen(false);
                if (action === "upload") {
                  fileInputRef.current?.click();
                } else if (action === "flashcard") {
                  setCurrentStep(4);
                } else if (action === "quiz") {
                  setCurrentStep(5);
                } else if (action === "knowledge-graph") {
                  setShowKnowledgeGraph(true);
                } else if (typeof action === "object" && action.type === "flashcard-doc") {
                  const s = sessions.find((x) => x.docId === action.docId);
                  if (s) { setActiveSession(s); }
                  setCurrentStep(4);
                }
              }} />
            </div>

            {/* 退出登录 */}
            <div className="border-t border-border/50 p-3">
              <button
                type="button"
                onClick={() => { auth.logout(); setUserMenuOpen(false); }}
                className="flex w-full items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-all"
              >
                <LogOut className="h-4 w-4" />
                退出登录
              </button>
            </div>
          </div>
        </>
      )}

      {/* 隐藏文件输入 — 供看板快捷入口触发 */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,.md,.html,.htm,.xlsx,.csv,.jpg,.jpeg,.png,.bmp,.tiff,image/*,application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation,text/markdown,text/html,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
        onChange={(e) => {
          const files = e.target.files;
          if (files && files.length > 0) handleUpload(files);
          e.target.value = "";
        }}
        className="hidden"
      />

      {/* Onboarding tour — first-time user guide */}
      <OnboardingTour open={tourOpen} onClose={() => setTourOpen(false)} />
    </div>
  );
}
