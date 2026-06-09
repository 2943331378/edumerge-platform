import { useState, useCallback, useEffect } from "react";
import { toast } from "sonner";
import type { SessionRecord, MindMapRecord, StudyNoteRecord } from "@/lib/api";
import { listSessions, deleteDocument, retryDocument, renameDocument } from "@/lib/api";

export interface SessionCacheEntry {
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
}

export function useSessionState(onSessionChange?: () => void) {
  const [sessions, setSessions] = useState<SessionRecord[]>([]);
  const [activeSession, setActiveSession] = useState<SessionRecord | null>(null);
  const [sessionCache, setSessionCache] = useState<Map<number, SessionCacheEntry>>(new Map());

  const loadSessions = useCallback(async () => {
    try {
      const list = await listSessions();
      setSessions(list);
      setActiveSession((cur) => {
        if (!cur?.id) {
          const completed = list.find((s) => s.docStatus === "COMPLETED");
          return completed ?? list[0] ?? null;
        }
        const updated = list.find((s) => s.id === cur.id);
        return updated ?? cur;
      });
    } catch { toast.error("加载会话列表失败"); }
  }, []);

  const updateSessionCache = useCallback((updates: Partial<SessionCacheEntry>) => {
    setActiveSession((curSession) => {
      if (!curSession) return curSession;
      setSessionCache((prev) => {
        const next = new Map(prev);
        const existing = next.get(curSession.id) ?? {};
        next.set(curSession.id, { ...existing, ...updates });
        return next;
      });
      return curSession; // don't change activeSession
    });
  }, []);

  const handleSelectSession = useCallback((sessionId: number) => {
    const s = sessions.find((x) => x.id === sessionId);
    if (s) {
      setActiveSession(s);
      onSessionChange?.();
    }
  }, [sessions, onSessionChange]);

  const handleDeleteDocument = useCallback(async (sessionId: number) => {
    const session = sessions.find((s) => s.id === sessionId);
    if (!session?.docId) return;
    if (!confirm(`确定删除「${session.fileName ?? session.title}」及其全部关联数据吗？此操作不可撤销。`)) return;
    try {
      await deleteDocument(session.docId);
      toast.success("文档已删除");
      setActiveSession((cur) => {
        if (cur?.id === sessionId) {
          setSessionCache((prev) => {
            const next = new Map(prev);
            next.delete(sessionId);
            return next;
          });
          return null;
        }
        return cur;
      });
      await loadSessions();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败");
    }
  }, [sessions, loadSessions]);

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

  return {
    sessions,
    activeSession,
    setActiveSession,
    sessionCache,
    setSessionCache,
    loadSessions,
    updateSessionCache,
    handleSelectSession,
    handleDeleteDocument,
    handleRetryDocument,
    handleRenameDocument,
  };
}
