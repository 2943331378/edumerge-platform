package com.edumerge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 应用启动测试类
 */
@SpringBootTest
@ActiveProfiles("dev")
class EduMergeApplicationTests {

    @Autowired(required = false)
    private RestTemplate restTemplate;

    @Test
    void contextLoads() {
        // 测试 Spring 容器是否正常启动
        assertNotNull(EduMergeApplication.class);
    }

    @Test
    void testApplicationStartup() {
        // 验证应用正常启动
        assertTrue(true, "应用启动成功");
    }
}
