# Spring Cloud 短信平台三模块代码分析报告

> **分析对象**：`beacon-cloud/` 下的 `beacon-smsgateway`（28 个 Java 文件）、`beacon-push`（5 个）、`beacon-search`（12 个），共 45 个文件。
> **方法**：全部结论基于对源码的实际阅读（read/glob/grep），证据以 `文件路径:行号` 形式给出（路径相对于 `beacon-cloud/` 根目录）。
> **辅助上下文**：另阅读了 `beacon-common` 中的 `StandardSubmit`、`StandardReport`、`RabbitMQConstants`、`CacheKeyConstants`、`CMPP2ResultEnums`、`CMPP2DeliverEnums`，以及 `docs/13_beacon-search_es_analysis.md`、ES 种子数据。

---

## 1. 总体架构与消息流转

三个模块构成"网关下发 → 回执处理 → 检索展示"的短信后半链路：

```text
beacon-api/strategy  --(StandardSubmit)-->  queue:${gateway.sendtopic}
        |
        v
[beacon-smsgateway] SmsGatewayListener.consume
        |  1) MsgUtils.getSequence() 取流水号
        |  2) CmppStateStore.saveSubmit(seq, submit)  -> beacon-cache(Redis) 暂存
        |  3) NettyClient.submit(CmppSubmit)  -> 运营商 ISMG (CMPP2.0 over TCP)
        v
  运营商应答(SubmitResp) -> CMPPHandler -> cmppSubmitPool
        |  SubmitRepoRunnable:
        |     result=0: 构造 StandardReport, saveDeliver(msgId, report) 到缓存
        |     result!=0: submit.reportState=REPORT_FAIL
        |  一律 publish SMS_WRITE_LOG (搜索模块写 ES)
        v
  运营商回执(Deliver, Registered_Delivery=1) -> CMPPHandler -> cmppDeliverPool
        |  DeliverRunnable:
        |     takeDeliver(msgId) 取回 report
        |     stat=DELIVRD -> REPORT_SUCCESS, 否则 REPORT_FAIL + errorMsg
        |     (可选) 缓存开关 isCallback=1 -> 填 callbackUrl -> publish SMS_PUSH_REPORT
        |     publish SMS_GATEWAY_NORMAL_EXCHANGE (fanout)
        v
[beacon-push] PushReportListener(SMS_PUSH_REPORT)
        |  post "http://" + report.callbackUrl  (body=JSON report, 期望响应体 == "SUCCESS")
        |  失败 -> resendCount+1 -> 发往 push_delayed_exchange (x-delayed-message, 延迟 15s/30s/60s/300s)
        |  resendCount>=5 -> 静默丢弃并 ack
        v
[beacon-search]
   SMS_GATEWAY_NORMAL_QUEUE (TTL 10s, 无消费者) --过期--> SMS_GATEWAY_DEAD_EXCHANGE
        --> SMS_GATEWAY_DEAD_QUEUE
        v
   SmsUpdateLogListener -> ElasticsearchServiceImpl.update(index+year, sequenceId, {reportState})
        |  文档不存在且 reUpdate=false -> 重新投递回 normal queue 再试一次(TTL+DLX 延迟)
        |  reUpdate=true 再失败 -> 仅记 error 日志,丢弃
```

### 1.1 各模块文件清单与职责

| 模块 | 文件 | 职责 |
|---|---|---|
| smsgateway | `netty4/NettyStartCMPP.java` | 硬编码 CMPP 连接参数并创建 NettyClient Bean |
| | `netty4/NettyClient.java` | TCP 连接/提交/重连封装 |
| | `netty4/NettyClientInitializer.java` | pipeline 装配（帧解码/心跳/编解码/业务） |
| | `netty4/CMPPEncoder.java`、`CMPPDecoder.java` | CMPP 报文编解码 |
| | `netty4/CMPPHandler.java`、`HeartHandler.java` | 应答/回执业务分发、心跳与重连触发 |
| | `netty4/entity/*`（CmppMessageHeader/CmppConnect/CmppSubmit/CmppSubmitResp/CmppDeliver/CmppActiveTest/CmppActiveTestResp） | 报文实体与序列化 |
| | `netty4/utils/Command.java`、`MsgUtils.java` | 命令字常量、序列/时间戳/鉴权/字节工具 |
| | `mq/SmsGatewayListener.java` | 消费路由后的短信并下发 |
| | `runnable/SubmitRepoRunnable.java`、`DeliverRunnable.java` | SubmitResp / Deliver 二次处理 |
| | `config/ThreadPoolConfig.java` | hippo4j 动态线程池 |
| | `config/RabbitMQConfig.java` | 网关侧交换机/队列/TTL+DLX 声明 |
| | `config/CacheFeignAuthConfig.java` | cache 服务 Feign 签名拦截器 |
| | `client/BeaconCacheClient.java`、`CacheFacade.java`、`CmppStateStore.java` | 缓存访问门面、状态暂存 |
| | `util/SpringUtil.java` | 静态 ApplicationContext 工具 |
| | `controller/TestController.java`、`SmsGatewayStarterApp.java` | 调试端点、启动类 |
| push | `mq/PushReportListener.java` | 回执 HTTP 推送 + 延迟重试 |
| | `config/RabbitMQConfig.java` | x-delayed-message 交换机声明 |
| | `config/RestTemplateConfig.java`、`PushStarterApp.java` | RestTemplate Bean、启动类 |
| | `mq/PushReportListenerTest.java` | 5 个单测 |
| search | `config/RestHighLevelClientConfig.java` | ES 客户端 |
| | `config/RabbitConfig.java` | normal/dead 队列声明（与网关重复声明） |
| | `mq/SmsWriteLogListener.java`、`SmsUpdateLogListener.java` | 写日志/改状态消费者 |
| | `utils/SearchUtils.java` | 索引名 + ThreadLocal |
| | `service/SearchService.java` + `impl/ElasticsearchServiceImpl.java` | index/exists/update/查询/聚合 |
| | `controller/SmsSearchController.java`、`SearchStarterApp.java` | HTTP 查询接口、启动类 |
| | 3 个测试类（共 12 个单测） | |

---

## 2. beacon-smsgateway 详细分析

### 2.1 Netty CMPP 客户端结构

**入口与 Bean 装配**（`netty4/NettyStartCMPP.java`）

- `host=127.0.0.1`、`port=7890`、`serviceId="cz"`、`pwd="123"` 全部**硬编码为 static 字段**（11-17 行），不读配置中心/环境变量。
- `@Bean(initMethod = "start")`（21-25 行）：Spring 容器初始化阶段同步建立连接，`start()` 内部 `connect().sync()` 阻塞（`NettyClient.java:133`）。**CMPP 服务器不可达时应用启动直接失败**。

**Pipeline 装配**（`netty4/NettyClientInitializer.java:23-39`）

```
LengthFieldBasedFrameDecoder(MAX_VALUE,0,4,-4,0,true) → IdleStateHandler(0,0,20s)
→ HeartHandler → CMPPEncoder → CMPPDecoder → CMPPHandler
```

- `LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, -4, 0, true)`（26 行）：从偏移 0 读 4 字节总长（含长度字段自身），`-4` 调整、不剥离长度字段 → 返回完整帧（含 4 字节长度），与 `CMPPDecoder` 自行再读长度的逻辑一致（`CMPPDecoder.java:33-35`）。但 `maxFrameLength=Integer.MAX_VALUE` **无上限**，异常/恶意服务端声明超大长度时会持续堆积缓冲。
- 心跳：`IdleStateHandler(0, 0, 20)` 只监控 **ALL_IDLE**（31 行）；`HeartHandler` 中 `WRITER_IDLE` 分支永远不触发（`HeartHandler.java:33`）。

**连接与提交**（`netty4/NettyClient.java`）

- `submit(CmppMessageHeader)`（50-56 行）校验 `channel` 非空、open、active、writable 后 `writeAndFlush`，**返回 boolean**。
- `isActive()`（59-68 行）含 `isWritable()` 背压检查。
- `doConnect`（115-145 行）：每次调用 **新建 `NioEventLoopGroup(4)`**（118 行），从不 `shutdownGracefully()`；`bootstrap.handler()` 调用了两次（129 行 `LoggingHandler`、131 行 `NettyClientInitializer`），第二次**覆盖**第一次 → `LoggingHandler` 实际从未注册。
- 连接成功后立即发送 `CmppConnect`（138-139 行）；`CLOSE_ON_FAILURE` 监听器在 `sync()` 之后才添加（141 行），失败时能触发但语义已退化。

### 2.2 报文编解码细节

**消息头结构**（`entity/CmppMessageHeader.java:8-39`）

抽象基类，`totalLength(4) + commandId(4) + sequenceId(4)` 共 12 字节（大端，总长含头），子类各自实现 `toByteArray()` 完成整体序列化。`version` 字段只在 Connect 中使用。

**命令字与协议常量**（`netty4/utils/Command.java`）

- 完整列出 CMPP2.0 命令字（`CMPP_CONNECT=0x00000001` … `CMPP_GET_MO_ROUTE_RESP=0x80000017`，10-134 行）。
- `CMPP2_VERSION=(byte)32`（0x20）、`CMPP3_VERSION=(byte)48`（138-140 行）。

**序列号/时间戳/鉴权工具**（`netty4/utils/MsgUtils.java`）

- `getSequence()`：静态 `synchronized` 自增，超过 `MAX_VALUE/2` 后重置为 `currentTimeMillis` 的 int 值（21-34 行）。单实例可用；多实例部署时**序列号空间重叠**（网关目前单点，见 5.2）。
- `getTimestamp()`（41-44 行）：格式 `MMddhhmmss`——**用了 12 小时制 `hh`**，协议要求 24 小时制 `MMDDHHMMSS`，下午时段（12:00–23:59）生成的时间戳不符合协议（01–11），鉴权摘要将错误。
- `getAuthenticatorSource(spId, secret)`（53-62 行）：`MD5(spId + 9×'\0' + secret + timestamp)`，与 CMPP2.0 规范一致；明文用默认字符集（UTF-8，ASCII 场景无碍）。
- `getLenBytes`（90-107 行）：定长补 0（超长**静默截断**，无告警）。
- `bytesToInt/bytesToLong`（176-192 行）：手动大端转换。
- `DecodeUCS2/EncodeUCS2`（157-219 行）：实际未被 netty4 链路使用（死代码）。

**CMPPEncoder**（`netty4/CMPPEncoder.java`）

- `MessageToByteEncoder<Object>`、`preferDirect=false`（13-15 行）。
- 按类型分支写入 ByteBuf：`byte[]`/`Integer`/`Byte`/`Long` 原样写，**`String` 一律按 `UTF-16BE` 编码**（27-28 行）。CMPP 协议中不同字段编码不同（ASCII/GB2312/UCS2），这种"一刀切"的 String 编码只适用于短信内容，不适合作为通用编码规则。
- `CmppMessageHeader` 分支调用 `toByteArray()`（31-34 行）。

**CMPPDecoder**（`netty4/CMPPDecoder.java`）

- 从帧中再读 `totalLength`（33 行）截取本条消息，再读 `commandId`（38 行），按命令字分发（41-76 行）：
  - `CMPP_ACTIVE_TEST` → 直接 `writeAndFlush(new CmppActiveTestResp())`（45 行，从 decoder 位置发出会经 CMPPEncoder，链路正确）。
  - `CMPP_DELIVER` / `CMPP_SUBMIT_RESP` → 解析成对象加入 `list`（54-55、60-61 行）。
  - `CMPP_CONNECT_RESP` **只打日志，不校验 result 状态码**（67-71 行）——鉴权失败（如密码错误）时客户端毫不知情，继续提交短信。
  - `CMPP_DELIVER_RESP`（`Command.java:38` 已定义）**从未发送**——违反协议"收到 Deliver 必须应答"，ISMG 会按超时重发 Deliver，可能产生重复回执（当前靠缓存 pop 幂等兜底，见 2.5）。
  - 对 `totalLength` 无合法性校验：服务端声明 0~3 字节长度时，`ArrayUtils.subarray(buf,4,8)` 返回不足 4 字节，`MsgUtils.bytesToInt` 抛 `ArrayIndexOutOfBoundsException` → 通道异常关闭（可被恶意/异常服务端打挂连接）。
- `CmppActiveTestResp` 固定 `sequenceId=0`（`CmppActiveTestResp.java:23`），未回显收到的流水号。

**CmppConnect**（`entity/CmppConnect.java`）

- 固定 39 字节：头 12 + SourceAddr 6 + AuthenticatorSource 16 + Version 1 + Timestamp 4（25-42 行）。
- **Version 硬编码 `writeByte(1)`**（37 行），与 `Command.CMPP2_VERSION=0x20` 不一致。
- 头中 Sequence_Id 用 4 字节 int 写入（32 行，符合规范）。

**CmppSubmit**（`entity/CmppSubmit.java`）

- 构造时：`msgFmt=8`（UCS2，49、109 行），内容按 `UTF-16BE` 编码（112 行）；`registeredDelivery=1`（26 行，要求回执）；`srcId = srcId + "1630"`（108 行，**硬编码接入号后缀**）。
- `toByteArray()`（124-156 行）逐字段写 ByteBuf，声明总长 `130+8+21+msgContent.length`（127 行，=159+n，与实际写入字节数一致），但字段序列与 CMPP2.0 规范**不一致**：
  - 头后写 `writeLong(0)` 作为 Msg_Id（131 行）——**Msg_Id 恒为 0**，运营商按协议会在 SubmitResp/Deliver 中回显该值，所有短信的 msgId 关联键都将退化为 `"0"`（除非模拟服务器自行生成 msgId）。
  - 之后依次写 Pk_total(0)、Pk_number(0)、Registered_Delivery(1)、Msg_level(0)、Service_Id(10)、Fee_UserType(2)、Fee_terminal_Id(21)、Fee_terminal_type(0)、TP_pid(0)、**TP_udhi 位置写入了 8**（141 行，本应是 msgFmt 的值）、**缺少 Msg_Fmt 字段**，随后直接写 Msg_src。即 TP_udhi 被 msgFmt 值污染、Msg_Fmt 整字段缺失，其后所有定长字段相对协议整体**偏移 1 字节**。
  - `Msg_src` 写的是 `serviceId`（143 行）而非企业代码。
  - `msgLength` 用 `writeByte(msgContent.length)`（151 行）：**1 字节长度字段**，内容超过 255 字节（约 70 个汉字）时长度字段溢出、与实际内容不符 → 报文损坏。
  - 无长短信拆分：`pkTotal/pkNumber=0`（22-24 行）、`tp_udhi=0`（40 行），不支持拆包拼接。

**CmppSubmitResp**（`entity/CmppSubmitResp.java`）

- 解析 `sequenceId`（bytes 8-12）、`msgId`（bytes 12-20）、`result`（bytes[20]，20-23 行）。
- `msgId = Math.abs(msgId)`（22 行）：**CMPP 规范中 msgId 最高位为 1 表示运营商侧错误**，取绝对值后该信息丢失；且 `Math.abs(Long.MIN_VALUE)` 仍为负。

**CmppDeliver**（`entity/CmppDeliver.java`）

- 构造函数开头 `System.arraycopy(data, 12, data, 0, data.length - 12)`（47 行）：重叠拷贝把整包**左移 12 字节**以跳过消息头，随后按 0 偏移解析。依赖 `CMPPDecoder.java:35` 的 `ArrayUtils.subarray` 返回的是新数组（`CMPPDecoder.java:35`），否则会破坏共享缓冲。写法危险且晦涩。
- 按 `Registered_Delivery` 区分（73-96 行）：
  - =1 状态报告：Msg_Content 固定 60 字节（8+7+10+10+21+4，73-74 行），解析 `Msg_Id_DELIVRD`（`Math.abs`，84 行）、`Stat`(7)、`Submit_time`(10)、`Done_time`(10)、`Dest_terminal_Id`(21)、`SMSC_sequence`(4)。
  - =0 上行短信：仅解出内容，**业务侧只打日志**（`CMPPHandler.java:50-55`），上行短信没有进入任何业务/消息通道。
- **状态报告字段的字符集取决于 Msg_Fmt**（85-88 行）：`Msg_Fmt==8 ? UTF-16BE : gb2312`。状态报告内容按规范是 ASCII，若运营商下发的回执 Msg_Fmt=8，`Stat` 用 UTF-16BE 解 7 字节（奇数长度）会产生乱码 → `"DELIVRD"` 比对失败 → 回执被误判为失败。内容字段同理（94 行）。
- 异常均收敛为 `result=1` 并记日志（101-118 行），但 `CMPPHandler` 未检查 `result` 字段。

### 2.3 连接管理与重连机制

- 触发点：`HeartHandler.channelInactive` → `client.reConnect(10)`（`HeartHandler.java:44-48`），最多重试 10 次。
- `NettyClient.reConnect`（`NettyClient.java:76-107`）：循环体内 `!isActive()` 时调 `start()`（内部 `connect().sync()` **阻塞当前线程**）。由于 `channelInactive` 在 **Netty I/O 线程**上回调，重连期间该 EventLoop 线程被阻塞；服务器无响应时 `connect().sync()` 可能阻塞数十秒（取决于 OS SYN 超时），期间该线程无法处理任何 I/O。失败后 `Thread.sleep(10*1000)` 同样发生在 I/O 线程上。
- 每次 `doConnect` 新建 `NioEventLoopGroup(4)` 且永不关闭（118 行）：每轮断线重连最多泄漏 4×10=40 个线程；多次断线无界累积 → **线程泄漏**。
- 心跳只依赖 ALL_IDLE 20 秒（`NettyClientInitializer.java:31`），且**不校验 ActiveTest 应答**（`CMPPDecoder.java:47-50` 只打日志）：
  - 持续有下行流量的场景 ALL_IDLE 永不触发；
  - 即使触发并发出 ActiveTest，无应答也不判定连接失效 → 半开连接上继续"成功"提交，直到 TCP 层最终报错。
- `channel` 字段非 volatile（`NettyClient.java:21`），MQ 消费线程读、重连路径写，存在可见性隐患。
- 连接为**单通道单点**：无连接池、无备链路，吞吐受限于单条 TCP 连接；网关实例也仅一个。

### 2.4 MQ 消费路由消息后如何下发

`mq/SmsGatewayListener.java`

- `@RabbitListener(queues = "${gateway.sendtopic}")`（33 行）消费 `StandardSubmit`（JSON，依赖模块内注册的 `Jackson2JsonMessageConverter`，`RabbitMQConfig.java:69-72`；`StandardSubmit.sendTime` 带 `@JsonDeserialize` 注解保证 LocalDateTime 反序列化）。
- 下发三步（36-48 行）：
  1. `MsgUtils.getSequence()` 取流水号（42 行）；
  2. `cmppStateStore.saveSubmit(sequence, submit)` 把 submit 存入 beacon-cache（46 行，TTL 默认 600s）；
  3. `nettyClient.submit(cmppSubmit)`（48 行）——**忽略返回值**。连接断开时 `submit` 返回 false，消息仍被 `basicAck`（58 行）→ **短信静默丢失**（无重试、无补偿、无状态记录）。
- 手动 ack、`@RabbitListener` 未设置并发/prefetch，默认单消费者线程、预取默认值。

`config/RabbitMQConfig.java`

- 声明 `SMS_GATEWAY_NORMAL_EXCHANGE`(fanout) → `SMS_GATEWAY_NORMAL_QUEUE`（`x-message-ttl=10s`、`x-dead-letter-exchange=SMS_GATEWAY_DEAD_EXCHANGE`、routing key ""，28-45 行）→ `SMS_GATEWAY_DEAD_QUEUE`（48-58 行）。**用"TTL 10 秒 + 死信"充当延迟 10 秒投递**（给 ES 写入留时间），队列本身无消费者。
- 声明监听队列 `${gateway.sendtopic}`（63-66 行）避免容器启动时队列不存在反复重试。
- 注意：normal/dead 队列在 `beacon-search` 的 `RabbitConfig.java:26-57` **重复声明**，两处参数必须完全一致（当前一致：TTL 均 10000），否则 broker 会报 PRECONDITION_FAILED。

### 2.5 回执（Deliver）处理

**第一次响应 SubmitResp**（`runnable/SubmitRepoRunnable.java`）

- 由 `CMPPHandler.channelRead0` 提交到 `cmppSubmitPool` 执行（`CMPPHandler.java:34-35`）。
- 流程（36-62 行）：
  1. `cmppStateStore.takeSubmit(sequenceId)` 原子取回暂存的 submit（39 行；取不到仅 warn 并 return，**该短信从此无 ES 记录**——因为写 ES 也依赖此步骤）；
  2. `result != 0` → `submit.reportState=REPORT_FAIL`、`errorMsg=resultMessage`（47-52 行，码表见 `CMPP2ResultEnums`）；`result == 0` → 复制出 `StandardReport` 并按 `submitResp.getMsgId()` 存入缓存待回执关联（54-59 行）；
  3. 无条件发 `SMS_WRITE_LOG`（61 行）→ 搜索模块写 ES。
- **失败语义缺口**：SubmitResp 直接失败的短信不会走"推送回调"（回调只在 Deliver 链路触发），客户拿不到失败通知。

**第二次响应 Deliver（状态报告）**（`runnable/DeliverRunnable.java`）

- `CMPPHandler` 中 `registered_delivery==1` 时提交到 `cmppDeliverPool`（`CMPPHandler.java:41-48`）。
- 流程（39-67 行）：
  1. `takeDeliver(msgId)` 取回 report（41 行；取不到仅 warn——重复 Deliver 由该 pop 幂等吸收）；
  2. `stat == "DELIVRD"` → `REPORT_SUCCESS`，否则 `REPORT_FAIL` + `CMPP2DeliverEnums.descriptionOf(stat)`（48-53 行）；
  3. 缓存开关开启且回调地址非空 → `isCallback=1`、填 `callbackUrl`、发 `SMS_PUSH_REPORT`（56-63 行）；
  4. 发 `SMS_GATEWAY_NORMAL_EXCHANGE`（routing key ""，66 行）→ 经 10s TTL+DLX 延迟后由搜索模块更新 ES。
- **脆弱点**：第 3 步的缓存查询是 **Feign 同步远程调用**（`CacheFacade → BeaconCacheClient`，`client/CacheFacade.java:18-25`），cache 服务不可用或返回非 0 code（`BeaconCacheClient.java:54-55` 抛 `IllegalStateException`）时，Runnable 内异常未被捕获 → **第 4 步不执行** → 最终状态既不更新 ES、也不推送回调（且异常只在工作线程内无声消失，线程池 `execute` 吞掉后仅剩默认未捕获处理器）。SubmitRepoRunnable 的缓存调用同理（`SubmitRepoRunnable.java:39`）。

**状态暂存**（`client/CmppStateStore.java`）

- 经 beacon-cache Feign 存 Redis：`cmpp:submit:{seq}` TTL 600s、`cmpp:deliver:{msgId}` TTL 86400s（19-28 行）；`take*` 用 `popString`（DeleteMapping，`BeaconCacheClient.java:30-31`）取走即删，实现"原子取走"语义（依赖 cache 服务端实现原子性）。
- **无超时兜底**：SubmitResp 一直不来 → key 600s 后过期，短信永久停留在 ES 的"waiting(0)"状态，没有任何机制把超时短信置为失败。

### 2.6 hippo4j 动态线程池与 ThreadPoolConfig

`config/ThreadPoolConfig.java`

- 两个 `@DynamicThreadPool` Bean：`cmppSubmitPool`（threadPoolId `cmpp-submit`）与 `cmppDeliverPool`（`cmpp-deliver`），用 `ThreadPoolBuilder.dynamicPool()` 构建（17-42 行）。
- **未显式设置 core/max/队列容量/拒绝策略**，全部依赖 hippo4j 配置中心的参数（`SmsGatewayStarterApp` 上 `@EnableDynamicThreadPool`，`SmsGatewayStarterApp.java:16`）；若配置中心无对应参数，容量与拒绝策略不可控。
- 使用方式：`CMPPHandler` 每次消息通过 `SpringUtil.getBeanByName("cmppSubmitPool")` 取池（`CMPPHandler.java:34、47`）；`SubmitRepoRunnable`/`DeliverRunnable` 在字段初始化时经 `SpringUtil.getBeanByClass` 取 RabbitTemplate/CacheFacade/CmppStateStore（`SubmitRepoRunnable.java:23-25`、`DeliverRunnable.java:21-25`）。
- **拒绝策略隐患**：线程池满时 `execute` 抛 `RejectedExecutionException`，异常沿 `CMPPHandler.channelRead0` 向上传播 → Netty `exceptionCaught` 默认关闭通道 → **回执风暴/大流量时 CMPP 连接被池拒绝拖垮**。

### 2.7 SpringUtil

`util/SpringUtil.java`

- `ApplicationContextAware` 静态持有上下文（15-20 行），提供 `getBeanByName/getBeanByClass`（22-28 行）。
- 优点：让 Netty 处理器、Runnable 这些非 Spring 管理对象能取 Bean。缺点：静态全局状态、与生命周期强耦合（初始化顺序敏感）、Runnable 类无法脱离 Spring 上下文做纯单测（push 模块用 `ReflectionTestUtils` 注入依赖做单测，而这两个 Runnable 无法如此测试）。

---

## 3. beacon-push 详细分析

### 3.1 回执推送逻辑

`mq/PushReportListener.java`

- `consume`（54-65 行）：监听 `SMS_PUSH_REPORT`；`callbackUrl` 为空 → ack 并返回（58-62 行）；否则 trim 后 `process`。
- `delayedConsume`（75-78 行）：监听 `push_delayed_queue`，与 `consume` 共用 `process`。
- `process`（80-89 行）：`pushReport` → `isResend` → 无条件 `basicAck`（失败也 ack，靠重发消息实现重试）。
- `pushReport`（97-119 行）：
  - `postForObject("http://" + report.getCallbackUrl(), JSON(report), String.class)`（108 行）：**强制 http**（不支持 https）、响应体必须**完全等于** `"SUCCESS"` 才算成功（109 行，契约脆弱）。
  - 序列化失败/网络异常/响应非 SUCCESS 均返回 false（110-116 行）。
- `isResend`（126-144 行）：失败则 `resendCount+1`（129 行）；`resendCount >= 5` 直接 return（130-132 行）→ **重试 5 次（1 次首发 + 4 次重试）后静默丢弃**，无死信队列、无告警、无最终失败记录（只有 info 日志）。
- 延迟取 `delayTime[report.getResendCount()]`（137 行）：第 1 次重试 15s、第 2 次 30s、第 3 次 60s、第 4 次 300s（数组定义 36 行：`{0,15000,30000,60000,300000}`）。

### 3.2 延迟重试（x-delayed-message）

`config/RabbitMQConfig.java`

- `CustomExchange(DELAYED_EXCHANGE, "x-delayed-message", false/*durable*/, false/*autoDelete*/, args)`，args 含 `x-delayed-type=fanout`（30-35 行）；`push_delayed_queue` 为 durable 队列（38-40 行）；binding routing key ""（43-45 行）。
- 重试消息发往该交换机并在 `MessagePostProcessor` 中 `setDelay(delayTime[...])`（`PushReportListener.java:133-140`）。
- 依赖 RabbitMQ **`rabbitmq_delayed_message_exchange` 插件**，未安装时启动/声明即失败。
- 注意：**交换机非 durable**（33 行第 1 个 false），broker 重启后依赖应用启动时重新声明（可恢复，但 broker 宕机期间发往该交换机的消息会丢失）。

### 3.3 RestTemplateConfig

`config/RestTemplateConfig.java`

- 纯 `new RestTemplate()`（15-17 行）：**无 connect/read 超时**（默认无限等待）、无连接池调优、无错误处理器。
- 后果：客户回调地址不可达/挂起时，**消费者线程被无限期阻塞**；`@RabbitListener` 默认单线程并发（consume 与 delayedConsume 各一个容器），一个坏地址即可阻塞整条推送队列（队头阻塞）。

### 3.4 回调 URL 从哪来

链路：`DeliverRunnable`（网关，回执时）→ `CacheFacade.getClientCallbackUrl(apiKey)`（`client/CacheFacade.java:23-25`）→ `BeaconCacheClient.hget(client_business:{apiKey}, "callbackUrl")`（`BeaconCacheClient.java:20-24`、`CacheKeyConstants.CALLBACK_URL="callbackUrl"`）→ beacon-cache 服务读 Redis hash。开关 `isCallback` 同理（`CacheKeyConstants.IS_CALLBACK`）。拿到后写入 `report.callbackUrl/isCallback`，随 `SMS_PUSH_REPORT` 消息进入 push 模块（`DeliverRunnable.java:56-62`）。

- 即：**回调地址由客户业务配置（缓存）决定**，不是请求参数，也不是网关配置。
- push 模块 `consume` 只判断 `callbackUrl` 非空（58 行），未校验 `isCallback` 标志。
- **SSRF 风险**：`callbackUrl` 完全由客户配置，push 模块无条件向该地址发起 POST（`PushReportListener.java:108`），可指向内网地址/敏感端口，且强制 http、无出站白名单。

---

## 4. beacon-search 详细分析

### 4.1 ES 索引结构（字段映射）

- **代码中没有显式 index mapping / index template**（12 个文件全部读完，无 `PutMappingRequest`、无模板文件），字段映射完全依赖 ES **动态映射**。
- 索引名：`sms_submit_log_{当前年份}`（`utils/SearchUtils.java:16`、`22-24`），文档 id = `sequenceId`（`SmsWriteLogListener.java:41`）。
- 实际入库字段（`StandardSubmit` JSON，`SmsWriteLogListener.java:32-40` + 种子数据 `backups/es/*.ndjson`）：`sequenceId, clientId, ip, uid, mobile, sign, text, sendTime, sendTimeMillis, fee, operatorId, areaCode, area, srcNumber, channelId, reportState, errorMsg, realIp, apiKey, state, signId, isTransfer, oneHourLimitMilli`。
- 类型推断（动态映射）：`mobile/text/sign` 等字符串 → `text`（标准分词器）+`.keyword` 子字段；`clientId/reportState/sequenceId` → `long`；`sendTime` 旧数据曾以 LocalDateTime 数组形式存为 list（`ElasticsearchServiceImpl.java:336-341` 的 legacy 兼容解析印证），新数据统一写 `sendTimeMillis`（`SmsWriteLogListener.java:33-40`）。
- **查询字段与存储字段不一致的风险**：`mobile` 用 `prefixQuery` 打在 text 字段上（`ElasticsearchServiceImpl.java:263`），受分词器影响；无显式 mapping 时不同环境的分词器版本差异会改变匹配行为。
- 无 shards/replicas/生命周期（ILM）配置，历史索引靠手工管理；按年分片但查询只走当年索引（见 4.6）。

### 4.2 RestHighLevelClientConfig

`config/RestHighLevelClientConfig.java`

- `elasticsearch.hostAndPorts` 逗号分隔多节点，`split(":")` 拆 host/port（20-37 行）：不支持 IPv6、无 trim（配置带空格即 `NumberFormatException`）。
- 用户名/密码 basic auth（40-45 行）。
- **未配置任何超时/重试/连接池参数**（44-48 行）：默认 socketTimeout 30s、connectTimeout 1s（HTTP Client 默认），无 `setMaxConnTotal/PerRoute`、无 `RetryListener`；ES 抖动时请求阻塞线程。
- 使用已弃用的 `RestHighLevelClient`（ES 7.16 起弃用，8.x 移除），升级 ES 8 需整体替换为 `ElasticsearchClient`。

### 4.3 SmsWriteLogListener 消费逻辑

`mq/SmsWriteLogListener.java`

- 监听 `SMS_WRITE_LOG`（28 行），`JsonUtil.toMap(submit)` 转文档；`sendTime` 非空时转 epoch millis 并同时写 `sendTime`、`sendTimeMillis` 两个字段（33-40 行）。
- `searchService.index(INDEX + getYear(), sequenceId, json)`（41 行）→ ack（45 行）。
- 年份用 `LocalDateTime.now()`（服务器本地时钟），跨年瞬间（12/31 23:59:59 消费 1/1 00:00:00 的消息）会写进新年索引。
- **幂等缺陷（重试风暴入口）**：`ElasticsearchServiceImpl.index` 要求返回 `created`，否则抛 `SearchException`（`ElasticsearchServiceImpl.java:74-79`）。消息重投（消费者崩溃、ack 丢失、网络抖动）时同 id 文档返回 `updated` → 抛异常 → 手动 ack 模式下消息被 requeue → **无限重试该消息**，阻塞队列头。

### 4.4 SmsUpdateLogListener 与死信队列状态更新

`mq/SmsUpdateLogListener.java`

- 监听 `SMS_GATEWAY_DEAD_QUEUE`（25 行）——即"网关 → normal exchange → TTL 10s 队列 → DLX → dead 队列"的延迟 10 秒通道。
- `SearchUtils.set(report)` 把 report 放进 ThreadLocal（29 行）→ `searchService.update(INDEX+year, sequenceId, {reportState})`（31-33 行）→ ack（36 行）。
- **只更新 `reportState`**：`errorMsg` 未写入 ES（33 行 doc 仅 reportState），最终失败原因在 ES 中查询不到。
- 年份同样取"当前年份"（33 行）：**跨年边界时，12/31 的短信回执在 1/1 到达 → 更新目标为新年索引 → 文档不存在 → 触发一次重投后仍失败 → 状态更新丢失**。
- ThreadLocal 泄漏：`SearchUtils.remove()` 只在 update 的"文档不存在"分支调用（`ElasticsearchServiceImpl.java:115`），成功/异常路径**不清理**；靠下一条消息 `set` 覆盖兜底（监听线程池固定，功能上暂不串数据，但存在泄漏与交叉污染隐患）。

`service/impl/ElasticsearchServiceImpl.java` update（99-139 行）

- `exists` 先查（102 行）→ 不存在且 `reUpdate=false` → 置 `reUpdate=true` 并 `rabbitTemplate.convertAndSend(SMS_GATEWAY_NORMAL_QUEUE, report)`（113 行：**两参重载，routing key=队列名、走默认交换机**）→ 消息进 10s TTL 队列再经 DLX 回到 dead 队列重试一次。
- 第二次（`reUpdate=true`）仍不存在 → 仅 error 日志，更新被丢弃（107-108 行）。
- 更新成功判定：`UPDATED || NOOP` 都算成功（132 行，比 index 的判定合理）；其他结果抛异常 → 不 ack → requeue（**若文档存在但 update 抛异常（如 ES 短暂故障），会无限 requeue 重试**）。
- 该"TTL+DLX 当延迟队列"机制依赖 `SMS_GATEWAY_NORMAL_QUEUE` **永远没有消费者**；一旦未来有人消费该队列，整个状态更新链路的延迟语义被破坏。

### 4.5 SearchUtils

`utils/SearchUtils.java`

- `INDEX="sms_submit_log_"`（16 行）、`getYear()` 当前年份（22-24 行）。
- `ThreadLocal<StandardReport>`（32-41 行）用于把 MQ 消息上下文传进 service 层（`reUpdate` 判定），属于**隐式跨层传参**：service 层方法签名看不出依赖，多线程/异步化即失效。

### 4.6 SmsSearchController 接口与查询实现

`controller/SmsSearchController.java`

- `POST /search/sms/list`（23-30 行）：分页列表；`POST search/sms/countSmsState`（32-35 行）：状态聚合（饼图）。
- 参数为弱类型 `Map<String,Object>`，**接口无任何认证/授权/租户校验**——调用方不传 `clientID` 时可查询**全部客户**的短信记录（手机号、内容、apiKey 均暴露），数据隔离完全依赖上游网关（若有）。

`ElasticsearchServiceImpl.findSmsByParameters`（142-183 行）

- 只查当年索引（143 行）；`from/size` 直接 `Integer.parseInt`（151-156 行）：非法值 → `NumberFormatException`（500）；无上限约束，`from+size > 10000`（ES max_result_window）→ ES 报错。
- 结果逐条 `row.put("corpname", row.get("sign"))`（169 行，前端显示用签名冒充企业名，注意 `StandardSubmit` 无 corpname 字段）；高亮 `text`（170-174 行），高亮碎片直接替换原 text。
- `countSmsState`（188-231 行）：`size(0)` + `reportState` terms 聚合（196-201 行）突破 10000 条限制，返回 success(1)/fail(2)/waiting(0 及其他)（209-230 行）。
- `buildBoolQuery`（236-284 行）：
  - `content` → `matchQuery("text", content)`（249 行）：match 查询是安全的（非 query_string，无语法注入面）；
  - `mobile` → `prefixQuery("mobile", ...)`（263 行）：打在被动态映射为 text 的字段上，分词行为影响前缀匹配效果；
  - `starttime/stoptime` → `rangeQuery("sendTimeMillis").gte/lte(Long.parseLong(...))`（267-276 行）：非法值抛异常；
  - `clientID` 支持标量或 List（286-319 行解析），**仅在传入时才加过滤条件**（279-281 行）——再次印证跨租户查询风险。
- 时间解析兜底 `resolveSendTimeStr/listToDateString`（321-393 行）兼容历史 LocalDateTime 数组文档，健壮性较好（异常吞并返回空串）。

`config/RabbitConfig.java`

- 与网关重复声明 normal/dead 交换机队列（26-57 行），参数必须与网关侧完全一致（当前均为 TTL 10000、DLX fanout、routing key ""）。

---

## 5. 值得注意的设计点与潜在问题（证据清单）

### 5.1 协议硬编码与协议实现缺陷

| # | 问题 | 证据 |
|---|---|---|
| P1 | CMPP 服务器地址/账号/密码硬编码在代码中 | `beacon-smsgateway/.../netty4/NettyStartCMPP.java:11-17` |
| P2 | CmppSubmit 序列化缺 Msg_Fmt 字段、TP_udhi 位置写入 8，字段整体错位 1 字节，不符合 CMPP2.0 | `netty4/entity/CmppSubmit.java:139-153`（对照 `Command.java:142-144` 的长度常量） |
| P3 | Submit 的 Msg_Id 恒为 0，SubmitResp/Deliver 回显后所有 msgId 关联键退化为 "0" | `CmppSubmit.java:131` |
| P4 | msgLength 用 1 字节承载内容长度，>255 字节溢出；无长短信拆分（pkTotal/pkNumber/tp_udhi 全 0） | `CmppSubmit.java:22-24,40,151` |
| P5 | 鉴权时间戳用 12 小时制 `MMddhhmmss`，下午时段与协议不符 | `netty4/utils/MsgUtils.java:42` |
| P6 | Connect 版本字节硬编码 1，与 CMPP2_VERSION=0x20 不一致 | `CmppConnect.java:37` vs `Command.java:138` |
| P7 | CONNECT_RESP 不校验 result，鉴权失败无感知 | `CMPPDecoder.java:67-71` |
| P8 | 收到 Deliver 从不回 Deliver_Resp，违反协议，可能引发 ISMG 重发 | `CMPPDecoder.java:51-56`（`Command.java:38` 定义了 0x80000005 却无引用） |
| P9 | SubmitResp msgId 取 Math.abs，丢失"最高位=1 表错误"语义 | `CmppSubmitResp.java:22`；Deliver 同样处理 `CmppDeliver.java:84` |
| P10 | CmppDeliver 构造时重叠左移拷贝入参数组，依赖调用方先复制，写法危险 | `CmppDeliver.java:47`（配合 `CMPPDecoder.java:35`） |
| P11 | 状态报告字段按 Msg_Fmt 选 UTF-16BE/gb2312，回执 ASCII 内容遇 Msg_Fmt=8 会乱码导致误判失败 | `CmppDeliver.java:85-88` |
| P12 | Encoder 对 String 一律 UTF-16BE，非通用 CMPP 编码规则 | `CMPPEncoder.java:27-28` |
| P13 | 源号码硬编码追加 "1630"、Msg_src 误用 serviceId | `CmppSubmit.java:108,143` |
| P14 | 上行短信（MO）仅打日志，无业务通道 | `CMPPHandler.java:50-55` |

### 5.2 连接与线程问题

| # | 问题 | 证据 |
|---|---|---|
| P15 | 每次重连新建 NioEventLoopGroup(4) 且不关闭 → 线程泄漏（每轮断线最多 40 线程） | `NettyClient.java:118`（`doConnect` 由 `reConnect`/`start` 反复调用） |
| P16 | channelInactive 在 Netty I/O 线程上同步阻塞重连（connect().sync + sleep 10s） | `HeartHandler.java:44-48` → `NettyClient.java:76-107,133` |
| P17 | CMPP 不可达时 Bean initMethod 启动失败 → 应用无法启动 | `NettyStartCMPP.java:21-25` + `NettyClient.java:133` |
| P18 | 心跳只看 ALL_IDLE 且不校验 ActiveTest 应答，半开连接无法及时发现 | `NettyClientInitializer.java:31`、`CMPPDecoder.java:47-50` |
| P19 | LoggingHandler 被后续 handler 覆盖，实际未注册 | `NettyClient.java:129-131`（两次调用 `bootstrap.handler`） |
| P20 | LengthFieldBasedFrameDecoder maxFrameLength=Integer.MAX_VALUE，无帧长上限 | `NettyClientInitializer.java:26` |
| P21 | CMPPDecoder 对声明长度过小(0~3)的帧无防御，bytesToInt 抛 AIOOBE 打挂通道 | `CMPPDecoder.java:33-38` + `MsgUtils.java:187-192` |
| P22 | channel 字段非 volatile，跨线程可见性无保证 | `NettyClient.java:21,50-56` |
| P23 | 连接单通道单点，无池化/备链路 | `NettyClient.java:20-21`、`NettyStartCMPP.java:22-25` |

### 5.3 消息可靠性问题（丢消息/状态缺失）

| # | 问题 | 证据 |
|---|---|---|
| P24 | 下发时忽略 submit() 返回值，连接断开时消息被 ack 后静默丢失 | `SmsGatewayListener.java:48,58` |
| P25 | SubmitResp 未到 → submit 缓存 600s 过期 → 短信永久 waiting、永无 ES 记录/失败通知（无超时兜底任务） | `CmppStateStore.java:24`、`SubmitRepoRunnable.java:39-44` |
| P26 | SubmitResp 直接失败不触发客户回调（回调只在 Deliver 链路） | `SubmitRepoRunnable.java:47-62` vs `DeliverRunnable.java:56-63` |
| P27 | DeliverRunnable 中缓存 Feign 调用抛异常 → 最终状态既不更新 ES 也不推送（第 4 步被跳过），异常在工作线程无声消失 | `DeliverRunnable.java:56-66`、`BeaconCacheClient.java:54-55` |
| P28 | ES 更新只写 reportState，errorMsg 不入库 | `SmsUpdateLogListener.java:31-33` |
| P29 | 回调最终失败（5 次）静默丢弃，无死信/告警 | `PushReportListener.java:126-144` |
| P30 | 跨年边界：写入/更新按"当前年"索引，12/31↔1/1 的消息写入与更新错位，更新重试一次后仍丢失 | `SmsWriteLogListener.java:41,48-50`、`SmsUpdateLogListener.java:33`、`SearchUtils.java:22-24` |

### 5.4 重试风暴 / 无限重试

| # | 问题 | 证据 |
|---|---|---|
| P31 | ES index 幂等判定错误（要求 created）：消息重投返回 updated → 抛异常 → 手动 ack 模式 requeue → 无限重试 | `ElasticsearchServiceImpl.java:74-79` + `SmsWriteLogListener.java:45` |
| P32 | ES update 抛异常（如 ES 故障）→ 不 ack → 无限 requeue；`exists→update` 间还有竞态 | `ElasticsearchServiceImpl.java:126,137` |
| P33 | push 无超时 RestTemplate：坏回调地址阻塞消费者线程，默认单线程并发 → 队头阻塞 | `RestTemplateConfig.java:15-17`、`PushReportListener.java:108` |
| P34 | "TTL 10s + DLX" 当延迟队列，语义依赖 normal 队列永无消费者；重试二次失败即丢弃 | `RabbitMQConfig.java:33-40`（网关）、`RabbitConfig.java:30-37`（搜索）、`ElasticsearchServiceImpl.java:107-115` |
| P35 | 回执风暴下线程池 execute 抛 RejectedExecutionException 沿 Netty 传播 → 通道被关闭 | `CMPPHandler.java:34-35,47-48`、`ThreadPoolConfig.java:17-42` |

### 5.5 ES 查询安全与健壮性

| # | 问题 | 证据 |
|---|---|---|
| P36 | 查询接口无认证/授权/租户隔离，不传 clientID 即查全量客户数据（手机号/内容/apiKey） | `SmsSearchController.java:23-35`、`ElasticsearchServiceImpl.java:279-281` |
| P37 | from/size 无上限，from+size>10000 报错；非法值 NumberFormatException 500 | `ElasticsearchServiceImpl.java:151-156` |
| P38 | 无显式索引 mapping（动态映射），mobile/text 的分词行为不可控 | 全模块无 mapping 代码；`ElasticsearchServiceImpl.java:249,263` |
| P39 | RestHighLevelClient 无超时/连接池配置，且 API 已被 ES 弃用 | `RestHighLevelClientConfig.java:44-48` |
| P40 | 查询只覆盖当年索引，跨年查询/统计缺失（docs 亦指出） | `ElasticsearchServiceImpl.java:143,189`、`docs/13_beacon-search_es_analysis.md:37,342` |
| P41 | ThreadLocal 传递 reUpdate 上下文，仅失败分支 remove，泄漏/污染隐患 | `SearchUtils.java:32-45`、`ElasticsearchServiceImpl.java:115` |

### 5.6 其他设计与运维点

| # | 问题 | 证据 |
|---|---|---|
| P42 | push 回调强制 http、URL 完全由客户配置 → SSRF 风险 | `PushReportListener.java:108`、`CacheFacade.java:23-25` |
| P43 | push 成功判定依赖响应体精确等于 "SUCCESS"，契约脆弱 | `PushReportListener.java:109` |
| P44 | x-delayed-message 交换机非 durable，依赖插件 | `push/RabbitMQConfig.java:23,33` |
| P45 | normal/dead 队列在网关与搜索两个模块重复声明，参数需严格一致 | `smsgateway/RabbitMQConfig.java:27-58` vs `search/RabbitConfig.java:25-57` |
| P46 | SpringUtil 静态上下文 + Runnable 字段初始化取 Bean，不可脱离容器测试 | `SpringUtil.java:15-28`、`SubmitRepoRunnable.java:23-25` |
| P47 | hippo4j 线程池未显式配置容量/拒绝策略，完全依赖配置中心 | `ThreadPoolConfig.java:18-42` |
| P48 | 序列号 static synchronized，多实例部署时全局流水号重叠（当前单点） | `MsgUtils.java:21-34` |
| P49 | 遗留调试端点 /test 直连生产线程池执行任务 | `TestController.java:21-27` |

**做得好的点**：① push 模块 5 个单测覆盖空 URL/成功/重试延迟/最大次数/序列化失败（`PushReportListenerTest.java`）；② search 模块 12 个单测覆盖写入、幂等异常、查询构建、时间兼容（`ElasticsearchServiceImplTest.java` 等）；③ 回执幂等用缓存"pop 取走"吸收重复 Deliver；④ `countSmsState` 用 size(0)+聚合避开 10000 限制；⑤ 网关监听队列提前声明避免容器反复重试；⑥ 日志上下文（channelId/sequenceId/msgId）较完整。

---

## 6. 总结

- **beacon-smsgateway**：以单 Netty 长连接对接 CMPP2.0 的完整链路（MQ→Submit→SubmitResp→Deliver→MQ 反馈），状态经 beacon-cache 做进程外暂存，属于务实的设计；但协议序列化存在多处硬编码与规范偏差（缺 Msg_Fmt、Msg_Id=0、12 小时时间戳）、连接管理存在线程泄漏与 I/O 线程阻塞、下发失败无补偿，可靠性风险集中在本模块。
- **beacon-push**：延迟重试（x-delayed-message）思路清晰、单测完善；主要风险是 RestTemplate 无超时导致的队头阻塞、重试耗尽后静默丢弃、回调 URL 的 SSRF 面。
- **beacon-search**：读写查询闭环完整、聚合统计绕过了 ES 分页上限；核心问题是无显式 mapping、index 幂等判定错误导致的无限重试、跨年索引错位、查询接口无租户隔离。
- **最优先建议**：① 修复 CmppSubmit 序列化与鉴权时间戳；② 检查 submit() 返回值并对失败消息做 nack/补偿；③ 修正 ES index 幂等判定（接受 updated/noop）；④ 给 RestTemplate 加超时并把回调失败引入死信告警；⑤ 查询接口加租户过滤与分页上限。
