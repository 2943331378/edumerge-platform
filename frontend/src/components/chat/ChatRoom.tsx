"use client";

import { useState, useRef, useCallback, useEffect, useMemo } from "react";

/** crypto.randomUUID polyfill for non-secure contexts (http://) */
function randomId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
    });
  }
}
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { MessageBubble } from "./MessageBubble";
import type { MessageData } from "./MessageBubble";
import { ChatInput } from "./ChatInput";
import { chatStream, chatHistory, listConversations, deleteConversation, renameConversation } from "@/lib/api";
import { ArrowDown, Plus, Trash2, MessageSquare, Sparkles } from "lucide-react";

const ACTIVE_KEY = "active_chat_id";

interface Conversation {
  id: string;
  title: string;
  createdAt: string;
}

interface Message extends MessageData {
  id: string;
  error?: boolean;
}

interface ChatRoomProps {
  docUuid: string | null;
  docId: number | null;
  activityType: string | null;
  contextHint: string | null;
}

export function ChatRoom({ docUuid, docId, activityType, contextHint }: ChatRoomProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [showScrollBtn, setShowScrollBtn] = useState(false);

  // Windowed rendering — only render last N messages to keep DOM light
  const MAX_RENDERED = 100;
  const [visibleCount, setVisibleCount] = useState(MAX_RENDERED);
  const visibleMessages = useMemo(() => {
    if (messages.length <= visibleCount) return messages;
    return messages.slice(messages.length - visibleCount);
  }, [messages, visibleCount]);

  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string>("");

  const abortRef = useRef<AbortController | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const lastQuery = useRef<string>("");
  const titledRef = useRef(false); // 是否已用首条消息命名
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameTitle, setRenameTitle] = useState("");

  // 初始化 + docId 变化时从后端加载该文档的会话列表
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const remote = await listConversations(docId ?? undefined);
        if (cancelled) return;
        if (remote.length > 0) {
          const list: Conversation[] = remote.map((r) => ({
            id: r.sessionId,
            title: r.title,
            createdAt: r.createdAt,
          }));
          setConversations(list);
          // 恢复上次活跃会话，或选第一个
          const saved = localStorage.getItem(ACTIVE_KEY);
          const target = saved && list.some(c => c.id === saved) ? saved : list[0].id;
          setActiveId(target);
          setMessages([]);
          return;
        }
      } catch { /* fallback */ }
      if (cancelled) return;

      const newId = randomId();
      const c: Conversation = { id: newId, title: "新对话", createdAt: new Date().toISOString() };
      setConversations([c]);
      setActiveId(newId);
      setMessages([]);
    })();
    return () => { cancelled = true; };
  }, [docId]);

  // 活跃会话变化 → 加载历史（带取消机制）
  useEffect(() => {
    if (!activeId) return;
    let cancelled = false;
    localStorage.setItem(ACTIVE_KEY, activeId);
    titledRef.current = false;
    setHistoryLoading(true);
    (async () => {
      try {
        const items = await chatHistory(activeId);
        if (cancelled) return;
        if (!Array.isArray(items)) { setHistoryLoading(false); return; }
        const msgs: Message[] = [];
        for (const h of items) {
          msgs.push({ id: `q-${h.id}`, role: "user", content: h.query, chatHistoryId: h.id });
          msgs.push({ id: `a-${h.id}`, role: "assistant", content: h.response, chatHistoryId: h.id });
        }
        setMessages(msgs);
        setTimeout(() => scrollToBottom(false), 50);
      } catch { /* 静默 */ }
      if (!cancelled) setHistoryLoading(false);
    })();
    return () => { cancelled = true; };
  }, [activeId]);

  const handleNewConv = () => {
    const c: Conversation = {
      id: randomId(),
      title: "新对话",
      createdAt: new Date().toISOString(),
    };
    setConversations((prev) => [c, ...prev]);
    setActiveId(c.id);
    setMessages([]);
  };

  const handleDeleteConv = (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    deleteConversation(id).catch(() => {});
    setConversations((prev) => {
      const remaining = prev.filter((c) => c.id !== id);
      if (id === activeId) {
        if (remaining.length > 0) {
          setActiveId(remaining[0].id);
        } else {
          const newId = randomId();
          const c: Conversation = { id: newId, title: "新对话", createdAt: new Date().toISOString() };
          setActiveId(newId);
          return [c];
        }
      }
      return remaining;
    });
  };

  const handleSelectConv = (id: string) => {
    if (id === activeId) return;
    abortRef.current?.abort(); // 取消正在进行的流式请求，避免幽灵 loading 消息
    setActiveId(id);
    setMessages([]);
  };

  const scrollToBottom = (smooth = true) => {
    bottomRef.current?.scrollIntoView({ behavior: smooth ? "smooth" : "auto" });
  };

  // Keep visibleCount in sync when messages change (e.g., history load, conversation switch)
  useEffect(() => {
    setVisibleCount((prev) => Math.max(prev, Math.min(messages.length, MAX_RENDERED)));
  }, [messages.length]);

  const handleScroll = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    const dist = el.scrollHeight - el.scrollTop - el.clientHeight;
    setShowScrollBtn(dist > 120);
  }, []);

  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    if (nearBottom || !showScrollBtn) {
      scrollToBottom(true);
    }
  }, [messages]);

  // Abort in-flight stream on unmount
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const doSend = useCallback(async (text: string) => {
    lastQuery.current = text;

    // 首条消息自动命名
    if (!titledRef.current) {
      titledRef.current = true;
      const title = text.length > 40 ? text.slice(0, 40) + "..." : text;
      setConversations((prev) => prev.map((c) => c.id === activeId ? { ...c, title } : c));
    }

    const userMsg: Message = { id: randomId(), role: "user", content: text };
    const assistantMsg: Message = { id: randomId(), role: "assistant", content: "", loading: true };

    setMessages((prev) => [...prev, userMsg, assistantMsg]);
    setInput("");
    setLoading(true);
    setTimeout(() => scrollToBottom(false), 50);

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const stream = await chatStream(text, docUuid ?? undefined, activeId, docId ?? undefined, activityType ?? undefined, contextHint ?? undefined, controller.signal);
      const reader = stream.getReader();
      if (!reader) throw new Error("不支持流式读取");

      const decoder = new TextDecoder();
      let buffer = "";
      let streamDone = false;

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || !trimmed.startsWith("data:")) continue;
          const payload = trimmed.slice(5).trim();
          if (payload === "[DONE]") { streamDone = true; break; }

          try {
            const data = JSON.parse(payload);
            if (data.token) {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsg.id ? { ...m, content: m.content + data.token } : m
                )
              );
            } else if (data.sources) {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsg.id ? { ...m, sources: data.sources } : m
                )
              );
            } else if (data.error) {
              const errText = String(data.error);
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsg.id
                    ? { ...m, content: errText, error: true }
                    : m
                )
              );
              toast.error("生成失败", { description: errText });
            }
          } catch { /* skip unparseable */ }
        }
        if (streamDone) break;
      }
      reader.cancel();
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      const msg = err instanceof Error ? err.message : "网络请求失败";
      // Preserve already-received content — don't wipe it with the error
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantMsg.id && !m.content
            ? { ...m, content: msg, error: true }
            : m
        )
      );
      if (msg.includes("chunked") || msg.includes("interrupted") || msg.includes("network")) {
        // Connection interrupted after content received — content is preserved, just notify
        toast.error("连接中断，但已收到的内容已保留", { duration: 3000 });
      }
    } finally {
      setLoading(false);
      abortRef.current = null;
    }
  }, [docUuid, docId, activityType, contextHint, activeId]);

  const handleSend = useCallback(() => {
    const text = input.trim();
    if (!text || loading) return;
    doSend(text);
  }, [input, loading, doSend]);

  const handleRetry = useCallback(() => {
    if (loading) return;
    setMessages((prev) => {
      const idx = prev.length - 1;
      if (idx < 0 || prev[idx].role !== "assistant" || !prev[idx].error) return prev;
      return prev.slice(0, idx - 1);
    });
    const q = lastQuery.current;
    if (q) doSend(q);
  }, [loading, doSend]);

  const handleStop = useCallback(() => abortRef.current?.abort(), []);

  return (
    <div className="flex flex-col h-full">
      {/* 会话列表 Header */}
      <div className="flex items-center gap-1.5 px-4 py-2 border-b bg-muted/20 overflow-x-auto shrink-0">
        {conversations.map((c) => {
          const isRenaming = renamingId === c.id;
          const handleRename = async () => {
            const title = renameTitle.trim();
            if (!title || title === c.title) { setRenamingId(null); return; }
            try {
              await renameConversation(c.id, title);
              setConversations((prev) => prev.map((x) => x.id === c.id ? { ...x, title } : x));
            } catch { toast.error("重命名失败"); }
            setRenamingId(null);
          };
          return (
          <div
            key={c.id}
            onClick={() => { if (!isRenaming) handleSelectConv(c.id); }}
            className={`group flex items-center gap-1 shrink-0 rounded-lg px-3 py-1.5 text-xs cursor-pointer transition-colors select-none
              ${c.id === activeId
                ? "bg-background shadow-sm text-foreground font-medium"
                : "text-muted-foreground hover:bg-muted/60 hover:text-foreground"
              }`}
          >
            <MessageSquare className="h-3 w-3 shrink-0 opacity-50" />
            {isRenaming ? (
              <input
                value={renameTitle}
                onChange={(e) => setRenameTitle(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") handleRename(); if (e.key === "Escape") setRenamingId(null); }}
                onBlur={handleRename}
                autoFocus
                className="w-[120px] bg-background border border-primary/30 rounded px-1 py-0 text-xs outline-none"
                onClick={(e) => e.stopPropagation()}
              />
            ) : (
              <span
                className="max-w-[140px] truncate"
                onDoubleClick={() => { setRenamingId(c.id); setRenameTitle(c.title); }}
                title="双击重命名"
              >{c.title}</span>
            )}
            <button
              onClick={(e) => handleDeleteConv(e, c.id)}
              className="ml-0.5 opacity-0 group-hover:opacity-60 hover:!opacity-100 hover:text-destructive rounded p-0.5 transition-opacity"
              title="删除对话"
            >
              <Trash2 className="h-2.5 w-2.5" />
            </button>
          </div>
        )})}
        <Button
          variant="ghost"
          size="icon"
          className="h-7 w-7 rounded-lg shrink-0 ml-1"
          onClick={handleNewConv}
          title="新建对话"
        >
          <Plus className="h-3.5 w-3.5" />
        </Button>
      </div>

      {/* 消息列表 */}
      <div className="flex-1 relative">
        <div
          ref={listRef}
          onScroll={handleScroll}
          className="absolute inset-0 overflow-y-auto"
          role="log"
          aria-live="polite"
          aria-label="对话消息"
        >
          {historyLoading ? (
            <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
              <div className="flex flex-col gap-3 w-full max-w-3xl mx-auto px-6 py-12">
                {[1, 2, 3].map((i) => (
                  <div key={i} className="animate-pulse space-y-2">
                    <div className={`h-3 bg-muted-foreground/10 rounded w-1/4 ${i % 2 === 0 ? "ml-auto" : ""}`} />
                    <div className={`h-12 bg-muted-foreground/5 rounded-lg ${i % 2 === 0 ? "ml-4" : "mr-4"}`} />
                  </div>
                ))}
                <p className="text-center text-xs text-muted-foreground/40 mt-2">加载历史记录中...</p>
              </div>
            </div>
          ) : messages.length === 0 ? (
            <EmptyState activityType={activityType} docId={docId} contextHint={contextHint} onSelectQuestion={(q) => { if (!loading) doSend(q); }} />
          ) : (
            <div className="mx-auto max-w-3xl">
              {messages.length > MAX_RENDERED && (
                <div className="text-center py-3">
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-xs text-muted-foreground"
                    onClick={() => setVisibleCount((c) => Math.min(c + 50, messages.length))}
                  >
                    加载更早的消息 ({messages.length - visibleCount} 条)
                  </Button>
                </div>
              )}
              {visibleMessages.map((m) => (
                <MessageBubble key={m.id} message={m} onRetry={handleRetry} docId={docId} />
              ))}
              <div ref={bottomRef} className="h-1" />
            </div>
          )}
        </div>

        {showScrollBtn && !historyLoading && (
          <div className="absolute bottom-4 left-1/2 -translate-x-1/2 z-10">
            <Button
              size="icon"
              variant="secondary"
              className="h-8 w-8 rounded-full shadow-md"
              onClick={() => { scrollToBottom(true); setShowScrollBtn(false); }}
            >
              <ArrowDown className="h-4 w-4" />
            </Button>
          </div>
        )}
      </div>

      <ChatInput
        value={input}
        onChange={setInput}
        onSend={handleSend}
        onStop={handleStop}
        loading={loading}
      />
    </div>
  );
}

const SUGGESTED_QUESTIONS: Record<string, string[]> = {
  notes: [
    "这篇文章的核心论点是什么？",
    "作者用了哪些论据支持他的观点？",
    "这篇文章和我之前学的有什么关联？",
  ],
  mindmap: [
    "导图中的某个主题能展开讲讲吗？",
    "这些概念之间的逻辑关系是怎样的？",
    "能用一句话总结这张导图的核心吗？",
  ],
  flashcards: [
    "能举个实际应用的例子吗？",
    "这个概念为什么重要？",
    "考试一般会怎么考察这个知识点？",
  ],
  quiz: [
    "为什么其他选项不正确？",
    "能再详细解释一下这道题的原理吗？",
    "这类题型有什么解题技巧？",
  ],
  flownote: [
    "我应该重点复习哪些内容？",
    "这些知识点有什么内在联系？",
    "哪些内容我可能还没完全掌握？",
  ],
};

function getContextualQuestions(activityType: string | null, contextHint: string | null): string[] {
  const fallback = (activityType && SUGGESTED_QUESTIONS[activityType])
    ? SUGGESTED_QUESTIONS[activityType]
    : ["这篇文档主要讲了什么？", "帮我整理一下核心知识点", "生成一份学习笔记"];
  if (!contextHint) return fallback;
  const quoteMatch = contextHint.match(/"([^"]+)"/);
  const topic = quoteMatch ? quoteMatch[1] : "";
  if (!topic || topic.length > 50) return fallback;
  if (activityType === "flashcards") {
    return [`关于"${topic}"，能举个实际应用的例子吗？`, `"${topic}"和其他概念有什么联系？`, `考试一般会怎么考察"${topic}"？`];
  }
  if (activityType === "quiz") {
    return [`能详细解释一下"${topic}"的原理吗？`, `"${topic}"有哪些常见的误区？`, `关于"${topic}"，还有其他类似的题型吗？`];
  }
  if (activityType === "notes") {
    return [`能再详细解释一下"${topic}"吗？`, `"${topic}"的核心要点是什么？`, `关于"${topic}"，有哪些需要特别注意的地方？`];
  }
  return [`能详细解释一下"${topic}"吗？`, `"${topic}"在实际中如何应用？`, `"${topic}"和其他概念有什么联系？`];
}

function EmptyState({
  activityType,
  docId,
  contextHint,
  onSelectQuestion,
}: {
  activityType: string | null;
  docId: number | null;
  contextHint: string | null;
  onSelectQuestion: (text: string) => void;
}) {
  const questions = getContextualQuestions(activityType, contextHint);

  return (
    <div className="flex flex-col items-center justify-center h-full gap-5 text-muted-foreground px-6">
      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted/60">
        <span className="text-2xl">📚</span>
      </div>
      <div className="text-center space-y-1">
        <p className="text-sm font-medium">智融 EduMerge AI</p>
        <p className="text-xs text-muted-foreground/60">
          {!docId
            ? "上传学习资料后开始提问"
            : "试试下面的问题，或直接输入你的疑问"}
        </p>
      </div>
      <div className="flex flex-col gap-1.5 w-full max-w-xs">
        {questions.map((q) => (
          <button
            key={q}
            type="button"
            onClick={() => onSelectQuestion(q)}
            disabled={false}
            className="flex items-center gap-2 rounded-lg border border-border/50 bg-muted/20 px-3 py-2 text-xs text-left text-muted-foreground hover:text-foreground hover:border-primary/30 hover:bg-muted/40 active:scale-[0.98] transition-all"
          >
            <Sparkles className="h-3 w-3 shrink-0 text-primary/50" />
            <span>{q}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
