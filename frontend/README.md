# amlagent 前端

Vue 3 + TypeScript + Vite 写的控制台：工单看板、工作流监控、尽调报告、客户管理与 AI 小助。

> **这是 `amlagent` 项目的一部分。** 项目整体说明、启动方式、架构与评测数据都在
> [仓库根目录的 README](../README.md)——本文件只讲前端自己怎么跑、代码怎么放。
>
> （**这份文件原先原样留着 `create-vue` 的模板说明**（"This template should help you
> get started developing with Vue 3…"）。它说的不是这个项目，也不是这个仓库的用法，
> 2026-09-19 换掉了。同族的模板残留清理见 IDEA 插件那边。）

## 跑起来

前端**不单独启动**——它要连后端。按根 README 的「快速启动」三步走：
先 `docker compose up -d` 起基础设施，再在 `backend/` 起 Spring Boot，最后在这里：

```bash
npm install
npm run dev          # http://localhost:5173
```

开发服务器的 `/api` **代理到 `http://localhost:8080`**（后端），REST 与 SSE 实时推送
都走这条路径——所以浏览器里不需要配 CORS，也不要在前端代码里写死后端地址。

## 命令

| 命令                   | 作用                                                                                                          |
| ---------------------- | ------------------------------------------------------------------------------------------------------------- |
| `npm run dev`          | 开发服务器（端口 5173，见上）                                                                                 |
| `npm run build`        | `vue-tsc -b && vite build`——**类型检查是构建的一部分**，类型不过就构建不出来                                  |
| `npm run lint`         | ESLint，带 `--max-warnings=0`：**警告也算失败**                                                               |
| `npm run format:check` | Prettier 检查（`npm run format` 写回）                                                                        |
| `npm test`             | Vitest 单元测试，全部离线，不依赖后端                                                                         |
| `npm run test:json`    | 同上，结果写到 `.reports/vitest.json`                                                                         |
| `npm run test:e2e`     | Playwright 端到端；它自己会 `npm run dev` 起前端（`reuseExistingServer: true`），但**后端与基础设施得先跑着** |

> ⚠️ `test:json` 的输出路径不是随便定的：根目录的 `scripts/test_summary.py` 读的就是
> `frontend/.reports/vitest.json`，用来统计前端那一栏的测试数。改这个路径会让统计脚本
> 读不到前端数据（它会把那一栏标成"未执行"，而不是 0——但仍然是错的）。

## 代码怎么放

```
src/
├── api/          接口调用与**契约**。contracts.ts / response-contracts.ts 描述后端返回的
│                 形状，*.spec.ts 对着它断言——前后端形状对不上时在这里红，而不是等到运行时
├── components/   可复用组件（assistant/ 是 AI 小助那一块）
├── constants/    领域常量
├── router/       路由表
├── utils/        纯函数：错误映射、脱敏展示、评测解释…测试与实现同目录
└── views/        页面
e2e/              Playwright：auth / case-flow / case-closure 三条链路
```

**`.vue` 旁边那个同名 `.controller.ts` 是这个项目的约定**：把能测的逻辑从组件里拿出来，
组件只留渲染与事件绑定；单元测试覆盖的正是那些 controller 与 `utils/`。加新页面时照这个
形状放，别把逻辑写进 `.vue`。
