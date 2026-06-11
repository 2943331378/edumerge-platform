# EduMerge 后台管理系统 — 技术设计文档

> 版本：v3.0 | 日期：2026-06-09 | 状态：评审通过（10 轮全面评审）

---

## 目录

1. [项目背景](#1-项目背景)
2. [设计目标](#2-设计目标)
3. [系统架构](#3-系统架构)
4. [数据库设计](#4-数据库设计)
5. [安全架构](#5-安全架构)
6. [后端设计](#6-后端设计)
7. [前端设计](#7-前端设计)
8. [创新功能详设](#8-创新功能详设)
9. [性能与可扩展性](#9-性能与可扩展性)
10. [数据完整性保障](#10-数据完整性保障)
11. [错误处理策略](#11-错误处理策略)
12. [迁移方案](#12-迁移方案)
13. [测试策略](#13-测试策略)
14. [实施计划](#14-实施计划)
15. [文件清单](#15-文件清单)
16. [验证方案](#16-验证方案)

---

## 1. 项目背景

### 1.1 现状分析

EduMerge 是一个 AI 驱动的教育学习平台，后端基于 Spring Boot 3.2.4 + MyBatis-Plus，前端基于 Next.js 16 + shadcn/ui + Tailwind v4。当前系统：

- **19 个实体**，20 张数据库表，16 个 Controller，17 个 Service
- **无角色系统**：`users` 表仅有 `id, username, email, password, displayName, status`，无 `role` 字段
- **无管理员端点**：所有 Controller 通过 `SecurityUtils.getCurrentUserId()` 做所有权校验，无管理员旁路
- **`system_logs` 表已存在**（schema.sql 108-126 行）但无对应的 Entity、Mapper、Service、Controller
- **默认管理员种子** `admin / admin123` 存在于 schema.sql 但无任何特权
- **已有的可复用基础设施**：SSE 实时推送、`react-force-graph-2d` 知识图谱、`CircuitBreaker` 断路器、`LearnerDashboardService` 聚合模式、`chat_history` 质量信号

### 1.2 问题定义

| 问题 | 影响 |
|---|---|
| 无用户管理能力 | 无法查看/禁用/删除用户 |
| 无内容监控 | 无法跨用户浏览文档、闪卡、测验 |
| 无 AI 用量可观测性 | 无法追踪 token 消耗、生成质量、断路器状态 |
| 无审计日志 | 关键操作无法追溯 |
| 无系统配置管理 | 参数变更需修改代码重新部署 |

---

## 2. 设计目标

### 2.1 核心目标

| 目标 | 描述 |
|---|---|
| 用户管理 | 查看、搜索、启用/禁用、重置密码、修改角色、删除用户 |
| 内容监控 | 跨用户浏览文档、查看健康状态、一键重试失败文档 |
| AI 可观测性 | 断路器状态、token 消耗趋势、对话好评率、各模块用量 |
| 审计日志 | 记录所有管理员写操作，支持分页/筛选/搜索 |
| 系统配置 | 运行时修改系统参数（功能开关、限制阈值等） |

### 2.2 创新目标

| 功能 | 亮点 |
|---|---|
| 实时管理看板 | SSE 广播模式，每 5 秒推送在线用户/AI 任务/断路器/错误率 |
| 全平台知识图谱 | 跨用户概念网络可视化，节点大小=用户覆盖数 |
| 文档健康诊断 | 失败/卡死文档自动识别，含诊断信息和一键重试 |
| 全平台学习热力图 | 30 天 GitHub 风格热力图，点击展开当日详情 |
| 智能告警 | 断路器打开/失败率飙升/好评率下降自动触发 |
| 命令面板 | Cmd+K 快速搜索用户/文档/操作 |
| 用户模拟登录 | 管理员以任意用户身份登录查看，15 分钟自动过期 |
| 数据导出 | 用户/文档列表 CSV 导出 |

---

## 3. 系统架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                    Frontend (Next.js 16)                │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐ │
│  │ User App (/) │  │ Admin (/admin)│  │  Middleware   │ │
│  │ 6-step flow  │  │ 5-section    │  │ JWT role check│ │
│  └──────────────┘  └──────────────┘  └───────────────┘ │
└─────────────────────┬───────────────────────────────────┘
                      │ HTTP + SSE
┌─────────────────────┴───────────────────────────────────┐
│                  Backend (Spring Boot 3.2.4)             │
│  ┌─────────────────────────────────────────────────────┐│
│  │            Spring Security + JWT Filter              ││
│  │   /admin/** → ROLE_ADMIN   |   /** → authenticated  ││
│  └─────────────────────────────────────────────────────┘│
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ │
│  │ User Ctrl│ │ Doc Ctrl │ │ AI Ctrl  │ │ Admin Ctrls│ │
│  │ (16个)   │ │          │ │          │ │ (5个)      │ │
│  └──────────┘ └──────────┘ └──────────┘ └───────────┘ │
│  ┌─────────────────────────────────────────────────────┐│
│  │                    Service Layer                     ││
│  │  UserService | DocumentService | ... | Admin Services││
│  └─────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────┐│
│  │         MySQL + Milvus + Redis + RabbitMQ           ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
```

### 3.2 Admin 模块架构

```
controller/admin/
├── AdminDashboardController   — 概览指标 + SSE 监控流 + 热力图
├── AdminUserController        — 用户 CRUD + 模拟登录 + 导出
├── AdminDocumentController    — 文档管理 + 健康诊断 + 重试
├── AdminAiMonitorController   — 断路器 + Token 趋势 + 好评率
└── AdminSystemController      — 系统日志 + 配置管理

service/admin/
├── AdminUserService           — 用户管理业务逻辑
├── AdminDocumentService       — 文档管理 + 健康诊断
├── AdminStatsService          — 平台级统计聚合（Redis 缓存）
├── AdminMonitorService        — SSE 广播 + 指标采集 + 告警
└── AdminConfigService         — 系统配置 CRUD
```

---

## 4. 数据库设计

### 4.1 变更概览

| 变更类型 | 表/列 | 说明 |
|---|---|---|
| 新增列 | `users.role` | VARCHAR(20) DEFAULT 'USER'，用户角色 |
| 新增列 | `documents.version` | INT DEFAULT 0，乐观锁版本号 |
| 新增表 | `system_config` | 系统配置键值对 |
| 已有表 | `system_logs` | 无需修改，已就绪 |

### 4.2 `users` 表新增 `role` 列

```sql
-- 条件执行，防止重复列错误
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'users'
    AND COLUMN_NAME = 'role');

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE users ADD COLUMN role VARCHAR(20) DEFAULT ''USER'' COMMENT ''用户角色: USER/ADMIN''',
    'SELECT 1');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 更新已有 admin 用户（幂等）
INSERT INTO users (username, email, password, display_name, status, role)
VALUES ('admin', 'admin@edumerge.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    '管理员', 1, 'ADMIN')
ON DUPLICATE KEY UPDATE role = 'ADMIN';
```

**字段说明：**

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `role` | VARCHAR(20) | 'USER' | 用户角色：`USER`=普通用户，`ADMIN`=管理员 |

### 4.3 `documents` 表新增 `version` 列

```sql
ALTER TABLE documents ADD COLUMN version INT DEFAULT 0 COMMENT '乐观锁版本号';
```

用于防止管理员修改文档状态时与后台处理任务冲突（详见[第 10 节](#10-数据完整性保障)）。

### 4.4 `system_config` 表

```sql
CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '配置ID',
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    description VARCHAR(500) COMMENT '配置描述',
    updated_by BIGINT COMMENT '最后修改人ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- 种子数据
INSERT IGNORE INTO system_config (config_key, config_value, description) VALUES
('site.name', 'EduMerge', '站点名称'),
('feature.registration.enabled', 'true', '是否开放注册'),
('feature.ai_generation.enabled', 'true', '是否启用 AI 生成功能'),
('feature.sse_monitor.enabled', 'true', '是否启用实时监控 SSE'),
('upload.max_size_mb', '50', '上传文件大小限制 (MB)'),
('ai.circuit_breaker.threshold', '5', '断路器失败阈值'),
('ai.circuit_breaker.cooldown_ms', '30000', '断路器冷却时间 (ms)');
```

### 4.5 `system_logs` 表（已有，无需修改）

```sql
-- schema.sql 中已定义（108-126 行）
CREATE TABLE IF NOT EXISTS system_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    user_id BIGINT COMMENT '用户ID',
    action VARCHAR(100) COMMENT '操作类型',
    resource_type VARCHAR(50) COMMENT '资源类型',
    resource_id BIGINT COMMENT '资源ID',
    request_data LONGTEXT COMMENT '请求数据',
    response_data LONGTEXT COMMENT '响应数据',
    status INT COMMENT '状态码',
    error_message VARCHAR(500) COMMENT '错误信息',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    user_agent VARCHAR(500) COMMENT '用户代理',
    duration_ms BIGINT COMMENT '执行耗时（毫秒）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_action (action),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统日志表';
```

**注意**：`user_id` 列无外键约束，用户删除后日志保留。

---

## 5. 安全架构

### 5.1 角色系统设计

采用 **RBAC（基于角色的访问控制）** 模型，当前仅两个角色：

| 角色 | 权限 | 说明 |
|---|---|---|
| `USER` | 所有用户端功能 | 默认角色，注册时自动分配 |
| `ADMIN` | 所有功能 + 管理后台 | 由管理员手动分配 |

### 5.2 角色传播链路

```
┌──────────┐     ┌──────────┐     ┌──────────────┐     ┌───────────────┐
│  MySQL   │────▶│  User    │────▶│ JwtUtils     │────▶│ JWT Token     │
│ users.   │     │ Entity   │     │ .generate    │     │ {role:"ADMIN"}│
│ role     │     │ .role    │     │ Token(role)  │     │               │
└──────────┘     └──────────┘     └──────────────┘     └───────┬───────┘
                                                               │
┌──────────────────────────────────────────────────────────────┘
│
▼
┌──────────────┐     ┌──────────────┐     ┌───────────────┐
│ JwtAuthFilter│────▶│ AuthUser     │────▶│ GrantedAuth   │
│ .getRole()   │     │ (userId,     │     │ "ROLE_ADMIN"  │
│ from token   │     │  username,   │     │               │
│              │     │  role)       │     │               │
└──────────────┘     └──────────────┘     └───────┬───────┘
                                                   │
┌──────────────────────────────────────────────────┘
│
▼
┌──────────────┐     ┌──────────────┐     ┌───────────────┐
│ Security     │────▶│ @PreAuthorize│────▶│ Admin         │
│ Config       │     │ hasAuthority │     │ Controller    │
│ /admin/**    │     │ ('ROLE_ADMIN')│     │ Methods       │
│ ROLE_ADMIN   │     │              │     │               │
└──────────────┘     └──────────────┘     └───────────────┘
```

### 5.3 后端安全改动

#### 5.3.1 `User` 实体

```java
// 新增字段
private String role; // "USER" or "ADMIN"
```

#### 5.3.2 `AuthUser` 安全主体

```java
@Getter
@AllArgsConstructor
public class AuthUser {
    private Long userId;
    private String username;
    private String role; // 新增
}
```

#### 5.3.3 `JwtUtils` — Token 生成与解析

```java
// 修改：增加 role 参数
public String generateToken(Long userId, String username, String role) {
    return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("username", username)
            .claim("role", role != null ? role : "USER") // null 安全
            .claim("type", "access")
            .issuedAt(now)
            .expiration(new Date(now.getTime() + expirationMs))
            .signWith(key)
            .compact();
}

// 新增：提取 role
public String getRole(String token) {
    String role = parseToken(token).get("role", String.class);
    return role != null ? role : "USER"; // 兼容旧 token
}
```

#### 5.3.4 `JwtAuthFilter` — 角色提取与权限设置

```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain filterChain) throws ServletException, IOException {
    String token = extractToken(request);
    if (token != null && jwtUtils.validateToken(token)) {
        Long userId = jwtUtils.getUserId(token);
        String username = jwtUtils.parseToken(token).get("username", String.class);
        String role = jwtUtils.getRole(token); // 提取 role

        AuthUser authUser = new AuthUser(userId, username, role);

        // 设置权限（关键改动：替换原来的 Collections.emptyList()）
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_" + role));

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(authUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    filterChain.doFilter(request, response);
}
```

#### 5.3.5 `SecurityConfig` — URL 级 + 方法级双重保护

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // 新增：启用方法级安全
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // ... 省略其他配置 ...
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/auth/**", "/health/**", "/error", "/actuator/**").permitAll()
                .requestMatchers("/admin/**").hasAuthority("ROLE_ADMIN")  // 新增
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

#### 5.3.6 `SecurityUtils` — 角色访问工具

```java
// 新增方法
public static String getCurrentUserRole() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthUser authUser) {
        return authUser.getRole();
    }
    throw new AuthenticationCredentialsNotFoundException("用户未认证");
}

public static boolean isAdmin() {
    return "ADMIN".equals(getCurrentUserRole());
}
```

#### 5.3.7 Admin Controller 方法级注解

```java
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")  // 类级保护
public class AdminUserController {

    @GetMapping
    public Result<PageResponse<AdminUserResponse>> listUsers(...) { ... }

    @DeleteMapping("/{id}")
    public Result<AdminDeleteResult> deleteUser(@PathVariable Long id) { ... }
}
```

### 5.4 前端安全改动

#### 5.4.1 `auth-context.tsx` — UserInfo 增加 role

```typescript
interface UserInfo {
  id: number;
  username: string;
  email: string;
  displayName: string;
  role: string; // 新增："USER" | "ADMIN"
}
```

#### 5.4.2 `middleware.ts` — JWT 解码校验（不信任 localStorage）

```typescript
// /admin/* 路由的额外校验
if (pathname.startsWith("/admin")) {
  if (!token) {
    return NextResponse.redirect(new URL("/landing", request.url));
  }
  try {
    const payload = JSON.parse(atob(token.split(".")[1]));
    if (payload.role !== "ADMIN") {
      return NextResponse.redirect(new URL("/", request.url));
    }
  } catch {
    return NextResponse.redirect(new URL("/login", request.url));
  }
}
```

**安全要点**：前端仅基于 JWT cookie 中的 role 做 UI 守卫，真正的鉴权由后端 `@PreAuthorize` + SecurityConfig 完成。即使用户篡改 localStorage 中的 role，也无法访问 `/admin/**` 端点。

### 5.5 JWT 向后兼容

| 场景 | 处理方式 |
|---|---|
| 旧 token 无 role claim | `JwtUtils.getRole()` 返回默认值 `"USER"` |
| 旧 refresh token 刷新 | `AuthController.buildLoginResponse()` 生成新 token 含 role |
| admin 用户 role 为 NULL | `AuthController.toResponse()` 默认 `"USER"`，schema.sql 的 `ON DUPLICATE KEY UPDATE` 确保 admin 有 ADMIN 角色 |

---

## 6. 后端设计

### 6.1 控制器设计（5 个拆分）

#### 6.1.1 `AdminDashboardController`

| 端点 | 方法 | 说明 |
|---|---|---|
| `/admin/dashboard/metrics` | GET | 平台概览指标 |
| `/admin/dashboard/monitor` | GET (SSE) | 实时监控数据流 |
| `/admin/dashboard/heatmap` | GET | 全平台 30 天学习热力图 |

#### 6.1.2 `AdminUserController`

| 端点 | 方法 | 说明 |
|---|---|---|
| `/admin/users` | GET | 用户列表（keyword/status/role 分页） |
| `/admin/users/{id}` | GET | 用户详情 + 聚合统计 |
| `/admin/users/{id}/status` | PUT | 启用/禁用用户 |
| `/admin/users/{id}/password` | PUT | 重置密码 |
| `/admin/users/{id}/role` | PUT | 修改角色（防自降权） |
| `/admin/users/{id}` | DELETE | 软删除 + 级联清理 |
| `/admin/users/{id}/impersonate` | POST | 模拟登录（生成 15 分钟短效 token） |
| `/admin/users/export` | GET | CSV 导出用户列表 |

#### 6.1.3 `AdminDocumentController`

| 端点 | 方法 | 说明 |
|---|---|---|
| `/admin/documents` | GET | 文档列表（keyword/status/subjectType/userId 分页） |
| `/admin/documents/{id}` | GET | 文档详情 |
| `/admin/documents/{id}` | DELETE | 删除文档（含 Milvus 清理） |
| `/admin/documents/{id}/status` | PUT | 强制修改状态（乐观锁） |
| `/admin/documents/{id}/retry` | POST | 重试失败文档 |
| `/admin/documents/health` | GET | 文档健康诊断（失败/卡死/正常统计） |
| `/admin/documents/export` | GET | CSV 导出文档列表 |

#### 6.1.4 `AdminAiMonitorController`

| 端点 | 方法 | 说明 |
|---|---|---|
| `/admin/ai-monitor/health` | GET | AI 服务健康（断路器状态 + 连续失败数） |
| `/admin/ai-monitor/usage/summary` | GET | AI 用量按类型汇总 |
| `/admin/ai-monitor/usage/trend` | GET | AI 用量按天趋势（startDate/endDate） |
| `/admin/ai-monitor/quality` | GET | AI 对话质量（好评率/反馈/按 activity_type 分组） |

#### 6.1.5 `AdminSystemController`

| 端点 | 方法 | 说明 |
|---|---|---|
| `/admin/system/logs` | GET | 系统日志分页查询 |
| `/admin/system/logs/stats` | GET | 日志统计（总数/错误数/top 操作） |
| `/admin/system/config` | GET | 配置列表 |
| `/admin/system/config/{key}` | PUT | 更新配置值 |

### 6.2 Service 层设计

#### 6.2.1 `AdminUserService`

```java
@Service
public class AdminUserService {

    // 分页用户列表（支持 keyword/status/role 筛选）
    public PageResponse<AdminUserResponse> listUsers(
        String keyword, Integer status, String role, int pageNum, int pageSize);

    // 用户详情 + 聚合统计
    public AdminUserResponse getUserDetail(Long userId);
    // 内部执行 5 个 COUNT 查询：
    // - documents COUNT by userId
    // - flashcards COUNT by userId
    // - quizzes COUNT by userId
    // - chat_history COUNT by userId
    // - chat_history MAX(created_at) as lastActiveAt

    // 启用/禁用用户
    public void toggleStatus(Long userId);

    // 重置密码
    public void resetPassword(Long userId, String newPassword);

    // 修改角色（防止管理员自降权）
    public void updateRole(Long userId, String newRole);

    // 删除用户（软删除 + DocumentService 级联清理）
    @Transactional
    public AdminDeleteResult deleteUser(Long userId);

    // 模拟登录（生成 15 分钟短效 token）
    public LoginResponse impersonate(Long userId);
}
```

**用户删除流程**（解决 FK CASCADE vs 软删除冲突）：

```
deleteUser(userId)
  ├── 1. 遍历用户文档
  │     └── DocumentService.delete(docId)
  │           ├── Milvus 向量清理
  │           ├── 物理文件删除
  │           ├── document_chunks 删除
  │           └── document 软删除
  ├── 2. 记录删除日志到 system_logs
  └── 3. userMapper.deleteById(userId)  // @TableLogic 软删除
```

#### 6.2.2 `AdminDocumentService`

```java
@Service
public class AdminDocumentService {

    // 跨用户文档列表
    public PageResponse<AdminDocumentResponse> listAllDocuments(
        String keyword, String status, String subjectType, Long userId,
        int pageNum, int pageSize);

    // 文档健康诊断
    public DocHealthReport getDocHealth();
    // - failedDocs: status='FAILED' 的文档列表（含 statusMessage）
    // - stuckDocs: status='PROCESSING' 且 createdAt 超过 10 分钟
    // - healthyCount / totalCount

    // 删除文档（管理员可删除任意文档）
    public void deleteDocument(Long docId);

    // 强制修改状态（乐观锁）
    public void updateStatus(Long docId, String newStatus, int expectedVersion);

    // 重试失败文档
    public void retryDocument(Long docId);
}
```

#### 6.2.3 `AdminStatsService`

```java
@Service
public class AdminStatsService {

    // 平台概览指标（Redis 缓存 60s TTL）
    @Cacheable(cacheNames = "admin-metrics", key = "'dashboard'")
    public AdminDashboardMetrics getDashboardMetrics();

    // AI 用量汇总（Redis 缓存 60s TTL）
    @Cacheable(cacheNames = "admin-metrics", key = "'ai-summary'")
    public AiUsageSummary getAiUsageSummary();

    // AI 用量按天趋势
    public List<AiUsageDaily> getAiUsageTrend(LocalDate start, LocalDate end);

    // 全平台学习热力图（Redis 缓存 300s TTL）
    @Cacheable(cacheNames = "admin-metrics", key = "'heatmap'")
    public List<HeatmapDay> getLearningHeatmap();

    // AI 对话质量
    public ChatQualityStats getChatQuality();
}
```

**性能关键**：所有聚合查询使用 SQL `GROUP BY` + `COUNT` + `SUM`，**禁止**加载原始记录到 Java 内存。

#### 6.2.4 `AdminMonitorService`

```java
@Service
public class AdminMonitorService {

    // SSE 广播模式（单线程服务所有连接）
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::broadcast, 5, 5, TimeUnit.SECONDS);
    }

    // 注册新的 SSE 连接
    public SseEmitter subscribe();

    // 采集指标并广播
    private void broadcast() {
        MonitorData data = collectMetrics();
        List<Alert> alerts = evaluateAlerts(data);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(Map.of("metrics", data, "alerts", alerts)));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    // 指标采集
    private MonitorData collectMetrics() {
        return new MonitorData(
            countOnlineUsers(),           // 最近 5 分钟有请求的 userId
            countActiveAiTasks(),         // 正在进行的 AI 生成
            circuitBreaker.getState(),    // CLOSED/OPEN/HALF_OPEN
            circuitBreaker.getConsecutiveFailures(),
            countRecentErrors()           // 最近 5 分钟错误数
        );
    }

    // 告警规则
    private List<Alert> evaluateAlerts(MonitorData data) {
        List<Alert> alerts = new ArrayList<>();
        if (data.getCircuitBreakerState() == CircuitBreaker.State.OPEN)
            alerts.add(new Alert(AlertLevel.CRITICAL, "AI 断路器已打开，AI 功能暂时不可用"));
        if (data.getRecentErrorCount() > 10)
            alerts.add(new Alert(AlertLevel.WARNING, "最近 5 分钟错误数异常：" + data.getRecentErrorCount()));
        return alerts;
    }
}
```

#### 6.2.5 `AdminConfigService`

```java
@Service
public class AdminConfigService {

    public List<SystemConfig> getAllConfigs();
    public void updateConfig(String key, String value, Long updatedBy);
    public String getConfig(String key);
}
```

### 6.3 新增 Mapper 方法

#### `ChatHistoryMapper` 新增

```java
// 按天统计对话数
List<Map<String, Object>> countByDayRange(
    @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

// 按天统计 token 消耗
List<Map<String, Object>> sumTokensByDay(
    @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

// 按 activity_type 统计好评率
List<Map<String, Object>> countHelpfulByType(
    @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
```

#### `DocumentMapper` 新增

```java
// 按状态分组统计
List<Map<String, Object>> countByStatusGroup();

// 列出失败文档
List<Document> listFailed();

// 列出卡死文档（PROCESSING 超过 threshold 分钟）
List<Document> listStuckProcessing(@Param("thresholdMinutes") int thresholdMinutes);
```

#### `UserMapper` 新增

```java
// 统计活跃用户数（最近 N 分钟有请求）
int countActiveSince(@Param("since") LocalDateTime since);

// 按天统计注册数
List<Map<String, Object>> countRegistrationsByDay(
    @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
```

### 6.4 审计日志拦截器

```java
@Component
public class AdminAuditInterceptor implements HandlerInterceptor {

    private final SystemLogService systemLogService;
    private final ExecutorService asyncExecutor;

    @Override
    public boolean preHandle(HttpServletRequest request, ...) {
        request.setAttribute("startTime", System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, ...) {
        // 仅记录写操作
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) {
            return;
        }

        long duration = System.currentTimeMillis() - (long) request.getAttribute("startTime");

        // 异步写入，不阻塞请求
        asyncExecutor.submit(() -> {
            systemLogService.log(
                SecurityUtils.getCurrentUserId(),
                method + " " + request.getRequestURI(),
                extractResourceType(request.getRequestURI()),
                extractResourceId(request.getRequestURI()),
                truncate(request.getQueryString(), 2000),
                null, // response body 不捕获
                response.getStatus(),
                null,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),
                duration
            );
        });
    }
}
```

注册到 `/admin/**` 路径：

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuditInterceptor)
                .addPathPatterns("/admin/**");
    }
}
```

### 6.5 DTO 设计

#### `PageResponse<T>` — 通用分页响应

```java
@Data
@Builder
public class PageResponse<T> {
    private List<T> list;
    private long total;
    private int pageNum;
    private int pageSize;
}
```

#### `AdminDashboardMetrics` — 平台概览

```java
@Data
@Builder
public class AdminDashboardMetrics {
    private long totalUsers;
    private long activeUsersToday;
    private long activeUsersWeek;
    private long totalDocuments;
    private long totalStorageBytes;
    private long totalAiGenerations;
    private long totalTokensUsed;
    private List<DailyCount> recentRegistrations; // 近 7 天注册数
    private double errorRate;                      // FAILED 文档占比
}
```

#### `AdminUserResponse` — 用户详情

```java
@Data
@Builder
public class AdminUserResponse {
    private Long id;
    private String username;
    private String email;
    private String displayName;
    private String role;
    private Integer status;
    private LocalDateTime createdAt;
    private UserStats stats; // 聚合统计

    @Data @Builder
    public static class UserStats {
        private int documentCount;
        private int flashcardCount;
        private int quizCount;
        private int chatCount;
        private long totalTokensUsed;
        private LocalDateTime lastActiveAt;
    }
}
```

#### `DocHealthReport` — 文档健康诊断

```java
@Data
@Builder
public class DocHealthReport {
    private long healthyCount;
    private long failedCount;
    private long stuckCount;
    private List<Document> failedDocs;   // 含 statusMessage
    private List<Document> stuckDocs;    // PROCESSING 超时
}
```

#### `ChatQualityStats` — 对话质量

```java
@Data
@Builder
public class ChatQualityStats {
    private long totalFeedbacks;
    private long helpfulCount;
    private long unhelpfulCount;
    private double helpfulRate;
    private List<FeedbackByType> byType; // 按 activity_type 分组

    @Data @Builder
    public static class FeedbackByType {
        private String activityType;
        private long total;
        private long helpful;
        private double helpfulRate;
        private long avgTokens;
    }
}
```

### 6.6 KnowledgeGraphService 扩展

```java
// 新增方法：管理员视图（跨用户，限制节点数）
public Map<String, Object> getGraphForAdmin(Long userId, int limit) {
    LambdaQueryWrapper<KnowledgeConcept> cw = new LambdaQueryWrapper<>();
    if (userId != null) {
        cw.eq(KnowledgeConcept::getUserId, userId);
    }
    cw.orderByDesc(KnowledgeConcept::getImportanceScore)
       .last("LIMIT " + limit);  // 防止无界查询

    List<KnowledgeConcept> concepts = conceptMapper.selectList(cw);
    // ... 后续关系查询逻辑与 getGraph() 相同
}
```

---

## 7. 前端设计

### 7.1 路由结构

```
frontend/src/app/admin/
├── layout.tsx              — 独立 Admin 布局
├── page.tsx                — 重定向 → /admin/dashboard
├── dashboard/
│   └── page.tsx            — 概览 + 实时脉搏 + 告警 + 热力图
├── users/
│   ├── page.tsx            — 用户管理列表
│   └── [id]/
│       └── page.tsx        — 用户详情
├── documents/
│   ├── page.tsx            — 文档管理 + 健康诊断
│   └── [id]/
│       └── page.tsx        — 文档详情
├── ai-monitor/
│   └── page.tsx            — AI 健康 + 用量 + 质量
├── logs/
│   └── page.tsx            — 系统日志
└── config/
    └── page.tsx            — 系统配置
```

### 7.2 Admin 布局

`admin/layout.tsx` 提供**完全独立的 Shell**，不继承根 `layout.tsx` 中 `AppShell` 的用户侧边栏、聊天抽屉和学习路径导航。

```
┌─────────────────────────────────────────────────────┐
│  Top Bar: 管理员信息 | 返回主应用 | 告警铃铛 | 退出  │
├───────────┬─────────────────────────────────────────┤
│           │                                         │
│  Admin    │         Content Area                    │
│  Sidebar  │         {children}                      │
│           │                                         │
│  • 概览   │                                         │
│  • 用户   │                                         │
│  • 文档   │                                         │
│  • AI 监控│                                         │
│  • 系统   │                                         │
│           │                                         │
├───────────┴─────────────────────────────────────────┤
│  Cmd+K 命令面板入口                                  │
└─────────────────────────────────────────────────────┘
```

**角色守卫**：`useAuth()` 检查 `user.role === "ADMIN"`，非管理员重定向 `/`。

### 7.3 Admin 侧边栏

| 分区 | 图标 | 导航项 | 路由 |
|---|---|---|---|
| 总览 | BarChart3 | Dashboard | `/admin/dashboard` |
| 用户 | Users | 用户管理 | `/admin/users` |
| 内容 | FileText | 文档管理 | `/admin/documents` |
| AI 监控 | Zap | AI 健康 | `/admin/ai-monitor` |
| 系统 | Settings | 日志 / 配置 | `/admin/logs` `/admin/config` |

### 7.4 核心页面设计

#### 7.4.1 Dashboard 页面

```
┌─────────────────────────────────────────────────────┐
│  [实时脉搏条] 在线:23 | AI任务:5 | 断路器:正常 | 错误:2 │
├─────────┬─────────┬─────────┬─────────┬─────────────┤
│ 总用户  │ 今日活跃│ 总文档  │ AI 生成 │ Token 总量  │
│  150    │   23    │  340    │ 12,500  │  5,000,000  │
├─────────┴─────────┴─────────┴─────────┴─────────────┤
│  [告警中心] 点击展开告警列表                           │
├──────────────────────┬──────────────────────────────┤
│  [30天学习热力图]     │  [注册趋势柱状图]              │
│  GitHub 风格方块      │  近 7 天每日注册数             │
│  点击展开当日详情      │                               │
└──────────────────────┴──────────────────────────────┘
```

#### 7.4.2 AI 监控页面

```
┌──────────┬──────────┬──────────┐
│ 断路器状态│ Token趋势 │ AI好评率  │
│ 🟢 正常  │ 📈 折线图 │ 🎯 87.3% │
├──────────┴──────────┴──────────┤
│  [各模块 AI 用量柱状图]         │
│  闪卡 | 测验 | 笔记 | 导图 | 对话│
├────────────────────────────────┤
│  [对话质量详情表]               │
│  按 activity_type 分组          │
│  好评率 | 平均 token | 反馈数   │
└────────────────────────────────┘
```

#### 7.4.3 文档管理页面

```
┌─────────────────────────────────────────────────────┐
│  [健康度概览] 环形图: COMPLETED 92% | PROCESSING 5%  │
│                                    | FAILED 3%      │
├─────────────────────────────────────────────────────┤
│  [失败文档列表] 含 statusMessage 诊断 + 一键重试       │
│  [卡死文档列表] PROCESSING 超 10 分钟                 │
├─────────────────────────────────────────────────────┤
│  [文档表格] 搜索 | 状态筛选 | 学科筛选 | 分页          │
│  标题 | 文件名 | 大小 | 类型 | 状态 | 学科 | 所有者    │
└─────────────────────────────────────────────────────┘
```

### 7.5 组件设计

| 组件 | 文件 | 说明 |
|---|---|---|
| `AdminSidebar` | `components/admin/AdminSidebar.tsx` | 5 分区导航，路径高亮，移动端可折叠 |
| `RealtimePulse` | `components/admin/RealtimePulse.tsx` | SSE 连接 → 顶部脉搏条，自动重连 |
| `AlertBell` | `components/admin/AlertBell.tsx` | 通知铃铛 + 告警列表下拉 |
| `CommandPalette` | `components/admin/CommandPalette.tsx` | Cmd+K 命令面板，搜索用户/文档 |
| `DataTable` | `components/admin/DataTable.tsx` | 通用数据表格（搜索/筛选/分页/排序） |

### 7.6 Admin API 模块

`lib/admin-api.ts` — 复用 `api.ts` 的 `request<T>()` 封装：

```typescript
// Dashboard
export async function getAdminDashboardMetrics(): Promise<AdminDashboardMetrics>
export async function getAdminHeatmap(): Promise<HeatmapDay[]>

// Users
export async function listAdminUsers(params: UserListParams): Promise<PageResponse<AdminUserResponse>>
export async function getAdminUserDetail(userId: number): Promise<AdminUserDetailResponse>
export async function toggleUserStatus(userId: number, status: number): Promise<void>
export async function resetUserPassword(userId: number, password: string): Promise<void>
export async function updateUserRole(userId: number, role: string): Promise<void>
export async function deleteUser(userId: number): Promise<AdminDeleteResult>
export async function impersonateUser(userId: number): Promise<LoginResponse>
export async function exportUsers(): Promise<Blob>

// Documents
export async function listAdminDocuments(params: DocListParams): Promise<PageResponse<AdminDocumentResponse>>
export async function getDocHealth(): Promise<DocHealthReport>
export async function deleteAdminDocument(docId: number): Promise<void>
export async function updateDocumentStatus(docId: number, status: string, version: number): Promise<void>
export async function retryDocument(docId: number): Promise<void>
export async function exportDocuments(): Promise<Blob>

// AI Monitor
export async function getAiHealth(): Promise<AiHealthStatus>
export async function getAiUsageSummary(): Promise<AiUsageSummary>
export async function getAiUsageTrend(start: string, end: string): Promise<AiUsageDaily[]>
export async function getChatQuality(): Promise<ChatQualityStats>

// System
export async function listSystemLogs(params: LogListParams): Promise<PageResponse<SystemLogResponse>>
export async function getLogStats(): Promise<LogStats>
export async function listConfigs(): Promise<ConfigResponse[]>
export async function updateConfig(key: string, value: string): Promise<void>
```

### 7.7 新增前端依赖

```bash
npm install recharts  # 图表库（折线/柱状/环形/仪表盘）
```

---

## 8. 创新功能详设

### 8.1 实时管理看板（SSE 广播模式）

**架构**：单个 `ScheduledExecutorService` 每 5 秒采集指标并通过 `CopyOnWriteArrayList<SseEmitter>` 广播给所有已连接的管理员。

```
Admin Browser 1 ──┐
Admin Browser 2 ──┤    ┌──────────────────┐    ┌───────────────┐
Admin Browser 3 ──┼───▶│ AdminMonitorSvc  │───▶│ ScheduledExec │
Admin Browser N ──┘    │ (emitter list)   │    │ (每 5 秒广播) │
                       └──────────────────┘    └───────────────┘
```

**优势**：1 个线程服务 N 个连接，不占用 `asyncExecutor` 线程池。

**推送数据**：

| 字段 | 来源 | 说明 |
|---|---|---|
| `onlineUsers` | 查询最近 5 分钟有 HTTP 请求的 distinct userId | 需配合请求日志或 Redis Set |
| `activeAiTasks` | 正在进行的 AI 生成 SSE 连接数 | 从各 Controller 的 SseEmitter 计数 |
| `circuitBreakerState` | `AiGeneratorBase.AI_CIRCUIT_BREAKER.getState()` | CLOSED/OPEN/HALF_OPEN |
| `consecutiveFailures` | `circuitBreaker.getConsecutiveFailures()` | 当前连续失败次数 |
| `recentErrors` | 最近 5 分钟 system_logs 中 status >= 500 的计数 | 需异步统计 |

### 8.2 全平台知识图谱探索器

**数据限制**：全平台最多 500 个概念节点（按 `importance_score DESC` 排序截断）。

**节点视觉映射**：

| 属性 | 映射 | 计算方式 |
|---|---|---|
| 节点大小 | 学习该概念的用户数 | `COUNT(DISTINCT user_id) FROM knowledge_concepts WHERE name = ?` |
| 节点颜色 | 平均重要度 | `AVG(importance_score)` → 色阶（蓝→红） |
| 边粗细 | 关系强度 | `strength` 字段（0.0-1.0）→ 1-5px |

**交互功能**：
- 搜索框 → 高亮匹配节点
- 关系类型筛选（IS_A / PART_OF / PREREQUISITE / RELATES_TO 等）
- 点击节点 → 侧边面板：概念名称、定义、涉及文档数、学习用户数、平均重要度

**复用**：`react-force-graph-2d` 已安装，`KnowledgeGraphService` 数据模型已就绪。

### 8.3 AI 健康监控面板

**断路器状态**：

| 状态 | 颜色 | 含义 |
|---|---|---|
| CLOSED | 绿色 | 正常运行 |
| HALF_OPEN | 黄色 | 冷却期，正在试探 |
| OPEN | 红色 | 熔断中，AI 功能不可用 |

**Token 消耗趋势**：基于 `chat_history.tokens_used` 按天 `SUM` 聚合，按 `activity_type` 分色。

**AI 好评率**：基于 `chat_history.is_helpful`，计算 `COUNT(is_helpful=1) / COUNT(is_helpful IS NOT NULL)`。

### 8.4 文档健康诊断

**诊断逻辑**：

| 状态 | 判定条件 | 处理 |
|---|---|---|
| 失败 | `status = 'FAILED'` | 显示 `statusMessage` + 重试按钮 |
| 卡死 | `status = 'PROCESSING'` 且 `created_at` 超过 10 分钟 | 显示耗时 + 重试按钮 |
| 正常 | `status = 'COMPLETED'` | 统计数量 |

**一键重试**：调用 `DocumentService.retryAndSendMessage()` 重新发送到 RabbitMQ 队列。

### 8.5 全平台学习热力图

**数据源**：`flashcard_review_logs` + `quiz_attempts` 按天 `GROUP BY` 聚合。

```sql
SELECT day, SUM(cnt) AS total FROM (
    SELECT DATE(created_at) AS day, COUNT(*) AS cnt
    FROM flashcard_review_logs
    WHERE created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
    GROUP BY DATE(created_at)
    UNION ALL
    SELECT DATE(created_at) AS day, COUNT(*) AS cnt
    FROM quiz_attempts
    WHERE created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
    GROUP BY DATE(created_at)
) t GROUP BY day ORDER BY day
```

**交互**：点击某天 → 展开显示当日活跃用户数 + 最热门文档 Top 5。

### 8.6 智能告警

| 告警规则 | 级别 | 触发条件 |
|---|---|---|
| AI 断路器打开 | CRITICAL | `circuitBreaker.getState() == OPEN` |
| 文档失败率飙升 | WARNING | 最近 1 小时 `FAILED / total > 20%` |
| AI 好评率下降 | WARNING | 最近 1 小时 `is_helpful=1 / total < 50%` |

通过 SSE 推送到管理看板，前端通知铃铛展示。

### 8.7 命令面板（Cmd+K）

基于 shadcn/ui `Dialog` + `Command` 组件：

- 输入框搜索用户（用户名/邮箱）→ 跳转 `/admin/users/{id}`
- 输入框搜索文档（标题）→ 跳转 `/admin/documents/{id}`
- 快捷操作：查看日志、查看配置、查看 AI 状态
- 最近访问记录（localStorage 持久化）

### 8.8 用户模拟登录

```
POST /admin/users/{id}/impersonate
→ 生成 15 分钟短效 JWT，payload 含 { userId: targetId, impersonatedBy: adminId, role: "USER" }
→ 返回 LoginResponse

前端检测 impersonatedBy 字段时：
→ 显示黄色横幅"正在以 {username} 身份浏览 [返回管理员]"
→ 所有 API 请求使用此 token

POST /admin/impersonate/stop
→ 恢复管理员 token
→ 清除横幅
```

### 8.9 数据导出

| 端点 | 格式 | 字段 |
|---|---|---|
| `GET /admin/users/export` | CSV (UTF-8 BOM) | id, username, email, displayName, role, status, createdAt |
| `GET /admin/documents/export` | CSV (UTF-8 BOM) | id, title, fileName, fileSize, fileType, status, subjectType, userId, createdAt |

**Excel 兼容**：添加 UTF-8 BOM (`﻿`) 前缀，确保中文不乱码。

---

## 9. 性能与可扩展性

### 9.1 Redis 缓存策略

| 缓存键 | TTL | 数据 |
|---|---|---|
| `admin-metrics::dashboard` | 60s | 平台概览指标 |
| `admin-metrics::ai-summary` | 60s | AI 用量汇总 |
| `admin-metrics::heatmap` | 300s | 30 天学习热力图 |
| `admin-kg::{userId}` | 300s | 知识图谱数据 |

缓存失效：管理员执行写操作时主动清除相关缓存（`@CacheEvict`）。

### 9.2 SQL 聚合原则

**禁止**：加载原始记录到 Java 内存解析（如 `LearnerDashboardService.parseAnswerDetails` 模式）。

**使用**：`GROUP BY` + `SUM` + `COUNT` + `JSON_TABLE()`（MySQL 8.0+）在数据库层完成聚合。

### 9.3 SSE 广播模式

| 指标 | 传统模式 | 广播模式 |
|---|---|---|
| 线程消耗 | N 个连接 = N 个线程 | 1 个线程服务所有连接 |
| 最大并发 | 受线程池大小限制（16） | 仅受内存限制 |
| 实现方式 | 每个连接一个 SseEmitter + asyncExecutor | 单个 ScheduledExecutor + CopyOnWriteArrayList |

### 9.4 知识图谱限流

全平台查询限制 top 500 概念（按 `importance_score DESC`）。支持按用户筛选以查看单用户图谱。

### 9.5 分页默认值

| 参数 | 默认值 | 最大值 |
|---|---|---|
| `pageNum` | 1 | — |
| `pageSize` | 20 | 100 |

---

## 10. 数据完整性保障

### 10.1 用户删除：软删除 + DocumentService 级联

**问题**：`users` 表使用 `@TableLogic` 软删除，但 `documents` 表有 `ON DELETE CASCADE`。直接硬删除用户会导致 Milvus 向量和物理文件无法清理。

**解决方案**：Admin 删除用户时通过 Service 层级联清理：

```java
@Transactional
public AdminDeleteResult deleteUser(Long userId) {
    int docsDeleted = 0, docsFailed = 0;

    // 1. 遍历用户文档，通过 DocumentService.delete() 清理
    List<Document> docs = documentMapper.selectList(
        new LambdaQueryWrapper<Document>().eq(Document::getUserId, userId));
    for (Document doc : docs) {
        try {
            documentService.delete(doc.getId());
            docsDeleted++;
        } catch (Exception e) {
            docsFailed++;
            log.error("删除文档失败: docId={}, error={}", doc.getId(), e.getMessage());
        }
    }

    // 2. 软删除用户（@TableLogic 自动处理）
    userMapper.deleteById(userId);

    return new AdminDeleteResult(docsDeleted, docsFailed);
}
```

### 10.2 文档状态更新：乐观锁

**问题**：管理员修改 PROCESSING 文档状态为 FAILED 时，RabbitMQ 消费者可能同时将其更新为 COMPLETED，导致管理员的修改被覆盖。

**解决方案**：`documents` 表新增 `version` 列，使用乐观锁：

```java
@Transactional
public void updateStatus(Long docId, String newStatus, int expectedVersion) {
    int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
        .eq(Document::getId, docId)
        .eq(Document::getVersion, expectedVersion)
        .set(Document::getStatus, newStatus)
        .set(Document::getVersion, expectedVersion + 1));

    if (updated == 0) {
        throw new OptimisticLockException("文档状态已被其他操作修改，请刷新后重试");
    }
}
```

---

## 11. 错误处理策略

### 11.1 Milvus 清理失败

`DocumentService.delete()` 中 Milvus 清理失败时，记录到 `system_logs`（action = `MILVUS_CLEANUP_FAILED`），管理员可在日志页面看到。后续可加定时重试任务。

### 11.2 用户删除详细报告

```java
public class AdminDeleteResult {
    private int documentsDeleted;
    private int documentsFailed;
    private String message; // "已删除 5 个文档，2 个文档删除失败"
}
```

### 11.3 操作日志

所有 Admin 写操作自动记录到 `system_logs`，包含：操作人、操作类型、请求数据、IP 地址、耗时。

---

## 12. 迁移方案

### 12.1 数据库迁移

| 步骤 | SQL | 幂等性 |
|---|---|---|
| 1. 新增 `role` 列 | `ALTER TABLE users ADD COLUMN role ...` | 通过 `information_schema` 检查实现条件执行 |
| 2. 新增 `version` 列 | `ALTER TABLE documents ADD COLUMN version ...` | 同上 |
| 3. 更新 admin 种子 | `INSERT ... ON DUPLICATE KEY UPDATE role = 'ADMIN'` | 幂等 |
| 4. 创建 `system_config` | `CREATE TABLE IF NOT EXISTS` | 幂等 |

### 12.2 JWT 向后兼容

| 场景 | 处理 |
|---|---|
| 旧 token 无 role claim | `JwtUtils.getRole()` 返回默认 `"USER"` |
| 旧 refresh token | 刷新后获得带 role 的新 token |
| admin 用户 role 为 NULL | `AuthController` 默认 `"USER"`，但 schema.sql 的 `ON DUPLICATE KEY UPDATE` 确保 admin 有 ADMIN 角色 |

### 12.3 部署顺序

1. 执行 schema.sql 增量变更
2. 部署后端（新代码兼容旧 token）
3. 部署前端（新页面 + middleware 更新）
4. 用 admin 账号登录验证

---

## 13. 测试策略

### 13.1 后端测试

| 测试类型 | 测试类 | 测试内容 |
|---|---|---|
| 单元测试 | `JwtAuthFilterTest` | role 提取、authority 设置、旧 token 兼容 |
| 单元测试 | `JwtUtilsTest` | role claim 嵌入/提取、null role 默认值 |
| WebMvc 测试 | `AdminSecurityTest` | `/admin/**` 对非管理员返回 403 |
| WebMvc 测试 | `AdminUserControllerTest` | 各端点 CRUD 正确性 |
| 集成测试 | `AdminIntegrationTest` | register → set admin → login → access admin endpoint |

### 13.2 前端测试

| 测试 | 内容 |
|---|---|
| Middleware 角色检查 | 无 token → 重定向 /landing；非 admin token → 重定向 / |
| Admin 页面渲染 | admin 用户 → 正常渲染；普通用户 → 重定向 |

### 13.3 关键测试用例

```java
// JwtAuthFilterTest
@Test
void shouldExtractRoleFromToken() {
    String token = jwtUtils.generateToken(1L, "admin", "ADMIN");
    // ... 验证 authorities 包含 "ROLE_ADMIN"
}

@Test
void shouldDefaultToUserWhenRoleIsNull() {
    // 模拟旧 token（无 role claim）
    String oldToken = generateTokenWithoutRole(1L, "user");
    // ... 验证 authorities 包含 "ROLE_USER"
}

// AdminSecurityTest
@Test
void shouldReturn403ForNonAdminUser() {
    mockMvc.perform(get("/admin/users").header("Authorization", "Bearer " + userToken))
           .andExpect(status().isForbidden());
}
```

---

## 14. 实施计划

| 阶段 | 内容 | 预估工时 | 依赖 |
|---|---|---|---|
| **Phase 1** | 角色系统基础 | 1 天 | 无 |
| | schema 变更（role 列 + version 列 + system_config 表） | | |
| | User/AuthUser/JwtUtils/JwtAuthFilter/SecurityConfig 改造 | | |
| | AuthController/UserResponse/UserService 改造 | | |
| | 前端 UserInfo + middleware 角色检查 | | |
| **Phase 2** | 审计日志 + 新 Mapper 方法 | 0.5 天 | Phase 1 |
| | SystemLog 实体/Mapper/Service | | |
| | AdminAuditInterceptor + WebMvcConfig 注册 | | |
| | ChatHistoryMapper/DocumentMapper/UserMapper 新增方法 | | |
| **Phase 3** | Admin 布局 + Dashboard + 用户管理 | 2 天 | Phase 1, 2 |
| | AdminDashboardController + AdminUserController | | |
| | AdminStatsService + AdminUserService | | |
| | AdminMonitorService（SSE 广播 + 告警） | | |
| | 前端 admin/layout + AdminSidebar + Dashboard 页面 | | |
| | 前端 Users 页面 + 用户详情 | | |
| | 前端 RealtimePulse + AlertBell 组件 | | |
| | 全平台学习热力图 | | |
| **Phase 4** | 文档管理 + AI 监控 + 知识图谱 | 2 天 | Phase 1 |
| | AdminDocumentController + AdminAiMonitorController | | |
| | AdminDocumentService | | |
| | KnowledgeGraphService.getGraphForAdmin() | | |
| | 前端 Documents 页面 + 健康诊断 | | |
| | 前端 AI Monitor 页面 | | |
| | 用户模拟登录 + 数据导出 | | |
| **Phase 5** | 系统日志 UI + 配置 + 命令面板 | 1 天 | Phase 2 |
| | AdminSystemController | | |
| | AdminConfigService | | |
| | 前端 Logs 页面 + Config 页面 | | |
| | CommandPalette 组件 | | |
| **Phase 6** | 测试 + 优化 | 1 天 | Phase 1-5 |
| | 安全测试 + 控制器测试 | | |
| | 性能优化（Redis 缓存验证） | | |
| | 端到端验证 | | |

**总预估工时：7.5 天**

---

## 15. 文件清单

### 新增文件（32 个）

#### 后端（18 个）

| 文件路径 | 说明 |
|---|---|
| `common/enums/UserRole.java` | 角色枚举 |
| `entity/SystemLog.java` | 系统日志实体 |
| `entity/SystemConfig.java` | 系统配置实体 |
| `mapper/SystemLogMapper.java` | 日志 Mapper |
| `mapper/SystemConfigMapper.java` | 配置 Mapper |
| `service/SystemLogService.java` | 日志服务 |
| `service/admin/AdminUserService.java` | 用户管理服务 |
| `service/admin/AdminDocumentService.java` | 文档管理服务 |
| `service/admin/AdminStatsService.java` | 统计聚合服务 |
| `service/admin/AdminMonitorService.java` | SSE 广播 + 告警服务 |
| `service/admin/AdminConfigService.java` | 配置管理服务 |
| `controller/admin/AdminDashboardController.java` | Dashboard 控制器 |
| `controller/admin/AdminUserController.java` | 用户管理控制器 |
| `controller/admin/AdminDocumentController.java` | 文档管理控制器 |
| `controller/admin/AdminAiMonitorController.java` | AI 监控控制器 |
| `controller/admin/AdminSystemController.java` | 系统管理控制器 |
| `config/AdminAuditInterceptor.java` | 审计日志拦截器 |
| `dto/admin/*.java` (6 个 DTO) | PageResponse, DashboardMetrics, UserResponse, DocumentResponse, AiUsageSummary, ChatQualityStats |

#### 前端（14 个）

| 文件路径 | 说明 |
|---|---|
| `app/admin/layout.tsx` | Admin 独立布局 |
| `app/admin/page.tsx` | 重定向 |
| `app/admin/dashboard/page.tsx` | Dashboard 页面 |
| `app/admin/users/page.tsx` | 用户管理页面 |
| `app/admin/users/[id]/page.tsx` | 用户详情页面 |
| `app/admin/documents/page.tsx` | 文档管理页面 |
| `app/admin/documents/[id]/page.tsx` | 文档详情页面 |
| `app/admin/ai-monitor/page.tsx` | AI 监控页面 |
| `app/admin/logs/page.tsx` | 系统日志页面 |
| `app/admin/config/page.tsx` | 系统配置页面 |
| `components/admin/AdminSidebar.tsx` | Admin 侧边栏 |
| `components/admin/CommandPalette.tsx` | 命令面板 |
| `components/admin/RealtimePulse.tsx` | 实时脉搏组件 |
| `components/admin/AlertBell.tsx` | 告警铃铛组件 |
| `lib/admin-api.ts` | Admin API 模块 |

### 修改文件（13 个）

#### 后端（11 个）

| 文件路径 | 改动 |
|---|---|
| `resources/db/schema.sql` | role 列 + version 列 + system_config 表 + admin 种子更新 |
| `entity/User.java` | 新增 `role` 字段 |
| `entity/Document.java` | 新增 `version` 字段 + `@Version` |
| `security/AuthUser.java` | 新增 `role` 字段 |
| `security/JwtUtils.java` | role 参数 + getRole() + null 安全 |
| `security/JwtAuthFilter.java` | 提取 role + 设置 authorities |
| `security/SecurityUtils.java` | 新增 getCurrentUserRole() / isAdmin() |
| `config/SecurityConfig.java` | /admin/** authority + @EnableMethodSecurity |
| `controller/AuthController.java` | buildLoginResponse 传 role |
| `dto/UserResponse.java` | 新增 role 字段 |
| `service/UserService.java` | register() 加 .role("USER") |
| `service/KnowledgeGraphService.java` | 新增 getGraphForAdmin() |
| `service/DocumentService.java` | updateStatus() 乐观锁版本 |

#### 前端（2 个）

| 文件路径 | 改动 |
|---|---|
| `lib/auth-context.tsx` | UserInfo 新增 role 字段 |
| `middleware.ts` | /admin/* JWT 角色检查 |

---

## 16. 验证方案

| 验证项 | 步骤 | 预期结果 |
|---|---|---|
| 角色系统 | admin 登录 → 检查 JWT payload | JWT 含 `role: "ADMIN"` |
| | 普通用户访问 `/admin/*` | 返回 403 |
| | 旧 token（无 role）访问用户端点 | 正常访问，默认 USER |
| @PreAuthorize | 伪造 ADMIN 权限的请求访问 admin 方法 | 返回 403 |
| 实时看板 | 打开 Dashboard 页面 | SSE 连接建立，每 5 秒数据更新 |
| | 多个管理员窗口同时打开 | 所有窗口同步更新 |
| | 断开网络后恢复 | 自动重连 |
| 知识图谱 | 打开知识图谱页面 | 展示 ≤500 节点的力导向图 |
| | 搜索概念 | 高亮匹配节点 |
| | 点击节点 | 侧边面板显示详情 |
| AI 健康 | 查看断路器状态 | 实时反映 CLOSED/OPEN/HALF_OPEN |
| | 查看 Token 趋势 | 折线图数据与 chat_history 一致 |
| | 查看好评率 | 按 activity_type 分组显示 |
| 文档健康 | 查看健康度概览 | 环形图显示 COMPLETED/PROCESSING/FAILED 比例 |
| | 点击重试失败文档 | 文档重新进入处理队列 |
| | 并发修改文档状态 | 乐观锁冲突时提示"请刷新后重试" |
| 热力图 | 查看 30 天热力图 | 数据与实际学习记录一致 |
| | 点击某天 | 展开当日活跃用户数 + 热门文档 |
| 告警 | 模拟断路器打开 | 告警出现在铃铛 + 脉搏条变红 |
| 命令面板 | 按 Cmd+K | 命令面板弹出 |
| | 搜索用户名 | 显示匹配结果，点击跳转 |
| 用户模拟 | 点击"模拟登录" | 生成短效 token，显示黄色横幅 |
| | 15 分钟后 | token 过期，自动恢复管理员会话 |
| 数据导出 | 点击"导出用户" | 下载 CSV 文件，中文不乱码 |
| 迁移 | 已有数据库执行 schema.sql | 不报错，admin 用户获得 ADMIN 角色 |
| 构建 | `mvn clean && mvn package -DskipTests` | 构建成功 |
| | `npm run build` | 构建成功 |
