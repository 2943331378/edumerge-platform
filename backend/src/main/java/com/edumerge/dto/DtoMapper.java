package com.edumerge.dto;

import com.edumerge.entity.*;

import java.util.List;

/**
 * Entity → DTO 转换工具类
 * 隐藏内部字段（userId, filePath, deleted 等），控制 API 契约
 */
public final class DtoMapper {

    private DtoMapper() {}

    public static DocumentResponse toResponse(Document d) {
        if (d == null) return null;
        return DocumentResponse.builder()
                .id(d.getId()).documentId(d.getDocumentId())
                .title(d.getTitle()).description(d.getDescription())
                .fileName(d.getFileName()).fileSize(d.getFileSize()).fileType(d.getFileType())
                .status(d.getStatus()).statusMessage(d.getStatusMessage())
                .chunkCount(d.getChunkCount()).vectorCount(d.getVectorCount())
                .folderId(d.getFolderId())
                .createdAt(d.getCreatedAt()).updatedAt(d.getUpdatedAt())
                .build();
    }

    public static List<DocumentResponse> toResponseList(List<Document> list) {
        return list.stream().map(DtoMapper::toResponse).toList();
    }

    public static FlashcardResponse toResponse(Flashcard f) {
        if (f == null) return null;
        return FlashcardResponse.builder()
                .id(f.getId()).docId(f.getDocId()).deckId(f.getDeckId())
                .question(f.getQuestion()).answer(f.getAnswer()).explanation(f.getExplanation())
                .sourceSegment(f.getSourceSegment()).status(f.getStatus())
                .difficulty(f.getDifficulty()).reviewCount(f.getReviewCount())
                .lastReviewedAt(f.getLastReviewedAt()).easeFactor(f.getEaseFactor())
                .reviewInterval(f.getReviewInterval()).nextReviewAt(f.getNextReviewAt())
                .isImportant(f.getIsImportant() != null && f.getIsImportant() == 1)
                .createdAt(f.getCreatedAt()).updatedAt(f.getUpdatedAt())
                .build();
    }

    public static List<FlashcardResponse> toFlashcardResponseList(List<Flashcard> list) {
        return list.stream().map(DtoMapper::toResponse).toList();
    }

    public static QuizResponse toResponse(Quiz q) {
        if (q == null) return null;
        return QuizResponse.builder()
                .id(q.getId()).docId(q.getDocId()).deckId(q.getDeckId())
                .question(q.getQuestion()).options(q.getOptions()).answer(q.getAnswer())
                .explanation(q.getExplanation()).sourceSegment(q.getSourceSegment())
                .quizType(q.getQuizType()).difficulty(q.getDifficulty()).status(q.getStatus())
                .createdAt(q.getCreatedAt()).updatedAt(q.getUpdatedAt())
                .build();
    }

    public static List<QuizResponse> toQuizResponseList(List<Quiz> list) {
        return list.stream().map(DtoMapper::toResponse).toList();
    }

    public static SessionResponse toResponse(Session s) {
        if (s == null) return null;
        return SessionResponse.builder()
                .id(s.getId()).docId(s.getDocId())
                .title(s.getTitle()).status(s.getStatus())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt())
                .build();
    }

    public static CardDeckResponse toResponse(CardDeck d) {
        if (d == null) return null;
        return CardDeckResponse.builder()
                .id(d.getId()).docId(d.getDocId())
                .title(d.getTitle()).type(d.getType())
                .createdAt(d.getCreatedAt())
                .build();
    }

    public static List<CardDeckResponse> toDeckResponseList(List<CardDeck> list) {
        return list.stream().map(DtoMapper::toResponse).toList();
    }

    public static ChatHistoryResponse toResponse(ChatHistory h) {
        if (h == null) return null;
        return ChatHistoryResponse.builder()
                .id(h.getId()).sessionId(h.getSessionId())
                .query(h.getQuery()).response(h.getResponse())
                .retrievedDocuments(h.getRetrievedDocuments()).confidence(h.getConfidence())
                .isHelpful(h.getIsHelpful()).activityType(h.getActivityType())
                .createdAt(h.getCreatedAt())
                .build();
    }

    public static List<ChatHistoryResponse> toChatHistoryResponseList(List<ChatHistory> list) {
        return list.stream().map(DtoMapper::toResponse).toList();
    }

    public static ConversationResponse toResponse(Conversation c) {
        if (c == null) return null;
        return ConversationResponse.builder()
                .id(c.getId()).sessionId(c.getSessionId())
                .docId(c.getDocId()).title(c.getTitle())
                .createdAt(c.getCreatedAt()).updatedAt(c.getUpdatedAt())
                .build();
    }

    public static List<ConversationResponse> toConversationResponseList(List<Conversation> list) {
        return list.stream().map(DtoMapper::toResponse).toList();
    }

    public static FlowNoteResponse toResponse(FlowNote n) {
        if (n == null) return null;
        return FlowNoteResponse.builder()
                .id(n.getId()).docId(n.getDocId()).sessionId(n.getSessionId())
                .category(n.getCategory()).title(n.getTitle()).content(n.getContent())
                .sourceSegment(n.getSourceSegment()).sourceType(n.getSourceType())
                .isReviewed(n.getIsReviewed()).reviewedAt(n.getReviewedAt())
                .createdAt(n.getCreatedAt()).updatedAt(n.getUpdatedAt())
                .build();
    }

    public static List<FlowNoteResponse> toFlowNoteResponseList(List<FlowNote> list) {
        return list.stream().map(DtoMapper::toResponse).toList();
    }

    public static QuizAttemptResponse toResponse(QuizAttempt a) {
        if (a == null) return null;
        return QuizAttemptResponse.builder()
                .id(a.getId()).docId(a.getDocId()).deckId(a.getDeckId())
                .totalQuestions(a.getTotalQuestions()).correctCount(a.getCorrectCount())
                .scorePercent(a.getScorePercent()).answerDetails(a.getAnswerDetails())
                .createdAt(a.getCreatedAt())
                .build();
    }

    public static List<QuizAttemptResponse> toQuizAttemptResponseList(List<QuizAttempt> list) {
        return list.stream().map(DtoMapper::toResponse).toList();
    }
}
