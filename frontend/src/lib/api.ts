/**
 * API 抽象层 — 所有后端请求集中管理
 */

const BASE = `http://${typeof window !== "undefined" ? window.location.hostname : "localhost"}:8080/api`;

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  if (json.code !== 0) throw new Error(json.message ?? "请求失败");
  return json.data as T;
}

// ===== 对话会话 (Conversation) =====

export interface ConversationRecord {
  id: number;
  sessionId: string;
  title: string;
  createdAt: string;
}

export async function listConversations(): Promise<ConversationRecord[]> {
  return request<ConversationRecord[]>("/conversations");
}

export async function deleteConversation(sessionId: string): Promise<void> {
  return request<void>(`/conversations/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
}

// ===== 学习会话 (EduMerge Session) =====

export interface SessionRecord {
  id: number;
  docId: number;
  docUuid: string | null;
  title: string;
  status: string;
  fileName: string | null;
  docStatus: string | null;
  chunkCount: number | null;
  vectorCount: number | null;
  createdAt: string;
}

export async function listSessions(): Promise<SessionRecord[]> {
  return request<SessionRecord[]>("/sessions");
}

export async function deleteSession(id: number): Promise<void> {
  return request<void>(`/sessions/${id}`, { method: "DELETE" });
}

// ===== 文档 =====

export interface DocRecord {
  id: number;
  filePath: string;
  fileName: string;
  fileSize: number;
  fileType: string;
  status: string;
  chunkCount: number;
  vectorCount: number;
}

export async function listDocuments(): Promise<DocRecord[]> {
  return request<DocRecord[]>("/documents");
}

export interface UploadResult {
  documentId: string;
  sessionId: number;
  fileName: string;
  size: number;
  status: string;
}

export async function uploadDocument(file: File): Promise<UploadResult> {
  const formData = new FormData();
  formData.append("file", file);
  const res = await fetch(`${BASE}/documents/upload`, { method: "POST", body: formData });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  if (json.code !== 0) throw new Error(json.message ?? "上传失败");
  return json.data;
}

// ===== 对话 =====

export interface SourceRef {
  index: number;
  content: string;
  score: number;
}

export async function chatStream(message: string, documentId?: string, sessionId?: string): Promise<ReadableStream<Uint8Array>> {
  const body: Record<string, unknown> = { message };
  if (sessionId) body.sessionId = sessionId;
  if (documentId) body.documentId = documentId;
  const res = await fetch(`${BASE}/chat/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.body!;
}

export async function chat(message: string, documentId?: string, sessionId?: string): Promise<{ answer: string; sources: SourceRef[] }> {
  const body: Record<string, unknown> = { message };
  if (sessionId) body.sessionId = sessionId;
  if (documentId) body.documentId = documentId;
  return request<{ answer: string; sources: SourceRef[] }>("/rag/chat", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export interface ChatHistoryItem {
  id: number;
  query: string;
  response: string;
  createdAt: string;
}

export async function chatHistory(sessionId?: string): Promise<ChatHistoryItem[]> {
  const params = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : "";
  return request<ChatHistoryItem[]>(`/rag/history${params}`);
}

// ===== 思维导图 (MindMap) =====

export interface MindMapRecord {
  deckId: number;
  docId: number;
  title: string;
  content: string;
  createdAt: string;
}

export async function getMindMap(docId: number): Promise<MindMapRecord> {
  return request<MindMapRecord>(`/mindmap?docId=${docId}`);
}

// ===== 学习笔记 (StudyNote) =====

export interface StudyNoteRecord {
  id?: number;
  deckId: number;
  docId: number;
  title: string;
  content: string;
  sourceSummary?: string;
  createdAt: string;
}

export async function getStudyNote(docId: number): Promise<StudyNoteRecord> {
  return request<StudyNoteRecord>(`/notes?docId=${docId}`);
}

export async function generateStudyNote(docId: number): Promise<StudyNoteRecord> {
  return request<StudyNoteRecord>("/notes/generate", {
    method: "POST",
    body: JSON.stringify({ docId: String(docId) }),
  });
}

// ===== 卡片组 (Deck) =====

export interface DeckRecord {
  id: number;
  docId: number;
  title: string;
  type: "FLASHCARD" | "QUIZ" | "MIND_MAP" | "NOTE";
  createdAt: string;
}

export async function listDecks(docId?: number, type?: string): Promise<DeckRecord[]> {
  const params = new URLSearchParams();
  if (docId) params.set("docId", String(docId));
  if (type) params.set("type", type);
  return request<DeckRecord[]>(`/decks?${params.toString()}`);
}

export async function deleteDeck(id: number): Promise<void> {
  return request<void>(`/decks/${id}`, { method: "DELETE" });
}

// ===== 学习卡片 =====

export interface FlashcardItem {
  id: number;
  question: string;
  answer: string;
  explanation?: string;
  sourceSegment?: string;
}

export async function listFlashcards(docId?: number, sessionId?: number): Promise<FlashcardItem[]> {
  const params = sessionId ? `?sessionId=${sessionId}` : docId ? `?docId=${docId}` : "";
  return request<FlashcardItem[]>(`/flashcards${params}`);
}

export async function listFlashcardsByDeck(deckId: number): Promise<FlashcardItem[]> {
  return request<FlashcardItem[]>(`/flashcards?deckId=${deckId}`);
}

export async function generateFlashcards(docId?: number, docUuid?: string, sessionId?: number): Promise<FlashcardItem[]> {
  const body: Record<string, string> = {};
  if (sessionId) body.sessionId = String(sessionId);
  if (docId && !sessionId) body.docId = String(docId);
  if (docUuid && !sessionId) body.docUuid = docUuid;
  return request<FlashcardItem[]>("/flashcards/generate", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

// ===== 测试题 =====

export interface QuizItem {
  id: number;
  question: string;
  options: string[];
  answer: string;
  explanation?: string;
  quizType?: string;
  difficulty?: number;
}

export async function listQuizzes(docId?: number, sessionId?: number): Promise<QuizItem[]> {
  const params = sessionId ? `?sessionId=${sessionId}` : docId ? `?docId=${docId}` : "";
  const raw = await request<Array<Record<string, unknown>>>(`/quizzes${params}`);
  return raw.map((q) => ({
    id: Number(q.id ?? 0),
    question: String(q.question ?? ""),
    options: typeof q.options === "string" ? JSON.parse(q.options as string) : (q.options as string[] ?? []),
    answer: String(q.answer ?? ""),
    explanation: q.explanation ? String(q.explanation) : undefined,
    quizType: q.quizType ? String(q.quizType) : undefined,
    difficulty: q.difficulty ? Number(q.difficulty) : undefined,
  }));
}

export async function listQuizzesByDeck(deckId: number): Promise<QuizItem[]> {
  const raw = await request<Array<Record<string, unknown>>>(`/quizzes?deckId=${deckId}`);
  return raw.map((q) => ({
    id: Number(q.id ?? 0),
    question: String(q.question ?? ""),
    options: typeof q.options === "string" ? JSON.parse(q.options as string) : (q.options as string[] ?? []),
    answer: String(q.answer ?? ""),
    explanation: q.explanation ? String(q.explanation) : undefined,
    quizType: q.quizType ? String(q.quizType) : undefined,
    difficulty: q.difficulty ? Number(q.difficulty) : undefined,
  }));
}

export async function generateQuizzes(docId?: number, docUuid?: string, sessionId?: number): Promise<QuizItem[]> {
  const body: Record<string, string> = {};
  if (sessionId) body.sessionId = String(sessionId);
  if (docId && !sessionId) body.docId = String(docId);
  if (docUuid && !sessionId) body.docUuid = docUuid;
  return request<QuizItem[]>("/quizzes/generate", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
