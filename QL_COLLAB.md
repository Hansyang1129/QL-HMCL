# QL_COLLAB — HMCL for QingLan MTR 2.0 协作 / 交接文档

> 给接手者（人类或 AI）：这是本项目的唯一权威状态文档。开工前先读完本文件，收工后更新「工作日志」并提交。
> 仓库：`<repo>`，分支 `ql/main`（fork 自 HMCL-dev/HMCL v3.16.4，**上游已锁死，不跟更新**）。
> 文中 `<repo>` / `<tmp>` / `<gradle-home>` / `%USERPROFILE%` 是作者本机路径的占位符，按自己的环境替换。

---

## 1. 项目目标

为「轻蓝 MTR 2.0」整合包社区定制的启动器（品牌中文名为**轻蓝**，繁中作**輕藍**，罗马字 Qinglan；历史工作日志里的旧写法不改）：

1. **增量同步**（已完成）：manifest 驱动（替代 1.7GB 整包重下），支持双频道（std 官方包 / pfm 优化包）+ 可选 mod 组（voxy/dh/axiom）。
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
- `QLManifest.java` — manifest 模型 + `fromJson` 严格校验（schema=1；channels 必含 std/pfm/common；路径禁 `:`、`..`、`/` 开头；sha1/urls 必填；频道内路径去重）。嵌套 `pack{name,version,updatedAt}`。
- `QLDirectoryLayout.java` — 目录语义：活跃=`mods/`，停用=`mods-std/`、`mods-pfm/`，`.qinglan-channel` 标记；`switchChannel/recover/detectActiveChannel/modsDirectory`。
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

1. `collectDesiredFiles` 规划**全部** MODS_CHANNELS（std+pfm）条目 + common——切频道**不删**对方频道文件；禁用可选 mod 保留为 `*.jar.disabled`。
2. **optional 分组只对 std 频道的 mods 生效**；config（如 `DistantHorizons.toml`）始终同步——发布器已按此实现，别改回去。
3. purge 只删 `tracked` 里记录的文件（键 `channel + "::" + logicalPath`）；用户手放文件永不触碰；`deleteTrackedFile` 不抛异常。
4. 下载任务 map 的键**必须唯一**——曾用 `label()`（= 频道/文件名）作键导致 24 个同名文件任务被静默覆盖丢失，已改用 `PlannedFile`（record 值语义）作键。任何"文件名当 key"的写法都要警惕。
5. reconcile：OK / RENAME（`.jar`↔`.jar.disabled` 兄弟互换，免下载）/ DOWNLOAD；**RENAME 不计入 unchanged**。
6. 单测/驱动跑 sync 前必须 `CacheRepository.getInstance().changeDirectory(<dir>)`（ETag index 仅在此初始化）；生产侧由 `Launcher.initializeSettingsRuntime()`（`HMCL/Launcher.java:165` 附近）覆盖。
7. URL 拼接必须按路径段 `URLEncoder.encode`（中文/空格/方括号），空格用 `%20` 不是 `+`；manifest 路径禁 `:`、`..`。
8. `versionId` 自动去 `-full` 后缀；pfm 频道当前为空占位。
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
- [ ] pfm 频道真实优化包产出（等优化包 mrpack）。
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
- 待办不变：pfm 真实优化包、正式资源服务器 URL、`servers[]` 消费、下拉无颜色 cell 之外的图标位。

## 续16（2026-10-21）：MTR 页美化（tag 药丸 / RadioChoiceList / 线色班次与站序链 / 删手写 style）

- **tag 药丸**：`block()` 里裸 `Label` 全改 `TwoLineListItem.addTag(tag)`，吃 root.css 自带 `.two-line-list-item > .first-line .tag`（monet-secondary-container 底色 + 圆角 2 + padding 2 + 12px 字号）；「终点站」匾额升级 `addTagWarning`（`.tag:warning` 深浅主题各自适配的 `-fixed-warning-tag-background`）。**顺手修掉一个肖 bug：`getTags()` 首呼会 `firstLine.getChildren().setAll(lblTitle, tagsBox)`，先插 swatch 再加 tag 的话 swatch 会被就地抹掉——现在 addTag 全部前置，带色 + 带 tag 的块色块才真出来。**
- **模式单选改原生**：按台/按线路两个平铺 `JFXRadioButton` + `ToggleGroup` + 单独 hint 标签 → `RadioChoiceList<Mode>`（`Mode` 枚举 PLATFORM/ROUTE），两个 `Choice.setSubtitle` 撒进各自的模式说明，`modeRow`/`updateModeHint`/`modeHint` 全删；选择走 `selectedValueProperty` 监听重渲染，语义与旧 onAction 一致（换模式即重新查询）。
- **线路时刻表**：班次块从 `color=0` 改挂 `route.color()`（班次行也带线色小方块）；站序从「A  →  B  →  C」长文本换成 **逐站方块链**：每站一行 `StackPane(12px 列)` + 10px 线色方块 + 站名（wrap + HGrow），站间插 2×6 线色连杆（padding-left 5 对准列心）。纯色块无文案，不受暗色主题文字对比度影响。
- **swatch 暗色对比**：`MtrRoute.color` 是裸 sRGB，新增 `liftedColor()`——相对亮度 < 0.2 时向白混色（黑线也能看），`swatchStyle()` 统一背景串，BlockCell/普通块/站序块共用，不再各自手写 hex。
- **删手写 style**：二级页大标题 `"-fx-font-weight: bold; -fx-font-size: 16px"` → `title-label + bold` 原生样式类；「近期班次/线路车站」的 `"-fx-font-weight: bold"` → `bold` 类；plan 出发站/到达站/刷新状态/无匹配等 `-fx-opacity: 0.7/0.6` → `subtitle-label` 类（monet on-surface-variant，主题自适应）；页内所有散落 inline style 清零，仅剩数据驱动的 `swatchStyle`。
- **提示行统一**：`notice()` = `subtitle-label` 类 + 居中 + wrap + maxWidth ∞，pick.station / loading / no.arrivals / failed / no.route / no.timetable / plan.pick.first / plan.none 全走它；`hintBlock` 删除。
- **planBlock**：副标题 `"#"+index` → i18n `qinglan.mtr.plan.index`（en `Plan #%d`、zh_CN/zh `方案 #%d`，×3 同步入账）；walk 腿从 opacity 文本改为原生 `block()`（主=换乘步行，副=起点 → 终点，无色无 tag），与行程块同形。
- 验证：门禁全绿（`checkTranslations` · `:HMCL:compileJava` · ql 测试 · `makeExecutables`），exe+jar 已投 `F:\mc`。
- 踩坑记录：`RadioChoiceList.Choice.setSubtitle` 链 AFTER 构造（再要 title 得 `setTitle`）；`ObjectPropertyBase.set` 同值不触发（构选期 setSelectedValue 不会多余重渲染）；`muted()` 已加 `subtitle-label` 的，中途记着别再 add 一次造成重复样式类。
- **追加（同日）：结果区卡片化**——用户看实拍嫌散行乱，拍板照论坛 `ForumRow` 的做法：`StackPane` + 原生 `.card` 样式类（半透明 surface 底 + 圆角 4 + padding 8 + 阴影）。落点：到站行 `card(item)`、途经线路行与一级入口走 `clickable()` 内部先包 `card(...)` 再上 `RipplerContainer`（**别把 `.card` 直接挂 RipplerContainer 上**，它的 `setShape` 矩形 clip 会把 dropshadow 切平）；线路页头块/班次行/站序链卡化，`planBlock` 整体一张卡（inner spacing 2→8）；三个结果 VBox 间距 4→8。legs 在方案卡内部保持裸两行块（论坛 inner 同構），不卡中卡。

## 续17（2026-10-21）—— 按用户 5 图重构：平台卡 × 3 班 / 线路卡 + 停靠王牌 / 逐站卡 / 1 web + 5 缓存

- **path API 打通**：`POST /mtr/api/map/directions`，body `{"startTime":<epochMillis>,"startStationName":"...","endStationName":"..."}`；站名大小写不敏感，匹配全名或按 `|` 分段的任一部分。响应 `data.connections[]`（字段：`routeId`，空=步行；`start/endStationId` hex；`start/endPlatformName`；`startTime/endTime` epoch ms；`walkingDistance`）。**只返回一条路径**（连接数组即顺序 legs）。先前 `path` 端点是空壳（其他 name 也回占位），`directions` 才是真本体。QLMtr 新增两个 record：`MtrDirectionsLeg(ride/routeId/startEndStationId/startEndPlatformName/departure/arrival/walkingDistance)` 与 `MtrDirections(time, legs)`，`fetchDirections(fromName,toName)` POST，重试逻辑与 arrivals 相同。
- **QLMtrRouter 三改**：
  1. 换乘计数修正（图五吐槽）：tracePath 里步行腿不再 `transfers++`，改为 **ride→ride 且 routeId 变化才 +1**，与搜索用的 TRANSFER_PENALTY 判定同步（walk 前后的两次换线只算 1 次）。
  2. 同线/同路段合并（图五吐槽）：签名从 routeId 改为 **`主名#number|from>to`** 行程序列——同 A→B 段多个平行交路（不同 routeId 同名）折叠为一条，保留最便宜者。
  3. 新增 `directionsToPath(sar, directions, fromName, toName)`：把 web legs 映射成 MtrPath（站名查 SAR，空站 id 用起讫显示名兜底），total = 末腿 endTime − 首腿 startTime，换乘同 ride→ride 规则。
- **图一**：`entryBody()` 加 `Insets(16)`（与二级页同），卡片不再顶到滚动条右缘。
- **图二（按站台）**：`loadPlatformArrivals` 重写为**站台优先**——一次 `fetchArrivals(全线站组ids, 3)`，按 `platformId` 分组，每站台 = 一张卡：卡头 `qinglan.mtr.platform.tag`（站台 n），卡内排序取最近 **3 班**（`TRAINS_PER_PLATFORM=3`，替换原 KEPT_ARRIVALS_PER_PLATFORM=6）；行 = 原生 `arrivalRow()`（不再外套卡），无实时数据的站台直接不出卡（没标签没法立卡），全空回 no.arrivals。站台按标签数字排序（非数字排末尾）。渲染前 `stationPicker.selected()==null` guard 防 poll 竞态。
- **图三（按线路）**：`loadStationRoutes` 重写——每条线路一张 `clickable` 卡：title=线名、subtitle=方向、色块=route.color()，**tag 药丸 = 近一班时间 + 本站停靠站台列表**（站台标签来自同一批 arrivals 的 platformName 映射；多条站台线在该站多个站台停靠时全部列）。数据源 = 批量 arrivals(maxCount=1) + nextAt(stopIndex) 兜底。渲染前同样有 selected guard。
- **图四（线路时刻表）**：`renderRouteTimetable` 重写为**逐站卡片**：顶部线路 header 卡，然后按站序每站一张卡：title=站名、swatch=route.color、tag=近一班（优先该站实时 arrivals 行的 arrival 时间 + 站台药丸；无实时用 `nextAt(packs, route, i)` 兜底；两者皆无 → no.timetable）。stopChain/stopRow/stopLink 全删（被逐站卡取代）；`qinglan.mtr.departures`/`qinglan.mtr.stops` 两个键 ×3 语言删除（页面已不引用）；`KEPT_DEPARTURES`/`CLOCK` 的 DEPARTURE_TIME/clockText 一并删。poll 竞态 guard：`routePicker.selected() != route` 直接丢弃结果。
- **图五（路径规划）**：`runPathQuery` 合一 Task：web `fetchDirections`（**异常吞掉置 null，离线也能继续**）+ 本地 `findPaths(…,KEPT_PATHS=5)` → PlanBundle(web, paths)；渲染 #1=web 卡（`planBlock(index, path, web=true)`，`qinglan.mtr.plan.web` 药丸），#2..=本地候选。web 空/失败 → 只剩本地 5 条；全空 → plan.none。
- **i18n ×3**：改 hint ×2（route.timetable.hint=“按站序逐站展示最近一班与停靠站台”、plan.hint=“首条来自网页查询，另附本地缓存的最多 5 条方案”），新增 `qinglan.mtr.plan.web`（en `From the web` / zh_CN `网页查询` / zh `網上查詢`），删 departures/stops 两键；properties 重写后统一 LF + 末尾 0x0A（脚本 WriteAllLines 引入 CRLF 已回写）。
- **QLMtrTest**：+3 用例（`parsesDirectionsReplyAndShapesIt` 打桩 `/mtr/api/map/directions` 校验解析与 directionsToPath 换乘/总和；`countsLineChangesWithoutWalkLegs` 换线无步行也计 1 次；`mergesParallelVariantsOfOneLine` 平行交路折叠为 1 条）。既有 `chargesTransfers…` 断言在新规则下不变（A-walk-B 计 1）。**门禁全绿**：checkTranslations + compileJava ×2 + ql tests（新 29 用例）+ makeExecutables；exe+jar（14:34，14239555/13785411 B）已投 F:\mc（无旧实例）。
- **坑与风**：
  - `WriteAllLines` 默认 CRLF，properties 会全线变体（git 警告同上）；回写用 `WriteAllText + 手动 -replace "\r\n","\n"`。
  - web directions 请求不挂 `?dimension=` 也能用（服务器默认 0=overworld），QLMtr 里仍复用 `sarEndpoint()` 带上以求一致。
  - 方案页 web 卡 total 是**服务器规划时钟**（start..end），与本地 Dijkstra 分钟数口径不同，用户在意的是可行性不必对齐。
  - 平台卡/线路卡/逐站卡都发**批量 arrivals**（1 帧多平台），缓存 4s 吸收 20s 轮询。
  - departuresData packs 缺失时逐站卡不再整页失败，仅各站显示 no.timetable。

### 续17 追加（同日 14:48，用户截图勘误）
- **勘误**：实测确认 SAR `routes[].stations[].name` 字段是**站台号**（"4"/"31R"…）而非车站名——上版图四把站台号当标题渲染（为什么截图里只见 4/6/4/3）。修复：`renderRouteTimetable` 逐站卡标题改用 `stop.stationId()` 反查 SAR 站表 `primaryName`（站表 id→名 map 一次构建，查不到回落站台号），**站台号药丸固定由 stop 记录给出**（不再依赖实时行）；header 卡不加站台药丸。
- **图三同步简化**：`loadStationRoutes` 的"停靠站台"药丸直接走 stop 记录（去重保序），删掉了那次 maxCount=1 的批量 arrivals 请求，整个方法回到同步渲染。
- **教训**：SAR 路线站点行的 `name` 语义是被站台号占用的（旧 stopChain 时代恰好把"站台号"当站名也是错的，只是车站名视觉上没被checks出来）；站名永远从 `stations[]` 表按 id 反查。
- 门禁全绿，exe（14239437 B，14:48:44）+jar 已投 F:\mc。

### 续17 追加2（同日 14:57，web 后处理 + API 查询开关）
- **web 数据后处理**（`QLMtrRouter.directionsToPath` 内两遍清理）：
  1. **同名站步行 drop**——起讫站名相同的步行（进站/站台换转/出站，SAR 站名按 id 反查后比较）不渲染，避免"蜗鸟岛 → 蜗鸟岛"这类噪音行；跨站步行保留（真换乘步行）。
  2. **相邻同 routeId ride 合并**——网页把贯通服务按分段拆 connections（H4 段/L1 段/L2 段…），相邻且同线 ride 并回一条（名/色同、from 取第一段、to 取末段、时间求和）；合并/丢行后重算 transfers（仍 ride→ride 换线计数）。
  3. `total` 不变（末腿 endTime − 首腿 startTime，含被 drop 步行的真实耗时）。
- **启用API查询勾选**：planActions 行加原生 `JFXCheckBox`（`qinglan.mtr.plan.web.toggle`，en `Query the web`/zh_CN `启用API查询`/zh `啟用API查詢`），默认勾选；不勾 → `runPathQuery(false)` 直接跳过 fetchDirections，只出本地 5 条缓存方案。
- **QLMtrTest** +1：`cleansSameStationWalksAndSplitRides`（st桩 6 腿：同名站步行被丢、同线两段合并 60000ms、换乘=1、total 117000 保持全程）。
- 门禁全绿；exe（14240047 B，14:57:22）+jar 已投 F:\mc。
### 续17 追加3：论坛/MTR 两页重构为原生“设置页”样式（15:25）
- **指令**：参考实例设置页（左导航 + 右侧行区）重做论坛与 MTR 两页，用户确认“似乎这样更好点”。
- **MTR 页（QLMtrPage）**：
  - 删除一级入口与返回导航（View/entryBody/entryBlock/backRow/open 全拆），三个查询直接成为左侧 `AdvancedListBox` 侧栏 tab：分组“时刻表”下——车站时刻表（SVG.LISTS）、线路时刻表（SVG.TRIP）；分组“路径规划”下——路径规划（SVG.EXPLORE）。侧栏宽 200，`setLeft(sideBar)`。
  - 中央 `TabHeader(contentPane, stationTab, routeTab, planTab)`（TransitionPane 仍在 spinnerPane 内），侧栏 `addNavigationDrawerTab` 双向接线；`select` 后监听 `tabHeader.getSelectionModel()` 驱动 `updatePoll()`（station/route 轮询、plan 关）。
  - 各 tab 内容 = `tabBody(ClassTitle 标题 + 控件 + 结果区)` → `scroll()`；站点卡深链 `openRoutePage` 改为 `routePicker.pick + tabHeader.select(routeTab,false)`。头部刷新按钮+状态行保留，三 tab 共用。
- **论坛页（QLForumPage）**：侧栏 tab 帖子列表（SVG.FORMAT_LIST_BULLETED）+ 底部工具条“刷新”（SVG.REFRESH，高 64）；`setLeft(sideBar, toolBar)`；详情仍走 contentPane 切换（listShown 守卫不变），点侧栏“帖子列表”即回列表。
- **i18n**：新增 `qinglan.forum.posts` ×3（Post List / 帖子列表 / 帖子列表）。
- **教训**：大段 replace 搬运时被移走的旧方法（card/clickable、Mode、planPickers/planActions）在别处仍被引用，漏搬即编译错；今后大重构拆小步，搬走前先 grep 引用点。
- 门禁全绿；exe 14239747 B + jar 13785603 B（15:24:20）投递 F:\mc，无旧实例运行。### 续17 追加4：倒置英语（en-Qabs）缺字调查（15:47，结论存档，用户拍板暂不改码）
- **现象**：语言选择列表“英语 (颠倒)”一项的本地名串出现豆腐块；映射文件 `HMCLCore/src/main/resources/assets/lang/upside_down.txt` 输出的目标码位多为生僻块（U+15FA/1614 加拿大音节字、U+1D37/1D1A 语音扩展、U+2C6F 扩展拉丁-C、U+A780/81 扩展拉丁-D、U+2132/2141 字形符号、U+27D8 数学、U+03DB/1F49 希腊、U+3123/3125 注音）。
- **本机实测（JavaFX 21.0.8 + DirectWrite，探针程序截图验证）**：
  1. 物理字体 family 下缺字 → **不存在逐字形系统回退**（A∩ 渲染、ᗺ ㄣ 全豆腐）；
  2. `-fx-font-family` 逗号列表 **不支持**（整串当单个 family 名，解析失败回退 System）；
  3. `Font.loadFont` 注册的字体 **不进回退链**（loadFont Gadugi 后 Tahoma 渲染 ᗺ 仍豆腐）；
  4. 逻辑字体 "System" 只对注音等已定义文字块回退，生僻块一样豆腐。
  5. 系统字体覆盖盘点：ᗺ/ᘔ 仅 Gadugi；⟘ 仅 Cambria/Segoe UI Symbol；ꞁ/ᵷ/ᴚ/Ɐ Calibri/Cambria/Segoe UI；ㄣ DengXian 系——无单一字体全覆盖。
- **候选方案及弃置理由**：整窗 180° 旋转（效果最完整但改动面大）；内置合体字体+激活时整体换 family（物理字体无回退→动态中文内容豆腐、语言行混排仍两难）；仅修语言行 per-node 字体（切到该语言后界面其余仍豆腐）。**用户拍板维持现状**，本轮不改代码、不交付。
- **将来重启的入口点**：`Translator_en_Qabs.translate`（映射+倒序）、buildSrc `CreateLocaleNamesResourceBundle`（本地名生成）、`StyleSheets.getFontStyleSheet`（data-URI CSS，单 family）、`FontManager`（font.ttf/HMCL_FONT/env 加载链）、设置页 `LineSelectButton<SupportedLocale>`（语言行渲染）、映射表自身。### 续17 追加5：论坛文章页原生表格 + 图片加载（17:10）
- **用户报障**：整合包更新日志（整合包v26.8.x）的表格不显示（`<table>/<th>/<td>` 被 BLOCK 正则剥掉只剩文字）；随后又指出部分文章（如"方圆轨交"）有图片需要加载出来。
- **真实样本核对**（直接打 Halo 内容 API 拉两篇）：表格结构 `<table><colgroup><col style="width:Npx">…<tbody><tr><th colspan=1 colwidth="N"><p>…</p></th>…`（整合包篇含 4 张表：16+4+2+3 行，colgroup/colwidth 提示齐全）；图片形如 `<img src="https://p3-flow-imagex-sign.byteimg.com/…&amp;…">`（外链 HTTPS 图床 + 签名参数，`&amp;` 实体必须解码否则 404）。
- **QLForumPage 改动**：
  - `BLOCK` 正则加入 `table`；`appendBlock` 新增 `case "table" -> tableOf(inner)`。
  - `tableOf`：原生 GridPane 表格——列宽三路取值（colgroup px > 单元格 `colwidth` 属性 > 文字配重估算，CJK 双倍、上限 60）；表头行加粗且分隔线用 `-monet-outline`（粗 2px），正文行分隔线 `-monet-outline-variant`（1px），末行无线；每格 `flowOf` 富文本（链接/加粗/行内代码/图片都活），包进 launcher `card`。
  - `TableRow` record（cells/hints/header）+ `TABLE_ROW/TABLE_CELL/TABLE_COL` 正则 + `parseRow/parseDoubleSafe/textWeight/boldify/card`。
  - **无 thead 表**：整表无 `th` 时首行按表头处理。
  - **图片**：`flowOf` 的 `img` 分支从链接占位改为 `imageNode(src)`——`Image(url, 560, 0, reserveRatio, bgLoading=true)` 后台加载并按宽 560 等比缩放解码（大图内存安全），`progressProperty` 监听完成后把占位链接换成 ImageView；出错则删缓存保留"图片"链接（浏览器兜底）。静态 `IMAGE_CACHE` LRU（LinkedHashMap accessOrder, 40 上限，synchronizedMap）。
  - **顺手修 bug**：`appendBlock` 开头的跳过段原来把"仅含 img 的块"也整个丢弃（这正是方圆轨交图片完全消失的原因），改为只跳 hr/br；`attrOf` 增加 `decodeEntities`（`&amp;` 放最后防二次解码）。
- **验证**：python 复刻同套正则跑两篇真实 HTML——表格行列/colwidth/图片 URL 全命中；门禁全绿；exe 14245627 B + jar 13791483 B（17:09:52）投递 F:\mc，无旧实例运行。
### 续17 追加6：论坛文章图片不显示的真凶＝INLINE_TAG 不匹配 void 标签（17:25:44）
- **用户报告**：追加5 交付后"方圆轨交"图片仍不显示；换测"南铁集团7.4运营调整"同样无声——**连"图片"占位链接都没有**。
- **复抓真实正文对拍**（南铁篇 `019f139a-4a6f-73dc-819a-1d5a359c2dcd`）：图片为 `<figure data-content-type="image" …><img src="https://bee-reg-ab.imagency.cn/e/….png" width="1713px"></figure>`——Halo 正文 img 是**普通未闭合写法（无 `/` 自闭合、无 `</img>` 闭合标签）**。
- **根因**：`INLINE_TAG` 尾部 `(?:/>|>(.*?)</\\1\\s*>)` 两个分支分别要求"自闭合斜杠"或"闭合标签"，void 标签 img/br 两者皆无 → 永不匹配；`<figure>` 又不在 BLOCK 交替里，整段落 GAP → appendBlock("p") → flowOf 走 appendPlain 只剩纯文本 → 图片彻底蒸发。离线 jshell 复现：**旧正则 img 命中=0**。前两处修复（appendBlock 跳过、decodeEntities）是真 bug 但非阻断主因。
- **修复**：INLINE_TAG 尾部加第三个回退分支裸 `>`：`(?:/>|>(.*?)</\\1\\s*>|>)`——group(1) 结构不变（middle=null）；自闭合/配对优先故 `<a>`/`<strong>` 等配对标签行为不变（配对分支先试），a 的 null-middle 分支本就处理。
- **验证（jshell 离线探针，喂真实文章 JSON）**：新正则每份正文拷贝 img 命中 1 ✓（JSON 中正文因 `content.content` 结构出现两份，客户端 Gson 只解析一份，无影响）；figure 落 GAP(194) → img 命中 → src 提取 ✓（运行时真实引号，attrOf 无碍）→ **至少必现"图片"占位链接**；合成回归 `<a>paired</a>`/`<strong>`/`<br>`/`<br/>`/未闭合 `<img …>` 全部 tag/middle 正确。探针目录已清理。
- 门禁全绿 → 交付 F:\mc（exe 14245627 B / jar 13791483 B，17:25:44）。若再不显示则只剩网络层（届时占位链接应可见，点击可开浏览器兜底）。

### 续17 追加7：按外部审查报告（CODE_REVIEW_2026-10-21.md）修复，P0–P1 全部落地（19:32:20 交付第一轮）
- **前情**：`CODE_REVIEW_2026-10-21.md`（外部审查，未跟踪不入库）开列 S1–S8/M1–M20；用户拍板「全部 P0–P3」+「先提交旧工作、按批 commit」。抽查 9 处（S1–S8、M11、M15/S7）行号全准。批次0 先把 1063 行旧工作提交 `5eb4565dc`，顺手修 QL_COLLAB 漂移（L145 ①/⑤ 标已解决、L261 ① 标已解决）。
- **P0-1 `474ab2625`**：S2 重复 index path 补 `{ continue; }`＋`buildFromMrpack` 改包可见做测试缝＋新 `QLManifestToolTest.duplicateIndexPathIsEmittedOnce`；S5 MTR arrivals 缓存键并入 `#maxCountPerPlatform`（QLMtrTest maxCount=10 再发请求）；M11 `message.done`→`message.success`。
- **P0-2 `d81ada0c5`**：S1 probeHttp 重写（真 getResponseCode、4xx 读 errorStream、非 HTTP(S) 拒绝、timeoutMs 覆盖 connect/read、跟随重定向）；S4 pingSlp 加 `MAX_RESPONSE_BYTES=1MB` + 欠流/超界/截断三个坏包测试（本地 HttpServer 200/404/malformed/file: 全测）。
- **P1-1 `9084b9d9d`**：S6 `volatile boolean visible` 守卫状态页 timer 不越页续命；S7 删 openRoutePage 末尾重复 renderRoutePage；S8+M20 adoptInstance 目录查重 + 监听器 pin `getSelectedRepository()` + AtomicBoolean 一次性。
- **P1-2 `9cc4c0da0`**：QLMtrPage 三视图各自代际令牌（stationArrivals/routeTimetable/plan）+ planning 连点保护 + arrivalsInFlight 只拦轮询；station 回调值等比较、route 回调 equals 比较且 header+行合并一次 setAll 防重复卡。
- **P1-3 `cbf4a37a7`**：S3 `QLDirectoryLayout.recover` 顶部落 marker 优先（stale record 警告并改信磁盘），seedize 先 detect 再 recover、种子目标仅在全新目录生效——杜绝「marker=std 的目录被 pfm 种子重戳/混内容」；M3 种子单文件哈希失败按 MISMATCH 容错继续（新 `reconcileForSeedSoftly`），不丢已备结果。新增测试 `recoverKeepsTheStampedMarker` + `seedizeKeepsTheStampedChannel`。
- **教训**：detectActiveChannel(gameDir) 在无 mods 文件夹时返回 null，直接当 recorded 传 recover 会把全新 pfm 目标落到 std——须 `detected != null ? detected : channel`；测试里写 mods 子目录文件前要先 createDirectories。
- 门禁全绿 → **第一轮交付 F:\mc**（exe 14247704 B / jar 13793560 B，19:32:20），无旧实例运行。
- **待做**：P2＝M12/M13/M14（QLSyncPage 按钮恢复、PageAware+URL 清 fetchedManifest、load/save 挪 IO）、M18/M19/M17（QLForumPage listShown 推导+moreRow、内链 scheme 白名单、图片完成摘监听）、M1/M2/M4/M6/M7（envelope 校验收敛、arrivals null 安全、toURIOrNull、RSS 兜底+死代码、permalink startsWith）；P3＝M8（///注释）、M9（测试类 @NotNullByDefault）、M10（System.err→Logger）、§4 轻微项、§5 测试缺口（QLSyncE2E→@Test、QLPinyin 多音字）。

### 续17 追加8（外部审查报告 P2+P3 全部关闭，第二轮交付）

- **前置**：追加7 的六连提交（P0/P1）后，P2 三批 + P3 一批全部完成并提交。
- **P2-1 `0a675c592`（QLSyncPage live sync）**：M12 种子导入链 `whenComplete` 恢复 applyButton；M13 `implements PageAware`——`onPageShown` 调 `refreshInstanceHint()` 且清 `fetchedManifest` 并禁用 apply，URL 编辑监听同效；M14 `loadInstanceStateAsync(Consumer<@Nullable QLSyncState>)`——IO 线程 load、FX 线程回调，`saveManifestUrl` 全程 IO + 失败回落空态，checkUpdate 链式 load→fetch，applyUpdate 完成后的状态重载也下 IO（内层变量改名 `reloadException` 避免遮蔽）。教训：`Task.supplyAsync` 的 lambda 可直接抛受检异常；大段 edit 曾吞掉 refreshInstanceHint——大编辑后必须编译验证。
- **P2-2 `2f6fe38d8`（QLForumPage）**：M18 `listShown()` 派生（`contentPane.getCurrentNode() == listPane`），删字段与双写；`moreRow` 字段 `indexOf` 插拔，非 RSS 分支 `finished = size < PAGE_SIZE`；M19 linkNode 仅 http/https 可点，否则纯文本无手型光标；M17 图片监听器自摘——`imageSettled` helper + 匿名 `ChangeListener` 内 `removeListener(this)`（lambda 自引用编译报「可能尚未初始化变量」，须用匿名类）。
- **P2-3 `d1b6decfd`（core parsers）**：M1 `parse()` envelope 校验（缺 data → `IOException("(code=...)")`）；M2 cars 脏行跳过；M4 `NetworkUtils.toURIOrNull` + `Invalid download URL for ...`；M5 sync javadoc 如实（reports 无中间进度）；M6 RSS permalink 收敛单分支（localhost 特判并入通用逻辑）；M7 `fetchArticleFromPage` 仅 `/archives/`。测试 `rejectsErrorEnvelopesWithoutData`/`skipsMalformedCarEntries`；教训：MTR arrivals 静态缓存键＝站 id+maxCount，测试须用唯一站 id 防串扰。
- **P3-1 `7da840d2b`（评审收尾）**：M8 全量 `///` 注释转换（12 主类 + 三个测试类）并修复批量转换丢缩进问题（按块后首个代码行缩进补齐；`{ @code mods/** }` 类文档通配符文本正确保留）；M9 `QLServerStatusTest`/`QLSyncE2E` 补 `@NotNullByDefault`；M10 `QLManifestTool` 六处 `System.err` → `Logger.LOG`（main catch 与 validate 失败走 `LOG.error`、其余 `LOG.warning`；`System.out` 是 CLI 报表，保留）；§4/§5：QLSyncPage 四处 assert 渐改显式判空（load 失败回落 empty 与 fetch/apply 失败路径一致）、`"unknown"`→`message.unknown`、种子 FileChooser 过滤器→`qinglan.sync.seed.filter`（EN/zh_CN/zh 三语言，nasyon 其他语言本就子集化）；QLSyncE2E 加 `@Test` 入口（`-Dql.syncE2EArgs` 提供真包 fixture 时跑同一场景链，缺件 skip——CI 有执行点但不再强制 fixture）；QLForumPage import 排序、`attrOf` Pattern 按属性名缓存、`decodeEntities` 先解数字实体（十/十六进制）再名实体补 `&apos;`、两处 `#4a90d9` → `-monet-primary`、java.time 收 import；QLConstants.WALL_CLOCK 共享 `HH:mm:ss`（MTR/论坛/状态页三处去重）；QLMtrPage `%06X` → `Locale.ROOT`。QLPinyinTest 新增八断言四用例（重庆多音、生僻与表外字、中英混排）。
- **新一轮教训**：properties 文件是 LF——PowerShell 里用 `[Environment]::NewLine`（CRLF）找锚点会拿到 -1 把文件切坏（已 git 还原重做）；断言前先在 `pinyin.txt` 查表，际→J（南际 NJ 而非 NZ）、重→Z 开头（表序 zhong 先）。
- 门禁：`checkTranslations :HMCLCore:test :HMCL:compileJava :HMCL:makeExecutables` 全绿（284 用例通过）。
- **第二轮交付 F:\mc**：exe 14251538 B / jar 13797394 B（21:07:54）。
- 至此外部审查报告 P0–P3 全部条目关闭，暂无已知未修项。

### 续17 追加9（复审闭环：RSS 兜底 + seed 不可读两侧测试落位）

- **触发**：复审版 `CODE_REVIEW_2026-10-21.md`（21:56，基于 `2a54e4240`）：主缺陷 33/33 已修确认；遗留 3 条轻微 + 2 条测试缺口 + §8 三处漂移。
- **P2（指挥官定级：论坛唯一活路）`174c564c0`**：`QLForum` 增包私有 base 重载（公开方法委托 BASE）→ 新 `QLForumTest` 用本地假 Halo 站覆盖 RSS 兜底列表（绝对链接取路径、裸 host 落 "/" 不下溢、feed 像素剥除、无 title 条目跳过、空 feed、坏 feed 抛 IOException）+ 渲染页文章读取（非 /archives/ 前置拒绝、标题后缀剥离、缺 content 容器抛 IOException）。
- **P3 seed 不可读（同 commit）**：`QLSeedImporterTest.unreconcilableSeedFilesStayUntracked` 走 softly 两个盲点：删除→missing、读拒绝（Windows ACL deny READ_DATA / POSIX 去 R 位，属性读保留让尺寸检查过、摘要阶段炸 IOException）→ mismatch；两者均不 tracked，随后 sync 直接收养（内容本已正确→ unchanged=1）。教训：`Files.isRegularFile` 只需 READ_ATTRIBUTES，deny 只 READ_DATA 才能精确命中摘要分支；平台忽略权限（root）时用「读到失败」探测自保。
- **顺手**：`QLForumPage.PAGE_SIZE` / `QLMtrPage.KEPT_PATHS` 补 `///` 一行（复查点名的最后两个无文档常量）；QL_COLLAB §8 MTR 遗留条目刷新至当前事实（20s 轮询、三 tab 重渲、arrivalsCache 有界化、下拉色点均已落地）。L-c（FX 线程 stat）按复查口径保留为已记录、不改。
- 门禁全绿；**第三轮交付 F:\mc**：exe 14251676 B / jar 13797532 B（22:55:48）。复审遗留至此全部处理完毕。
- **2026-10-21（opencode/GLM）**：复审收尾两 commit（`174c564c0`/`4a1053c3e`，RSS 兜底+seed 不可读测试、§8 刷新、第三轮交付）；另按需求新增 `HMCL.sh`（Linux 启动脚本，仓库根）：定位同目录 `HMCL-*-ql2.jar`→ `JAVA_HOME`/`PATH`/常见 jvm 目录找 java → `exec java -jar`，git 以 x 位入库，已验证 jar 缺失退出与 java 探查分支，副本随 jar 放入 `F:\mc`。
- **2026-10-21（opencode/GLM）**：MTR 页可读性修复（用户冒烟反馈「复选框文字消失」）：根因是启动器背景为用户自选图片，MTR 页内容区全透明，深色主题 `-monet-on-surface` 白字落在亮色区域即不可读（复选框「联网API查询」正压在亮色天空带上）。修复：`QLMtrPage` 根容器垫 `-monet-surface` 不透明表面（三个 tab + 顶栏整体受益）；论坛/状态/同步页同款「浮字」隐患保留待用户反馈再议。门禁全绿，第四轮交付 F:\mc。
- **2026-10-21（续）**：按用户反馈把 MTR 页表面从全不透明 -monet-surface 调成 -monet-surface-transparent-80（HMCL 全主题动态生成的 80% 标准配方），背景隐约可见、白字仍可读。
- **2026-10-22（追加10）**：复审闭环三连修（各一提交）：① size 软降级——shaMatches 对 size<=0 的清单条目跳过长度预检、仅按 SHA-1 证明（b01646853，sizeless 条目不再每次全量重下；boxed Long + validate 硬拒等服务端清单确认干净后再上）；② 部分失败提前 return 前先记 outcome.unchanged（4dc607820，packVersion 保持 null 是故意的：state 未提交时赋值会主动说谎，且 UI 版本号读 state 文件不读 outcome）；③ QLSyncState.save 走 .tmp + ATOMIC_MOVE（无 REPLACE 兜底时回退普通替换），失败清残留；load 对损坏 JSON 容错——改名 .corrupt 留现场后按空状态启动（只少删不乱删）（1c8d6ab60）。驳回并经审查者确认不改：purge 先于下载（desiredKeys 不交、天然安全）、RENAME 不计 unchanged（UI 单独显示）。备注：审查者引用的 429/730/1076 行号系 class 反汇编字节码偏移，非源码行号。
- **2026-10-22（续）**：硬校验收紧（54059695c）：FileEntry.size 改 boxed @Nullable Long；fromJson 校验拒绝缺失/负 size，错误信息带 channel+path 便于定位坏条目；validateChannel 改 void 顺手去掉从未被使用的返回 Map。防拆箱 NPE：QLSyncManager.shaMatches 参数改 @Nullable Long（null/<=0 走 SHA-1，保留软降级护手装 manifest）；QLSeedImporter.reconcileForSeed 对 sizeless 条目明确退化为纯 SHA-1（seed 早于清单校验）。软降级临时测试退役，换代硬校验用例（缺 size、负 size 两例断言错误含 channel/path）。

---

## 12. 发布（GitHub）

- **目标仓库**：`https://github.com/Hansyang1129/QL-HMCL`（已设为 `origin`；`upstream` 远端已删，**上游锁死在 3.16.4，不跟更新**）。
- **首次推送完成**（2026-11-14）：`ql/main` → `origin/main`（`da44566b6...ded83a003`，force 覆盖了建库时的 `Initial commit`），已设 tracking。本机推送要点：`http.sslBackend=openssl`（schannel 报 `SEC_E_NO_CREDENTIALS`）+ 沙箱禁用 GCM（`sh.exe` 无法建信号管道），改用凭据管理器里的 token 走 `http.extraheader` 一次性认证。
- **历史形态（2026-11-14 调整）**：远端 `main` 是**单提交源码快照**（`2de63b96f`「QingLan launcher 3.16.4-ql2 (source snapshot)」），作者只有本项目维护者；上游作者不再出现在提交历史里，改为在 `README.md` 的「来源与署名」显式署名（huangyuhui + HMCL 贡献者 + 上游仓库 + 基线 commit + GPLv3）。
- **本地保留**：完整上游历史在本地分支 `archive/full-history`（89 个自研提交 + 上游 4848 条），上游 3.16.4 基线在 `baseline-3164`（`d03ad38e2`），`upstream` remote 也保留 —— 都不推送，仅用于查来源与 diff。
- **日常开发**：直接在新 `main` 上提交（快照之后只有本项目的提交）。
- **CI 现状**（工作流未改）：`mirror.yml` 的 job 带 `if: github.repository == 'HMCL-dev/HMCL'` 守卫 → fork 上自动 skip；`release.yml` 只有 `workflow_dispatch` + owner 守卫；会跑的是 `gradle.yml`（`./gradlew build`，含 checkstyle/测试/makeExecutables）与 `check-codes.yml`（`checkstyle checkTranslations`）。**两者现在都是绿的**：checkstyle 四个任务全通过（2026-11-14 实跑，清掉 19 处未用 import）。
- **许可**：沿用 GPLv3；根目录 `README.md` 已注明「HMCL 修改版 / 非官方发行版」。
- **公开信息**：提交作者 `tux106 <tux106@qq.com>`；本文档里的本机路径已换成 `<repo>` / `<tmp>` / `<gradle-home>` / `%USERPROFILE%` 占位符。