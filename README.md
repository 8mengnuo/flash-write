# 闪写 FlashWrite

桌面码字软件：Java 21 + OpenJFX 21（WebView）+ Vue 3。
完整编辑模式 + 气泡悬浮模式，支持老板键、应用伪装、词库、多文档、快照寻回、六种格式导出，摸鱼码字利器。

## 目录结构

```
flashwrite-hybrid/                  ← IDEA / VSCode 都打开这一层，51 个文件入库
├─ src/          7 个 Java 文件（FxApp 是后端主体，3000+ 行）        〔入库〕
├─ web/          index.html（Vue+CSS+JS 全内联）· vue3.min.js · selftest.js 〔入库〕
├─ build.ps1     唯一构建入口                                      〔入库〕
├─ build/        manifest.txt · launch4j.xml · icon.ico（其余是日志）〔入库 3 个〕
├─ .vscode/ .idea/ 闪写.iml   IDE 静态配置                        〔入库〕
├─ docs/         闪写开发手册.md + 4 张 SVG                        〔入库〕
├─ README.md · LICENSE · .gitignore · test/                        〔入库〕
├─ 闪写.exe      启动器（路径编译在资源段）                        〔入库〕
├─ app/
│   ├─ FlashWriteFx.jar   程序本体（class + 前端页面）             〔入库〕
│   └─ lib/               4 个第三方 jar + JNativeHook DLL        〔入库〕
├─ runtime/      89MB 自带 JVM（含 JavaFX）                        〔忽略〕
├─ data/         用户文档 / 配置 / 寻回历史（严禁入库）            〔忽略〕
├─ dist/         免安装发行：闪写.exe + app/ + runtime/           〔忽略〕
├─ tools/ jfx/   JDK21+launch4j+rcedit / JavaFX jar（体积大）      〔忽略〕
└─ out/ target/ ui_*.png shot*.log   编译与测试临时产物            〔忽略〕

```

## License

见 [LICENSE](LICENSE)。
