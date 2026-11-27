<div align="center">

<img src="docs/images/logo.png" alt="BookMind Logo" width="132" />

# BookMind

**本地私人电子书库与 AI 伴读服务 · 可扩展 Java 插件化开源工程**

[![CI](https://github.com/LosAveRoad/BookMind/actions/workflows/ci.yml/badge.svg)](https://github.com/LosAveRoad/BookMind/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21%20LTS-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Lucene](https://img.shields.io/badge/Apache%20Lucene-9.12-D22128?style=flat-square&logo=apache&logoColor=white)](https://lucene.apache.org/)
[![React](https://img.shields.io/badge/React-18%20%2B%20Vite-61DAFB?style=flat-square&logo=react&logoColor=black)](https://react.dev/)
[![SPI](https://img.shields.io/badge/Plugin%20SPI-v1.0-3B49DF?style=flat-square)](#核心与扩展)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)

[快速开始](#快速开始) · [架构与扩展机制](#核心与扩展) · [系统设计文档](docs/DESIGN.docx) · [AI 协作指南](AGENTS.md) · [测试与 CI](#测试与检查)

</div>

---

## 用户、场景与范围

**一句话场景**：大学生与普通读者在本地整理个人电子书与课程教材时，使用 **BookMind** 扫描本地书库目录，在浏览器中完成书架管理、在线分章阅读与基于 OpenAI 兼容接口的跨书 RAG 伴读问答，并通过独立接入外部 PDF 插件零改动扩展新格式能力。

### 核心亮点

- **开箱即用的私人书架与阅读器**：内置 TXT 编码容错与智能分章解析，扫描本地文件夹即可在浏览器浏览书架、切换目录、在线阅读。
- **跨书精准溯源的 AI 伴读**：核心基于 **Apache Lucene BM25** 构建毫秒级本地全文切片索引，结合 **OpenAI 兼容接口**（支持 OpenAI / DeepSeek / 通义千问 / 智谱 / 本地 Ollama）输出带 `[1][2]` 引用角标的伴读回答，界面严格区分「模型回答」与「原文引用证据」，点击引用直达原文章节。
- **编译期 + 运行期双重物理隔离插件生态**：独立扩展模块 `book-plugin-pdf`（基于 Apache PDFBox）仅依赖零三方依赖的 `book-spi`，由 Maven Enforcer 编译期封禁对核心模块的依赖；运行期将独立插件 JAR 放入 `./plugins/` 重启即生效——**无需修改核心源码、无需重打包宿主主 JAR**，同时解锁书架 PDF 阅读与跨书 RAG 伴读。
- **故障隔离与确定性边界**：坏文件、加密 PDF、不兼容 SPI 版本、插件超时或未配置 LLM API Key 均被隔离为规范错误码，绝不拖垮宿主和其他书籍。

### 本次明确不做（范围边界）

- 不做多用户注册/登录、RBAC 权限、插件市场或云端集群部署；
- 不依赖外部安装型数据库（MySQL/PostgreSQL）或重型向量库（Milvus/Qdrant）及百兆本地 Embedding 模型；
- 不提供伪离线拼接回答模式（未配置 `BOOKMIND_LLM_API_KEY` 时明确返回 `LLM_NOT_CONFIGURED`，同时保持本地 Lucene 跨书检索与在线阅读 100% 可用）；
- 不支持扫描版图片 PDF 的 OCR 识别，也不要求不停机热卸载插件（增删/禁用插件后重启生效）。

### 仓库状态与托管说明

- **当前阶段**：已完成多模块工程脚手架、公开 `book-spi` 契约、`book-tck` 契约测试基类、CI 流水线、系统设计初稿与 4 人小 PR 协作规范（`AGENTS.md`），正按小 PR 节奏持续迭代至 `v0.1.0`。
- **仓库地址**：https://github.com/LosAveRoad/BookMind（公开仓库，GitHub Actions CI 与 PR Review 记录均可直接访问核验）
- **开源借鉴声明**：本项目全部代码由团队原创编写，架构设计借鉴了 [Komga](https://github.com/gotson/komga)（MIT）、[PF4J](https://github.com/pf4j/pf4j)（Apache-2.0）、[Apache Lucene](https://github.com/apache/lucene)（Apache-2.0）与 [LangChain4j](https://github.com/langchain4j/langchain4j)（Apache-2.0），详见 [NOTICE](NOTICE) 与 [设计文档第 2.3 节](docs/DESIGN.md#23-开源架构借鉴与裁剪说明)。

---

## 总体架构一览

<p align="center">
  <img src="docs/images/architecture.png" alt="BookMind Architecture" width="96%" />
</p>

完整模块划分、公开 SPI 方法签名、故障隔离时序图与设计取舍详见系统设计文档：**[docs/DESIGN.docx](docs/DESIGN.docx)**（协作源稿见 [docs/DESIGN.md](docs/DESIGN.md)）。

---

## 快速开始

### 环境要求

| 组件 | 最低版本 | 说明 |
| --- | --- | --- |
| JDK | **21 LTS** | 运行与编译后端及插件必需 |
| Apache Maven | **3.9+** | 多模块构建、Checkstyle、Enforcer 与测试 |
| Node.js | **22+** | 仅开发与构建 `frontend/` 需要；最终发行 JAR 运行无需 Node.js |

> macOS Homebrew 用户若未将 OpenJDK 21 设为默认 `java`，请先执行：
> ```bash
> export JAVA_HOME=/opt/homebrew/opt/openjdk@21
> export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"
> ```

### 构建与验证（在仓库根目录执行）

```bash
# 1. 构建并测试前端（React + Vite + Vitest）
cd frontend && npm install && npm test && npm run build && cd ..

# 2. 编译全部 Java 模块，执行单元测试、Checkstyle、Enforcer 与独立插件打包
mvn -B verify
```

### 本地运行服务（在仓库根目录执行）

**使用发行包**：从成功的 GitHub Actions 构建下载 `bookmind-release` 制品，解开制品后，
将其中的 `BookMind.zip` 完整解压到可写目录。安装 Java 21 后，Windows 双击
`BookMind/bookmind.bat`，Linux 或 macOS 在解压目录运行 `sh BookMind/bin/bookmind.sh`。
看到服务启动成功后，打开 `http://localhost:8080`。发行包自带 TXT、PDF 样书和 PDF 插件。
使用同目录下的 `RELEASE.md` 核对 ZIP 的 SHA-256 和对应提交。

**从源码启动**：

```bash
# 可选：按需设置 OpenAI 兼容伴读接口环境变量（绝不要把真实 Key 提交进 Git，参考 .env.example）
export BOOKMIND_LLM_BASE_URL="https://api.openai.com/v1"
export BOOKMIND_LLM_API_KEY="your-api-key"
export BOOKMIND_LLM_MODEL="gpt-4o-mini"

# 启动后端服务（默认端口 8080，默认扫描 ./samples/books 与 ./plugins）
java -jar book-server/target/book-server-0.1.0-SNAPSHOT.jar
```

打开浏览器访问 `http://localhost:8080`（开发期可在 `frontend/` 执行 `npm run dev` 访问 `http://localhost:5173`，自动代理 `/api` 至 `8080`），访问 `GET http://localhost:8080/api/health` 预期返回：

```json
{"status":"UP","service":"BookMind","spiApiVersion":"1.0","llmConfigured":false,"llmModel":"gpt-4o-mini"}
```

> 正式发行物的独立运行命令与 SHA-256 校验记录见仓库外并列的 `release/RELEASE.md`（将在版本冻结发布时生成）。

---

## 核心与扩展

- **核心与公开契约**：`book-spi`（零第三方依赖）定义 `BookFormatPlugin`、`ParsedBook`、`Chapter`、`BookCover`、`PluginErrorCode` 与 `SpiVersion`；`book-server` 负责书库管理、内置 TXT 分章解析及基于 `URLClassLoader + ServiceLoader` 的外部插件隔离底座；`book-rag` 负责 Lucene BM25 切片检索与 OpenAI 兼容伴读编排。详细契约见 [docs/DESIGN.docx](docs/DESIGN.docx)。
- **独立构建 PDF 扩展插件**（在仓库根目录执行，`-am` 仅联动契约模块 `book-spi`/`book-tck`，完全不编译 `book-server` 与 `book-rag`）：
  ```bash
  mvn -B -pl book-plugin-pdf -am package
  ```
  产物为自包含胖 JAR（打包 Apache PDFBox，自动排除 `provided` 范围的 `book-spi`）：`book-plugin-pdf/target/book-plugin-pdf-0.1.0-SNAPSHOT.jar`。
- **接入与禁用方式**（需要重启服务，**无需重新打包核心 `book-server`**）：
  - **接入**：将上述插件 JAR 复制到宿主外部的 `./plugins/` 目录后重启服务；
  - **禁用**：移除 `./plugins/` 下对应 JAR，或在 `application.yml` / 环境变量中将 `pdf-pdfbox` 加入 `bookmind.disabled-plugins` 后重启。
- **核心源码零修改核验方法**：
  ```bash
  # 1. 核验 book-plugin-pdf 模块被 Enforcer 禁止依赖宿主核心（触发 forbid-host-dependencies 规则）
  mvn -B -pl book-plugin-pdf -am validate

  # 2. 核验接入/移除插件前后宿主核心代码与主 JAR 字节完全不变
  git diff -- book-server book-rag
  shasum -a 256 book-server/target/book-server-0.1.0-SNAPSHOT.jar
  ```

---

## 测试与检查

- **全量构建与测试命令**（在仓库根目录执行）：
  ```bash
  mvn -B verify
  cd frontend && npm test && cd ..
  ```
- **三类核心行为覆盖**：
  - **正常处理**：SPI 版本兼容校验、内置 TXT 分章与 Lucene BM25 跨书检索精确断言、Spring Boot `/api/health` 与书库/伴读主流程、前端视图交互单测；
  - **边界与失败处理**：损坏/加密/空文本 PDF 映射为规范 `PluginErrorCode`、未配置 Key 与模拟上游超时/429/500 返回规范 `RagErrorCode`（测试期通过本地 `MockOpenAiServer` 复现，不依赖外网与真实 Key）；
  - **扩展接入与隔离**：内置与 PDF 插件均继承 `book-tck` 抽象契约测试套件；`book-e2e-tests` 黑盒验证外部插件 JAR 加载、禁用与故障隔离。
- **故意引入小错误演示（Mutation Demo）**：由 Role 2 在 `PR-R2` 中固化具体行号与复现步骤（例如故意偏移 `ChapterChunker` 的 `locator` 编号，运行 `mvn -pl book-rag test` 观察精确溯源断言立即失败，恢复后通过）。
- **基础质量检查与 CI**：
  - **基础检查**：Maven Checkstyle（规则见 [config/checkstyle/checkstyle.xml](config/checkstyle/checkstyle.xml)）+ Maven Enforcer（JDK 21 / Maven 3.9 与插件依赖隔离规则），执行 `mvn -B verify` 自动运行；
  - **CI 配置**：[.github/workflows/ci.yml](.github/workflows/ci.yml)（PR 与 `main` 推送自动触发前后端全量构建、测试与检查）；真实绿色 Run 链接将在首个 PR 合并后补充于此。

---

## 协作与贡献入口

- **最小协作规则**：
  1. 本项目按 4 个独立角色分工（Role 1 前端 / Role 2 RAG / Role 3 图书管理与核心 / Role 4 独立 PDF 插件与 CI/CD），完整 AI 协作规范、初始化角色认领与小 PR 清单见 **[AGENTS.md](AGENTS.md)**；
  2. 所有改动均通过“Issue/PR 说明 → `feat/r<角色>-<缩写>-<简名>` 分支 → 小 PR → 环状交叉 Review → 修改复核 → CI 绿灯后合并至 `main`”；
  3. 严禁直接推送 `main`，严禁“LGTM”敷衍审查，且 Role 4 全程严禁修改 `book-server/` 与 `book-rag/`；每个 PR 须经至少 1 位指定审查人核验后由审查人或维护者批准合并。
- **代表性 PR / Review 记录**（随开发迭代如实补充）：

| PR 编号 | 作者 | 审查人 | 审查关注点与具体意见 | 处理结果与复核 Commit |
| --- | --- | --- | --- | --- |
| *待补充* | | | *示例：Role 4 审 Role 3 的 `book-spi` 契约提出封面 MIME/错误码边界建议* | *待补充* |
| *待补充* | | | *示例：Role 3 审 Role 4 的 PDF 插件指出加密/损坏边界异常封装遗漏* | *待补充* |

---

## 成员贡献与 AI 核验

| 姓名/学号 | 负责角色 | 本人实质改动及 Commit/PR | 审查他人的改动及结果 | 关键决策与 AI 核验 |
| --- | --- | --- | --- | --- |
| 成员 1（待填） | Role 1：前端（`frontend/`） | 待随 `PR-F1..F4` 填写 | 待填写审查的 PR 与具体意见 | 待填写：1 条关键 AI 建议、采纳/修正、实际验证方式与结论 |
| 成员 2（待填） | Role 2：RAG（`book-rag/`） | 待随 `PR-R1..R4` 填写 | 待填写审查的 PR 与具体意见 | 待填写：1 条关键 AI 建议、采纳/修正、实际验证方式与结论 |
| 成员 3（待填） | Role 3：图书管理 & 核心（`book-spi/tck/server`） | 待随 `PR-S1..S4` 填写 | 待填写审查的 PR 与具体意见 | 待填写：1 条关键 AI 建议、采纳/修正、实际验证方式与结论 |
| 成员 4（待填） | Role 4：独立 PDF 插件 & CI/CD（`book-plugin-pdf/e2e/.github`） | 待随 `PR-P1..P4` 填写 | 待填写审查的 PR 与具体意见 | 待填写：1 条关键 AI 建议、采纳/修正、实际验证方式与结论 |

---

## 许可与已知限制

### 许可证选择

本项目采用 **[Apache License 2.0](LICENSE)**，原因如下：
1. 与 Java 后端与插件主流开源生态（Spring Boot、Apache Lucene、Apache PDFBox、PF4J）许可证高度一致；
2. 明确的专利授权与 `NOTICE` 归属条款非常适合包含公开 SPI 与外部插件生态的开源工程。

### 关键第三方依赖与许可义务

| 依赖项目 | 用途 | 许可证 | 需履行的许可义务 |
| --- | --- | --- | --- |
| Spring Boot 3.3.5 | 宿主 Web 服务与配置管理（`book-server`） | Apache-2.0 | 保留版权与 `NOTICE` 声明 |
| Apache Lucene 9.12.0 | 本地中文分词与 BM25 全文检索引擎（`book-rag`） | Apache-2.0 | 保留版权与 `NOTICE` 声明 |
| Apache PDFBox 3.0.3 | 独立 PDF 格式解析插件（`book-plugin-pdf`） | Apache-2.0 | 保留版权与 `NOTICE` 声明 |
| JUnit 5 / AssertJ | 单元测试与契约断言（`test` 范围） | EPL-2.0 / Apache-2.0 | 仅测试期依赖，保留许可声明 |
| React 18 / Vite / Vitest | Web 阅读与伴读前端界面（`frontend`） | MIT | 在发行说明/静态包中保留版权与 MIT 声明 |

### 已知限制

1. `book-plugin-pdf` 仅支持包含可选中文本层的 PDF，不支持图片扫描件 OCR；
2. 增删或禁用 `./plugins/` 目录中的外部插件后需重启服务生效；
3. AI 伴读回答需要用户在运行环境中配置可用的 OpenAI 兼容 API（未配置时仅开放书架阅读与本地 Lucene 全文检索，不生成伪离线回答）。
