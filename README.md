# 闪写 FlashWrite

桌面码字软件：Java 21 + OpenJFX 21（WebView）+ Vue 3。
完整编辑模式 + 气泡悬浮模式，老板键与应用伪装随时藏起来，词库、多文档、快照寻回、六种格式导出一应俱全 —— 摸鱼码字利器。

- **名称**：中文「闪写」，英文 **FlashWrite**（早期误用的 SnapWrite 已全部统一）
- 形态：单体桌面应用，无服务端、无 HTTP、无 Node；Java 与网页通过 JavaFX LiveConnect 直接互调
- 平台：Windows x64（免安装包自带 jlink 运行时，不需要装 JDK）
- 版本：功能批次 **v1.30** · 文档更新 2026-11-09

> 开发细节见 [docs/闪写开发手册.md](docs/闪写开发手册.md)：前后端桥接、打包流程、配置说明与 4 张结构图。

---

## 一、直接上手（零开发环境）

去 [Gitee Releases](https://gitee.com/meng_nuo/flash-write/releases) 取 v1.30 免安装版压缩包，解压到任意文件夹，双击 `闪写.exe` 即可。

```
解压目录/
├─ 闪写.exe            ← 双击这个
├─ 使用说明.txt
├─ app/                 程序本体：FlashWriteFx.jar + lib/（4 个 jar + JNativeHook DLL）
└─ runtime/             自带的迷你 Java 运行时（约 88 MB）
```

- **不要单独把 exe 拷走**：它只是启动器，程序与依赖都在 `app/` 里；`闪写.exe`、`app/`、`runtime/` 必须保持同级。
- 首次运行会在同目录生成 `data/`，存放文档、配置、词库、字体与寻回快照（备份 / 搬家时带走它）。
- 老板键默认 `Ctrl+Shift+Z` 隐藏 / 显示；隐藏后单击右键或快速三连击左键可直接以气泡模式唤回；鼠标手势 `左左右右左左` 同样可切换。

---

## 二、功能

| 分类 | 能力 |
|---|---|
| **写作与文档** | 多文档（新建 / 切换 / 改名 / 删除 / 关联本地文件）、草稿自动保存、字数统计、查找替换、导航跳转、自定义词库与全局快捷键、按文档独立的快照寻回与回收站 |
| **气泡与隐藏** | 气泡悬浮模式（随光标段落自适应、可拉伸、双击回完整界面）、老板键 / 应用伪装 / 托盘、鼠标手势与三连击唤回、置顶 / 透明度 / 开机自启；**托盘左键打开 / 隐藏窗口，右键只弹菜单（显示/隐藏、退出），不会把软件唤出来** |
| **排版与格式** | 正文与 H1–H3 样式、字体字号、加粗 / 斜体 / 下划线、文字色 / 底纹 / 命名标记、格式刷（单击一次、连击批量）、清除格式、段首缩进与对齐；**按钮亮起 = 当前文字带该格式，再点即恢复默认**，工具条与右键菜单一致 |
| **链接与外部网页** | 超链接设置 / 编辑 / 全局管理与「点击直接打开」开关；粘贴网页内容自动归一化（不会在软件里跳转到外部站点）；「最新版本获取」直达 Gitee，收藏夹可拖拽排序，一律用系统默认浏览器打开 |
| **导入导出** | 导入 `docx` / `doc` / `txt`；导出 `TXT` / `Markdown` / `HTML` / `RTF` / `DOCX` / `PDF` |
| **外观与扩展** | 明暗主题、导入字体、壁纸 / 装饰图 / 自定义图标（可提取其它 exe 的图标）、BGM、`data/plugins/*.js` 插件 |

---

## 三、近期更新

| 批次 | 内容 |
|---|---|
| v1.24–v1.25 | jlink 免安装发行包与 `app/` 新布局；开发配置、结构图与说明文档入库；VSCode / IDEA 共用同一根目录 |
| v1.26–v1.27 | 新增「外部网页」与工具条「超链接」、全局管理；粘贴外部链接归一化，链接后输入不再继承链接 |
| v1.28 | 粘贴可选「保持原格式 / 匹配当前格式」；新增格式刷（单击 / 连击批量 / 再点退出） |
| v1.29–v1.30 | 格式按钮二次点击恢复默认（真拆壳，右键同步）；中文斜体生效；格式刷可复制全部格式 |

> `v1.xx` 是功能批次号；exe 右键属性里的文件版本由 `build/launch4j.xml` 的 `<fileVersion>` 单独控制（当前 `1.24.0.0`），两者不是一回事。该配置里的 `ProductName` 已统一为 `FlashWrite`，所以任务栏右键看到的是「闪写 FlashWrite」。

---

## 四、目录结构（开发视角）

源码、配置、文档、jar 与 exe **入库**；`runtime/`、`data/`、`dist/`、`tools/`、`jfx/`、`out/`、`target/`、测试截图与日志一律被 `.gitignore` **忽略**（本机环境、编译产物与用户数据都不进仓库）。

```text
flashwrite-hybrid/                  ← IDEA / VSCode 都打开这一层
├─ src/                    Java 后端：FxMain（入口/CLI）、FxApp（总控与桥接）、
│                          Hooks（全局键鼠）、Exporter（六格式导出）、
│                          VolumeMeter / IconArt / MakeIco                 〔入库〕
├─ web/                    前端：index.html（Vue3 模板+脚本+样式全内联，<style> 在末尾）、
│                          vue3.min.js、selftest.js（前端断言）              〔入库〕
├─ app/                    ★ 运行文件：FlashWriteFx.jar（class + 前端资源）、lib/ 依赖 〔入库〕
├─ build/                  launch4j.xml、manifest.txt、icon.ico（打包三件套）   〔入库 3 个〕
├─ build.ps1               ★ 唯一构建入口（PowerShell，源码保持纯 ASCII）      〔入库〕
├─ docs/                   开发手册 + 4 张结构图                               〔入库〕
├─ .vscode/ .idea/ 闪写.iml  VSCode 与 IDEA 共用根目录的「按钮」配置            〔入库〕
├─ 闪写.exe                ★ launch4j 启动器（dontWrapJar，读 app/ 里最新的 jar）〔入库〕
├─ jfx/  tools/  out/  target/  JavaFX jar、JDK/launch4j/rcedit、编译产物      〔忽略〕
├─ runtime/  dist/  data/   jlink 运行时、发行目录、用户数据                   〔忽略〕
└─ ui_*.png  shot*.log      测试截图与日志                                     〔忽略〕
```

免安装版压缩包在项目**外层目录**生成，本身也不入库。

**为什么是 jar + lib + exe 三段**：exe 只是不内嵌 jar 的启动器；`app/FlashWriteFx.jar` 装自己写的 class 与网页；`app/lib/` 放第三方 jar（JNativeHook、PDFBox、FontBox、commons-logging）；`runtime/` 是 jlink 裁出的 Java 运行环境。重打 jar 后 exe 不用换即可生效（但交付前仍按流程重打 exe）。

---

## 五、开发环境与构建

需要：JDK 21、JavaFX 21（`jfx/`）、launch4j + rcedit（`tools/`）。VSCode 或 IDEA **直接打开本根目录**即可，两套配置互不覆盖。

```powershell
.\build.ps1 -JarOnly      # 只改了前端：增量编译 + 重打 jar（最快）
.\build.ps1               # 完整构建：整组 javac + jar + exe + rcedit 中文版本信息
.\build.ps1 -AllTests     # 完整构建 + 全量回归
.\build.ps1 -SelfTest     # 跑 selftest
.\build.ps1 -Shot         # 出截图
```

VSCode 里 `Ctrl+Alt+J / B / T` 分别对应 JarOnly / 完整构建 / 全量测试；IDEA 用 `.idea/runConfigurations` 里现成的三个运行配置。

手动六步（脚本坏了时）：

```sh
# 1. 整组编译（单文件 javac 必炸：FxApp 引用 FxMain 字段）
javac -encoding UTF-8 --release 21 --module-path jfx \
  --add-modules javafx.controls,javafx.media,javafx.swing,javafx.web \
  -cp "app/lib/*" -d out src/*.java

# 2. 打 jar（class 与 web 资源进同一个包）
jar cfm app/FlashWriteFx.jar build/manifest.txt -C out . -C web .

# 3~6. launch4j 生成 exe → rcedit 补中文版本信息与图标
tools/launch4j/launch4jc.exe build/launch4j.xml
```

三条打包铁律：新增第三方 jar 时 **`build/manifest.txt` 与 `build/launch4j.xml` 两处 Class-Path 都要加**；`build.ps1` 必须纯 ASCII（PS 5.1 是 GB2312，中文只从 XML 读）；改 launch4j 临时配置只能替换 `outfile` / `icon` 两行文本，别用 `XmlDocument.Save()`（会把空的 `<maxVersion>` 改坏）。

---

## 六、回归自测

```sh
java -cp "app/FlashWriteFx.jar;app/lib/*" -Dprism.order=sw FxMain --selftest
```

| 开关 | 用途 |
|---|---|
| `--selftest` | 前端全量断言（当前基线 **315 PASS / 0 FAIL**） |
| `--bosstest` | 老板键显示 / 隐藏（`SHOW_OK`） |
| `--keytest` / `--gesttest` | 键位录制与鼠标手势（含 260ms 慢手势回归） |
| `--traytest` | 托盘左右键分工（右键不唤回、左键能打开、普通右键仍能唤回） |
| `--exporttest` / `--fonttest` / `--plugtest` / `--dragtest` | 六格式导出、字体库、插件、拖拽 |
| `--shot*` | 各界面截图 |

自测与截图一律跑在 `data/selftest` 隔离数据根，不会碰真实草稿与寻回历史。免安装包交付前还需用**包内** `runtime/bin/java` 复跑 `--selftest` / `--bosstest` / `--gesttest` / `--traytest`，确认零开发环境也能起来 —— 其中手势测试能验出 jlink 运行时是否漏了 `java.logging`（漏了会让老板键和全局快捷键在免安装版里失效）。

---

## 七、架构速览

```text
WebView (Vue3 单页)  ──window.host('命令', 参数)──▶  FxApp.Bridge.js()  ──▶ 文件 / 钩子 / 导出 / 窗口
        ▲                                                    │
        └──────── executeScript / 桥接方法返回值 ◀─────────────┘
```

Java 回调里**不能反向 `executeScript` 通知前端**（重入会静默失败），数据一律当桥接方法的返回值交给前端落字。详见 [开发手册第 1 章](docs/闪写开发手册.md) 与 [docs/bridge.svg](docs/bridge.svg)。

---

## License

见 [LICENSE](LICENSE)。
