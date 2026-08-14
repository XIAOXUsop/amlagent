# Cookie 认证与 CSRF 模型

## 认证

- 登录签发 JWT，写入 **HttpOnly Cookie**（`aml_token`），响应体不返回 JWT，前端不写 localStorage。
- `JwtAuthenticationFilter` 只从 `Authorization: Bearer` 或 `aml_token` Cookie 读取，不再支持 `?token=` Query 回退。
- 刷新后通过 `GET /api/auth/me` 恢复登录态；`POST /api/auth/logout` 清除 Cookie。

## Cookie 属性

- `HttpOnly=true`（JS 不可读，防 XSS 窃取）。
- `SameSite=Lax`（额外防护，非唯一）。
- `Secure` 由 `aml.security.cookie-secure` 控制，**prod 强制 true**（`ProductionConfigValidator` 校验）。

## CSRF 防护

HttpOnly Cookie 认证下浏览器自动携带认证信息，必须启用 CSRF：

- Spring Security `CookieCsrfTokenRepository.withHttpOnlyFalse()`：生成可读 `XSRF-TOKEN` Cookie，
  校验写请求的 `X-XSRF-TOKEN` header 与 Cookie 一致。
- 前端 Axios 拦截器从 `XSRF-TOKEN` Cookie 读取并写入 `X-XSRF-TOKEN` header（POST/PUT/DELETE/PATCH）。
- `login` 与 Actuator/Swagger 忽略 CSRF；logout 与所有状态变更接口强制校验。

## 角色边界

- `/api/queues/**` 限 ADMIN（死信查看/重放）。
- case 创建/处理/重试限 ANALYST/ADMIN。
- `/api/reviews/**` 限 REVIEWER/ADMIN。
- `/api/eval/**` 限 ADMIN。
- 演示账号仅 `!prod` 注册；prod 从环境变量初始化管理员。
