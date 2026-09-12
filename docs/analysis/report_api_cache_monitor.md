# beacon-cloud 三模块代码分析报告

> 分析对象：`beacon-api`（24 个 Java 文件）、`beacon-cache`（17 个）、`beacon-monitor`（11 个）
> 分析方式：逐文件 read 实际源码，行号均为源码实际行号；路径相对于 `beacon-cloud/` 仓库根目录。

---

## 1. 模块总览

| 模块 | 角色 | 关键依赖 |
|---|---|---|
| beacon-api | 短信发送受理入口（外部/内部 HTTP API），校验后投递 MQ | Nacos、OpenFeign、RabbitMQ、beacon-common |
| beacon-cache | 统一缓存服务（HTTP 服务端，Redis 封装 + 签名鉴权 + 命名空间） | Nacos、Spring Data Redis、beacon-common |
| beacon-monitor | xxl-job 定时监控（队列积压 / 客户余额）+ 邮件告警 | xxl-job-core 2.3.1、RabbitMQ、OpenFeign、JavaMail |

三个模块均无 `application.yml`，运行时配置全部放在 Nacos 配置中心（`bootstrap.yml` 只保留 Nacos 地址与少量敏感配置），Nacos 地址统一为 `192.168.88.128:8848`。

---

## 2. beacon-api 模块

### 2.1 启动类 `ApiStarterApp`

- `ApiStarterApp.java:15-21`：`@SpringBootApplication` + `@EnableDiscoveryClient` + `@EnableFeignClients`，并用 `@ComponentScan(basePackages = {"com.cz.api", "com.cz.common"})` 把公共模块的 Bean（如 `SnowFlakeUtil`）纳入扫描。
- 启动后打印 appName/port/profiles/version（`ApiStarterApp.java:26-33`）。

### 2.2 SmsController 全部接口

`SmsController.java` 共 2 个 POST 接口，均在 `/sms` 前缀下：

**（1）外部单发 `POST /sms/single_send`（`SmsController.java:82-97`）**
- 入参 `@Validated SingleSendForm`，先检查 `BindingResult`，有错返回 `PARAMETER_ERROR(-10)` + 首个字段错误信息（`firstError()`，`SmsController.java:214-219`）。
- 提取真实 IP（`getRealIp`）→ 组装 `StandardSubmit`（clientId 为 null，由过滤器链补齐）→ 执行完整校验链 `checkFilterContext.check(submit)` → `enqueue`。

**（2）内部单发 `POST /sms/internal/single_send`（`SmsController.java:110-131`）**
- 入参 `@Validated InternalSingleSendForm`，请求头 `X-Internal-Token`（`required = false`）。
- Token 校验（`SmsController.java:118-120`）：
  ```java
  if (StringUtils.hasText(internalSmsToken) && !internalSmsToken.equals(requestToken)) {
      return Result.error(-403, "internal token invalid");
  }
  ```
- 通过 `resolveClientId(form.getApikey())` 从缓存查客户 ID（`SmsController.java:191-206`，`client_business:` hash 的 `id` 字段，解析失败返回 null → 报 `ERROR_APIKEY`）。
- **不执行过滤器链**，直接 `enqueue` —— 内部通道只校验 token + apikey，跳过 mobile/sign/template/fee/IP 全部业务校验。

**入队 `enqueue`（`SmsController.java:139-150`）**
- 雪花 ID 作为 `sequenceId`，`sendTime = LocalDateTime.now()`；
- `rabbitTemplate.convertAndSend(RabbitMQConstants.SMS_PRE_SEND, submit, new CorrelationData(...))` —— 使用**默认交换机**，routing key 即队列名 `sms_pre_send_topic`（`RabbitMQConstants.java:11`），消息被 `Jackson2JsonMessageConverter` 序列化；
- 返回 `SmsSendResultVO`：`code=0/msg=接收成功/uid/sid`。

### 2.3 参数表单

**`SingleSendForm.java`**（外部）：`apikey`、`mobile`、`text` 均 `@NotBlank`；`state` `@NotNull + @Range(0,2)`（0验证码/1通知/2营销）；`uid` 无任何约束（`SingleSendForm.java:20-39`）。`text` 无长度上限约束。

**`InternalSingleSendForm.java`**：字段同上，多一个可选 `realIp`（`InternalSingleSendForm.java:34-37`），允许内部调用方自报来源 IP（`SmsController.java:127` 优先使用该值）。

### 2.4 校验过滤器链（策略模式）

**接口与上下文**
- `CheckFilter.java:10-17`：策略父接口，`void check(StandardSubmit submit)`。
- `CheckFilterContext.java:20-72`：
  - `@Component @RefreshScope`；通过 `@Autowired Map<String, CheckFilter>` 收集全部实现（bean 名即策略名）。
  - 链配置 `@Value("${filters:apikey,ip,sign,template}")`（`CheckFilterContext.java:30`），逗号分隔、可 trim、忽略空项，从 Nacos 可动态调整顺序。
  - `@PostConstruct validateFilters()`（`CheckFilterContext.java:33-40`）启动时校验链名合法；运行时 `check()`（`CheckFilterContext.java:45-53`）按配置顺序依次执行，未知过滤器抛 `IllegalStateException`。

**各过滤器实现（`filter/impl/`）**
| Bean 名 | 类 | 校验内容 |
|---|---|---|
| `apikey` | `ApiKeyCheckFilter.java:19-42` | 从缓存 `client_business:{apikey}` hash 查客户，不存在抛 `ERROR_APIKEY(-1)`；将 `id` 字段解析为 `clientId` 写入 submit |
| `ip` | `IPCheckFilter.java:21-46` | 读 `client_business` hash 的 `ipAddress` 字段（逗号分隔白名单），**白名单为空或包含 realIp 则放行**，否则抛 `IP_NOT_WHITE(-2)`；白名单快照写入 `submit.setIp(...)` |
| `sign` | `SignCheckFilter.java:22-78` | 短信必须以 `【` 开头且含 `】`；截取签名，从 `client_sign:{clientId}` Set 中匹配，命中则回填 `sign/signId`，否则 `ERROR_SIGN(-3)` |
| `template` | `TemplateCheckFilter.java:21-78` | 去除签名前缀后与 `client_template:{signId}` 模板精确匹配；支持"仅一个 `#xx#` 占位符"的前后缀匹配；失败抛 `ERROR_TEMPLATE(-4)` |
| `mobile` | `MobileCheckFilter.java:16-32` | `PhoneFormatCheckUtil.isChinaPhone` 正则校验（`PhoneFormatCheckUtil.java:11`，13x/14x/15x/16x/17x/18x/19x），失败抛 `ERROR_MOBILE(-5)` |
| `fee` | `FeeCheckFilter.java:22-80` | 按 70 字/条、超出按 67 字/条计算条数，单条费用 `ApiConstant.SINGLE_FEE = 50`（厘）；读 `client_balance:{clientId}` 的 `balance` 字段，不足抛 `BALANCE_NOT_ENOUGH(-6)`。**只校验不扣费** |

**关键事实**：`mobile` 与 `fee` 两个过滤器有实现，但**不在默认链** `apikey,ip,sign,template` 中（`CheckFilterContext.java:30`）。默认情况下手机号格式、余额均不校验，除非在 Nacos 的 `filters` 配置中显式追加。
链执行顺序有隐式依赖：`sign` 依赖 `apikey` 先设置 `clientId`，`template` 依赖 `sign` 先设置 `sign/signId`，`fee` 依赖 `apikey`。

### 2.5 RabbitMQ 配置

- `RabbitMQConfig.java:27-30`：只声明持久队列 `sms_pre_send_topic`（`QueueBuilder.durable`），无交换机。
- `RabbitMQConfig.java:36-48`：`Jackson2JsonMessageConverter`，注册 `JavaTimeModule` 并禁用时间戳序列化，保证 `LocalDateTime` 正常传输。
- `RabbitTemplateConfig.java:24-56`：自定义 `RabbitTemplate`，设置 confirm 回调（未到交换机打 error 日志）与 return 回调（未路由到队列打 error 日志）。**注意**：代码只注册回调，未开启 `publisher-confirm-type` / `publisher-returns` / `mandatory`（这些依赖 Nacos 中的 `spring.rabbitmq.*` 配置），否则回调永远不会触发。

### 2.6 对 beacon-cache 的 Feign 调用

- `BeaconCacheClient.java:12-25`：`@FeignClient(value = "beacon-cache", configuration = CacheFeignAuthConfig.class)`，4 个 `/v2/cache/**` 类型化读接口：`hGetAllTyped`、`hgetStringTyped`、`hgetIntegerTyped`、`smemberTyped`，返回 `ResultVO<T>`。
- `CacheFacade.java:12-46`（api 模块）：对 Feign 结果 `unwrap`，`code != 0` 抛 `IllegalStateException`（会被 `ApiExceptionHandler.java:49-54` 当成 `IllegalArgumentException` 处理，返回 `PARAMETER_ERROR(-10)`，语义有偏差）。
- `CacheFeignAuthConfig.java:21-39`：Feign 请求拦截器自动加 `X-Cache-Caller` / `X-Cache-Timestamp` / `X-Cache-Sign` 三头，签名算法来自 beacon-common（见 3.3）。`enabled/caller/secret` 来自 `${cache.client.auth.*}`。

### 2.7 internal.sms.token 鉴权

`internal.sms.token`（`SmsController.java:54`）从 Nacos 读取；**默认值为空串**，为空时内部接口完全无鉴权（`SmsController.java:118` 的 `hasText` 短路）。比较用 `String.equals`（非常量时间）。`ExceptionEnums.INTERNAL_TOKEN_INVALID(-106)` 存在但此处未使用，硬编码了 `-403`（`SmsController.java:119`）。

### 2.8 IP 提取 `getRealIp`（`SmsController.java:227-249`）

按 `${headers}` 配置的请求头顺序取真实 IP（默认取 `req.getRemoteAddr()`）；`x-forwarded-for` 取第一个逗号前值。**该头值由客户端可控**，若服务可被直连（不经网关清洗），IP 白名单校验可被伪造头绕过。

### 2.9 统一异常处理 `ApiExceptionHandler.java`

`@RestControllerAdvice`：`BizException` → 返回对应 code/msg；参数校验异常（`MethodArgumentNotValidException`/`BindException`）→ `-10`；`HttpMessageNotReadableException` → "请求体格式错误"；兜底 `Exception` → `UNKNOWN_ERROR(-100)`。所有业务错误 **HTTP 状态码仍为 200**（返回体 `SmsSendResultVO.code` 区分）。

---

## 3. beacon-cache 模块

### 3.1 启动类与依赖

`CacheStarterApp.java`：`@SpringBootApplication + @EnableDiscoveryClient`；**未启用 Feign**（它是被调用方）。pom 无 OpenFeign 依赖（`beacon-cache/pom.xml` 中"为了访问缓存模块，需要配置OpenFeign"注释与实际不符）。

### 3.2 HTTP 接口设计（统一缓存服务）

**`CacheController.java`（通用缓存，`/cache/**` 与 `/v2/cache/**`）**
- 写接口（部分无返回值或裸类型）：`POST /cache/hmset/{key}`、`POST /cache/set/{key}?value=`、`POST /cache/sadd/{key}`、`POST /cache/saddstr/{key}`、`POST /cache/pipeline/string`、`POST /cache/setnx/{key}?value=&ttlSeconds=300`（分布式锁语义）、`DELETE /cache/pop/{key}`（Lua 原子 get+del）、`DELETE /cache/delete-if-match/{key}?value=`（CAS 删除，锁安全释放）、`POST /cache/sinterstr/{key}/{sinterKey}`、`POST /cache/zadd/{key}/{score}/{member}`、`GET /cache/zrangebyscorecount/{key}/{start}/{end}`、`DELETE /cache/zremove/{key}/{member}`、`POST /cache/hincrby/{key}/{field}/{delta}`、`DELETE /cache/delete/{key}`、`POST /cache/delete/batch`、`GET /cache/keys?pattern=&count=1000`。
- 类型化读接口 `/v2/cache/**`（`CacheController.java:74-138`）：`hGetAllString`、`hGetString`、`hGetInteger`、`hGetLong`、`sMembersString`、`sMembersMap`、`getString`，统一返回 `ResultVO<T>`（`ResultVO.java:20` 中 `data` 为 `@JsonInclude(NON_EMPTY)`，未命中时 data 字段被省略）。

**`CacheStringController.java`（`/cache/strings/**`）**：字符串资源的 REST 风格独立控制器，`GET/PUT/DELETE /cache/strings/{key}`，PUT 带 `CacheStringWriteRequest{value, ttlSeconds}`（`beacon-common`），DELETE 返回删除前的值（pop 语义）。

**`TestController.java`**：`@ConditionalOnProperty(prefix="cache.security", name="test-api-enabled", havingValue="true")`，`/test/set/{key}`、`/test/get/{key}`、`/test/pipeline` 直接操作 `LocalRedisClient`（不走命名空间），当前配置为 false 不注册。

### 3.3 CacheAuthInterceptor 鉴权（X-Cache-Caller / X-Cache-Timestamp / X-Cache-Sign）

**注册**：`WebMvcSecurityConfig.java:19-20`，拦截 `/cache/**`、`/v2/cache/**`、`/test/**`。

**校验流程**（`CacheAuthInterceptor.java:56-119`）：
1. `cache.security.enabled=false` 时**直接放行**（`CacheAuthInterceptor.java:57-59`）；
2. 三个头缺一不可（`CacheAuthInterceptor.java:65-68`）；
3. 时间戳必须是数字（`CacheAuthInterceptor.java:70-76`）；
4. **时间窗口**：`|now - timestamp| <= maxTimeSkewSeconds * 1000`，默认 300s（`CacheAuthInterceptor.java:78-83`；`CacheSecurityProperties.java:18`）；
5. 按 caller 查共享密钥（`CacheAuthInterceptor.java:85-90`）；
6. **验签**（`CacheAuthInterceptor.java:92-98`）：服务端用 `CacheAuthSignUtil.buildPayload(caller, timestamp, request.getMethod(), request.getRequestURI())` 重算，`safeEquals` 常量时间比较（`MessageDigest.isEqual`，`CacheAuthInterceptor.java:151-156`）；
7. **授权**（`CacheAuthInterceptor.java:100-111`）：按路径+方法解析所需权限，不满足返回 403；
8. caller 写入请求属性 `REQUEST_ATTR_CALLER`（`CacheAuthInterceptor.java:117`）。

**签名算法**（`beacon-common` 的 `CacheAuthSignUtil.java`）：
- `HmacSHA256(secret, payload)`，payload = `caller + "\n" + timestamp + "\n" + METHOD大写 + "\n" + path`（`CacheAuthSignUtil.java:27-33`）；
- `normalizePath`：去首尾空白、去 query、保证以 `/` 开头（`CacheAuthSignUtil.java:35-48`）；
- 头名常量 `X-Cache-Caller` / `X-Cache-Timestamp` / `X-Cache-Sign`（`CacheAuthHeaders.java:28/36/44`）。

**权限模型**（`CachePermission.java`：`READ/WRITE/KEYS/TEST/ADMIN`）：
- 解析规则（`CacheAuthInterceptor.java:127-142`）：`/test/**` → TEST（且需 `test-api-enabled`，否则 404）；`/cache/keys`、`/v2/cache/keys` → KEYS；**GET → READ；其余任何方法（POST/PUT/DELETE）→ WRITE**。
- 匹配规则（`CacheSecurityProperties.java:23-39`）：权限列表含 `ADMIN` 或精确同名即通过。

**权限与密钥配置**（`beacon-cache/bootstrap.yml:19-52`）：5 个 caller 各配独立 secret 与权限（`beacon-api: READ`；`beacon-strategy: READ+WRITE`；`beacon-monitor: READ+KEYS`；`beacon-webmaster: WRITE+KEYS`；`beacon-smsgateway: READ+WRITE`）。密钥**明文硬编码在仓库**。

### 3.4 Redis 操作封装

- `RedisConfig.java:26-57`：`RedisTemplate<String, Object>`，key/hashKey 用 String 序列化，value 用 `Jackson2JsonRedisSerializer`，注册 `JavaTimeModule`（yyyy-MM-dd HH:mm:ss），并 **`objectMapper.enableDefaultTyping(DefaultTyping.NON_FINAL)`**（`RedisConfig.java:51`）。
- `LocalRedisClient.java`：对 RedisTemplate 的轻量封装——hSet/set(带 TTL)/sAdd/hGetAll/hGet/sMembers/pipelined/get/getAndDelete(Lua)/deleteIfValueMatches(Lua CAS)/setIfAbsent/zAdd/zRemove/hIncrementBy。Lua 脚本保证 `getAndDelete`（`LocalRedisClient.java:168-180`）与"值匹配才删除"（`LocalRedisClient.java:189-202`）的原子性。
- `RedisScanService.java:28-61`：基于 `SCAN` 游标扫描（`count` 上限 5000），cursor 在 finally 中关闭。
- `NamespaceKeyResolver.java`：逻辑 key ↔ 物理 key 双向映射，前缀幂等（已含前缀不重复添加，`NamespaceKeyResolver.java:45-54`）；前缀规则见 `CacheNamespaceProperties.java:29`（默认 `beacon:dev:beacon-cloud:cz:`，必须以冒号结尾）。

### 3.5 应用门面 `CacheFacade`（cache 模块，`application/CacheFacade.java`）

- 所有用例统一做逻辑→物理 key 转换，并 `log.info` 记录 key 与**完整 value**（如 `CacheFacade.java:64、70、91`）。
- 类型转换：`hGetAllString/hGetString/hGetInteger/hGetLong/sMembersString/sMembersMap`（`CacheFacade.java:111-177`），数值用 `BigDecimal.intValueExact()/longValueExact()` 严格转换，类型不符抛 400（`CacheFacade.java:391-417`）。
- `keys()`（`CacheFacade.java:323-334`）：先校验逻辑 pattern 命中 `key-pattern-allow-list`（前缀匹配，允许项形如 `client_business:*` 会剥掉 `*` 后 `startsWith` 判断，`CacheFacade.java:336-354`），不在白名单内直接 403，避免任意扫描。
- `delete/deleteBatch` 返回结构化 `CacheDeleteResult`（attemptedCount/successCount/deletedCount/failedKeys/namespace，`CacheController.java:274-285`；`CacheDeleteResult.java`）；批量删除逐条执行未用 pipeline（`CacheFacade.java:300-313`）。

### 3.6 缓存键设计

**逻辑键常量**（`beacon-common/CacheKeyConstants.java`）：
- `client_business:{apikey}`（HASH：id/ipAddress/isCallback/callbackUrl 等）
- `client_sign:{clientId}`（SET，成员为 `{id,signInfo}` JSON）
- `client_template:{signId}`（SET，成员为 `{templateText}` JSON）
- `client_balance:{clientId}`（HASH，字段 `balance`/`extend1` 等）
- `client_channel:{clientId}`、`channel:{id}`、`dirty_word`、`black:{mobile}`、`transfer:{mobile}`、`limit:minutes/hours/days:*`、`phase:*`

**域契约注册表**（`beacon-common/CacheDomainRegistry.java:244-355`）：9 个主线域（client_business/client_sign/client_template/client_channel/channel/client_balance/transfer/black/dirty_word），每个域声明 Redis 类型、真源（MYSQL）、写入策略（WRITE_THROUGH / DELETE_AND_REBUILD / MYSQL_ATOMIC_UPDATE_THEN_REFRESH）、删除策略、重建策略（FULL_REBUILD）、归属服务（beacon-webmaster）与是否允许启动重建。`client_balance` 明确标注"MYSQL 原子更新后刷新缓存"（`CacheDomainRegistry.java:305-315`），印证余额是 MySQL 主口径、Redis 为镜像。

**物理键**：逻辑键 + 命名空间前缀 `beacon:dev:beacon-cloud:cz:`（`beacon-cache/bootstrap.yml:19-22`）。

---

## 4. beacon-monitor 模块

### 4.1 启动类与依赖

`MonitorStarterApp.java`：`@SpringBootApplication + @EnableDiscoveryClient + @EnableFeignClients`。依赖 xxl-job-core 2.3.1、spring-boot-starter-amqp、openfeign、mail（`beacon-monitor/pom.xml:43-63`）。

### 4.2 xxl-job 配置与任务

**`XxlJobConfig.java:24-39`**：装配 `XxlJobSpringExecutor`（adminAddresses、appname、address/ip 可选、port、accessToken、logPath、logRetentionDays）。
**`XxlJobProperties.java`**：`@ConfigurationProperties(prefix="xxl.job")` + `@PostConstruct` 启动校验（地址/应用名/日志路径非空、端口 1-65535、日志保留天数 ≥1，`XxlJobProperties.java:25-41`）。
**任务**（`@XxlJob` 注解注册，共 3 个）：
- `monitorQueueMessageCountTask`（`MonitorQueueMessageCountTask.java:55`）
- `monitorClientBalanceTask`（`MonitorClientBalanceTask.java:37`）
- `test`（`TestTask.java:15`）

**调度 cron 不在仓库中**（全仓库仅 2 处出现任务名，均在 `@XxlJob` 注解处），调度计划在 xxl-job 调度中心控制台配置；`xxl.job.*` 配置也不在 `bootstrap.yml`，需由 Nacos 的 `beacon-monitor-dev.yml` 提供（否则 `XxlJobProperties` 启动校验会失败）。

### 4.3 MonitorQueueMessageCountTask（队列积压监控）

流程（`MonitorQueueMessageCountTask.java:56-73`）：
1. 通过 `CacheClient.keys("channel:*")` 从 beacon-cache 拿到所有通道逻辑 key（monitor 有 KEYS 权限，且 `channel:*` 在 cache 白名单内）；
2. `connectionFactory.createConnection()` + `createChannel(false)`；
3. 对 `sms_pre_send_topic` 及每个 `sms_gateway_topic_{channelId}` 执行 `listenQueueAndSendEmail`；
4. 该私有方法（`MonitorQueueMessageCountTask.java:75-110`）：先 `queueDeclare(queueName, true, false, false, null)`（队列不存在则**创建**），再 `channel.messageCount()`，积压数 > 10000（硬编码 `MESSAGE_COUNT_LIMIT`，`MonitorQueueMessageCountTask.java:31`）时通过 `MailUtil.sendEmail(subject, content)` 发 HTML 告警邮件。

### 4.4 MonitorClientBalanceTask（客户余额监控）

流程（`MonitorClientBalanceTask.java:38-51`）：
1. `CacheClient.keys("client_balance:*")` 拿全部余额 key；
2. 逐个 `hGetAll`，`Long.parseLong(map.get("balance") + "")` 得余额（单位：厘），`map.get("extend1")` 取客户邮箱；
3. 余额 < 500000 厘（即 500 元，硬编码 `balanceLimit`，`MonitorClientBalanceTask.java:21`）时发提醒邮件（余额 `balance/1000` 换算为元）。

### 4.5 MailUtil 邮件告警

`MailUtil.java`：`@Component @RefreshScope`；两个重载：`sendEmail(subject, text)` 发给 `${spring.mail.tos}`（按逗号 split，`MailUtil.java:35`）与 `sendEmail(to, subject, text)`；`MimeMessageHelper` + `helper.setText(text)`（`MailUtil.java:58`，**未开启 HTML**，因此队列告警的 `<h1>` 模板会以纯文本形式显示）。

### 4.6 对 beacon-cache 的调用

`CacheClient.java:14-37`（`@FeignClient(value="beacon-cache")`）：
- `GET /cache/keys?pattern=` 返回**裸 `Set<String>`**（非 ResultVO，与 `/v2` 接口风格不一致）；
- `GET /v2/cache/hash/{key}` 返回 `ResultVO<Map<String,String>>`，default 方法 `hGetAll` + static `unwrap` 解包（`CacheClient.java:23-37`）。
`CacheFeignAuthConfig.java` 与 api 模块的完全一致，自动加签名三头（caller=beacon-monitor，secret=`cache_monitor_secret`，配置于 `beacon-monitor/bootstrap.yml:41-46`）。

### 4.7 配置文件

`beacon-monitor/bootstrap.yml`：
- Nacos 地址 192.168.88.128:8848（`:11-17`）；
- **邮件账号硬编码**（`:20-39`）：QQ 企业邮箱 `2931163626@qq.com`、SMTP 授权码 `optzmoheptapdcfb`、收件人 `lc204573@gmail.com`、SSL 465；
- cache 鉴权 caller/secret 硬编码（`:41-46`）。
无 `application.yml`；`xxl.job.*` 配置需 Nacos 提供。

---

## 5. 值得注意的设计点与潜在问题

### 5.1 安全问题（高）

| # | 问题 | 证据 |
|---|---|---|
| S1 | **凭据明文入库**：cache 各 caller 的 HMAC 密钥、SMTP 邮箱授权码、收件人均硬编码在 `bootstrap.yml` 并提交仓库 | `beacon-cache/bootstrap.yml:32-52`；`beacon-api/bootstrap.yml:19-24`；`beacon-monitor/bootstrap.yml:20-46` |
| S2 | **缓存签名不含请求体与 query**：`X-Cache-Sign` 只覆盖 caller+timestamp+method+path，POST 的 JSON body、query 参数（如 `/cache/set?value=`）均可被篡改而不破坏验签；且无 nonce，300 秒窗口内可重放 | `CacheAuthSignUtil.java:27-33`；时间窗口 `CacheAuthInterceptor.java:78-83` |
| S3 | **`enableDefaultTyping(NON_FINAL)`**：Redis 反序列化信任 JSON 中的类名，存在多态反序列化安全风险（历史 CVE 高发区） | `beacon-cache/.../config/RedisConfig.java:51` |
| S4 | **internal 接口默认无鉴权**：`internal.sms.token` 默认空串，未配置时内部单发接口完全开放；且内部接口绕过全部业务过滤器（余额/签名/模板/IP），仅靠 apikey+token | `SmsController.java:118`（`@Value("${internal.sms.token:}")` 在 `SmsController.java:54`）；跳过校验链见 `SmsController.java:110-131` |
| S5 | **真实 IP 可伪造**：`x-forwarded-for` 直接取第一个值，直连场景下可绕过 IP 白名单 | `SmsController.java:227-249`（尤其 `:239-243`） |
| S6 | 内部 token 比较用 `String.equals`，非常量时间（轻微） | `SmsController.java:118` |
| S7 | 日志泄露：cache 门面把所有写入/读取的**完整 value** 打到 info 日志（含客户余额、业务配置） | `beacon-cache/.../application/CacheFacade.java:64,70,76,91,107,193` 等 |

### 5.2 正确性问题（高）

| # | 问题 | 证据 |
|---|---|---|
| C1 | **默认过滤器链缺 `mobile` 和 `fee`**：手机号格式与余额校验的过滤器实现了但默认不启用（默认 `apikey,ip,sign,template`），线上若不显式配置等于不校验手机号与余额 | `CheckFilterContext.java:30`；实现 `MobileCheckFilter.java:16-32`、`FeeCheckFilter.java:22-80` |
| C2 | **余额"只查不扣"的竞态**：API 层仅读 Redis 余额镜像判断，扣费在下游进行，并发下可超额发送；且 Redis 与 MySQL 主口径的一致性依赖外部同步链路（代码注释也承认） | `FeeCheckFilter.java:68-79`；`CacheDomainRegistry.java:305-315` |
| C3 | **RabbitTemplate confirm/return 回调可能永不生效**：只 `setConfirmCallback/setReturnCallback`，未开 `publisher-confirm-type`/`publisher-returns`，Nacos 中若未配置则消息丢失仅靠默认行为 | `RabbitTemplateConfig.java:33-52` |
| C4 | **`ApiKeyCheckFilter` 的 parse 无保护**：`Long.parseLong(clientBusiness.get("id") + "")`，`id` 缺失时得到字符串 `"null"` 抛 `NumberFormatException`，被兜底成 `UNKNOWN_ERROR(-100)`（500 语义），而非清晰的 apikey 错误 | `ApiKeyCheckFilter.java:40`；兜底 `ApiExceptionHandler.java:56-60` |
| C5 | **监控任务资源泄漏**：每次调度创建的 RabbitMQ `Connection`/`Channel` 从不 close | `MonitorQueueMessageCountTask.java:61-62` |
| C6 | **余额任务无容错**：`Long.parseLong(map.get(BALANCE) + "")` 在 map 为 null / balance 缺失时 NPE/NumberFormatException；`email` 为 null 时 `helper.setTo(null)` 抛异常，任一脏数据导致整个任务失败 | `MonitorClientBalanceTask.java:43-48` |
| C7 | **邮件轰炸**：余额任务无节流/去重，每次调度给所有低余额客户重发邮件（按调度频率重复轰炸） | `MonitorClientBalanceTask.java:38-50` |
| C8 | **监控任务会创建/篡改业务队列**：`queueDeclare(durable)` 作为"读数量"的前置副作用；若队列已存在且参数不同会抛 406，被 `e.printStackTrace()` 吞掉 | `MonitorQueueMessageCountTask.java:78-81` |
| C9 | **channelId 提取靠字符串切片**：`QUEUE_PATTERN.indexOf("*")` 得索引 8 再 `key.substring(8)`，依赖前缀恰好等长，脆弱且隐晦 | `MonitorQueueMessageCountTask.java:34-37, 66` |
| C10 | 队列告警 HTML 模板闭合标签笔误 `</1>`；且 `setText` 未开 HTML 渲染，效果双损 | `MonitorQueueMessageCountTask.java:28`；`MailUtil.java:58` |

### 5.3 健壮性与代码质量（中）

| # | 问题 | 证据 |
|---|---|---|
| R1 | IP 白名单为空 = 放行（fail-open），测试用例甚至固化了该行为 | `IPCheckFilter.java:39-42`；`IPCheckFilterTest.java:38-53` |
| R2 | 业务错误统一 HTTP 200 + 业务 code，外部调用方需自行判断（风格问题，非 bug） | `ApiExceptionHandler.java:15-61` |
| R3 | `TemplateCheckFilter` 用 `replaceAll("#","")` 统计占位符数量（正则引擎 + 全串替换仅为计数），且模板匹配依赖链上 `sign` 已执行，否则 `submit.getSign()` 为 null 时 `"【null】"` 替换不命中导致误判 | `TemplateCheckFilter.java:44, 60-61` |
| R4 | `SignCheckFilter`/`FeeCheckFilter`/`ApiKeyCheckFilter` 每次请求都同步 Feign 调 cache 拉全量数据（sign 拉整个 Set、template 拉整个 Set），无本地缓存，高 QPS 下放大对 cache 的压力与延迟 | `SignCheckFilter.java:59`；`TemplateCheckFilter.java:46` |
| R5 | `CheckFilterContext` 用 `@RefreshScope` + `@Autowired Map` 注入，链配置刷新会重建上下文 Bean；链顺序无程序化约束（apikey 必须先于 sign/template/fee），配错顺序即运行时 500 | `CheckFilterContext.java:21-27, 45-53` |
| R6 | `SmsSendResultVO` 的 `count/fee` 字段从未被填充（受理即返回），外部调用方易误解 | `SmsController.java:146-149`；`SmsSendResultVO.java:14-25` |
| R7 | cache 写接口返回风格混乱：`void`（hmset/set/sadd）、裸 `Boolean`/`Set`/`Long`、`CacheDeleteResult` 并存，消费端难以统一处理 | `CacheController.java:42-44, 151-156, 210-213, 264-269` |
| R8 | cache 服务端验签用 `request.getRequestURI()`，客户端用 `template.path()`；若将来经网关/上下文路径转发则验签失败（当前点对点 Feign 可工作，属脆弱点） | `CacheAuthInterceptor.java:92` vs `CacheFeignAuthConfig.java:28-33` |
| R9 | `CacheFacade.unwrap`（api 侧）把 cache 服务返回的业务错误码转成 `IllegalStateException`，最终被 API 层包装为"参数错误 -10"，掩盖真实错误 | `beacon-api/.../client/CacheFacade.java:37-46` + `ApiExceptionHandler.java:49-54` |
| R10 | `deleteBatch` 逐条 `delete`，未使用 pipeline/del 批量命令，key 多时 RT 线性增长 | `beacon-cache/.../application/CacheFacade.java:300-313` |
| R11 | `MailUtil.sendEmail` 对 `tos.split(",")` 不 trim；任务中 `from/tos` 通过 `@Value` 注入无默认值，Nacos 缺失时启动/运行报错 | `MailUtil.java:19-23, 35` |
| R12 | `MonitorQueueMessageCountTask.text` 与 `MonitorClientBalanceTask.text` 为实例字段（非 final/static），字符串模板应内聚为常量 | `MonitorQueueMessageCountTask.java:28`；`MonitorClientBalanceTask.java:29` |
| R13 | `TestController` 的 `/test/pipeline` 使用裸 key（不走命名空间），若开启 test-api 会写脏数据 | `TestController.java:36-50` |
| R14 | monitor 的 `CacheClient.keys` 返回裸 `Set`，未包 `ResultVO`，与其他接口契约不一致 | `beacon-monitor/.../client/CacheClient.java:17-18` |
| R15 | 无 Nacos 配置镜像/文档时模块无法独立启动：三个模块的 `bootstrap.yml` 均把 server.port、redis、rabbitmq、xxl.job、snowflake.machineId 等关键配置外置到 Nacos，仓库内不可复现完整运行环境 | 三个模块 `bootstrap.yml` 仅含 Nacos 地址与少量配置 |

### 5.4 值得肯定的设计点

1. **过滤器链可配置且启动校验**：未知过滤器名在启动期即报错（`CheckFilterContext.java:33-40`），并有对应单测。
2. **缓存鉴权分层清晰**：验签用常量时间比较（`CacheAuthInterceptor.java:151-156`），时间窗口默认 300s，caller→secret、caller→permission 分离配置；`READ/WRITE/KEYS/TEST/ADMIN` 权限模型与请求路径映射明确；`keys` 扫描有白名单限制（`CacheFacade.java:336-354`）。
3. **统一命名空间**：逻辑 key 与物理 key 分离，前缀幂等转换，多环境隔离（`NamespaceKeyResolver.java`）。
4. **原子性封装**：锁申请（`setIfAbsent`+TTL）、锁释放（`deleteIfValueMatches` CAS）、消费即删（`getAndDelete`）都用 Redis 原子命令/Lua 实现（`LocalRedisClient.java:168-218`）。
5. **类型安全读接口**：`/v2/cache/**` 提供 string/int/long 强类型读取，整数用 `BigDecimal.exact` 转换，防止精度截断（`CacheFacade.java:403-417`）。
6. **缓存域契约集中管理**：`CacheDomainRegistry` 把 key 模板、Redis 类型、真源、写/删/重建策略收口（beacon-common），为重建/同步链路提供元数据。
7. **雪花 ID 线程安全且有防护**：`synchronized`、时钟回拨直接失败、ID 强制为正（`SnowFlakeUtil.java:115-141`）。
8. **监控告警有 try/catch 隔离**：队列告警的邮件发送失败只记日志，不影响任务整体（`MonitorQueueMessageCountTask.java:96-108`）。
9. 各模块启动类均打印环境信息便于排查（`ApiStarterApp.java:26-33` 等）。

---

## 6. 总结

- **beacon-api** 是"受理即返回"的异步入口：Bean Validation → 可配置过滤器链（策略模式）→ 雪花 ID → MQ 投递，业务规则校验（apikey/IP/签名/模板）强依赖 beacon-cache 的 Feign 读接口；内部接口是信任边界，默认无令牌、无业务校验。
- **beacon-cache** 是带自定义 HMAC 签名鉴权 + caller 权限模型 + 命名空间映射的统一缓存服务，能力覆盖 String/Hash/Set/ZSet/SCAN/原子锁语义，设计完整度高；主要风险在签名不覆盖 body/query 与 JSON 多态反序列化。
- **beacon-monitor** 用 xxl-job 做两类巡检：MQ 队列积压与客户余额，告警通道为 SMTP 邮件；代码量小但存在资源泄漏、脏数据致任务失败、邮件轰炸、敏感凭据入库等工程问题。

最需要优先处理的四项：S1（凭据入库）、S3（default typing）、C2（余额校验-扣费竞态）、C4/C6（parse 无保护导致的隐性 500 与任务失败）。
