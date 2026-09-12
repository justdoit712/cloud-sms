# 02 · Sa-Token 入门与 Shiro 迁移对照（学习笔记）

> 面向：beacon-webmaster 从 Shiro 1.4.0 + JWT 迁移到 Sa-Token 1.46.0（boot3 starter）的学习路径。
> 状态：✅ 完成（纯学习文档，无代码改动）
> 迁移蓝图：本文档第 3、4 节；现状问题：分析报告 `../analysis/report_webmaster.md`（Shiro 授权缺失等 🔴 问题）。

---

## 1. 为什么弃 Shiro 换 Sa-Token

| 维度 | Shiro 现状（本项目） | Sa-Token |
| --- | --- | --- |
| Jakarta EE 适配 | 1.4.0 是 javax 时代产物，与 Boot 3/Tomcat 10 不兼容；官方 Jakarta 支持长期滞后 | 官方 `sa-token-spring-boot3-starter` 原生支持 |
| 前后端分离 | 本项目用"Shiro + 自研 JwtFilter + 自研 JwtToken"拼出来的 Token 态 | Token 态是**第一公民**（登录/踢人/续期/注销开箱即用） |
| 授权 | 本项目 `ShiroRealm.doGetAuthorizationInfo` 返回 null → **无服务端授权**（分析报告 🔴#1） | `StpInterface` 两方法搞定角色/权限列表，配合注解即用 |
| 会话存储 | 本项目关闭了 session（无状态 JWT，24h 不可撤销） | Token 可存 Redis（sa-token-redis-jackson），支持**踢人下线、强制注销** |
| 学习成本 | — | 轻量（单 jar，无 Spring Security 的复杂度） |

> Sa-Token 定位：轻量 Java 权限认证框架，官网 https://sa-token.cc 。核心思想：**登录 = 给一个 token；鉴权 = 查这个 token 的角色/权限**。

## 2. Sa-Token 核心概念（30 分钟入门）

### 2.1 四个核心 API（`StpUtil` 静态门面）

```java
StpUtil.login(10001);                 // 会话登录：为 loginId 签发 token，写入登录态
StpUtil.isLogin();                    // 当前会话是否登录
StpUtil.getLoginId();                 // 取当前会话的 loginId（即登录时传入的业务主键）
StpUtil.checkLogin();                 // 未登录抛 NotLoginException
StpUtil.logout();                     // 当前会话注销（token 即刻失效）

StpUtil.checkPermission("user:add");  // 无权限抛 NotPermissionException
StpUtil.checkRole("admin");           // 无角色抛 NotRoleException
StpUtil.getTokenValue();              // 取当前会话 token（登录后返回给前端）
```

### 2.2 权限数据从哪来：实现 `StpInterface`

Sa-Token 本身不存权限，它通过 **SPI 回调**向你的业务要数据：

```java
@Component
public class StpInterfaceImpl implements StpInterface {
    // 返回指定 loginId 的权限码列表（本项目：菜单 url 集合）
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 查 sms_user -> sms_user_role -> sms_role_menu -> sms_menu，返回 url 列表
    }
    // 返回指定 loginId 的角色名列表
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 查 sms_user -> sms_user_role -> sms_role，返回角色 name 列表
    }
}
```

对应关系：本项目 `ShiroRealm.doGetAuthorizationInfo` 该干而没干的事，就是这两个方法（分析报告指出它返回 null）。**迁移时顺手把授权闭环补上。**

### 2.3 拦截器与注解

```java
// 拦截器模式：路径级规则
registry.addInterceptor(new SaInterceptor(handler -> {
    SaRouter.match("/**").notMatch("/sys/login", "/sys/auth/**").check(r -> StpUtil.checkLogin());
})).addPathPatterns("/**");

// 注解模式：方法级规则（需在 SaInterceptor 构造器传 isAnnotation=true）
@SaCheckLogin                     // 必须登录
@SaCheckRole("admin")             // 必须拥有角色
@SaCheckPermission("user:add")    // 必须拥有权限
```

### 2.4 前后端分离配置（本项目直接采用）

```yaml
sa-token:
  token-name: satoken       # Header 名 / 参数名
  timeout: 86400            # token 有效期（秒）
  is-read-cookie: false     # 前后端分离：关闭 Cookie
  is-read-header: true      # 从 Header 读取 token
  is-concurrent: true       # 同账号可多处登录
  token-style: uuid         # token 形态（默认 36 位 uuid，也可 random-64 等）
```

### 2.5 Redis 会话存储

```xml
<dependency>
  <groupId>cn.dev33</groupId>
  <artifactId>sa-token-redis-jackson</artifactId>
</dependency>
<!-- 另需 spring-boot-starter-data-redis + commons-pool2 -->
```

引入后 Sa-Token 自动把登录态/权限缓存放 Redis（键前缀 `satoken:`），获得：
- **多实例共享登录态**（本项目将来若 webmaster 多实例部署）；
- **踢人下线**：`StpUtil.kickout(loginId)`；
- 注销即时生效（不再像旧 JWT 24h 内不可撤销）。

> 旧实现痛点对照：本项目旧 JWT（HS256，密钥硬编码）一旦签发 24h 内无法撤销，且密钥泄漏可伪造任意身份（分析报告 🔴#3）。Sa-Token 的 token 是随机值 + Redis 会话，天然规避"伪造"与"无法撤销"两类问题。

## 3. Shiro → Sa-Token 逐项迁移对照表

| 现有（beacon-webmaster） | 迁移后（Sa-Token） | 说明 |
| --- | --- | --- |
| `shiro-spring-boot-web-starter` 依赖 | `sa-token-spring-boot3-starter` + `sa-token-redis-jackson` | pom 替换 |
| `ShiroConfig`（过滤链 anon/jwt/logout） | `SaTokenConfig`（SaInterceptor + excludePathPatterns） | 迁移 4.1 节的路径白名单：`/sys/login`、`/sys/auth/**`、静态资源 |
| `JwtFilter`（Bearer token 解析+CORS） | 删除；CORS 交给独立 `WebMvcConfigurer`；token 读取由 Sa-Token 完成 | 前端 axios 头改为 `satoken: xxx` |
| `JwtToken` + `ShiroRealm.doGetAuthenticationInfo`（JWT 验签+查库） | 登录时 `StpUtil.login(userId)`；`StpInterfaceImpl.getPermissionList/getRoleList` 查库 | 认证逻辑收进登录接口；授权闭环补上 |
| `JwtUtil`（HS256，硬编码密钥） | 删除 | Sa-Token 不再需要自建 JWT |
| `Subject.login/logout`、`SecurityUtils.getSubject()` | `StpUtil.*` | 全局替换 |
| 角色名硬编码判断 `"管理员".equals(role)`（WebMasterConstants.ROOT） | `StpUtil.checkRole(...)` / `@SaCheckRole` | 消除字符串判权（分析报告 🔴#4） |
| 无（授权缺失） | `@SaCheckPermission` 按菜单 url 码控权 | **新增强制**，修复 🔴#1 |

## 4. 本项目的落地设计（beacon-webmaster）

### 4.1 登录接口改造（对照旧 `SmsUserController.login`）

```java
@PostMapping("/login")
public ResultVO<String> login(@RequestBody @Valid LoginForm form) {
    // 1. 校验验证码（Redis 中 uuid -> code，用后即删）
    // 2. 校验账号密码（沿用 MD5+盐+1024 迭代——不在本次升级范围）
    // 3. 登录态签发
    StpUtil.login(user.getId());
    return Result.ok(StpUtil.getTokenValue());
}
```

### 4.2 权限码设计

利用现有 RBAC 表（sms_user / sms_role / sms_menu + 2 张关联表）：

- **角色**：`getRoleList` 返回 `sms_role.name` 列表。
- **权限**：`getPermissionList` 返回 `sms_menu.url` 列表（去掉 `.html`），注解侧用 `@SaCheckPermission("sys/user/list")` 之类的 url 码。菜单数据从"仅前端渲染"升级为"前后端双用"。

### 4.3 全局异常映射

```java
NotLoginException    -> HTTP 401, code=401（前端 axios 据此跳登录页）
NotPermissionException -> HTTP 403, code=403
NotRoleException     -> HTTP 403, code=403
```

与前端认证约定呼应：401 触发前端清理登录态并跳登录。

### 4.4 验证码接口化

- `GET /sys/auth/captcha` → `{uuid, img(base64)}`，文本存 Redis（key=`captcha:{uuid}`，TTL 5 分钟，**替代旧的无 TTL 内存 Map**，顺带修复分析报告 🔴#5 的内存膨胀问题）。
- `POST /sys/login` 带 `uuid/code/username/password`。
- 保留 KaptchaConfig（kaptcha 0.0.9 的 servlet-api 依赖是 provided，不会污染 Boot 3）。

## 5. 学习资源与验证方法

- 官网文档：https://sa-token.cc/doc.html#/（登录认证 / 权限认证 / 前后台分离 / Redis 集成章节）
- 验证路径：启动 webmaster → `POST /sys/login` 拿 token → 带 `satoken` 头访问受保护接口 → 未带 token 应 401；无权限账号访问受保护接口应 403。

## 6. 遗留风险提示（重构后仍要关注）

1. 密码仍是 MD5+盐+1024 迭代（强度不足）——按"业务缺陷后处理"原则，本轮只迁移不改算法。
2. 万能验证码 `1111`、默认密码 `123456` 属于业务缺陷，后处理阶段关闭。
3. `SaTokenConfig` 的白名单必须与旧 ShiroConfig 的 anon 列表逐条核对，漏配会导致 401 事故。
4. 前端 `utils/request.ts` 的 Header 名需同步从 `Authorization: Bearer` 改为 `satoken`（已约定）。

---

*下一篇：03 ES 8 新 Java Client 与 beacon-search 改造。*
