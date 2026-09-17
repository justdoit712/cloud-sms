# cloud-sms 项目进度日志

> **用途**：跨 PC 协作的人工可读进度台账。git 回答"代码改了什么"，本文档回答"现在到哪、下一步干什么"。
> **规则**：① 每次工作会话结束**必须**更新本文档并随代码一起提交；② 新日志加在"详细日志"**顶部**（倒序）；③ 重要决策必须写入"决策记录"（跨机协作最怕决策丢在聊天里）。

---

## 一、当前状态（一眼看完）

| 项 | 状态 |
| --- | --- |
| 项目阶段 | **阶段一：单发短信跑通到网关**（场景驱动，共 8 个场景；里程碑进度 1/9） 🟡 **前置已完成，代码第 1/19 步落地** |
| 代码状态 | backend/：父 POM（2.0.0）挂载 **5 模块**（common/cache/api/strategy/smsgateway）+ 各模块 pom + 4 个启动类，`mvn install` 通过；其余 4 模块（search/push/monitor/webmaster）注释保留等各自场景开启。frontend/ 空 |
| 最近完成 | 2026-09-17：**环境转为 Docker Desktop（WSL2）+ 阶段一第 1 步提交 `ea102ff`**（父 POM 挂 5 模块 + 启动类骨架，编译通过） |
| 当前阻塞 | **无** —— 阶段一第 1~11 步不需要中间件；第 12 步起所需的 Nacos/RabbitMQ/Redis 已在 Docker 中就绪 |
| 下一步 | 按 `docs/learning/10_阶段一执行手册.md` 推进 **第 2 步 `StandardSubmit` → 第 11 步**（common 模块，全程无需中间件） |

## 二、阶段总览（**场景驱动**：一个阶段 = 一个能真实跑通的业务场景，从最小闭环逐步长大）

> **当前阶段：阶段一 · 单发短信跑通到网关**（最小可用系统）。执行顺序与设计要点见 `docs/learning/09`。
> **原则**：场景需要什么就建什么 —— 每个场景把涉及的模块纵向打通并真实运行验证，不做"先把某模块整个写完再进下一个"。
> 里程碑进度 **1/9**：前置已完成，阶段一~八未开工。

| 阶段 | 真实场景 | 涉及模块 | 验收动作（真的跑起来） | 需装中间件 | 状态 | 完成日期 | commit |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 前置 | 清仓旧代码 + 父 POM（2.0.0 + Boot 3.2.12 + 双 BOM）就绪 | — | `mvn -N validate` 成功 | — | ✅ | 2026-09-13 | 9dd0044 |
| **一** | **单发短信跑通到网关**：客户 POST → 校验 → 策略链 → CMPP 下发 | common · cache(读) · api · strategy · smsgateway | CMPP 模拟器收到 Submit，日志有应答 | Nacos · RabbitMQ · 模拟器 · Redis | 🟡 | | |
| 二 | 扣费与余额拦截：发送时实时扣费，余额不足拒发 | + webmaster(扣费接口) | `client_balance` 真实减少；余额不足返回错误码 | MySQL | ⬜ | | |
| 三 | 限流与路由：同号 1 小时限 1 条；按权重选通道 | + cache(原子限流) | 第 2 条被拦；通道按权重分配 | Redis | ⬜ | | |
| 四 | 回执与短信日志：Deliver → 落库 + ES + 状态更新 | + search | ES 能查到短信，状态由「已提交」变「成功」 | Elasticsearch | ⬜ | | |
| 五 | 客户回调推送：状态变更后回调客户 URL | + push | 模拟客户服务收到回调 | — | ⬜ | | |
| 六 | 后台登录与代发：运营登录、查短信、手动补发 | + webmaster · frontend | 浏览器登录/检索/代发全通 | — | ⬜ | | |
| 七 | 监控巡检：通道健康检查、余额预警、定时任务 | + monitor | xxl-job 控制台看到任务执行 | xxl-job | ⬜ | | |
| 八 | 完整客户接入：批量提交、模板、签名、黑名单 + 全链路联调 | 全模块 | 全链路冒烟通过 | 全部 | ⬜ | | |

> **旧"迁移式"步骤表、以及"模块即阶段"表均已作废**（0.1/0.2 产物保留：父 POM 直接复用；旧代码已清仓）。执行顺序与契约以 `docs/learning/09` 为准。
> **38 项业务缺陷**（🔴/🟡，见 `docs/02` 与 `docs/analysis/`）不单独排期，**按其所属场景并入该场景的验收标准**（例：签名不覆盖 body 的 🟡#23 → 阶段一；ES 幂等/租户隔离的 🔴#20/#21 → 阶段四），横切项在阶段八联调收口。
> **R1~R10 模块编号不废弃**，降级为场景内的**骨架索引**（说明该场景要动哪些模块、哪些类），见 09 §5。

#### 阶段一 · 场景执行拆分（小步学习：每步 2~4 个文件 + 一个学习点 + 一个 commit）

**第 0 步 · 场景一前置（不写业务代码）**

> **★ 逐步细节以 `docs/learning/10_阶段一执行手册.md` 为准**（24 个工作项 = 第 0 步 4 个前置 + 第 1~19 步实现；每步含交付物/学习点/验收命令/commit message）。
> **★ 2026-09-16 调整：CMPP 后置。** 阶段一拆两段验收：**验收点 A = 接口闭环**（第 12~15 步，Postman/curl 实测，只需 Nacos+RabbitMQ，sm gateway 先用 stub 只打日志）→ **验收点 B = CMPP 真下发**（第 16~19 步，需模拟器 + Netty）。CMPP 模拟器缺失**不再阻塞前 15 步**。

| 步 | 事项 | 学习点 | 状态 |
| --- | --- | --- | --- |
| 0-a | 装并启 Nacos 2.3.x，按 `docs/ops/01` 建 DataId | 注册中心 + 配置中心的数据模型 | ✅ **2026-09-17** Nacos 2.3.2（Docker）+ 4 个 DataId 已建（见 `docs/ops/01` 附录 C） |
| 0-b | 装 RabbitMQ 3.12+ 并启用 `rabbitmq_delayed_message_exchange` | 延迟交换机为何要插件 | 🟡 服务 ✅（3.13-management）；**插件未启用**（官方镜像不含，阶段五前补） |
| 0-c | 启 Redis 7.4.9；**CMPP 模拟器延后到第 16 步前** | 模拟器与被测网关的方向关系 | ✅ Redis 7.4 已启；模拟器随 CMPP 后置到验收点 B |
| 0-d | 从 git 基线 `5edb39d` 导出旧代码作对照物 | git worktree 取历史物 | ✅ `_reference/`（374 Java / 9 模块，已 gitignore） |

**第 1~19 步 · 场景一实现（拉开即最小闭环）**

| 步 | 文件 / 事项 | 学习点 | 段 |
| --- | --- | --- | --- |
| 1 | 父 POM 挂 common+cache（其余注释）+ 各模块 pom + 启动类骨架 | Maven 多模块 / 父 POM 版本治理 | A |
| 2 | StandardSubmit | 可变 @Data vs record 的取舍（链式逐字段装配） | A |
| 3 | StandardReport | 回执/回调双支路共用载体 | A |
| 4 | RabbitMQConstants | MQ 拓扑语义（topic 实为队列） | A |
| 5 | CacheKeyConstants + SmsConstant | 逻辑/物理缓存键、回执状态码 | A |
| 6 | ExceptionEnums + 单测 | 错误码表设计（-100 撞码教训） | A |
| 7 | MobileOperatorEnum + CMPP2 两枚映射枚举 | 枚举映射与 Optional 反查 | A |
| 8 | BizException + 4 子类 | 异常上下文体系（ack/nack 分类依据） | A |
| 9 | SnowFlake + 单测 | 雪花 ID 位运算、时钟回拨 fail-fast | A |
| 10 | PhoneFormatCheckUtil + Result/ResultVO/PageResultVO | record 返回体、集合禁 null | A |
| 11 | CacheAuthSignUtil + CacheAuthHeaders + 单测 | HMAC payload 设计、恒时比较、**签名覆盖 body（规避 🟡#23）** | A |
| 12 | ApiStarterApp + SmsController + CheckFilter 链（先 3 段） | 校验链与过滤器上下文 | A |
| 13 | strategy 消费 + 策略链（**先 route 一段**） | MQ 手动 ack 与异常分类 | A |
| 14 | **smsgateway stub：只消费 + 打印报文摘要（不碰 CMPP）** | 监听容器 ackMode/prefetch/concurrency | A |
| 15 | **★ 验收点 A：接口闭环（Postman/curl 实测）** | 一次请求穿过五跳；负面验证 | A |
| 16 | CMPP 协议层：Netty 连接 + 帧解码 + Connect 握手 | Netty pipeline 方向、帧解码器参数 | B |
| 17 | CMPP 业务层：Submit 编码 / 下发 / SubmitResp | CMPP 定长字段与偏移（P2 错位教训） | B |
| 18 | 网关可靠性：状态暂存 + 线程池 + 超时兜底 | 中间状态外置、CallerRunsPolicy | B |
| 19 | **★ 验收点 B：CMPP 真下发 + 阶段一收口** | 全链路串起来 | B |

> 步骤 12~19 是场景一特有的（模块索引里属 R4/R5/R7）—— **它们先做最小可跑版本，够跑通就停**，完整能力留给后续场景。
> **第 14 步的 stub 必须在第 19 步收尾时清理**（类名统一 `*Stub` 后缀便于 grep）。

## 三、详细日志（倒序，最新在上）

### 2026-09-17 · 环境转为 Docker Desktop + 阶段一第 1 步落地（PC：本机 Windows）

- **类型**：环境重建 + 代码首落地
- **内容**：
  1. **环境方案变更**：废弃原 Linux 虚拟机（CentOS 7，因 `hwclock` 触发内核 panic、时钟漂移、sshd 挂掉等一连串问题而不可用），改为 **Docker Desktop on Windows（WSL2 后端）**。落地 `D:\docker\middleware\docker-compose.yml`，含 Nacos 2.3.2 / RabbitMQ 3.13-management / MySQL 8.4 / Redis 7.4，全部映射到 `127.0.0.1`；WSL 资源限制 `memory=6GB, processors=4`（`~/.wslconfig`）
  2. **Nacos 4 个 DataId 已建**（`beacon-{api,strategy,smsgateway,cache}-dev.yml`），端口规划 8081/8082/8083/8084，8080 留给阶段六 webmaster；详细登记见 `docs/ops/01` 附录 C
  3. **阶段一第 1 步完成**：父 POM 挂载 5 模块（common/cache/api/strategy/smsgateway，其余 4 个注释保留）+ 5 个子模块 pom + 4 个启动类（`ApiStarterApp`/`CacheStarterApp`/`StrategyStarterApp`/`SmsGatewayStarterApp`）+ 清理 9 个 `.gitkeep`
  4. **编译验证通过**：`mvn -q install` 退出码 0，5 个模块全部装入本地仓库 `maven-repo\com\cz\`
- **踩坑记录（已写入 `docs/ops/01` 附录 C.3）**：
  1. 旧 VM 拉镜像报证书未生效 → 根因是 VM 系统时钟比 RTC 落后 4 个月
  2. 中间件端口 TCP 可连但 HTTP 无响应 → `wsl --shutdown` 后 `wslrelay` 转发进程未重建，**彻底重启 Docker Desktop** 解决
  3. 宿主机访问 localhost 中间件超时 → 系统代理（`127.0.0.1:7897`）劫持，需加 no-proxy 例外
- **commit**：`ea102ff` feat: 阶段一第1步 父POM挂载5模块并生成骨架启动类
- **遗留**：
  1. RabbitMQ `rabbitmq_delayed_message_exchange` 插件未启用（官方镜像不含，**阶段五前必须补**）
  2. 各模块 `bootstrap.yml` 未创建 —— 按计划随对应场景落地（api 第 12 步 / strategy 第 13 步 / smsgateway 第 14 步）
  3. CMPP 模拟器未准备（已随 CMPP 后置到第 16 步）

### 2026-09-16 · JDK 17 安装切换，环境阻塞解除（PC：本机 Windows）

- **类型**：环境落地 + 实测验证
- **内容**：
  1. **安装** Eclipse Temurin JDK **17.0.20.1**（Windows x64 MSI，8 月最新补丁）→ `D:\Dev_Envs\Java\jdk-17.0.20.1`；安装向导勾选 Set JAVA_HOME + Modify PATH，**未**选 JavaSoft (Oracle) registry keys（避免与已有 Oracle JDK 25 的注册表项混淆）
  2. **持久化结果**：机器级 `JAVA_HOME` = `D:\Dev_Envs\Java\jdk-17.0.20.1\`；机器级 PATH 首位 = `D:\Dev_Envs\Java\jdk-17.0.20.1\bin`
  3. **六项实测验证**（全部通过）：`mvn -version` 认到 17.0.20.1（vendor Eclipse Adoptium）；探针工程（Boot 3.2.12 + release 17 + Boot 管理 Lombok 1.18.36）`compile` 成功、getter/setter 正常生成；JDK 17 特性矩阵（record/模式匹配/switch 表达式/Text Block/Stream.toList/Map.ofEntries/Map.ofEntries）全通过；产物 `major=61`；运行输出正确；`backend/` 的 `mvn -N clean install` **BUILD SUCCESS**（父 POM 已装入本地仓库）
  4. **文档同步**：PROGRESS §一 状态表 + §四 决策记录（3 条）+ §五 待办 + §六 环境登记；`docs/learning/01` §7 报错速查补"已按方案 A 解决"；README 顶部警示改为"环境已就绪"
- **踩坑记录**：换完 JDK 后本 AI 会话仍报 Maven 用 25 编译 —— 原因是**环境变量变更不作用于已启动的进程**（本会话启动于安装之前）。新开进程实测正确。已写入环境登记表与 `learning/01` 作为警示
- **commit**：待提交（`docs: JDK 17 安装完成，环境阻塞解除`）
- **遗留**：中间件（RabbitMQ/Nacos/ES/xxl-job/CMPP 模拟器）未安装，R3 起逐个补齐

### 2026-09-16 · 环境全项实测 + 文档漂移订正（PC：本机 Windows）

- **类型**：环境核查 + 文档纠正
- **内容**：
  1. **环境全项实测**：逐一验证工具链（JDK/Maven/仓库/Settings/Node/pnpm/Git/PS 执行策略）与中间件（MySQL/Redis/RabbitMQ/Nacos/ES/xxl-job/CMPP/Docker/WSL）；结论写入 §六 环境登记（旧登记含笔误与过期信息，已订正）
  2. **JDK 25 阻塞定位**：探针工程实测 `spring-boot-starter-parent:3.2.12` + `release 17` → 父 POM 解析成功、JDK 17 语言特性全通过、产物 `major=61` 正确，**但 Lombok 注解处理失效**（`validate` 过、`compile` 炸）。根因两点叠加：JDK 23+ 默认 `-proc:none`；Boot 3.2.12 管理的 Lombok 1.18.36 不支持 JDK 25（隔离测试 1.18.46 成功）。已写入 `docs/learning/01` §7 报错速查
  3. **路径漂移订正**：`D:\Code\project\cloud-sms` → `D:\Code_Projects\Project\cloud-sms`（`AI代码编写规范.md`、`learning/00`×3、`learning/08`）
  4. **旧源码路径失效**：实测 `D:\Code\Java\springcloud\beacon-cloud` 不存在 → `learning/00`、`learning/09`、决策记录同步订正，对照物改为从 git 基线 `5edb39d` 导出
  5. **`docs/07` 重定位**：原文案假设"Vue3 重构已完成、只做视觉优化"，与 R10 从零搭建不符 → 加定位说明段 + §11 落地节奏改写为"规范先行、样板定版"的搭建顺序
  6. **PROGRESS 清理**：§一 当前状态（新增阻塞项）、§五 待办清单（移除已完成的 0.1/0.2 陈旧项，重建为开工前/R1/联调前/长期四组）、§四 新增 3 条决策记录、§六 环境登记重写
- **验证**：`mvn -N validate` 在 `backend/` 下 BUILD SUCCESS（父 POM 可解析、Central 可达）；探针产物 `javap` 确认 `major version: 61`
- **commit**：`docs: 环境实测订正与文档漂移修正`
- **遗留**：JDK 阻塞未解决（待用户选择方案 A 切 17/21 或方案 B 改父 POM）；中间件安装未启动

### 2026-09-13 · 提交历史重写：msg 统一去掉括号（PC：本机 Windows）

- **类型**：git 历史整理
- **内容**：17 条 commit msg 重写——删除英文范围括号 `(pom)/(all)/(test)` 与中文说明括号，括号内文字保留（逗号衔接）；`git filter-branch --msg-filter` 全量重写 + `git push --force`；新约定「类型: 描述」不带括号，AI 规范 §5.5 与 09 §2 已同步
- **旧 hash 对照**：基线拷贝 `ad31930`→`5edb39d`、清仓 `f061857`→`9dd0044`（台账引用已全部更新；其余旧 hash 作废，历史叙述条目保留原样）
- **commit**：重写本身无独立 commit；台账同步提交见下一条

### 2026-09-13 · 本机会话收尾，交接新 PC（PC：本机 Windows）

- **类型**：会话收尾
- **内容**：文档体系全部齐备并已推送 GitHub（`origin/master` = `b02a29b`）；代码未动（R1 待用户指令，计划已拆 12 步登记在案）；环境登记表更新（本机另装有 jdk-17 可选）
- **下机指引**：新 PC `git clone https://github.com/justdoit712/cloud-sms` → 先读 PROGRESS（一、当前状态）→ `docs/learning/09`（执行蓝图）→ 按 R1 十二步表逐步执行；旧项目源码仅本机有（`D:\Code\Java\springcloud\beacon-cloud`），如新机需对照可从 GitHub clone beacon-cloud 旧仓库
- **commit**：`docs: 会话收尾交接（环境登记更新 + 下机指引）`

### 2026-09-13 · 回退 R1 + 补齐三份缺口文档（PC：本机 Windows）

- **类型**：过程纠正 + 文档补齐
- **内容**：
  1. **回退 R1**：用户明确"未授权执行 R1"，`git reset --hard 2138418` + force push，R1 两个提交（7324643/e3ac49d）从历史移除，backend 恢复空骨架
  2. **三份缺口文档补齐**：
     - `docs/db/01_数据库表结构DDL.md`（611 行，18 张表；5 张表采用旧库真实 dump 权威结构）
     - `docs/protocol/01_CMPP2报文规格.md`（帧/命令字/Submit/Deliver/心跳逐字段 + P1~P25 规避对照）
     - `docs/ops/01_Nacos配置清单.md`（8 服务配置项 + 敏感明文盘点 + 登记记录表）
  3. 索引同步：README 导航 10/11/12、09 §3.4 命名口径 + §7 MySQL 9.1.0 修正 + §9 登记、00 目录树
- **重大发现**：旧库真实版本 **MySQL 9.1.0**（非 8.0）；`channel__number`/`channel_protocal` 历史拼写保留；`client_balance` 有 uk_client_id 唯一约束；`client_business.id` bigint unsigned 无自增（业务侧生成）；22 项待核对清单已列（联调前从旧库核对）
- **commit**：`docs: 补齐三份缺口文档（DDL/CMPP/Nacos）+ 索引同步`

### 2026-09-13 · 删除迁移式作废文档（PC：本机 Windows）

- **类型**：文档清理
- **内容**：删除 8 篇迁移式手册（`learning/06`、`07_索引`、`07A~07F`）；同步修正全部引用（README 导航重编号、AI 规范 §8/§5 指向 09、PROGRESS 阶段表/待办/跨机约定、00 路线图整体刷新为从零重写路线、05/08 页脚衔接、09 措辞"已删除"）
- **验证**：grep `06_逐模块|07_索引|07A~07F` 无残留有效引用（git 历史中仍可查）
- **commit**：`docs: 删除迁移式作废文档（06/07 系列）并修正引用`

### 2026-09-13 · 《09_从零重写方案》定稿（PC：本机 Windows）

- **类型**：重写路线蓝图
- **内容**：新增 `docs/learning/09_从零重写方案.md`——系统规格摘要（主链路/对象/MQ 拓扑/缓存键/接口契约）、R1~R10 逐模块设计要点（关键类 + 验收 + 规避缺陷编号）、横切决策（MQ 可靠性/ES/认证/扣费/限流/线程池/配置）、里程碑验收；README 导航同步（06/07 系列标记作废，09 为现行蓝图）
- **commit**：`docs: 新增 09_从零重写方案（重写路线总蓝图）`

### 2026-09-13 · 路线改弦：从零重写，清仓旧代码（PC：本机 Windows）

- **类型**：重大决策变更
- **内容**：用户明确"所有代码全部重写"。删除迁移式拷贝的旧代码（backend 514 文件 + frontend 85 文件），仅保留：父 POM（Boot 3.2.12 + 双 BOM + 版本收口，0.2 成果直接复用）、9 个空模块骨架、frontend 空目录
- **参考资源保留**：旧项目原版 `D:\Code\Java\springcloud\beacon-cloud`（未动）；git 历史 `5edb39d` 基线 commit（可 `git show`/checkout 恢复）；docs/02、03 与 analysis/ 四篇深挖报告 = 重写时的"规格说明书"
- **作废标记**：learning/06、07A~07F 为迁移式执行手册，历史留存不再执行；learning/01~05 组件笔记、AI代码编写规范.md 继续生效
- **commit**：`chore: 清仓迁移式旧代码，转入从零重写`

### 2026-09-13 · 步骤 0.3 全局机械横切完成（PC：本机 Windows）

- **类型**：阶段二（07A 步骤 0.3，3 个 commit）
- **内容**：
  1. `3321a4d` refactor(all): javax→jakarta 换名——19 文件 28 行（annotation/servlet/validation 三类；javax.mail/crypto/imageio 保留 ✅）
  2. `314a977` refactor(test): JUnit4→JUnit5——50 文件 132 行（@RunWith(SpringRunner)→@ExtendWith(SpringExtension) ×5、@Before/@After→Each、Assert→Assertions）
  3. `aa91e4d` refactor(pom): 子模块版本清理+版本号 2.0.0-SNAPSHOT——9 模块 pom：父引用/common 依赖版本升级、删 compiler source/target 1.8（与父 POM release=17 冲突）、删已被管理的写死版本
- **验证**：grep `1.0-SNAPSHOT`/`maven.compiler.source`/`org.junit.(非jupiter)`/`@RunWith` 全部归零；`mvn -pl beacon-common -am compile` BUILD SUCCESS（Boot 3.2.12 + release 17 首次编译通过）
- **执行偏差（已补录 07A）**：
  1. ⚠️ 手册批次 3 未区分"已被父 POM/Boot 管理"的依赖——hippo4j 1.5.0、mysql 5.1.49、druid boot2 1.2.28、ES 7.6.2×2 无管理版本，删掉版本会令整个 reactor 无法解析 → **保留版本到对应模块步骤**（3.1/4.1/2.1 随依赖删除/替换）
  2. mockito-inline 在 Boot 3.2 无管理版本 → 提前到 0.3 删除（手册原定 2.1；Mockito 5 已内置 inline，零影响）
  3. 额外清理：commons-lang3（Boot 管理）、kaptcha/webmaster hutool-dfa（父 POM 管理）版本一并删除；模块 pom 的 maven.compiler.source/target 1.8 一并删除
  4. 过程事故：PowerShell `[regex]::Replace` 四参重载不存在导致 6 个 pom 被写成空文件 → `git checkout` 恢复后改用实例方法 count 重载 + 空内容护栏重做，结果验证无误
- **commit**：见上 3 个

### 2026-09-13 · 步骤 0.2 父 POM 升级完成（PC：本机 Windows）

- **类型**：阶段二（07A 步骤 0.2）
- **内容**：parent → Boot 3.2.12；版本 1.0-SNAPSHOT → 2.0.0-SNAPSHOT；属性改连字符写法（spring-cloud.version=2023.0.3 / spring-cloud-alibaba.version=2023.0.3.2，绕开历史"点号导致 BOM 解析失败"坑）；`maven.compiler.release=17`；netty.version=4.1.138.Final 属性覆盖 Boot 管理；dependencyManagement 新增 8 个三方组件（hutool-dfa/es-java/xxl-job/sa-token×2/mybatis/druid-boot3/ikanalyzer/kaptcha）
- **验证**：`mvn -N install` BUILD SUCCESS；effective-pom 实测：openfeign→4.1.3（Cloud BOM）、nacos-discovery→2023.0.3.2（Alibaba BOM）、netty 4.1.138.Final、parent 3.2.12
- **坑（已补录 07A）**：PowerShell 下 `help:effective-pom` 的 `-Doutput=` 参数未加引号被截断（报 Unknown lifecycle phase ".xml"）→ 加引号解决
- **commit**：`f57ff3e` refactor(pom): 升级 Boot 3.2.12 / Cloud 2023.0.3 / Alibaba 2023.0.3.2，版本收口

### 2026-09-13 · 步骤 0.1 基线拷贝完成（PC：本机 Windows）

- **类型**：阶段二启动（07A 步骤 0.1）
- **内容**：
  1. `robocopy $OLD → backend/`（/XD .git .idea .vscode node_modules target dist backups xxl-job）：**264 目录 / 514 文件 / 0 失败**（与手册期望一致）
  2. `robocopy $OLD\Frontend → frontend/`：85 文件 / 0 失败
  3. 清理 10 个 .gitkeep
- **偏差记录（已补录 07A）**：旧仓库根含 `Frontend/`，第一步 robocopy 会把它一并拷入 backend/ 与 frontend/ 重复 → 已删除 `backend\Frontend`（结构以 learning/08 为准：前端唯一归宿是 frontend/）
- **验证**：`fc /b` 确认 backend/pom.xml 与旧项目字节级一致；backend 顶层 = 9 模块 + pom + 旧项目杂项（analysis/docs/README 等，保真基线）
- **commit**：`5edb39d` chore: 基线拷贝，来自 beacon-cloud master c696c0c，未做任何修改

### 2026-09-12 · 清理阶段一冗余文档（PC：本机 Windows）

- **类型**：文档清理
- **内容**：删除 4 篇冗余分析文档（`01_系统全局概览` / `04_RabbitMQ消息链路与死信队列` / `05_RabbitMQ实现问题与改进方案` / `06_ES作用与交互分析`，内容已被 `02_总报告` 与 `analysis/` 深挖报告覆盖）；同步修正 README、learning/00、03_架构指南中的引用
- **验证**：grep 无残留断链（analysis/ 报告中 2 处指向旧项目自身 docs 的历史引用保留未动）
- **commit**：`docs: 删除冗余分析文档 01/04/05/06 并修正引用`

### 2026-09-12 · 推送到 GitHub 远端（PC：本机 Windows）

- **类型**：仓库发布
- **内容**：创建远端仓库 `https://github.com/justdoit712/cloud-sms`（public）；`git remote add origin` + `git push -u origin master`（凭据经 Git Credential Manager 浏览器授权，已存 Windows 凭据管理器）
- **验证**：`git ls-remote origin` 的 HEAD/refs/heads/master 与本地 `96f48b4` 一致；`git status -sb` 无 ahead/behind
- **commit**：`docs: 记录远端仓库信息`

### 2026-09-12 · 创建 git 仓库并初始提交（PC：本机 Windows）

- **类型**：仓库初始化
- **内容**：`git init -b master`（单仓建在 cloud-sms 根，按 `docs/learning/08` 约定）；新增根 `.gitignore`（忽略 target/dist/node_modules/.idea/.env 等）；全部现有内容（文档体系 + backend/frontend 目录骨架）作为初始提交入库
- **验证**：`git status` 干净；`git log` 仅 1 个 commit
- **commit**：`chore: 初始化仓库（文档体系 + 前后端目录骨架基线）`

### 2026-09-12 · 创建目录骨架（PC：本机 Windows）

- **类型**：文档先行阶段的结构准备
- **内容**：创建 ackend\（含 9 个 beacon-* 空模块目录 + .gitkeep）与 rontend\（空目录 + .gitkeep），与 docs/learning/08 结构冻结文档一致；未拷贝任何代码
- **验证**：目录树核对通过；PROGRESS/README 已同步
- **commit**：无（代码阶段 0.1 步统一提交）

### 2026-09-12 · 文档体系建成（PC：本机 Windows）

- **类型**：文档
- **内容**：
  1. 旧项目详细分析：`docs/02_项目详细分析总报告.md` + `docs/analysis/`（4 篇模块深挖报告，38 项风险清单）
  2. 重构方向决策：JDK 17 目标（本机 JDK 21 以 `--release 17` 编译）、先技术升级后修缺陷、学习笔记沉淀、独立目录重构
  3. 版本调研（全部经 Maven Central 实测）：Boot 3.2.12 / Cloud 2023.0.3 / Alibaba 2023.0.3.2 / Sa-Token 1.46.0 / ES 8.19.21 / xxl-job 2.4.2 / Netty 4.1.138 / hutool 5.8.47 / MyBatis 3.0.5 / Druid 1.2.31
  4. 学习笔记 7 篇（`docs/learning/00~10`）+ `AI代码编写规范.md`（自包含，强制 JDK 17 新特性）+ 本进度台账
  5. 目录定名 `cloud-sms`，全部文档迁至 `D:\Code\project\cloud-sms`；`docs/16`、`docs/17` 已由用户删除，规范文档已改为自包含
- **验证**：目录结构核对完成，旧项目 `beacon-cloud` 未动
- **commit**：尚无（代码阶段启动后开始提交）

## 四、决策记录（重要决策永久存档）

| 日期 | 决策 | 理由/备注 |
| --- | --- | --- |
| 2026-09-16 | **JDK 基线落地**：安装 Eclipse Temurin **JDK 17.0.20.1** 至 `D:\Dev_Envs\Java\jdk-17.0.20.1`，设为机器级 `JAVA_HOME` 并置 PATH 首位（安装时未写 JavaSoft registry keys，避免与 Oracle JDK 25 注册表混淆） | 与文档基线（JDK 17）完全对齐；实测 `mvn -version` 认到 17.0.20.1、探针 Lombok 正常、产物 major=61、父 POM `mvn -N clean install` 成功。此前 JAVA_HOME 为 25.0.3，导致 Boot 3.2.12 管理的 Lombok 1.18.36 注解处理失效 |
| 2026-09-16 | **JDK 版本策略**：不使用 JDK 25（不采用"改父 POM 加 `<proc>full</proc>` + `lombok.version=1.18.46`"的方案 B） | Boot 3.2.12 官方支持 Java 17~21，JDK 25 越界；方案 A 零侵入、无需改代码，后续每引入注解处理器（MyBatis Generator 等）都不会再踩 |
| 2026-09-16 | **环境登记纠错**：JDK 登记由"21.0.9 + 另有 jdk-17 可选"订正为"实际 JAVA_HOME=25.0.3，本机无 JDK 17，可选 8/21/25"；Maven 3.6.3 → 3.9.12；本地仓库 `D:\App\MAVEN\maven-repository` → `D:\Dev_Tools\maven\maven-repo` | 本机全项实测（全盘搜 javac、注册表 JavaSoft\JDK、环境变量 `JAVA_HOME_8/21/25`）均无 JDK 17；旧登记存在笔误与过期信息 |
| 2026-09-16 | **JDK 版本策略**：R1 开工前必须解决 JDK 25 下的 Lombok 注解处理失效（`mvn validate` 通过但 `compile` 失败）；优先切 JAVA_HOME 到 17/21，备选方案 B 改父 POM（`<proc>full</proc>` + `lombok.version=1.18.46`） | Boot 3.2.12 官方支持 Java 17~21，JDK 25 越界；实测 Lombok 1.18.36 在 JDK 25 失败、1.18.46 成功，且 JDK 23+ 默认 `-proc:none` |
| 2026-09-16 | **旧项目对照源码路径失效**：`D:\Code\Java\springcloud\beacon-cloud` 本机已不存在，重写对照物改用 git 基线 commit `5edb39d` 导出 | 实测路径不存在；此前文档多处假设该目录可用（含 09 §1、PROGRESS 决策记录），全部已订正 |
| 2026-09-13 | **代码须经用户明确指令才动工**（R1 抢跑已回退） | 用户要求：先补齐全部文档、一步一步搭建；文档未齐/未确认前不动代码 |
| 2026-09-13 | **commit msg 约定：`类型: 描述`，不带任何括号**（历史已全量重写 + force push） | 用户要求；AI 规范 §5.5、09 §2 已同步 |
| 2026-09-13 | 中间件版本修正：MySQL 按旧库实测 **9.1.0**（原 09 方案写 8.0） | 旧库 dump 证据（backups/mysql/user_role_permission_seed_20260519）；DDL 文档已收录待核对项 |
| 2026-09-13 | **路线改为"从零重写"（推翻迁移式）**：后端 9 模块 + 前端全部重新编写，旧项目仅作对照参考（原版仍在 `D:\Code\Java\springcloud\beacon-cloud` 不动） | 用户明确："所有代码全部重写"；已拷贝基线保留于 git 历史（5edb39d），learning/06、07A~07F 迁移手册作废（历史留存）；learning/01~05 组件笔记与 AI 编写规范继续生效 |
| 2026-09-12 | 重构目录：`D:\Code\project\cloud-sms`，前后端分离（backend/ + frontend/） | 用户指定；旧项目 beacon-cloud 保持不动随时对照 |
| 2026-09-12 | JDK 目标 17（非 21） | 本机只装 JDK 21，用 `--release 17` 编译保证产物 17 兼容；Boot 3.2 与 17 配套最成熟 |
| 2026-09-12 | 先做技术升级，业务缺陷后处理 | 升级与修缺陷分开，降低每步风险 |
| 2026-09-12 | Hippo4j 2.x 暂缓 | Central 无 2.x/boot3 版本（实测仅 1.5.0）；网关暂用标准 ThreadPoolExecutor 等价替换，Bean 名不变，2.x 发布后单文件回归 |
| 2026-09-12 | 学习成果沉淀为 markdown 笔记（docs/learning/） | 用户要求"边重构边学" |
| 2026-09-12 | AI 生成代码以根目录 `AI代码编写规范.md` 为最高约束 | 强制 JDK 17 新特性；docs/16、17 已删除，该文档自包含 |
| 2026-09-12 | 执行策略：两步走（横切管机械 + 纵切管深入） | 第 0 步全局横切：父 POM + javax→jakarta + JUnit 5 + pom 版本清理，一次性做完；第 1~9 步按依赖顺序纵切（common→cache→search→api/strategy/push→smsgateway→monitor→webmaster），简单模块先做验证流程，webmaster 最后并内部分两段 |
| 2026-09-12 | 前后端同步推进（用户要求） | 只有 webmaster 会破坏前后端契约，故阶段 E 改为 4 个交错小步（E1 骨架→E2 登录+验证码★→E3 401/403→E4 点检）：契约变更成对提交、每小步浏览器实测通过才进下一步；阶段 A~D 前端无感不用同步 |

## 五、待办清单（下一步）

> 2026-09-16 清理并**改按场景驱动重排**：旧清单里"建 backend/、拷贝基线、git init、父 POM 升级"等已在 0.1/0.2 完成；"R1 十二步表"已由阶段一场景拆分取代。中间件不再统一推后，**按场景需要提前装**。

**开工前（阻塞阶段一）**
- [x] ~~解决 JDK 阻塞~~ ✅ 2026-09-16 完成：JDK 17.0.20.1 已装并切换，编译链路实测通过（DSH 重启后进程环境已同步）
- [ ] 从 git 历史基线 `5edb39d` 导出旧代码到本地（旧项目目录 `D:\Code\Java\springcloud\beacon-cloud` 已失效，重写需对照物）

**阶段一前置 · 中间件（场景一必须全部就绪）**
- [ ] 安装并启动 Nacos 2.3.x（注册中心 + 配置中心，按 `docs/ops/01_Nacos配置清单.md` 建 DataId）
- [ ] 安装 RabbitMQ 3.12+ 并启用 `rabbitmq_delayed_message_exchange` 插件
- [ ] 启动 Redis 7.4.9（已装未运行，`D:\Dev_Tools\redis\...-with-Service`）
- [ ] 准备 CMPP 模拟器（127.0.0.1:7890；旧项目资源 `模拟cmpp-server.zip` 本机未找到，需另行获取）

**阶段一 · 场景实现**
- [ ] 按上表 §二「阶段一 · 场景执行拆分」逐步执行（第 0 步前置 → 第 1~15 步实现）

**后续场景的中间件（按场景提前装，不提前）**
- [ ] MySQL 服务启动 + 核对本机 8.4.8 与旧库 9.1.0 建表差异（22 项，`docs/db/01`）→ **阶段二需要**
- [ ] Elasticsearch 8.x → **阶段四需要**
- [ ] xxl-job-admin 2.4.x → **阶段七需要**

**长期**
- [ ] hippo4j 2.x 发布后回归动态线程池（只改 `ThreadPoolConfig` 一个文件，Bean 名不变）
- [ ] 生产部署方式（Nginx 托管前端 + /api 反代）确认
- [ ] Spring Cloud Gateway 引入评估

## 六、跨机协作约定

1. **本文档唯一权威**：开新机器/新会话先读本文档 + `docs/learning/00`，再动手。
2. **每次会话收尾**：更新"当前状态"表 + 顶部追加一条日志（日期/机器/类型/内容/验证/commit）+ 提交 git。
3. **决策必须落纸**：聊天里口头定的方向，当场写进"决策记录"，否则换机器就丢。
4. **代码真相源仍是 git**：本文档只记状态与决策，代码细节以 git 为准；文档与代码同仓同步提交。
5. **避免冲突**：按 `docs/learning/09 §4.5` 的**场景顺序**线性推进，一次只做一个场景；场景内涉及的模块可同时动（同一场景的改动成对提交）；发现文档与代码不一致时，以代码为准并修正文档。
6. **环境登记**（多机各自填一行；**2026-09-16 本机全项实测复核**）：

   ### 6.1 工具链

   | 机器 | 系统 | JDK（JAVA_HOME） | Maven | Maven 本地仓库 | 备注 |
   | --- | --- | --- | --- | --- | --- |
   | 本机 | Windows 11 | **17.0.20.1 Temurin**（`D:\Dev_Envs\Java\jdk-17.0.20.1\`，已设为机器级 JAVA_HOME 且 PATH 首位） | 3.9.12（`D:\Dev_Tools\maven\apache-maven-3.9.12`） | `D:\Dev_Tools\maven\maven-repo`（0.95 GB） | 另有 `JAVA_HOME_8/21/25` 三个备用变量；JDK 25 为 Oracle 版 |
   | （待填） | | | | | |

   > ✅ **JDK 阻塞已解决（2026-09-16 实测通过）**：安装 Eclipse Temurin JDK 17.0.20.1（Windows x64 MSI，装于 `D:\Dev_Envs\Java\jdk-17.0.20.1`，安装时勾选 Set JAVA_HOME + Modify PATH，**未**安装 JavaSoft (Oracle) registry keys）。
   > **实测验证结果**：① `mvn -version` → `Java version: 17.0.20.1, vendor: Eclipse Adoptium`；② 探针工程（Boot 3.2.12 + `release 17` + Boot 管理的 Lombok）`compile` **成功**，Lombok getter/setter 正常生成（JDK 25 下必失败）；③ JDK 17 特性矩阵 R1/R3/R4/R5/R6/R7/R9 全部编译通过；④ 产物字节码 `major version: 61`（Java 17）；⑤ 运行时输出正确；⑥ `backend/` 执行 `mvn -N clean install` → **BUILD SUCCESS**，父 POM 已装入本地仓库。
   > ⚠️ **注意**：环境变量变更**只对新开的进程生效**。安装前已打开的终端、IDE、本 AI 会话仍持有旧值（`JAVA_HOME=jdk-25.0.3`）——**必须新开终端或在 IDE 中重新加载环境**，否则 Maven 仍会用 JDK 25 编译并复现 Lombok 失败。
   > 本机 JDK 清单：`jdk-17.0.20.1`（新增，当前使用）/ `jdk-21.0.9` / `jdk-25.0.3` / `jdk1.8.0_471` + IDE 自带 JBR（IDEA 21.0.10、DataGrip/PyCharm 25.0.2）。

   ### 6.2 中间件（按场景提前装；"阻塞场景"列为该中间件首次被需要的阶段）

   | 中间件 | 项目要求 | 本机实测 | 阻塞场景 | 状态 |
   | --- | --- | --- | --- | --- |
   | MySQL | 旧库 9.1.0 | 8.4.8（`D:\Dev_Tools\Mysql\mysql-8.4.8-winx64`，服务 MySQL 已停止/手动启动） | 阶段二 | ⚠️ 版本不同但为 LTS，`utf8mb4_0900_ai_ci` 可用 |
   | Redis | 7.x | 7.4.9（`D:\Dev_Tools\redis\Redis-7.4.9-Windows-x64-cygwin-with-Service`，未运行） | **阶段一** | ⚠️ 版本满足，需手动启动 |
   | RabbitMQ | 3.12+ 且装 delayed 插件 | **未安装** | **阶段一** | ❌ 阻塞当前阶段 |
   | Nacos | 2.3.x | **未安装** | **阶段一** | ❌ 阻塞当前阶段 |
   | CMPP 模拟器 | `模拟cmpp-server.zip` | **未找到** | **阶段一** | ❌ 阻塞当前阶段 |
   | Elasticsearch | 8.x | **未安装** | 阶段四 | ❌ 阶段四前补齐 |
   | xxl-job-admin | 2.4.x | **未安装** | 阶段七 | ❌ 阶段七前补齐 |
   | Docker / WSL | 可选 | **均未安装** | — | ⚠️ 无容器化捷径 |

   > **缓解**：阶段一（单发短信跑通到网关）需要 **Nacos + RabbitMQ + Redis + CMPP 模拟器**，四项均未就绪 —— **这是当前唯一阻塞项**，装好即可开工。ES/MySQL/xxl-job 分别在阶段二/四/七前补齐即可，不阻塞开工。

   ### 6.3 前端与其它

   | 项 | 实测 |
   | --- | --- |
   | Node / npm / pnpm | v22.22.0 / 10.9.4 / 11.7.0（pnpm 位于 harness 目录，非独立安装） |
   | PowerShell | 执行策略 Undefined → `npm.ps1`/`pnpm.ps1` 被拒，须用 `npm.cmd`/`pnpm.cmd` |
   | Git | 2.53.0.windows.1 |
   | Maven settings.xml | `D:\Dev_Tools\maven\apache-maven-3.9.12\conf\settings.xml`：仅内置 HTTP blocker，无 mirror/代理，Central 直连可用 |
   | **旧项目对照源码** | ⚠️ `D:\Code\Java\springcloud\beacon-cloud` **已不存在**，需对照时从 git 基线 `5edb39d` 导出 |
