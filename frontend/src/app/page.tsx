"use client";

import { useState, useEffect, useCallback } from "react";
import { toast } from "sonner";
import { AppSidebar, NavTab, UploadedDoc } from "@/components/app-sidebar";
import { ChatRoom } from "@/components/chat/ChatRoom";
import { Progress } from "@/components/ui/progress";
import { FlashcardView } from "@/components/FlashcardView";
import { QuizView } from "@/components/QuizView";
import { MindMapViewer } from "@/components/MindMapViewer";
import { StudyNoteView } from "@/components/StudyNoteView";
import {
  listSessions, uploadDocument,
  SessionRecord, MindMapRecord, getMindMap,
} from "@/lib/api";

export default function Home() {
  const [activeTab, setActiveTab] = useState<NavTab>("chat");
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  // Session state
  const [sessions, setSessions] = useState<SessionRecord[]>([]);
  const [activeSession, setActiveSession] = useState<SessionRecord | null>(null);

  // Sidebar document list (derived from sessions)
  const sidebarDocs: UploadedDoc[] = sessions.map((s) => ({
    id: String(s.id),
    sessionId: s.id,
    name: s.fileName ?? s.title,
    size: 0,
    status: s.docStatus === "COMPLETED" ? "done" : s.docStatus === "FAILED" ? "error" : "uploading",
    chunks: s.chunkCount ?? 0,
  }));

  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);

  // MindMap state
  const [mindMapData, setMindMapData] = useState<MindMapRecord | null>(null);
  const [mindMapLoading, setMindMapLoading] = useState(false);

  const loadSessions = useCallback(async () => {
    try {
      const items = await listSessions();
      setSessions(items);
      setActiveSession((current) => {
        if (current || items.length === 0) return current;
        return items.find((s) => s.docStatus === "COMPLETED") ?? items[0];
      });
    } catch { /* 静默 */ }
  }, []);

  useEffect(() => {
    let cancelled = false;
    const loadMindMap = async () => {
      if (activeTab !== "mindmap" || !activeSession?.docId) return;
      setMindMapLoading(true);
      try {
        const data = await getMindMap(activeSession.docId);
        if (!cancelled) setMindMapData(data);
      } catch {
        if (!cancelled) setMindMapData(null);
      } finally {
        if (!cancelled) setMindMapLoading(false);
      }
    };
    void loadMindMap();
    return () => {
      cancelled = true;
    };
  }, [activeTab, activeSession?.docId]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadSessions();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadSessions]);

  const handleSelectSession = useCallback((sessionId: number) => {
    const s = sessions.find((x) => x.id === sessionId);
    if (s) setActiveSession(s);
  }, [sessions]);

  const handleSwitchToMindMap = useCallback(() => {
    setActiveTab("mindmap");
  }, []);

  const handleUpload = useCallback(async (file: File) => {
    setSessions((prev) => [
      ...prev,
      { id: 0, docId: 0, docUuid: null, title: file.name, status: "ACTIVE",
        fileName: file.name, docStatus: "UPLOADING", chunkCount: 0, vectorCount: 0, createdAt: "" } as SessionRecord,
    ]);

    setUploading(true);
    setUploadProgress(0);

    const progressTimer = setInterval(() => {
      setUploadProgress((p) => Math.min(p + 15, 70));
    }, 200);

    try {
      await uploadDocument(file);
      clearInterval(progressTimer);
      setUploadProgress(100);
      await loadSessions();
      toast.success("文档上传成功，正在异步处理中");
    } catch (err: unknown) {
      clearInterval(progressTimer);
      toast.error(err instanceof Error ? err.message : "上传失败");
    }
    setUploading(false);
  }, [loadSessions]);

  return (
    <div className="flex h-screen w-full overflow-hidden bg-background">
      <AppSidebar
        activeTab={activeTab}
        onTabChange={setActiveTab}
        documents={sidebarDocs}
        activeSessionId={activeSession?.id ?? null}
        onSelectSession={handleSelectSession}
        onUpload={handleUpload}
        collapsed={sidebarCollapsed}
        onToggleCollapse={() => setSidebarCollapsed((v) => !v)}
      />

      <main className="flex-1 min-w-0 flex flex-col overflow-hidden">
        {uploading && (
          <div className="px-6 py-2 border-b bg-muted/20">
            <div className="flex items-center gap-3 max-w-3xl mx-auto">
              <span className="text-[11px] text-muted-foreground shrink-0">上传中</span>
              <Progress value={uploadProgress} className="h-1.5 rounded-full" />
              <span className="text-[11px] text-muted-foreground/50 shrink-0">{uploadProgress}%</span>
            </div>
          </div>
        )}
        {activeTab === "chat" && (
          <ChatRoom docUuid={activeSession?.docUuid ?? null} />
        )}
        {activeTab === "notes" && (
          <StudyNoteView
            docId={activeSession?.docId ?? null}
            docStatus={activeSession?.docStatus ?? null}
          />
        )}
        {activeTab === "flashcards" && (
          <FlashcardView
            docId={activeSession?.docId ?? null}
            docUuid={activeSession?.docUuid ?? null}
            sessionId={activeSession?.id ?? null}
            onMindMapGenerated={handleSwitchToMindMap}
          />
        )}
        {activeTab === "quizzes" && (
          <QuizView
            docId={activeSession?.docId ?? null}
            docUuid={activeSession?.docUuid ?? null}
            sessionId={activeSession?.id ?? null}
            onMindMapGenerated={handleSwitchToMindMap}
          />
        )}
        {activeTab === "mindmap" && (
          <div className="flex-1 min-h-0 overflow-hidden relative z-10">
            {mindMapLoading ? (
              <div className="flex items-center justify-center h-full">
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                  加载思维导图...
                </div>
              </div>
            ) : mindMapData?.content ? (
              <div className="h-full p-4">
                <div className="h-full rounded-2xl border border-white/20 bg-white/40 dark:bg-slate-900/40 backdrop-blur-md overflow-hidden">
                  <MindMapViewer markdown={mindMapData.content} />
                </div>
              </div>
            ) : (
              <div className="flex items-center justify-center h-full">
                <div className="text-center space-y-2">
                  <p className="text-sm text-muted-foreground">
                    {activeSession ? "该文档暂无思维导图，请先选择已完成向量化的文档" : "请先在左侧选择一份文档"}
                  </p>
                </div>
              </div>
            )}
          </div>
        )}
      </main>

      {/* 环境光晕球体 — 品牌色蓝紫渐变 */}
      <div className="orb-blue" />
      <div className="orb-purple" />
    </div>
  );
}
