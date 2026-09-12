# 04 · Netty 4.1 升级与 CMPP 网关改造（学习笔记）

> 面向：beacon-smsgateway 的 Netty 客户端升级（4.1.69 → 4.1.138.Final）、动态线程池替换方案、连接参数外置化。
> 状态：✅ 完成（纯学习文档，无代码改动）
> 现状依据：`../analysis/report_gateway_push_search.md`（P1-P49 问题清单）、重构模块清单（"连接参数外置化"）。

---

## 1. Netty 是什么（30 秒回顾）

Netty 是 Java 的**异步事件驱动网络框架**。本项目用它的客户端能力：

```text
EventLoopGroup（I/O 线程池）
   └─ Bootstrap（客户端引导器）
        └─ Channel（一条 TCP 连接）
             └─ Pipeline（Handler 链，数据像流水线一样依次穿过）
                  ├─ LengthFieldBasedFrameDecoder   帧解码（按长度字段切包）
                  ├─ IdleStateHandler              空闲检测（心跳触发）
                  ├─ HeartHandler                  心跳/断线重连
                  ├─ CMPPEncoder / CMPPDecoder     协议编解码
                  └─ CMPPHandler                   业务处理（SubmitResp/Deliver 分发）
```

本项目关键类：`NettyStartCMPP`（Bean 装配）→ `NettyClient`（连接/提交/重连）→ `NettyClientInitializer`（装配 Pipeline）→ `CMPPHandler`（把应答扔进线程池）。

## 2. Netty 4.1.69 → 4.1.138.Final：升级要点

- **4.1 线是 API 稳定线**：Bootstrap/Channel/ChannelHandler/ByteBuf 用法全部不变，本项目属**原地升级**（只改 pom 版本，代码零改动概率高）。
- 为什么不升 Netty 4.2/5：4.2 对 API 有较大重构（如 `EventLoopGroup` 接口拆分），5.0 仍是 Alpha（Central 实测 5.0.0.Alpha2），**学习阶段求稳，钉在 4.1 最新版**。
- 升级带来的实际收益：4.1.69（2021）→ 4.1.138（2025）修复了大量内存/HTTP 编解码 CVE 与 bug（本项目只用 TCP + 自研 CMPP 编解码，主要吃 bugfix 红利）。
- 验证方式：`mvn compile` + 启动 smsgateway 连模拟 CMPP Server（127.0.0.1:7890）跑通提交-应答-回执。

## 3. 动态线程池：Hippo4j 决策与替换方案

### 3.1 现状

```java
// ThreadPoolConfig（现状）
@Bean
@DynamicThreadPool(threadPoolId = "cmpp-submit")   // hippo4j 1.5.0 注解
public ThreadPoolExecutor cmppSubmitPool() {
    return ThreadPoolBuilder.dynamicPool().build();  // 容量全靠 hippo4j 服务端下发
}
```

用途：`cmppSubmitPool` 处理 SubmitResp、`cmppDeliverPool` 处理 Deliver，通过 `SpringUtil.getBeanByName` 取用。

### 3.2 调研结论（2026-09 实测 Maven Central）

- `cn.hippo4j:hippo4j-spring-boot-starter` 最新 **1.5.0**（Boot 2 时代产物，与 Boot 3 不兼容）。
- **不存在** `hippo4j-spring-boot3-starter` 工件；hippo4j 2.x（opengoofy/hippo4j fork）未发布到 Central。
- 期望"升级 2.x"在当前不可行。

### 3.3 决策：标准线程池等价替换 + 回归预案

重构阶段用 **Spring 原生 `ThreadPoolExecutor` Bean** 替换，行为对齐：

```java
@Bean("cmppSubmitPool")
public ThreadPoolExecutor cmppSubmitPool() {
    return new ThreadPoolExecutor(
            corePoolSize, maxPoolSize,
            keepAliveSeconds, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new ThreadFactoryBuilder()...("cmpp-submit"),
            new ThreadPoolExecutor.CallerRunsPolicy());   // 拒绝策略：防止回执风暴拖垮 Netty 通道
}
```

| 项 | 现状（hippo4j 1.5.0） | 替换后 |
| --- | --- | --- |
| 容量参数 | 依赖 hippo4j 服务端下发（缺失时不可控） | 本地 yml 配置（Nacos 可刷新），默认值显式给出 |
| 动态调整 | 运行期可改 | 失去（**学习要点：动态线程池本质是"配置中心驱动的参数热更新"，见 3.4**） |
| 拒绝策略 | 未显式配置（分析报告 P35：池满抛 RejectedExecutionException 沿 Netty 传播可能关闭通道） | 显式 CallerRunsPolicy 兜底 |
| Bean 名 | 不变（`SpringUtil.getBeanByName` 无需改动） | 不变 |

**回归预案**：待 hippo4j 2.x 发布到 Central 后，仅需改回 `ThreadPoolConfig` 一个文件（Bean 名与取用方式不变，其余代码零影响）。

### 3.4 学习要点：动态线程池的原理（为什么将来要换回）

1. 普通线程池：core/max/queue 是构造时写死的，改参数要重启。
2. 动态线程池：客户端把线程池参数上报到 **hippo4j-server**，服务端通过长轮询/事件把新参数下发，客户端用 `ThreadPoolExecutor.setCorePoolSize()/setMaximumPoolSize()` 热更新——**本质是"配置中心 + 运行时 API"的组合**。
3. 本项目暂时用"Nacos 配置 + 重启生效"顶替"运行期热更新"，业务场景（回执吞吐）可接受。

## 4. 连接参数外置化（重构要求的改造项）

### 4.1 现状（分析报告 P1）

```java
// NettyStartCMPP（现状）：参数硬编码在 static 字段
public static String host = "127.0.0.1";
public static int port = 7890;
public static String serviceId = "cz";
public static String pwd = "123";
```

### 4.2 改造方案（技术升级范畴）

```yaml
# bootstrap.yml / Nacos beacon-smsgateway-dev.yml
cmpp:
  host: 127.0.0.1
  port: 7890
  service-id: cz
  pwd: 123
```

```java
@ConfigurationProperties(prefix = "cmpp")     // Boot 3 推荐构造器绑定/record（AI代码编写规范）
public record CmppGatewayProperties(String host, int port, String serviceId, String pwd) {}

@Configuration
public class NettyStartCMPP {
    @Bean(initMethod = "start")
    public NettyClient nettyClient(CmppGatewayProperties props) {
        return new NettyClient(props.host(), props.port(), props.serviceId(), props.pwd());
    }
}
```

收益：环境切换不用改代码；多通道扩展有落脚点。注意 `start()` 里的 `connect().sync()` 阻塞启动仍是已知缺陷（P17），**属业务缺陷，本轮不修**。

## 5. 本次升级「不动」的清单（划清范围）

以下 CMPP/连接可靠性问题在分析报告中属于业务缺陷，**按约定留到升级后处理阶段**：

| 问题 | 分析报告编号 |
| --- | --- |
| CmppSubmit 序列化缺 Msg_Fmt、Msg_Id 恒 0、12 小时时间戳等协议缺陷 | P2-P14 |
| 断连时忽略 submit() 返回值 → 消息静默丢失 | P24 |
| 重连新建 NioEventLoopGroup 不关闭 → 线程泄漏 | P15 |
| I/O 线程阻塞重连、心跳不校验应答 | P16/P18 |
| 回执风暴时线程池拒绝 → 通道被关 | P35 |

## 6. 学习路径建议

1. Netty 官方 User Guide（`https://netty.io/wiki/user-guide.html`）第 1-6 章：Bootstrap、Channel、Handler、ByteBuf。
2. 对照本模块 7 个 netty4 类逐一画 Pipeline 数据流图。
3. 带着问题读：为什么 `LengthFieldBasedFrameDecoder(MAX,0,4,-4,0,true)` 能切出完整 CMPP 帧？为什么编码器在解码器之前？（出站/入站方向相反）

## 7. 迁移后验证清单

- [ ] `mvn compile` 通过（Netty 版本升级编译无变化）
- [ ] 启动 smsgateway 能连上模拟 CMPP Server（连接参数来自 Nacos/yml）
- [ ] 发送测试短信：Submit → SubmitResp → 写日志队列
- [ ] 模拟 Deliver 回执：状态更新交换机 → 10s 后 search 更新 ES
- [ ] 断网恢复：重连行为与旧版一致

---

*下一篇：05 xxl-job 2.4 与监控模块升级。*
