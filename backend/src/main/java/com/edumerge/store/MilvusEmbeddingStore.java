package com.edumerge.store;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResultData;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.response.SearchResultsWrapper.IDScore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Milvus 的向量存储实现，桥接 LangChain4j EmbeddingStore 接口与 Milvus SDK
 */
@Slf4j
public class MilvusEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final String FIELD_ID = "id";
    private static final String FIELD_DOCUMENT_ID = "document_id";
    private static final String FIELD_CHUNK_INDEX = "chunk_index";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_EMBEDDING = "embedding";

    private final MilvusServiceClient milvusClient;
    private final String collectionName;
    private final int embeddingDimension;
    private final MetricType metricType;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public MilvusEmbeddingStore(MilvusServiceClient milvusClient,
                                String collectionName,
                                int embeddingDimension,
                                MetricType metricType) {
        this.milvusClient = milvusClient;
        this.collectionName = collectionName;
        this.embeddingDimension = embeddingDimension;
        this.metricType = metricType;
    }

    /**
     * 确保 Milvus 集合已创建，若不存在则自动创建
     */
    private void ensureCollection() {
        if (initialized.get()) return;

        synchronized (this) {
            if (initialized.get()) return;

            R<Boolean> exists = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()
            );

            if (Boolean.TRUE.equals(exists.getData())) {
                log.info("Milvus 集合 [{}] 已存在", collectionName);
                loadCollection();
                initialized.set(true);
                return;
            }

            log.info("正在创建 Milvus 集合: {}", collectionName);
            List<FieldType> fields = new ArrayList<>();

            // 主键 (Int64, 自增)
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_ID)
                    .withDataType(DataType.Int64)
                    .withPrimaryKey(true)
                    .withAutoID(true)
                    .build());

            // 文档 ID
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_DOCUMENT_ID)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(128)
                    .build());

            // 块序号
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_CHUNK_INDEX)
                    .withDataType(DataType.Int32)
                    .build());

            // 文本内容 (最大 65535 字符)
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_TEXT)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build());

            // 向量字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_EMBEDDING)
                    .withDataType(DataType.FloatVector)
                    .withDimension(embeddingDimension)
                    .build());

            R<RpcStatus> createResult = milvusClient.createCollection(
                    CreateCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withFieldTypes(fields)
                            .build()
            );

            if (createResult.getStatus() != 0) {
                throw new RuntimeException("创建 Milvus 集合失败: " + createResult.getMessage());
            }

            log.info("Milvus 集合 [{}] 创建成功, 向量维度: {}", collectionName, embeddingDimension);
            loadCollection();
            initialized.set(true);
        }
    }

    /**
     * 创建索引并加载集合到内存, 使其可检索
     */
    private void loadCollection() {
        // 1. 创建向量字段索引 (Milvus 要求先建索引再加载)
        R<RpcStatus> indexResult = milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName(FIELD_EMBEDDING)
                        .withIndexType(IndexType.IVF_FLAT)
                        .withMetricType(metricType)
                        .withExtraParam("{\"nlist\":128}")
                        .build()
        );
        if (indexResult.getStatus() != 0) {
            throw new RuntimeException("创建 Milvus 索引失败: " + indexResult.getMessage());
        }
        log.info("Milvus 集合 [{}] 索引创建成功", collectionName);

        // 2. 加载集合到内存
        R<RpcStatus> loadResult = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );
        if (loadResult.getStatus() != 0) {
            throw new RuntimeException("加载 Milvus 集合失败: " + loadResult.getMessage());
        }
        log.info("Milvus 集合 [{}] 加载成功", collectionName);
    }

    @Override
    public String add(Embedding embedding) {
        return add(embedding, null);
    }

    @Override
    public void add(String id, Embedding embedding) {
        addSingle(id, embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = UUID.randomUUID().toString();
        addSingle(id, embedding, textSegment);
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return addAllInternal(embeddings, null);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        return addAllInternal(embeddings, textSegments);
    }

    private void addSingle(String id, Embedding embedding, TextSegment textSegment) {
        ensureCollection();

        List<Float> vector = new ArrayList<>(embedding.dimension());
        for (float v : embedding.vector()) {
            vector.add(v);
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(FIELD_DOCUMENT_ID,
                Collections.singletonList(textSegment != null
                        ? textSegment.metadata().getString(FIELD_DOCUMENT_ID) : "")));
        fields.add(new InsertParam.Field(FIELD_CHUNK_INDEX,
                Collections.singletonList(textSegment != null
                        ? textSegment.metadata().getInteger(FIELD_CHUNK_INDEX) : 0)));
        fields.add(new InsertParam.Field(FIELD_TEXT,
                Collections.singletonList(textSegment != null ? textSegment.text() : "")));
        fields.add(new InsertParam.Field(FIELD_EMBEDDING, Collections.singletonList(vector)));

        R<MutationResult> result = milvusClient.insert(
                InsertParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFields(fields)
                        .build()
        );

        if (result.getStatus() != 0) {
            throw new RuntimeException("Milvus 插入向量失败: " + result.getMessage());
        }
    }

    private List<String> addAllInternal(List<Embedding> embeddings, List<TextSegment> textSegments) {
        ensureCollection();

        List<String> ids = new ArrayList<>(embeddings.size());
        List<String> docIdValues = new ArrayList<>();
        List<Integer> chunkIndexValues = new ArrayList<>();
        List<String> textValues = new ArrayList<>();
        List<List<Float>> vectorValues = new ArrayList<>();

        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());

            Embedding embedding = embeddings.get(i);
            TextSegment segment = (textSegments != null && i < textSegments.size())
                    ? textSegments.get(i) : null;

            docIdValues.add(segment != null
                    ? segment.metadata().getString(FIELD_DOCUMENT_ID) : "");
            chunkIndexValues.add(segment != null
                    ? segment.metadata().getInteger(FIELD_CHUNK_INDEX) : i);
            textValues.add(segment != null ? segment.text() : "");

            List<Float> vector = new ArrayList<>(embedding.dimension());
            for (float v : embedding.vector()) {
                vector.add(v);
            }
            vectorValues.add(vector);
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(FIELD_DOCUMENT_ID, docIdValues));
        fields.add(new InsertParam.Field(FIELD_CHUNK_INDEX, chunkIndexValues));
        fields.add(new InsertParam.Field(FIELD_TEXT, textValues));
        fields.add(new InsertParam.Field(FIELD_EMBEDDING, vectorValues));

        R<MutationResult> result = milvusClient.insert(
                InsertParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFields(fields)
                        .build()
        );

        if (result.getStatus() != 0) {
            throw new RuntimeException("Milvus 插入向量失败: " + result.getMessage());
        }

        return ids;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        ensureCollection();

        // 将 LangChain4j Filter 转换为 Milvus 标量过滤表达式
        String filterExpr = toMilvusExpr(request.filter());

        // 组装查询向量
        Embedding queryEmbedding = request.queryEmbedding();
        List<List<Float>> queryVectors = new ArrayList<>();
        List<Float> queryVector = new ArrayList<>(queryEmbedding.dimension());
        for (float v : queryEmbedding.vector()) {
            queryVector.add(v);
        }
        queryVectors.add(queryVector);

        Double minScore = request.minScore();

        // 执行 Milvus 向量检索
        R<SearchResults> searchResult = milvusClient.search(
                SearchParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withVectorFieldName(FIELD_EMBEDDING)
                        .withFloatVectors(queryVectors)
                        .withTopK(request.maxResults())
                        .withMetricType(metricType)
                        .withExpr(filterExpr)
                        .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                        .withOutFields(List.of(FIELD_ID, FIELD_DOCUMENT_ID, FIELD_CHUNK_INDEX, FIELD_TEXT))
                        .build()
        );

        if (searchResult.getStatus() != 0) {
            throw new RuntimeException("Milvus 检索失败: " + searchResult.getMessage());
        }

        // 解析检索结果
        SearchResultData resultData = searchResult.getData().getResults();
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resultData);

        List<IDScore> idScores = wrapper.getIDScore(0);

        // 无结果时直接返回空列表, 避免 getFieldData 在空响应上抛异常
        if (idScores.isEmpty()) {
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }

        // 提取各字段数据 (targetIndex=0 表示第一个查询向量)
        List<?> docIds = wrapper.getFieldData(FIELD_DOCUMENT_ID, 0);
        List<?> chunkIndices = wrapper.getFieldData(FIELD_CHUNK_INDEX, 0);
        List<?> texts = wrapper.getFieldData(FIELD_TEXT, 0);

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (int i = 0; i < idScores.size(); i++) {
            IDScore idScore = idScores.get(i);
            float score = idScore.getScore();

            // 过滤低于最小相似度阈值的结果
            if (minScore != null && score < minScore) continue;

            String text = texts.size() > i ? (String) texts.get(i) : "";
            String documentId = docIds.size() > i ? (String) docIds.get(i) : "";
            Object chunkIdxObj = chunkIndices.size() > i ? chunkIndices.get(i) : 0;
            int chunkIndex = chunkIdxObj instanceof Integer ? (Integer) chunkIdxObj : 0;

            Metadata metadata = new Metadata()
                    .put(FIELD_DOCUMENT_ID, documentId)
                    .put(FIELD_CHUNK_INDEX, chunkIndex);
            TextSegment segment = TextSegment.from(text, metadata);

            matches.add(new EmbeddingMatch<>((double) score, String.valueOf(idScore.getLongID()), null, segment));
        }

        return new EmbeddingSearchResult<>(matches);
    }

    /**
     * 将 LangChain4j Filter 转换为 Milvus 标量过滤表达式
     */
    private String toMilvusExpr(Filter filter) {
        if (filter == null) return "";
        if (filter instanceof IsEqualTo eq) {
            String value = eq.comparisonValue().toString();
            if (eq.comparisonValue() instanceof String) {
                value = "\"" + value + "\"";
            }
            return eq.key() + " == " + value;
        }
        if (filter instanceof And and) {
            return "(" + toMilvusExpr(and.left()) + ") && (" + toMilvusExpr(and.right()) + ")";
        }
        if (filter instanceof Or or) {
            return "(" + toMilvusExpr(or.left()) + ") || (" + toMilvusExpr(or.right()) + ")";
        }
        if (filter instanceof Not not) {
            return "not (" + toMilvusExpr(not.expression()) + ")";
        }
        log.warn("不支持的 Filter 类型: {}", filter.getClass().getSimpleName());
        return "";
    }
}
