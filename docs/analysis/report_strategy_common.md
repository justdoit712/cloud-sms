# beacon-strategy 与 beacon-common 模块代码分析报告

> 分析范围：`D:\Code\Java\springcloud\beacon-cloud\beacon-cloud\` 下的 `beacon-strategy`（主代码 20 个文件 + 测试 7 个）与 `beacon-common`（主代码 31 个 + 测试 10 个）。
> 本文所有"文件:行号"证据均相对于仓库根 `beacon-cloud\`。分析基于实际阅读源码，未做臆测；对跨模块调用（webmaster/cache）关键处做了交叉验证。

---

## 1. 模块概览

| 模块 | 角色 | 关键依赖 |
|---|---|---|
| beacon-common | 全系统共享底座：常量、模型、异常、工具、缓存域元数据、签名 | spring-context 5.3.12（显式版本）、jackson、caffeine、lombok |
| beacon-strategy | 短信发送前的策略校验与通道路由中枢 | beacon-common、spring-boot-starter-amqp、openfeign、nacos、ikanalyzer、hutool-dfa |

beacon-strategy 的 Redis 访问**不直连 Redis**，全部通过 OpenFeign 调用 `beacon-cache` 的 HTTP 接口（`BeaconCacheClient`），这是理解其性能与可用性特征的关键。

---

## 2. beacon-strategy 模块

### 2.1 启动类与基础配置

**StrategyStarterApp**（`beacon-strategy\src\main\java\com\cz\strategy\StrategyStarterApp.java`）
- `@SpringBootApplication` + `@EnableDiscoveryClient` + `@EnableFeignClients`（行 15-17），注册中心/配置中心为 Nacos（`bootstrap.yml` 行 11-18）。
- 手动注册裸 `RestTemplate`（行 21-24）：**无连接/读取超时配置**，供 `MobileOperatorUtil` 调用 360 第三方接口使用——第三方接口卡死会拖垮 MQ 消费线程。

### 2.2 MQ 入口：PreSendListener

`beacon-strategy\src\main\java\com\cz\strategy\mq\PreSendListener.java`

```java
@RabbitListener(queues = RabbitMQConstants.SMS_PRE_SEND)   // 行 26
public void listen(StandardSubmit submit, Message message, Channel channel) throws IOException {
    try {
        filterContext.strategy(submit);                    // 行 31
        channel.basicAck(deliveryTag, false);              // 行 33 成功 ack
    } catch (StrategyException e) {
        channel.basicAck(deliveryTag, false);              // 行 36 业务失败也 ack
    }
}
```

要点：
- 手动 ack，未设 `concurrency`（默认单线程消费，吞吐受限）。
- **`StrategyException` 一律 ack 丢弃**：策略拦截 = 消息"成功消费"（失败信息已通过写日志/回执队列旁路传走，见 2.7），无重试、无死信。
- **只有 `StrategyException` 被捕获**；任何其他 `RuntimeException`（Feign 调用异常、NPE、`StringIndexOutOfBoundsException` 等）会向上抛出 → 消息未 ack → 依赖 Broker 的 requeue/死信配置；若 requeue 且异常是确定性的，会形成**无限重投**。

### 2.3 策略过滤器链机制（核心）

**StrategyFilter 接口**：单一方法 `void strategy(StandardSubmit submit)`，以异常表达"拦截"（`beacon-strategy\...\filter\StrategyFilter.java` 行 5-11）。

**StrategyFilterContext**（`beacon-strategy\...\filter\StrategyFilterContext.java`）：
- 通过 `@Autowired Map<String, StrategyFilter>` 注入全部过滤器，**key = @Service 注解的 bean 名**（行 16-17），即"配置名 → 过滤器实现"的映射。
- 每次消息处理时，从 Redis hash `client_business:{apiKey}` 的 `clientFilters` 字段读取逗号分隔的链配置（行 31，经 `CacheFacade.getClientFilters`，行 49-51），**按配置顺序逐个执行**（行 36-54）。
- 兼容别名：配置值 `black` 会展开为 `blackGlobal`+`blackClient`（行 46-51）。
- **fail-open 行为**：`filters == null` 直接 return（行 32-34）——配置缺失时**既不拦截也不路由，消息被静默 ack 丢弃**；未知过滤器名仅 `log.warn` 后跳过（行 59-61）。

过滤器注册名全集（`@Service` value）与 webmaster 侧白名单（`beacon-webmaster\...\controller\SysStrategyFilterController.java` 行 30-42）一致：

| 配置名 | 类 | 职责 |
|---|---|---|
| blackGlobal | BlackGlobalStrategyFilter | 全局黑名单 |
| blackClient | BlackClientStrategyFilter | 客户级黑名单 |
| dirtyword | DirtyWordStrategyFilter | 敏感词（IK 分词 + sinter，**不拦截**） |
| dfaDirtyWord | DirtyWordDFAStrategyFilter | 敏感词（自研 DFA，**不拦截**） |
| hutoolDFADirtyWord | DirtyWordHutoolDFAStrategyFilter | 敏感词（Hutool DFA，**真正拦截**） |
| limitOneHour | LimitOneHourStrategyFilter | 验证码分钟/小时/天三级限流 |
| fee | FeeStrategyFilter | 余额扣减（Feign 调 webmaster） |
| phase | PhaseStrategyFilter | 号段补齐（归属地/运营商） |
| transfer | TransferStrategyFilter | 携号转网运营商修正 |
| route | RouteStrategyFilter | 通道选择与投递网关队列 |

> **默认链**：webmaster 新建客户时默认 `clientFilters = "blackGlobal,blackClient,dirtyword,route"`（`beacon-webmaster\...\ClientBusinessServiceImpl.java` 行 132-134）。注意默认链里**不含 fee/phase/transfer/limitOneHour**，且敏感词选中的是"只 log 不拦截"的 `dirtyword`（详见 5.2）。

### 2.4 各过滤器判断逻辑详解

#### BlackGlobalStrategyFilter（全局黑名单，行 27-45）
1. 取 `submit.getMobile()`；
2. 查 Redis string key `black:{mobile}`（`CacheKeyConstants.BLACK`，行 33）；
3. 值等于 `"1"` → 设置 errorMsg、`sendWriteLog` + `sendPushReport`，抛 `StrategyException(BLACK_GLOBAL)`（行 36-42）。

#### BlackClientStrategyFilter（客户黑名单，行 28-47）
1. 取 mobile + clientId；
2. 查 key `black:{clientId}:{mobile}`（行 35）；
3. 值 `"1"` → 同样回传 + 抛异常（行 38-44）。
- **Bug**：行 40 `submit.setErrorMsg(ExceptionEnums.BLACK_CLIENT + ",mobile = " + mobile)` 拼接的是**枚举对象本身**而非 `getMsg()`，落库的 errorMsg 形如 `"BLACK_CLIENT(-15,客户黑名单),mobile = 138xxxx"`；对比 BlackGlobal 行 38 用的是 `getMsg()`，两处不一致。

#### DirtyWordStrategyFilter（`dirtyword`，行 26-56）—— 已失效的拦截
1. IK 分词器（`useSmart=false`）对文本分词（行 32-43）；
2. 调 `cacheClient.sinterStr(UUID.randomUUID().toString(), DIRTY_WORD, 分词数组)` 求与敏感词集合的交集（行 48）；
3. **命中后仅 `log.info`，不设置错误、不抛异常**（行 51-55）→ 该过滤器形同虚设。
- 额外问题：行 39-42 catch `IOException` 后继续执行行 42 `contents.add(lex.getLexemeText())`，此时 `lex` 可能为 null → NPE；行 48 每次随机 `sinterKey`（UUID），使 cache 侧无法按客户复用/观测交集结果。

#### DirtyWordDFAStrategyFilter（`dfaDirtyWord`，行 21-35）
- 调 `DFAUtil.getDirtyWord(text)`，命中后同样**只 log 不拦截**（行 30-34）。
- `DFAUtil`（`beacon-strategy\...\util\DFAUtil.java`）：
  - 静态块通过 `SpringUtil.getBeanByClass(CacheFacade.class)` 在**类加载时**从 Redis 拉全量敏感词建 DFA 树（行 20-27）。风险：类首次加载发生在第一次调用时，若容器未就绪则 NPE；且敏感词树**只在 JVM 生命周期内构建一次**，运营后台新增/删除敏感词**不重启不生效**。
  - 行 30-42 残留 `main` 测试方法（生产代码携带 main，不影响运行但属杂质）。
  - 树构建与匹配逻辑本身正确（`isEnd` 标记处理，行 48-83；匹配回退不完整，行 90-133）。

#### DirtyWordHutoolDFAStrategyFilter（`hutoolDFADirtyWord`，行 29-52）
- `HutoolDFAUtil.getDirtyWord`（Hutool `WordTree.matchAll`）。
- 命中 → 设置 errorMsg → `sendWriteLog` + `sendPushReport` → **抛 `StrategyException(ERROR_DIRTY_WORD)`**（行 42-47）。三个敏感词过滤器中唯一真正拦截的版本。
- `HutoolDFAUtil` 静态块与 DFAUtil 同样的"启动时一次性建树"问题（行 17-24）。

#### LimitOneHourStrategyFilter（`limitOneHour`，行 48-127）
- 仅对**验证码类**（`state == SmsConstant.CODE_TYPE`，行 49-51）生效，通知/营销短信跳过。
- 三个 zset（key 分别为 `limit:minutes:{clientId}:{mobile}` 等）：
  1. `minuteLimit`：`zadd(key, sendTimeMilli, sendTimeMilli)`；**返回值非 true 直接判定"限流命中"并 reject**（行 66-68）——而 Redis `zadd` 返回 0 仅表示 member 已存在（同一毫秒内第二条消息），这是**误杀**而非限流；随后 `zRangeByScoreCount` 窗口计数 `> LIMIT_MINUTE(=1)` 则回滚成员并拒绝（行 70-75）。
  2. `hourLimit`/`dayLimit`：`tryInsertWithRetry`（行 104-119）——zadd 失败改用 `System.currentTimeMillis() + retry + 1` 作新 member 重试（行 115），**时间口径与 sendTime 漂移**；计数超限回滚 `zRemove` 后拒绝。
- 大量魔术值硬编码：`ONE_MINUTE = 60*1000L - 1`、`LIMIT_HOUR = 3`、`LIMIT_DAY = 10`、`RETRY_COUNT = 2`（行 25-39），无法按客户/场景配置。
- 时区硬编码 `ZoneOffset.of("+8")`（行 25、54）——非 UTC+8 部署即错位。
- 并发问题：`zadd + zcount + zremove` 三步非原子（跨三个 HTTP 调用，见 BeaconCacheClient），并发提交时计数窗口可被穿透。
- 失败路径统一 `reject()`：写日志 + 推送回执 + 抛异常（行 121-127）。

#### FeeStrategyFilter（`fee`，行 41-82）
- 入参校验：`fee==null || fee<=0 || clientId 非法` → 按 `PARAMETER_ERROR` 处理并抛异常（行 45-49）。
- `amountLimit = ClientBalanceUtil.getClientAmountLimit(clientId)` —— **硬编码返回 `-10000L`**（`beacon-strategy\...\util\ClientBalanceUtil.java` 行 11-13），即所有客户固定允许透支 10000 厘（100 元），业务明显未完成。
- Feign 调 `beacon-webmaster` 的 `/internal/balance/debit` 同步扣费（`InternalBalanceClient`，行 56）；token 来自 `@Value("${internal.balance.token:}")`，bootstrap.yml 中默认**空串**（`beacon-strategy\src\main\resources\bootstrap.yml` 行 28-30）。
- 响应 code==0 通过；code==BALANCE_NOT_ENOUGH(-6) 走余额不足；其余/异常/空响应一律 `handleUnknownFailure`（行 57-81）。
- `warnMysqlSourceOfTruthConstraintOnce`（行 129-141）：利用 `CacheDomainRegistry` 检查 client_balance 域真源，MYSQL 时告警"扣费应走 MySQL 原子更新 + Redis 刷新"——说明当前 Feign 直扣与契约定义的 `MYSQL_ATOMIC_UPDATE_THEN_REFRESH` 写入策略存在张力（见 5.4）。

#### PhaseStrategyFilter（`phase`，行 68-111）
1. `mobile.substring(0, 7)`（行 72）——**手机号长度 <7 抛 `StringIndexOutOfBoundsException`**，不属于 StrategyException，会触发 2.2 所述的未 ack 重投；
2. 查 Redis `phase:{前7位}`；未命中 → 调 `MobileOperatorUtil.getMobileInfoBy360`（360 接口 `https://cx.shouji.360.cn/phonearea.php?number=`，`MobileOperatorUtil.java` 行 20、38）；
3. 第三方命中 → 发 `Map{mobile, info}` 到 `MOBILE_AREA_OPERATOR` 队列异步回写（行 82-87；注意注释提到需 Jackson 转换器，与 2.6 配置一致）；查不到 → 用常量 `"未知 未知，未知"`（行 26、90）；
4. 按 `","` 拆分，`operatorIdByName("移动/联通/电信")` 映射为 1/2/3，识别不出 set 0（行 97-105）。
- 主链路同步依赖第三方 HTTP 且 RestTemplate 无超时（2.1）——**360 接口抖动直接阻塞短信发送**。
- `UNKNOWN` 字符串靠"恰好能被逗号拆成 2 段"来兼容（行 24-26、97）。
- 行 41-66 保留了一大段被注释的旧实现（含发送 `submit.getMobile()` 的旧逻辑），与新实现并存，维护噪音。

#### TransferStrategyFilter（`transfer`，行 23-42）
- 查 Redis `transfer:{mobile}`，有值（运营商 id 字符串）→ `setOperatorId(Integer.valueOf(value))` + `setIsTransfer(true)`（行 32-36）。
- 引用了阿里系 `com.alibaba.cloud.commons.lang.StringUtils`（行 3），与 PhaseStrategyFilter 用的 `org.apache.commons.lang.StringUtils`（PhaseStrategyFilter 行 12）**两套工具并存**，依赖口径不统一。
- 配置顺序上必须位于 `route` 之前才有意义（否则修正的 operatorId 不被路由使用）——链顺序无校验（见 5.1）。

#### RouteStrategyFilter（`route`，行 42-101）—— 通道选择与投递
1. 取 `client_channel:{clientId}` 集合（客户-通道绑定快照），按 **weight 降序、channelId 升序**排序（行 48-51）；
2. 逐个过滤：绑定可用（`isAvailableForRoute`：**`isAvailable == 0` 视为可用**，行 36-38）、通道快照存在且可用、`channel.supportsOperator(operatorId)`（`channelType==0` 为通用通道，否则要求 operatorId == channelType，行 40-45）；
3. 选中第一个即 break（行 66-68）——**无流量负载均衡**，纯静态权重排序，权重相同永远走同一通道；
4. `srcNumber = channelNumber + clientChannelNumber` 纯字符串拼接（行 76，如 "1069"+"01"→"106901"）；
5. **每次路由都 `amqpAdmin.declareQueue(QueueBuilder.durable("sms_gateway_topic_{channelId}").build())`**（行 78-79）：重复声明幂等但每次多一次 Broker 往返；队列经 default exchange 以队列名作 routing key 直投（行 80），要求网关侧同名队列（`RabbitMQConstants.SMS_GATEWAY` 前缀）。
6. 异常处理分层：`AmqpException` → 记日志回传；`StrategyException` 原样上抛；其他 `RuntimeException` → 转 `UNKNOWN_ERROR`（行 81-92）。

### 2.5 路由相关工具类

| 类 | 现状 | 问题 |
|---|---|---|
| ChannelTransferUtil（`util\ChannelTransferUtil.java` 行 14-26） | 空实现，仅返回入参 Map（"留的口子"） | 未接入任何调用方；用原始 `Map` 类型传递通道 |
| ClientBalanceUtil（`util\ClientBalanceUtil.java` 行 11-13） | `getClientAmountLimit` 硬编码 `-10000L` | 每客户固定透支额度，参数被 FeeStrategyFilter 当真实限额用 |
| MobileOperatorUtil（`util\MobileOperatorUtil.java` 行 35-57） | RestTemplate 调 360 接口，解析 JSON 拼 `"省 市,运营商"` | 无超时、无缓存策略（仅靠 phase 缓存兜底）、`code != 0` 返回 null 由调用方当"未知"处理 |
| SpringUtil（`util\SpringUtil.java`） | 静态 ApplicationContext 存取器 | 静态块初始化 DFA 树的桥梁，带来类加载顺序隐患（见 5.2） |
| ErrorSendMsgUtil（`util\ErrorSendMsgUtil.java`） | 失败旁路：`sendWriteLog`（置 reportState=2 后发 `SMS_WRITE_LOG`，行 31-35）；`sendPushReport`（校验客户回调开关与 URL，Redis `SETNX` 24h TTL 去重后发 `SMS_PUSH_REPORT`，行 41-55） | 去重 key 仅 24h（`CacheFacade` 行 23-24），同 sequenceId 跨天重复回执可能二次推送 |

### 2.6 RabbitMQ / Redis 配置

**RabbitMQConfig**（`config\RabbitMQConfig.java`）
- 仅声明 4 个**持久队列**：`sms_pre_send_topic`、`mobile_area_operator_topic`、`sms_write_log_topic`、`sms_push_report_topic`（行 45-76），**全部走 default exchange**（用队列名作 routing key），未声明任何显式交换机/绑定。
- 全局 `Jackson2JsonMessageConverter`：注册 JavaTimeModule、禁用 WRITE_DATES_AS_TIMESTAMPS（行 28-40）。注释声称输出 `yyyy-MM-dd HH:mm:ss`，实际 Jackson 默认输出 **ISO-8601**（如 `2024-01-01T12:00:00`），且未指定时区——注释与行为不符，需与网关侧反序列化口径对齐。

**RabbitTemplateConfig**（`config\RabbitTemplateConfig.java`）
- 自定义 RabbitTemplate，配置 confirm（行 33-42）与 return（行 45-53）回调，但**都只 log.error，无补偿、无落库、无告警**：投递到网关失败的消息静默丢失。
- return 回调中 `new String(message.getBody())` 直接打印消息体：中文乱码 + 可能泄露短信内容（行 51）。

**Redis 访问 = Feign HTTP**（`client\BeaconCacheClient.java`）
- 全部通过 `beacon-cache` 的 REST 接口：`/v2/cache/hash/{key}/string/{field}`、`/v2/cache/set/{key}/map-members`、`/cache/zadd/...`、`/cache/zrangebyscorecount/...`、`/cache/zremove/...`、`/cache/setnx/{key}` 等（行 14-58）。
- 一条消息典型 RPC 次数：读链配置 1 + 黑名单 2 + 敏感词（可选）+ 限流每级 2-3 + fee 快照 1 + 扣费 1 + phase 1-2 + transfer 1 + 路由绑定 1 + 通道 1 ≈ **7~12 次 HTTP**。限流 zadd/zcount/zremove 拆分三次调用，**非原子**。
- `CacheFacade`（`client\CacheFacade.java`）做类型化包装（行 49-113）：整数解析失败抛 `IllegalStateException`（行 137-141、155-159）→ 该异常在 PreSendListener 中**不会被 ack**，会引发重投。

### 2.7 一条消息的完整处理链路

```
接口模块 beacon-api
   │  发送 StandardSubmit(sequenceId, apiKey, clientId, mobile, text, fee, state, sendTime...)
   ▼
队列 sms_pre_send_topic（default exchange 直投）
   ▼
PreSendListener.listen（手动 ack；仅捕获 StrategyException）
   ▼
StrategyFilterContext.strategy
   ├─ Redis: client_business:{apiKey} hash → clientFilters（链配置，逗号分隔，按序执行）
   │
   ├─[1] blackGlobal : black:{mobile}=="1" ? 拦截(BLACK_GLOBAL)
   ├─[2] blackClient : black:{clientId}:{mobile}=="1" ? 拦截(BLACK_CLIENT)
   ├─[3] dirtyword / dfaDirtyWord / hutoolDFADirtyWord : 文本分词/DFA vs dirty_word 集合
   │        （前两者命中仅 log；hutoolDFADirtyWord 命中拦截 ERROR_DIRTY_WORD）
   ├─[4] limitOneHour : 仅 state==0(验证码)：zset 分钟/小时/天三级计数，超限拦截并回滚成员
   ├─[5] fee : Feign beacon-webmaster /internal/balance/debit 预扣费；余额不足拦截
   ├─[6] phase : phase:{前7位} 归属地/运营商补齐；未命中调 360 接口并异步回写 MQ
   ├─[7] transfer: transfer:{mobile} 有值则覆盖 operatorId
   └─[8] route : client_channel:{clientId} 按 weight 降序选通道
          → 校验通道可用性/运营商 → 声明 sms_gateway_topic_{channelId}
          → 发送 StandardSubmit 到网关队列
   │
   ├─ 任一过滤器拦截：ErrorSendMsgUtil
   │      ├─ sms_write_log_topic（reportState=2 写 ES 日志）
   │      └─ sms_push_report_topic（24h 去重后回执给接口模块回调客户）
   │      抛 StrategyException → PreSendListener ack（消息终结）
   ▼
beacon-smsgateway 消费 sms_gateway_topic_{channelId}
```

关键顺序约束（代码**未强制**，完全依赖运营配置）：`phase`/`transfer` 必须在 `route` 之前（否则 operatorId 未补/未修正）；`fee` 必须在 `route` 之前（否则先发货后扣费）；`route` 必须最后（它是唯一推进消息的过滤器）。

---

## 3. beacon-common 模块

### 3.1 RabbitMQConstants（`constant\RabbitMQConstants.java`，行 8-31）

| 常量 | 值 | 用途 |
|---|---|---|
| SMS_PRE_SEND | `sms_pre_send_topic` | API 模块 → 策略模块预发送队列 |
| MOBILE_AREA_OPERATOR | `mobile_area_operator_topic` | 策略 → 后台管理：手机号归属地/运营商异步同步（MySQL+Redis） |
| SMS_WRITE_LOG | `sms_write_log_topic` | 策略/搜索链路 → 搜索模块写 Elasticsearch 日志 |
| SMS_PUSH_REPORT | `sms_push_report_topic` | 状态报告推送（策略失败回执、网关回执） |
| SMS_GATEWAY | `sms_gateway_topic_` | 策略 → 网关队列**前缀**，后缀追加通道 ID |
| SMS_GATEWAY_NORMAL_EXCHANGE / _QUEUE | `sms_gateway_normal_exchange/queue` | 网关状态更新链路（正常） |
| SMS_GATEWAY_DEAD_EXCHANGE / _QUEUE | `sms_gateway_dead_exchange/queue` | 网关状态更新链路（死信） |

> 命名观察：所有 `*_topic` 常量实际是**队列名**（经 default exchange 直投），并非 topic 交换机；`SMS_GATEWAY_NORMAL/DEAD_*` 是交换机+队列（说明网关链路用过绑定关系），两套风格并存，易误导。

### 3.2 SmsConstant（`constant\SmsConstant.java`）

- `REPORT_SUCCESS=1`、`REPORT_FAIL=2`（行 8、13）——注意**两处注释都写"发送成功"**（行 5-7 与 10-12），REPORT_FAIL 的注释复制错误。
- `CODE_TYPE=0 / NOTIFY_TYPE=1 / MARKETING_TYPE=2`（行 18、23、28），与 `StandardSubmit.state` 注释一致。

### 3.3 核心模型

**StandardSubmit**（`model\StandardSubmit.java`）——贯穿 API/策略/网关的统一提交对象：
- 标识：sequenceId（雪花）、clientId、uid（客户业务侧请求 ID）、apiKey；
- 内容：mobile、sign、text、signId、state（短信类型）；
- 计费/路由：fee（单位**厘**，行 63）、operatorId、areaCode、area、srcNumber、channelId；
- 状态：reportState（0-等待/1-成功/2-失败，行 84）、errorMsg、isTransfer；
- `sendTime` 用 `LocalDateTimeSerializer/Deserializer` 显式注解（行 59-60）；
- `oneHourLimitMilli`（行 108）：全仓库 Java 代码中**无任何读写**（grep 验证仅此一处定义），为遗留死字段。

**StandardReport**（`model\StandardReport.java`）——回执与客户回调共用：apiKey、sequenceId、clientId、uid、mobile、sendTime、reportState、errorMsg、isCallback、callbackUrl、resendCount（默认 0，行 56）、reUpdate（默认 false，行 59）。

### 3.4 异常体系

```
RuntimeException
 ├─ BizException（抽象，code + message；exception\BizException.java 行 7-19）
 │   ├─ ApiException（接口模块）
 │   ├─ StrategyException（策略模块，PreSendListener 唯一捕获类型）
 │   └─ SearchException（搜索模块）
 └─ JsonSerializeException（JSON 失败专用，独立体系；exception\JsonSerializeException.java 行 9）
```

**ExceptionEnums**（`enums\ExceptionEnums.java`）：
- 业务码段：-1 ~ -21（apiKey/签名/模板/余额/敏感词/黑白名单/限流/无通道等，行 7-25）；
- 后台码段：-100 ~ -106（验证码、认证、权限、内部令牌，行 27-33）；
- 缓存同步码段：-201 ~ -203（行 35-37）。
- **问题**：`UNKNOWN_ERROR(-100)` 与 `KAPACHA_ERROR(-100)` **code 重复**（行 7 与 27），按 code 反查/统计会歧义；`BALANCE_NOT_ENOUGH` 文案"手客户余额不足"多一个"手"字（行 13）；`KAPACHA` 疑为 KAPTCHA 拼写（与 `WebMasterConstants.KAPTCHA` 行 11 不一致）。

### 3.5 工具类

| 类 | 要点 |
|---|---|
| SnowFlakeUtil（`util\SnowFlakeUtil.java`） | 41 位时间戳 + 5 位机器 + 5 位服务 + 12 位序列（行 32-39）；起始时间 2022-11-11（行 27）；`@PostConstruct` 校验 id 范围（行 91-98）；时钟回拨**直接抛 ApiException** 无自旋等待（行 117-121）；`synchronized nextId()`；`id & Long.MAX_VALUE` 保证正数（行 140）。机器/服务 ID 靠 `snowflake.machineId/serviceId` 配置保证多节点不冲突 |
| JsonUtil（`util\JsonUtil.java`） | 静态共享 ObjectMapper + JavaTimeModule（行 21-23）；序列化失败统一转 JsonSerializeException（行 31-33）；`fromJson` 空串返回 null（行 37-39） |
| CMPPDeliverMapUtil / CMPPSubmitRepoMapUtil（`util\`） | Caffeine 进程内缓存：10 分钟过期、50 万上限（各自行 19-22）。**网关多实例部署时，SubmitResp/Deliver 若落在不同实例则关联失败**——需要 sticky 路由或共享存储 |
| PhoneFormatCheckUtil | **注意：该类不在 beacon-common**，实际位于 `beacon-api\src\main\java\com\cz\api\utils\PhoneFormatCheckUtil.java`（grep 验证）。正则：`^(13[0-9]|14[01456879]|15[0-35-9]|16[2567]|17[0-8]|18[0-9]|19[0-35-9])\d{8}$`（行 11）。若意图是共享工具，当前放错了模块 |

### 3.6 安全签名（CacheAuthSignUtil / CacheAuthHeaders）

- `CacheAuthSignUtil`（`security\CacheAuthSignUtil.java`）：HMAC-SHA256，十六进制小写输出（行 16-25）；`buildPayload` 拼接 `caller\ntimestamp\nmethod\npath`（行 27-33）；`normalizePath` 去 query、补前导 `/`（行 35-48）。
- 消费方：strategy 侧 `CacheFeignAuthConfig`（`beacon-strategy\...\config\CacheFeignAuthConfig.java` 行 21-38）为 Feign 请求加 `X-Cache-Caller/Timestamp/Sign` 三头。
- `CacheAuthHeaders`（行 28、36、44、52）统一定义头名与请求属性名。
- **问题**：签名**只覆盖方法+路径，不覆盖 body 与 query 参数值**（仅路径本身）——对写类接口（zadd/setnx 等）无法防篡改；时间戳仅"参与签名"，策略侧未做时间窗校验（防重放依赖 cache 服务端实现）；secret 明文写在 `beacon-strategy\src\main\resources\bootstrap.yml` 行 21-27（`cache_strategy_secret`），随代码仓库分发。

### 3.7 Redis 操作封装：缓存域契约体系（`cache\` 包）

**设计**：用"契约"把每个缓存域的 key 模板、Redis 类型、真源、写/删/重建策略、归属服务收口到 `CacheDomainRegistry`（`cache\meta\CacheDomainRegistry.java` 静态注册 9 个域，行 244-355）：

| 域 | key 模板 | Redis 类型 | 写策略 | 删策略 | 重建 |
|---|---|---|---|---|---|
| client_business | `client_business:{apikey}` | HASH | WRITE_THROUGH | DELETE_KEY | FULL_REBUILD |
| client_sign | `client_sign:{clientId}` | SET | DELETE_AND_REBUILD | DELETE_KEY | FULL_REBUILD |
| client_template | `client_template:{signId}` | SET | DELETE_AND_REBUILD | DELETE_KEY | FULL_REBUILD |
| client_channel | `client_channel:{clientId}` | SET | DELETE_AND_REBUILD | DELETE_KEY | FULL_REBUILD |
| channel | `channel:{id}` | HASH | WRITE_THROUGH | DELETE_KEY | FULL_REBUILD |
| client_balance | `client_balance:{clientId}` | HASH | **MYSQL_ATOMIC_UPDATE_THEN_REFRESH** | **OVERWRITE_ONLY** | FULL_REBUILD |
| transfer | `transfer:{mobile}` | STRING | WRITE_THROUGH | DELETE_KEY | FULL_REBUILD |
| black | `black:{mobile}`、`black:{clientId}:{mobile}` | STRING | WRITE_THROUGH | DELETE_KEY | FULL_REBUILD |
| dirty_word | `dirty_word` | SET | DELETE_AND_REBUILD | DELETE_KEY | FULL_REBUILD |

- 全部域 `sourceOfTruth = MYSQL`、`ownerService = beacon-webmaster`（策略模块只读）；
- 余额域特殊：`OVERWRITE_ONLY`（禁删 key）——但 strategy 的 `FeeStrategyFilter` 实际经 Feign 同步扣减，与契约"MySQL 原子更新"的约定存在**实现漂移**（FeeStrategyFilter 行 129-141 有专门告警兜底）。
- 配套元数据：`CacheDomainContract`（不可变 + 构造校验，行 59-77）、`CacheRedisType`、`CacheSourceOfTruth`、`CacheWritePolicy`、`CacheDeletePolicy`、`CacheRebuildPolicy` 六个小类，职责清晰。

### 3.8 通用返回与 VO

- `ResultVO<T>`（code/msg/data，data 为 NON_EMPTY 时忽略，`vo\ResultVO.java` 行 20-21）；`PageResultVO`（total/rows，行 22-26）。
- `Result` 工厂（`util\Result.java`）：`ok()` 系列 code=0；`error(String)` 默认 code=**-1**（行 57-59）——与 `ERROR_APIKEY(-1)` 撞码，纯展示层问题。
- `CacheStringWriteRequest`（value + ttlSeconds）：网关写 cache 字符串的请求体（`vo\CacheStringWriteRequest.java`）。

### 3.9 其他常量与枚举

- `ApiConstant`：签名包裹符 `【` `】`（行 8、13）、`SINGLE_FEE = 50L` 单条费用硬编码 50 厘（行 15）——与 StandardSubmit.fee 单位一致，但**单条计费写死在常量**，无按通道/客户定价能力。
- `MobileOperatorEnum`：移动 1 / 联通 2 / 电信 3 / 未知 0；`UNKNOW` 拼写错误（行 17）；`operatorIdByName` 未命中返回 null（行 32-35），调用方（PhaseStrategyFilter 行 105）做了兜底。
- `CMPP2DeliverEnums` / `CMPP2ResultEnums`：CMPP 回执/应答码映射；`Exceeding_maximum_message_length` 命名违反枚举大写规范（CMPP2ResultEnums 行 25）。

---

## 4. 设计亮点

1. **配置驱动的过滤器链**：`clientFilters` 存 Redis，运营可在线调整客户级校验链（StrategyFilterContext 行 30-55），并有 webmaster 侧白名单校验 + 去重规范化（SysStrategyFilterController 行 146-166）。
2. **兼容性处理**：`black` 旧别名展开（StrategyFilterContext 行 46-51）；`ClientBusinessServiceImpl` 给新建客户默认链。
3. **失败旁路闭环**：拦截不静默——统一 `sendWriteLog`（ES 留痕）+ `sendPushReport`（客户回执，24h SETNX 去重，CacheFacade 行 103-113），配合"业务异常也 ack"避免消息堆积。
4. **缓存域契约体系**（beacon-common `cache` 包）：把缓存 key/类型/真源/策略做成显式元数据，余额域通过 `OVERWRITE_ONLY` + `MYSQL_ATOMIC_UPDATE_THEN_REFRESH` 表达高风险语义，且 strategy 侧有运行期一致性告警（FeeStrategyFilter 行 129-141）。
5. **强类型缓存门面**：`CacheFacade`/`ClientChannelBinding`/`ChannelInfo` 将裸 Redis 值解析为类型化快照，解析失败快速抛错（fail-fast）而非悄悄放行。
6. **手动 ack + 异常分类**：业务失败与系统异常区分对待（虽然系统异常路径还有问题，见 5.1）。
7. **测试覆盖**：两个模块共 17 个单测，PreSendListener（ack 语义）、StrategyFilterContext（链展开/容错）、Route、Limit、Fee 等核心路径均有用例。

---

## 5. 值得注意的问题与风险（按严重度，附证据）

### 5.1 高：策略链顺序无约束 + fail-open + 异常路径

| 问题 | 证据 |
|---|---|
| 链顺序完全依赖运营配置；`phase`/`transfer` 在 `route` 后配置会导致运营商信息缺失/错误路由，`fee` 在 `route` 后导致先发货后扣费；代码无顺序校验、无默认兜底链 | `StrategyFilterContext.java` 行 36-54；过滤器顺序只在 webmaster 白名单里做了"名称合法"校验（`SysStrategyFilterController.java` 行 146-166），无顺序约束 |
| `clientFilters == null` 时整条消息**静默丢弃**（return 后 PreSendListener 直接 ack，不路由不报错） | `StrategyFilterContext.java` 行 32-34 + `PreSendListener.java` 行 33 |
| 未知过滤器名仅 warn 跳过，配置笔误导致校验静默缺失 | `StrategyFilterContext.java` 行 59-61 |
| 非 StrategyException（NPE、Feign 异常、substring 越界等）不上抛未 ack → 依赖 broker requeue，确定性异常会**无限重投**；而 cache 服务宕机时所有过滤器抛 Feign 异常，消息全部滞留 | `PreSendListener.java` 行 30-37（仅 catch StrategyException）；`PhaseStrategyFilter.java` 行 72（substring 越界源） |
| 默认链 `blackGlobal,blackClient,dirtyword,route` 缺 fee/phase/transfer/limitOneHour：新建客户**不扣费、不补运营商、不限流**，且 route 对非通用通道（channelType≠0）会因 operatorId=null 全部跳过 → NO_CHANNEL | `ClientBusinessServiceImpl.java` 行 132-134；`ChannelInfo.java` 行 40-45 |

### 5.2 高：敏感词过滤三实现并存，两个不拦截，且建树一次性

| 问题 | 证据 |
|---|---|
| `dirtyword`、`dfaDirtyWord` 命中敏感词只 log 不拦截（无 errorMsg、无异常），配置了等于没配 | `DirtyWordStrategyFilter.java` 行 51-55；`DirtyWordDFAStrategyFilter.java` 行 30-34 |
| 默认链用的正是无效的 `dirtyword` | `ClientBusinessServiceImpl.java` 行 133 |
| DFA 树在**类加载静态块**中通过 `SpringUtil` 从 Redis 一次性构建：容器未就绪时 NPE；运行期增删敏感词**不重启不生效** | `DFAUtil.java` 行 20-27；`HutoolDFAUtil.java` 行 17-24 |
| IK 分词异常路径 catch 后继续使用可能为 null 的 `lex` | `DirtyWordStrategyFilter.java` 行 38-42 |

### 5.3 高：限流实现的正确性/并发缺陷

| 问题 | 证据 |
|---|---|
| `zadd` 返回 false（member 已存在，同一毫秒第二条）被直接判定为"一分钟限流命中"而拒绝 —— 高并发验证码场景**误杀** | `LimitOneHourStrategyFilter.java` 行 66-68 |
| zadd → zcount → zremove 分三次独立 HTTP 调用，非原子；并发提交可同时通过计数窗口 | `BeaconCacheClient.java` 行 33-44；`LimitOneHourStrategyFilter.java` 行 64-101 |
| 重试插入使用 `System.currentTimeMillis()+retry+1`，与 sendTime 口径不一致，且会写入"未来分数"污染窗口 | `LimitOneHourStrategyFilter.java` 行 115 |
| 时区硬编码 +8；限流阈值/窗口全部魔术值 | `LimitOneHourStrategyFilter.java` 行 25、27-39、54 |

### 5.4 中：计费链路不完整、无补偿

| 问题 | 证据 |
|---|---|
| 扣费成功后才执行 route；route 失败（无通道）**费用已扣、无退款/冲正逻辑** | `FeeStrategyFilter.java` 行 56 扣费在前；`RouteStrategyFilter.java` 行 71-73 NO_CHANNEL 直接抛异常，无退费调用 |
| 所有客户透支额度硬编码 `-10000L`，业务未实现 | `ClientBalanceUtil.java` 行 11-13 |
| 扣费经 Feign 同步直调，与契约定义的 `MYSQL_ATOMIC_UPDATE_THEN_REFRESH` 存在实现漂移，仅靠一行运行期 warn 提示 | `FeeStrategyFilter.java` 行 129-141；`CacheDomainRegistry.java` 行 305-315 |
| `internal.balance.token` 默认空串，webmaster 侧若启用校验则全部 401 → 按 UNKNOWN_ERROR 回传客户 | `bootstrap.yml` 行 28-30；`FeeStrategyFilter.java` 行 116-127 |
| 内部故障（cache/webmaster 不可用）被当成"发送失败"回执给客户，语义污染 | `FeeStrategyFilter.java` 行 116-127（handleUnknownFailure 含 sendPushReport） |

### 5.5 中：错误信息与枚举质量问题

| 问题 | 证据 |
|---|---|
| 客户黑名单 errorMsg 拼接的是**枚举对象**而非文案 | `BlackClientStrategyFilter.java` 行 40（对比 `BlackGlobalStrategyFilter.java` 行 38 用 getMsg()） |
| `UNKNOWN_ERROR` 与 `KAPACHA_ERROR` code 均为 -100；`BALANCE_NOT_ENOUGH` 文案"手客户余额不足"；`KAPACHA` 拼写 | `ExceptionEnums.java` 行 7、13、27 |
| `REPORT_FAIL` 注释写成"短信发送成功"；`UNKNOW` 枚举名拼错；`Exceeding_maximum_message_length` 命名不规范 | `SmsConstant.java` 行 10-13；`MobileOperatorEnum.java` 行 17；`CMPP2ResultEnums.java` 行 25 |
| `RouteStrategyFilter` 的 fail() 传入 `NO_CHANNEL.getMsg()` 而 BlackGlobal 等自行拼 `",mobile="`，错误文案格式各过滤器不统一 | `RouteStrategyFilter.java` 行 96-101 vs `BlackGlobalStrategyFilter.java` 行 38 |

### 5.6 中：性能与可用性（缓存走 HTTP、无超时）

| 问题 | 证据 |
|---|---|
| 每条消息 7~12 次 Feign HTTP RPC（链配置、黑白名单、限流 3 级×2-3 次、phase、transfer、路由绑定+通道、fee），无本地缓存、无批量接口；cache 服务抖动/宕机 = 策略全链路中断 | `BeaconCacheClient.java` 行 14-58；各过滤器调用点 |
| RestTemplate 无连接/读取超时，360 第三方接口同步阻塞主链路 | `StrategyStarterApp.java` 行 21-24；`MobileOperatorUtil.java` 行 38 |
| 每次路由都 declareQueue（多一次 broker 往返），且是每条消息一次 | `RouteStrategyFilter.java` 行 78-79 |
| PreSendListener 未配置 concurrency，默认单线程消费 | `PreSendListener.java` 行 26-27 |

### 5.7 低：投递可靠性、安全与其他

| 问题 | 证据 |
|---|---|
| confirm/return 回调只 log，网关队列投递失败消息静默丢失，无补偿/死信 | `RabbitTemplateConfig.java` 行 33-53 |
| return 回调 `new String(body)` 乱码且可能泄露短信内容到日志 | `RabbitTemplateConfig.java` 行 51 |
| 回执去重 TTL 仅 24h，跨天同 sequenceId 重复触发会二次推送 | `CacheFacade.java` 行 23-24、103-113 |
| 缓存鉴权签名不覆盖 body/query 值，仅路径；secret 明文入库（bootstrap.yml） | `CacheAuthSignUtil.java` 行 27-48；`bootstrap.yml` 行 21-27 |
| Jackson 转换器注释与实际输出格式不符（ISO-8601 vs yyyy-MM-dd HH:mm:ss），存在跨模块反序列化口径风险 | `RabbitMQConfig.java` 行 35-37 |
| CMPP 关联上下文存进程内 Caffeine，网关多实例时回执可能关联失败 | `CMPPSubmitRepoMapUtil.java` 行 19-22；`CMPPDeliverMapUtil.java` 行 19-22 |
| `oneHourLimitMilli` 死字段；`ChannelTransferUtil` 空实现；`DFAUtil` 残留 main；`PhaseStrategyFilter` 大段注释旧实现 | `StandardSubmit.java` 行 108；`ChannelTransferUtil.java` 行 23-26；`DFAUtil.java` 行 30-42；`PhaseStrategyFilter.java` 行 41-66 |
| `isAvailable` 语义倒置（0=可用）与 `isCallback`（1=开）不一致，且 CacheFacade 解析失败时默认 1（不可用）——字段语义两套 | `ClientChannelBinding.java` 行 36-38、`ChannelInfo.java` 行 36-38 vs `ClientBusinessSnapshot.java` 行 40-42；`CacheFacade.java` 行 83 |
| 手机号校验工具 `PhoneFormatCheckUtil` 实际位于 beacon-api 而非 beacon-common，若其他模块需要则为依赖倒置隐患 | `beacon-api\src\main\java\com\cz\api\utils\PhoneFormatCheckUtil.java`（grep 证实 common 中不存在） |

---

## 6. 改进建议（按优先级）

1. **链治理**：在 StrategyFilterContext 内置"安全顺序"校验（phase/transfer → fee → route 必须存在且相对有序）；`clientFilters` 缺失时 fail-closed（写日志 + 回执 + 拒绝）而非静默丢弃。
2. **敏感词收敛**：下线 `dirtyword`/`dfaDirtyWord` 或改为与 hutool 版一致的完整拦截；DFA 树改为启动时构建 + 定时/订阅刷新；修复 IK catch 后的空指针路径。
3. **限流重构**：把 zadd/zcount/zremove 合并为 cache 侧**原子 Lua/脚本接口**；zadd 返回 false 不再视为命中；阈值/窗口移入配置或客户维度；修掉 `System.currentTimeMillis()` 重试口径。
4. **计费闭环**：route 失败退费/冲正；`amountLimit` 改为真实客户配置；确认 webmaster 扣费端与 `MYSQL_ATOMIC_UPDATE_THEN_REFRESH` 契约对齐；补上 internal token。
5. **监听器健壮性**：catch 所有异常后按类型分类（业务→ack，系统→nack/死信+告警）；设置合理 concurrency 与 prefetch。
6. **可观测与降级**：RestTemplate 加超时；Feign 加超时/熔断（如 sentinel）；360 查询失败不阻塞主链路（异步补偿）；confirm/return 失败写告警或补偿队列。
7. **清洁度**：统一 errorMsg 拼接方式（修 BlackClient 的枚举拼接 bug）；修 ExceptionEnums 重复 code 与错别字；删除死字段/空工具类/注释旧代码；统一 StringUtils 依赖来源；把 PhoneFormatCheckUtil 下沉到 common（或明确其归属）。

---

*报告生成方式：全部结论基于 read/grep 对源码的实际阅读；行号以当前工作区文件为准。*
