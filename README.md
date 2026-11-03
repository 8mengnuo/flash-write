# 闪写 FlashWrite

桌面码字软件：Java 21 + OpenJFX 21（WebView）+ Vue 3。
完整编辑模式 + 气泡悬浮模式，支持老板键、词库、多文档、快照寻回、六种格式导出。

## 目录结构

```
├── src/                Java 源码（主程序 / 桥接 / 全局钩子 / 导出器等）
│   ├── FxMain.java         程序入口与启动开关
│   ├── FxApp.java          JavaFX 主类，Java↔JS 桥接
│   ├── Hooks.java          全局键盘 / 鼠标钩子（老板键、手势）
│   ├── Exporter.java       TXT/MD/HTML/DOCX/RTF/PDF 导出
│   └── ...
├── web/                前端界面（Vue 3 单页 + 自测脚本）
├── lib/                第三方依赖（JNativeHook、PDFBox 等）
├── build/              打包配置（launch4j.xml、icon.ico）
├── test/               托盘等测试代码
├── FlashWriteFx.jar    程序主体（须与 lib/、jfx/ 同级）
└── 闪写.exe            Windows 启动器（加载旁边的 jar）
```

## 构建与运行

```sh
# 编译 + 打 jar
javac -encoding UTF-8 --release 21 --module-path jfx \
  --add-modules javafx.web,javafx.controls,javafx.swing \
  -cp lib/jnativehook-2.2.2.jar -d out src/*.java
jar cfm FlashWriteFx.jar build/manifest.txt -C out . -C web .

# 打包 exe（launch4j + rcedit 补中文版本信息）
tools/launch4j/launch4jc.exe build/launch4j.xml
```

直接双击 `闪写.exe` 运行（需 JDK 21 + JavaFX 21 运行环境，或用 jlink 裁剪的 runtime/）。

## 自测

```sh
java -cp "FlashWriteFx.jar;lib/jnativehook-2.2.2.jar" -Dprism.order=sw FxMain --selftest
```

其他开关：`--keytest` / `--bosstest` / `--gesttest` / `--exporttest` / `--fonttest` / `--plugtest` / `--shot*`

## License

见 [LICENSE](LICENSE)。
