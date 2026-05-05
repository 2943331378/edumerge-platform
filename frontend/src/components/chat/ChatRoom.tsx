"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { MessageBubble } from "./MessageBubble";
import type { MessageData } from "./MessageBubble";
import { ChatInput } from "./ChatInput";
import { chatStream, chatHistory, listConversations, deleteConversation } from "@/lib/api";
import { ArrowDown, Plus, Trash2, MessageSquare } from "lucide-react";

const CONVOS_KEY = "chat_conversations";
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
}

/** 读写 localStorage 中的会话列表 */
function loadConversations(): Conversation[] {
  try {
    const raw = localStorage.getItem(CONVOS_KEY);
    return raw ? (JSON.parse(raw) as Conversation[]) : [];
  } catch { return []; }
}

function saveConversations(list: Conversation[]) {
  localStorage.setItem(CONVOS_KEY, JSON.stringify(list));
}

export function ChatRoom({ docUuid }: ChatRoomProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [showScrollBtn, setShowScrollBtn] = useState(false);

  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string>("");

  const abortRef = useRef<AbortController | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const lastQuery = useRef<string>("");
  const titledRef = useRef(false); // 是否已用首条消息命名

  // 初始化: 从后端加载会话列表, 后端无数据时创建本地会话
  useEffect(() => {
    (async () => {
      try {
        const remote = await listConversations();
        if (remote.length > 0) {
          const list: Conversation[] = remote.map((r) => ({
            id: r.sessionId,
            title: r.title,
            createdAt: r.createdAt,
          }));
          saveConversations(list);
          setConversations(list);
          const active = localStorage.getItem(ACTIVE_KEY);
          setActiveId(active && list.find((c) => c.id === active) ? active : list[0].id);
          return;
        }
      } catch { /* 后端不可用时回退 localStorage */ }

      // 回退: localStorage
      const list = loadConversations();
      let active = localStorage.getItem(ACTIVE_KEY);
      if (!active || !list.find((c) => c.id === active)) {
        active = createConversation(list);
      }
      setConversations(list);
      setActiveId(active);
    })();
  }, []);

  // 活跃会话变化 → 加载历史
  useEffect(() => {
    if (!activeId) return;
    localStorage.setItem(ACTIVE_KEY, activeId);
    titledRef.current = false;
    loadHistory(activeId);
  }, [activeId]);

  const createConversation = (list?: Conversation[]) => {
    const convs = list ?? loadConversations();
    const c: Conversation = {
      id: crypto.randomUUID(),
      title: "新对话",
      createdAt: new Date().toISOString(),
    };
    convs.unshift(c);
    saveConversations(convs);
    return c.id;
  };

  const handleNewConv = () => {
    const convs = loadConversations();
    const id = createConversation(convs);
    setConversations([...convs]);
    setActiveId(id);
    setMessages([]);
  };

  const handleDeleteConv = (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    // 同步删除后端
    deleteConversation(id).catch(() => {});
    const convs = loadConversations().filter((c) => c.id !== id);
    saveConversations(convs);
    setConversations(convs);

    if (id === activeId) {
      if (convs.length > 0) {
        setActiveId(convs[0].id);
      } else {
        const newId = crypto.randomUUID();
        const c: Conversation = { id: newId, title: "新对话", createdAt: new Date().toISOString() };
        saveConversations([c]);
        setConversations([c]);
        setActiveId(newId);
      }
    }
  };

  const handleSelectConv = (id: string) => {
    if (id === activeId) return;
    setActiveId(id);
    setMessages([]);
  };

  const scrollToBottom = (smooth = true) => {
    bottomRef.current?.scrollIntoView({ behavior: smooth ? "smooth" : "auto" });
  };

  const handleScroll = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    const dist = el.scrollHeight - el.scrollTop - el.clientHeight;
    setShowScrollBtn(dist > 120);
  }, []);

  const loadHistory = async (sid: string) => {
    setHistoryLoading(true);
    try {
      const items = await chatHistory(sid);
      if (!Array.isArray(items)) return;
      const msgs: Message[] = [];
      for (const h of items) {
        msgs.push({ id: `q-${h.id}`, role: "user", content: h.query });
        msgs.push({ id: `a-${h.id}`, role: "assistant", content: h.response });
      }
      setMessages(msgs);
      setTimeout(() => scrollToBottom(false), 50);
    } catch { /* 静默 */ }
    setHistoryLoading(false);
  };

  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    if (nearBottom || !showScrollBtn) {
      scrollToBottom(true);
    }
  }, [messages]);

  const doSend = useCallback(async (text: string) => {
    lastQuery.current = text;

    // 首条消息自动命名
    if (!titledRef.current) {
      titledRef.current = true;
      const title = text.length > 40 ? text.slice(0, 40) + "..." : text;
      const convs = loadConversations();
      const idx = convs.findIndex((c) => c.id === activeId);
      if (idx >= 0) {
        convs[idx].title = title;
        saveConversations(convs);
        setConversations([...convs]);
      }
    }

    const userMsg: Message = { id: crypto.randomUUID(), role: "user", content: text };
    const assistantMsg: Message = { id: crypto.randomUUID(), role: "assistant", content: "", loading: true };

    setMessages((prev) => [...prev, userMsg, assistantMsg]);
    setInput("");
    setLoading(true);
    setTimeout(() => scrollToBottom(false), 50);

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const stream = await chatStream(text, docUuid ?? undefined, activeId);
      const reader = stream.getReader();
      if (!reader) throw new Error("不支持流式读取");

      const decoder = new TextDecoder();
      let buffer = "";

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
          if (payload === "[DONE]") break;

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
      }
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      const msg = err instanceof Error ? err.message : "网络请求失败";
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantMsg.id
            ? { ...m, content: msg, error: true }
            : m
        )
      );
      toast.error("连接中断", { description: msg, duration: 5000 });
    } finally {
      setLoading(false);
      abortRef.current = null;
    }
  }, [docUuid, activeId]);

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
        {conversations.map((c) => (
          <div
            key={c.id}
            onClick={() => handleSelectConv(c.id)}
            className={`group flex items-center gap-1 shrink-0 rounded-lg px-3 py-1.5 text-xs cursor-pointer transition-colors select-none
              ${c.id === activeId
                ? "bg-background shadow-sm text-foreground font-medium"
                : "text-muted-foreground hover:bg-muted/60 hover:text-foreground"
              }`}
          >
            <MessageSquare className="h-3 w-3 shrink-0 opacity-50" />
            <span className="max-w-[140px] truncate">{c.title}</span>
            <button
              onClick={(e) => handleDeleteConv(e, c.id)}
              className="ml-0.5 opacity-0 group-hover:opacity-60 hover:!opacity-100 hover:text-destructive rounded p-0.5 transition-opacity"
              title="删除对话"
            >
              <Trash2 className="h-2.5 w-2.5" />
            </button>
          </div>
        ))}
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
            <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
              <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted/60">
                <span className="text-2xl">📚</span>
              </div>
              <p className="text-sm font-medium">智融 EduMerge AI</p>
              <p className="text-xs text-muted-foreground/60">上传学习资料后开始提问</p>
            </div>
          ) : (
            <div className="mx-auto max-w-3xl">
              {messages.map((m) => (
                <MessageBubble key={m.id} message={m} onRetry={handleRetry} />
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
