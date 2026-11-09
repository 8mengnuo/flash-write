# 闪写 SnapWrite / FlashWrite

一款为「专心码字」设计的 Windows 桌面写作软件：**完整编辑界面 + 气泡悬浮模式**，可一键藏进老板键，也能把文字随手格式成稿。

- 技术栈：Java 21 + OpenJFX 21（`WebView` / WebKit）+ Vue 3（单文件前端）
- 形态：单体桌面应用，无服务端、无 HTTP、无 Node；Java 与网页通过 JavaFX LiveConnect 直接互调
- 平台：Windows x64（免安装包自带 jlink 运行时，不需要装 JDK）
- 文档更新：2026-11-09 · 当前功能批次 **v1.30**

> 想了解前后端桥接、打包流程与每一份配置文件的作用，见 [docs/闪写开发手册.md](docs/闪写开发手册.md)（含 4 张结构图）。

---

## 一、直接上手（零开发环境）

去 [Gitee Releases](https://gitee.com/meng_nuo/flash-write/releases) 取《闪写 SnapWrite v1.30 免安装版.zip》，解压到任意文件夹，双击 `闪写.exe` 即可。

```
解压目录/
├─ 闪写.exe            ← 双击这个
├─ 使用说明.txt
├─ app/                 程序本体：FlashWriteFx.jar + lib/（4 个 jar + JNativeHook DLL）
└─ runtime/             自带的迷你 Java 运行时（约 88 MB）
```

- **不要单独把 exe 拷走**：它只是启动器，程序与依赖都在 `app/` 里；`闪写.exe`、`app/`、`runtime/` 必须保持同级。
- 首次运行会在同目录生成 `data/`，存放文档、配置、词库、字体与寻回快照（备份/搬家时带走它）。
- 老板键默认 `Ctrl+Shift+Z` 隐藏 / 显示；隐藏后单击右键或快速三连击左键可直接以气泡模式唤回；鼠标手势 `左左右右左左` 同样可切换。

---

## 二、主要功能

| 分类 | 能力 |
|---|---|
| **写作** | 多文档（新建 / 切换 / 改名 / 删除 / 关联本地文件）、草稿自动保存、字数统计、查找替换、导航面板（标题与命名标记跳转）、自定义词库（编码 + `Tab` / 前缀最长匹配）、全局快捷键录制 |
| **气泡与隐藏** | 气泡模式按「光标所在段落」自适应宽高、边缘拖动改尺寸、双击顶部回完整界面；老板键 / 托盘 / 开机自启 / 窗口置顶 / 透明度；隐藏时右键或三连击唤回；慢手势与三连击互不抢跑 |
| **排版** | 正文与 H1–H3 四套可编辑样式、字体字号（**只对选中文字生效**）、加粗 / 斜体 / 下划线 / 文字色 / 底纹与命名标记、段首缩进、左中右对齐、清除格式、导航跳转；格式按钮与右键菜单统一遵循「**亮起 = 当前文字带该格式，再点 = 恢复默认**」 |
| **中文斜体** | WebView 只给西文合成斜体，因此含汉字的斜体壳改用 `inline-block + skewX(-12deg) + text-indent:0`，中文也能真正倾斜且不产生段首空白 |
| **格式刷** | 选中源文字后单击 = 把**全部**格式（B/I/U、颜色、底纹、标记、超链接属性、中文斜体）刷给下一段选中文字；320ms 内连击两次 = 批量模式可反复刷；再点一次退出，全程无提示 |
| **粘贴** | 从网页等外部粘进来的 HTML 会先归一化（外部 `<a href>` 一律转成 `span.txtlink` + `data-href`，绝不让 WebView 在应用内导航），再给「保持原格式 / 匹配当前格式」两条路 |
| **超链接** | 工具条「超链接」左键设置 / 编辑当前链接，右键打开**全局管理**（改目标、增删改、点击是否直接跳转）；正文右键按光标自动分「编辑当前 / 添加」；链接后继续输入会自动脱离链接壳 |
| **外部网页** | 标题栏「最新版本获取」直达 Gitee Releases，右键可添加自己的网址、拖拽调整收藏夹顺序；全部走系统默认浏览器 / 资源管理器，不在软件里开网页 |
| **导入导出** | 导入 `docx` / `doc` / `txt`；导出 `TXT` / `Markdown` / `HTML` / `RTF` / `DOCX` / `PDF`（PDF 中文子集内嵌字体、缺字形自动降级不炸整篇） |
| **寻回** | 快照按文档独立存放，小改动节流合并，按时间分层抽稀保留（10 分钟逐条 → 更早每周），删除的文档进回收站可恢复 |
| **外观与扩展** | 明暗主题、导入字体（自动换算 CSS 族名）、壁纸 / 装饰图 / 自定义窗口图标（可提取其它 exe 的图标）、BGM、`data/plugins/*.js` 插件 |

---

## 三、近期版本记录

| 批次 | 主要内容 |
|---|---|
| v1.24 | jlink 免安装发行包（exe + `app/` + `runtime/` 新布局）；慢手势与右键唤回不再抢跑；气泡回归「整段入框」两态 |
| v1.25 | `<style>` 移到页面底部；前后端桥接 / 后端功能 / 打包 / 配置四份说明文档与结构图入库；VSCode、IDEA 共用同一根目录的开发配置 |
| v1.26 | 新增「外部网页」：Gitee 最新版入口 + 自定义收藏夹、拖拽排序、系统默认浏览器打开；标题栏按钮顺序可配 |
| v1.27 | 工具条「超链接」与全局管理、点击直接跳转开关；粘贴外部 `<a>` 归一化（四道闸防止应用内导航退不回）；正文右键编辑 / 添加链接 |
| v1.28 | 链接后输入不再继承链接；粘贴提供「保持原格式 / 匹配当前格式」；底纹右侧新增格式刷（单击 / 连击批量 / 再点退出） |
| v1.29 | B / I / U / 文字色 / 底纹统一真拆壳 toggle，右键菜单同步；修 WebKit `intersectsNode` 与 `extractContents` 克隆祖先壳导致「清除没反应」 |
| v1.30 | 中文斜体真正生效（`text-indent:0` 防 32px 空白，附像素断言）；格式刷改为扫描整段选区收集全部格式壳再克隆，不再丢内层格式 |

> 说明：`v1.xx` 是**功能批次号**；exe 右键属性里的文件版本由 `build/launch4j.xml` 的 `<fileVersion>` 单独控制（当前 `1.24.0.0`），两者不是一回事。

---

## 四、目录结构（开发视角）

```text
flashwrite-hybrid/
├─ src/                    Java 后端：FxMain（入口/CLI）、FxApp（总控与桥接）、
│                          Hooks（全局键鼠）、Exporter（六格式导出）、
│                          VolumeMeter / IconArt / MakeIco
├─ web/                    前端：index.html（Vue3 模板+脚本+样式全内联，<style> 在末尾）、
│                          vue3.min.js、selftest.js（前端断言）
├─ app/                    ★ 运行文件：FlashWriteFx.jar（class + 前端资源）、lib/ 依赖
├─ build/                  launch4j.xml、manifest.txt、icon.ico（打包三件套）
├─ build.ps1               ★ 唯一构建入口（PowerShell，源码保持纯 ASCII）
├─ docs/                   开发手册 + bridge / backend-tree / build-pipeline / config-layers 四张 SVG
├─ .vscode/ .idea/ 闪写.iml  VSCode 与 IDEA 共用根目录的「按钮」配置
├─ jfx/  tools/  out/       JavaFX jar、JDK/launch4j/rcedit、编译产物（均不入库）
├─ runtime/  dist/  data/   jlink 运行时、发行目录、用户数据（均不入库）
├─ 闪写.exe                ★ launch4j 启动器（dontWrapJar，运行时读 app/ 里最新的 jar）
└─ 闪写 SnapWrite vX.XX 免安装版.zip  ← 发行包在项目**外层目录**生成，不入库
```

**为什么是 jar + lib + exe 三段**：exe 只是不内嵌 jar 的启动器；`app/FlashWriteFx.jar` 装自己写的 class 与网页；`app/lib/` 放第三方 jar（JNativeHook、PDFBox、FontBox、commons-logging）；`runtime/` 是 jlink 裁出的 Java 运行环境。重打 jar 后 exe 不用换即可生效（但交付前仍按流程重打 exe）。

---

## 五、开发环境与构建

需要：JDK 21、JavaFX 21（`jfx/`）、launch4j + rcedit（`tools/`）。VSCode 或 IDEA **直接打开本根目录**即可，两套配置互不覆盖。

日常用 `build.ps1`（PowerShell，在项目根目录执行）：

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
| `--exporttest` / `--fonttest` / `--plugtest` / `--dragtest` | 六格式导出、字体库、插件、拖拽 |
| `--shot*` | 各界面截图（气泡、链接、粘贴卡片、格式刷、斜体等） |

自测与截图一律跑在 `data/selftest` 隔离数据根，不会碰真实草稿与寻回历史。免安装包交付前还需用 `runtime/bin/java` 复跑一次 `--selftest` / `--bosstest`，确认零开发环境也能起来。

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
