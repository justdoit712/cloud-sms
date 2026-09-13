# CMPP 2.0 报文规格（重写版网关实现规范）

> 用途：**R7 beacon-smsgateway 重写时的协议实现规范**。旧实现缺陷（分析报告 P1~P25）不"后处理"，重写时直接按本规格写对。
> 来源：旧项目 `netty4/entity/` 实体类逆向 + CMPP 2.0 协议规范核对 + `../analysis/report_gateway_push_search.md` 缺陷清单。
> 状态：🟡 依据旧实现逆向整理，与真实运营商对接前仍需以运营商协议文档最终核对（文末"待核对清单"）。

---

## 1. 帧结构

所有报文以 **12 字节消息头**开头（大端序）：

| 偏移 | 字段 | 长度 | 说明 |
| --- | --- | --- | --- |
| 0 | totalLength | 4 | 报文总长度（含消息头） |
| 4 | commandId | 4 | 命令字 |
| 8 | sequenceId | 4 | 消息流水号（应答必须回显） |

## 2. 命令字与版本

| 命令 | 值 |
| --- | --- |
| CMPP_CONNECT / CONNECT_RESP | 0x00000001 / 0x80000001 |
| CMPP_TERMINATE / TERMINATE_RESP | 0x00000002 / 0x80000002 |
| CMPP_SUBMIT / SUBMIT_RESP | 0x00000004 / 0x80000004 |
| CMPP_DELIVER / DELIVER_RESP | 0x00000005 / 0x80000005 |
| CMPP_QUERY / QUERY_RESP | 0x00000006 / 0x80000006 |
| CMPP_CANCEL / CANCEL_RESP | 0x00000007 / 0x80000007 |
| CMPP_ACTIVE_TEST / ACTIVE_TEST_RESP | 0x00000008 / 0x80000008 |

版本字节：`0x20`（CMPP 2.0）/ `0x30`（CMPP 3.0）。本平台使用 2.0。

## 3. Connect（39 字节）

| 字段 | 长度 | 说明 |
| --- | --- | --- |
| SourceAddr | 6 | SP 接入账号 |
| AuthenticatorSource | 16 | `MD5(SourceAddr + 9×0x00 + sharedSecret + Timestamp)`（字节级拼接） |
| Version | 1 | `0x20`（旧实现硬编码 1 —— P6，重写必须用 0x20） |
| Timestamp | 4 | **`MMddHHmmss` 24 小时制**（旧实现误用 12 小时制 `hh` —— P5，下午时段鉴权必错） |

**ConnectResp**：Status(1，**必须校验**，旧实现不校验 —— P7) + AuthenticatorISMG(16) + Version(1)。Status≠0 视为连接失败并记录原因。

## 4. Submit（CMPP 2.0）

定长段 + 目的号码段 + 内容段：

| 字段 | 长度 | 说明 |
| --- | --- | --- |
| Msg_Id | 8 | 平台生成（雪花 ID 低 8 字节或独立 msgId 序列）；运营商在 SubmitResp 中回显，作为状态报告关联键（旧实现恒 0 致关联键退化 —— P3） |
| Pk_total | 1 | 长短信总条数（**必须支持拆分**；旧实现恒 0 —— P4） |
| Pk_number | 1 | 当前条序号（从 1 起） |
| Registered_Delivery | 1 | 1 = 要求状态报告 |
| Msg_level | 1 | 信息级别，取 0 |
| Service_Id | 10 | 业务类型 |
| Fee_UserType | 1 | 计费用户类型 |
| Fee_terminal_Id | 21 | 被计费号码（定长，不足补 0） |
| Fee_terminal_type | 1 | 0=真实号码 1=伪码 |
| TP_pid | 1 | 0 |
| TP_udhi | 1 | 长短信含 6 字节协议头时置 1，否则 0 |
| Msg_Fmt | 1 | **0=ASCII、8=UCS2、15=GB18030**（旧实现整个字段缺失，其后字段错位 1 字节 —— P2） |
| Msg_src | 6 | 企业代码（旧实现误填 serviceId —— P13） |
| FeeType | 3 | 01 免费 / 02 按条 / 03 包月 / 04 封顶 / 05 包月扣费请求等 |
| FeeCode | 6 | 资费代码（"000000"） |
| ValId_Time | 17 | 短消息存活有效期（`yymmddhhmmsstnnp`，全 0 表示不限制） |
| At_Time | 17 | 定时发送时间（全 0 立即发送） |
| Src_Id | 21 | **接入号/源号码，来自通道配置，不硬编码**（旧实现硬编码追加 "1630" —— P13） |
| DestUsr_tl | 1 | 收端号码个数 n（≤100） |
| Dest_terminal_Id | 21×n | 收端号码（定长，不足补 0） |
| Dest_terminal_type | 1 | 0=真实号码 1=伪码 |
| Msg_Length | 1 | Msg_Content 字节数（≤140 字节/条） |
| Msg_Content | ≤140 | 内容按 Msg_Fmt 编码（UCS2 = UTF-16BE） |

**长短信拆分规则**：内容按 140 字节（UCS2 按 70 字/条）切分，逐条发送；首条 TP_udhi=1 并携带 6 字节 UDH 协议头（`05 00 03 refNum totalNum seqNum`），Pk_total/Pk_number 对应填写。

**SubmitResp**：Msg_Id(8) + Result(1)。Result=0 成功，非 0 按 CMPP2ResultEnums 码表直译；**Msg_Id 最高位为 1 表示运营商侧错误语义，不得 Math.abs 丢失**（旧实现 —— P9）。

## 5. Deliver（状态报告 / 上行）

按 Registered_Delivery 区分：

**状态报告（=1），Msg_Content 固定 60 字节**：

| 字段 | 长度 | 说明 |
| --- | --- | --- |
| Msg_Id | 8 | 与 SubmitResp 回显一致（关联键） |
| Stat | 7 | 回执状态码（CMPP2DeliverEnums；**仅 DELIVRD 视为成功**） |
| Submit_time | 10 | yymmddhhmm |
| Done_time | 10 | yymmddhhmm |
| Dest_terminal_Id | 21 | 收端号码 |
| SMSC_sequence | 4 | 短消息中心流水号 |

**上行短信（=0）**：Msg_Content 为用户上行内容（重写版保留仅日志记录，业务接入为后处理范围 —— P14）。

**Deliver_Resp（必须应答）**：Msg_Id(8，回显) + Result(1，0=正确)。旧实现收到 Deliver 从不回 Deliver_Resp，ISMG 会超时重发 —— P8。

> ⚠️ 编码陷阱（旧实现 P11）：状态报告字段按协议为 ASCII/BCD 编码，**不得按 Msg_Fmt 切换字符集**，否则奇数长度 UTF-16BE 解析产生乱码、DELIVRD 比对失败误判。

## 6. ActiveTest / ActiveTestResp（心跳）

- 空闲 20s 触发 ActiveTest；**必须校验 ActiveTestResp 应答**（旧实现只看 ALL_IDLE 不校验应答，半开连接无法发现 —— P18）。
- ActiveTestResp 必须**回显原 sequenceId**（旧实现恒 0）。

## 7. 编解码实现要点（重写版强制）

1. 帧解码：`LengthFieldBasedFrameDecoder` 必须设置**帧长上限**（如 64KB，防恶意超长 —— P20）；totalLength 需合法性校验（≥12 且不超上限，防 0~3 字节短帧打挂通道 —— P21）。
2. 字符串编码**按字段语义**分别处理（定长 ASCII 字段 / 内容按 Msg_Fmt），禁止"一刀切 UTF-16BE"（旧实现 —— P12）。
3. 序列号：实例级原子自增，回绕后避开在用值；多实例部署不共享序列空间（旧实现 static synchronized 全局共享 —— P48）。
4. 长连接管理：EventLoopGroup **复用不每次新建**（P15 线程泄漏）、重连**不在 I/O 线程阻塞**（P16）、心跳应答校验（P18）、`channel` 状态字段可见性（volatile，P22）。

## 8. 交互时序

```text
SP                                      ISMG
 │── Connect ──────────────────────────▶│
 │◀─ ConnectResp（校验 Status）──────────│
 │── Submit ───────────────────────────▶│
 │◀─ SubmitResp（Result / Msg_Id）───────│
 │◀─ Deliver（状态报告，可能重复）────────│
 │── Deliver_Resp（回显 Msg_Id）────────▶│
 │── ActiveTest ◀─────▶ ActiveTestResp ─│（20s 空闲心跳，校验应答）
 │── Terminate ◀────▶ TerminateResp ────│
```

## 9. 缺陷规避对照（P1~P25 摘要）

| 编号 | 旧缺陷 | 重写版做法 |
| --- | --- | --- |
| P1 | 连接参数硬编码 | `@ConfigurationProperties` record `CmppGatewayProperties`（cmpp.host/port/service-id/pwd） |
| P2 | Submit 缺 Msg_Fmt、整体错位 1 字节 | 按 §4 字段表完整实现 |
| P3 | Msg_Id 恒 0 | 平台生成 msgId，回显键唯一 |
| P4 | 无长短信拆分 | §4 拆分规则 + TP_udhi/Pk_total/Pk_number |
| P5 | 12 小时制时间戳 | §3 24 小时制 `MMddHHmmss` |
| P6 | Connect 版本字节硬编码 1 | 0x20 |
| P7 | CONNECT_RESP 不校验 | Status 校验 |
| P8 | 不回 Deliver_Resp | §5 必须应答 |
| P9 | msgId Math.abs 丢语义 | 保留原始 8 字节 |
| P10 | Deliver 重叠左移拷贝 | 基于帧切片解析，不原地改写 |
| P11 | 状态报告按 Msg_Fmt 选字符集 | 状态报告固定 ASCII |
| P12 | Encoder 一刀切 UTF-16BE | 按字段语义编码 |
| P13 | srcId 硬编码 "1630"、Msg_src 误用 | 配置化接入号 + 企业代码 |
| P14 | 上行仅日志 | 保留仅日志（后处理范围） |
| P15/P16 | EventLoopGroup 泄漏 / I/O 线程阻塞重连 | §7.4 |
| P17 | CMPP 不可达启动失败 | initMethod 改为启动后异步连接（非阻塞启动） |
| P18 | 心跳不校验应答 | §6 |
| P20/P21 | 帧长无上限 / 短帧无防御 | §7.1 |
| P22 | channel 可见性 | volatile |
| P24 | 忽略 submit() 返回值 | 检查返回值，失败 nack/补偿 |
| P25 | SubmitResp 无超时兜底 | cmpp:submit TTL + 超时兜底任务置失败 |

---

## 附 · 待核对清单（与真实运营商对接前）

- [ ] 各定长字段的补位规则（左补 0 / 右补空格）以运营商协议文档为准
- [ ] FeeType/FeeCode/ValId_Time 在本平台的实际取值（按通道配置还是统一 02/000000/全0）
- [ ] UCS2 长短信的 UDH 长度语义（TP_udhi 与 Pk_total 的联动细节）
- [ ] 运营商对 Msg_Id 的生成规则（部分 ISMG 要求平台填 0 由侧生成，以运营商文档为准）
- [ ] 状态报告 Stat 全集（CMPP2DeliverEnums 收录的为常见值，个别运营商有私有码）
