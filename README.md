# QL-HMCL · 轻蓝启动器

> 基于 [HMCL](https://github.com/HMCL-dev/HMCL) 3.16.4 的定制启动器，为「轻蓝 MTR 2.0」整合包社区服务。
> 本仓库是 **3.16.4 冻结分支**，不跟随上游更新；品牌中文名为**轻蓝**（繁中「輕藍」，罗马字 Qinglan）。

## 这是什么

原版 HMCL 的整合包分发需要整包下载（约 1.7 GB）。这里换掉了这条链路：服务端发布一份 manifest，客户端只拉**差异**（新增/变更的文件），删文件只删自己记录过的、用户手放的文件永不触碰。围绕它还有一整条社区向的界面。

## 主要功能

| 功能 | 说明 |
|---|---|
| 增量同步 | manifest 驱动；`std` 官方包 / `pfm` 优化包双频道，切频道只改目录名；可选 mod 组按 `*.jar.disabled` 保留；SHA-1 对账；每 20s 自动刷新 |
| 种子导入 | 直接吃 FULL / LITE `.mrpack`：解包 overrides → 建频道目录 → 首次同步补齐缺口 |
| 轻蓝同步页 | 查更新、切频道、勾选可选 mod、离线可用的清单地址 |
| 论坛页 | `qlbbs.ota8a.cn` 的 Halo content API，原生控件渲染文章（富文本、表格、图片）；接口挂掉自动切 RSS |
| 服务器状态页 | SLP 握手 + MTR Web / reporter 的 HTTP 探测，进页即探、30s 轮询 |
| MTR 轨交页 | 车站时刻表（按站台 / 按线路）、线路时刻表、本地 Dijkstra 路径规划（最多 5 条方案） |
| 站名搜索 | 中文 / 罗马字 / **全拼** / **首字母** 四种输入都能命中（`观塘`、`guantang`、`gt` 同一结果；多音字并入 Unihan `kHanyuPinlu`/`kHanyuPinyin`） |
| 无实例引导 | 「寻找实例」页：群文件导包，或选目录后自动认领轻蓝实例 |
| 离线优先 | 自更新与更新检查在本构建中关闭 |

## 构建

需要 **JDK 21**（源码 `target 17`），Gradle 由 wrapper 提供。

```bash
./gradlew :HMCL:shadowJar        # 可执行 jar
./gradlew :HMCL:makeExecutables  # 追加 .exe / .sh / .deb 外壳
```

产物在 `HMCL/build/libs/HMCL-3.16.4-ql2.{jar,exe,sh}`（同目录附 `.sha1/.sha256/.sha512`）。

质量门禁：

```bash
./gradlew :HMCLCore:test                 # 核心测试（含 QL 70 余用例）
./gradlew :HMCL:checkTranslations        # i18n 键对齐检查
./gradlew :HMCL:compileJava              # 两模块编译
```

## 运行

- Windows：双击 `HMCL-3.16.4-ql2.exe`，或 `java -jar HMCL-3.16.4-ql2.jar`
- Linux / macOS：`java -jar HMCL-3.16.4-ql2.jar`；Linux 也可用仓库根的 `HMCL.sh`（自动找同目录 jar 与 java）

## 文档

- [QL_COLLAB.md](QL_COLLAB.md) — **唯一权威状态文档**：模块地图、踩过的坑、命令手册、工作日志（接手前先读）
- [AGENTS.md](AGENTS.md) — Java 风格约定（`@NotNullByDefault`、显式 `@Nullable`、`///` 文档、GPL 头）

## 数据来源

- MTR 地图 API：`play.simpfun.cn:14614`（`stations-and-routes` / `departures` / `arrivals`，均匿名）
- 论坛：`qlbbs.ota8a.cn`（Halo content API + RSS 兜底）
- 状态页：`play.simpfun.cn:32650`（SLP）、`:14614`、`:19451`

## 许可

沿用上游 **GPLv3**（见 [LICENSE](LICENSE)）：本项目为 HMCL 的修改版，改动与新增文件见提交历史，版权头保留。
**本仓库不是 HMCL 官方发行版**，与 HMCL-dev 无关；遇到问题请提本仓库的 issue。

> 备注：微软登录的 client id 兜底值沿用 HMCL 官方 Azure 应用，若长期分发建议自行注册应用并通过 `MICROSOFT_AUTH_ID` 环境变量注入。

## 部署配置

仓库是**空壳**：不包含任何服务器地址。需要把 [`ql-endpoints.example.json`](ql-endpoints.example.json) 复制到启动器配置目录（Windows 为 `%APPDATA%\.hmcl\ql-endpoints.json`）并填写：

```json
{
  "forumBase": "",
  "mtrBase": "",
  "manifestBaseUrl": "",
  "status": [],
  "servers": []
}
```

留空的项对应页面显示「配置未就绪」，不会向任何地址发起请求。

## 来源与署名

本项目是 **Hello Minecraft! Launcher（HMCL）** 的修改版，遵循 GPLv3 分享：

- **原作者**：huangyuhui（黄雨晖）`<huanghongxun2008@126.com>` 及 HMCL 贡献者
- **上游仓库**：<https://github.com/HMCL-dev/HMCL>
- **基线版本**：`3.16.4`（上游 commit `2dccd8099`，2026-07-13）
- **许可**：GPLv3（见 [LICENSE](LICENSE)），所有文件的版权头均原样保留
- **说明**：本仓库的提交历史是一份**源码快照**，不包含上游的逐条提交历史；上游代码的著作权归原作者与 HMCL 贡献者所有。本项目新增/修改的部分（`org.jackhuang.hmcl.ql` 及各处 UI 改动）由本项目维护者完成，同样以 GPLv3 授权。

本仓库与 HMCL 官方发行版无关，不代表上游立场。


