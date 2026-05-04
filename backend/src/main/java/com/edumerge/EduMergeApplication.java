package com.edumerge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 智融 (EduMerge) AI 学习伴侣平台 - 主应用启动类
 */
@SpringBootApplication
@EnableAsync
@EnableAspectJAutoProxy(proxyTargetClass = true)
@MapperScan("com.edumerge.mapper")
public class EduMergeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EduMergeApplication.class, args);
    }
}
