# ADR-008：Spring Boot 3.5 → 4.x 迁移的实测代价

- 状态：Accepted（结论：**暂不迁移**；本文记录代价，供以后再决策）
- 日期：2026-09-19

## 背景

依赖漏洞扫描当前有 **16 条阻断**。前几轮已经查清：这些在 3.5.x 线上**无解**——
修复版本要么还没发布（spring-framework 6.2.20 / spring-security 6.5.12 /
spring-data-jpa 3.5.14），要么根本不存在（mysql-connector-j 9.7.1）。

> ⚠️ **口径说明（2026-09-19 补）**：这里说的是**需要处置的** 16 条（12 + 2 + 2）。
> 扫描器实际打印的是 **17 条**——多出来的那条是 `pgvector` 的
> `CVE-2026-18022`，已判定为误报（依据是 NVD 原文里的 `target_sw: postgresql`，
> 见 README「依赖安全」一节），但**没有落成豁免规则，所以它仍然算在阻断里**。
> 两个数都对，指的是不同的东西；README 那节也是这么分开写的。
> 下面"清掉 14 条"说的是那 16 条里的 14 条。

同时实测过另一条线：**Spring Boot 4.1.1 带 spring-framework 7.0.9 与
spring-security 7.1.1，逐条比对 NVD 受影响区间后是 0/12 与 0/2**——
也就是说 Boot 4 能清掉 16 条里的 **14 条**（只剩 mysql-connector-j 那 2 条）。

问题一直是「那迁移本身要多少代价」。这一轮把它量出来了。

## 实测方法

**在临时分支上做，master 未动**（做完即删分支）：

1. `spring-boot-starter-parent` 3.5.16 → 4.1.1；
2. 去掉两个在新线上**会变成错误**的覆盖——`jackson-bom.version` 2.21.5
   （会把 Boot 4 的 Jackson 3 拉回 2.x）与 `tomcat.version` 10.1.60
   （Boot 4 带 11.0.24，覆盖它等于降级）；
3. `./mvnw -B compile`。

## 结果一：看上去很小（**这是假象**）

编译只报了 **6 处**错误、涉及 2 个文件（javac 两趟输出，日志里每处出现两次、共 12 行）：

```
DataSourceConfig.java:4       程序包 org.springframework.boot.autoconfigure.jdbc 不存在
JsonContractConfiguration.java:5 程序包 org.springframework.boot.autoconfigure.jackson 不存在
```

第一处是一行 import：`DataSourceProperties` 搬到了
`org.springframework.boot.jdbc.autoconfigure.DataSourceProperties`。

第二处看起来也只是一行，**但它暴露了真正的问题**：
`Jackson2ObjectMapperBuilderCustomizer` 在 Boot 4 里**不存在了**，替代品是
`org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer`——
参数类型是 **Jackson 3** 的 `tools.jackson.databind.json.JsonMapper.Builder`。

## 结果二：真正的代价是 Jackson 2 → 3

Boot 4.1.1 换掉的坐标（对比 3.5.16 的 BOM 属性）：

| | 3.5.16 | 4.1.1 |
|---|---|---|
| spring-framework | 6.2.19 | **7.0.9** |
| spring-security | 6.5.11 | **7.1.1** |
| hibernate | 6.x | **7.4.5.Final** |
| tomcat | 10.1.55 | **11.0.24** |
| jackson-bom | 2.21.4 | **3.1.5**（坐标换成 `tools.jackson.*`） |
| spring-data-bom | 2025.0.13 | **2026.0.1** |
| flyway | 11.x | **12.4.0** |
| micrometer | 1.15.x | **1.17.1** |

而本工程自己的代码大量直接用 Jackson 2：

```
主代码  37 个文件      ObjectMapper 31 处 / JsonProcessingException 21 处 /
                      JsonNode 9 处 / TypeReference 4 处 / ObjectNode 2 处 …
测试代码 33 个文件
```

> ⚠️ **2026-09-19 复核：主代码 37 对，测试那行原先是 32，实际是 33。**
>
> 数法是"文件内容里出现 `com.fasterxml.jackson` 就算一个"，
> 在**本文档落库的那个提交**（`e0bdca2`）上数是 **37 / 33**，与当前工作区一致——
> 也就是说这不是后来涨上去的，是当时就少写了一个。
> 复算命令（在 `backend/` 下）：
>
> ```bash
> grep -rl "com\.fasterxml\.jackson" src/main/java --include=*.java | wc -l   # 37
> grep -rl "com\.fasterxml\.jackson" src/test/java --include=*.java | wc -l   # 33
> ```
>
> **主代码那行后面括号里的"31 处 / 21 处 / …"没有复核**：那显然是另一种更窄的口径
> （按模块数符号出现次数是 103 / 55 / 88 / 12 / 8，对不上），
> 我没有找到与之对应的算法，**不去改一个自己也无法确证的数字**。
> 但结论不依赖那几个数——它依赖的是"主代码 37 个文件直接用 Jackson 2"这个量级，
> 而这一条已核实。

**Boot 4 不再提供 Jackson 2。** 实测依赖树：Boot 4 下 `com.fasterxml.jackson.core:jackson-databind`
只会**因为 `io.jsonwebtoken:jjwt-jackson` 而被顺带带上 classpath**。

于是会出现一个安静但严重的结果：**HTTP 层（Spring MVC）走 Jackson 3，
而应用自己的 JSON 处理走一个"碰巧在 classpath 上"的 Jackson 2。**
`JsonContractConfiguration` 那条「对外数值一律序列化为十进制字符串」的契约
**会静默失效**——编译照过、接口照返回 200、字段照在，只是值从 `"320000.00"`
变回 `320000.00`。

（本轮已经给这条契约补了测试，见下面的「附带产出」。）

## 结论

**门禁想变绿只有这一条路，但它不是补丁级升级，是一次重构项目**：
约 **69 个文件**的 Jackson 2→3 移植，叠加 Hibernate 6→7、Tomcat 10→11、
Spring Security 6→7、Spring Data 2025→2026、Flyway 11→12、Netty 4.1→4.2。

在没有人能投入这段时间之前，**保持现状是正确的**：门禁红着，但每一条阻断
都能说清"为什么修不了"（见 README「依赖安全」），而不是靠豁免把红藏起来。

## 附带产出

查这件事时发现 `JsonContractConfiguration` **此前没有任何测试**——
它定义了整个对外接口的数值表示，却没人盯着。如果直接做 Boot 4 迁移，
上面那条静默失效**不会被任何测试抓住**。

已补 `JsonContractConfigurationTest`（`@JsonTest`，三项：顶层标量、尾随 0 不被归一、
嵌套对象里也成立），并做过反向验证：去掉 `@Import` 后三项全红，
证明它盖住的是**接线层**而不只是机制。

## 发布约束

- **在决定迁移之前，不要动 master 上的 `spring-boot-starter-parent`。**
  单独升级 parent 而不做 Jackson 移植，会得到"编译通过但对外契约变了"的状态。
- 若将来决定迁移：**先补 HTTP 层的契约测试**（本轮的 `@JsonTest` 是第一步），
  再动 parent。顺序反了就没有安全网。
- 本文的测量可复现：按「实测方法」那三步即可，代价是十几分钟。
