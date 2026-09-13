# AI 代码编写规范（cloud-sms 重构专用）

> 文档位置：`D:\Code\project\cloud-sms\AI代码编写规范.md`（根目录，随项目走）
> 适用对象：**所有参与本重构的 AI/开发工具生成的代码**，以及人工编写的等价代码
> 定位：代码阶段启动后，本文件是**最高优先级、自包含的代码约束**。本文件不依赖任何其它文档即可独立执行；内部文档（`docs/learning/00`、`docs/learning/09`、`docs/02_项目详细分析总报告.md`）只作任务范围与背景参考。
> 背景：本次重构的目标之一是通过实战学习 **JDK 17 新特性**，因此**只要能使用新特性的场景，禁止退回旧写法**。

---

## 1. 总原则

1. **JDK 17 优先**：任何能用 JDK 17 新特性表达的场景，必须用新特性；自查标准——"这段代码在 JDK 8 能写出来吗？能写出来就要改"。
2. **不可变优先**：DTO / VO / Form / 配置 / 结果类型一律不可变（record / sealed）。
3. **只做被要求的事**：不顺手重构无关代码、不修不在任务清单里的业务缺陷、不删除"看似无用"的历史代码。
4. **每次改动必须可编译**：完成一个模块（或一个文件）的改动立即编译验证，禁止攒一批再编译。
5. **证据优先**：不确定的 API/版本先查证（Maven Central、官方文档），**禁止编造 API**。

## 2. 技术基线（自包含版本清单）

重构后的技术底座（AI 生成代码时以此为准，不得擅自更换版本）：

| 类别 | 组件 | 版本 |
| --- | --- | --- |
| JDK | 编译/运行目标 | 17（本机 JDK 21 以 `--release 17` 编译） |
| 框架 | Spring Boot | 3.2.12 |
| 微服务 | Spring Cloud / Spring Cloud Alibaba | 2023.0.3 / 2023.0.3.2 |
| 注册/配置 | Nacos | 2.3.x |
| 认证 | Sa-Token（取代 Shiro） | 1.46.0（`sa-token-spring-boot3-starter` + `sa-token-redis-jackson`） |
| 持久层 | MyBatis starter / Druid | 3.0.5 / 1.2.31（`druid-spring-boot-3-starter`） |
| 检索 | Elasticsearch Java Client | 8.19.21（`co.elastic.clients:elasticsearch-java`） |
| 消息 | RabbitMQ（`spring-boot-starter-amqp`） | 由 Boot BOM 管理；delayed 插件必装 |
| 网关 | Netty | 4.1.138.Final |
| 调度 | xxl-job-core | 2.4.2 |
| 工具 | hutool-dfa / ikanalyzer / kaptcha | 5.8.47 / 2012_u6 / 0.0.9 |
| 线程池 | Hippo4j | ⚠️ 2.x 未发布 Central，暂用标准 `ThreadPoolExecutor` 替换 |

关键纪律：
- **版本只写在父 POM 的 `dependencyManagement`**，子模块一律不写版本号。
- 新增依赖前必须在 Maven Central 验证存在性。
- 包命名空间：Boot 3 下 servlet/validation/annotation 一律 `jakarta.*`（`javax.crypto`、`javax.mail`、`javax.imageio`、`javax.sql` 不变）。

## 3. JDK 17 特性使用矩阵（强制项）

### 3.1 必须使用（出现旧写法 = 不合格）

| # | 场景 | 必须写法 | 禁止写法 |
| --- | --- | --- | --- |
| R1 | 请求 Form / 响应 VO / 查询 DTO | `record` | `@Data` 类 / 手写 getter-setter |
| R2 | `@ConfigurationProperties` 配置类 | `record` + 构造器绑定 | 字段类 + setter |
| R3 | instanceof 后紧跟强转 | 模式匹配 `if (x instanceof Foo f)` | `if (x instanceof Foo) { Foo f = (Foo) x; ... }` |
| R4 | 多分支返回一个值 | `switch` 表达式（枚举全覆盖；配合 sealed 可省略 default） | if-else 链、switch 语句+临时变量 |
| R5 | SQL / JSON / 长文本拼接 | Text Block `"""..."""` + `.formatted(...)` | 逐段字符串拼接 |
| R6 | 收集到不需要修改的 List | `stream().toList()` | `collect(Collectors.toList())` |
| R7 | 少量固定集合 | `List.of()` / `Set.of()` / `Map.of()`（>10 对用 `Map.ofEntries`） | `new ArrayList<>() {{add...}}` 等 |
| R8 | 判断空 + 抛异常 | `Optional.ofNullable(x).orElseThrow(() -> ...)` | `if (x == null) throw ...` 裸写 |
| R9 | 空串判断 / 空值默认 | `String.isBlank()` / `Objects.requireNonNullElse` | `x == null \|\| "".equals(x)` |
| R10 | 字符串处理 | `strip()` / `isBlank()` / `repeat()` / `lines()` | `trim()` / `length()==0` / 手写循环 |
| R11 | 文件读写 | `Files.readString()` / `Files.writeString()` | BufferedReader 手写循环 |
| R12 | try-with-resources | Java 9 增强版（资源可为已声明 final 变量） | 手动 close / finally |
| R13 | 内部类只读外部变量 | 直接引用（effectively final） | 套一层 final 数组 / 包装类 |
| R14 | 集合批量操作 | `removeIf` / `computeIfAbsent` / `merge` | 手写 contains+remove 循环 |

### 3.2 结果类型（推荐，写业务分支时优先考虑）

```java
// 封闭的成功/失败二元结果，代替"code + message"的散装字段
public sealed interface BalanceCommandResult
        permits BalanceCommandResult.Success, BalanceCommandResult.Failure {

    record Success(long balance) implements BalanceCommandResult {}
    record Failure(int code, String message) implements BalanceCommandResult {}

    default boolean isSuccess() { return this instanceof Success; }
}

// 使用处：编译器保证分支完备
String msg = switch (result) {
    case BalanceCommandResult.Success s -> "ok, balance=" + s.balance();
    case BalanceCommandResult.Failure f -> "fail: " + f.message();
};
```

适用候选（本项目重构时优先落地）：`BalanceCommandResult`、缓存读写结果、策略链判定结果、CMPP 应答解析结果。
注意：`sealed` 的 `permits` 子类须与父类同包；Spring 容器动态注入的策略接口（如过滤器链）**不用** sealed。

### 3.3 var 的边界（克制使用）

- ✅ 可用：右侧类型一目了然时（`var result = cacheFacade.hGetAll(key);`）。
- ❌ 禁止：方法签名、字段声明、公共 API、右侧看不出类型时（`var x = getSomething();`）。

### 3.4 明确禁止使用新特性的场景（防止误用）

| 场景 | 原因 | 正确写法 |
| --- | --- | --- |
| MyBatis 持久化实体（entity） | 需无参构造 + 可变 setter，record 是 final | Lombok `@Data`（保留） |
| 跨模块可变流转对象（`StandardSubmit`/`StandardReport`） | 链路中反复 `setXxx` | Lombok `@Data`（保留） |
| 策略上下文 / 过滤器流转对象 | 多个过滤器先后写入 | Lombok `@Data`（保留） |
| `@RefreshScope` 的类 | record 是 final，CGLIB 代理冲突 | 普通类 |
| 需要继承/被子类扩展的类 | record final | 普通类 |
| MQ 消息体且消费侧需改字段 | 反序列化后可变 | `@Data` 类 |
| record 的组件类型 | 浅不可变，可变集合需兜底 | 构造器内 `List.copyOf` |

**Lombok 分工总表**：

| 场景 | 选型 |
| --- | --- |
| 持久化实体 / 跨模块可变流转对象 | Lombok `@Data` |
| 请求表单 / 响应 VO / DTO / 配置快照 / 值对象 | `record`（禁用 Lombok） |
| 封闭分支结果类型 | `sealed interface` + `record` |

## 4. 新代码风格强制项（非特性类）

1. **日期时间**：一律 `java.time.*`，禁止 `java.util.Date`、`SimpleDateFormat`、`Calendar`。
2. **并发**：新并发代码优先 `ConcurrentHashMap`/原子类/`CompletableFuture`；禁止裸 `synchronized(this)` 长临界区（存量代码不主动改）。
3. **异常**：业务异常必须带上下文（sequenceId / mobile / channelId 至少其一），禁止 `catch (Exception e) { e.printStackTrace(); }`。
4. **日志**：一律 `@Slf4j` + 占位符 `log.info("xxx={}", value)`，禁止字符串拼接日志、禁止打印完整短信内容与密钥。
5. **注释**：新增类/公开方法必须有中文 Javadoc 风格注释（说明"为什么"）；改动他人代码用 `//` 标注原因。
6. **命名**：沿用 `com.cz.*` 包结构；常量全大写；枚举按 `XxxEnums` 命名。
7. **集合**：返回值优先不可变；内部可变集合用接口类型声明（`List<X> l = new ArrayList<>();`）。
8. **空值**：方法返回集合禁止返回 null（返回空集合）；入参允许空时用 `@Nullable` 标明。
9. **分层与包结构**（新增代码遵守）：

```text
com.cz.{module}
├── controller      # HTTP 入口，只做参数接收、校验、调用 service
├── service(.impl)  # 业务接口与实现
├── mq              # MQ 监听器 / 生产者
├── client          # Feign 客户端及其 dto
├── config          # @Configuration / @ConfigurationProperties
├── filter          # 过滤器 / 策略（接口 + 实现）
├── dto / vo / form # 数据载体（record）
├── util            # 无状态工具类
└── entity / mapper # 持久化实体与 Mapper（Lombok @Data）
```

约束：Controller 禁止写业务逻辑；DTO/VO/Form 用 record，与 entity 严格分离；跨服务传输对象优先复用 `beacon-common`。

## 5. AI 执行约束（过程性硬规则）

1. **范围锁定**：只改任务指定的模块/文件；发现额外问题先记录到任务报告，**不顺手修**。
2. **编译验证**：每完成一个模块执行 `mvn -pl <模块> -am install -DskipTests`（PowerShell 下参数用引号包裹），编译不过不进入下一模块。
3. **测试迁移**：该模块旧测试同步迁 JUnit 5（`org.junit.jupiter.api.*`）；新增代码**必须带对应单测**（纯 POJO/record 可豁免）。
4. **版本纪律**：新增依赖版本一律在父 POM `dependencyManagement` 声明，子模块不写版本号；引入前先在 Maven Central 验证存在性。
5. **提交纪律**：一个模块一个 commit，message 格式 `refactor(模块): 描述`；不混入无关文件。
6. **对照学习**：每落一个 JDK 17 特性，在 `docs/learning/` 对应笔记末尾补一行实战记录（特性名 + 文件 + 一句话心得）——服务于"边重构边学习"目标。
7. **禁止行为清单**：
   - 禁止删除/改写不在任务范围的代码（含"死代码"，除非任务明确要求）
   - 禁止改依赖版本（本规范第 2 节已冻结全部版本）
   - 禁止把数据库/邮箱/网关密码写进新代码或新配置（一律 `${...}` 占位 + Nacos）
   - 禁止编造 Nacos 配置项；新增配置必须在 `docs/learning/09` 的对应模块清单中登记

## 6. 审查清单（每次提交前逐项自查）

- [ ] 新写的 Form/VO/DTO 是 record 而非 `@Data`？
- [ ] 没有 `instanceof` + 强转（应模式匹配）？
- [ ] 没有 if-else 链返回值（应 switch 表达式）？
- [ ] 没有字符串拼接 SQL/JSON（应 Text Block）？
- [ ] 没有 `collect(Collectors.toList())`（应 `Stream.toList()`）？
- [ ] 没有 `new ArrayList<>()` 承载固定元素（应 `List.of`）？
- [ ] entity / StandardSubmit 等可变对象仍是普通类（未误改 record）？
- [ ] 没有 `java.util.Date` / `SimpleDateFormat`？
- [ ] 没有 `var` 出现在公共 API / 字段？
- [ ] servlet/validation/annotation 用了 `jakarta.*`（而非 `javax.*`）？
- [ ] 编译验证 + 单测通过？
- [ ] 是否越界改了任务清单之外的代码？

## 7. 示例对照表（旧 → 新）

| 旧写法（禁止） | 新写法（必须） |
| --- | --- |
| `class SingleSendForm { private String apikey; public String getApikey() {...} }` | `record SingleSendForm(@NotBlank String apikey, @NotBlank String mobile, String text, Integer state, String uid) {}` |
| `if (msg instanceof CmppSubmitResp) { CmppSubmitResp r = (CmppSubmitResp) msg; ... }` | `if (msg instanceof CmppSubmitResp resp) { ... }` |
| `String stateName; if (state==0) stateName="waiting"; else if (state==1)...` | `String stateName = switch (state) { case 0 -> "waiting"; case 1 -> "success"; default -> "fail"; };` |
| `String json = "{\"clientId\":" + clientId + "}";` | `String json = """ {"clientId": %s} """.formatted(clientId);` |
| `list.stream().map(...).collect(Collectors.toList())` | `list.stream().map(...).toList()` |
| `Set<String> s = new HashSet<>(); s.add("a"); s.add("b");` | `Set<String> s = Set.of("a", "b");` |
| `if (name == null \|\| "".equals(name.trim())) throw ...` | `Optional.ofNullable(name).filter(n -> !n.isBlank()).orElseThrow(...)` |
| `new String(Files.readAllBytes(p))` | `Files.readString(p)` |
| 配置类字段 + setter + `@Value` 注入 | `@ConfigurationProperties(prefix="cmpp") record CmppGatewayProperties(String host, int port, String serviceId, String pwd) {}` |
| `List<String> l = new ArrayList<>(); for (String s : src) l.add(trim(s));` | `List<String> l = src.stream().map(String::strip).toList();` |

## 8. 内部参考文档（仅作背景，不以它们为准）

- `docs/learning/00_重构路线图与进度.md` —— 版本调研过程与决策记录、进度台账
- `docs/learning/09_从零重写方案.md` —— 从零重写路线执行蓝图（模块设计/缺陷规避清单以此为准）
- `docs/02_项目详细分析总报告.md` 与 `docs/analysis/` —— 旧项目分析报告（业务缺陷清单，升级阶段不修）
- `docs/learning/01~05` —— 各组件升级学习笔记
