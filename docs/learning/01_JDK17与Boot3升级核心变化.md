# 01 · JDK 17 与 Spring Boot 3 升级核心变化（学习笔记）

> 面向：把 beacon-cloud 从 JDK 1.8 / Boot 2.3.12 升级到 JDK 17 / Boot 3.2.12 的过程中，需要理解的核心概念。
> 状态：✅ 完成（纯学习文档，无代码改动）

---

## 1. 为什么要升级

| 事实 | 影响 |
| --- | --- |
| JDK 8 已于 2019 年停止公开更新（付费延长支持也陆续到期） | 无安全补丁 |
| Spring Boot 2.x 主线（2.7）2023-11 停止 OSS 支持 | 无安全补丁、生态不再适配 |
| Spring Cloud Hoxton 随 Boot 2.2/2.3 已 EOL | 无法获得新版本配套 |
| 本项目原用的 Shiro 1.4.0、mysql-connector 5.1.49、ES 7.6.2 均有已知 CVE 或已弃用 | 安全与维护风险（详见 `analysis/` 分析报告） |

升级不是"追新"，而是**回到受支持的版本线**：
JDK 17（LTS，支持到 2029+）→ Spring Boot 3.2.x → Spring Cloud 2023.0.x → Spring Cloud Alibaba 2023.0.3.x。

## 2. 版本对应关系（关键！）

Spring 生态版本必须成套，否则启动即报错：

| JDK | Spring Boot | Spring Cloud | Spring Cloud Alibaba |
| --- | --- | --- | --- |
| 1.8 | 2.3.x | Hoxton | 2.2.x |
| **17+** | **3.2.x** | **2023.0.x** | **2023.0.3.x** |
| 17+ | 3.3.x | 2023.0.3 | 2023.0.3.x |
| 17+ | 3.5.x | 2025.0.x | 2025.x |

本项目选择 **Boot 3.2.12 + Cloud 2023.0.3 + Alibaba 2023.0.3.2**：
- Boot 3.2 是 JDK 17 时代最成熟的 LTS 线之一，且 重构基线（learning/00 版本表）已冻结此选型。
- Cloud Alibaba 2023.0.3.2 是官方发布版（Maven Central 实测存在）。
- 本机只有 JDK 17 以上的高版本：用 `<maven.compiler.release>17</maven.compiler.release>` 编译，产物字节码即为 17，运行在 17/21 均可。

## 3. JDK 8 → 17 的新特性（重构中会用到）

| 特性 | 版本 | 用途 | 本项目对应规范 |
| --- | --- | --- | --- |
| `record` | 16 正式 | 不可变数据载体（DTO/VO/Form/配置） | AI代码编写规范：Form/VO 必须用 record |
| `sealed` 类/接口 | 17 | 封闭类型分支，编译器保证完备 | AI代码编写规范：结果类型用 sealed+record |
| Pattern Matching for instanceof | 16 | `if (obj instanceof String s)` | AI代码编写规范：必须使用 |
| switch 表达式 | 14 | 多分支返回值 | AI代码编写规范：必须使用 |
| Text Block | 15 | 多行字符串 | AI代码编写规范：必须使用 |
| `var` | 10 | 局部变量类型推断 | AI代码编写规范：推荐但克制 |
| `Stream.toList()` | 16 | 不可变 List | AI代码编写规范：必须使用 |
| 虚拟线程 | 21 | 高并发 IO | 本项目 JDK 17 不用（升级 21 后再谈） |

⚠️ 注意：**record 不能替代一切**——MyBatis 实体（需无参构造+setter）、跨模块可变流转对象（`StandardSubmit`）、策略上下文必须保留普通类 + Lombok `@Data`（见 AI代码编写规范.md 3.4 节）。

## 4. 最大的破坏性变更：javax → jakarta 命名空间

### 4.1 来龙去脉

Java EE（企业版规范，包名 `javax.*`）2017 年捐给 Eclipse 基金会，改名为 **Jakarta EE**。改名后的新版本包名从 `javax.*` 变为 `jakarta.*`：

- Jakarta EE 9 起：Servlet、Validation、Annotation、Persistence 等全部换包名。
- Spring Boot 3 / Spring Framework 6 基于 Jakarta EE 9+，因此**所有 Web 层 API 变成 `jakarta.*`**。

### 4.2 迁移对照表（本项目实际涉及）

| 旧（Boot 2） | 新（Boot 3） |
| --- | --- |
| `javax.annotation.PostConstruct` | `jakarta.annotation.PostConstruct` |
| `javax.annotation.Resource` | `jakarta.annotation.Resource` |
| `javax.servlet.http.HttpServletRequest/Response` | `jakarta.servlet.http.*` |
| `javax.servlet.Filter` / `ServletRequest` | `jakarta.servlet.*` |
| `javax.validation.constraints.*` / `javax.validation.Valid` | `jakarta.validation.constraints.*` / `jakarta.validation.Valid` |

**不需要改的**（容易被误改）：

| 包 | 原因 |
| --- | --- |
| `javax.crypto.*`（Mac、Cipher 等） | JDK 自带加密库，从来不属于 Java EE |
| `javax.mail.*` | Jakarta Mail 2.x 刻意保留 `javax.mail` 包名（避免所有邮件代码重写） |
| `javax.imageio.*` | JDK 自带 |
| `javax.sql.*` | JDK 自带 |

本项目 grep 统计：**35 处 javax 引用**，其中真正要改的是 annotation/servlet/validation 三类，其余不用动。

### 4.3 依赖侧的变化

| 依赖 | Boot 2 | Boot 3 |
| --- | --- | --- |
| 校验 | 自带 `javax.validation`（hibernate-validator 6） | `spring-boot-starter-validation` 带 jakarta + hibernate-validator 8 |
| 注解 | 靠 spring-context 传递 `jakarta.annotation-api` 1.3 | Boot 管理 `jakarta.annotation-api` 2.1 |
| Tomcat | 9（javax.servlet） | 10.1（jakarta.servlet） |
| Servlet 容器类加载失败 | `ClassNotFoundException: javax.servlet.*` 就是漏改 | — |

## 5. Spring Boot 3 其他关键变化

### 5.1 Jackson 与 LocalDateTime

- Boot 2：需手工 `new ObjectMapper()` + `registerModule(new JavaTimeModule())`，字段上常加 `@JsonSerialize/@JsonDeserialize` 注解。
- Boot 3：自动配置的 `ObjectMapper` **内置 JavaTimeModule**，且输出 `ISO-8601` 格式。→ 手工注解可删（重构约定：移除 jsr310 手工注解）。
- ⚠️ 坑：`Jackson2JsonMessageConverter`（RabbitMQ）**默认构造创建的是裸 ObjectMapper，不带 JavaTimeModule**。本项目 MQ 消息体含 `LocalDateTime`，所以各模块的 MQ 转换器要改为注入 Boot 的 ObjectMapper Bean，否则运行期序列化报错（编译期不报）。

### 5.2 自动配置注册机制

- Boot 2.7 前：`META-INF/spring.factories` 里 `EnableAutoConfiguration`。
- Boot 2.7 起：新格式 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
- 本项目没有自定义 starter，**无感知**；但升级第三方 starter 时，老版本 starter 若只支持 spring.factories 旧写法，在新 Boot 3.2 上仍兼容（3.x 同时支持两种格式，逐步弃用）。

### 5.3 配置属性绑定

- `@ConstructorBinding` 在 Boot 3 中**不再需要**（构造器绑定成为默认，只要只有一个构造器）。
- 本项目大量用 `@Value("${xx:default}")`，**完全兼容**，无需改动。
- `@ConfigurationProperties` 类若升级为 record（AI代码编写规范推荐），注意 record 是 final 且构造器即绑定入口，与 `@RefreshScope` 的 CGLIB 代理可能冲突——本项目 `CheckFilterContext` 等 @RefreshScope 的类不要用 record。

### 5.4 Spring Cloud / Alibaba 升级注意

- `spring-cloud-starter-alibaba-nacos-discovery/config` 的坐标不变，版本由 BOM 管理。
- Nacos 客户端从 1.x 升到 2.x，**服务端必须 ≥ 2.x**（升级基线定 2.3.x）。
- `@EnableDiscoveryClient` 依旧可用（Cloud 2023.0.x 中保留）。
- OpenFeign 升到 4.x：`@FeignClient` 用法不变；底层默认客户端变化不影响本项目。

### 5.5 其他会踩到的点（本项目视角）

| 变化 | 对本项目的影响 |
| --- | --- |
| JUnit 4 → JUnit 5 | 旧测试 `org.junit.Test`、`Assert.*` 编译失败，需改 `org.junit.jupiter.api.*`（common/monitor/webmaster 等都有） |
| mockito-inline | Boot 3 的 starter-test 已含 mockito-core（inline 能力默认开启），显式 `mockito-inline` 依赖可删 |
| MyBatis starter 3.0.x | `map-underscore-to-camel-case` 配置键不变；entity/Example 生成代码不受影响 |
| Druid | **必须换 `druid-spring-boot-3-starter`**（boot2 starter 的自动配置在 Boot 3 不生效） |
| Shiro | Shiro 官方对 Jakarta EE 支持长期滞后（1.10+ 才起步），重构已决定弃用，换 Sa-Token（见笔记 02） |
| ES RestHighLevelClient | ES 8 移除该 API，必须重写为 `co.elastic.clients:elasticsearch-java`（见笔记 03） |
| Spring AMQP 3.x | 监听器/转换器 API 不变；`x-delayed-message` 插件用法不变 |
| Caffeine | Boot 3 管理 3.x，本项目 `Caffeine.newBuilder().build()` API 兼容 |

## 6. 本项目的实际迁移步骤（预览）

1. 父 POM：Boot 3.2.12 + BOM 导入 + 三方组件版本收口（详见笔记 10 模块方案）。
2. `beacon-common`：jakarta 注解 import + 移除 jsr310 手工注解 + JUnit 5。
3. 各 Web 模块：javax.servlet / javax.validation import 替换。
4. `beacon-search`：ES 客户端整体重写（笔记 03）。
5. `beacon-webmaster`：Shiro→Sa-Token + Kaptcha 接口化 + druid boot3 starter（笔记 02）。
6. `beacon-smsgateway`：Netty 4.1.138 + 线程池方案（笔记 04、05）。
7. `beacon-monitor`：xxl-job 2.4.2 + jakarta.annotation（笔记 05）。

## 7. 常见报错速查

| 报错 | 原因 | 解法 |
| --- | --- | --- |
| `package javax.servlet does not exist` | 漏改 servlet import | 改 `jakarta.servlet` |
| `package javax.validation does not exist` | 漏改校验 import | 改 `jakarta.validation`；确认有 starter-validation |
| `cannot access javax.annotation.PostConstruct` | JDK 17 没有 javax.annotation | 改 `jakarta.annotation.PostConstruct` |
| `NoSuchMethodError: com.fasterxml.jackson.databind...` | Jackson 版本混用 | 以 Boot BOM 为准，删除手工版本 |
| `Invalid value type for attribute 'factoryBeanObjectType'` | MyBatis 2.x 跑在 Boot 3 | 升 mybatis-starter 3.0.x |
| `Error creating bean ... druid` | 用 boot2 的 druid starter | 换 `druid-spring-boot-3-starter` |
| JUnit 编译错误 `org.junit` | Boot 3 无 JUnit 4 | 测试迁移 JUnit 5 |
| MQ 消息 LocalDateTime 序列化异常 | 转换器用裸 ObjectMapper | 注入 Boot 的 ObjectMapper Bean |
| `找不到符号: 方法 setXxx/getXxx`（Lombok 全失效，**仅 compile 阶段报，validate 不报**） | ① JDK 23+ 起 `javac` 默认 `-proc:none`（注解处理关闭）；② Boot 3.2.12 管理的 Lombok 1.18.36 不支持 JDK 25 | 二选一：<br>**A（推荐）** 把 JAVA_HOME 切到 JDK 17 或 21（Boot 3.2 官方支持范围 17~21）→ 零改动；<br>**B** 坚持用 JDK 25 → 父 POM 同时加 `maven-compiler-plugin` 的 `<proc>full</proc>` 与 `<lombok.version>1.18.46</lombok.version>`（版本覆盖 Boot 管理值） |

> **实测记录（2026-09-16，本机 JDK 25.0.3 + Maven 3.9.12 + Boot 3.2.12）**：`mvn -N validate` **成功**（父 POM 可解析），但真正 `compile` 时才暴露 Lombok 注解处理器未运行；`record`/`sealed`/模式匹配 `instanceof`/`switch` 表达式/Text Block/`Stream.toList`/`Map.ofEntries` 全部编译通过，产物 `major version: 61`（Java 17）正确。单独用 `javac -proc:full` 隔离测试：Lombok 1.18.36 失败、1.18.46 成功 —— 证明是"注解处理开关 + Lombok 版本"两个因素叠加，不是编译器版本本身不兼容 release 17。**注意 JDK 17 语法边界**：`switch` 中的类型模式、`switch` 对 sealed 类型的穷尽推断都是 JDK 21 特性，在 `release 17` 下会直接编译报错。

---

*下一篇：02 Sa-Token 入门与 Shiro 迁移对照。*
