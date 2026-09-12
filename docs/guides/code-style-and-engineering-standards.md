# AML 尽调 Agent 平台代码与工程规范

> 适用范围：本仓库中的 Java 21 / Spring Boot 3.5 后端、Vue 3 / TypeScript 前端、Python 辅助脚本、Flyway 数据库迁移、自动化测试、配置和技术文档。
>
> 本规范是项目级基线。它综合 Spring、Vue、TypeScript、OWASP、Flyway、Google Engineering Practices 等业界规范，并根据金融风控、反洗钱和智能 Agent 系统的特点进行了补充。

## 1. 目标与基本原则

本规范的目标不是统一个人偏好，而是降低以下工程风险：

- 业务规则因代码表达不清而被误改；
- 金额、时间、状态机、并发或事务处理不一致；
- 权限、审计、隐私数据和模型输出处理不安全；
- 模块边界不断退化，形成难以测试和修改的超大类；
- 格式争论占用评审时间；
- 存量代码一次性重写，导致大量无意义 diff 和回归风险。

项目遵循以下原则：

1. **正确性优先于简洁性，简洁性优先于技巧性。**
2. **业务含义应通过类型、命名和结构表达，而不是依赖注释猜测。**
3. **格式交给工具，设计交给评审，正确性交给测试和静态分析共同保证。**
4. **默认拒绝不安全输入，默认保护敏感数据，默认保留关键审计证据。**
5. **新代码必须符合规范，存量代码按修改范围渐进治理。**
6. **自动化规则优先。能够由工具可靠判断的规则，不依赖人工记忆。**

## 2. 规范级别

本文使用以下关键词：

- **必须（MUST）**：违反后不得合并；如确有例外，必须在 PR 中说明原因并获得评审者认可。
- **禁止（MUST NOT）**：不可使用，除非经过明确的架构或安全评审。
- **建议（SHOULD）**：默认遵循；偏离时应能说明带来的收益。
- **可选（MAY）**：团队允许采用，但不得破坏一致性或增加不必要复杂度。

当自动化工具和本文冲突时：

1. 已提交并由 CI 执行的项目配置优先；
2. 若配置明显落后，应在独立 PR 中修正规范或配置；
3. 禁止在业务 PR 中临时关闭规则以绕过检查。

## 3. 渐进实施与例外

### 3.1 存量代码治理

- 新增文件必须完整符合本规范。
- 修改存量文件时，必须保证修改行及直接相关代码不产生新的违规。
- 建议顺手修复局部、低风险的问题，但禁止借业务变更进行无关的大规模重排或格式化。
- 大规模格式化、重命名、包迁移必须使用独立 PR，不得和业务逻辑修改混合。
- 自动化门禁优先检查新增和修改代码；全库历史问题建立基线后逐步消除。

### 3.2 例外管理

确需偏离规范时，应在代码附近或 PR 描述中记录：

- 偏离了哪一条规则；
- 为什么常规方案不可用；
- 风险如何被测试、监控或其他机制覆盖；
- 例外是永久性的还是有清理期限。

禁止使用没有原因说明的全局规则关闭，例如：

```java
@SuppressWarnings("all") // 禁止：范围过大且没有解释
```

允许最小范围、有原因的抑制：

```java
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // 法规原文必须保持逐字一致
private static final String REGULATORY_TEXT = "...";
```

## 4. 通用源文件规范

### 4.1 编码与换行

- 所有文本文件必须使用 UTF-8。
- 统一使用 LF 换行符。
- 文件末尾必须保留一个换行符。
- 禁止尾随空格和无意义的连续空行。
- 缩进必须使用空格，不得混用 Tab；Makefile 等由语法强制使用 Tab 的文件除外。
- Java 使用 4 个空格缩进；TypeScript、Vue、JSON、YAML 使用 2 个空格缩进。
- 代码行宽上限为 120 个字符。不可读的长 URL、哈希、法规原文等可例外。

### 4.2 文件内容

- 一个源文件应表达一个主要职责。
- 禁止保留已注释掉的旧代码；历史由 Git 保存。
- 禁止提交调试输出、临时断点、个人路径、IDE 缓存、构建产物和真实凭证。
- TODO 必须说明后续动作，建议关联任务编号。

```java
// 不推荐
// TODO fix later

// 推荐
// TODO(AML-248): 监管规则版本化完成后移除旧版兼容分支。
```

### 4.3 命名

命名必须优先表达业务语义，不得使用含义模糊的缩写。

| 对象 | 规则 | 示例 |
|---|---|---|
| Java 类、接口、枚举、Record | `UpperCamelCase` | `CaseReviewService` |
| Java 方法、字段、局部变量 | `lowerCamelCase` | `calculateRiskScore` |
| 常量 | `UPPER_SNAKE_CASE` | `MAX_RETRY_COUNT` |
| Java 包 | 全小写，按领域划分 | `com.bank.aml.casework` |
| Vue 组件文件 | `PascalCase.vue` | `CaseReviewPanel.vue` |
| TypeScript 类型 | `PascalCase` | `CaseReviewResult` |
| TypeScript 变量和函数 | `camelCase` | `loadCaseDetail` |
| 数据库对象 | `snake_case` | `case_review_record` |
| REST 路径 | 小写复数名词、连字符 | `/api/case-reviews` |

禁止无明确含义的命名：

```java
// 不推荐
var x = service.doIt(data);

// 推荐
RiskAssessment assessment = riskAssessmentService.assess(customerSnapshot);
```

通用缩写只在团队已达成共识时使用，例如 `id`、`url`、`http`、`dto`。同一概念必须使用同一术语，例如不可在不同模块中混用 `case`、`task`、`ticket` 表示同一种尽调工单。

### 4.4 注释与文档

- 注释应解释“为什么”“约束是什么”“业务来源是什么”，不得复述代码。
- 复杂监管规则必须注明法规、规则版本或内部制度来源。
- 公开 API、关键扩展点、非显然的金额/时间/状态机规则建议使用 Javadoc 或 TSDoc。
- 修改逻辑后必须同步修改注释；错误注释比没有注释更危险。
- 注释语言以中文为主；标识符、标准技术名词和协议字段保留英文。

```java
// 不推荐：复述代码
// 将 retryCount 加一
retryCount++;

// 推荐：说明原因和不变量
// 每次重新入队都占用一次重试额度，避免消息发布成功但消费失败时无限循环。
retryCount++;
```

### 4.5 Python 辅助脚本

- Python 脚本遵循 PEP 8，并由 Ruff 统一执行格式化、import 排序和静态检查。
- 使用 4 个空格缩进、LF 换行和 120 字符行宽；字符串默认使用双引号。
- 网络请求必须设置超时；命令行参数应通过 `argparse` 等标准解析器处理，不得手工拼接解析。
- 新增公共函数建议提供类型标注；脚本包含可复用业务逻辑时，应迁移到可测试模块并补充单元测试。
- 禁止在脚本中硬编码真实凭证、客户数据或生产环境地址；演示账号必须仅适用于本地隔离环境。

## 5. Java 代码规范

### 5.1 格式与导入

- Java 代码以 Spring 风格和 120 字符行宽为基线。
- 禁止通配符导入。
- 禁止在方法体中使用冗长的完全限定类名规避 import 冲突；有真实同名类型冲突时除外。
- 静态导入只用于测试断言、明确常量或能明显改善可读性的工厂方法。
- import 必须由格式化工具排序并移除未使用项。

```java
// 禁止
import java.util.*;

// 推荐
import java.util.List;
import java.util.Map;
```

### 5.2 类与方法设计

- 类必须具有单一、可描述的职责。
- 新增生产代码文件超过 500 行时必须重新评估设计；超过 800 行原则上不得合并，生成代码除外。
- 新增方法建议控制在 50 行以内；超过 80 行必须拆分或在 PR 中说明原因。
- 新增方法的认知复杂度建议不超过 15。
- 公共方法数量过多通常意味着职责过宽，应按用例或领域拆分。
- 优先组合而非继承。继承只用于稳定的“is-a”关系，不得为了复用几行代码建立继承层次。
- 避免静态全局可变状态。

不要机械地把长方法切成没有语义的 `step1()`、`step2()`。提取的方法名必须能表达业务动作：

```java
public ReviewDecision review(CaseSnapshot snapshot) {
    validateReviewEligibility(snapshot);
    EvidenceBundle evidence = collectRequiredEvidence(snapshot);
    RiskAssessment assessment = assessRisk(snapshot, evidence);
    return decideReviewOutcome(snapshot, assessment);
}
```

### 5.3 可变性与空值

- 依赖和不应变化的字段必须声明为 `final`。
- 优先使用不可变对象、`record` 和不可变集合表达快照、命令、结果。
- 集合返回值不得返回 `null`，应返回空集合。
- 普通业务参数不得使用 `Optional`；`Optional` 主要用于表示查询结果可能不存在。
- 禁止调用 `Optional.get()` 而不先验证存在性。
- 不得使用 `null` 同时表达“未知”“未提供”“查询不到”和“无需处理”等多个含义。

```java
public record ReviewCommand(
        long caseId,
        long expectedRevision,
        ReviewDecision decision,
        String reviewerId,
        String comment) {
}
```

### 5.4 集合与流式处理

- 根据访问方式选择集合，不得默认所有场景都使用 `ArrayList` 或 `HashMap`。
- 对外返回集合时，避免泄漏内部可变集合。
- Stream 用于清晰的数据变换；包含复杂分支、异常处理或副作用时优先使用普通循环。
- 禁止在并行流中执行数据库写入、远程调用或依赖线程上下文的操作。
- 循环或 Stream 内调用数据库时必须警惕 N+1 查询。

```java
// 不推荐：副作用和异常处理隐藏在 Stream 中
caseIds.parallelStream().forEach(caseId -> repository.updateStatus(caseId, RUNNING));

// 推荐：批量更新，事务和失败语义明确
int updatedRows = repository.markRunning(caseIds, workerId, startedAt);
verifyAllRowsUpdated(caseIds.size(), updatedRows);
```

### 5.5 异常处理

- 异常必须表达失败类型，不得使用通用 `RuntimeException` 代替领域异常。
- 禁止捕获异常后忽略。
- 禁止捕获 `Exception` 或 `Throwable`，除非处于任务执行、线程或协议适配的最外层边界；此时必须记录、转换或执行补偿。
- 业务校验失败、并发冲突、资源不存在、外部依赖失败必须区分处理。
- 日志和抛出异常不能重复记录同一失败；通常由能够补充完整上下文的边界层记录一次。
- 对外错误不得包含数据库结构、文件路径、堆栈、密钥或第三方敏感响应。

```java
// 不推荐
try {
    riskClient.assess(request);
} catch (Exception ex) {
    return null;
}

// 推荐
try {
    return riskClient.assess(request);
} catch (RiskClientTimeoutException ex) {
    throw new ExternalAssessmentUnavailableException(caseId, ex);
}
```

### 5.6 金额、比例与数值

- 金额、余额、汇率、阈值和需要精确计算的比例必须使用 `BigDecimal`，禁止使用 `float` 或 `double`。
- 每次除法必须明确精度和舍入模式。
- 金额必须和币种一起表达，禁止只传递裸数值。
- 数据库存储必须使用明确精度的 `DECIMAL`。
- 前端不得承担权威金额计算；权威结果必须由后端返回。
- 比较 `BigDecimal` 数值时使用 `compareTo`，不得依赖 `equals` 忽略不了的 scale 差异。

```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }
}

BigDecimal ratio = suspiciousAmount.divide(totalAmount, 4, RoundingMode.HALF_UP);
boolean exceedsThreshold = amount.compareTo(threshold) >= 0;
```

### 5.7 时间与时区

- 业务代码必须使用 `java.time`，禁止新增 `Date`、`Calendar`。
- 表示时间点优先使用 `Instant`；需要业务时区时使用 `ZonedDateTime` 或明确的 `ZoneId`。
- 禁止依赖服务器默认时区。
- 可测试的业务逻辑必须注入 `Clock`，不得散布 `Instant.now()`。
- 持久化和系统间传输统一使用 UTC；界面层按用户时区展示。
- 时间窗口必须明确边界是闭区间还是开区间。

```java
@Service
public class CaseExpiryService {
    private final Clock clock;

    public CaseExpiryService(Clock clock) {
        this.clock = clock;
    }

    public boolean isExpired(Instant expiresAt) {
        return !expiresAt.isAfter(clock.instant());
    }
}
```

### 5.8 并发与异步任务

- 并发安全不得依赖“通常不会同时发生”。
- 状态变更必须通过数据库条件更新、乐观锁、租约版本或其他原子机制保证。
- 分布式锁必须有超时、所有者标识和 fencing token；仅有 Redis `SETNX` 不足以防止旧 Worker 写入。
- 异步任务必须定义幂等键、重试上限、退避策略、死信处理和可观测状态。
- 禁止在事务提交前发布依赖该事务数据的外部消息；应使用 Transactional Outbox 等可靠模式。
- 禁止使用固定 `sleep` 协调并发。

## 6. Spring Boot 规范

### 6.1 分层与依赖方向

推荐依赖方向：

```text
Controller / HTTP Adapter
            ↓
Application Service / Use Case
            ↓
Domain Model / Domain Service
            ↓
Repository Port
            ↑
Infrastructure Adapter / JPA / Redis / LLM
```

必须遵循：

- Controller 不得直接访问 Repository。
- Domain 不得依赖 Controller、Servlet、JPA 实现类或具体第三方客户端。
- 基础设施异常必须在适配器或应用边界转换成项目可理解的异常。
- 包之间不得形成循环依赖。
- 测试数据源、Mock LLM 和演示实现不得进入生产核心路径。
- 跨领域调用通过明确的应用服务或端口完成，不得随意访问对方内部类。

### 6.2 依赖注入

- 必须使用构造器注入。
- 禁止字段注入。
- 一个类的构造器依赖过多时，应检查职责是否过宽，而不是使用字段注入隐藏问题。
- 可选依赖应通过明确配置或策略实现表达，不建议直接注入 `Optional<Bean>` 到核心业务类。

```java
// 禁止
@Autowired
private CaseRepository caseRepository;

// 推荐
private final CaseRepository caseRepository;

public CaseCommandService(CaseRepository caseRepository) {
    this.caseRepository = caseRepository;
}
```

### 6.3 Controller 与 DTO

- Controller 只负责认证上下文、协议校验、DTO 转换、调用用例和转换响应。
- 请求与响应必须使用独立 DTO，禁止公开 JPA Entity。
- DTO 字段必须有校验规则；嵌套对象使用 `@Valid`。
- 禁止公开接口返回 `Map<String, Object>` 或结构不稳定的匿名对象。
- HTTP 状态码、错误码和响应结构必须一致。
- 分页接口必须统一分页参数、最大页大小和排序字段白名单。

```java
public record CompleteReviewRequest(
        @NotNull Long expectedRevision,
        @NotNull ReviewDecision decision,
        @Size(max = 2_000) String comment) {
}

@PostMapping("/{caseId}/review-completions")
public ResponseEntity<CaseReviewResponse> completeReview(
        @PathVariable long caseId,
        @Valid @RequestBody CompleteReviewRequest request,
        Authentication authentication) {
    CaseReviewResult result = commandService.complete(caseId, request, authentication.getName());
    return ResponseEntity.ok(CaseReviewResponse.from(result));
}
```

### 6.4 Service 与事务

- 事务边界原则上位于应用服务的公开用例方法。
- 查询使用 `@Transactional(readOnly = true)`；写操作使用明确事务。
- 禁止在长事务中调用 LLM、HTTP 服务或执行不可控的文件处理。
- Spring 代理事务不得依赖同类内部调用生效。
- 捕获异常后继续提交事务时必须明确说明，否则应让异常触发回滚。
- 批处理必须定义部分成功还是全部回滚，不得留下模糊语义。
- 事务内数据库变更与异步消息必须使用 Outbox 等机制保证一致性。

### 6.5 Repository 与持久化

- Repository 方法名应表达查询意图，不得把业务决策塞入难以理解的超长派生查询名。
- 复杂查询使用明确 JPQL、Specification 或专用查询组件。
- 写状态时优先使用带旧状态、版本号或租约条件的原子更新。
- 条件更新后必须检查影响行数，`0` 行通常表示并发冲突或状态已变化。
- 禁止依赖 JPA 延迟加载在 Web 序列化阶段自动查询。
- 对高频查询、外键、唯一约束和幂等键建立数据库索引。

### 6.6 配置管理

- 配置使用类型安全的 `@ConfigurationProperties`，并通过 Bean Validation 校验。
- 禁止在代码中硬编码环境 URL、密钥、密码、超时时间和业务阈值。
- 所有外部调用必须配置连接、读取和整体超时。
- 生产环境不得自动创建或更新数据库结构，应使用 Flyway 并设置 `ddl-auto=validate`。
- 不同环境配置只允许改变部署参数，不应改变核心业务语义。

## 7. Vue 与 TypeScript 规范

### 7.1 基础规则

- Vue 单文件组件必须使用 `<script setup lang="ts">`，除非第三方兼容性要求其他形式。
- 必须启用 `vue-tsc` 类型检查。
- ESLint 应采用 Vue 3 推荐规则和 TypeScript 类型感知规则。
- Prettier 只负责格式，ESLint 负责质量和正确性；冲突规则由 `eslint-config-prettier` 关闭。
- 禁止新增显式 `any`。无法确定的外部数据使用 `unknown` 并验证。
- 禁止使用 `// @ts-ignore` 隐藏错误；特殊情况使用 `// @ts-expect-error` 并说明原因。
- 非空断言 `!` 只允许在程序不变量已得到其他机制保证时使用。

```ts
// 不推荐
function getErrorMessage(error: any) {
  return error.response.data.message
}

// 推荐
function getErrorMessage(error: unknown): string {
  if (axios.isAxiosError<ApiErrorResponse>(error)) {
    return error.response?.data.message ?? '请求失败'
  }
  return error instanceof Error ? error.message : '未知错误'
}
```

### 7.2 组件设计

- 组件应聚焦展示或单一交互流程。
- Vue SFC 超过 400 行时应评估拆分；超过 600 行原则上不得继续扩展。
- 页面组件负责组合，复杂业务逻辑提取到 composable 或领域模块。
- Props 和 Emits 必须显式声明类型。
- Props 不得被子组件直接修改。
- 计算值使用 `computed`，不得使用 `watch` 模拟可以直接计算的派生状态。
- `watch` 中执行异步请求时必须处理竞态、取消或过期响应。
- 列表必须使用稳定业务标识作为 `key`，不得在可重排列表中使用数组索引。
- 组件名至少由两个单词组成，基础组件使用统一前缀。

```vue
<script setup lang="ts">
interface Props {
  caseId: number
  readonly?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  readonly: false,
})

const emit = defineEmits<{
  completed: [reviewId: number]
  cancelled: []
}>()
</script>
```

### 7.3 状态管理与副作用

- 只在多个页面或远距离组件需要共享状态时使用全局 Store。
- 页面局部状态保留在组件或 composable 中。
- API 请求、计时器、订阅和事件监听必须在组件卸载时清理。
- 不得在计算属性中执行请求、写 Store 或修改其他状态。
- 请求状态至少区分 `idle`、`loading`、`success`、`error`，不得仅用一个布尔值承载多个阶段。
- 用户重复提交、页面切换和响应乱序必须有明确处理策略。

### 7.4 API 客户端

- API 客户端按业务域拆分，例如 `cases`、`reviews`、`customers`、`evidence`，不得持续扩展单一超大文件。
- 传输 DTO 和页面 ViewModel 分离；必要时使用转换函数。
- 统一在基础客户端处理中间件层设置认证、追踪 ID、超时和通用错误解析。
- 不得在每个组件中复制 Axios 错误解析逻辑。
- 后端返回值不得未经验证直接断言为业务类型。
- 金额在协议层使用精确十进制字符串或经过约定的 Decimal 表达，不得依赖 JavaScript 浮点数做权威计算。

```ts
export interface MoneyDto {
  amount: string
  currency: string
}

export interface ApiErrorResponse {
  code: string
  message: string
  traceId: string
  fieldErrors?: Record<string, string>
}
```

### 7.5 模板与样式

- 禁止未经可信消毒器处理使用 `v-html`。
- 表单控件必须有关联标签和清晰错误提示。
- 所有可点击操作必须支持键盘操作；不要用普通 `div` 模拟按钮。
- 颜色不能作为唯一状态信号，应同时使用文本或图标。
- 样式优先使用组件局部作用域和现有设计变量，禁止随意引入全局覆盖。
- CSS 类名表达角色或状态，避免 `red-text`、`left-box` 等视觉实现命名。

## 8. REST API 规范

### 8.1 资源与路径

- 路径使用名词和复数形式：`/api/cases`、`/api/customers/{customerId}`。
- 使用 HTTP 方法表达通用动作：GET 查询、POST 创建、PUT 整体替换、PATCH 部分修改、DELETE 删除。
- 无法自然表示为 CRUD 的业务命令，可使用子资源表达，例如：
  - `POST /api/cases/{caseId}/review-completions`
  - `POST /api/cases/{caseId}/retry-requests`
- 路径中不得出现技术实现名，例如 `/callRepository`、`/executeSql`。
- API 版本策略必须统一，禁止单个接口随意增加版本前缀。

### 8.2 状态码

| 状态码 | 使用场景 |
|---|---|
| `200 OK` | 查询或返回响应体的成功更新 |
| `201 Created` | 成功创建资源，并返回资源位置或内容 |
| `204 No Content` | 成功且无需响应体 |
| `400 Bad Request` | 请求格式或字段校验失败 |
| `401 Unauthorized` | 未认证或认证失效 |
| `403 Forbidden` | 已认证但无权限 |
| `404 Not Found` | 资源不存在或按安全策略不可见 |
| `409 Conflict` | 版本、幂等、资源状态或并发冲突 |
| `422 Unprocessable Entity` | 可选：格式正确但不满足业务规则；项目采用后必须全局一致 |
| `429 Too Many Requests` | 触发限流 |
| `500 Internal Server Error` | 未预期的服务端失败 |
| `503 Service Unavailable` | 关键依赖暂时不可用 |

### 8.3 统一错误响应

所有公开 API 必须使用稳定错误结构：

```json
{
  "code": "CASE_REVISION_CONFLICT",
  "message": "工单状态已发生变化，请刷新后重试",
  "traceId": "c8ab46d7d8de4b82",
  "fieldErrors": {
    "comment": "复核意见不能超过 2000 个字符"
  }
}
```

- `code` 是稳定、可供前端判断的机器错误码。
- `message` 是面向用户或调用方的安全描述。
- `traceId` 用于关联日志和链路。
- 禁止将异常类名、SQL、堆栈或第三方原始敏感响应放入错误对象。

### 8.4 分页、排序与过滤

- 分页参数必须统一，例如 `page`、`size`、`sort`。
- 必须限制最大 `size`，避免全表读取。
- 排序字段必须使用白名单，禁止将用户字符串直接拼接到 SQL。
- 分页响应应包含当前页、页大小、总数以及是否存在下一页。
- 大规模、实时变化数据优先使用游标分页，并定义游标稳定性。

### 8.5 幂等与并发控制

- 支付、审核、重试、状态变更等可重复提交的写操作必须考虑幂等。
- 使用幂等键时，服务端必须保存请求指纹和首次处理结果。
- 资源更新应携带 `expectedRevision`、ETag/`If-Match` 或等效条件。
- 版本不匹配返回 `409 Conflict`，不得静默覆盖他人修改。
- 幂等键相同但请求内容不同必须拒绝。

### 8.6 时间、标识和金额

- 时间使用 RFC 3339/ISO 8601 且包含时区，例如 `2026-09-09T10:30:00Z`。
- ID 类型在同一 API 中必须一致；如果超出 JavaScript 安全整数范围，应使用字符串传输。
- 金额应使用精确十进制字符串并携带币种。
- 枚举值必须文档化；未知枚举值应有兼容策略。

## 9. 数据库与 Flyway 规范

### 9.1 命名与建模

- 表、列、索引和约束统一使用 `snake_case`。
- 表名使用能表达领域含义的名词，不使用 `data`、`info` 等冗余后缀。
- 主键、外键、唯一约束、非空约束必须显式定义。
- 布尔、状态、金额、时间字段的含义必须清晰，禁止使用含义不明的魔法数。
- 数据库负责维护结构完整性；不能只依赖应用层校验。
- 所有高频查询必须评估执行计划和索引。

### 9.2 Flyway 迁移

- 迁移文件采用统一命名，例如 `V20260909_01__add_case_revision.sql`。
- 已在任何共享环境执行的版本迁移禁止修改或删除。
- 修复错误必须创建新的更高版本迁移。
- 每个迁移聚焦一个可描述的结构变更。
- SQL 必须可审查，不使用来源不明的数据库导出脚本。
- 迁移必须同时在空数据库和当前基线数据库验证。
- 生产结构变更必须考虑锁表、执行时间、回滚和多版本应用兼容。

### 9.3 破坏性变更

删除列、收紧约束、更改字段类型等变更必须使用 expand-contract：

1. 新增兼容字段或结构；
2. 部署可同时读写新旧结构的应用；
3. 回填并验证数据；
4. 切换读取路径；
5. 经过观察期后再删除旧结构。

禁止在同一次上线中直接删除仍可能被旧版本应用使用的字段。

### 9.4 数据修复

- 数据修复脚本必须可审查、可重复验证，并明确影响范围。
- 执行前必须提供查询语句确认预计影响行数。
- 大批量更新必须分批执行并监控锁、延迟和复制状态。
- 不可逆修复必须有备份或可恢复方案。
- 严禁将生产客户数据复制到开发和测试环境；必须脱敏或生成合成数据。

## 10. 安全与隐私规范

### 10.1 输入与输出

- 所有外部输入都视为不可信，包括 HTTP、消息队列、文件、数据库历史数据、第三方 API 和 LLM 输出。
- 校验必须在服务端执行，并优先使用允许列表、范围和长度限制。
- 文件上传必须校验大小、类型、扩展名、内容特征和存储位置。
- SQL、命令、模板和查询条件不得通过字符串拼接构造。
- 输出到 HTML、日志、CSV、Prompt 等不同目标时，必须采用目标上下文对应的编码或转义。

### 10.2 认证与授权

- 认证只回答“是谁”，授权必须针对每个受保护操作和资源单独判断。
- 写操作、管理操作、人工复核和敏感数据查询必须服务端鉴权。
- 禁止只依赖前端隐藏按钮实现权限控制。
- 权限判断应默认拒绝；无法确定权限时不得放行。
- 高风险操作应记录操作者、时间、目标、旧值、新值、结果和追踪 ID。
- 审计记录不得允许普通业务用户修改或删除。

### 10.3 凭证与敏感数据

- 密钥、Token、数据库密码不得提交到 Git。
- 凭证必须通过环境变量、密钥管理服务或受控部署配置注入。
- 日志禁止记录密码、JWT、Session ID、API Key、完整证件号、银行卡号、客户报文或完整 Prompt 上下文。
- 必须遵循数据最小化原则，只获取、传输和保留完成业务所需的信息。
- 测试数据和截图必须使用虚构或脱敏信息。

```java
// 禁止
log.info("login request username={}, password={}, token={}", username, password, token);

// 推荐
log.info("authentication completed userId={}, result={}, traceId={}", userId, result, traceId);
```

### 10.4 LLM 与 Agent 安全

- LLM 输出始终视为不可信数据，不得直接作为数据库命令、SQL、脚本或权限决策执行。
- Prompt 中的客户资料、外部网页、附件和检索文档均可能包含提示注入。
- 工具调用必须使用参数化、允许列表和最小权限；模型不得自由构造任意系统命令。
- 高风险决定必须由确定性规则或人工复核确认，不得只依赖模型文本。
- 模型输出引用法规或证据时，必须能追溯到来源片段和版本。
- Prompt、模型、温度、工具版本和证据快照应具备可审计标识。
- 失败、降级、重试和供应商切换不得悄悄改变业务结论语义。
- 发送给外部模型的数据必须满足数据分级、脱敏和供应商合规要求。

## 11. 日志、监控与审计

### 11.1 日志

- 使用结构化、参数化日志，禁止字符串拼接。
- 日志至少包含必要的业务关联标识，如 `caseId`、`executionVersion`、`traceId`，但不得包含敏感原文。
- `ERROR` 表示需要处理的失败；`WARN` 表示可恢复但值得关注；`INFO` 记录关键状态变化；调试细节使用 `DEBUG`。
- 禁止把预期的业务拒绝全部记录为 ERROR。
- 同一异常原则上只记录一次完整堆栈。
- 高频循环中不得逐条输出 INFO 日志。

```java
log.info(
        "case execution transitioned caseId={}, from={}, to={}, executionVersion={}",
        caseId,
        previousStatus,
        nextStatus,
        executionVersion);
```

### 11.2 指标与告警

- 关键工作流应提供吞吐量、成功率、失败率、重试数、处理时长、队列积压和死信数。
- 外部依赖应监控延迟、超时、错误率和熔断状态。
- 告警必须对应可执行行动，避免无法处置的噪声告警。
- 指标标签禁止使用客户 ID、工单 ID 等高基数值。
- SLO、告警阈值和仪表盘含义必须有文档。

### 11.3 审计

- 审计日志与运行日志分离。
- 审计事件必须可关联操作者、操作、资源、时间、结果和业务版本。
- 人工结论、风险等级、证据增删和规则覆盖必须保留前后值。
- 审计内容必须防篡改，并符合监管保存期限。

## 12. 测试规范

### 12.1 测试分层

| 类型 | 目的 | 典型工具 |
|---|---|---|
| 单元测试 | 验证纯业务规则、值对象、状态机和边界条件 | JUnit 5、Vitest |
| 切片测试 | 验证 Web、序列化、校验或 Repository 层 | `@WebMvcTest`、`@DataJpaTest` |
| 集成测试 | 验证数据库、Redis、PGVector、事务和真实适配器行为 | Spring Boot Test、真实依赖容器 |
| 契约测试 | 验证 API、消息和第三方适配协议兼容性 | OpenAPI、JSON Schema |
| E2E 测试 | 验证关键用户旅程和角色权限 | Playwright |

- 不得默认所有测试都使用完整 `@SpringBootTest`。
- 测试必须快速、隔离、可重复，不依赖执行顺序。
- 每个生产缺陷修复必须增加能先失败、修复后通过的回归测试。
- 禁止使用固定 `sleep` 等待异步结果，应轮询可观察状态并设定超时。
- 时间相关测试必须使用固定 `Clock`。
- 测试不得依赖公网、真实 LLM 或真实客户数据，专门的受控评测除外。

### 12.2 命名与结构

测试名必须表达场景和期望结果，可采用 `should...when...` 或中文 `@DisplayName`。

```java
@Test
void shouldRejectCompletionWhenExpectedRevisionIsStale() {
    // Arrange
    CaseRecord existing = caseRecordWithRevision(7);

    // Act
    ThrowingCallable action = () -> service.complete(existing.id(), 6, APPROVED);

    // Assert
    assertThatThrownBy(action)
            .isInstanceOf(CaseRevisionConflictException.class);
}
```

- 测试遵循 Arrange–Act–Assert 或 Given–When–Then。
- 一个测试优先验证一个行为；允许为该行为断言多个相关结果。
- 测试数据构造使用 Builder 或 Fixture，避免复制大量无关字段。
- 禁止仅为了覆盖率编写没有业务断言的测试。
- Mock 只用于隔离真正的外部边界，不要 Mock 被测类内部每一个对象。

### 12.3 金融与工作流强制测试

以下场景必须有自动化测试：

- 金额精度、舍入、币种和阈值边界；
- 状态机允许和禁止的转换；
- 乐观锁、租约丢失、旧 Worker 陈旧写入；
- 重复消息、重复点击和幂等键；
- 事务回滚以及数据库变更与 Outbox 的原子性；
- 权限矩阵和越权访问；
- 空值、极值、超长输入、非法枚举和恶意内容；
- LLM 超时、限流、错误响应、无引用输出和结构化结果解析失败；
- 人工复核、撤回、重试和死信重放。

### 12.4 前端测试

- 组件测试应从用户可见文本、角色和行为出发，不依赖内部实现细节。
- E2E 测试使用稳定定位方式，优先 `getByRole`、`getByLabel`，避免脆弱 CSS 路径。
- 使用 Playwright Web-first Assertions，不手写无条件等待。
- 每个 E2E 测试独立创建和清理数据。
- 必须覆盖加载、空数据、错误、权限不足和重复提交状态。

## 13. 代码评审规范

### 13.1 PR 要求

每个 PR 必须：

- 目标单一、可独立理解和回滚；
- 描述背景、方案、风险和不做什么；
- 列出测试证据；
- 说明数据库迁移、配置、兼容性、安全和数据影响；
- 提供界面变更截图或录屏；
- 不包含无关格式化、重命名或生成文件变化；
- 不提交密钥、真实客户数据或敏感日志。

推荐 PR 模板：

```markdown
## 变更目的

## 核心方案

## 风险与兼容性

## 数据库 / 配置影响

## 安全与隐私影响

## 验证结果

## 回滚方案
```

### 13.2 评审重点

评审者应按以下顺序检查：

1. 需求和业务规则是否正确；
2. 权限、隐私、金额、事务和并发是否安全；
3. 架构边界和数据模型是否合理；
4. 失败、降级和可观测性是否完整；
5. 测试是否能证明关键行为；
6. 命名、可读性和可维护性；
7. 格式和机械问题是否已由工具发现。

评审意见建议标记优先级：

- `blocking`：正确性、安全、架构或严重维护性问题，必须修改；
- `suggestion`：建议修改，但不阻塞合并；
- `nit`：非必要的小建议；
- `question`：请求解释，不默认表示代码错误。

### 13.3 提交信息

提交信息采用 Conventional Commits 风格：

```text
feat(review): add optimistic locking to completion command
fix(outbox): prevent stale worker from publishing completion event
test(auth): cover reviewer role permission matrix
docs(style): add project engineering standards
refactor(api): split case and evidence clients
```

- `feat`：新增用户可见能力；
- `fix`：修复缺陷；
- `refactor`：不改变外部行为的重构；
- `test`、`docs`、`build`、`ci`、`chore` 按含义使用；
- 破坏性变更必须在正文中标记 `BREAKING CHANGE:`。

## 14. CI 质量门禁

### 14.1 必须执行的检查

每个 PR 至少执行：

1. 文件编码、换行和格式检查；
2. Java 编译和格式检查；
3. Java 静态缺陷检查；
4. 前端 ESLint、TypeScript 类型检查和格式检查；
5. 后端单元/切片测试；
6. 前端单元测试；
7. 架构规则测试；
8. 依赖漏洞、密钥和敏感信息扫描；
9. 数据库迁移校验；
10. 关键集成或 E2E 测试。

### 14.2 新代码质量阈值

建议采用 Clean as You Code：

- 新代码不得引入 Blocker/Critical 级缺陷和漏洞；
- 新代码所有安全热点必须经过人工确认；
- 新代码覆盖率不低于 80%；
- 新代码重复率不高于 3%；
- 新代码不得增加 lint 或编译警告；
- 不得跳过或静默忽略失败测试；
- 关键金融规则和权限逻辑不能只依赖总覆盖率，必须有行为测试。

### 14.3 推荐工具组合

后端：

- Spring Java Format 或 Spotless：格式化和 import 管理；
- Checkstyle：源代码结构规则；
- SpotBugs：字节码缺陷检查；
- Error Prone：编译期高置信度错误检查；
- 精选 P3C：Java 企业开发、并发和数据库规则；
- ArchUnit：分层、依赖方向和循环依赖检查；
- JaCoCo：覆盖率；
- SonarQube/SonarCloud：新代码质量门禁。

前端：

- Prettier：格式化；
- ESLint Flat Config；
- `eslint-plugin-vue` 的 Vue 3 recommended 配置；
- `typescript-eslint` 的 `recommended-type-checked` 和 `stylistic-type-checked`；
- `eslint-config-prettier`：关闭格式冲突规则；
- `vue-tsc`：Vue SFC 类型检查；
- Vitest、Vue Test Utils、Playwright：自动化测试。

引入工具时应拆成独立阶段：先只报告，修复基线后再阻断 CI，避免一次性产生无法审查的大规模变化。

### 14.4 本项目执行命令

后端（在 `backend` 目录执行）：

```bash
# 自动格式化全部 Java 源码
./mvnw spring-javaformat:apply

# 校验格式、编译、执行非集成测试并运行 SpotBugs
./mvnw verify -Dgroups='!integration'
```

前端（在 `frontend` 目录执行）：

```bash
# 首次安装或按锁文件恢复依赖
npm ci

# 自动格式化 / 只校验格式
npm run format
npm run format:check

# 类型感知 Lint、单元测试和生产构建
npm run lint
npm test
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
npm run build
```

辅助 Python 脚本（在仓库根目录执行）：

```bash
python -m pip install -r requirements-dev.txt
ruff format benchmark
ruff check benchmark
```

提交前必须至少执行对应子项目的全部质量门禁。CI 使用相同命令，不接受“本地工具版本不同”作为跳过依据。

## 15. 文档规范

- 架构决定使用 ADR 记录背景、选择、备选方案和后果。
- 运行手册必须包含启动、配置、依赖、健康检查、常见故障和恢复方法。
- API 以 OpenAPI 为可执行契约，代码和文档必须同步。
- 状态机、权限矩阵、数据流和可靠性机制应使用图或表格表达。
- 文档中的命令必须可复制运行，并注明运行目录和前置条件。
- 文档不得包含真实凭证、客户数据或只能在作者电脑工作的绝对路径。
- 修改行为、配置或运维方式时，必须同步更新相关文档。

## 16. Definition of Done

一个变更只有同时满足以下条件，才视为完成：

- 需求和验收条件已实现；
- 代码遵守本规范，未引入新的静态检查问题；
- 正常、失败、边界和权限场景已验证；
- 必要的单元、集成、契约或 E2E 测试已添加；
- 数据库迁移已从空库和现有基线验证；
- 日志、指标、审计和告警满足上线排障需要；
- API、配置、架构和运维文档已同步；
- 不包含密钥、真实客户数据、调试产物和无关变更；
- CI 全部通过；
- 风险和回滚方式已在 PR 中说明。

## 17. 参考规范

本规范主要参考：

- [Spring Java Format](https://github.com/spring-io/spring-javaformat)
- [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- [Alibaba Java Coding Guidelines](https://github.com/alibaba/Alibaba-Java-Coding-Guidelines)
- [Spotless Maven Plugin](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md)
- [SpotBugs Maven Plugin](https://spotbugs.github.io/spotbugs-maven-plugin/plugin-info.html)
- [Error Prone Bug Pattern Criteria](https://errorprone.info/docs/criteria)
- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
- [Vue Style Guide](https://vuejs.org/style-guide/)
- [eslint-plugin-vue User Guide](https://eslint.vuejs.org/user-guide/)
- [typescript-eslint Shared Configs](https://typescript-eslint.com/users/configs/)
- [Prettier Rationale](https://prettier.io/docs/rationale.html)
- [PEP 8 — Style Guide for Python Code](https://peps.python.org/pep-0008/)
- [Ruff Formatter](https://docs.astral.sh/ruff/formatter/)
- [Ruff Linter](https://docs.astral.sh/ruff/linter/)
- [Mockito：Java 21+ 显式配置 Instrumentation](https://javadoc.io/static/org.mockito/mockito-core/5.23.0/org.mockito/org/mockito/Mockito.html#0.3)
- [JEP 451：Prepare to Disallow the Dynamic Loading of Agents](https://openjdk.org/jeps/451)
- [Microsoft REST API Guidelines](https://github.com/microsoft/api-guidelines)
- [OWASP Secure Coding Practices Checklist](https://owasp.org/www-project-secure-coding-practices-quick-reference-guide/stable-en/02-checklist/05-checklist)
- [OWASP Secure Code Review Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secure_Code_Review_Cheat_Sheet.html)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Playwright Best Practices](https://playwright.dev/docs/best-practices)
- [Flyway Versioned Migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html)
- [Google Engineering Practices](https://google.github.io/eng-practices/)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [Sonar Quality Gates](https://docs.sonarsource.com/sonarqube-cloud/standards/managing-quality-gates/introduction-to-quality-gates)

---

本规范应与自动化配置共同演进。规范中的强制规则一旦具备可靠的机器检查方式，应逐步转化为格式化、Lint、架构测试或 CI 门禁，而不是长期依赖人工检查。
