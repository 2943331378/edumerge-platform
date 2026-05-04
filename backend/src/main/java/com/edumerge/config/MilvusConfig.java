package com.edumerge.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.partition.HasPartitionParam;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Milvus 向量数据库配置类
 * 配置向量数据库连接
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private Integer port;

    @Value("${milvus.database:edumerge}")
    private String database;

    @Value("${milvus.timeout:30000}")
    private Long timeout;

    /**
     * 创建 Milvus 服务客户端
     */
    @Bean
    @Lazy
    public MilvusServiceClient milvusServiceClient() {
        try {
            log.info("正在连接 Milvus: {}:{}", host, port);

            MilvusServiceClient milvusClient = new MilvusServiceClient(
                    ConnectParam.newBuilder()
                            .withHost(host)
                            .withPort(port)
                            .withDatabaseName(database)
                            .build()
            );

            log.info("Milvus 连接成功");
            return milvusClient;
        } catch (Exception e) {
            log.error("Milvus 连接失败: {}", e.getMessage(), e);
            throw new RuntimeException("Milvus 连接失败", e);
        }
    }

    /**
     * Milvus 操作工具类
     */
    @Bean
    public MilvusOperationHelper milvusOperationHelper(MilvusServiceClient milvusClient) {
        return new MilvusOperationHelper(milvusClient);
    }

    /**
     * Milvus 数据库操作辅助类
     */
    @Slf4j
    public static class MilvusOperationHelper {
        private final MilvusServiceClient milvusClient;

        public MilvusOperationHelper(MilvusServiceClient milvusClient) {
            this.milvusClient = milvusClient;
        }

        /**
         * 获取 Milvus 客户端
         */
        public MilvusServiceClient getClient() {
            return milvusClient;
        }

        /**
         * 检查集合是否存在
         */
        public boolean collectionExists(String collectionName) {
            try {
                R<Boolean> response = milvusClient.hasCollection(
                        HasCollectionParam.newBuilder()
                                .withCollectionName(collectionName)
                                .build()
                );
                return Boolean.TRUE.equals(response.getData());
            } catch (Exception e) {
                log.error("检查集合失败: {}", collectionName, e);
                return false;
            }
        }

        /**
         * 获取集合统计信息
         */
        public long getCollectionRowCount(String collectionName) {
            try {
                R<GetCollectionStatisticsResponse> response = milvusClient.getCollectionStatistics(
                        GetCollectionStatisticsParam.newBuilder()
                                .withCollectionName(collectionName)
                                .build()
                );
                GetCollectionStatisticsResponse stats = response.getData();
                if (stats != null) {
                    for (io.milvus.grpc.KeyValuePair kv : stats.getStatsList()) {
                        if ("row_count".equals(kv.getKey())) {
                            return Long.parseLong(kv.getValue());
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                log.error("获取集合行数失败: {}", collectionName, e);
                return 0;
            }
        }

        /**
         * 检查集合中的分区
         */
        public boolean partitionExists(String collectionName, String partitionName) {
            try {
                R<Boolean> response = milvusClient.hasPartition(
                        HasPartitionParam.newBuilder()
                                .withCollectionName(collectionName)
                                .withPartitionName(partitionName)
                                .build()
                );
                return Boolean.TRUE.equals(response.getData());
            } catch (Exception e) {
                log.error("检查分区失败: {} in {}", partitionName, collectionName, e);
                return false;
            }
        }
    }
}
