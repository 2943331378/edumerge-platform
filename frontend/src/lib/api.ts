/**
 * API 抽象层 — 所有后端请求集中管理
 */

const BASE = process.env.NEXT_PUBLIC_API_BASE ?? "/api";

function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (typeof window !== "undefined") {
    const token = localStorage.getItem("edumerge_token");
    if (token) headers["Authorization"] = `Bearer ${token}`;
  }
  return headers;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: getAuthHeaders(),
    ...options,
  });
  const json = await res.json().catch(() => null);
  if (!res.ok) {
    const msg = json?.message ?? `HTTP ${res.status}`;
    throw new Error(msg);
  }
  if (json?.code !== 0) throw new Error(json?.message ?? "请求失败");
  return json.data as T;
}

// ===== 对话会话 (Conversation) =====

export interface ConversationRecord {
  id: number;
  sessionId: string;
  title: string;
  createdAt: string;
}

export async function listConversations(docId?: number): Promise<ConversationRecord[]> {
  const params = docId != null ? `?docId=${docId}` : "";
  return request<ConversationRecord[]>(`/conversations${params}`);
}

export async function renameConversation(sessionId: string, title: string): Promise<void> {
  return request<void>(`/conversations/${encodeURIComponent(sessionId)}`, { method: "PUT", body: JSON.stringify({ title }) });
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
  fileType: string | null;
  docStatus: string | null;
  chunkCount: number | null;
  vectorCount: number | null;
  pageCount: number | null;
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

export async function deleteDocument(id: number): Promise<void> {
  return request<void>(`/documents/${id}`, { method: "DELETE" });
}

export async function retryDocument(id: number): Promise<void> {
  return request<void>(`/documents/${id}/retry`, { method: "POST" });
}

export async function renameDocument(id: number, title: string): Promise<void> {
  return request<void>(`/documents/${id}`, { method: "PUT", body: JSON.stringify({ title }) });
}

// ===== 文档大纲 (Document Outline) =====

export interface OutlineSection {
  id: string;
  title: string;
  level: number;
  startChunk?: number;
  endChunk?: number;
  children: OutlineSection[];
}

export interface OutlineData {
  docType: string;
  docTypeLabel: string;
  totalChunks: number;
  sections: OutlineSection[];
}

export interface DocumentOutline {
  id: number;
  docId: number;
  docType: string;
  docTypeLabel: string;
  outline: OutlineData;
  version: number;
  createdAt: string;
}

export async function getDocumentOutline(docId: number): Promise<DocumentOutline> {
  return request<DocumentOutline>(`/documents/${docId}/outline`);
}

export async function updateDocumentOutline(docId: number, outline: OutlineData): Promise<DocumentOutline> {
  return request<DocumentOutline>(`/documents/${docId}/outline`, {
    method: "PUT",
    body: JSON.stringify(outline),
  });
}

export async function regenerateDocumentOutline(docId: number): Promise<DocumentOutline> {
  return request<DocumentOutline>(`/documents/${docId}/outline/regenerate`, {
    method: "POST",
  });
}

export function uploadDocument(
  file: File,
  onProgress?: (percent: number) => void,
): Promise<UploadResult> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const formData = new FormData();
    formData.append("file", file);

    xhr.upload.addEventListener("progress", (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    });

    xhr.addEventListener("load", () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const json = JSON.parse(xhr.responseText);
          if (json.code !== 0) reject(new Error(json.message ?? "上传失败"));
          else resolve(json.data);
        } catch {
          reject(new Error("响应解析失败"));
        }
      } else {
        try {
          const json = JSON.parse(xhr.responseText);
          reject(new Error(json.message ?? `HTTP ${xhr.status}`));
        } catch {
          reject(new Error(`HTTP ${xhr.status}`));
        }
      }
    });

    xhr.addEventListener("error", () => reject(new Error("网络错误")));
    xhr.addEventListener("abort", () => reject(new Error("上传已取消")));

    xhr.open("POST", `${BASE}/documents/upload`);
    const token = typeof window !== "undefined" ? localStorage.getItem("edumerge_token") : null;
    if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    xhr.send(formData);
  });
}

// ===== 对话 =====

export interface SourceRef {
  index: number;
  documentId: string;   // 源文档 UUID — 数据可追溯性标识
  chunkIndex: number;   // 文档切片序号 — 精确到段落级溯源
  content: string;
  score: number;
}

export async function chatStream(message: string, documentId?: string, sessionId?: string, docId?: number, activityType?: string, contextHint?: string, signal?: AbortSignal): Promise<ReadableStream<Uint8Array>> {
  const body: Record<string, unknown> = { message };
  if (sessionId) body.sessionId = sessionId;
  if (documentId) body.documentId = documentId;
  if (docId != null) body.docId = docId;
  if (activityType) body.activityType = activityType;
  if (contextHint) body.contextHint = contextHint;
  const res = await fetch(`${BASE}/chat/stream`, {
    method: "POST",
    headers: getAuthHeaders(),
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.body!;
}

export async function chat(message: string, documentId?: string, sessionId?: string, docId?: number, activityType?: string, contextHint?: string): Promise<{ answer: string; sources: SourceRef[] }> {
  const body: Record<string, unknown> = { message };
  if (sessionId) body.sessionId = sessionId;
  if (documentId) body.documentId = documentId;
  if (docId != null) body.docId = docId;
  if (activityType) body.activityType = activityType;
  if (contextHint) body.contextHint = contextHint;
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

export async function markChatHelpful(id: number, isHelpful: number, reason?: string): Promise<void> {
  return request<void>(`/rag/history/${id}/feedback`, {
    method: "PUT",
    body: JSON.stringify({ isHelpful, reason: reason || undefined }),
  });
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

export async function listMindMaps(docId: number): Promise<MindMapRecord[]> {
  return request<MindMapRecord[]>(`/mindmap/list?docId=${docId}`);
}

export async function getMindMapDetail(deckId: number): Promise<MindMapRecord> {
  return request<MindMapRecord>(`/mindmap/detail?deckId=${deckId}`);
}

export async function generateMindMap(docId: number, sectionContext?: string, startChunk?: number, endChunk?: number): Promise<MindMapRecord> {
  const params = new URLSearchParams({ docId: String(docId) });
  if (sectionContext) params.set("sectionContext", sectionContext);
  if (startChunk != null) params.set("startChunk", String(startChunk));
  if (endChunk != null) params.set("endChunk", String(endChunk));
  return request<MindMapRecord>(`/mindmap/generate?${params}`, { method: "POST" });
}

export async function deleteMindMap(deckId: number): Promise<void> {
  return request<void>(`/mindmap/${deckId}`, { method: "DELETE" });
}

// ===== 学习笔记 (StudyNote) =====

export interface StudyNoteRecord {
  id?: number;
  deckId: number;
  docId: number;
  title: string;
  content: string;
  sourceSummary?: string;
  requirements?: string;
  createdAt: string;
}

export async function getStudyNote(docId: number): Promise<StudyNoteRecord> {
  return request<StudyNoteRecord>(`/notes?docId=${docId}`);
}

export async function listNoteHistory(docId: number): Promise<StudyNoteRecord[]> {
  return request<StudyNoteRecord[]>(`/notes/history?docId=${docId}`);
}

export async function generateStudyNote(docId: number, requirements?: string, signal?: AbortSignal, sectionContext?: string, startChunk?: number, endChunk?: number): Promise<StudyNoteRecord> {
  const body: Record<string, string> = { docId: String(docId) };
  if (requirements) body.requirements = requirements;
  if (sectionContext) body.sectionContext = sectionContext;
  if (startChunk != null) body.startChunk = String(startChunk);
  if (endChunk != null) body.endChunk = String(endChunk);
  return request<StudyNoteRecord>("/notes/generate", {
    method: "POST",
    body: JSON.stringify(body),
    signal,
  });
}

export async function updateStudyNote(id: number, data: { content?: string; title?: string }): Promise<StudyNoteRecord> {
  return request<StudyNoteRecord>(`/notes/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
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

export async function generateFlashcards(docId?: number, docUuid?: string, sessionId?: number, signal?: AbortSignal, sectionContext?: string, startChunk?: number, endChunk?: number): Promise<FlashcardItem[]> {
  const body: Record<string, string> = {};
  if (sessionId) body.sessionId = String(sessionId);
  if (docId && !sessionId) body.docId = String(docId);
  if (docUuid && !sessionId) body.docUuid = docUuid;
  if (sectionContext) body.sectionContext = sectionContext;
  if (startChunk != null) body.startChunk = String(startChunk);
  if (endChunk != null) body.endChunk = String(endChunk);
  return request<FlashcardItem[]>("/flashcards/generate", {
    method: "POST",
    body: JSON.stringify(body),
    signal,
  });
}

export async function updateFlashcard(id: number, data: Partial<FlashcardItem>): Promise<void> {
  return request<void>(`/flashcards/${id}`, { method: "PUT", body: JSON.stringify(data) });
}
export async function deleteFlashcard(id: number): Promise<void> {
  return request<void>(`/flashcards/${id}`, { method: "DELETE" });
}

/** SM-2 间隔重复: 提交自评 (quality: 1=忘了 2=模糊 3=记住 4=秒答) */
export async function reviewFlashcard(id: number, quality: number): Promise<FlashcardItem> {
  return request<FlashcardItem>(`/flashcards/${id}/review`, {
    method: "PUT",
    body: JSON.stringify({ quality }),
  });
}

/** 查询到期需复习的卡片 */
export async function listDueFlashcards(docId: number): Promise<FlashcardItem[]> {
  return request<FlashcardItem[]>(`/flashcards/due?docId=${docId}`);
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

export interface QuizAttemptRecord {
  id: number;
  userId: number;
  docId: number;
  deckId: number;
  totalQuestions: number;
  correctCount: number;
  scorePercent: number;
  answerDetails: string;
  createdAt: string;
}

export async function saveQuizAttempt(attempt: {
  docId: number;
  deckId: number;
  totalQuestions: number;
  correctCount: number;
  scorePercent: number;
  answerDetails: string;
}): Promise<QuizAttemptRecord> {
  return request<QuizAttemptRecord>("/quizzes/attempts", {
    method: "POST",
    body: JSON.stringify(attempt),
  });
}

export async function listQuizAttempts(docId: number): Promise<QuizAttemptRecord[]> {
  return request<QuizAttemptRecord[]>(`/quizzes/attempts?docId=${docId}`);
}

export async function generateQuizzes(docId?: number, docUuid?: string, sessionId?: number, signal?: AbortSignal, sectionContext?: string, startChunk?: number, endChunk?: number): Promise<QuizItem[]> {
  const body: Record<string, string> = {};
  if (sessionId) body.sessionId = String(sessionId);
  if (docId && !sessionId) body.docId = String(docId);
  if (docUuid && !sessionId) body.docUuid = docUuid;
  if (sectionContext) body.sectionContext = sectionContext;
  if (startChunk != null) body.startChunk = String(startChunk);
  if (endChunk != null) body.endChunk = String(endChunk);
  return request<QuizItem[]>("/quizzes/generate", {
    method: "POST",
    body: JSON.stringify(body),
    signal,
  });
}

export async function updateQuiz(id: number, data: { question?: string; options?: string[]; answer?: string; explanation?: string }): Promise<void> {
  const body: Record<string, unknown> = {};
  if (data.question !== undefined) body.question = data.question;
  if (data.answer !== undefined) body.answer = data.answer;
  if (data.explanation !== undefined) body.explanation = data.explanation;
  if (data.options !== undefined) body.options = JSON.stringify(data.options);
  return request<void>(`/quizzes/${id}`, { method: "PUT", body: JSON.stringify(body) });
}
export async function deleteQuiz(id: number): Promise<void> {
  return request<void>(`/quizzes/${id}`, { method: "DELETE" });
}

/** 全局错题本 */
export interface ErrorBookItem {
  quizId: number;
  question: string;
  options: string;
  answer: string;
  explanation?: string;
  errorCount: number;
  deckId: number;
}

export async function listErrorBook(docId: number): Promise<ErrorBookItem[]> {
  const raw = await request<ErrorBookItem[]>(`/quizzes/error-book?docId=${docId}`);
  return raw.map((q) => ({
    ...q,
    options: typeof q.options === "string" ? q.options : JSON.stringify(q.options),
  }));
}

/** 按知识点统计正确率 (薄弱度热力图) */
export interface WeaknessItem {
  deckId: number;
  totalQuestions: number;
  correctCount: number;
  accuracyRate: number;
}

export async function listWeakness(docId: number): Promise<WeaknessItem[]> {
  return request<WeaknessItem[]>(`/quizzes/weakness?docId=${docId}`);
}

// ===== 数据资产看板 (Stats) =====

export interface DataAssetMetrics {
  totalDocuments: number;
  totalCharsProcessed: number;
  totalDecks: number;
  totalMindMaps: number;
  totalStudyNotes: number;
  totalFlashcards: number;
  totalQuizzes: number;
  totalChatExchanges: number;
  avgChunksPerDocument: number;
  vectorCoverageRate: number;
}

export interface EfficiencyMetrics {
  estimatedPrepTimeReduction: string;
  estimatedLearningEfficiencyGain: string;
  dataToAssetConversionRate: string;
}

export interface GovernanceMetrics {
  auditPassRate: number;
  totalAuditLogs: number;
  traceableResponseRate: number;
}

export interface EvalMetrics {
  hitRate: number;
  avgFaithfulness: number;
  avgCorrectness: number;
  compositeScore: number;
  totalQuestions: number;
}

export interface StatsResponse {
  dataAssetMetrics: DataAssetMetrics;
  efficiencyMetrics: EfficiencyMetrics;
  governanceMetrics: GovernanceMetrics;
  evalMetrics?: EvalMetrics;
}

export async function getStats(): Promise<StatsResponse> {
  return request<StatsResponse>("/stats");
}

export async function getStatsReport(): Promise<{ format: string; title: string; content: string }> {
  return request<{ format: string; title: string; content: string }>("/stats/report");
}

export interface LearningStatsResponse {
  today: {
    flashcardReviews: number;
    quizAttempts: number;
    quizAccuracy: number;
    totalQuestionsAnswered: number;
    totalCorrect: number;
  };
  weekly: {
    date: string;
    flashcardReviews: number;
    quizAttempts: number;
    quizAccuracy: number;
  }[];
  allTime: {
    totalFlashcardReviews: number;
    totalQuizAttempts: number;
    avgQuizAccuracy: number;
    streakDays: number;
  };
}

export async function getLearningStats(): Promise<LearningStatsResponse> {
  return request<LearningStatsResponse>("/stats/learning");
}

export interface LearnerDashboardResponse {
  today: {
    reviewedCards: number;
    quizAttempts: number;
    quizAccuracy: number;
    correctCount: number;
    totalAnswered: number;
  };
  rhythm: {
    streakDays: number;
    weekly: { date: string; reviews: number; quizzes: number }[];
    monthly: { date: string; reviews: number; quizzes: number }[];
  };
  achievement: {
    totalReviews: number;
    totalQuizzes: number;
    avgAccuracy: number;
    totalDocs: number;
    totalFlashcards: number;
    totalQuizQuestions: number;
  };
  dueDocs: { docId: number; docName: string; dueCount: number }[];
  topErrors: {
    quizId: number;
    question: string;
    answer: string;
    explanation?: string;
    errorCount: number;
    docId: number;
    docName: string;
  }[];
  deckWeaknesses: {
    docId: number;
    docName: string;
    totalQuestions: number;
    correctCount: number;
    accuracyRate: number;
  }[];
  docProgress: {
    docId: number;
    docName: string;
    totalCards: number;
    reviewedCards: number;
    quizAccuracy: number;
    quizTotal: number;
  }[];
  todayTimeline: {
    time: string;
    type: "review" | "quiz";
    description: string;
    docName: string;
    docId: number;
  }[];
  weeklySummary: {
    reviews: number;
    quizzes: number;
    accuracy: number;
    activeDays: number;
    topDocName: string;
    topDocReviews: number;
    prevReviews: number;
    prevQuizzes: number;
    prevAccuracy: number;
  };
}

export async function getLearnerDashboard(): Promise<LearnerDashboardResponse> {
  return request<LearnerDashboardResponse>("/stats/learner");
}

// ===== FlowNote 持续学习日志 =====

export interface FlowNoteItem {
  id: number;
  userId: number;
  docId: number;
  sessionId?: string;
  category: "KEY_POINT" | "QUESTION" | "EXAMPLE" | "REVIEW";
  title: string;
  content: string;
  sourceSegment?: string;
  sourceType: "AI_GENERATED" | "USER_WRITTEN" | "CHAT_EXTRACTED";
  isReviewed: number;
  reviewedAt?: string;
  createdAt: string;
}

export interface FlowNoteStats {
  total: number;
  reviewed: number;
  reviewRate: number;
  byCategory: Record<string, number>;
}

export async function listFlowNotes(docId: number, category?: string): Promise<FlowNoteItem[]> {
  const params = new URLSearchParams({ docId: String(docId) });
  if (category) params.set("category", category);
  return request<FlowNoteItem[]>(`/flownote?${params.toString()}`);
}

export async function extractFlowNotes(docId: number, sessionId?: string, maxExchanges?: number): Promise<FlowNoteItem[]> {
  return request<FlowNoteItem[]>("/flownote/extract", {
    method: "POST",
    body: JSON.stringify({ docId, sessionId, maxExchanges: maxExchanges ?? 10 }),
  });
}

export async function createFlowNote(note: {
  docId: number;
  category: string;
  title: string;
  content: string;
  sessionId?: string;
}): Promise<FlowNoteItem> {
  return request<FlowNoteItem>("/flownote/entries", {
    method: "POST",
    body: JSON.stringify(note),
  });
}

export async function updateFlowNote(id: number, note: Partial<FlowNoteItem>): Promise<void> {
  return request<void>(`/flownote/entries/${id}`, {
    method: "PUT",
    body: JSON.stringify(note),
  });
}

export async function deleteFlowNote(id: number): Promise<void> {
  return request<void>(`/flownote/entries/${id}`, { method: "DELETE" });
}

export async function markFlowNoteReviewed(id: number): Promise<void> {
  return request<void>(`/flownote/entries/${id}/review`, { method: "PUT" });
}

export async function getFlowNoteStats(docId: number): Promise<FlowNoteStats> {
  return request<FlowNoteStats>(`/flownote/stats?docId=${docId}`);
}

// ===== 知识图谱 (Knowledge Graph) =====

export interface ConceptNode {
  id: number;
  name: string;
  definition?: string;
  importance: number;
  documentCount: number;
}

export interface RelationshipLink {
  id: number;
  sourceConceptId: number;
  targetConceptId: number;
  relationshipType: string;
  description?: string;
  strength: number;
}

export interface KnowledgeGraphData {
  concepts: ConceptNode[];
  relationships: RelationshipLink[];
}

export interface ConceptDetail {
  concept: ConceptNode;
  relationships: { id: number; sourceId: number; targetId: number; type: string; description?: string; strength: number }[];
  relatedConcepts: ConceptNode[];
  sourceDocuments: { docId: number; docUuid?: string; mentionText?: string }[];
}

export interface ConceptDocSource {
  docId: number;
  docUuid?: string;
  docTitle?: string;
  fileName?: string;
  sessionId?: number;
  mentionText?: string;
}

export async function getKnowledgeGraph(): Promise<KnowledgeGraphData | null> {
  return request<KnowledgeGraphData | null>("/knowledge-graph");
}

export async function generateKnowledgeGraph(): Promise<KnowledgeGraphData> {
  return request<KnowledgeGraphData>("/knowledge-graph/generate", { method: "POST" });
}

export async function getConceptDetail(conceptId: number): Promise<ConceptDetail> {
  return request<ConceptDetail>(`/knowledge-graph/concepts/${conceptId}`);
}

export async function getConceptDocuments(conceptId: number): Promise<ConceptDocSource[]> {
  return request<ConceptDocSource[]>(`/knowledge-graph/concepts/${conceptId}/documents`);
}
