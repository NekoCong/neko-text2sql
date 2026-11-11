# ui/screens Specification

## Purpose

Defines how each screen presents the Academic Clarity language while preserving the product's current information architecture, functionality, and role-based access.

## Requirements
### Requirement: Application shell

The authenticated application SHALL present a persistent top bar with the product brand, primary navigation, and the account menu; the chat screen SHALL additionally present the conversation history sidebar. The content area MUST provide a consistent page canvas and gutter for every route.

#### Scenario: Signed-in user lands on chat

- **WHEN** a signed-in user opens the application
- **THEN** the top bar, account menu, conversation sidebar, and chat canvas are visible with consistent spacing

#### Scenario: Switching to a data screen

- **WHEN** the user selects a non-chat navigation item
- **THEN** the top bar remains, the conversation sidebar is hidden, and the page content uses the shared canvas

### Requirement: About entry point removed

The application SHALL NOT expose an "关于" navigation item. The `/about` path MUST redirect to the chat screen so existing bookmarks and links do not render an empty page.

#### Scenario: Navigation without About

- **WHEN** a signed-in user views the primary navigation
- **THEN** the navigation lists chat, data sources, datasource access when authorized, and no About item

#### Scenario: Old About link

- **WHEN** a user opens `/about` directly
- **THEN** the application redirects to the chat screen

### Requirement: Login and registration presentation

The login and registration screen SHALL present the brand, a focused authentication card, and the same control styling as the rest of the product. Form errors and loading states MUST remain visible without shifting the card layout.

#### Scenario: Signing in

- **WHEN** a guest submits valid credentials
- **THEN** the submit control shows a loading state and the screen keeps its layout until navigation completes

#### Scenario: Validation error

- **WHEN** a guest submits an invalid form
- **THEN** the field-level error is readable and the card does not jump or overflow

### Requirement: Chat workspace hierarchy

The chat screen SHALL make the conversation hierarchy legible: the assistant identity and steps, the natural-language answer, the SQL block, the result table or chart, and the composer MUST be visually distinct. Streaming and error states MUST be distinguishable from completed answers.

#### Scenario: Answer with SQL and chart

- **WHEN** an assistant answer contains steps, explanation, SQL, and a chart
- **THEN** each part is visually separated and the SQL remains readable without competing with the chart

#### Scenario: Streaming answer

- **WHEN** an answer is still streaming
- **THEN** the transcript shows a clear in-progress state and the composer reflects that a request is running

### Requirement: SQL and result readability

SQL SHALL be rendered in a dedicated code surface with syntax-preserving whitespace and a copy affordance. Result tables SHALL remain horizontally scrollable without breaking the transcript layout, and charts MUST use the shared data-visualization palette.

#### Scenario: Wide result table

- **WHEN** a result contains more columns than fit the viewport
- **THEN** the table scrolls horizontally inside its own region and the page itself does not scroll sideways

#### Scenario: Copying SQL over plain HTTP

- **WHEN** the user copies SQL from a page served over plain HTTP
- **THEN** the copy action still succeeds through the fallback path

### Requirement: Data management screens

The data sources, model settings, and datasource access screens SHALL use the shared page title, action bar, card, table, form, and dialog treatments. Destructive actions MUST be visually distinct, and permission changes MUST show a clear saved or failed outcome.

#### Scenario: Editing a datasource

- **WHEN** a user opens the datasource dialog to edit an existing entry
- **THEN** the dialog uses the shared form styling and its primary and cancel actions are visually distinct

#### Scenario: Toggling datasource access

- **WHEN** an administrator grants or revokes a role on a datasource
- **THEN** the screen reports whether the change was saved and keeps the matrix aligned

### Requirement: Responsive layout

At widths below 768px, the application SHALL keep the primary navigation reachable, collapse or hide the conversation sidebar, and preserve readable chat and table content without requiring horizontal page scrolling.

#### Scenario: Narrow viewport chat

- **WHEN** the chat screen is opened on a narrow viewport
- **THEN** the transcript, composer, and SQL block remain usable and the page does not scroll horizontally

#### Scenario: Narrow viewport navigation

- **WHEN** the user opens a data screen on a narrow viewport
- **THEN** the primary navigation remains reachable and the content gutter stays consistent

### Requirement: Consistent loading, empty, and error states

Every screen SHALL use the shared presentation for loading, empty, and error states. A failed request MUST show a readable message and MUST NOT leave the screen in an ambiguous blank state.

#### Scenario: Empty conversation history

- **WHEN** a signed-in user has no saved conversations
- **THEN** the sidebar shows the shared empty state instead of an unstyled blank area

#### Scenario: Failed page load

- **WHEN** a data screen fails to load its content
- **THEN** the screen shows a readable error message and retains its page frame

### Requirement: 知识库管理页面

前端 SHALL 为管理员提供知识库管理页面，按元数据、业务知识、智能体知识三类分栏展示；页面 MUST 支持条目列表、新增与编辑、删除、元数据同步、文档上传与网页导入、解析状态查看、失败重试、原文预览和检索调试。非管理员 MUST 看不到该导航入口，也无法通过直接访问路由获得数据。

#### Scenario: 三类分栏

- **WHEN** 管理员打开知识库管理页面
- **THEN** 元数据、业务知识、智能体知识三个分栏可见，且各自展示本类型的条目

#### Scenario: 文档上传

- **WHEN** 管理员在智能体知识分栏上传一个 PDF 或 Word 文档
- **THEN** 列表出现该文档条目，并显示解析状态、解析引擎与文件信息

#### Scenario: 入库状态与重试

- **WHEN** 某条目处于失败状态
- **THEN** 列表显示失败状态、失败阶段与可读原因，并提供重试操作，重试成功后状态更新

#### Scenario: 检索调试

- **WHEN** 管理员在页面输入一个调试问题
- **THEN** 页面展示命中的知识来源、候选表、最终表集合与是否回退

#### Scenario: 非管理员访问

- **WHEN** 学生或辅导员访问知识库管理路由
- **THEN** 页面拒绝展示并提示无权限，不加载任何知识数据

### Requirement: 回答中的原文引用角标

当回答使用了文档型知识时，前端 SHALL 在对应文本处展示可点击的引用角标；角标编号 MUST 与回答末尾的引用列表一致，点击角标 MUST 打开该条引用的原文预览。没有文档引用的回答 MUST NOT 展示空角标或空引用区。

#### Scenario: 引用角标

- **WHEN** 回答基于一个 PDF 中的片段生成
- **THEN** 对应位置出现角标，点击后打开该 PDF 的预览并定位到命中页

#### Scenario: 多条引用

- **WHEN** 回答引用同一文档的多个位置或不同文档
- **THEN** 每个引用有独立角标与编号，且与引用列表一一对应

#### Scenario: 无引用

- **WHEN** 回答只基于元数据或未引用任何文档
- **THEN** 界面不出现引用角标与引用区

### Requirement: 原文预览与命中高亮

原文预览 SHALL 按来源类型选择呈现方式：PDF 以分页渲染并在命中位置叠加高亮框，图片以原图叠加高亮框，纯文本类来源以文本快照加高亮区间呈现。预览 MUST 自动定位到命中位置，并允许用户切换高亮或关闭预览。

#### Scenario: PDF 高亮

- **WHEN** 用户打开一条 PDF 引用
- **THEN** 预览自动跳到命中页，命中区域被高亮框标出

#### Scenario: 图片高亮

- **WHEN** 用户打开一条图片引用
- **THEN** 预览展示原图，并在命中区域叠加高亮框

#### Scenario: 文本高亮

- **WHEN** 用户打开一条 Word 或网页快照引用
- **THEN** 预览展示文本快照，并高亮命中片段

#### Scenario: 缩放后高亮仍对齐

- **WHEN** 用户在 PDF 预览中缩放或翻页后再回到命中页
- **THEN** 高亮框仍与命中文本对齐

### Requirement: 引用不可用的提示

当引用无法打开或原文不可用时，前端 SHALL 给出明确提示，并保留可用的替代操作（如查看引用文本或下载原文）；MUST NOT 展示空白预览或静默失败。

#### Scenario: 原文缺失

- **WHEN** 用户打开一条已失效的引用
- **THEN** 预览区提示原文不可用，并仍展示引用文本

#### Scenario: 预览加载失败

- **WHEN** 预览资源加载失败
- **THEN** 界面提示加载失败并提供重试，不阻塞回答内容的阅读
