# 数据库表结构 DDL（duanxin_pingtai · 18 张表）

## 文档用途

本文档用于 cloud-sms 短信平台**从零重写**时的旧库表结构**对照与文档化**，覆盖旧库 `duanxin_pingtai` 的 18 张表。

## 数据来源

- **旧项目实体类**：`beacon-webmaster/src/main/java/com/cz/webmaster/entity/` 下全部 14 个实体（SmsUser、SmsRole、SmsMenu、ClientBusiness、ClientBalance、ClientChannel、Channel、MobileBlack、MobileDirtyWord、MobileTransfer、MobileArea、CodeLimit、ScheduleJob、ScheduleLog；Example 类已忽略）。
- **MyBatis XML**：`beacon-webmaster/src/main/resources/mapper/` 下全部 14 个 XML（列名以 XML 为准；其中 `client_sign`、`client_template` 只有 XML 没有实体类，字段以 XML 为准）。
- **注解 SQL Mapper**：`SmsRoleMapper.java`（含 `sms_user_role`、`sms_role_menu` 的注解 SQL）、`ScheduleJobMapper.java`、`ScheduleLogMapper.java`。
- **缓存域对照**：`beacon-common` 的 `CacheKeyConstants.java` 与 `cache/meta/CacheDomainRegistry.java`（9 个主线域：client_business / client_sign / client_template / client_channel / channel / client_balance / transfer / black / dirty_word）。
- **旧库 mysqldump 备份（权威结构证据）**：`beacon-cloud/backups/mysql/user_role_permission_seed_20260519_152530.sql`（主机 192.168.88.128，库名 `duanxin_pingtai`，dump 工具 8.0.26 / 服务器版本 9.1.0），其中包含 `sms_user`、`sms_role`、`sms_user_role`、`sms_role_menu`、`client_business` 五张表的真实 `CREATE TABLE`，本文档对这五张表**直接采用 dump 结构**；其余 13 张表按 XML 列名 + 实体 Java 类型映射合理 MySQL 类型。

## 重要声明

> 实际数据库**沿用旧库 `duanxin_pingtai`**，本 DDL **不执行、仅作重写对照与文档化**。
> **字段以旧库实际结构为准，如本文件与旧库不符，一律以旧库为准。**
> 未获得 dump 证据的 13 张表，其列类型、varchar 长度、默认值、索引均为基于源码的合理推断，见文末「待核对清单」。

## 类型映射与列名约定

| Java 类型 | MySQL 类型 | 说明 |
|---|---|---|
| `Long` | `BIGINT` | 主键/外键/金额类 |
| `Integer` | `INT` | 状态、类型、端口等 |
| `Byte` | `TINYINT` | `is_delete`、`is_callback`、`is_available` 等布尔位 |
| `String` | `VARCHAR`（长度按语义推断，dump 覆盖者以 dump 为准） | 文本列 |
| `Date`（XML jdbcType=TIMESTAMP） | `TIMESTAMP` | 审计时间列 |
| `Date`（XML 未给 jdbcType，schedule_*） | `DATETIME` | 待核对 |

**列名以 mapper XML 为准**，两个历史遗留拼写按旧库原样保留：

- `channel__number`（双下划线，对应实体 `spNumber`，通道扩展号）；
- `channel_protocal`（历史拼写错误，对应实体 `protocolType`，协议类型）。

## 18 张表清单与缓存域对照

| # | 表名 | 用途 | 缓存域（CacheKeyConstants / CacheDomainRegistry） |
|---|---|---|---|
| 1 | `sms_user` | 后台用户（登录账号） | 无 |
| 2 | `sms_role` | 角色 | 无 |
| 3 | `sms_menu` | 菜单（type 0/1 为一、二级菜单） | 无 |
| 4 | `sms_user_role` | 用户-角色关系 | 无 |
| 5 | `sms_role_menu` | 角色-菜单关系 | 无 |
| 6 | `client_business` | 客户（商户）信息 | `client_business`（HASH，key `client_business:{apikey}`） |
| 7 | `client_balance` | 客户余额（MySQL 真源 + Redis 镜像） | `client_balance`（HASH，key `client_balance:{clientId}`，MYSQL_ATOMIC_UPDATE_THEN_REFRESH） |
| 8 | `client_channel` | 客户-通道绑定（路由成员） | `client_channel`（SET，key `client_channel:{clientId}`） |
| 9 | `client_sign` | 客户短信签名 | `client_sign`（SET，key `client_sign:{clientId}`） |
| 10 | `client_template` | 客户短信模板 | `client_template`（SET，key `client_template:{signId}`） |
| 11 | `channel` | 短信通道（供应商接入信息） | `channel`（HASH，key `channel:{id}`） |
| 12 | `mobile_black` | 手机号黑名单 | `black`（STRING，key `black:{mobile}` / `black:{clientId}:{mobile}`） |
| 13 | `mobile_dirtyword` | 敏感词 | `dirty_word`（SET，key `dirty_word`） |
| 14 | `mobile_transfer` | 携号转网 | `transfer`（STRING，key `transfer:{mobile}`） |
| 15 | `mobile_area` | 手机号段归属地 | 无（`phase` 号段补齐域未注册进 CacheDomainRegistry） |
| 16 | `code_limit` | 短信发送频控规则 | 无（`limit:minutes/hours/days` 为 Redis 计数 key，非表缓存域） |
| 17 | `schedule_job` | 定时任务定义（Quartz 数据源） | 无 |
| 18 | `schedule_log` | 定时任务执行日志 | 无 |

---

## 1. sms_user（用户表）

- **用途**：系统后台登录用户（用户名 / 密码密文 / 盐 / 昵称）。
- **缓存域**：无。
- **结构来源**：旧库 mysqldump 备份（2026-05-19），权威；同时与 `SmsUserMapper.xml` resultMap 完全对应。
- **备注**：XML 中 `id` 的 jdbcType 为 INTEGER，但旧库实际为 `bigint`，以 dump 为准。

```sql
CREATE TABLE `sms_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(32) NOT NULL COMMENT '用户名',
  `password` varchar(64) DEFAULT NULL COMMENT '用户登录密码',
  `salt` varchar(32) NOT NULL COMMENT '认证的盐',
  `nickname` varchar(32) NOT NULL COMMENT '昵称',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，默认系统时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间，默认系统时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除',
  `extend1` varchar(32) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(32) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(32) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(32) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
```

---

## 2. sms_role（角色表）

- **用途**：系统角色（角色名），与用户、菜单通过关系表关联。
- **缓存域**：无。
- **结构来源**：旧库 mysqldump 备份（2026-05-19），权威；与 `SmsRoleMapper.xml` resultMap 对应。

```sql
CREATE TABLE `sms_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) NOT NULL COMMENT '角色名',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，默认系统时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间，默认系统时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除',
  `extend1` varchar(32) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(32) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(32) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(32) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';
```

---

## 3. sms_menu（菜单表）

- **用途**：管理端菜单/导航项（`type` 0=一级菜单、1=二级菜单，见 `SmsMenuMapper.xml` `findMenuByUserId` 中 `m.type in (0,1)` 注释与用法）；仅用于前端导航渲染，不参与服务端鉴权。
- **缓存域**：无。
- **结构来源**：`SmsMenuMapper.xml` resultMap/Base_Column_List + 实体类 `SmsMenu`（无 dump，类型按实体推断；审计列默认值按旧库同族表惯例推断）。

```sql
CREATE TABLE `sms_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT NULL COMMENT '父菜单id',
  `url` varchar(255) DEFAULT NULL COMMENT '菜单地址',
  `icon` varchar(255) DEFAULT NULL COMMENT '菜单图标',
  `type` int DEFAULT NULL COMMENT '菜单类型 0-一级菜单 1-二级菜单',
  `sort` int DEFAULT NULL COMMENT '排序号',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，默认系统时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间，默认系统时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除',
  `extend1` varchar(32) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(32) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(32) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(32) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单表';
```

---

## 4. sms_user_role（用户角色关系表）

- **用途**：用户-角色多对多关系。
- **缓存域**：无。
- **结构来源**：旧库 mysqldump 备份（2026-05-19），权威；`SmsRoleMapper.java` 注解 SQL（`findRoleNameByUserId`）只用到了 `user_id`、`role_id` 两列，其余审计列为 dump 揭示。

```sql
CREATE TABLE `sms_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户id',
  `role_id` bigint NOT NULL COMMENT '角色id',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，默认系统时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间，默认系统时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除',
  PRIMARY KEY (`user_id`,`role_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关系表';
```

---

## 5. sms_role_menu（角色菜单关系表）

- **用途**：角色-菜单多对多关系。
- **缓存域**：无。
- **结构来源**：旧库 mysqldump 备份（2026-05-19），权威；`SmsRoleMapper.java` 注解 SQL（`insertRoleMenus` 写入 `role_id, menu_id`；`deleteRoleMenuByRoleId`、`findMenuIdsByRoleId`）证实两列用法。
- **备注**：dump 中表注释为「用户角色关系表」（与 `sms_user_role` 相同，疑似建表时复制遗留，按实际语义应理解为角色菜单关系表）。

```sql
CREATE TABLE `sms_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色id',
  `menu_id` bigint NOT NULL COMMENT '菜单id',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，默认系统时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间，默认系统时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除',
  PRIMARY KEY (`role_id`,`menu_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色菜单关系表';
```

---

## 6. client_business（客户信息表）

- **用途**：客户（商户）信息：公司名、HTTP 接入 apikey、IP 白名单、状态报告回调配置、联系人、策略校验链（client_filters）等。
- **缓存域**：`client_business`（HASH，key `client_business:{apikey}`；hash 字段含 `isCallback`、`callbackUrl` 等）。
- **结构来源**：旧库 mysqldump 备份（2026-05-19），权威；与 `ClientBusinessMapper.xml` resultMap 完全对应。
- **备注**：
  - `id` 为 `bigint unsigned` 且**无 AUTO_INCREMENT**（insert 时显式写入 id，业务侧生成）。
  - `extend1` 存**归属运营用户 id**（dump 注释即「用户id」）。
  - `client_filters` 为策略校验顺序规则（如 `black,dirtyword,route`，逗号分隔，见 dump 数据）。

```sql
CREATE TABLE `client_business` (
  `id` bigint unsigned NOT NULL COMMENT '主键（业务侧生成）',
  `corpname` varchar(128) NOT NULL COMMENT '公司名',
  `apikey` varchar(64) DEFAULT NULL COMMENT 'HTTP接入的密码',
  `ip_address` varchar(255) NOT NULL DEFAULT '0' COMMENT 'HTTP客户端的IP白名单（多个用,隔开）',
  `is_callback` tinyint NOT NULL DEFAULT '0' COMMENT '状态报告是否返回：0 不返回 1 返回',
  `callback_url` varchar(255) DEFAULT NULL COMMENT '客户接收状态报告的URL地址',
  `client_linkname` varchar(64) NOT NULL COMMENT '联系人',
  `client_phone` varchar(32) DEFAULT NULL COMMENT '密保手机',
  `client_filters` varchar(255) NOT NULL COMMENT '策略校验顺序动态实现规则',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，默认系统时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间，默认系统时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除',
  `extend1` varchar(255) DEFAULT NULL COMMENT '用户id（归属运营用户id）',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(255) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户信息表';
```

---

## 7. client_balance（客户余额表）

- **用途**：客户短信余额（一客户一条记录，MySQL 为真源、Redis 为镜像）。
- **缓存域**：`client_balance`（HASH，key `client_balance:{clientId}`，写入策略 MYSQL_ATOMIC_UPDATE_THEN_REFRESH）。
- **结构来源**：`ClientBalanceMapper.xml` resultMap/Base_Column_List + 实体类 `ClientBalance`（无 dump，类型按实体推断）。
- **备注**：
  - 原子更新 SQL：`debitBalanceAtomic`（扣费，带余额下限校验）、`rechargeBalanceAtomic`（充值）、`adjustBalanceAtomic`（调账，delta 正加负减）。
  - `ClientBalanceMapper.java` 注释明确「约定一客户一余额记录，调用方应确保 `client_id` 唯一约束已由数据库保证」→ `client_id` 建唯一索引。

```sql
CREATE TABLE `client_balance` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` bigint NOT NULL COMMENT '客户id，对应 client_business.id',
  `balance` bigint NOT NULL DEFAULT '0' COMMENT '余额（最小计费单位）',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除',
  `extend1` varchar(255) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_client_id` (`client_id`) COMMENT '一客户一余额记录（源码注释明确唯一约束）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户余额表';
```

---

## 8. client_channel（客户通道绑定表）

- **用途**：客户与短信通道的绑定关系（路由成员：通道号、单价、权重、可用状态）。
- **缓存域**：`client_channel`（SET，key `client_channel:{clientId}`，成员为路由快照）。
- **结构来源**：`ClientChannelMapper.xml`（Base_Join_Column_List / insertSelective / findRouteMembersByClientIds）+ 实体类 `ClientChannel`（无 dump，类型按实体与 SQL 推断）。
- **备注**：
  - 实体中的 `corpName`、`channelName` 是联表（client_business / channel）查询的展示字段，**不是本表列**。
  - 列名以 XML 为准：扩展号为 `client_channel_number`、单价为 `client_channel_price`（实体字段名分别为 extendNumber / price）。
  - `client_channel_weight` 只出现在 `findRouteMembersByClientIds`（`ifnull(cc.client_channel_weight, 0)`）中，用于路由权重。
  - `insertSelective` 恒写 `is_available = 0`，路由快照读 `ifnull(cc.is_available, 0)`。

```sql
CREATE TABLE `client_channel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` bigint NOT NULL COMMENT '客户id，对应 client_business.id',
  `channel_id` bigint NOT NULL COMMENT '通道id，对应 channel.id',
  `client_channel_number` varchar(32) DEFAULT NULL COMMENT '客户通道扩展号',
  `client_channel_price` bigint DEFAULT NULL COMMENT '客户通道单价（最小计费单位）',
  `client_channel_weight` int NOT NULL DEFAULT '0' COMMENT '路由权重（推断）',
  `is_available` tinyint NOT NULL DEFAULT '0' COMMENT '是否可用 0-不可用 1-可用（insert 恒写 0，推断）',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除（逻辑删除 deleteBatch）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户通道绑定表';
```

---

## 9. client_sign（客户签名表）

- **用途**：客户短信签名（审核状态、类型、营业执照证明等）。
- **缓存域**：`client_sign`（SET，key `client_sign:{clientId}`，成员为 `{id, signInfo}` 等 JSON）。
- **结构来源**：`ClientSignMapper.xml`（`findAllActive` 全列 SELECT，仅此一个 SQL；无实体类、无 jdbcType，类型按字段语义推断）。
- **备注**：该表在本模块只有快照查询，写操作疑似在其它模块/历史代码中；`sign_state`/`sign_type` 的取值语义源码中未见枚举。

```sql
CREATE TABLE `client_sign` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` bigint NOT NULL COMMENT '客户id，对应 client_business.id',
  `sign_info` varchar(64) DEFAULT NULL COMMENT '签名内容',
  `sign_state` tinyint DEFAULT NULL COMMENT '签名状态（推断，取值语义待核对）',
  `sign_type` tinyint DEFAULT NULL COMMENT '签名类型（推断，取值语义待核对）',
  `business_web` varchar(255) DEFAULT NULL COMMENT '业务网站/网址',
  `prove_descr` varchar(255) DEFAULT NULL COMMENT '证明材料描述',
  `prove_file` varchar(255) DEFAULT NULL COMMENT '证明文件（路径）',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除',
  `extend1` varchar(255) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(255) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户签名表';
```

---

## 10. client_template（客户模板表）

- **用途**：客户短信模板（挂在签名下，含类型、审核状态、使用方）。
- **缓存域**：`client_template`（SET，key `client_template:{signId}`，成员为模板快照）。
- **结构来源**：`ClientTemplateMapper.xml`（Page_Column_List / insert / update / logicalDelete，insert/update 带 jdbcType）+ 无实体类。
- **备注**：
  - `insert` 语句不写 `created`/`updated`（update 时显式 `updated = now()`）→ 两列应有数据库默认值（推断 `DEFAULT CURRENT_TIMESTAMP`）。
  - `use_id` jdbcType 为 INTEGER（使用方 id），`use_web` VARCHAR（使用方网站）。
  - 逻辑删除 `logicalDelete`：`is_delete = 1`。

```sql
CREATE TABLE `client_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sign_id` bigint NOT NULL COMMENT '签名id，对应 client_sign.id',
  `template_text` varchar(500) DEFAULT NULL COMMENT '模板内容',
  `template_type` int DEFAULT NULL COMMENT '模板类型',
  `template_state` int DEFAULT NULL COMMENT '模板状态',
  `use_id` int DEFAULT NULL COMMENT '使用方id（jdbcType INTEGER）',
  `use_web` varchar(255) DEFAULT NULL COMMENT '使用方网站',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（insert 不写该列，推断默认系统时间）',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间（更新语句显式写 now()）',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除（逻辑删除 logicalDelete）',
  `extend1` varchar(255) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(255) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户短信模板表';
```

---

## 11. channel（短信通道表）

- **用途**：短信通道（供应商）接入信息：类型、区域、单价、IP/端口、账号密码、扩展号、协议类型、可用状态。
- **缓存域**：`channel`（HASH，key `channel:{id}`）。
- **结构来源**：`ChannelMapper.xml`（Base_Column_List / insertSelective / updateById）+ 实体类 `Channel`（无 dump，列名以 XML 为准，类型按实体推断）。
- **备注**：
  - 列名以 XML 为准，注意两个历史遗留列名：`channel__number`（双下划线，spNumber 扩展号）、`channel_protocal`（拼写错误，protocolType）。
  - `channel_password` 旧库**明文存储**；重写方案要求数据库改列加密存储、代码侧加解密（本 DDL 仅如实记录旧库现状）。
  - 逻辑删除 `deleteBatch`：`is_delete = 1`。

```sql
CREATE TABLE `channel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `channel_name` varchar(64) NOT NULL COMMENT '通道名称',
  `channel_type` int DEFAULT NULL COMMENT '通道类型',
  `channel_area` varchar(64) DEFAULT NULL COMMENT '通道归属区域',
  `channel_area_code` varchar(16) DEFAULT NULL COMMENT '通道区域编码',
  `channel_price` bigint DEFAULT NULL COMMENT '通道单价（最小计费单位）',
  `channel_ip` varchar(64) DEFAULT NULL COMMENT '通道接入IP',
  `channel_port` int DEFAULT NULL COMMENT '通道接入端口',
  `channel_username` varchar(64) DEFAULT NULL COMMENT '通道接入用户名',
  `channel_password` varchar(64) DEFAULT NULL COMMENT '通道接入密码（旧库明文存储）',
  `channel__number` varchar(32) DEFAULT NULL COMMENT '通道扩展号/spNumber（历史双下划线列名）',
  `channel_protocal` int DEFAULT NULL COMMENT '协议类型（历史拼写列名 channel_protocal）',
  `is_available` tinyint NOT NULL DEFAULT '0' COMMENT '是否可用 0-不可用 1-可用',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除（逻辑删除 deleteBatch）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='短信通道表';
```

---

## 12. mobile_black（手机号黑名单表）

- **用途**：手机号黑名单（策略链 `black` 校验用）。
- **缓存域**：`black`（STRING，key `black:{mobile}` 与 `black:{clientId}:{mobile}`）。
- **结构来源**：`MobileBlackMapper.xml`（resultMap / Base_Column_List / insertSelective / deleteBatch）+ 实体类 `MobileBlack`（无 dump，类型按 XML jdbcType 与实体推断）。
- **备注**：
  - XML resultMap 中 `client_id` 的 jdbcType 为 INTEGER（实体 `Integer clientId`），但 `client_business.id` 为 bigint，类型不一致，疑似旧库实际为 int 或历史生成偏差，待核对。
  - 逻辑删除 `deleteBatch`：`is_delete = 1`。

```sql
CREATE TABLE `mobile_black` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `black_number` varchar(20) NOT NULL COMMENT '黑名单手机号',
  `black_type` int DEFAULT NULL COMMENT '黑名单类型',
  `client_id` int DEFAULT NULL COMMENT '客户id（XML jdbcType INTEGER，待核对与 client_business.id 的类型匹配）',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除（逻辑删除 deleteBatch）',
  `extend1` varchar(255) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(255) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手机号黑名单表';
```

---

## 13. mobile_dirtyword（敏感词表）

- **用途**：短信内容敏感词（策略链 `dirtyword` 校验用）。
- **缓存域**：`dirty_word`（SET，key `dirty_word`）。
- **结构来源**：`MobileDirtyWordMapper.xml`（resultMap / Base_Column_List / insertSelective / deleteBatch）+ 实体类 `MobileDirtyWord`（无 dump，类型按实体推断）。
- **备注**：逻辑删除 `deleteBatch`：`is_delete = 1`。

```sql
CREATE TABLE `mobile_dirtyword` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dirtyword` varchar(128) NOT NULL COMMENT '敏感词',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除（逻辑删除 deleteBatch）',
  `extend1` varchar(255) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(255) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词表';
```

---

## 14. mobile_transfer（携号转网表）

- **用途**：携号转网号码信息（原运营商 init_isp → 现运营商 now_isp），策略路由时查询真实运营商。
- **缓存域**：`transfer`（STRING，key `transfer:{mobile}`）。
- **结构来源**：`MobileTransferMapper.xml`（resultMap / Base_Column_List / insertSelective / deleteBatch）+ 实体类 `MobileTransfer`（无 dump，类型按实体推断）。
- **备注**：逻辑删除 `deleteBatch`：`is_delete = 1`。

```sql
CREATE TABLE `mobile_transfer` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `transfer_number` varchar(20) NOT NULL COMMENT '携号转网手机号',
  `area_code` varchar(16) DEFAULT NULL COMMENT '区号',
  `init_isp` int DEFAULT NULL COMMENT '初始运营商',
  `now_isp` int DEFAULT NULL COMMENT '当前运营商',
  `is_transfer` tinyint DEFAULT NULL COMMENT '是否携号转网 0-否 1-是',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除（逻辑删除 deleteBatch）',
  `extend1` varchar(255) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(255) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='携号转网表';
```

---

## 15. mobile_area（手机号段归属地表）

- **用途**：手机号段归属地/运营商字典（号段、归属地「省份 城市」、运营商类型、区号、邮编、省份编码）。
- **缓存域**：无（`CacheKeyConstants.PHASE` 号段补齐域未在 CacheDomainRegistry 注册契约）。
- **结构来源**：`MobileAreaMapper.xml`（resultMap / Base_Column_List / insertSelective / deleteBatch）+ 实体类 `MobileArea`（无 dump，类型按实体推断）。
- **备注**：逻辑删除 `deleteBatch`：`is_delete = 1`。

```sql
CREATE TABLE `mobile_area` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `mobile_number` varchar(16) NOT NULL COMMENT '手机号段',
  `mobile_area` varchar(64) DEFAULT NULL COMMENT '归属地（如 省份 城市）',
  `mobile_type` varchar(32) DEFAULT NULL COMMENT '手机类型（运营商）',
  `area_code` varchar(16) DEFAULT NULL COMMENT '区号',
  `post_code` varchar(16) DEFAULT NULL COMMENT '邮编',
  `province_code` varchar(32) DEFAULT NULL COMMENT '省份编码',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除（逻辑删除 deleteBatch）',
  `extend1` varchar(255) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(255) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手机号段归属地表';
```

---

## 16. code_limit（短信频控规则表）

- **用途**：短信发送频率控制规则（时间窗口内允许的最大发送次数）。
- **缓存域**：无（`limit:minutes/hours/days` 为 Redis 计次 key，不属于本表的缓存域）。
- **结构来源**：`CodeLimitMapper.xml`（resultMap / Base_Column_List / insertSelective / deleteBatch）+ 实体类 `CodeLimit`（无 dump，类型按实体推断）。
- **备注**：逻辑删除 `deleteBatch`：`is_delete = 1`。

```sql
CREATE TABLE `code_limit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `limit_time` int DEFAULT NULL COMMENT '时间窗口（推断为分钟）',
  `limit_count` int DEFAULT NULL COMMENT '窗口内最大发送次数',
  `description` varchar(128) DEFAULT NULL COMMENT '规则描述',
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id` bigint DEFAULT NULL COMMENT '创建人id',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_id` bigint DEFAULT NULL COMMENT '修改人id',
  `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 ， 1-已删除（逻辑删除 deleteBatch）',
  `extend1` varchar(255) DEFAULT NULL COMMENT '备用字段1',
  `extend2` varchar(255) DEFAULT NULL COMMENT '备用字段2',
  `extend3` varchar(255) DEFAULT NULL COMMENT '备用字段3',
  `extend4` varchar(255) DEFAULT NULL COMMENT '备用字段4',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='短信频控规则表';
```

---

## 17. schedule_job（定时任务定义表）

- **用途**：Quartz 定时任务定义（Spring bean + 方法 + 参数 + cron 表达式），启动时注册到 Quartz。
- **缓存域**：无。
- **结构来源**：`ScheduleJobMapper.java`（注解 SQL，select 列清单 `job_id, bean_name, method_name, params, cron_expression, remark, status, create_time, update_time`）+ 实体类 `ScheduleJob`（无 XML、无 jdbcType，类型按实体推断）。
- **备注**：`insert` 显式写入 `job_id`（业务侧生成），推断主键**非自增**；执行方式为按 `bean_name` 取 Spring Bean 反射调用 `method_name`。

```sql
CREATE TABLE `schedule_job` (
  `job_id` bigint NOT NULL COMMENT '任务id（insert 显式写入，推断业务侧生成、非自增）',
  `bean_name` varchar(64) NOT NULL COMMENT 'Spring bean 名称',
  `method_name` varchar(64) NOT NULL COMMENT '要执行的方法名',
  `params` varchar(255) DEFAULT NULL COMMENT '方法参数（字符串）',
  `cron_expression` varchar(64) NOT NULL COMMENT 'cron 表达式',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `status` int DEFAULT NULL COMMENT '任务状态（取值语义待核对）',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务定义表';
```

---

## 18. schedule_log（定时任务执行日志表）

- **用途**：定时任务执行日志（执行结果、耗时、异常信息）。
- **缓存域**：无。
- **结构来源**：`ScheduleLogMapper.java`（注解 SQL，select 列清单 `log_id, job_id, bean_name, method_name, params, status, times, error, create_time`）+ 实体类 `ScheduleLog`（无 XML、无 jdbcType，类型按实体推断）。
- **备注**：`insert` 显式写入 `log_id`，推断主键业务侧生成、非自增；`times` 为耗时（毫秒），`error` 存异常栈信息（推断 TEXT）。

```sql
CREATE TABLE `schedule_log` (
  `log_id` bigint NOT NULL COMMENT '日志id（insert 显式写入，推断业务侧生成、非自增）',
  `job_id` bigint DEFAULT NULL COMMENT '任务id，对应 schedule_job.job_id',
  `bean_name` varchar(64) DEFAULT NULL COMMENT 'Spring bean 名称（快照）',
  `method_name` varchar(64) DEFAULT NULL COMMENT '方法名（快照）',
  `params` varchar(255) DEFAULT NULL COMMENT '执行参数（快照）',
  `status` int DEFAULT NULL COMMENT '执行状态（取值语义待核对）',
  `times` bigint DEFAULT NULL COMMENT '执行耗时（推断为毫秒）',
  `error` text COMMENT '异常信息/堆栈（推断 TEXT）',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务执行日志表';
```

---

## 待核对清单

以下条目**无法仅凭旧项目源码/备份确定**，正式对照旧库前需逐项核对（以旧库实际结构为准）：

### 一、全局性事项

1. **旧库 MySQL 版本**：任务说明为 MySQL 8，但 dump 头部显示服务器版本 `9.1.0`（dump 客户端 8.0.26），需与运维确认实际版本。
2. **varchar 长度**：除 5 张 dump 覆盖表外，其余 13 张表所有 `VARCHAR(n)` 的 n 均为按字段语义的推断值，需核对实际长度。
3. **审计列默认值**：未获 dump 的表中 `created`/`updated` 的 `NOT NULL`、`DEFAULT CURRENT_TIMESTAMP`、`ON UPDATE CURRENT_TIMESTAMP` 均按旧库同族表惯例推断，需核对。
4. **主键自增**：无 dump 证据的表按「insertSelective 中 id 可省略 → AUTO_INCREMENT」推断；`schedule_job.job_id`、`schedule_log.log_id` 因 insert 显式写入而推断为非自增，需核对。
5. **字符集/排序规则**：推断所有表与 dump 一致（`utf8mb4` / `utf8mb4_0900_ai_ci` / InnoDB），需核对其余 13 张表。
6. **主键类型偏差**：`sms_user`/`sms_role` XML resultMap 中 `id` jdbcType 为 INTEGER，但 dump 显示旧库实际为 `bigint`；`sms_menu` 未在 dump 中，本文档按同族表惯例取 `bigint`，需核对 `sms_menu.id` 实际类型。

### 二、按表核对项

| 表 | 待核对项 |
|---|---|
| `sms_menu` | `id` 实际类型（XML 说 INTEGER，关联表 `sms_role_menu.menu_id` 为 bigint）；`name/url/icon` 等 varchar 长度；`parent_id` 是否有索引 |
| `sms_user_role` / `sms_role_menu` | 审计列（created/create_id/updated/update_id/is_delete）是否实际存在（注解 SQL 只用 user_id/role_id/menu_id，审计列仅由 dump 证实） |
| `client_business` | `id` 无 AUTO_INCREMENT（业务侧生成）是否属实；`apikey` 是否有唯一索引（缓存 key `client_business:{apikey}` 暗示唯一，源码未见建索引证据） |
| `client_balance` | `balance` 默认值与 NOT NULL；`uk_client_id` 唯一索引是否实际存在（仅源码注释明示「由数据库保证」）；extend1~3 长度 |
| `client_channel` | `client_channel_weight` 列类型（推断 int，可能 tinyint）与默认值；`is_available` 默认值；`client_channel_number` 长度；`client_id`/`channel_id` 是否有索引（查询均按 client_id 过滤/去重） |
| `client_sign` | `sign_state`/`sign_type` 实际类型与取值语义（推断 tinyint）；`sign_info` 等 varchar 长度；`client_id` 索引 |
| `client_template` | `template_text` 长度（推断 500，实际可能 TEXT）；`template_type`/`template_state`/`use_id` 取值语义；`created`/`updated` 是否确有 DB 默认值（insert 不写这两列）；`sign_id` 索引 |
| `channel` | `channel__number`（双下划线）与 `channel_protocal`（拼写）两个列名需在旧库逐字核对；`channel_password` 明文存储现状确认（重写方案计划加密改造）；各 varchar 长度；`channel_name` 是否唯一 |
| `mobile_black` | `client_id` 实际类型（XML jdbcType INTEGER 与 `client_business.id` bigint 不一致）；`black_number` 唯一性/索引 |
| `mobile_dirtyword` | `dirtyword` 长度与唯一性/索引（SET 缓存域暗示去重） |
| `mobile_transfer` | `transfer_number` 唯一性/索引（STRING 缓存 key `transfer:{mobile}` 暗示唯一）；`is_transfer` 语义 |
| `mobile_area` | `mobile_number` 唯一性/索引；`mobile_type` 取值语义；各 varchar 长度 |
| `code_limit` | `limit_time` 单位（推断分钟）；是否还有其它维度列（如适用对象 client_id，源码中未见）；`extend1` 是否如旧报告所述存 limitState（当前源码 LegacyCrudServiceImpl 为内存假存储，不足为证） |
| `schedule_job` | `job_id` 是否自增；`status` 取值语义（0/1 对应启停待核对）；各 varchar 长度；`create_time`/`update_time` 为 datetime 还是 timestamp |
| `schedule_log` | `log_id` 是否自增；`times` 单位（推断毫秒）；`error` 实际类型（推断 TEXT，可能 VARCHAR）；`status` 取值语义 |
