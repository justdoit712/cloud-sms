# Nacos 配置清单（cloud-sms 重构版）

> 适用项目：cloud-sms（beacon-cloud 重构）
> 文档定位：Nacos 配置中心所有 DataId 的配置项唯一登记处，用于指导重写各模块时登记配置、以及联调前创建 Nacos 配置。

## 1. 文档说明与约定

### 1.1 用途

本清单是 SMS 平台重构项目的 Nacos 配置登记与建档文档，服务于两件事：

1. **重写登记**：重写各模块时，任何新增/变更的配置键必须先在本清单登记（见文末「登记记录」表）；
2. **联调建配置**：联调前，运维/开发按本清单在 Nacos 配置中心为 8 个部署服务创建对应 DataId（`{app}-dev.yml`）。

### 1.2 配置约定

1. **本地 bootstrap.yml 只放少量基础项**：服务名（`spring.application.name`）、激活环境（`spring.profiles.active`）、Nacos 注册/配置中心地址（`spring.cloud.nacos.*`），以及少量本地默认值（如 `cache.client.auth.*` 默认开关、`cmpp.state.*` TTL）。
2. **业务配置一律放 Nacos 配置中心**，DataId 为 `{app}-dev.yml`（如 `beacon-api-dev.yml`），分组默认 `DEFAULT_GROUP`，文件类型 yml。
3. **敏感项一律 `${...}` 占位**，仓库不落明文（数据库密码、SMTP 授权码、MQ/Redis/ES 密码、内部 Token、CMPP 密码、调用密钥等）；真实值只存在于 Nacos（或由环境变量注入）。
4. **与《AI 代码编写规范》§5.7 联动**：重写各模块时新增配置必须先在本文档登记，并同步到 `docs/learning/09` 的对应模块清单；禁止编造 Nacos 配置项，未登记的配置键视为不存在。

### 1.3 占位符说明

| 写法 | 含义 |
| --- | --- |
| `待定` | 旧版该配置在 Nacos 侧、仓库不可见；联调前需从旧 Nacos 控制台导出确认真实值 |
| `${VAR}` | 敏感值占位符，仅示意变量名；真实值不落仓库，只在 Nacos/密钥管理 |
| `旧值` | 直接取自旧仓库 `bootstrap.yml` / `application.yml` |

### 1.4 覆盖范围

实际部署 8 个服务：`beacon-api`、`beacon-strategy`、`beacon-smsgateway`、`beacon-search`、`beacon-push`、`beacon-cache`、`beacon-monitor`、`beacon-webmaster`。`beacon-common` 为公共库，不部署、无 Nacos 配置。

---

## 2. 通用配置（8 个服务均适用）

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| server.port | int | 待定 | 服务端口；旧版由 Nacos 下发、仓库未写死，联调前从旧 Nacos 导出确认 | 否 |
| spring.application.name | string | beacon-api / beacon-strategy / … | 服务名，决定注册名与 DataId 前缀；本地 bootstrap.yml | 否 |
| spring.profiles.active | string | dev | 激活环境；本地 bootstrap.yml | 否 |
| spring.cloud.nacos.discovery.server-addr | string | 192.168.88.128:8848（旧值） | 注册中心地址；本地 bootstrap.yml 默认值，生产可用环境变量覆盖 | 否 |
| spring.cloud.nacos.config.server-addr | string | 192.168.88.128:8848（旧值） | 配置中心地址；同上 | 否 |
| spring.cloud.nacos.config.file-extension | string | yml | 配置 DataId 文件扩展名 | 否 |
| spring.cloud.nacos.config.namespace | string | 空（默认公共命名空间） | 生产建议按环境隔离命名空间；dev 可留空 | 否 |
| DataId | string | {app}-dev.yml（如 beacon-api-dev.yml） | 各服务 Nacos DataId；分组默认 DEFAULT_GROUP，无需显式配置 | 否 |
| spring.rabbitmq.host | string | 待定 | MQ 地址；旧版在 Nacos 里，仓库不可见 | 否 |
| spring.rabbitmq.port | int | 待定 | MQ 端口 | 否 |
| spring.rabbitmq.virtual-host | string | 待定 | 虚拟主机 | 否 |
| spring.rabbitmq.username | string | 待定 | MQ 账号 | 否（建议与密码一同外置） |
| spring.rabbitmq.password | string | ${RABBITMQ_PASSWORD} | MQ 密码；旧版在 Nacos 里 | 是 |
| spring.data.redis.host | string | 待定 | Redis 地址；旧版键名为 spring.redis.*，Boot 3 重写后为 spring.data.redis.* | 否 |
| spring.data.redis.port | int | 待定 | Redis 端口 | 否 |
| spring.data.redis.password | string | ${REDIS_PASSWORD} | Redis 密码；无密码留空 | 是 |
| spring.data.redis.database | int | 待定 | Redis 库号 | 否 |

**适用范围说明**：

- **RabbitMQ**：`beacon-api`（生产预发送消息）、`beacon-strategy`、`beacon-smsgateway`、`beacon-search`、`beacon-push`（消费队列）、`beacon-monitor`（队列积压巡检）需要连接。
- **Redis 直连**：仅 `beacon-cache`（缓存门面底层）与 `beacon-webmaster`（重写后存 Sa-Token 会话与验证码）直连 Redis；其余服务一律经 beacon-cache 接口访问缓存，不直连。

---

## 3. beacon-api

**DataId**：`beacon-api-dev.yml`

通用配置（第 2 节）适用；本节列服务特有项。

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| filters | string（逗号分隔） | apikey,ip,sign,template | 校验过滤器链及执行顺序（apikey → ip → sign → template）；旧版在 Nacos 里 | 否 |
| internal.sms.token | string | ${INTERNAL_SMS_TOKEN} | 内部短信接口调用令牌，校验通过才受理；旧版在 Nacos 里 | 是 |
| snowflake.machineId | int | 待定 | 雪花 ID 机器号，多实例部署时须全局唯一；旧版在 Nacos 里 | 否 |
| snowflake.serviceId | int | 待定 | 雪花 ID 服务号；旧版在 Nacos 里 | 否 |
| cache.client.auth.enabled | boolean | true（旧值） | 调用 beacon-cache 是否启用签名鉴权 | 否 |
| cache.client.auth.caller | string | beacon-api（旧值） | 调用方身份，须与 beacon-cache 的 caller 清单一致 | 否 |
| cache.client.auth.secret | string | ${CACHE_SECRET_BEACON_API} | 调用签名密钥；旧仓库明文 cache_api_secret 须外置 | 是 |

---

## 4. beacon-strategy

**DataId**：`beacon-strategy-dev.yml`

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| internal.balance.token | string | ""（旧值，空 = 不校验） | 内部余额扣减接口令牌；生产必须配真实值，禁止空令牌 | 是 |
| cache.client.auth.enabled | boolean | true（旧值） | 调用 beacon-cache 是否启用签名鉴权 | 否 |
| cache.client.auth.caller | string | beacon-strategy（旧值） | 调用方身份，须与 beacon-cache 的 caller 清单一致 | 否 |
| cache.client.auth.secret | string | ${CACHE_SECRET_BEACON_STRATEGY} | 调用签名密钥；旧仓库明文 cache_strategy_secret 须外置 | 是 |
| snowflake.machineId | int | 待定 | 雪花 ID 机器号，多实例部署时须全局唯一；旧版在 Nacos 里 | 否 |
| snowflake.serviceId | int | 待定 | 雪花 ID 服务号；旧版在 Nacos 里 | 否 |
| sms.fee.single | int | 50 | 外部计费单条费用，单位厘（50 厘 = 0.05 元）；重写版将旧代码硬编码常量 SINGLE_FEE 改为配置，键名建议 sms.fee.single | 否 |

---

## 5. beacon-smsgateway

**DataId**：`beacon-smsgateway-dev.yml`

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| gateway.sendtopic | string | 待定 | 网关监听的发送队列名（策略引擎投递预发送消息的队列）；旧版在 Nacos 里 | 否 |
| cmpp.host | string | 待定 | CMPP 网关服务器地址（运营商下发）；旧版在 Nacos 里 | 否 |
| cmpp.port | int | 待定 | CMPP 网关端口；旧版在 Nacos 里 | 否 |
| cmpp.service-id | string | 待定 | SP 企业代码/接入号（运营商分配）；旧版在 Nacos 里 | 否 |
| cmpp.pwd | string | ${CMPP_PWD} | CMPP 接入密钥（运营商下发的 shared secret）；旧版在 Nacos 里 | 是 |
| cmpp.pool.submit-core | int | 待定 | 提交线程池核心线程数；旧版在 Nacos 里 | 否 |
| cmpp.pool.submit-max | int | 待定 | 提交线程池最大线程数 | 否 |
| cmpp.pool.submit-queue | int | 待定 | 提交线程池队列容量 | 否 |
| cmpp.pool.deliver-core | int | 待定 | 回执处理线程池核心线程数 | 否 |
| cmpp.pool.deliver-max | int | 待定 | 回执处理线程池最大线程数 | 否 |
| cmpp.pool.deliver-queue | int | 待定 | 回执处理线程池队列容量 | 否 |
| cmpp.state.submit-ttl-seconds | int | 600（旧值） | 已提交短信状态的过期时间（秒） | 否 |
| cmpp.state.deliver-ttl-seconds | int | 86400（旧值） | 回执状态记录的过期时间（秒，1 天） | 否 |
| cache.client.auth.enabled | boolean | false（旧值） | 旧版网关调用 beacon-cache 关闭了签名鉴权；重写后建议改为 true 与 cache 服务对齐（待评审确认） | 否 |
| cache.client.auth.caller | string | beacon-smsgateway（旧值） | 调用方身份，须与 beacon-cache 的 caller 清单一致 | 否 |
| cache.client.auth.secret | string | ${CACHE_SECRET_BEACON_SMSGATEWAY} | 调用签名密钥；旧仓库明文 cache_smsgateway_secret 须外置 | 是 |

---

## 6. beacon-search

**DataId**：`beacon-search-dev.yml`

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| elasticsearch.hostAndPorts | string | 待定（如 192.168.88.128:9200） | ES 地址与端口，多节点逗号分隔；旧版在 Nacos 里 | 否 |
| elasticsearch.username | string | 待定 | ES 账号；无认证可留空 | 否（建议与密码一同外置） |
| elasticsearch.password | string | ${ES_PASSWORD} | ES 密码；旧版在 Nacos 里 | 是 |
| sms.index.prefix | string | sms_submit_log_ | 短信发送日志索引前缀 | 否 |
| sms.report.retry-ttl-seconds | int | 10 | 状态更新失败时的重试 TTL（秒） | 否 |

---

## 7. beacon-push

**DataId**：`beacon-push-dev.yml`

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| push.retry.delays | string（逗号分隔，毫秒） | 0,15000,30000,60000,300000 | 回调失败后各次重试的延迟序列（0/15s/30s/60s/5min） | 否 |
| push.retry.max | int | 5 | 最大重试次数 | 否 |
| push.http.connect-timeout | int | 待定 | 客户回调 HTTP 连接超时（毫秒）；旧版在 Nacos 里 | 否 |
| push.http.read-timeout | int | 待定 | 客户回调 HTTP 读取超时（毫秒）；旧版在 Nacos 里 | 否 |

---

## 8. beacon-monitor

**DataId**：`beacon-monitor-dev.yml`

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| xxl.job.admin.addresses | string | 待定（如 http://127.0.0.1:8080/xxl-job-admin） | XXL-Job 调度中心地址；旧版在 Nacos 里 | 否 |
| xxl.job.appname | string | beacon-monitor | 执行器 AppName，须与调度中心登记一致 | 否 |
| xxl.job.port | int | 待定 | 执行器通信端口；旧版在 Nacos 里 | 否 |
| xxl.job.accessToken | string | ${XXL_JOB_ACCESS_TOKEN} | 调度中心访问令牌；旧版在 Nacos 里 | 是 |
| xxl.job.logPath | string | 待定（如 logs/xxl-job/jobhandler） | 执行器日志目录；旧版在 Nacos 里 | 否 |
| xxl.job.logRetentionDays | int | 待定（如 30） | 日志保留天数；旧版在 Nacos 里 | 否 |
| spring.mail.host | string | smtp.qq.com（旧值） | SMTP 服务器 | 否 |
| spring.mail.port | int | 465（旧值） | SMTP 端口（SSL） | 否 |
| spring.mail.username | string | 2931163626@qq.com（旧值，已泄露） | 发件邮箱账号 | 是 |
| spring.mail.password | string | ${SMTP_PASSWORD} | SMTP 授权码；旧仓库明文 optzmoheptapdcfb 已泄露，建议直接重置后外置 | 是 |
| spring.mail.tos | string | lc204573@gmail.com（旧值） | 告警邮件收件人 | 否 |
| spring.mail.protocol | string | smtp（旧值） | 邮件协议 | 否 |
| spring.mail.default-encoding | string | UTF-8（旧值） | 邮件默认编码 | 否 |
| spring.mail.properties.mail.smtp.auth | boolean | true（旧值） | 开启 SMTP 认证 | 否 |
| spring.mail.properties.mail.smtp.starttls.enable | boolean | true（旧值） | 开启 STARTTLS | 否 |
| spring.mail.properties.mail.smtp.starttls.required | boolean | true（旧值） | 强制 STARTTLS | 否 |
| spring.mail.properties.mail.smtp.ssl.enable | boolean | true（旧值） | 开启 SSL | 否 |
| spring.mail.properties.mail.smtp.socketFactory.port | int | 465（旧值） | SSL 端口 | 否 |
| spring.mail.properties.mail.smtp.socketFactory.class | string | javax.net.ssl.SSLSocketFactory（旧值） | Boot 3 重写后该行通常可省略（JavaMail 自动处理），建议仅保留 host/port/username/password/tos | 否 |
| queue.alert.threshold | int | 10000 | 队列积压告警阈值（条） | 否 |
| balance.alert.threshold | long | 500000 | 余额告警阈值，单位厘（500000 厘 = 5000 元） | 否 |
| cache.client.auth.enabled | boolean | true（旧值） | 调用 beacon-cache 是否启用签名鉴权 | 否 |
| cache.client.auth.caller | string | beacon-monitor（旧值） | 调用方身份，须与 beacon-cache 的 caller 清单一致 | 否 |
| cache.client.auth.secret | string | ${CACHE_SECRET_BEACON_MONITOR} | 调用签名密钥；旧仓库明文 cache_monitor_secret 须外置 | 是 |

---

## 9. beacon-webmaster

**DataId**：`beacon-webmaster-dev.yml`

> 说明：旧版 webmaster 把大部分配置放在本地 application.yml；重写后按统一约定迁移到 Nacos（DataId `beacon-webmaster-dev.yml`），本地仅保留 bootstrap 基础项。

### 9.1 数据源与 MyBatis

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| spring.datasource.driver-class-name | string | com.mysql.cj.jdbc.Driver | 重写版统一 MySQL 8 驱动；旧值为 org.gjt.mm.mysql.Driver（MySQL 5 驱动） | 否 |
| spring.datasource.url | string | jdbc:mysql://192.168.88.128:3306/duanxin_pingtai?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true（旧值） | 数据库连接串；dev 可沿用旧值，生产外置 | 否 |
| spring.datasource.username | string | root（旧值，明文） | 数据库账号 | 是 |
| spring.datasource.password | string | ${DB_PASSWORD} | 数据库密码；旧仓库明文 123 须外置 | 是 |
| spring.datasource.type | string | com.alibaba.druid.pool.DruidDataSource（旧值） | 连接池类型（Druid） | 否 |
| spring.datasource.druid.* | 多种 | 待定 | Druid 连接池参数（initial-size / max-active 等）；旧版不在仓库可见，联调前按需补充登记 | 否 |
| mybatis.mapper-locations | string | classpath:mapper/*.xml（旧值） | Mapper XML 位置 | 否 |
| mybatis.configuration.map-underscore-to-camel-case | boolean | true（旧值） | 下划线转驼峰 | 否 |

### 9.2 Sa-Token（重写版替换 Shiro）

旧版 `shiro.loginUrl` / `shiro.unauthorizedUrl` 配置随 Shiro 移除而作废，由下列 Sa-Token 配置取代。

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| sa-token.token-name | string | satoken | Token 名称（Header/参数中读取的键名） | 否 |
| sa-token.timeout | int | 86400 | Token 有效期（秒，1 天） | 否 |
| sa-token.is-read-cookie | boolean | false | 不从 Cookie 读取 Token | 否 |
| sa-token.is-read-header | boolean | true | 从 Header 读取 Token（与前端改造配合） | 否 |
| sa-token.is-concurrent | boolean | true | 允许同一账号多处并发登录 | 否 |
| sa-token.token-style | uuid | uuid | Token 风格 | 否 |

> Sa-Token 会话与登录态存入 Redis（对应第 2 节的 `spring.data.redis.*`），实现分布式会话与验证码共享。

### 9.3 验证码、同步、缓存与内部接口

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| captcha.expire-seconds | int | 300 | 图形验证码有效期（秒） | 否 |
| system.test-kaptcha | string | "1111"（旧值） | 本地开发万能验证码；仅 dev 生效，生产必须为空并确认校验逻辑关闭 | 否 |
| sync.enabled | boolean | false（旧值） | 缓存同步总开关；关闭后 runtime/manual/boot 子开关均不生效 | 否 |
| sync.redis.namespace | string | beacon:dev:beacon-cloud:cz:（旧值） | 同步统一命名空间完整前缀，须与 cache.namespace.fullPrefix 保持一致 | 否 |
| sync.runtime.enabled | boolean | true（旧值） | 运行时同步开关（业务写路径触发） | 否 |
| sync.runtime.domains | list | ["client_business"]（旧值） | 运行时同步域清单 | 否 |
| sync.manual.enabled | boolean | true（旧值） | 手工重建开关（管理入口触发） | 否 |
| sync.boot.enabled | boolean | true（旧值） | 启动校准开关（服务启动触发） | 否 |
| sync.boot.domains | list | []（旧值） | 启动校准域清单，留空由后续逻辑决定默认域策略 | 否 |
| cache.namespace.fullPrefix | string | beacon:dev:beacon-cloud:cz:（旧值） | 缓存命名空间完整前缀，须与 beacon-cache 的 cache.namespace.fullPrefix 一致 | 否 |
| cache.client.auth.enabled | boolean | true（旧值） | 调用 beacon-cache 是否启用签名鉴权 | 否 |
| cache.client.auth.caller | string | beacon-webmaster（旧值） | 调用方身份，须与 beacon-cache 的 caller 清单一致 | 否 |
| cache.client.auth.secret | string | ${CACHE_SECRET_BEACON_WEBMASTER} | 调用签名密钥；旧仓库明文 cache_webmaster_secret 须外置 | 是 |
| cache.client.feign.connect-timeout-ms | int | 10000（旧值） | 调用 beacon-cache 的 Feign 连接超时（毫秒） | 否 |
| cache.client.feign.read-timeout-ms | int | 120000（旧值） | 调用 beacon-cache 的 Feign 读取超时（毫秒） | 否 |
| internal.balance.token | string | ""（旧值，空 = 不校验） | 内部余额扣减接口令牌；生产必须配真实值，禁止空令牌 | 是 |
| snowflake.machineId | int | 待定 | 雪花 ID 机器号，多实例部署时须全局唯一 | 否 |
| snowflake.serviceId | int | 待定 | 雪花 ID 服务号 | 否 |

---

## 10. beacon-cache

**DataId**：`beacon-cache-dev.yml`

### 10.1 命名空间与安全策略

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| cache.namespace.enabled | boolean | true（旧值） | 是否启用命名空间前缀 | 否 |
| cache.namespace.fullPrefix | string | beacon:dev:beacon-cloud:cz:（旧值） | 统一命名空间完整前缀，所有 key 均带此前缀；其他服务（webmaster sync）须保持一致 | 否 |
| cache.security.enabled | boolean | true（旧值） | 是否启用接口签名鉴权 | 否 |
| cache.security.max-time-skew-seconds | int | 300（旧值） | 签名时间戳最大允许偏差（秒），防重放 | 否 |
| cache.security.test-api-enabled | boolean | false（旧值） | 是否开放无鉴权测试接口；生产必须为 false | 否 |
| cache.key-pattern-allow-list | list | client_business:* / client_channel:* / client_balance:* / channel:*（旧值） | 允许通过接口操作的 key 模式白名单 | 否 |

### 10.2 调用方（caller）密钥与权限

旧版结构为 `cache.security.caller-secrets` + `cache.security.caller-permissions` 两棵树；**重写版建议收敛为单棵树**：`cache.security.callers.<caller>.secret` 与 `cache.security.callers.<caller>.permissions`，一个 caller 一处登记、便于维护与审计。权限枚举沿用旧值：`READ`（读）、`WRITE`（写）、`KEYS`（允许 key 模式列出，仅监控与后台需要）。

建议的 Nacos 配置结构：

```yaml
cache:
  security:
    callers:
      beacon-api:
        secret: ${CACHE_SECRET_BEACON_API}
        permissions: [READ]
      beacon-strategy:
        secret: ${CACHE_SECRET_BEACON_STRATEGY}
        permissions: [READ, WRITE]
      beacon-monitor:
        secret: ${CACHE_SECRET_BEACON_MONITOR}
        permissions: [READ, KEYS]
      beacon-webmaster:
        secret: ${CACHE_SECRET_BEACON_WEBMASTER}
        permissions: [WRITE, KEYS]
      beacon-smsgateway:
        secret: ${CACHE_SECRET_BEACON_SMSGATEWAY}
        permissions: [READ, WRITE]
```

对应登记表（权限为旧值迁移；secret 全部敏感）：

| 配置键 | 类型 | 示例或默认值 | 说明 | 敏感否 |
| --- | --- | --- | --- | --- |
| cache.security.callers.beacon-api.secret | string | ${CACHE_SECRET_BEACON_API} | 旧值 cache_api_secret，须外置 | 是 |
| cache.security.callers.beacon-api.permissions | list | [READ]（旧值迁移） | 只读 | 否 |
| cache.security.callers.beacon-strategy.secret | string | ${CACHE_SECRET_BEACON_STRATEGY} | 旧值 cache_strategy_secret，须外置 | 是 |
| cache.security.callers.beacon-strategy.permissions | list | [READ, WRITE]（旧值迁移） | 读 + 写（策略引擎需回写限流/黑名单等状态） | 否 |
| cache.security.callers.beacon-monitor.secret | string | ${CACHE_SECRET_BEACON_MONITOR} | 旧值 cache_monitor_secret，须外置 | 是 |
| cache.security.callers.beacon-monitor.permissions | list | [READ, KEYS]（旧值迁移） | 读 + key 模式查询（巡检用） | 否 |
| cache.security.callers.beacon-webmaster.secret | string | ${CACHE_SECRET_BEACON_WEBMASTER} | 旧值 cache_webmaster_secret，须外置 | 是 |
| cache.security.callers.beacon-webmaster.permissions | list | [WRITE, KEYS]（旧值迁移） | 写 + key 模式查询（缓存同步/重建/管理） | 否 |
| cache.security.callers.beacon-smsgateway.secret | string | ${CACHE_SECRET_BEACON_SMSGATEWAY} | 旧值 cache_smsgateway_secret，须外置 | 是 |
| cache.security.callers.beacon-smsgateway.permissions | list | [READ, WRITE]（旧值迁移） | 读 + 写（网关存取提交/回执状态） | 否 |

> 注意：与各 caller 服务自身的 `cache.client.auth.caller` / `cache.client.auth.secret` 一一对应，两端 secret 必须一致；`cache.client.auth.enabled` 与 `cache.security.enabled` 需成对开关。

### 10.3 底层 Redis 连接

beacon-cache 作为 Redis 门面，底层直连 Redis，使用第 2 节通用配置的 `spring.data.redis.*`（旧版键名 `spring.redis.*`，Boot 3 重写后改 `spring.data.redis.*`）。

---

## 附录 A：旧仓库敏感明文盘点（必须外置）

| 位置（旧文件） | 明文配置项 | 明文值 | 处置 |
| --- | --- | --- | --- |
| beacon-monitor bootstrap.yml | spring.mail.username | 2931163626@qq.com | 外置；账号已随仓库公开 |
| beacon-monitor bootstrap.yml | spring.mail.password | optzmoheptapdcfb | **SMTP 授权码已泄露，建议直接重置**，新值只放 Nacos `${SMTP_PASSWORD}` |
| beacon-webmaster application.yml | spring.datasource.username / password | root / 123 | 外置 `${DB_USERNAME}` / `${DB_PASSWORD}` |
| beacon-api bootstrap.yml | cache.client.auth.secret | cache_api_secret | 外置 |
| beacon-strategy bootstrap.yml | cache.client.auth.secret | cache_strategy_secret | 外置 |
| beacon-monitor bootstrap.yml | cache.client.auth.secret | cache_monitor_secret | 外置 |
| beacon-webmaster application.yml | cache.client.auth.secret | cache_webmaster_secret | 外置 |
| beacon-smsgateway bootstrap.yml | cache.client.auth.secret | cache_smsgateway_secret | 外置 |
| beacon-cache bootstrap.yml | cache.security.caller-secrets（5 个 caller） | cache_api_secret / cache_strategy_secret / cache_monitor_secret / cache_webmaster_secret / cache_smsgateway_secret | 外置并重建为 cache.security.callers.*.secret |
| beacon-api / beacon-strategy / beacon-webmaster | internal.sms.token / internal.balance.token | 旧值为空 "" | 联调前在 Nacos 配真实值；生产禁止空令牌 |

---

## 附录 B：登记记录

> 重写各模块时，新增/变更配置必须在此登记（并与 `docs/learning/09` 对应模块清单同步）。未登记即视为未定义。

| 模块 | 新增配置键 | 日期 |
| --- | --- | --- |
| （示例）beacon-strategy | sms.fee.single | 待填 |
|  |  |  |
|  |  |  |
|  |  |  |
