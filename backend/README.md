# EduMerge Backend - Spring Boot 3 项目

## 项目结构说明

```
backend/
├── pom.xml                              # Maven 配置文件
├── src/
│   ├── main/
│   │   ├── java/com/edumerge/
│   │   │   ├── EduMergeApplication.java # 应用启动类
│   │   │   ├── common/
│   │   │   │   ├── result/              # 统一返回对象
│   │   │   │   │   ├── Result.java      # Result<T> 通用返回类
│   │   │   │   │   └── ResultCode.java  # 状态码枚举
│   │   │   │   └── exception/           # 异常处理
│   │   │   │       ├── BusinessException.java
│   │   │   │       └── GlobalExceptionHandler.java
│   │   │   ├── entity/                  # 数据库实体
│   │   │   ├── mapper/                  # MyBatis Mapper
│   │   │   ├── service/                 # 业务服务
│   │   │   ├── controller/              # REST 控制器
│   │   │   ├── dto/                     # 数据传输对象
│   │   │   ├── config/                  # 配置类（Redis、RabbitMQ等）
│   │   │   ├── mq/                      # 消息队列相关
│   │   │   ├── rag/                     # RAG 相关模块
│   │   │   └── utils/                   # 工具类
│   │   └── resources/
│   │       ├── application.yml          # 通用配置
│   │       ├── application-dev.yml      # 开发环境配置
│   │       ├── application-prod.yml     # 生产环境配置
│   │       ├── logback-spring.xml       # 日志配置
│   │       └── mapper/                  # MyBatis XML 映射文件
│   └── test/
│       └── java/com/edumerge/           # 测试代码
└── README.md
```

## 快速开始

### 1. 环境要求

- Java 21+
- Maven 3.8+
- MySQL 8.0+
- Redis 6.0+
- RabbitMQ 3.10+
- Milvus 2.3+

### 2. 依赖安装

使用 Maven 安装依赖：

```bash
cd backend
mvn clean install
```

### 3. 数据库初始化

创建数据库和表：

```sql
CREATE DATABASE edumerge CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 4. 配置文件说明

#### application.yml 主配置

- MySQL 连接配置
- Redis 连接配置
- RabbitMQ 连接配置
- MyBatis-Plus 配置
- Milvus 向量数据库配置
- LangChain4j 大模型配置

#### application-dev.yml 开发环境

本地开发时使用，所有服务指向 localhost

#### application-prod.yml 生产环境

使用环境变量注入敏感信息，参见下表

### 5. 环境变量配置（生产环境）

| 环境变量            | 说明           | 示例                 |
| ------------------- | -------------- | -------------------- |
| `DB_USERNAME`       | 数据库用户名   | root                 |
| `DB_PASSWORD`       | 数据库密码     | secure_password      |
| `REDIS_HOST`        | Redis 主机     | redis.example.com    |
| `REDIS_PORT`        | Redis 端口     | 6379                 |
| `REDIS_PASSWORD`    | Redis 密码     | redis_password       |
| `RABBITMQ_HOST`     | RabbitMQ 主机  | rabbitmq.example.com |
| `RABBITMQ_PORT`     | RabbitMQ 端口  | 5672                 |
| `RABBITMQ_USERNAME` | RabbitMQ 用户  | admin                |
| `RABBITMQ_PASSWORD` | RabbitMQ 密码  | admin_password       |
| `MILVUS_HOST`       | Milvus 主机    | milvus.example.com   |
| `OPENAI_API_KEY`    | OpenAI API Key | sk-xxx...            |

### 6. 启动应用

#### 开发环境

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"
```

#### 生产环境

```bash
java -jar target/edumerge-backend-1.0.0.jar --spring.profiles.active=prod
```

## 核心组件说明

### Result<T> - 统一返回对象

所有 API 返回格式如下：

```json
{
  "code": 0,
  "message": "SUCCESS",
  "data": {
    /* 业务数据 */
  }
}
```

使用示例：

```java
// 成功响应
return Result.success(data);

// 失败响应
return Result.fail(ResultCode.USER_NOT_EXIST);

// 自定义错误
return Result.fail(400, "自定义错误信息");
```

### GlobalExceptionHandler - 全局异常处理

自动捕获并处理以下异常：

- `BusinessException` - 业务异常
- `MethodArgumentNotValidException` - 参数验证异常
- `MethodArgumentTypeMismatchException` - 类型不匹配异常
- `NoHandlerFoundException` - 404 异常
- `Throwable` - 其他所有异常

### 日志配置

使用 SLF4J + Logback，支持：

- 控制台输出
- 文件输出（按日期和大小轮转）
- 错误日志单独记录
- 异步日志写入

日志等级配置在 `application.yml` 中的 `logging.level`

## 关键特性

### 1. 异步处理 - RabbitMQ 集成

所有长时间操作（如文档处理、向量化）必须通过 RabbitMQ 异步执行

### 2. RAG 零幻觉原则

大模型回答必须基于 Milvus 检索结果，严禁模型发散编造

### 3. 缓存优先 - Redis 集成

热点数据使用 Redis 缓存，减少数据库查询

### 4. Java 21 新特性

- Records (不可变数据类)
- Pattern Matching (模式匹配)
- Virtual Threads (虚拟线程) - 推荐用于 I/O 密集操作

## 常见命令

```bash
# 清理旧编译
mvn clean

# 编译和打包
mvn package

# 跳过测试打包
mvn package -DskipTests

# 运行单元测试
mvn test

# 生成可执行 JAR
mvn clean package -P production

# 查看依赖树
mvn dependency:tree

# 检查依赖冲突
mvn dependency:analyze
```

## 开发规范

1. **异常处理**：不要吞掉异常，必须通过 BusinessException 或 GlobalExceptionHandler 处理
2. **日志记录**：使用 @Slf4j 注解，关键节点记录 info/warn，错误记录 error
3. **中文注释**：复杂业务逻辑必须添加中文注释说明
4. **返回对象**：所有 API 必须返回 Result<T> 包装的对象
5. **参数校验**：使用 @Valid + @NotNull 等注解进行参数验证
6. **代码审查**：提交前使用 IDE 格式化并检查 Sonar/Checkstyle 规则

## 疑难排查

### MySQL 连接失败

```
检查 application.yml 中的数据库 URL、用户名、密码
确保 MySQL 服务已启动且监听相应端口
```

### Redis 连接失败

```
检查 Redis 服务是否启动
确保防火墙允许 6379 端口访问
```

### RabbitMQ 连接失败

```
检查 RabbitMQ 服务是否启动
确保用户名密码正确
```

## 联系方式

问题或建议请提交 Issue 或 Pull Request
