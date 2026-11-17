# QL_COLLAB — HMCL for QingLan MTR 2.0 协作 / 交接文档

> 给接手者（人类或 AI）：这是本项目的唯一权威状态文档。开工前先读完本文件，收工后更新「工作日志」并提交。
> 仓库：`<repo>`，分支 `ql/main`（fork 自 HMCL-dev/HMCL v3.16.4，**上游已锁死，不跟更新**）。
> 文中 `<repo>` / `<tmp>` / `<gradle-home>` / `%USERPROFILE%` 是作者本机路径的占位符，按自己的环境替换。

---

## 1. 项目目标

为「轻蓝 MTR 2.0」整合包社区定制的启动器（品牌中文名为**轻蓝**，繁中作**輕藍**，罗马字 Qinglan；历史工作日志里的旧写法不改）：

1. **增量同步**（已完成；2026-11-17 架构调整）：manifest 驱动（替代 1.7GB 整包重下），**单一 mods 频道**（不再区分官方包/优化包）+ 可选 mod 组（voxy/dh/axiom）。**资源包与光影交给游戏内的 RPU 模组**；同步页只对带 RPU 模组的整合包实例开放（`QLPackGuard`），其他实例显示「此实例不可同步」。
2. **种子导入**（阶段2，已完成）：直接安装 FULL/LITE `.mrpack`（`QLSeedImporter` + 同步页导入区块 + 「寻找实例」引导页）。
3. **论坛页**（阶段3，已完成）：qlbbs.ota8a.cn（Halo content API + RSS 兜底）。**用户明确要求：HMCL 原生 UI 组件渲染，禁止 WebView 套壳**。
4. **服务器状态页**（阶段4，已完成）：SLP `play.simpfun.cn:32650` / MTR Web `:14614` / reporter `:19451`；进页自动探测 + 30s 轮询；manifest `servers[]` 字段仍预留。
5. **发布工具**（已完成）：`QLManifestTool` 生成 manifest。
6. **MTR 轨交页**（阶段3 新结构，已完成）：`play.simpfun.cn:14614` 地图 API → 车站时刻表（按站台/按线路）+ 线路时刻表 Beta + 本地路径规划（`QLMtrRouter`，最多 5 条方案）。

版本 `3.16.4-ql2`，jar 名 `HMCL-3.16.4-ql2.jar`。GPLv3 合规：改名 + 保留 header。

## 2. 环境速查

| 事项 | 内容 |
|---|---|
| JDK | `C:\Program Files\Java\jdk-21`，每条 shell 命令前 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'` |
| 构建 | 无全局 gradle，一律 `.\gradlew`（在仓库根） |
| 控制台 | GBK，中文乱码是**显示**问题；写中文文件用 UTF-8 无 BOM（PowerShell .NET API 或 write 工具） |
| i18n 门禁 | `:HMCL:checkTranslations`：zh_CN 键必须同时存在于 en 与传统 zh；en 文件**强制半角括号 `()`**（禁 `（）`） |
| 网络 | 本机对 maven.org/authlib 有 TLS PKIX 问题，依赖走 huaweicloud 镜像（wrapper 已配，commit d943c7484）；**依赖解析（含 checkstyle jar）再加** `$env:MAVEN_CENTRAL_REPO='https://mirrors.huaweicloud.com/repository/maven/'`；访问论坛/服务器用带重试的 HTTP |
| checkstyle | 本机 `.ghm` 已缓存 checkstyle 10.24.0；但它绑在 huaweicloud repo id 下，**跑 checkstyle 必须带 `MAVEN_CENTRAL_REPO`**，否则 `--offline` 报 “No cached version” |
| git 推送 | 本机 schannel 后端报 `SEC_E_NO_CREDENTIALS`，仓库已设 `git config http.sslBackend openssl`；凭据在 Windows 凭据管理器里（Hansyang1129@github.com） |
| GUI 冒烟 | JavaFX GUI 依赖（~9MB）在 `%TEMP%\opencode\openjfx`；缺失时 GUI 冒烟会重新联网下载 |
| **C 盘空间** | **C 盘空间紧张**。GB 级测试产物（mrpack 拷贝、e2e run 目录）一律放 D 盘（如 `<tmp>\`），禁止塞 C 盘 Temp；收工必须清理自己产生的临时文件 |
| **shell 陷阱** | 用 `;` 分隔「编译;运行」时编译失败会静默跑旧 class——一律 `&&` 并检查 `$LASTEXITCODE` |

## 3. 命令手册

```powershell
# 全量测试（改完代码必跑）
.\gradlew -q :HMCLCore:test :HMCL:checkTranslations --console=plain

# 产出发布 jar
.\gradlew -q :HMCL:shadowJar --console=plain   # -> HMCL/build/libs/HMCL-3.16.4-ql2.jar

# GUI 冒烟（启动后肉眼验证，存活 >10s 即算过）
java -Dhmcl.dependencies.dir="$env:TEMP\opencode\openjfx" -jar HMCL\build\libs\HMCL-3.16.4-ql2.jar

# 生成 manifest（参数空格分隔，路径不能含空格；mrpack 先拷到无空格路径）
.\gradlew -q :HMCLCore:runQlManifestTool -PqlToolArgs="from-mrpack C:/temp/pack.mrpack --out C:/temp/manifest.json"
.\gradlew -q :HMCLCore:runQlManifestTool -PqlToolArgs="validate C:/temp/manifest.json"
.\gradlew -q :HMCLCore:runQlManifestTool -PqlToolArgs="from-instance C:/temp/run --out C:/temp/manifest.json"

# E2E 联测（五场景，见 §6）
.\gradlew -q :HMCLCore:runQlSyncE2E -PsyncE2EArgs="C:/temp/pack.mrpack C:/temp/manifest.json C:/temp/e2e"
```

## 4. 文件地图

**核心引擎** `HMCLCore/src/main/java/org/jackhuang/hmcl/ql/`：
- `QLManifest.java` — manifest 模型 + `fromJson` 严格校验（schema=1；channels 必含 **std + common**，旧 pfm 忽略；路径禁 `:`、`..`、`/` 开头；sha1/urls 必填；频道内路径去重）。嵌套 `pack{name,version,updatedAt}`。
- `QLDirectoryLayout.java` — 目录语义：活跃=`mods/`，`.qinglan-channel` 标记；单频道后只在 std 上 recover/stamp。旧的 `mods-pfm/` 不认领、不删除。
- `QLPackGuard.java` — 同步门禁：扫 `mods/*.jar` 找 RPU 模组（大小写不敏感、排除 `*.jar.disabled`），决定实例能否在同步页更新。
- `QLSyncState.java` — 实例状态 `ql-manifest-state.json`（`STATE_FILE` 常量）：`packVersion/manifestUrl/activeChannel/enabledOptional/tracked`。构造私有，用 `empty()`；`load(gameDir)` 缺文件返回空态。
- `QLSyncManager.java` — `sync(gameDir, manifest, targetChannel, optionalExtras, listener) -> SyncOutcome`。见 §7 行为备忘。
- `QLConstants.java` — `DEFAULT_MANIFEST_URL`（当前 provisional：`https://resource.ota8a.cn/qinglan/manifest.json`，正式服务器定稿后替换）。
- `QLManifestFetcher.java` — manifest 拉取（`NetworkUtils.doGet`，`Task.supplyAsync(Schedulers.io(), ...)`）。
- `QLManifestTool.java` — 发布器（Gson；from-mrpack/from-instance/validate 三模式）。
- `QLDblPack.java` / `QLSeedImporter.java` — dblpack 清单生成 + mrpack 解包后「QL 化」。
- `QLServerStatus.java` — 手写 SLP（varint 帧）+ HTTP 探测，`probeAll` 单端点故障隔离。
- `QLForum.java` — Halo 匿名 content API（列表/详情），固定 HTTP + 3 次重试，RSS 兜底。
- `QLMtr.java` — MTR 地图 API 客户端（`stations-and-routes` / `departures` / `arrivals`），id 归一 16 位大写 hex，arrivals 4s 缓存；`fetch*(String base)` 包私有重载是单测注入点。
- `QLMtrTimetable.java` — 把 departures 的相对偏移展开成具体班次（服务器 `currentTime` 为基准，60s 宽限窗）。注意：record 不实现 Comparable，排序必须显式给 Comparator。
- `QLMtrRouter.java` — 本地 Dijkstra 路径规划：步行 120s、换乘惩罚 180s；`MtrPath.rideEdges` 暴露单跳边键集合，备选方案靠 ban 这些边生成。
- `QLMtr.stationGroupIds(sar, id)` — 沿 `connections` 求同站月台闭包（无链时按中文名归并）。
- `QLPinyin.java` + `HMCLCore/src/main/resources/assets/ql/pinyin.txt` — 搜索匹配：GB2312 一级区取**首字母**（26 组边界表，零依赖），Unihan `kMandarin` 取**全拼**（6711 字 / 61 KB，去声调，`ü` 同时留 `lu` 与 `lv` 两种写法）。`searchKey()` 一次把 中文/罗马字/首字母/带空格全拼/连写全拼 拼成一个 blob，过滤只做 `contains`；**必须**在加载网络时预计算，别在每个按键事件里重算。表来源 Unicode Unihan（https://www.unicode.org/license.html），许可头已写进资源文件。

**测试** `HMCLCore/src/test/java/org/jackhuang/hmcl/ql/`：
- `QLSyncManagerTest.java` — 3 个单测（@TempDir + com.sun.net.httpserver；**必须** `CacheRepository.getInstance().changeDirectory(dir)`）。
- `QLSyncE2E.java` — 实包端到端驱动（五场景，详见 §6）。

**UI** `HMCL/src/main/java/org/jackhuang/hmcl/ui/`：
- `ql/QLSyncPage.java` — 同步页（查更新/频道切换/可选勾选/应用 + 种子包导入区块）。
- `ql/QLFindInstancePage.java` — 无实例时的引导页（群文件导入 / 选择目录，`locateGameRoot` 纠偏到游戏根目录，注册后监听版本加载自动认领实例）。
- `ql/QLStatusPage.java` — 服务器状态页（5 段行结构 + 进页探测 + 30s 轮询）。
- `ql/QLForumPage.java` — 论坛页（列表 ↔ 原生文章渲染，TextFlow 内联粗体/斜体/删除线/行内码/链接/图片）。
- `ql/QLMtrPage.java` — 轨交页：**三 tab 工作台**（车站时刻表 / 线路时刻表 Beta / 查询路径），不再是首页入口列表。选择器统一为内部类 `NameComboBox`（可编辑 `ComboBox` + `FilteredList`，编辑器当搜索框，Enter 选第一条匹配，Esc 恢复），车站 tab 内保留 按站台/按线路 切换；拉取与 Dijkstra 均在 io 线程。
- `Controllers.java` — `qinglanSyncPage`（Lazy 字段 + getter + shutdown 置空）。
- `main/RootPage.java` — 侧边栏 `qinglanSyncItem`（SVG.REFRESH）。

**i18n** `HMCL/src/main/resources/assets/lang/`：`I18N.properties`(en) / `I18N_zh_CN.properties` / `I18N_zh.properties`，键前缀 `qinglan.*`。

## 5. 提交历史

> **2026-11-14 起仓库历史已压缩为单提交快照**（远端只有本项目维护者的提交）；下表旧哈希仅存在于本地分支 `archive/full-history`，如需查证请用 `git show <hash>`。

| commit | 内容 |
|---|---|
| `d943c7484` | Gradle 依赖走 huaweicloud 镜像 |
| `0bd5c2e52` | 品牌改名 + 版本方案 3.16.4-ql2 |
| `0768683d4` | 同步引擎核心 + 单测 |
| `f7b0d1c42` | QLSyncPage UI + 导航 + i18n |
| `b8de5bf25` | QLManifestTool 发布工具 + JavaExec 任务 |
| `57cd26980` | E2E 驱动 + 重复键/RENAME 语义修复（**E2E 五场景全绿**） |

Git 身份（repo 本地）：`tux106` / `tux106@qq.com`。commit 规约：`feat(ql):/fix(ql):/docs(ql):` + 简洁描述。

## 6. E2E 联测（已全绿，复跑即验）

五场景（驱动自动断言计数）：

| 场景 | downloaded | unchanged | deleted | renamed | switched |
|---|---|---|---|---|---|
| A 新装 std + dh | 406 | 0 | 0 | 0 | false |
| B 幂等重跑 | 0 | 406 | 0 | 0 | false |
| C 关 dh | 0 | 405 | 0 | 1 | false |
| D 切 pfm | 0 | 406 | 0 | 0 | true |
| E 切回 std + dh | 0 | 405 | 0 | 1 | true |

末尾 `Final state verified` 校验：state 文件、`tracked` std=136/common=270/pfm=0、`enabledOptional={dh}`、DH jar sha1 对账、`mods/.qinglan-channel` 标记。最新全绿日志见 run `%TEMP%\opencode\e2e\run-1792419274512`。

## 7. 引擎行为备忘（改动前必读）

1. `collectDesiredFiles` 规划唯一 mods 频道（std）+ common；**RPU 目录（resourcepacks/shaderpacks）的清单条目一律跳过**；禁用可选 mod 保留为 `*.jar.disabled`。
2. **optional 分组只对 std 频道的 mods 生效**；config（如 `DistantHorizons.toml`）始终同步——发布器已按此实现，别改回去。
3. purge 只删 `tracked` 里记录的文件（键 `channel + "::" + logicalPath`）；用户手放文件永不触碰；`deleteTrackedFile` 不抛异常。
4. 下载任务 map 的键**必须唯一**——曾用 `label()`（= 频道/文件名）作键导致 24 个同名文件任务被静默覆盖丢失，已改用 `PlannedFile`（record 值语义）作键。任何"文件名当 key"的写法都要警惕。
5. reconcile：OK / RENAME（`.jar`↔`.jar.disabled` 兄弟互换，免下载）/ DOWNLOAD；**RENAME 不计入 unchanged**。
6. 单测/驱动跑 sync 前必须 `CacheRepository.getInstance().changeDirectory(<dir>)`（ETag index 仅在此初始化）；生产侧由 `Launcher.initializeSettingsRuntime()`（`HMCL/Launcher.java:165` 附近）覆盖。
7. URL 拼接必须按路径段 `URLEncoder.encode`（中文/空格/方括号），空格用 `%20` 不是 `+`；manifest 路径禁 `:`、`..`。
8. `versionId` 自动去 `-full` 后缀。pfm 频道已退役：清单里若还有就忽略；state 里若还跟踪，sync 会**移交**（untrack 不删文件）。
9. 发布器排除项：`PCL/`、`servers.dat`、`modrinth.index.json`、`mods/` 下 `fabric-loader-*`/`fabric-api*`。
10. 本地测试 HTTP 服务器：线程池须 daemon + `finally` 关闭，否则 JVM 挂死；payload 用流式 `transferTo`（79MB 文件 `readAllBytes`×16 并发会 OOM 页面文件）。
11. 数据实测：FULL mrpack（412 entries，index 空）→ std=136/common=270；LITE mrpack（274 entries，index 139 条 CDN 字典）→ std=136（123 条 CDN-first）/common=270，overrides 与 index 零重叠。

## 8. TODO（转交清单）

**P0 — 转交即做**
- [x] 通读本文件与 AGENTS.md；跑一遍 `:HMCLCore:test` 与 `:HMCL:checkTranslations` 确认环境无恙。（2026-10-20 已跑：ql 17 测全绿；`WinRegTest` 在本机沙箱下 `registryCreateKey` 被拒（HKCU 写入被沙箱拦截），属环境故障非代码问题。）

**P1 — 功能主线**
- [x] 阶段2 种子导入向导：`QLSeedImporter` + `QLSyncPage` 导入区块 + `QLFindInstancePage`。
- [ ] 正式资源服务器定稿后：上传 `manifest.json` + `files/**`（发布器输出即目录结构），替换 `QLConstants.DEFAULT_MANIFEST_URL`。
- [ ] 在真实 CDN 上跑一轮 LITE manifest 联测（可选，非阻塞）。

**P2 — 页面**
- [x] 阶段3 论坛页：`QLForum` + `QLForumPage`（content API 故障自动切 RSS；文章原生 TextFlow 渲染）。
- [x] 阶段4 服务器状态页：`QLServerStatus` + `QLStatusPage`。预留卫星扩展位；`manifest.servers[]` 字段仍未消费。
- [x] 阶段3 MTR 轨交页：`QLMtr` / `QLMtrRouter` / `QLMtrTimetable` + `QLMtrPage`。
- [x] ~~pfm 频道真实优化包产出~~（2026-11-17 取消双频道，需求作废）。
- [ ] MTR 页遗留（2026-10-21 复核，均不阻断）：~~①「按站台」实时到站只在重查时刷新~~ *已解决：P1-2 起每 20s 自动轮询*；②「按线路」按 `primaryName` 全等聚合车站，同名异地站会误并，应改走 `station.connections`；③ departureAt 以服务器 `currentTime` 为基准，展示用本地钟，服务器钟差会整体偏移；~~④ `QLMtr.arrivalsCache` 是 check-then-fetch 竞态且永不淘汰~~ *已解决：P1-2 改 `stationIds#maxCount` 键 + 有界淘汰 + 在飞去重*；~~⑤ 刷新按钮只重建首页列表，不重渲当前 detail~~ *已解决：现行 refresh→populate 重渲三个 tab*；⑥ `dwellTime` 解析后未参与计时；~~站台/线路下拉无颜色点~~ *已解决：弹层与普通块均带 12px 色块*。

**P3 — 发版**
- [ ] 发版流程固化：shadowJar → 全测试 → GUI 冒烟 → 分发。

## 9. 协作约定（给接手的 AI）

- 开工先读「工作日志」最后一条；收工必更「工作日志」+ commit。
- 改 `ql` 包代码后必跑 `:HMCLCore:test` + `:HMCL:checkTranslations`；动 UI 还要 GUI 冒烟。
- 遵守仓库 `AGENTS.md`：`@NotNullByDefault` 类注解、显式 `@Nullable`、`///` 文档、GPL header（抄 `config/checkstyle/license-header.txt`）、`@Unmodifiable(View)`。
- 不要重写已验证模块；改动最小化；新增键同时加 en/zh_CN/zh。
- 不提交 secrets，不 force push，不无请示 commit 用户私人文件。

## 10. 工作日志（append-only）

- **2026-10-19（opencode/GLM）**：E2E 五场景全绿（A 新装 406 下载 / B 幂等 / C 关 dh rename / D 切 pfm / E 切回 + `Final state verified`）。修复：`downloadByName` label 键冲突丢失 24 个任务（改 `PlannedFile` 键）；`RENAME` 不计入 `unchanged`；清理调试打印（FAILED 异常保留为 JUL 日志）。commit `57cd26980`。下一步：P1 阶段2 种子导入。
- **2026-10-19（续）**：C 盘临时清理完毕：已删 mrpack 拷贝 ×2（~3.4GB）+ e2e run 目录 ×2（~3.4GB）+ 空壳测试目录；**依赖缓存（`.gradle\caches` ~1.1GB、openjfx GUI 冒烟依赖）按用户要求全部保留**。用户约定：依赖不清，copy/测试产物必清且不得塞 C 盘 Temp。
- **2026-10-19（续2）**：**阶段2 核心完成**（commit `4019965de`）：`QLSeedImporter.seedize(gameDir, manifest, manifestUrl, channel, optionalExtras, listener)`（原生 `ModpackInstallTask` 解包后 QL 化：布局盖章 + sha1 对账 tracked + 初始化 `QLSyncState` + 生成 dblpack）；`QLDblPack`（schema 1，mods 条目带内嵌元数据 `fabric.mod.json`/`mcmod.info` 双格式解析，资源包/光影按文件名 best-effort 版本正则）在 **seedize 与每次成功 sync 后自动刷新**（挂 `QLSyncManager` bookkeeping）。E2E 新增 F/G 种子场景：FULL seeded=405/missing=1 → sync 0 下载 rename 闭环；LITE seeded=267/missing=139/mismatch=0 → sync 精准补 139（mock CDN），dblpack=136 mods/40 资源包/3 光影（135 mods 有内嵌名称版本）。单测 +3（`QLSeedImporterTest`），全程 `:HMCLCore:test` 绿。测试产物一律 `<tmp>\`（mrpack 拷贝 + manifest 已在此）。**剩余**：阶段2 的 UI 接线（QLSyncPage 导入按钮 + 进度 + i18n），见 P1/P2 清单。

- **2026-10-20（续3）**：**阶段2 UI 完成**（commit `fa1adbbb4`）：`QLSyncPage` 新增种子包导入块——`chooseSeedFile`（FileChooser `*.mrpack` → `CompressingUtils.findSuitableEncoding` → zip → `ModrinthModpackProvider.readManifest` 解析出名字/版本预填）+ `startSeedImport`（实例名重名自动加 ` (n)` → 原生 `ModpackHelper.getInstallTask` 链 `.thenComposeAsync(清单拉取)` → `.thenComposeAsync(io, seedize + setSelectedInstance)` → `Controllers.taskDialog`；全程 progress 监听进度条）。坑：`Modpack` API 是 `getVersion()/getGameVersion()`（没有 `getVersionId()`）；`CheckTranslations` 连简中/繁中也强制半角括号 `( )`（12 个全角问题已修）。**语言彩蛋 ×2**（commit `33c238446`）：广东话 `I18N_yue_Hant.properties`（正字繁体口语）+ 潮汕话 `I18N_nan.properties`（闽南语正字・简体・口语），机制上 `yue`/`nan` 本就是 `sublanguages.csv` 的 zh 子语言 → fallback 链天然回落繁中，`CreateLanguageList`/`CheckTranslations` 零改动；`languages.json` 实测 `[...,lzh,nan,yue-Hant]`；`LocaleNamesOverride{,_zh,_zh_Hant}` 补显示名（粤语/廣東話/潮汕话 等）。验证 `:HMCL:checkTranslations` + `:HMCL:compileJava` 双绿。**下一步**：GUI 冒烟（seed UI 实机渲染）；随后按清单进阶段4 服务器状态页（MTR 论坛页暂缓，待用户信号）。

- **2026-10-20（续4）**：**阶段4 服务器状态页落地**（commit `50a1a5477`）：`QLServerStatus`（HMCLCore，手写 Minecraft Java SLP 协议——varint 帧 + protocol 47 握手 + JSON 解析、HTTP GET 探测、`probeAll` 序贯且单端点失败隔离成 offline）+ `QLStatusPage`（左侧栏青蓝同步下方 SVG.PUBLIC 图标；行 = 名称/在线延迟/人数/版本·HTTP 码/错误细节；时间戳）。默认探测表：`play.simpfun.cn:32650`（SLP）、`:14614`（MTR web）、`:19451`（reporter），三端已实机验证全通（SLP 0/16 players 107ms@1.20.1、HTTP 200 双端）。测试 4 个（varint 回路、进程内假 SLP 服务器全回路、离线端点探测、探测表形状）全绿。协议坑记录：packetLength=packetId字节+payload（`handshake.length` 不加 1）；测 SLP 服务器要保持连接半开到 core 读完。语言彩蛋页面 keys 回落繁中（不加）。**manifest.servers[] 二期**：把 `DEFAULT_PROBES` 变成清单驱动（P2）。另外桌面 GUI 已重启为新包（含 seed 导入 + 状态页 + 语言彩蛋）。

- **2026-10-20（续5）**：**状态页 MOTD 行结构落地 + 陶瓦 UI 移除 + 轮换壁纸**。commit 531c1bdb1：状态页行改为用户钦定的 5 段结构（中文名加粗 → MOTD 全文 → ping → 在线 x/16 → sample 名单；HTTP 端点显 detail，离线显失败原因淡字），SLPResult 捎带 motd/sample、剥离 version，lattenText/eadSample 服务 JSON 序列；探测表命名正为**轻蓝**（服务器中文名全站以此为准——注意 i18n 品牌串仍写作"青蓝"，待用户拍板是否统一改）；陶瓦联机 UI 层删除（TerracottaPage/TerracottaControllerPage、RootPage/Controllers 注册、79 键 ×3 语言），core 包、gradle upgradeTerracottaConfig、ssets/terracotta.json、UserState.terracottaAgreementVersion 保留（闭环无外部引用）；孤儿续行引发的 checkTranslations 幽灵键（"陶瓦联机使用"）清掉；zh_CN/zh 状态键补齐 ping/players/sample；顺手修 seedize 链 seedInfoLabel.setText 离 FX 线程。commit 1efc47873：**启动器轮换壁纸**——6 张服务器截图（F:\mc\.minecraft\versions\Qinglan-2nd\screenshots）JPEG q90 ≤2560px 共 3.1MB 入 ssets/img/wallpapers/ql/（ql-01..06 + list.txt 清单，**后期加图 = 丢文件 + 清单加一行**），Themes DEFAULT 分支先本地 background/ 目录再 ql 随机再内置兜底，jar 10→13MB。GUI（PID 49604/44528）新包热启——桌面验证清单：seed 导入区块、状态页 5 段行、语言下拉粤语/潮汕话、陶瓦入口消失、每次启动随机壁纸。

- **2026-10-20（续6）**：**品牌名全站正名轻蓝**——用户拍板“改”。zh_CN/zh/yue_Hant/nan 四语言各 3 处 + QLManifestTool/QLManifest 服务器显示名（青蓝服务器→轻蓝服务器）共 14 处替换（简体 青→轻、繁体 青藍→輕藍，英文romanization Qinglan 同音不改）。编译+翻译检查+ql测试绿，commit 199c07736，新包重启 GUI（PID 52064/40296）。

- **2026-10-20（续7）**：**通用设置页瘦身**——用户指示只留语言和调试。SettingsPage 删「启动器更新」整段（更新渠道/预览版/不自动弹更新窗），fork 自更新到上游的通道一并掐断；「杂项」段删愚人节开关（lzh 彩蛋逻辑仍在 Controllers），调试行（打开日志文件夹/导出日志）保留并挂在杂项标题下。清理 updateListener 字段、onUpdate()、7 个升级相关 import。commit 55c9f70bb，新包重启 GUI（PID 23736/17708）。

- **2026-10-20（续8）**：**状态页自动+手动双刷 & 首页去实例列表入口**。commit 8a2cb304：QLStatusPage 实现 PageAware，进页即探一次 + 30s Timeline 自动轮询（仅页面可见时跑；探测中/busy 跳过；手动刷新与每次探测完结都重置倒计时）。commit 5b8e3dba5：首页启动按钮旁的箭头 menuButton（GameListPopupMenu 弹层）删除，副键触发的次级入口也一并移除；启动主按钮与滚轮切实例保留。新包重启 GUI（PID 47900/41464）。

## 续9（2026-10-20）：实例列表引导 + 下载页瘦身

用户指令：实例管理和下载从侧栏去掉；没实例时给引导（群文件整合包导入 / 选择QL2目录），只要管理 QL2 这一个实例；下载页只留模组和光影包，且只展示适配当前实例的。

- RootPage 侧栏删 实例管理(gameListItem)+下载(downloadItem)，保留：账户 / 实例列表(version.manage) / 轻蓝同步 / 服务器状态 / 设置 / 联系。
- 新 org.jackhuang.hmcl.ql.QLInstances：按 run 目录含 dblpack.json 或 ql-manifest-state.json 找到 QL2 实例 id（HMCL 模块，不能放 HMCLCore）。
- GameListPage：loadVersions 只保留 QL2 一个条目并设为选中；空实例时中央显示引导（从群文件下载整合包并导入 → 轻蓝同步页；选择 Qinglan-2nd 实例所在目录 → 注册新用户目录并切换）。左下角只剩 全局游戏设置。工具栏新增 下载模组/光影包 按钮进下载页。
- DownloadPage：只留 模组/光影包 两个标签；每个列表 loadVersionFor 直通 QLInstances.findQinglanInstance(repository)（fallback getSelectedInstance），loadInstance(repository, pinnedId) → search() 自动用实例游戏版本过滤（"仅展示适配的"）。新游戏/整合包/资源包/世界 四个侧栏类目删除。
- 连根拔入口：WorldListPage/ResourcePackListPage 实例页面的 下载 工具栏按钮删除；ModpackSelectionPage 删 从仓库下载 按钮（群文件整合包导入不走在线仓库）；Versions.addNewGame 删除；GameListPage 左下 install.new_game / install.modpack 删除。
- i18n：qinglan.download、qinglan.guide.title/hint/seed/chooseDir（en/zh_CN/zh）。
- 验证：:HMCL:compileJava + checkTranslations + :HMCLCore:ql tests 全绿；commit 41b38a493。
- 记忆点：版本列表页皮肤滤显示用 Bindings.isEmpty(skinnable.getItems()) 绑定（ObjectProperty 无 .empty()）；ui.versions 另有一个 DownloadPage（实例内对话框版，4 参构造，含 MOD/RESOURCE_PACK/SHADER_PACK 回调 switch），与 ui.download.DownloadPage 不同名同姓勿混淆。
- 待办：MainPage 空实例时点启动仍走 launchNoGame 原版下载流，可考虑改引导；侧栏实例列表入口 = version.manage 项仍然先到列表页。

### 续9b：更正侧栏——删"实例列表"留"实例管理"

上一条把意思听反了：应该删掉实例列表(gameItem → GameListPage 直达)，保留实例管理(gameListItem 卡片，无实例时点击仍进引导所在的实例列表页)。已恢复 gameListItem（含滚轮切换、悬停预览、右键弹窗），删除 gameItem；重新引入 GameAdvancedListItem import。commit d2129819b。

### 续9c：无实例引导改为独立"寻找实例"页

点侧栏实例管理（无实例时）不再进实例列表页，改进新页 org.jackhuang.hmcl.ui.ql.QLFindInstancePage（qinglan.find.nav=寻找实例）：同款两条引导（群文件整包导入 → 轻蓝同步页；选择目录 → 注册+切目录）。页面监听 GameDirectoryManager.selectedInstanceProperty，一旦 QL2 实例被找到并选中（loadVersions 会 setSelectedInstance），自动跳转该实例的设置页。Controllers 挂 getQinglanFindInstancePage（Lazy 持有+清理）。commit e92d93c50。

## 续10（2026-10-20）：论坛页 + 阶段3 启动 + MTR 结构定稿

用户指令：①首页提示先看论坛；②左侧游戏组加论坛页（反代/解析文章，先列表再点进去展示）；③MTR 是独立入口，含时刻表查询（车站按站台/按线路/线路时刻表Beta）+路径查询（1 API + 5 缓存），反代到展示到查询有参考项目可借鉴。

### 论坛页（commit 53886bd06）
- HMCLCore org.jackhuang.hmcl.ql.QLForum：Halo 匿名 content API。列表 GET http://qlbbs.ota8a.cn/apis/api.content.halo.run/v1alpha1/posts?size=&page=&sort=publishTime,desc（page/size/total/items；metadata.name=稳定 id，spec.title/publishTime/pinned，owner.displayName，status.excerpt/permalink，stats.visit/upvote）；详情 GET /posts/{name} → content.content HTML。HTTPS 超时 → 固定 HTTP + 3 次重试（间隔 600ms）。JsonUtils.fromNonNullJson(GSON,...,JsonObject)。
- HMCL QLForumPage：TransitionPane 列表↔文章。列表=ForumRow 卡片（标题+置顶标记+摘要+作者·日期·浏览数）+加载更多；文章=原生渲染（regex 分块 h1-h3/p/li/blockquote/pre，a→JFXHyperlink(org.jackhuang.hmcl.ui.construct.JFXHyperlink 注意非 com.jfoenix)，img→图片链接，localhost:8090→BASE 归一，spinnerPane show/hideSpinner）。ContainerAnimations 只有 NONE/FADE 没有 BACK。
- 侧栏游戏组第 4 项 论坛(SVG.CHAT)；Controllers.getQinglanForumPage。
- 首页预览提示改为「关于整合包」先看论坛（http://qlbbs.ota8a.cn）；update.channel.nightly.hint 改为单行值（**坑：properties 多行续行 \ 在 PowerShell 写入时极易断续——单行 + 内联 \n 最稳**；en 的 hint 在 title 之后单独补插）。
- 窗口标题去版本号：Metadata.FULL_TITLE = FULL_NAME（TITLE 保留 HMCL+version 给关于页用）。
- checkTranslations 陷阱再记：ghost 中文文本残留键会在 en/zh 报 missing key —— zh 也要同步换行。

### MTR 页（阶段3 新结构，动工前需 API 细节）
- 结构：独立入口 MTR → 查询时刻表（车站时刻表·按站台 / 车站时刻表·按线路 / 线路时刻表 Beta）→ 原生 UI；查询路径（1 API 查询 + 5 缓存查询）。
- 候选数据源：MTR Web :14614 / reporter :19451；最终实现改为本地 Dijkstra 计算路径（"反代到展示到查询"的远端 API 未用），时刻表三视图 + 路径查询均已上线，见续11。

## 续11（2026-10-21）：MTR 页收尾 + 许可头 / i18n 清扫

接手验证：`:HMCL:compileJava` + `:HMCL:checkTranslations` + `:HMCLCore:test --tests org.jackhuang.hmcl.ql.*` 全绿（离线跑在 `<gradle-home>`，这是 `~\.gradle` 的工作区副本——DSH 沙箱不允许写 C 盘 gradle home；全量 `:HMCLCore:test` 只有 `WinRegTest` 失败，原因是本环境连 `HKCU\Software\<新建键>` 都被拒（PowerShell 同样复现），属沙箱限制不是代码问题）。

修掉的真实缺陷（每条都有测试或可复现路径）：
1. **`QLMtrTimetable.nextAt` 必崩**：`departures.sort(null)` 对 record 取自然序，≥2 班即 `ClassCastException` → 「线路时刻表 Beta」「车站·按线路」一点就永远停在"查询中"。改为显式 `Comparator.comparingLong(departureAt).thenComparingLong(arrivalAt)`。
2. **`QLMtrRouter` 备选方案实际只出 1 条**：ban 集合用合并后 leg 的端点 `R|上车站>下车站`，dijkstra 查的是相邻站单跳边 → 跨多站乘车段 ban 不中，下一轮返回同一路径又被 signature 去重丢掉。现 `MtrPath` 新增 `rideEdges`（单跳边键集合），`findPaths` 全量 ban → "最多 5 条方案"真正生效。
3. **`MtrPath.total` 与文档不符**：文档写"含换乘惩罚"，实现只累加原始边权 → 补上 180s 换乘惩罚（与搜索定价一致，UI 的"总计 X 分钟"不再是乐观值）。
4. **路径查询卡 FX 线程**：`runPathQuery` 同步跑最多 `KEPT_PATHS*3` 次 dijkstra → 改 `Task.supplyAsync(Schedulers.io())` + `whenComplete(Schedulers.javafx())`。
5. **`QLMtr` 解析脆弱**：地图 API 对空集合会整个省略成员，`getAsJsonArray` 直接 NPE → 新增 `arrayOrEmpty`，stations/routes/connections/durations/departures/arrivals/cars 全线兜底。
6. **可测性 seam**：`fetchSar` / `fetchDepartures` / `fetchArrivals` 各加包私有 `fetch*(String base, …)` 重载（公版委托 `BASE`，行为不变），单测用进程内 `HttpServer` 注入，绝不碰游戏服务器。
7. **同一车站再点一次不重查**：`picked.set(同值)` 不触发 property，实时到站永不刷新 → 点击时先 `set(null)` 再 `set(station)`。
8. `QLMtr.java` 有一处方法被挤成同一行（`}    private static String sarEndpoint`），已拆。
9. **i18n 粘连行**：`I18N_zh.properties` / `I18N_zh_CN.properties` 里 `qinglan.guide.notFound` 与 `settings.launcher.update.disabled` 挤在同一行 → 繁简下 notFound 尾巴挂着半截 key，且 update.disabled 回落英文。已拆成两行。**坑记录：checkTranslations 抓不到这种**（它只按 key 集合比对，粘连行的第二个 key 根本不存在），写中文 properties 一定要确认行尾换行。
10. **GPL 许可头**：`QLForum`/`QLMtr`/`QLMtrRouter`/`QLMtrTimetable`/`QLFindInstancePage`/`QLForumPage`/`QLMtrPage` 7 个文件缺 header（checkstyle `RegexpHeader` 门禁项；checkstyle jar 不在离线缓存里跑不了 `checkstyleMain`，改用 `config/checkstyle/license-header.txt` 的 17 条正则逐文件核对 → 20 个 ql 文件全通过）。
11. **新增 `QLMtrTest`（7 用例）**：快照归一（id 大写 16 位 hex、坏 id/坏 connection 丢弃、双语名分段、方向后缀）、lean JSON 容错、departure 包展开（过期过滤/排序/limit/无表返空）、arrivals 十进制→hex 与 4s 缓存不再打网络、最廉方案与多备选、换乘计费 + hidden 线路排除、环形闭边取平均段时 + 退化查询返空。
12. `QLMtrPage` 的 `network`/`departures` 补 `@Nullable` 与文档（AGENTS 空值显式化）。

顺带收编：本次接手时工作区还有一批上一轮未提交的在途改动——论坛文章 TextFlow 富文本渲染、寻找实例页 `locateGameRoot` + 目录注册后自动认领实例、设置页只留语言/调试且更新彻底屏蔽（`UpdateChecker.init/requestCheckUpdate` 直接 return）、侧栏 MTR 入口、微软登录 client id 兜底、`IntegrityChecker.isSelfVerified` 恒真（自建 jar 无官方签名，否则微软登录被拦）。遗留小坑记在 §8 P2「MTR 页遗留」6 条。

新包：`HMCL/build/libs/HMCL-3.16.4-ql2.jar`（shadowJar，含以上全部）。
## 续12（2026-10-21）：车站时刻表改成「选站 → 上方双模切换」

用户拍板：入口不该拆成「按站台」「按线路」两条，直观做法是 **车站时刻表 → 选车站 → 结果区上方切换 按站台 / 按线路**。

- `QLMtrPage`：首页四条入口收敛成三条（车站时刻表 / 线路时刻表 Beta / 查询路径）；`stationView(title, StationLoader)` 与 `StationLoader` 接口删除，换成 `stationTimetableView()`：一个 `StationPicker` + `ToggleGroup` 两个 `JFXRadioButton`（默认 按站台）+ 一行随模式切换的 hint + 结果区。
- 关键语义：**切换模式不重选站**——`selected` 记住已选车站，模式变更只重跑 `render`；重敲同一车站仍会强制重查（这是实时到站目前唯一的手动刷新入口）。
- i18n：新增 `qinglan.mtr.station.timetable(.hint)`、`qinglan.mtr.mode.platform`、`qinglan.mtr.mode.route`、`qinglan.mtr.pick.station.first`；删掉不再引用的入口名 `qinglan.mtr.station.platform` / `qinglan.mtr.station.route`（它们的 `.hint` 保留当模式说明）。en/zh_CN/zh 三处同步，yue/nan 继续回落繁中不加。
- 坑照旧：properties 每行必须自带换行，写完除了 `checkTranslations` 还要 grep 行尾。
## 续13（2026-10-21）：到站自动轮询 + 按线路按站台链归并

- **实时到站自动刷新**（用户批准的 P2 遗留①）：`QLMtrPage` 加 `Timeline autoRefresh`（`ARRIVALS_POLL_INTERVAL=20s`，INDEFINITE），`onPageShown` playFromStart / `onPageHidden` stop；`stationTimetableView()` 把自己的 `render` 注册进 `stationPoll`，定时器只在 `modeRow.getScene() != null` 时跑 —— 换视图即自动静默，任何导航都不用记得停表。
- **车站归并改用数据语义**（P2 遗留②）：新增 `QLMtr.stationGroupIds(sar, stationId)` —— 沿 `station.connections` 做传递闭包（MTR 里一个月台就是一个 station id，同站月台靠 connections 互链）；完全没有任何链时退回按中文名归并（老导出兼容）。`loadStationRoutes` 不再自己按 `primaryName` 全等扫全表。
- 新增 `QLMtrTest.groupsPlatformsOfOneStation`：链式闭包 / 孤站单独 / 未知 id 原样返回 / 无链回落名字 / 互链不死循环。
- 删掉 `qinglan.mtr.station.platform` / `.route` 两个入口名键后 `.hint` 两键转为模式说明文案（续12 已记）。
- 产物：`:HMCL:makeExecutables` → `HMCL/build/libs/HMCL-3.16.4-ql2.exe`（HMCLauncher.exe + jar 直接拼接，同目录附 .sha1/.sha256/.sha512），按用户要求投放到 `F:\mc\`。
## 续14（2026-10-21）：MTR 页结构重写（三 tab + 可搜索下拉 + 全拼）

用户：车站改**下拉式**选择、输入框要**支持拼音**（后补：全拼和首字母都要），并且「整个 MTR 里面的结构都要重写，太难崩了布局」。

- **页面重写成三 tab 工作台**：`车站时刻表 / 线路时刻表 Beta / 查询路径` 三个 `ToggleButton` 常驻顶部 + 一行随 tab 变的说明；每个 tab 一个 `ScrollPane`，`TransitionPane` 切换，**选中项在下拉框里始终可见**（旧版选完把列表清空、看不到已选的老毛病一并解决）；`stationView/StationLoader/rebuildEntryList/detailHeader(返回钮)/entryButton/sectionHeader` 全删。
- **下拉式选择 `NameComboBox<T>`**：可编辑 `ComboBox` + `FilteredList`（`backing.setAll` 换数据），编辑器只当搜索框（`StringConverter.fromString` 恒返 null），输入即过滤，**Enter 直接选中第一条匹配**，Esc 回到已选文本，`setOnHidden` 还原显示，`select(item)` 供程序化选中。车站/线路/出发/到达 四个下拉共用一个实现。
- **拼音**：`QLPinyin`（HMCLCore）两级能力——首字母走 GB2312 一级区 26 组边界表（零依赖，`南际铁路→NJTL`）；全拼走打包资源 `assets/ql/pinyin.txt`（6711 字，Unihan `kMandarin` 去声调，`ü` 同时收录 `lu`/`lv` 两种拼法），`searchKey` 把 中文 / 罗马字 / 首字母 / `nan ji tie lu` / `nanjitielu` 拼成一个 blob，过滤只做 contains。资源已确认进 `HMCLCore` jar 与最终 `HMCL-3.16.4-ql2.jar`（`jar tf` 见 `assets/ql/pinyin.txt`），exe 运行时可加载。
- 再生成拼音表（下次加字/换版本时）：下载 `https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip` → 解压 → `<tmp>\BuildPinyinTable.java`（单文件 `java -Dfile.encoding=UTF-8 BuildPinyinTable.java`）读 `Unihan_Readings.txt` 的 `kMandarin`，只保留 GB2312 汉字区（0xB0A1..0xF7FE）字符，写到 `HMCLCore/src/main/resources/assets/ql/pinyin.txt`。注意 PowerShell 里 `-Dfile.encoding=UTF-8` 要整个加引号；Java 源码里 `\p{M}` 需要双反斜杠。
- **联动**：车站「按线路」的每一行点击后**切到线路 tab 并选中该线路**（旧版是压一个新页面）。轮询改为按当前 tab 决定：车站→重渲车站、线路→重渲时刻表、路径→停（`activePoll`）。
- i18n：新增 `qinglan.mtr.pick.station` / `pick.route` / `plan.from` / `plan.to`；删除已无引用的 `section.timetable` / `section.nav` / `no.station` / `pick.station.first` / `route.pick.first`；三个语言文件各 32 个 `qinglan.mtr.*` 键，脚本核对「页面用到的键全部存在」且「定义的键全部被使用」（旧版遗留的 `Mode` 死枚举也顺手用上了：现在就是 tab 枚举）。
- 验证：`:HMCL:compileJava` 0 错误、`:HMCL:checkTranslations` 绿、`:HMCLCore:test --tests org.jackhuang.hmcl.ql.*` **5 个类 24 个用例 0 失败**（新增 `QLPinyinTest` 6 例：表已打包且解析、站名首字母、非汉字跳过、全拼连写/分写、lu/lv 双拼、四种输入风格全命中 + 反例）、`:HMCL:makeExecutables` 绿 → exe 14.2 MB。
- 已知取舍（下次要不要做由用户定）：~~① 下拉无颜色点~~ *已解决：现行 Picker 弹层与普通块均带 12px 色块*；② GB2312 之外的生僻字只能靠汉字或英文命中；③ `kMandarin` 每字只给一个主读音，多音字（长 chang/zhang 之类）要全覆盖得并 `kHanyuPinlu`；④ 无匹配时不再显式提示（下拉列表空着即反馈）。
- GUI 冒烟仍由用户执行（沙箱不能写 `%USERPROFILE%\.hmcl`）。

---

## 11. 实施方案：MTR 页用 HMCL 原生控件重写 + 恢复二级入口（**已按此实施，见续15**）

### 11.0 指示拆解
- 「难绷的是**那个输入框**，不是二级入口」→ 续14 删掉首页入口列表是误读，**要恢复二级入口**；只换掉车站/线路选择那个输入框。
- 「用 HMCL 原生方块写」→ 选择器与结果区改用 HMCL 自带的方块组件（`TwoLineListItem` / `RipplerContainer` / `OptionsList` / `JFXComboBox`），不再手写 `setStyle("-fx-...")` 的 HBox 行。
- 保留上一轮的成果：下拉式选择、拼音（全拼 + 首字母）、按线路→线路 tab 的联动、轮询、`stationGroupIds`。

### 11.1 目标结构（文字线框）
```
侧栏 MTR 轨交
┌ 顶栏：刷新 · 最近更新时间
│  ── 一级：入口方块列表（ComponentList/OptionsList + ClassTitle 分组）──
│    [ 车站时刻表        查看某站的到站与途经线路 ]
│    [ 线路时刻表 Beta    整条线路的班次与站序   ]
│    [ 查询路径          本地计算最多 5 条方案   ]
│  ── 二级：detail 页（返回钮 + 标题）──
│    标题：< 车站时刻表
│    ┌ 原生下拉（JFXComboBox，弹层每项是方块）┐  ▾
│    │ ▣ 观塘 · Kwun Tong        [色块]      │
│    └ 输入即过滤：中文 / 罗马字 / 全拼 / 首字母 ┘
│    ○按站台  ○按线路        （RadioChoiceList 或两个 LineToggleButton）
│    结果：一叠 TwoLineListItem 方块（标题=线路名/终点，副标题=站台·分钟·实时）
```

### 11.2 组件选型（全部是仓库里现成的原生件，附出处）
| 用途 | 原生组件 | 现成参考 |
|---|---|---|
| 一级入口卡 | `OptionsList.addTitle/addListElements`（新）或 `ComponentList`+`ComponentList.createComponentListTitle`（稳） | `SettingsPage`、`ui/download/VersionsPage:158` |
| 列表项方块 | `TwoLineListItem`（title/subtitle + `titleProperty/subtitleProperty`） | `GameCrashWindow:340+`、`AdvancedListItemSkin:41` |
| 方块可点+涟漪 | `RipplerContainer`（**必须**包一层：`TwoLineListItem` 构造里 `setMouseTransparent(true)`） | `InstallerItem:285`、`AdvancedListItemSkin:39` |
| 下拉选择 | `JFXComboBox`（HMCL 全线用 jfoenix 的 combo，不用裸 `ComboBox`） | `ui/download/VersionsPage:304`、`GameSettingsPage:473` |
| 分组标题 | `ClassTitle` | construct 包 |
| 加载/失败态 | `SpinnerPane.showSpinner/hideSpinner/setFailedReason`（已在用） | `construct/SpinnerPane` |
| 模式二选一 | `RadioChoiceList`（带 ToggleGroup 的原生单选块列表）或 `LineToggleButton` | `construct/RadioChoiceList` |
| 线路色块 | `Region` + `-fx-background-radius` / `-fx-background-color: rgb(...)`，或沿用现 `colorDot`；**待拍板** | 目前手写 Label `●` |
| （可选）真 tab | `TabHeader`+`TabControl.Tab`、`AdvancedListBox.addNavigationDrawerTab` —— 只有想改回 tab 形态才用 | `construct/TabHeader:44` |

### 11.3 下拉 + 拼音的接法（把续14 的东西接到原生件上）
1. `JFXComboBox<Item>`，`Item` = 小 record（station/route + 预计算 `QLPinyin.searchKey`）——网络加载时算一次，按键时只做 `contains`。
2. items 用 `FilteredList`（沿用现有 `NameComboBox` 里的过滤逻辑，改成不吃 `ComboBox` 而是吃 `JFXComboBox`）。
3. `setCellFactory`：每格 = `RipplerContainer(TwoLineListItem)`，标题=中文名（线路名），副标题=罗马字/方向，左 graphic=色块。
4. `setButtonCell`：选中后在框内显示「[色块] 中文名 · 罗马字」——解决旧版「选完看不见已选」。
5. 编辑器 `setEditable(true)` 只当搜索框：`StringConverter.fromString` 恒 null、Enter 选第一条、Esc 还原（逻辑已写好，平移即可）。

### 11.4 结果区方块化清单（把手写行换成原生件）
- `ArrivalRow` → `TwoLineListItem`：标题 `终点/线路`，副标题 `x 分钟 · 站台 n · 实时`，左 graphic 色块（删现有 5 个 Label 拼接）。
- 按线路行 → `TwoLineListItem` + `RipplerContainer`，点击跳「线路时刻表」detail 并选中该线路（保留现有 deep-link）。
- 线路时刻表：班次列表 → `TwoLineListItem`（标题 `HH:mm`，副标题 `x 分钟后`）；站序 → 一行 `TwoLineListItem` 或色块链。
- 方案卡 `PathCard` → 外层 `TwoLineListItem`（标题 `总计 x 分钟 · 换乘 n 次`，副标题 腿摘要），展开的腿用 `ComponentSublist` 或缩进 `TwoLineListItem`。
- 统一删掉散落的 `setStyle("-fx-opacity/-fx-font-size")` 手写串，改走 `two-line-list-item` 的 title/subtitle 伪类，主题切换才对。

### 11.5 恢复二级入口的 git 基线
- 壳直接取 `git show 6d7d7ad36:HMCL/src/main/java/org/jackhuang/hmcl/ui/ql/QLMtrPage.java` 的 `rebuildEntryList / detailHeader / entryButton / sectionHeader / stationView / routeTimetableView / navView`，但**入口卡改用原生件**（11.2 第一行），返回钮用 `Navigator`/`LineButton` 而不是手写 JFXButton。
- 从当前版只回搬三样：`NameComboBox` 的过滤/拼音接入点、`stationGroupIds` 调用、按 tab 的轮询（改成按 detail 视图类型决定是否轮询）。
- 删除当前版的 `Tab`/`tabGroup`/`selectTab`/`selectTabByEnum`（deep-link 改为直接 `contentPane.setContent(线路detail(route))`，就是旧写法）。

### 11.6 i18n 变更清单（en / zh_CN / zh 必须同步）
- 恢复被删的：`qinglan.mtr.section.timetable`、`section.nav`、`no.station`、`pick.station.first`、`route.pick.first`。
- 保留新键：`station.timetable(.hint)`、`mode.platform`、`mode.route`、`pick.station`、`pick.route`、`plan.from`、`plan.to`、`station.platform.hint`、`station.route.hint`。
- 需要新增：`qinglan.mtr.no.match`（下拉无匹配时给一行方块提示，替掉「列表空空如也」）。
- 规则照旧：en 半角括号；一行一键，写完 grep 行尾防粘连。

### 11.7 分步实施（每步都跑门禁，失败即回滚该步）
1. **A 壳回退**：恢复二级入口 + detail/返回（原生件先照旧 HBox 占位）→ `compileJava`+`checkTranslations`+ql 24 用例+`makeExecutables`。
2. **B 原生下拉**：`JFXComboBox` + `FilteredList` + `cellFactory/buttonCell` = `RipplerContainer(TwoLineListItem)` + 色块，拼音过滤接上 → 同上门禁（`QLPinyinTest` 必须一直绿）。
3. **C 结果区方块化**：`ArrivalRow`/线路行/班次/方案卡 → `TwoLineListItem`，删手写 style 串 → 同上门禁。
4. **D 收尾**：入口卡换成 `OptionsList`/`ComponentList`，补 `no.match` 键与提示，GUI 冒烟 → `makeExecutables` 后 exe+jar 投 `F:\mc`（SHA1 校验），更新本文档。

### 11.8 已知坑（做之前记住）
- `TwoLineListItem` 自己 `setMouseTransparent(true)`：**不包 `RipplerContainer` 就点不动**。
- `JFXComboBox` 弹层默认高度有限，网络有几百个站/线路：popup 里必须靠 `ListView` 虚拟化（cell 复用），**cellFactory 里不要 new 出带监听/Runnable 捕获的复杂对象**，每格只做 setText/setGraphic。
- 可编辑 combo 的 `fromString` 返回 null 是有意为之（编辑器只当搜索框），别让 `setOnAction` 里再触发一次过滤导致死循环。
- 色块颜色：`MtrRoute.color()` 是 sRGB int，深色主题下要注意对比度；`-monet-on-surface` 这类主题变量只在 skin CSS 里可靠。
- `OptionsList` 源码里写着「TODO 打算用它替掉 ComponentList，但还有问题」→ 保守起见入口卡先 `ComponentList`，`OptionsList` 作为可选试验。
- checkstyle `RegexpHeader`：新文件必须带 GPL 头（离线没有 checkstyle jar，用 `config/checkstyle/license-header.txt` 的 17 条正则自查）。
- 沙箱不能写 `%USERPROFILE%\.hmcl`，GUI 冒烟由用户执行；gradle 用工作区副本 `<gradle-home>`。

### 11.9 用户拍板结果
1. 「原生方块」是指 **列表项方块**（`TwoLineListItem`，我按这个排的）还是 **线路色块/方向牌**（`Region` 画的方块，像真实 MTR 那种带色底 + 白字线号）？还是两者都要？
2. 一级入口要不要顺带带上图标（`SVG`）和当前选中实例/线路的状态，还是纯三块文字卡？
3. 下拉要不要「常用站点」记忆（按 `QLSyncState` 或独立 state 记住上次选的站，进页面直接填好）？
4. 多音字全拼要不要再并 Unihan `kHanyuPinlu`/`kHanyuPinyin`（表会再大 ~15KB）？
拍板：①「原生方块」= **HMCL 原生控件**（不是特指色块），并且「就算没有输入框你也给我创建个好看的」→ 自己拼一个好看的原生选择器；② 一级**就仨入口**，图标/状态之类的附加都不要；③（记住上次选站）没提，按「其他不用」理解为不做；④ **并入** 多读音字段。

## 续15（2026-10-21）：MTR 页按 §11 落地（原生控件 + 二级入口 + 全拼多音字）

- **结构回到两级**：一级 = 3 个入口（`ClassTitle` 分组 + `RipplerContainer(TwoLineListItem)` 卡片，鼠标手型 + 涟漪，点击进二级）；二级 = 带「返回」按钮的原生页面（`backRow` + `pageBody` + `ScrollPane`）。`open(View, Node)` 统一切换并重置轮询目标。三 tab 版废弃。
- **原生选择器 `Picker<T>`（VBox）**：`JFXTextField`（提示文字 = 搜索，输入即过滤）+ `JFXComboBox`（**非**可编辑，避开上版那个难绷输入框）+ 一行 `no.match` 提示；弹层与按钮单元都是同一个 `ListCell`，格子里放 `TwoLineListItem`（主行=中文名/线路名，副行=罗马字·方向）+ 左边 12px `Region` 色块（`-fx-background-radius` 原生形状，`color==0` 隐藏）。Enter 直接选第一条；`pick(item)` 供程序化选中并清掉过滤文字；`setChoices` 时**一次性**预计算 `QLPinyin.searchKey`，过滤只做 contains。车站/线路/出发/到达 四处共用。
- **结果区全方块化**：到站 = `TwoLineListItem`（主=终点，副=线路，原生 `getTags()` 药丸放「x 分钟 / 站台 n / 终点站 / 实时」）；途经线路行、班次行、方案腿全部走同一个 `block(...)` 工厂；删掉散落的 `-fx-opacity/-fx-font-size` 手写行拼接（主题切换才跟得上）。深链保留：点「途经线路」= 选中该线路并进线路页。
- **拼音表并入多读音**：`BuildPinyinTable` 现在吃 `kMandarin` + `kHanyuPinlu` + `kHanyuPinyin`，解析要点：`kHanyuPinyin` 值形如 `20811.060:háng,xìng,xíng`（要取最后一个冒号后的内容），`kHanyuPinlu` 值形如 `zhǎng(1879) cháng(1179)`（要剥掉括号频次尾），两者都以空格/逗号分隔；声调符号走 NFD + `\p{M}` 去除，`ü` 同时留 `lu`/`lv`。结果：**6763 字（GB2312 汉字区全覆盖）/ 1674 字多读音 / 70.9 KB**，`长→zhang,chang`、`行→xing,hang,heng`、`重→zhong,chong,tong`、`朝→chao,zhao,zhu`。kMandarin 优先放在首位，`pinyin(joined)` 只用主读音，其余读音作为独立词进 key。
- i18n：恢复 `qinglan.mtr.section.timetable` / `section.nav`（一级分组标题又用上了），新增 `platform.tag` / `terminating` / `next.train` / `no.match`；三语言文件按键审计：页面用到的 41 个键全存在、定义的 `qinglan.mtr.*` 无一个 unused（脚本对账，防粘连行也顺手复查）。
- 验证：`:HMCL:compileJava` 0 错误 · `:HMCL:checkTranslations` 绿 · `:HMCLCore:test --tests org.jackhuang.hmcl.ql.*` **25 用例 0 失败**（新增 `indexesPolyphones`）· `:HMCL:makeExecutables` 绿 → exe 投 `F:\mc`。
- 踩坑记录：`StringConverter` 在 **`javafx.util`** 不在 `javafx.scene.control`（这轮又踩一次）；分段写大文件时必须**当场核对方法齐全**（这次漏了 `entryBody/entryBlock/clickable/renderPlanPrompt` 四个方法 + `pageBody` 返回类型写错成 ScrollPane，编译期才抓到）；`JFXComboBox` 才是 HMCL 全线用的下拉，裸 `ComboBox` 不算原生味。
- 待办不变：正式资源服务器 URL、`servers[]` 消费、下拉色块之外的图标位。

## 续16（2026-10-21）：MTR 页美化（tag 药丸 / RadioChoiceList / 线色班次与站序链 / 删手写 style）

## 续18（2026-11-17）：同步架构调整——单频道 + RPU 门禁 + 资源包移交

用户判定三件事：① 资源包/光影由游戏内 **RPU 模组**自己的机制更新，启动器不该管；② 不再区分「优化包/标准包」，只保留一套 mods 清单；③ 同步页只对**带 RPU 模组的实例**开放（本机实测标记 `[C]QingLanRPUClient-1.0.0-updated.jar`），其余提示「此实例不可同步」。

- **资源包移交**（`QLManifest.isRpuOwned`）：发布器只把 `config/**` + `options.txt` 归入 common，`resourcepacks/**`、`shaderpacks/**` 进报表 RPU 桶、不进清单；同步引擎对这类条目直接跳过（不再拉那 ~700MB），已跟踪的旧文件**只 untrack 不删除**（`SyncOutcome.handedOver`），完成提示写「资源包已交由游戏内 RPU 模组管理：N 个」。
- **单频道**：`MODS_CHANNELS` 收缩为 `{std}`（新增 `CHANNEL_MODS`）；`fromJson` 只要求 `std`+`common`；发布器不再产出 pfm；种子/同步统一 std；state 里遗留 pfm 跟踪项按「退役频道」移交（untrack 不删），`mods-pfm/` 目录原样保留。
- **门禁 `QLPackGuard`**：`QLSyncPage.refreshInstanceHint` 判定，非整合包实例禁用「检查更新/保存地址/下载并应用」并显示 `qinglan.sync.notSyncable`，实例行显示 RPU 文件名；删掉频道单选组与 4 个 `qinglan.sync.channel*` 键（含 yue/nan）。
- 测试：新增 `QLPackGuardTest`（5 例）、`QLSyncManagerTest.handsResourcePacksOverInsteadOfDownloadingOrDeleting`、`QLManifestToolTest.leavesResourcePacksToTheRpuMod`；E2E 删 pfm 场景、common 期望 270→227；`QLSeedImporterTest` 计数与「退役 pfm 目录不动」断言更新；ACL 不可写环境里 seed 读拒绝分支改 assume 跳过。
- 门禁：ql 58 例 · checkTranslations · compileJava · 四个 checkstyle 任务全绿。
