# 依赖与许可证清单

应用没有网络权限。图片 PDF 写入器及本工程业务源码为本项目实现；1.1.0 新增 PDF 合并使用 PdfBox-Android。项目自身采用何种分发许可由项目所有者决定。本文件不代替各上游项目的完整许可证。

## APK 的直接依赖

| 组件 | 固定版本 | 许可 | 用途 |
| --- | --- | --- | --- |
| Kotlin standard library | 2.1.20 | Apache-2.0 | 语言运行库 |
| Jetpack Compose BOM | 2025.04.01 | Apache-2.0 | 固定 Compose 组件版本；BOM 本身不包含运行代码 |
| Compose UI / foundation / Material3 / material-icons-core | 由 BOM 固定 | Apache-2.0 | 原生界面、布局及图标 |
| AndroidX activity-compose | 1.10.1 | Apache-2.0 | Activity、系统选择器与 Compose 接入 |
| AndroidX core-ktx | 1.16.0 | Apache-2.0 | 安卓兼容及 Kotlin 扩展 |
| AndroidX lifecycle-runtime-compose | 2.9.0 | Apache-2.0 | 生命周期状态观察 |
| AndroidX exifinterface | 1.4.1 | Apache-2.0 | EXIF 方向和拍摄时间 |
| Kotlinx coroutines Android | 1.10.2 | Apache-2.0 | 串行后台管线和取消 |
| PdfBox-Android | 2.0.27.0 | Apache-2.0；内含字体 / CMap 等资源许可 | 读取普通静态 PDF、复制页面及依赖对象；不栅格化合并 |

Android 框架的 `BitmapFactory`、`Bitmap.compress`、`PdfRenderer`、SAF、通知和前台服务使用设备系统提供的 API。未将系统实现拷贝进源码。应用未引入 AGPL / GPL PDF 库或需要商业授权的 PDF SDK。

PdfBox-Android 的完整上游 LICENSE 与 NOTICE 保存在 `app/src/main/assets/licenses/`，随 APK 分发，可从首页“开源许可”查看。依赖声明排除了本版不使用的 Bouncy Castle 传递依赖；加密输入在解密处理前拒绝，不提取附件、不包含 examples 模块。实施时已核对 [Apache PDFBox 安全公告](https://pdfbox.apache.org/security.html)，公告列出的 2026 年附件路径问题仅影响 examples，并不等于核心解析器对任意畸形输入都有资源安全保证；本应用仍执行独立规模、缓存与输出预算。

## 仅构建或测试使用

| 组件 | 固定版本 | 许可 | 分发情况 |
| --- | --- | --- | --- |
| Gradle Wrapper / Gradle | 8.11.1 | Apache-2.0 | Wrapper 在源码；完整 Gradle 不在 APK |
| Android Gradle Plugin | 8.9.2 | Apache-2.0 | 构建工具，不在 APK |
| Kotlin Android/JVM/Compose 编译插件 | 2.1.20 | Apache-2.0 | 编译工具，不在 APK |
| JUnit | 4.13.2 | EPL-1.0 | JVM 测试，不在应用 APK |
| AndroidX Test runner / rules | 1.6.2 / 1.6.1 | Apache-2.0 | 单独的测试 APK |
| AndroidX Test ext:junit | 1.2.1 | Apache-2.0 | 单独的测试 APK |
| pypdf | 宿主环境实际安装版本 | BSD-3-Clause | 可选独立验证工具，不在 APK |
| PyMuPDF / MuPDF | 宿主环境实际安装版本 | AGPL-3.0 或商业许可 | 可选独立验证工具；仅由开发者在宿主安装使用，不在 APK / PDF 引擎中 |

本机 JDK、安卓 SDK、调试工具、Python 和宿主机图像库不是本应用的运行时依赖。正式分发时应按所采用 SDK 和工具的许可条款管理开发环境。

## 上游许可与来源

- [Kotlin 许可](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)
- [Kotlinx coroutines 许可](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)
- [AndroidX 源码与 Apache-2.0 许可](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)
- [AndroidX 发布与组件说明](https://developer.android.com/jetpack/androidx/releases)
- [Gradle 许可](https://github.com/gradle/gradle/blob/master/LICENSE)
- [Android Gradle Plugin 源码](https://android.googlesource.com/platform/tools/base/)
- [JUnit4 许可](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt)
- [pypdf 许可](https://github.com/py-pdf/pypdf/blob/main/LICENSE)
- [PyMuPDF 许可说明](https://pymupdf.readthedocs.io/en/latest/about.html#license-and-copyright)
- [PdfBox-Android 2.0.27.0 LICENSE](https://github.com/TomRoush/PdfBox-Android/blob/v2.0.27.0/LICENSE.txt)
- [PdfBox-Android 2.0.27.0 NOTICE](https://github.com/TomRoush/PdfBox-Android/blob/v2.0.27.0/NOTICE.txt)

通过 `.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath` 可查看当前实际解析的传递依赖。上述表格为直接依赖，不宣称代替每个传递依赖的逐文件通知。若变更依赖、发布 APK 或重新分发宿主验证环境，应复核相应上游许可证及 NOTICE。
