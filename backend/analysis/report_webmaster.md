# beacon-webmaster 运营后台模块分析报告

> 分析对象：`D:\Code\Java\springcloud\beacon-cloud\beacon-cloud\beacon-webmaster`
> 分析方式：基于源码静态阅读（read/grep/glob），全部结论附文件路径与行号证据。
> 代码规模：`src/main/java` 下 174 个 Java 文件（含测试约 198 个），其中大量为 MyBatis Generator 生成的 Example/Criteria 类（如 `SmsUserExample.java`、`ClientBusinessExample.java`）。

---

## 1. 模块职责

beacon-webmaster 是短信平台（duanxin_pingtai）的**运营管理后台服务**，提供面向运营人员的 HTTP 管理接口，职责包括：

1. **认证与用户管理**：运营账号登录（JWT + 验证码）、用户/角色/菜单 CRUD（`sms_user` / `sms_role` / `sms_menu` 表）。
2. **业务数据管理**：客户（商户）管理、短信通道管理、客户-通道绑定、号段转移、黑名单、敏感词、发送频控（code_limit）、客户签名/模板管理。
3. **短信发送**：运营后台代客户发短信（调用 beacon-api 内部接口）、短信记录查询与图表统计（调用 beacon-search）。
4. **资金管理**：客户余额充值（后台手动充值）、内部余额扣减接口（供其它模块扣费）。
5. **定时任务**：基于 Quartz 的动态定时任务管理（DB 配置 bean/method，反射执行）。
6. **MySQL→Redis 缓存同步**：以 MySQL 为真源、Redis 为派生缓存的最终一致同步（运行时同步 + 手工重建 + 启动校准），通过 Feign 调用 beacon-cache 写缓存。

模块在父工程 `beacon-cloud\pom.xml` 的 modules 列表（19-27 行）中，同属一个微服务群：beacon-api、beacon-common、beacon-cache、beacon-strategy、beacon-search、beacon-push、beacon-smsgateway、beacon-monitor、beacon-webmaster。Spring Boot 2.3.12.RELEASE + Spring Cloud Hoxton.SR12 + Spring Cloud Alibaba 2.2.6.RELEASE（父 pom 10/34/35 行）。

---

## 2. 启动类与技术栈

- 启动类：`src\main\java\com\cz\webmaster\WebMasterStarterApp.java`
  - `@SpringBootApplication` + `@MapperScan("com.cz.webmaster.mapper")` + `@EnableFeignClients`（17-19 行）。
  - 启动后打印 appName/port/profiles/version（26-30 行）。
- 技术栈（`pom.xml`）：
  - Shiro：`shiro-spring-boot-web-starter 1.4.0`（25-28 行）；JWT：`com.auth0:java-jwt 3.19.2`（87-91 行）。
  - 数据库：`mysql-connector-java 5.1.49` + `druid-spring-boot-starter 1.2.28` + `mybatis-spring-boot-starter 2.2.2`（30-44 行）。
  - Quartz：`spring-boot-starter-quartz`（46-48 行）。
  - 验证码：`com.github.axet:kaptcha 0.0.9`（53-57 行）。
  - 公共组件：`beacon-common`（59-63 行）；参数校验 `spring-boot-starter-validation`（64-68 行）。
  - 服务发现：`spring-cloud-starter-alibaba-nacos-discovery`；RPC：`spring-cloud-starter-openfeign`（70-77 行）。
  - 工具：`hutool-dfa 5.8.12`（79-84 行，DFA 敏感词）。
  - MyBatis Generator 插件 1.4.2（96-108 行）。

### 2.1 配置文件 application.yml 要点

文件：`src\main\resources\application.yml`（模块内**只有这一份** yml，没有 application-dev.yml / bootstrap.yml，dev 环境配置依赖 Nacos 配置中心）。

- 数据源：`jdbc:mysql://192.168.88.128:3306/duanxin_pingtai`，用户 root / 密码 `123`（5-7 行，**明文硬编码**）。
- 应用名 `beacon-webmaster`，`profiles.active: dev`（10-15 行）。
- Nacos：discovery 与 config 均指向 `192.168.88.128:8848`，config `file-extension: yml`（17-25 行）。
- MyBatis：`mapper-locations: classpath:mapper/*.xml`，开启驼峰映射（28-31 行）。
- Shiro：`loginUrl: /login.html`、`unauthorizedUrl: unauthorized.html`（35-39 行，但模块内无任何静态资源，见 §7）。
- `system.test-kaptcha: "1111"`：万能验证码（41-43 行）。
- `internal.balance.token: ""`：内部扣费令牌，**默认为空=不校验**（45-48 行）。
- `sync.*`：缓存同步总开关 `enabled: false`（52 行，当前关闭），redis 命名空间 `beacon:dev:beacon-cloud:cz:`，runtime/manual/boot 子开关与域清单（50-67 行）。
- `cache.*`：beacon-cache 调用鉴权 `caller=beacon-webmaster` / `secret=cache_webmaster_secret`（70-80 行，**明文密钥**）。

---

## 3. 对外 HTTP 接口清单

统一返回 `com.cz.common.vo.ResultVO`（code/msg/data，code=0 表示成功）。全局异常由 `advice\WebmasterExceptionHandler.java` 兜底（@RestControllerAdvice，72-76 行捕获 Exception 返回 UNKNOWN_ERROR）。

### 3.1 认证与用户

| 路径 | 方法 | 功能 | 鉴权 |
|---|---|---|---|
| `/sys/auth/captcha.jpg?uuid=` | GET | 生成验证码图片（KaptchaController.java:38） | anon |
| `/sys/login` | POST | 登录：验证码 + MD5 密码校验，签发 JWT（SmsUserController.java:51-90） | anon |
| `/sys/user/info` | GET | 当前登录用户昵称（SmsUserController.java:92-104） | JWT |
| `/sys/menu/user` | GET | 当前用户可见菜单树（SmsUserController.java:106-120） | JWT |
| `/sys/user/password` | POST | 修改密码——**空壳接口**，直接返回“修改成功”（SmsUserController.java:122-126） | JWT |

### 3.2 系统管理（用户/角色/菜单）

SysUserController（`/sys/user`）：

| 路径 | 方法 | 功能 |
|---|---|---|
| `/sys/user/list?offset=&limit=&search=` | GET | 用户分页列表（内存分页，先全量查再 subList，37-55 行） |
| `/sys/user/info/{id}` | GET | 用户详情（58-61 行） |
| `/sys/user/save` | POST | 新增用户（63-81 行） |
| `/sys/user/update` | POST | 修改用户（83-103 行） |
| `/sys/user/del` | POST | 批量逻辑删除（105-113 行） |

SystemRoleController（`/sys/role`）：`/list`、`/info/{id}`、`/save`、`/update`、`/del`、`/menu/tree`（菜单树）、`/menu/{roleId}`（角色已有菜单）、`/menu/assign`（分配菜单，SystemRoleController.java:129-134）。

SysMenuController（`/sys/menu`）：`/list`、`/info/{id}`、`/select`（下拉树）、`/save`、`/update`、`/del`（36-102 行）。

### 3.3 业务管理

| Controller | 路径前缀 | 接口 |
|---|---|---|
| SysAcountController | `/sys/account` 或 `/sys/acount`（双映射，20 行） | `/list`、`/info/{id}`、`/save`、`/update`、`/del`（充值流水，**内存存储**） |
| SysChannelController | `/sys/channel` | `/list`、`/all`（全部启用通道）、`/info/{id}`、`/save`、`/update`、`/del` |
| SysClientController | `/sys/client` | `/list`、`/info/{id}`、`/save`、`/update`、`/del` |
| ClientBusinessController | `/sys/client-business` 或 `/sys/clientbusiness` | `/list`、`/info/{id}`、`/save`、`/update`、`/del`、`/all`（按角色区分数据范围，120-146 行）、`/pay?jine=&clientId=`（**GET 充值**，151-218 行） |
| SysClientChannelController | `/sys/client-channel` 或 `/sys/clientchannel` | `/list`、`/info/{id}`、`/save`、`/update`、`/del` |
| SysMobileTransferController | `/sys/mobile-transfer` 或 `/sys/mobiletransfer` | `/list`、`/info/{id}`、`/save`、`/update`、`/del`（号段转移） |
| SysBlackController | `/sys/black` | `/list`、`/info/{id}`、`/save`、`/update`、`/del`（黑名单） |
| SysDirtyWordController | `/sys/message` | `/list`、`/info/{id}`、`/save`、`/update`、`/del`（敏感词） |
| SysCodeLimitController | `/sys/limit` | `/list`、`/info/{id}`、`/save`、`/update`、`/del`（频控） |
| SysPhaseController | `/sys` | `/phase/list`、`/phase/info/{id}`、`/phase/save`、`/phase/update`、`/phase/del`、`/provinces/all`、`/cities/all/{provId}`（号段归属地） |
| SysStrategyFilterController | `/sys/strategy-filter` 或 `/sys/stragetyfilter` | `/list`、`/info/{id}`、`/save`、`/update`（写客户策略链 client_filters）、`/del`（拒绝）、`/filters/all`（支持白名单列表，30-42 行） |
| SysSmsTemplateController | `/sys/sms-template` 或 `/sys/smstemp` | `/list`、`/info/{id}`、`/save`、`/update`、`/del`（客户模板） |
| SysApiGatewayFilterController | `/sys/api-gateway-filter` 或 `/sys/apigatewayfilter` | `/list`、`/info/{id}`（**从 Nacos 读 beacon-api 配置**，只读）、`/save|/update|/del`（统一拒绝，52-55 行） |
| SysLegacyCrudController | `/sys/{family}/...` | family 白名单 `activity|apimapping|grayrelease|publicparams|notify|searchparams|clientsign|clienttemplate`（25 行），每个 family 有 `/list`、`/info/{id}`、`/save`、`/update`、`/del` —— **数据存内存，非数据库**（见 §8.2） |

### 3.4 短信与统计

| 路径 | 方法 | 功能 |
|---|---|---|
| `/sys/sms/save`、`/sys/sms/update` | POST | 运营代发短信（批量，逐条调 beacon-api `/sms/internal/single_send`，单批上限 500，SysSmsController.java:24-42、SmsManageServiceImpl.java:30/70） |
| `/sys/search/list` | GET | 短信记录查询（透传参数给 beacon-search，SearchController.java:50-116，非 root 用户强制限定 clientID 范围） |
| `/sys/echarts/pie`、`/sys/echarts/line`、`/sys/echarts/bar` | GET | 发送状态统计图数据（调 beacon-search `/search/sms/countSmsState`） |

### 3.5 定时任务（Quartz）

ScheduleJobController（`/schedule/job` 或 `/sys/job`）：`/list`、`/info/{jobId}`、`/save`、`/update`、`/del`、`/pause`、`/resume`、`/run`（立即执行，60-76 行）。
ScheduleLogController（`/schedule/log` 或 `/sys/log`）：`/list`、`/del`。

### 3.6 内部/管理接口

| 路径 | 方法 | 功能 | 鉴权 |
|---|---|---|---|
| `/internal/balance/debit` | POST | 内部余额扣减（供内部系统调用，InternalBalanceController.java:68-107），请求头 `X-Internal-Token` | **anon**（ShiroConfig.java:55），且配置令牌默认为空（application.yml:47-48）→ 等于完全不鉴权 |
| `/admin/cache/rebuild` 或 `/sys/cache/rebuild?domain=` | POST | 手工重建 Redis 缓存域，需登录且角色名为“管理员”（CacheRebuildController.java:54-75） | JWT + 角色名判断 |

---

## 4. Shiro 认证授权机制分析

### 4.1 过滤器链（ShiroConfig.java:36-61）

```
/public/**            → anon
/**/*.html|js|css|png|svg|jpg|ico|woff|woff2|ttf → anon   （全部静态资源匿名）
/sys/auth/captcha.jpg → anon
/sys/login            → anon
/internal/balance/debit → anon
/logout               → logout
/**                   → jwt（自定义 JwtFilter）
```

### 4.2 认证（JWT）

- 登录不走 Realm：`SmsUserController.login`（79 行）手工用 Shiro `SimpleHash("MD5", password, salt, 1024)` 与库中密码比对，成功后 `JwtUtil.sign(username, userId)` 签发 24 小时 JWT（86 行）。
- `JwtFilter`（shiro\JwtFilter.java）：从 `Authorization: Bearer xxx` 或 query 参数 `token` 取 token（37-43 行），构造 `JwtToken` 提交 login；失败回 401 JSON（25-31 行）；preHandle 里处理 CORS/OPTIONS（55-68 行）。
- `ShiroRealm.doGetAuthenticationInfo`（relam\ShiroRealm.java:36-54）：解码 JWT 取 username → 查库确认用户存在 → `JwtUtil.verify` 校验签名 → 返回 principal 为 `SmsUser` 实体。
- JWT：HS256，密钥硬编码 `"beacon-webmaster-secret-key-2026"`（util\JwtUtil.java:18），有效期 24 小时（16 行）。
- 会话：`ShiroConfig` 关闭了 session 存储（27-31 行），无状态 JWT 模式。
- 密码加密：MD5 + 8 位随机盐 + 1024 次迭代（SmsUserServiceImpl.java:147-149）；新增用户未填密码时默认密码 `123456`（70-72 行）。
- 验证码：Kaptcha 生成 4 位文本（KaptchaConfig.java:26），存于**静态内存 Map** `KaptchaController.CAPTCHA_MAP`（KaptchaController.java:32，无过期清理），登录校验后移除；配置了万能验证码 `1111`（SmsUserController.java:63）。

### 4.3 授权（严重薄弱）

`ShiroRealm.doGetAuthorizationInfo` **直接返回 null**（relam\ShiroRealm.java:60-62）。即：

- 全系统没有任何 Shiro 级权限校验（无角色/权限注解、无 `isPermitted` 调用）。
- **任意登录用户可调用全部管理接口**（增删用户、改角色、分配菜单、充值、重建缓存、编辑定时任务等）。
- 少数接口自行做了“角色名 == 管理员”的判断（如 ClientBusinessController.java:132/164、CacheRebuildController.java:67-69、SmsManageServiceImpl.java:206-209），依据是 beacon-common 中 `WebMasterConstants.ROOT = "管理员"`（beacon-common\...\WebMasterConstants.java:14），**以角色名字符串做权限判定**，改名即失效/可伪造。

### 4.4 角色/菜单数据模型

- 用户 `sms_user`、角色 `sms_role`、菜单 `sms_menu`（type 0/1 为一二级菜单），关联表 `sms_user_role`（user_id, role_id，SmsRoleMapper.java:36-46）、`sms_role_menu`（role_id, menu_id，SmsRoleMapper.java:48-60）。
- 用户菜单查询：`sms_menu` ⋈ `sms_role_menu` ⋈ `sms_user_role`（SmsMenuMapper.xml:392-404）。
- 菜单数据只用于**前端导航渲染**，不参与任何服务端访问控制。

---

## 5. 数据库表（从实体与 mapper 推断）

数据库：`duanxin_pingtai`（application.yml:5）。

| 表名 | 实体 | 来源 | 主要字段 |
|---|---|---|---|
| `sms_user` | SmsUser | SmsUserMapper.xml | id, username, password(密文), salt, nickname, created/create_id, updated/update_id, is_delete, extend1-4 |
| `sms_role` | SmsRole | SmsRoleMapper.xml | id, name, created/create_id, updated/update_id, is_delete, extend1-4 |
| `sms_menu` | SmsMenu | SmsMenuMapper.xml | id, name, parent_id, url, icon, type, sort, 审计字段, is_delete, extend1-4 |
| `sms_user_role` | — | SmsRoleMapper.java:41 | user_id, role_id |
| `sms_role_menu` | — | SmsRoleMapper.java:48 | role_id, menu_id |
| `client_business` | ClientBusiness | ClientBusinessMapper.xml（生成） | id, corpname, apikey, ip_address, is_callback, callback_url, client_linkname, client_phone, client_filters, 审计字段, is_delete, extend1-4（**extend1 存归属运营用户 id**，见 §8.2） |
| `client_balance` | ClientBalance | ClientBalanceMapper.xml | id, client_id, balance, created/create_id, updated/update_id, is_delete, extend1-3；原子更新 `debitBalanceAtomic`/`rechargeBalanceAtomic`/`adjustBalanceAtomic`（73-108 行） |
| `client_channel` | ClientChannel | ClientChannelMapper.xml | id, client_id, channel_id, extend_number, price, 审计字段, is_delete |
| `client_sign` | — | ClientSignMapper.xml | 客户签名（client_id、sign_info 等） |
| `client_template` | — | ClientTemplateMapper.xml | 客户模板（sign_id、template_text、template_type、template_state、use_id、use_web 等） |
| `channel` | Channel | ChannelMapper.xml | id, channel_name/type/area/area_code/price/ip/port/username/**password(明文)**/channel__number/spNumber、channel_protocal、is_available, 审计字段, is_delete |
| `mobile_black` | MobileBlack | MobileBlackMapper.xml | id, black_number, black_type, client_id, create_id/update_id, is_delete |
| `mobile_dirtyword` | MobileDirtyWord | MobileDirtyWordMapper.xml | id, dirtyword, owntype, create_id/update_id, is_delete |
| `mobile_transfer` | MobileTransfer | MobileTransferMapper.xml | id, transfer_number, area_code, init_isp, now_isp, is_transfer 等 |
| `mobile_area` | MobileArea | MobileAreaMapper.xml | id, mobile_number(号段), province_code, mobile_area, mobile_type, area_code, post_code, 审计字段 |
| `code_limit` | CodeLimit | CodeLimitMapper.xml | id, limit_time, limit_count, description, extend1(存 limitState), create_id/update_id, is_delete |
| `schedule_job` | ScheduleJob | ScheduleJobMapper.java（注解 SQL） | job_id, bean_name, method_name, params, cron_expression, remark, status, create_time, update_time |
| `schedule_log` | ScheduleLog | ScheduleLogMapper.java（注解 SQL） | log_id, job_id, bean_name, method_name, params, status, times, error, create_time |

生成器配置 `resources\generatorConfig.xml` 目前只生成 `client_business`（46 行）；SmsUser/SmsRole/SmsMenu 为早期生成产物（43-45 行被注释）。generatorConfig.xml 内也硬编码了 DB 账号密码（14-17 行）。

---

## 6. 与其它模块 / Nacos 的交互

### 6.1 Feign 调用

| Client | 目标服务 | 接口 | 用途 |
|---|---|---|---|
| SearchClient（client\SearchClient.java:13） | beacon-search | POST `/search/sms/list`、`/search/sms/countSmsState` | 短信记录查询、状态统计 |
| ApiSmsClient（client\ApiSmsClient.java:13） | beacon-api | POST `/sms/internal/single_send`（带 `X-Internal-Token` 头） | 运营代发短信，单条循环调用（SmsManageServiceImpl.java:138） |
| BeaconCacheWriteClient（client\BeaconCacheWriteClient.java:23） | beacon-cache | `/cache/hmset/{key}`、`/cache/set/{key}`、`/cache/setnx/{key}`、`/cache/sadd/{key}`、`/cache/delete/{key}`、`/cache/delete/batch`、`/v2/cache/string/{key}`、`/cache/keys`、`/cache/pop/{key}`、`/cache/delete-if-match/{key}` | MySQL→Redis 缓存同步写删（逻辑 key，前缀由 beacon-cache 追加） |

### 6.2 beacon-cache 调用的鉴权

`CacheFeignAuthConfig`（config\CacheFeignAuthConfig.java）为 BeaconCacheWriteClient 增加 RequestInterceptor：以 caller+timestamp+method+path 构造 payload 并用 secret 做 HMAC 签名，写入 `CacheAuthHeaders` 三个请求头（50-83 行）；同时配置超时（88-91 行）、错误解码与统一日志（96-118 行）。caller/secret 来自 application.yml `cache.client.auth.*`（76-77 行，明文）。

### 6.3 Nacos

- 服务发现：注册到 `192.168.88.128:8848`（application.yml:17-20）。
- 配置中心：同样地址 + `file-extension: yml`（22-25 行）——Spring Boot 2.3 无 bootstrap，Nacos 配置中心实际是否生效取决于父工程依赖（本模块 pom 只引入了 discovery starter，**未引入 `spring-cloud-starter-alibaba-nacos-config`**，见 pom.xml:70-73 行；application.yml 中 config 段目前是“配置了但无对应 starter”）。
- 主动读取 Nacos：`SysApiGatewayFilterController.readSnapshot()`（57-84 行）直接用 `NacosFactory.createConfigService` 读 `beacon-api-dev.yml`（DEFAULT_GROUP）并解析 `filters` 字段展示，只读。

### 6.4 缓存同步体系（本模块核心设计）

- 以 MySQL 为真源、Redis 为派生缓存的最终一致方案（见 `src\docs\21_mysql_redis_sync_fix_guide.md` 的定位说明）。
- 三层同步：
  1. **运行时同步**：写路径（ClientBusinessServiceImpl.save/update/deleteBatch 等）在事务提交后（`CacheSyncRuntimeExecutor.runAfterCommitOrNow`）调 `CacheSyncService.syncUpsert/syncDelete` 写 beacon-cache；全局开关 `sync.enabled: false`（application.yml:52）当前关闭。
  2. **手工重建**：`CacheRebuildController` → `CacheRebuildServiceImpl.rebuildDomain`，域加载器在 `rebuild\loader\` 下（Channel/ClientBalance/ClientBusiness/ClientChannel/ClientSign/ClientTemplate/Transfer/Black/DirtyWord 共 9 个 DomainRebuildLoader）。
  3. **启动校准**：`rebuild\CacheBootReconcileRunner`。
- 关键组件：`config\CacheSyncProperties`（`sync.*` 配置模型 + 命名空间一致性校验，86-91 行要求 `sync.redis.namespace` 与 `cache.namespace.fullPrefix` 一致，否则启动失败）、`config\CacheNamespaceConsistencyGuard`（空壳守卫类）、`support\CacheKeyBuilder`（逻辑 key 构建）、`support\CacheSyncLogHelper`（统一同步日志）。

### 6.5 定时任务执行机制

- `ScheduleJobServiceImpl`：启动时 `@PostConstruct` 把所有 DB 任务注册到 Quartz（33-46 行）；save/update/pause/resume/run 同步维护 Quartz（63-208 行）；cron 表达式用 `CronExpression.isValidExpression` 校验（226 行）。
- `ScheduleInvokeServiceImpl.doInvoke`（schedule\ScheduleInvokeServiceImpl.java:60-86）：按 DB 中 `bean_name` 从 Spring 容器取 bean，反射调用 `method_name`（支持 `(String params)` 与无参两种签名），`ReflectionUtils.makeAccessible` 后可调用任意 public/非 public 方法；执行结果与耗时、异常栈写入 `schedule_log`（32-58 行）。

---

## 7. 前端静态资源

- 本模块 `src\main\resources` 下**没有任何 static/webapp 目录或 html/js/css 文件**（glob 验证无 `static/**`、无 `bootstrap*.yml`），只有 `mapper\*.xml`、`application.yml`、`generatorConfig.xml` 和 `docs\`。
- Shiro 配置中的 `loginUrl: /login.html`（application.yml:37）与大量静态资源 anon 规则（ShiroConfig.java:42-51）说明历史上前端页面曾由本模块（或期望由本模块）提供；当前实现为**纯 REST 后端**，前端应部署在其它服务/网关（本项目未含前端模块）。
- 结论：beacon-webmaster 自身不提供前端静态资源，管理前端为独立部署，通过 Nacos 服务名调用本服务接口。

---

## 8. 值得注意的设计点与潜在问题

### 8.1 鉴权与安全（高危）

1. **授权缺失（最严重）**：`ShiroRealm.doGetAuthorizationInfo` 返回 null（relam\ShiroRealm.java:60-62），无任何服务端权限控制，**任何登录用户 = 超级管理员**，可操作全部接口（包括改用户、分配角色菜单、充值、缓存重建、定时任务）。
2. **内部扣费接口无鉴权**：`/internal/balance/debit` 在 Shiro 链中为 anon（ShiroConfig.java:55），且 `internal.balance.token` 默认为空=跳过校验（InternalBalanceController.java:78、application.yml:47-48）。默认配置下**任何人可匿名调扣费接口**（受默认余额下限 -10000 约束，BalanceCommandServiceImpl.java:42）。
3. **JWT 密钥硬编码**：`"beacon-webmaster-secret-key-2026"`（util\JwtUtil.java:18），任何人拿到源码即可伪造任意用户 token。
4. **权限判定用角色名字符串**：`ROOT = "管理员"`（beacon-common WebMasterConstants.java:14），多处 `roleNameSet.contains(ROOT)`（ClientBusinessController.java:132/164、CacheRebuildController.java:68、SearchController.java:69、SmsManageServiceImpl.java:208）——数据库角色改名为“管理员”即可提权；角色名改动即破坏授权。
5. **万能验证码**：`system.test-kaptcha: "1111"` 使登录验证码可绕过（application.yml:42-43、SmsUserController.java:63）；验证码存无 TTL 的静态内存 Map（KaptchaController.java:32），可被无限占用且不清理（内存膨胀），注释也自认“生产应使用 Redis”（31 行）。
6. **弱密码策略**：MD5+盐+1024 次迭代（SmsUserServiceImpl.java:147-149），强度不足；新增用户密码为空时默认 `123456`（70-72 行）。
7. **改密码接口是空壳**：`/sys/user/password` 直接返回“修改成功”但不做任何事（SmsUserController.java:122-126）——运营侧无真实改密能力，且接口行为具有误导性。
8. **密码哈希泄露**：`/sys/user/list` 通过 `SysUserConverter.toView(user, false)` 把**密码哈希**原样返回给前端（converter\SysUserConverter.java:44）；`SmsUser.toString()` 含 password 和 salt（entity\SmsUser.java:146-164），配合日志（如 SmsUserController.java:54 打印 userDTO）存在泄露面。
9. **通道密码明文存储/下发**：`channel.channel_password` 明文存库（entity\Channel.java:18、ChannelMapper.xml:36/101），且 `/sys/channel/list|info` 直接回传实体。
10. **CORS 反射 Origin**：JwtFilter.preHandle 将请求 Origin 反射回 `Access-control-Allow-Origin`（shiro\JwtFilter.java:60），任意站点可携带 Authorization 头跨域调用；header 名大小写不规范（60-62 行）。
11. **反射执行任意 Bean 方法**：定时任务由 DB 可配置的 bean_name/method_name 反射执行（ScheduleInvokeServiceImpl.java:60-86），结合问题 1 的授权缺失，普通登录者可注册执行任意 Spring Bean 方法的任务。
12. **GET 请求做充值**：`/sys/client-business/pay` 用 GET 触发余额充值（ClientBusinessController.java:151-152），状态变更类操作应使用 POST，且易被链接诱导/浏览器缓存。

### 8.2 数据与一致性设计问题（中危）

13. **内存假存储**：`AcountServiceImpl`（充值流水，AcountServiceImpl.java:26）与 `LegacyCrudServiceImpl`（activity/apimapping/grayrelease/publicparams/notify/searchparams/clientsign/clienttemplate 等“遗留域”，LegacyCrudServiceImpl.java:83）使用 `ConcurrentHashMap` 作为数据源，**服务重启数据全部丢失**，且与数据库真实表（client_sign/client_template 等）脱节——两个服务各自维护一套 clienttemplate 数据（SysSmsTemplateController 走 DB，SysLegacyCrudController 走内存）。
14. **用户-客户归属用 extend1 字段**：`client_business.extend1` 存运营用户 id（ClientBusinessServiceImpl.java:68-72 `andExtend1EqualTo(userId + "")`），关系建模不规范，无索引、易被普通字段逻辑污染。
15. **内存分页**：多个 list 接口先全表查出再 `subList`（如 SysUserController.java:44-48、SysClientController.java:45-49、SysMenuController.java:43-48、ClientBusinessController.java:63-67），数据量大时性能与内存风险。
16. **`/sys/account` 与 `/sys/acount` 双路径**（SysAcountController.java:20，拼写兼容）及多处双前缀路由（`/sys/client-business`+`/sys/clientbusiness` 等）说明是历史命名兼容，增加攻击面与维护负担。
17. **同步链路默认关闭**：`sync.enabled: false`（application.yml:52），运行时缓存同步/重建/校准全部失效，Redis 依赖其它途径（或 beacon-cache 侧）维护——与 docs 描述的“三层同步闭环”不一致，需确认部署环境是否覆盖该值。
18. **缓存重建并发保护依赖可选依赖**：`CacheRebuildCoordinationSupport` 允许为 null（CacheSyncServiceImpl.java:105-121 构造器注释：无并发协调时“手工重建相关并发避让能力保持关闭”）。

### 8.3 SQL 注入评估（低风险，但需注意）

- 全部手写 mapper（Channel/ClientBalance/ClientChannel/MobileBlack/MobileDirtyWord/MobileTransfer/MobileArea/CodeLimit/ClientSign/ClientTemplate/ClientChannel）与 Schedule 注解 SQL 均使用 `#{}` 参数绑定，`keyword` 用 `concat('%',#{keyword},'%')`，未发现拼接注入点。
- 生成 XML（SmsUser/SmsRole/SmsMenu/ClientBusiness Mapper.xml）中的 `${criterion.condition}`、`${orderByClause}` 为 MyBatis Generator 标准产物；condition 由 Example 内部生成（不可由外部直接传入），orderByClause 在业务代码中为硬编码（如 `example.setOrderByClause("id desc")`，SmsUserServiceImpl.java:41、ClientBusinessServiceImpl.java:83）。**当前无外部可控 SQL 片段，暂无注入风险**；但若未来把 orderBy/sort 参数开放给前端，需严格白名单。
- SysApiGatewayFilterController 的 dataId 可经配置注入（36-37 行），仅供只读，风险低。

### 8.4 其它（低危/工程问题）

19. **配置硬编码**：数据库 root/123（application.yml:5-7、generatorConfig.xml:14-17）、Nacos 内网 IP（application.yml:20/23）、cache secret（application.yml:77）均明文提交在仓库。
20. **依赖老化**：Shiro 1.4.0（存在已知 CVE）、mysql-connector 5.1.49、Spring Boot 2.3.12/Hoxton.SR12 均已 EOL。
21. **应用级密码校验逻辑重复**：登录密码校验写在 Controller（SmsUserController.java:79）而非 Realm；Shiro 的 login 机制只用于 JWT 校验，两套机制并存。
22. **事务边界**：`SmsUserController.updatePassword`（Service 层真实实现存在，SmsUserServiceImpl.java:118-135）并未被 Controller 调用——Controller 里是空实现（见问题 7），死代码。
23. **限流/审计缺失**：登录无失败次数限制（仅靠可绕过的验证码）；除 schedule_log 外，管理操作无统一审计日志。

---

## 9. 总结

beacon-webmaster 是一个功能面较全的短信运营后台：JWT+Shiro 认证、RBAC 表模型（用户/角色/菜单）、MySQL 业务库 + Quartz 定时任务 + Feign 微服务调用 + MySQL→Redis 三层缓存同步体系（该体系有专门的 docs 说明，工程化程度较高）。但**服务端授权完全缺失**（Realm 返回 null、角色名字符串判断、内部扣费接口匿名且令牌默认为空）、**JWT/DB/缓存密钥全部硬编码**、部分管理域数据存内存（重启即丢）、通道密码明文、改密接口为假实现等问题较为突出，作为面向公网的运营后台存在明显的安全与数据可靠性隐患。
