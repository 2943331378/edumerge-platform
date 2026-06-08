package com.edumerge.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存配置类
 * 配置 Redis 连接池、序列化策略
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * 配置 RedisTemplate
     * 使用 JSON 序列化存储 Java 对象，支持泛型
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // String 序列化器
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        
        // JSON 序列化器（支持泛型和复杂对象）
        GenericJackson2JsonRedisSerializer jsonRedisSerializer = new GenericJackson2JsonRedisSerializer();

        // Key 序列化方式
        template.setKeySerializer(stringRedisSerializer);
        template.setHashKeySerializer(stringRedisSerializer);

        // Value 序列化方式
        template.setValueSerializer(jsonRedisSerializer);
        template.setHashValueSerializer(jsonRedisSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Spring Cache 管理器 — 基于 Redis，JSON 序列化
     * 默认 TTL 30 分钟，dashboard 缓存 5 分钟
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(stringSerializer))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                .entryTtl(Duration.ofMinutes(30))
                .disableCachingNullValues();

        // dashboard 缓存 TTL = 5 分钟（学习数据变化频繁）
        RedisCacheConfiguration dashboardConfig = defaultConfig.entryTtl(Duration.ofMinutes(5));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("dashboard", dashboardConfig)
                .build();
    }

    /**
     * 简化的缓存操作类
     */
    @Bean
    public RedisCache redisCache(RedisTemplate<String, Object> redisTemplate) {
        return new RedisCache(redisTemplate);
    }

    /**
     * Redis 缓存操作工具类
     */
    public static class RedisCache {
        private final RedisTemplate<String, Object> redisTemplate;
        private static final long DEFAULT_EXPIRE = 24; // 24 小时

        public RedisCache(RedisTemplate<String, Object> redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        /**
         * 设置缓存 - 默认 24 小时过期
         */
        public void set(String key, Object value) {
            set(key, value, DEFAULT_EXPIRE, TimeUnit.HOURS);
        }

        /**
         * 设置缓存 - 自定义过期时间
         */
        public void set(String key, Object value, long timeout, TimeUnit unit) {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        }

        /**
         * 获取缓存
         */
        public Object get(String key) {
            return redisTemplate.opsForValue().get(key);
        }

        /**
         * 删除缓存
         */
        public Boolean delete(String key) {
            return redisTemplate.delete(key);
        }

        /**
         * 判断缓存是否存在
         */
        public Boolean hasKey(String key) {
            return redisTemplate.hasKey(key);
        }

        /**
         * 设置过期时间
         */
        public Boolean expire(String key, long timeout, TimeUnit unit) {
            return redisTemplate.expire(key, timeout, unit);
        }

        /**
         * 获取剩余过期时间
         */
        public Long getExpire(String key, TimeUnit unit) {
            return redisTemplate.getExpire(key, unit);
        }
    }
}
