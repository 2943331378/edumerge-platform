"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";
import { ThemeToggle } from "@/components/theme-toggle";
import { ChatDrawer } from "@/components/chat/ChatDrawer";
import { AppSidebar, type UploadedDoc } from "@/components/app-sidebar";
import { LearningPath, type StepDef } from "@/components/learning-path";
import { StudyNoteView } from "@/components/StudyNoteView";
import { MindMapViewer } from "@/components/MindMapViewer";
import { FlashcardView } from "@/components/FlashcardView";
import { QuizView } from "@/components/QuizView";
import { FlowNoteView } from "@/components/FlowNoteView";
import { KnowledgeGraphPage } from "@/components/KnowledgeGraphPage";
import { StatsDashboard } from "@/components/StatsDashboard";
import { useAuth } from "@/lib/auth-context";
import {
  Upload, NotebookText, GitFork, Layers, HelpCircle,
  MessageSquare, ChevronLeft, ChevronRight,
  FileText, Loader2, X, BarChart3, Menu, Search, LogOut, User, BookOpen, Sparkles, GitBranch,
} from "lucide-react";
import type { SessionRecord, MindMapRecord, StudyNoteRecord } from "@/lib/api";
import { listSessions, uploadDocument, deleteDocument, getMindMap, listDecks } from "@/lib/api";

const STEPS: StepDef[] = [
  { id: 1, label: "上传材料", icon: Upload },
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

export default function HomePage() {
  const router = useRouter();
  const auth = useAuth();

  const [currentStep, setCurrentStep] = useState(1);

  const [sessions, setSessions] = useState<SessionRecord[]>([]);
  const [activeSession, setActiveSession] = useState<SessionRecord | null>(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);

  // Per-session cache to preserve AI content when switching documents
  const [sessionCache, setSessionCache] = useState<
    Map<number, { note?: StudyNoteRecord | null; mindMap?: MindMapRecord | null; completedSteps?: Set<number> }>
  >(new Map());

  const [mindMapLoading, setMindMapLoading] = useState(false);

  const [chatOpen, setChatOpen] = useState(false);
  const [chatContext, setChatContext] = useState("");
  const [showStats, setShowStats] = useState(false);
  const [showKnowledgeGraph, setShowKnowledgeGraph] = useState(false);
  const [docSearch, setDocSearch] = useState("");
  const [userMenuOpen, setUserMenuOpen] = useState(false);

  // Derive current session's state from cache
  const activeCache = activeSession ? sessionCache.get(activeSession.id) : undefined;
  const note = activeCache?.note ?? null;
  const mindMapData = activeCache?.mindMap ?? null;
  const completedSteps = activeCache?.completedSteps ?? new Set<number>();

  const updateSessionCache = (updates: Partial<NonNullable<typeof activeCache>>) => {
    if (!activeSession) return;
    setSessionCache((prev) => {
      const next = new Map(prev);
      const existing = next.get(activeSession.id) ?? {};
      next.set(activeSession.id, { ...existing, ...updates });
      return next;
    });
  };

  const loadSessions = useCallback(async () => {
    try {
      const list = await listSessions();
      setSessions(list);
      setActiveSession((cur) => {
        if (cur?.id && list.find((s) => s.id === cur.id)) return cur;
        const completed = list.find((s) => s.docStatus === "COMPLETED");
        return completed ?? list[0] ?? null;
      });
    } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    const t = setTimeout(loadSessions, 0);
    return () => clearTimeout(t);
  }, [loadSessions]);

  // Auth guard: redirect to login if not authenticated
  useEffect(() => {
    if (!auth.loading && !auth.token) {
      router.replace("/login");
    }
  }, [auth.loading, auth.token, router]);

  // 文档向量化异步处理中，定时刷新状态直至完成或失败
  useEffect(() => {
    const hasPending = sessions.some(
      (s) => s.docStatus && s.docStatus !== "COMPLETED" && s.docStatus !== "FAILED",
    );
    if (!hasPending) return;
    const timer = setInterval(loadSessions, 5000);
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
          : "uploading", // UPLOADING / PROCESSING 均显示处理中
    chunks: s.chunkCount ?? 0,
  }));

  const handleSelectSession = useCallback((sessionId: number) => {
    const s = sessions.find((x) => x.id === sessionId);
    if (s) {
      setActiveSession(s);
      // Restore cached state for this session, or start fresh
      const cached = sessionCache.get(s.id);
      setCurrentStep(cached?.completedSteps && cached.completedSteps.size > 0 ? 2 : 1);
    }
  }, [sessions, sessionCache]);

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

  const handleUpload = useCallback(async (file: File) => {
    setUploading(true);
    setUploadProgress(0);
    const timer = setInterval(() => {
      setUploadProgress((p) => Math.min(p + 15, 70));
    }, 400);
    try {
      const result = await uploadDocument(file);
      clearInterval(timer);
      setUploadProgress(100);
      await loadSessions();
      toast.success(`${result.fileName} 上传成功`);
    } catch (err) {
      clearInterval(timer);
      toast.error(err instanceof Error ? err.message : "上传失败");
    }
    setUploading(false);
  }, [loadSessions]);

  const step1Ready = !!activeSession && activeSession.docStatus === "COMPLETED";
  const step2Ready = !!note;
  const step3Ready = !!mindMapData;

  useEffect(() => {
    const next = new Set(completedSteps);
    if (step1Ready) next.add(1);
    if (step2Ready) next.add(2);
    if (step3Ready) next.add(3);
    updateSessionCache({ completedSteps: next });
  }, [step1Ready, step2Ready, step3Ready]); // eslint-disable-line

  const goStep = (s: number) => {
    if (s >= 1 && s <= STEPS.length) setCurrentStep(s);
  };
  const goNext = () => {
    if (currentStep < STEPS.length) setCurrentStep(currentStep + 1);
  };
  const goPrev = () => {
    if (currentStep > 1) setCurrentStep(currentStep - 1);
  };

  // Auto-load existing mind map from backend (no generation triggered for new docs)
  useEffect(() => {
    if (currentStep !== 3 || !activeSession?.docId || mindMapData || mindMapLoading) return;
    let cancelled = false;
    const load = async () => {
      setMindMapLoading(true);
      try {
        // Only load if a MIND_MAP deck already exists — prevents auto-generation
        const decks = await listDecks(activeSession!.docId!, "MIND_MAP");
        if (!cancelled && decks.length > 0) {
          const data = await getMindMap(activeSession!.docId!);
          if (!cancelled) updateSessionCache({ mindMap: data });
        }
      } catch { /* show generate button */ }
      if (!cancelled) setMindMapLoading(false);
    };
    load();
    return () => { cancelled = true; };
  }, [currentStep, activeSession?.docId]); // eslint-disable-line

  const handleGenerateMindMap = async () => {
    if (!activeSession?.docId || mindMapLoading) return;
    setMindMapLoading(true);
    try {
      const data = await getMindMap(activeSession.docId!);
      updateSessionCache({ mindMap: data });
      toast.success("思维导图生成成功");
    } catch { toast.error("思维导图生成失败"); }
    setMindMapLoading(false);
  };

  const renderStep = () => {
    const docId = activeSession?.docId ?? null;
    const docStatus = activeSession?.docStatus ?? null;
    const docUuid = activeSession?.docUuid ?? null;
    const sessionId = activeSession?.id ?? null;

    switch (currentStep) {
      case 1:
        return (
          <div className="flex-1 flex flex-col items-center justify-center gap-4 md:gap-6 p-4 md:p-8">
            <div className="text-center space-y-2 max-w-md">
              <div className="inline-flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/10 mb-2">
                <Upload className="h-7 w-7 text-primary" />
              </div>
              <h2 className="text-lg font-semibold text-foreground">上传学习资料</h2>
              <p className="text-sm text-muted-foreground leading-relaxed">
                支持 PDF、Word、PPT、TXT 等格式。上传后 AI 将自动处理文档内容。
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
                const file = e.dataTransfer.files[0];
                if (file) handleUpload(file);
              }}
            >
              <Upload className="h-6 w-6 text-muted-foreground/50" />
              <span className="text-sm text-muted-foreground">
                拖拽文件到此处，或<span className="text-primary font-medium">点击选择</span>
              </span>
              <span className="text-[11px] text-muted-foreground/50">PDF · DOCX · PPTX · TXT</span>
              <input
                type="file"
                accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,application/pdf,text/plain,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) handleUpload(f);
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
                    上传处理中...
                  </span>
                  <span className="text-xs text-muted-foreground">{uploadProgress}%</span>
                </div>
                <div className="h-1.5 w-full rounded-full bg-muted overflow-hidden">
                  <div
                    className="h-full rounded-full bg-primary transition-all duration-500"
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
                          ? "bg-emerald-400"
                          : s.docStatus === "FAILED"
                            ? "bg-destructive"
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
            onGenerated={() => {
              import("@/lib/api").then(({ getStudyNote }) => {
                if (docId) getStudyNote(docId).then((n) => updateSessionCache({ note: n }));
              });
            }}
          />
        );

      case 3:
        if (mindMapLoading) {
          return (
            <div className="flex-1 flex items-center justify-center">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                正在生成思维导图...
              </div>
            </div>
          );
        }
        if (!mindMapData?.content) {
          return (
            <div className="flex-1 flex flex-col items-center justify-center gap-5 p-8">
              <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/60">
                <GitFork className="h-7 w-7 text-muted-foreground/45" />
              </div>
              <div className="text-center space-y-1.5">
                <p className="text-sm font-medium text-foreground/75">暂无思维导图</p>
                <p className="max-w-sm text-xs leading-6 text-muted-foreground/60">
                  AI 将自动分析文档结构，生成可交互的思维导图。
                </p>
              </div>
              <Button onClick={handleGenerateMindMap} disabled={!activeSession?.docId || activeSession?.docStatus !== "COMPLETED"} className="rounded-xl gap-2 h-10">
                <Sparkles className="h-4 w-4" />
                一键生成思维导图
              </Button>
            </div>
          );
        }
        return <MindMapViewer markdown={mindMapData.content} className="flex-1" onContextChange={setChatContext} />;

      case 4:
        return (
          <FlashcardView
            docId={docId}
            docUuid={docUuid}
            sessionId={sessionId}
            embedded
            onContextChange={setChatContext}
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

  // Show loading while checking auth
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
            <Image
              src="/logo_converted.svg"
              alt="EduMerge"
              width={28}
              height={28}
              priority
              className="rounded-md hidden sm:block"
            />
            <div>
              <h1 className="text-sm font-semibold text-foreground">EduMerge</h1>
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
              className={cn("h-8 w-8 rounded-lg", showStats && "bg-primary/10 text-primary")}
              onClick={() => { setShowStats((v) => !v); setShowKnowledgeGraph(false); }}
              title="数据看板"
            >
              <BarChart3 className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className={cn("h-8 w-8 rounded-lg", showKnowledgeGraph && "bg-primary/10 text-primary")}
              onClick={() => { setShowKnowledgeGraph((v) => !v); setShowStats(false); }}
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

            {/* User menu */}
            <div className="relative">
              <button
                type="button"
                onClick={() => setUserMenuOpen((v) => !v)}
                className="flex items-center gap-1.5 rounded-lg px-2 py-1 text-[11px] text-muted-foreground hover:text-foreground hover:bg-muted transition-all"
              >
                <User className="h-3.5 w-3.5" />
                <span className="hidden sm:inline max-w-[80px] truncate">
                  {auth.user?.displayName ?? auth.user?.username ?? "用户"}
                </span>
              </button>
              {userMenuOpen && (
                <>
                  <div className="fixed inset-0 z-10" onClick={() => setUserMenuOpen(false)} />
                  <div className="absolute right-0 top-full mt-1 z-20 w-48 rounded-xl border border-border bg-card shadow-xl animate-in fade-in zoom-in-95 duration-150">
                    <div className="px-3 py-2 border-b border-border/50">
                      <p className="text-xs font-medium text-foreground truncate">
                        {auth.user?.displayName ?? auth.user?.username}
                      </p>
                      <p className="text-[10px] text-muted-foreground truncate">
                        {auth.user?.email}
                      </p>
                    </div>
                    <div className="p-1">
                      <button
                        type="button"
                        onClick={() => { auth.logout(); setUserMenuOpen(false); router.replace("/login"); }}
                        className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-[11px] text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-all"
                      >
                        <LogOut className="h-3 w-3" />
                        退出登录
                      </button>
                    </div>
                  </div>
                </>
              )}
            </div>
          </div>
        </header>

        {!showStats && !showKnowledgeGraph && (
          <LearningPath
            steps={STEPS}
            currentStep={currentStep}
            completedSteps={completedSteps}
            onStepClick={goStep}
          />
        )}

        <div className="flex-1 flex flex-col min-h-0 overflow-hidden relative">
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
          ) : showStats ? <StatsDashboard /> : renderStep()}
          {/* Floating Ask AI button — visible in steps 2-6 */}
          {!showStats && !showKnowledgeGraph && currentStep >= 2 && (
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

        {!showStats && !showKnowledgeGraph && (
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
    </div>
  );
}
