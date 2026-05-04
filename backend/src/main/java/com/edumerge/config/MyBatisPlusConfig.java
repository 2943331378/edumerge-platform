package com.edumerge.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类
 * 配置分页插件、类型处理器等
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * MyBatis-Plus 拦截器配置
     * 主要用于分页功能
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        
        // 分页拦截器 - 支持 MySQL
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        // 设置请求的页面大于最大页后操作: true 回到首页，false 继续请求
        paginationInterceptor.setOverflow(false);
        // 单页最大条数限制，-1 表示没有限制
        paginationInterceptor.setMaxLimit(500L);
        
        interceptor.addInnerInterceptor(paginationInterceptor);
        
        return interceptor;
    }
}
