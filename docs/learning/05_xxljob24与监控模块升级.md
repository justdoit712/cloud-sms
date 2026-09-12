# 05 · xxl-job 2.4 与监控模块升级（学习笔记）

> 面向：beacon-monitor 的 xxl-job 2.3.1 → 2.4.2 升级、jakarta 注解迁移、线程池决策收口。
> 状态：✅ 完成（纯学习文档，无代码改动）

---

## 1. xxl-job 是什么（30 秒回顾）

xxl-job 是国产分布式任务调度平台，两大角色：

```text
xxl-job-admin（调度中心，独立部署的 Web 平台）
      │  HTTP 触发（任务配置的 cron 到了就回调）
      ▼
xxl-job Executor（执行器，内嵌在本项目的 beacon-monitor 里）
      └─ XxlJobSpringExecutor Bean：注册任务、接收调度、回传执行结果
```

本项目两个任务：`monitorQueueMessageCountTask`（队列积压>10000 邮件告警）、`monitorClientBalanceTask`（余额<500 元提醒），外加一个 `test`。

### 1.1 关键代码形态（升级前后不变）

```java
@XxlJob("monitorQueueMessageCountTask")   // 任务名，与调度中心配置对应
public void monitor() { ... }
```

```java
// XxlJobConfig：执行器装配
XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
executor.setAdminAddresses(...);  // 调度中心地址
executor.setAppname(...);         // 执行器名称（调度中心登记的名字）
executor.setPort(...);            // 内嵌 HTTP 端口，供调度中心回调
```

## 2. 2.3.1 → 2.4.2 升级要点

| 项 | 结论 |
| --- | --- |
| API 兼容性 | `@XxlJob`、`XxlJobSpringExecutor` 不变，**只改 pom 版本**即可 |
| Boot 3 兼容 | xxl-job-core 不依赖 servlet 容器，2.4.x 在 Boot 3 下可用（社区主流用法） |
| 升级收益 | 2.4.x 修复若干调度 bug、支持新 admin 2.4 特性（如任务超时/重试优化） |
| admin 配套 | 调度中心也要用 2.4.x 版本部署（升级基线定 2.4.x） |
| 调度配置 | cron 与任务名都在调度中心配置，**执行器代码零改动** |

## 3. beacon-monitor 模块的迁移清单

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| pom.xml | xxl-job-core 版本删除（父 POM 管 2.4.2） | 版本治理 |
| XxlJobConfig / XxlJobProperties | 不动 | API 兼容 |
| MonitorQueueMessageCountTask | `javax.mail.MessagingException` **保持** | Jakarta Mail 2.x 保留 javax.mail 包名 |
| XxlJobProperties | `javax.annotation.PostConstruct` → `jakarta.annotation.PostConstruct` | JDK 17 无 javax.annotation |
| MailUtil | `javax.annotation.Resource` → `jakarta.annotation.Resource` | 同上 |
| 测试 | JUnit 4 → JUnit 5（XxlJobConfigTest / XxlJobPropertiesTest） | Boot 3 starter-test 只有 JUnit 5 |

## 4. 线程池方案收口（承接笔记 04）

| 组件 | 决策 | 理由 |
| --- | --- | --- |
| Hippo4j（smsgateway 动态线程池） | **暂用标准 ThreadPoolExecutor 替换** | Central 无 2.x/boot3 版本（实测） |
| Hippo4j（monitor） | 无影响（monitor 不使用） | — |
| xxl-job 线程池 | 使用执行器默认配置，不自定义 | 两个巡检任务频率低 |

回归预案：hippo4j 2.x 发布后仅改 smsgateway 的 `ThreadPoolConfig` 一个文件（Bean 名不变）。

## 5. 明确「不动」的清单（业务缺陷，后处理）

| 问题 | 来源（分析报告） |
| --- | --- |
| 监控任务每次创建 Connection/Channel 不 close | report_api_cache_monitor C5 |
| 余额任务脏数据 NPE、无节流（重复轰炸） | C6/C7 |
| SMTP 凭据硬编码在 bootstrap.yml | S1（若视为配置外置化可提前做，本轮先不动） |
| queueDeclare 读数量前置副作用、channelId 字符串切片 | C8/C9 |
| 队列告警 HTML 模板笔误、setText 未开 HTML | C10 |

## 6. 迁移后验证清单

- [ ] `mvn compile` + 单测通过
- [ ] 启动 beacon-monitor，日志出现执行器注册成功（连 xxl-job admin 2.4）
- [ ] 调度中心手动执行一次 `monitorQueueMessageCountTask` → 回执 200
- [ ] 余额巡检任务在低余额数据下能发邮件（验证 javax.mail 在 Boot 3 正常）

## 7. 学习资源

- xxl-job 官方文档：<https://www.xuxueli.com/xxl-job/>
- 学习路径：先看"架构图"，再走一遍"注册执行器 → 配置任务 → 手动触发 → 看回执"的完整链路；带着问题读 XxlJobSpringExecutor 源码（它怎么把 @XxlJob 方法注册成 JobHandler 的）。

---

*下一篇：06 逐模块重构方案（9 模块 × 改点/风险/验收）。*
