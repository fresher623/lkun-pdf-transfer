# 1.1.0 本地安全审查记录

审查日期：2026-10-28（Asia/Shanghai）。状态：**vivo 告警原因尚未确定；此前暂缓交付后，用户在当日明确要求继续，已恢复源码交付。** 继续交付不代表告警解除、已判定误报或安全审核通过。

用户已提供 2026-10-28 20:39 的两张手机截图，确认 vivo 手机管家 / 超级守护对主应用“l君のpdf转换”明确提示“发现高危病毒”，并称其为“危险行为程序”；同页另有“未经 vivo 人工亲测”的说明。不能将这组证据仅解释为一般未知来源或未经人工验证提示。截图仍未显示具体威胁族、检测引擎或规则信息，本记录不能证明软件没有安全问题，也不能将此次告警判为误报。

## 截图证据更新（2026-10-28 20:39）

以下仅记录与本应用告警有关的可见文字。截图含用户桌面内容，文件留在用户本机，本项目没有复制或提交截图文件。

| 本机截图文件名 | 可见证据 |
| --- | --- |
| `Screenshot_20261028_203931.jpg` | 应用名“l君のpdf转换”，版本 `1.1.0`，大小 `13.51 MB`，来源标为“未知来源”。“超级守护提示您”下显示“发现高危病毒”及“该应用为危险行为程序，可能导致信息泄露和财产损失，建议卸载。”同页另写“此应用未经 vivo 人工亲测，超级守护已管控本次安装”，并提供本次安装授权入口 |
| `Screenshot_20261028_203939.jpg` | “应用风险”对话框写明：手机管家检测到“l君のpdf转换”为恶意应用，可能导致隐私泄露、财产损失等严重后果，请立即处理。另显示“查看风险详情”“立即卸载”“继续使用”入口 |

“发现高危病毒”“危险行为程序”是截图中的实际表述；截图没有给出具体病毒家族、检测引擎、规则编号、检出代码位置或行为轨迹。版本和显示大小与本轮 debug 安装包相符，但截图本身没有包名或哈希，不能单凭显示信息证明 APK 身份，也不能由这些泛化风险说明确定触发原因。

### 最近一次安装记录的边界

最近一次安装请求中，用户明确要求“安装”，主执行流程于 2026-10-28 20:39 使用已核对交付哈希的 `l君のpdf转换-1.1.0-test.apk` 执行 `install -r --user 0`，安装返回 `Success`，启动请求返回 `Status: ok`。设备查询读数为版本 `1.1.0` / code `6`，`firstInstallTime` 与 `lastUpdateTime` **均为 `2026-10-28 20:39:34`**。

这些是本次实际命令和查询结果；不能将 `-r` 参数或安装成功解释为此次已验证旧草稿保留。旧私有数据没有逐项读取比对，安装时间也不构成完整升级链证据；不能据此推断用户是否卸载、何时卸载或卸载原因。此前记录的旧安装时间和较早 APK 比对仍归属当时的核验，不应改写成本次保留数据的证据。

## 1. 本次核验的文件身份

| 对象 | 身份与本地证据 |
| --- | --- |
| 较早一轮已安装主包比对 | `cn.local.imagepdf`，`1.1.0`，versionCode `6`；当时比对确认它与下面的 debug APK 字节一致；最近一次安装的边界见上节 |
| 被比对的 debug APK | `app/build/outputs/apk/debug/app-debug.apk`，13,507,877 字节；SHA-256 `d7acc34c68a3e8b3663a6ce04167e60e0fe2fadaabec2b9db395d373bd74cb20` |
| 本地 release 对照包 | `app/build/outputs/apk/release/app-release.apk`，9,524,091 字节；SHA-256 `cfd77d3f87b93cfa28cd6cfb798c3c48351dbbf4202c6399d6597e3e29fe3e26` |

release 是本次静态对照对象；不能据此声称手机已检测或接受该 release 包。后续重新构建会改变文件身份，应重新记录哈希，不能沿用本表结论。

## 2. 审查方法与范围

本次只读检查了 `app/src/main`、`core/src/main` 业务源码、各构建类型的合并清单、实际 APK 内二进制清单、ZIP 内容及 DEX 类型/方法引用和有关调用点，并用本机 Gradle 缓存中的第三方库字节码核对敏感调用的归属。

静态审查子任务未运行 Gradle、ADB、Git 或修改业务源码；主执行流程另行完成较早一轮已安装 APK 的比对，并尝试只读获取相关风险界面文字，当时没有取得可用界面树，后来由用户提供上述截图。用户要求继续交付后，又明确要求安装，主执行流程完成了上节记录的 20:39 安装。未向外部扫描平台或厂商上传 APK，未关闭手机检测，未执行卸载或清除数据。测试通过与构建成功不作为恶意软件检测结论。

## 3. 权限、组件及调试差异

用 Android SDK `aapt` 读取实际 debug/release APK，确认两者请求的 Android 权限均为：

- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`：转换和保存任务的前台服务。
- `POST_NOTIFICATIONS`：任务进度通知。
- `WAKE_LOCK`：任务执行期间保持 CPU 工作。

另有 AndroidX 合入的本应用 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，保护级别为 `signature`。该名称中的“DYNAMIC_RECEIVER”不代表动态下载或装载代码。

实际主包没有声明 `INTERNET`、安装/删除其他安装包、所有文件访问、悬浮窗、短信、联系人、通话、录音、摄像头、开机自启动等权限。没有辅助功能服务、设备管理员、VPN、APK 安装器或下载管理服务组件。

| 项目 | 实际 debug APK | 实际 release APK |
| --- | --- | --- |
| 调试能力 | `android:debuggable=true` | 未开启 debuggable |
| 主入口 | 导出的 `MainActivity`，仅 MAIN/LAUNCHER 过滤器 | 相同 |
| 转换服务 | `ConversionService`，不导出 | 相同 |
| 文件提供者 | `FileProvider`，不导出，允许临时 URI 授权；路径仅应用私有 `results/` | 相同 |
| 初始化提供者 | AndroidX Startup，不导出 | 相同 |
| ProfileInstallReceiver | 导出，但要求调用方具备 `android.permission.DUMP` | 相同 |
| Compose PreviewActivity | **存在且导出** | **不存在** |

`ProfileInstallReceiver` 的安装动作针对 ART 性能配置文件，不是 APK 安装。清单中它的 `android:permission="android.permission.DUMP"` 是对调用者的限制，不是主包申请了 DUMP 权限。

debug 的额外攻击面是真实差异：AndroidX Compose `PreviewActivity` 接收 `composable` 等 Intent 参数，通过 `ComposableInvoker` 反射查找并调用现有 Composable；其入口会检查应用的 debuggable 标志。启用调试也允许具备系统调试能力的环境接入调试功能。因此 debug 不能与正式 release 作为同等暴露面的包。当前没有 vivo 规则证据说明这就是告警原因。

## 4. 实际 DEX 中存在的敏感 API

不能把“业务源码未写 shell 命令”表述成“APK 没有 shell 字节码”。本次 debug/release 均发现以下库内调用，并核对其归属：

| 实际命中 | 已核验的调用者与用途 | 能得出的结论 |
| --- | --- | --- |
| `Runtime.exec`，两处调用点 | PdfBox-Android/FontBox 的 `WindowsFontDirFinder.getWinDir`；硬编码 `command.com /c echo %windir%` 或 `cmd.exe /c echo %windir%`，用于查询 Windows 字体目录 | **主 APK 确实保留命令执行字节码。** 业务源码未发现主动调用 Windows 查找器；不能仅据 API 名判断恶意性，也不能隐藏这项线索 |
| `Socket`、`SocketImpl` 构造 | AndroidX Core 的 `DatagramSocketWrapper`；使用 `Socket(SocketImpl)` 包装数据报 socket，关联 SDK < 24 的流量标记兼容分支；本应用 minSdk 为 26 | 没有由此发现指定主机/端口建立连接的业务实现 |
| `URL.openStream` | Kotlin 标准库 `TextStreamsKt.readBytes(URL)` 通用扩展；协程 `FastServiceLoader.parse(URL)` | 后者 URL 来自类加载器的 `META-INF/services/` 资源发现；没有据此找到远程下载目标 |
| `WebView.findAddress` | AndroidX `LinkifyCompat.findAddress` | 是地址文本识别兼容代码；未发现业务创建 WebView 或装载网页 |
| `System.loadLibrary` | AndroidX Graphics Path 的 `PathIteratorImpl` 静态初始化，固定库名 `androidx.graphics.path` | 装载随 APK 打包的本地绘图库；不等于下载本地代码 |

FontBox 的 `FontFileFinder.determineDirFinder` 字节码先在 `java.vendor == "The Android Project"` 时选择 Android 字体查找器；Windows 选择分支取决于 `os.name`。本次没有对手机执行该 Windows 分支做动态跟踪。该分支保留的命令执行代码仍是待核查线索；新截图没有给出检出位置或规则，不能将它与 vivo 的此次判定建立确定对应关系。

在主包 DEX 类型/方法表及业务源码中，未发现 `DexClassLoader`、`PathClassLoader`、`InMemoryDexClassLoader` 或 `DexFile.loadDex` 的引用。AndroidX/Kotlin 等库存在普通类加载及反射功能，不能扩大为“完全没有反射/动态行为”的保证。

## 5. APK 内容及第三方依赖

实际 release 包有 2 个 DEX，debug 包有 8 个 DEX。没有发现嵌套 APK、JAR、额外的隐藏 DEX 或 shell/PowerShell/Python 脚本。未发现主包定义 instrumentation 测试类、测试授权提供者或引用 `UiAutomation`。

两个主包各包含 4 个 ABI 的 `libandroidx.graphics.path.so`。它们与本机 `androidx.graphics:graphics-path:1.0.1` 缓存 AAR 对应文件逐字节一致。这证明本地归属一致，不等于已独立验证上游供应链或审计所有 native 指令。

还存在必须单独记录的资源：`DebugProbesKt.bin`，1,733 字节，文件头 `CAFEBABE00000034`，是 Java 8 JVM class 格式。两个主包中它均与缓存 `kotlinx-coroutines-core-jvm:1.10.2` JAR 的同名资源逐字节一致，SHA-256 为 `cd3da36122413f84be484c77cde4a77729f9e8f81a285408d073300c8385ca3a`。未发现业务源码主动装载它；不能据前述 APK/JAR/DEX 检查声称“没有任何额外可执行格式内容”。

当前打包依赖快照的 package 部分为 release 60 项、debug 63 项，归属 AndroidX、Kotlin/coroutines、PdfBox-Android、注解库及 Guava listenablefuture；未在已检查依赖中发现广告、统计、推送或应用更新 SDK。debug 多出的主要是 Compose UI tooling 相关依赖。

本次使用的 PDF 依赖为 `com.tom-roush:pdfbox-android:2.0.27.0`。当前快照和 DEX 类定义中没有打入 Bouncy Castle 实现；PDFBox 保留了对 BC 的类型引用，这与“打入 BC 类实现”不同。项目在进入解密前拒绝加密 PDF。旧 `merge-dependencies.log` 是排除 BC 前的历史日志，不能作为当前 APK 的完整依赖证明。

PDF/图片解析器仍处理不可信文件。现有页数、体积、对象图、内存及临时空间预算有助于限制资源消耗，不能证明所有畸形文档都不会触发漏洞、卡死或资源耗尽。本次未完成所有依赖的漏洞数据库核对、模糊测试或完整供应链验证；项目未发现 Gradle dependency verification 元数据。

## 6. 数据访问与测试组件边界

业务通过系统相册/文档选择结果获得读取权限；PDF 合并输入拷贝到本应用私有目录。另存由系统 CreateDocument 返回目标，分享/外部预览通过 FileProvider 授予所选完整 PDF 的临时只读权限。业务未发现网络请求实现，主包也没有直接联网权限；系统文档提供者或用户选择的分享应用可以独立联网，本次审查不覆盖这些外部应用。

单独的 `.benchmark`/`.test` 测试安装包具有与主包不同的能力。当前测试 APK 中存在 AndroidJUnitRunner、`UiAutomation`/测试 runner 的 shell 与 DexFile 等 API，以及导出的合成文档授权桥。授权桥没有清单级调用者权限限制，但代码校验固定合成文档 authority、四个合成根目录和唯一测试目标包；不是任意文件授权接口。上述能力不在主 debug/release 包中，不能混为主包行为，也不应把测试组件当作正式产品分发。

## 7. Windows Defender 记录的实际含义

本机记录 `build/merge-defender-scan.json` 显示：2026-10-28 20:17:22～20:17:27 完成扫描，`ScanCompleted=true`，匹配当前工作区的检测记录数为 `0`。

这只能说明本次 Windows Defender 本地检查没有产生相应工作区检测记录。它不是 Android 安全认证，不代表 vivo 结论已解除，也不能保证 APK 或第三方依赖没有恶意行为/漏洞。

## 8. 复核证据及下一步

本地可复核入口：

- `app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/file_paths.xml`、`app/build.gradle.kts`。
- `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml` 及对应 release 清单，并对照实际 APK 的 `aapt dump permissions/xmltree` 输出。
- `app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel/debug-artifact-dependencies.xml`。
- `app/build/intermediates/lint_vital_report_lint_model/release/generateReleaseLintVitalReportModel/release-artifact-dependencies.xml`。
- `app/src/androidTest/AndroidManifest.xml` 及 `FolderTestGrantProvider.java`，用于区分测试组件。
- `build/merge-defender-scan.json`。生成的构建产物/日志通常被 Git 忽略，不能假定它们均在仓库内。
- 本机 Gradle 缓存坐标：`com.tom-roush:pdfbox-android:2.0.27.0`、`androidx.core:core:1.16.0`、`androidx.compose.ui:ui-tooling-android:1.8.0`、`androidx.graphics:graphics-path:1.0.1`、`org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2`。

当前已取得上述两张告警截图，不能再写成“只有用户概述”或“尚未取得任何告警原文”。继续识别具体触发原因，仍需截图未展示的检测引擎、具体威胁族或规则信息，以及可能的检出行为明细；现有证据不足以确定根因或判定误报。用户在收到本轮排查说明后，于 2026-10-28 明确要求“别管了继续吧”；据此恢复向其指定 GitHub 仓库的源码交付，保留当前可复现版本和全部证据。未通过改包名、签名、混淆、删库字节码或关闭安全检测来试探消除告警，也未将 APK 提交给外部扫描平台或厂商。

复核材料已整理为 [vivo 检测复核说明](VIVO_REVIEW_REQUEST.md)，仅供用户或维护者核对后提交，本轮未外传安装包或原始截图。
