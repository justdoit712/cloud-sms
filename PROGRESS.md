# cloud-sms 项目进度日志

> **用途**：跨 PC 协作的人工可读进度台账。git 回答"代码改了什么"，本文档回答"现在到哪、下一步干什么"。
> **规则**：① 每次工作会话结束**必须**更新本文档并随代码一起提交；② 新日志加在"详细日志"**顶部**（倒序）；③ 重要决策必须写入"决策记录"（跨机协作最怕决策丢在聊天里）。

---

## 一、当前状态（一眼看完）

| 项 | 状态 |
| --- | --- |
| 项目阶段 | **阶段二：代码重构（从零重写路线）** 🟡 方案就绪（`docs/learning/09`），待启动 R1 |
| 代码状态 | backend/ 仅剩父 POM（2.0.0）+ 9 个空模块骨架；frontend/ 已清空 |
| 最近完成 | 2026-09-13：路线改弦 + 清仓 + 《09_从零重写方案》定稿（逐模块设计 + 缺陷规避清单） |
| 当前阻塞 | 无 |
| 下一步 | 启动 R1：beacon-common 重写（模型/常量/异常/工具/缓存契约，按 09 §5-R1） |

## 二、阶段总览

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 一 | 文档体系：版本调研 + 学习笔记 + 逐模块方案 + AI 编写规范 | ✅ 完成 |
| 二 | 代码重构：JDK17 + Boot 3.2 + 组件升级（9 模块，按 `docs/learning/06` 执行） | ⏳ 未开始 |
| 三 | 业务缺陷修复：`docs/02_项目详细分析总报告.md` 38 项风险，按优先级 | ⏳ 未开始 |

### 阶段二 · 模块进度（从零重写路线，2026-09-13 起）

> 旧"迁移式"步骤表作废（0.1/0.2 产物保留：父 POM 直接复用；旧代码已清仓）。重写顺序与契约以新方案文档为准。

| 步骤 | 模块 | 状态 | 完成日期 | commit |
| --- | --- | --- | --- | --- |
| R0 | 清仓：删除迁移式拷贝的旧代码，保留父 POM + 空骨架 | ✅ | 2026-09-13 | 待提交 |
| R1 | beacon-common（重写：模型/常量/异常/工具/缓存契约） | ⬜ | | |
| R2 | beacon-cache（重写：统一 Redis HTTP 服务） | ⬜ | | |
| R3 | beacon-search（全新 ES8 客户端实现） | ⬜ | | |
| R4 | beacon-api（重写：接入 + 校验链） | ⬜ | | |
| R5 | beacon-strategy（重写：策略链） | ⬜ | | |
| R6 | beacon-push（重写：回调推送） | ⬜ | | |
| R7 | beacon-smsgateway（重写：CMPP/Netty 网关） | ⬜ | | |
| R8 | beacon-monitor（重写：xxl-job 巡检） | ⬜ | | |
| R9 | beacon-webmaster（重写：Sa-Token 后台） | ⬜ | | |
| R10 | frontend（重写：Vue3 + TS 前端） | ⬜ | | |

## 三、详细日志（倒序，最新在上）

### 2026-09-13 · 《09_从零重写方案》定稿（PC：本机 Windows）

- **类型**：重写路线蓝图
- **内容**：新增 `docs/learning/09_从零重写方案.md`——系统规格摘要（主链路/对象/MQ 拓扑/缓存键/接口契约）、R1~R10 逐模块设计要点（关键类 + 验收 + 规避缺陷编号）、横切决策（MQ 可靠性/ES/认证/扣费/限流/线程池/配置）、里程碑验收；README 导航同步（06/07 系列标记作废，09 为现行蓝图）
- **commit**：`docs: 新增 09_从零重写方案（重写路线总蓝图）`

### 2026-09-13 · 路线改弦：从零重写，清仓旧代码（PC：本机 Windows）

- **类型**：重大决策变更
- **内容**：用户明确"所有代码全部重写"。删除迁移式拷贝的旧代码（backend 514 文件 + frontend 85 文件），仅保留：父 POM（Boot 3.2.12 + 双 BOM + 版本收口，0.2 成果直接复用）、9 个空模块骨架、frontend 空目录
- **参考资源保留**：旧项目原版 `D:\Code\Java\springcloud\beacon-cloud`（未动）；git 历史 `ad31930` 基线 commit（可 `git show`/checkout 恢复）；docs/02、03 与 analysis/ 四篇深挖报告 = 重写时的"规格说明书"
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
- **commit**：`ad31930` chore: 基线拷贝（来自 beacon-cloud master c696c0c，未做任何修改）

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
| 2026-09-13 | **路线改为"从零重写"（推翻迁移式）**：后端 9 模块 + 前端全部重新编写，旧项目仅作对照参考（原版仍在 `D:\Code\Java\springcloud\beacon-cloud` 不动） | 用户明确："所有代码全部重写"；已拷贝基线保留于 git 历史（ad31930），learning/06、07A~07F 迁移手册作废（历史留存）；learning/01~05 组件笔记与 AI 编写规范继续生效 |
| 2026-09-12 | 重构目录：`D:\Code\project\cloud-sms`，前后端分离（backend/ + frontend/） | 用户指定；旧项目 beacon-cloud 保持不动随时对照 |
| 2026-09-12 | JDK 目标 17（非 21） | 本机只装 JDK 21，用 `--release 17` 编译保证产物 17 兼容；Boot 3.2 与 17 配套最成熟 |
| 2026-09-12 | 先做技术升级，业务缺陷后处理 | 升级与修缺陷分开，降低每步风险 |
| 2026-09-12 | Hippo4j 2.x 暂缓 | Central 无 2.x/boot3 版本（实测仅 1.5.0）；网关暂用标准 ThreadPoolExecutor 等价替换，Bean 名不变，2.x 发布后单文件回归 |
| 2026-09-12 | 学习成果沉淀为 markdown 笔记（docs/learning/） | 用户要求"边重构边学" |
| 2026-09-12 | AI 生成代码以根目录 `AI代码编写规范.md` 为最高约束 | 强制 JDK 17 新特性；docs/16、17 已删除，该文档自包含 |
| 2026-09-12 | 执行策略：两步走（横切管机械 + 纵切管深入） | 第 0 步全局横切：父 POM + javax→jakarta + JUnit 5 + pom 版本清理，一次性做完；第 1~9 步按依赖顺序纵切（common→cache→search→api/strategy/push→smsgateway→monitor→webmaster），简单模块先做验证流程，webmaster 最后并内部分两段 |
| 2026-09-12 | 前后端同步推进（用户要求） | 只有 webmaster 会破坏前后端契约，故阶段 E 改为 4 个交错小步（E1 骨架→E2 登录+验证码★→E3 401/403→E4 点检）：契约变更成对提交、每小步浏览器实测通过才进下一步；阶段 A~D 前端无感不用同步 |

## 五、待办清单（下一步）

- [ ] 用户审阅：`docs/learning/00`（路线图）、`docs/learning/06`（逐模块方案）、`AI代码编写规范.md`
- [ ] 确认后启动阶段二：建 `backend/`、从旧项目拷贝基线（排除 .git/node_modules/target/backups/xxl-job）、git init、父 POM 升级
- [ ] 环境差异核对（跨机）：各 PC 的 JDK（需 17+，可用 21 以 --release 17 编译）、Maven（≥3.6.3）
- [ ] 待办：hippo4j 2.x 发布后回归动态线程池；生产部署方式（Nginx 托管前端）确认；Spring Cloud Gateway 引入评估

## 六、跨机协作约定

1. **本文档唯一权威**：开新机器/新会话先读本文档 + `docs/learning/00`，再动手。
2. **每次会话收尾**：更新"当前状态"表 + 顶部追加一条日志（日期/机器/类型/内容/验证/commit）+ 提交 git。
3. **决策必须落纸**：聊天里口头定的方向，当场写进"决策记录"，否则换机器就丢。
4. **代码真相源仍是 git**：本文档只记状态与决策，代码细节以 git 为准；文档与代码同仓同步提交。
5. **避免冲突**：阶段二按 `docs/learning/06` 的模块顺序线性推进，一次只动一个模块；发现文档与代码不一致时，以代码为准并修正文档。
6. **环境登记**（多机各自填一行）：

| 机器 | 系统 | JDK | Maven | 备注 |
| --- | --- | --- | --- | --- |
| 本机 | Windows 11 | 21.0.9（JAVA_HOME=D:\Dev_Envs\Java\jdk-21） | 3.6.3 | 无 JDK 17，用 --release 17 |
| （待填） | | | | |
