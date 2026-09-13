# cloud-sms · 短信平台重构项目

> 本项目是 [beacon-cloud](https://github.com/justdoit712/beacon-cloud) 短信平台的**重构版**。
> 目标：JDK 17 + Spring Boot 3.2 + Spring Cloud 2023.0.3 + 组件升级，前后端分离，边重构边学习。
> **当前阶段：文档先行。本目录目前只有文档，尚无代码。**

## 目录规划

```text
cloud-sms\
├── README.md        # 本文件（入口）
├── PROGRESS.md      # ★ 项目进度日志（跨 PC 协作台账：当前状态/日志/决策/待办）
├── AI代码编写规范.md  # ★ AI 生成代码的硬约束（JDK17 新特性强制使用 + 过程纪律）
├── docs\            # 文档体系
│   ├── 02、03               # 原项目设计/分析文档（保留总报告与架构指南）
│   ├── analysis\                 # 旧项目四大模块深挖报告
│   └── learning\                 # 重构学习笔记与执行蓝图（见下）
├── backend\         # 已建（9 个 beacon-* 空模块骨架，待步骤 0.1 拷贝基线）
└── frontend\        # 已建（空目录，待步骤 0.1 拷贝旧 Frontend）
```

## 文档导航（按阅读顺序）

0. `PROGRESS.md` —— **★ 新机器/新会话先读**：当前状态、决策记录、待办清单
1. `AI代码编写规范.md` —— **★ AI 写代码时必读**：JDK 17 新特性强制使用矩阵 + 审查清单
2. `docs/learning/00_重构路线图与进度.md` —— 版本基线、双阶段进度、决策记录
3. `docs/learning/01_JDK17与Boot3升级核心变化.md` —— jakarta 迁移、Boot 3 破坏性变更、报错速查
4. `docs/learning/02_SaToken入门与Shiro迁移对照.md` —— webmaster 认证框架迁移
5. `docs/learning/03_ES8新JavaClient与search改造.md` —— ES 客户端重写（逐方法映射）
6. `docs/learning/04_Netty升级与CMPP网关改造.md` —— 网关升级与线程池方案
7. `docs/learning/05_xxljob24与监控模块升级.md` —— 监控模块升级
8. `docs/learning/06_逐模块重构方案.md` —— 代码阶段执行蓝图：9 模块 × 改点/风险/验收
9. `docs/learning/07_索引与总览.md` —— ❌ 已作废（迁移式路线手册，历史留存）
10. `docs/learning/08_前后端架构与目录结构.md` —— **★ 结构冻结**：backend/frontend 目录、模块调用关系、新文件归属规则
11. `docs/learning/09_从零重写方案.md` —— **★★ 重写路线执行蓝图（现行）**：模块顺序、逐模块设计要点、38 项缺陷规避清单

## 阶段

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 一 | 文档体系（版本调研 + 学习笔记 + 重构方案） | ✅ 完成 |
| 二 | 代码重构（**从零重写路线**，按 `docs/learning/09` 执行） | 🟡 进行中 |
| 三 | 业务缺陷修复（已并入重写：38 项风险边写边规避） | 并入阶段二 |
