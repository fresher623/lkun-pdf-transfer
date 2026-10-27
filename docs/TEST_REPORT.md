# 测试报告

更新日期：2026-10-27（Asia/Shanghai）。以下保留 2026-10-26 已执行记录；新增结果按实际版本区分。需求基线：文件夹内《安卓图片合并 PDF App 需求文档》V1.0。

## 本轮 1.0.3 更名安装与回归验证

正式名称为 **`l君のpdf转换`**（小写 `l`、日文 `の`），主应用包名 `cn.local.imagepdf`，版本 **1.0.3 / versionCode 4**。本轮实际结果见 `build/release-1.0.3-verification.json`：

| 检查 | 本轮实际结果 | 证据与范围 |
| --- | --- | --- |
| 构建与静态检查 | 构建成功，1m 47s；lint 0 errors、8 条 KTX 建议 | `rename-1.0.3-build.log`；19 项核心原测试为 up-to-date，不称本轮重新执行 19 项 |
| 主应用安装与启动 | 安装返回 Success，设备确认 1.0.3 / code 4，可启动 | `device-install-1.0.3.log`、验证 JSON；桌面标签、首页标题和批量导入入口确认可见 |
| 安装文件一致性 | 已安装 `base.apk` 与构建 APK 的 SHA-256 完全相同，10,632,344 bytes | `ecf042041f4b7ea4b9a52f913e82de0a37df85d983cfcba9de5cf611c8f0f8f7` |
| 文件夹回归 | **4 通过、0 失败、0 跳过，6.231 s**；实际全选并导入 500 项 | `device-folder-1.0.3-test.log`；使用隔离 benchmark 和预授权合成夹具，不修改主应用草稿 |
| 12MP 三轮长测 | 本轮未重跑 | 319.554 / 208.408 / 130.215 s 仍是下文 **1.0.2 历史结果**，没有改写为 1.0.3 新测量 |

安装前未检测到设备主包，因此本轮不能称为已验证 1.0.2 → 1.0.3 的升级或数据迁移。6.231 秒是文件夹控件与微小合成图片导入回归耗时，不是 500 张 12MP 转换耗时；真实系统目录 / SAF、4 GB 设备、真实相机组、熄屏后台和连续真实峰值等限制仍保留。

以下是 1.0.2 及更早版本的历史报告，保留当时的名称、版本、成功、失败与中断数据；其中“当前”按该历史记录当时的版本理解，本轮交付版本以本节 1.0.3 结果为准。

## 1.0.2 及更早历史报告

当前交付实现为 **1.0.2（versionCode 3）**，主入口通过系统选择文件夹（`OpenDocumentTree`）后打开应用内图片选择页，提供固定位置的“全选 / 取消全选”和批量导入；不要求用户在系统 Photo Picker 中逐张勾选 500 张。主应用及测试变体已构建，lint 为 0 错误、8 条 KTX 建议。**主应用已安装成功，dumpsys 确认 1.0.2 / code 3；最终文件夹套件四项实际全部通过，包含真实点击“全选 → 导入已选 500 张”。**本轮用的是预授权合成目录和微小 PNG，验证选择控件与独立导入管线，不等于个人目录的系统授权或 500 张 12MP 转换验收。

最新 **1.0.2 合成 12MP 长测已完整完成三轮 500 张**，耗时分别为 **319,554 / 208,408 / 130,215 ms**，每份 500 页、153,891,616 bytes。首轮超过五分钟，不能宣称性能门槛全部通过；测试期间观察到系统冻结和 CPU 停顿，后续使用测试应用前台辅助，但没有精确量化或扣除冻结时间，也不把超时全部归因于冻结。最终留存 PDF 的 500 页条码全序、横向 A4、严格解析及独立渲染通过，SHA-256 与已独立校验文件匹配。**这些是高内存设备、合成 JPEG、直接转换管线及前台辅助条件下的结果；真实相机样本、4 GB 设备、实际系统目录 / SAF 授权、熄屏后台和连续真实峰值仍待验证，本项目尚未达到完整 V1 验收通过。**

先前 1.0.1 的六项功能测试、单批导入回归、100 / 300 张压力结果，以及第 326 页被系统一键清理强停的中断记录全部保留。较早重试没有 JUnit 最终结果，不以最新成功覆盖历史中断，也没有证据把当时强停称为应用 OOM。

## 1. 环境与构建记录

| 项目 | 实际信息 |
| --- | --- |
| 宿主 | Windows，工作目录 `E:\PDF转化` |
| 宿主 JVM | Oracle Java 21.0.6；应用 / core 字节码目标 Java 17 |
| 最新工程版本 | 主应用 `cn.local.imagepdf`，1.0.2，versionCode 3；历史成功六项功能与首次压力数值来自 1.0.1(2) |
| 隔离测试包 | `cn.local.imagepdf.benchmark`，应用名“纸间 · 测试”；与主应用数据分离，避免修改现有用户草稿 |
| 构建工具 | Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20 |
| 安卓配置 | minSdk 26、compileSdk / targetSdk 35 |
| 设备 | vivo V2505A、Android 16；USB 曾断开，2026-10-27 用户恢复后已重连并取得可见性配置轮次日志 |
| 设备总 RAM 读数 | 15,578,528 kB；不是需求中的 4 GB 低内存基准机 |
| 设备应用堆上限 | 本次 benchmark 运行报告为 268,435,456 bytes（256 MiB） |
| APK 签名 | debug / benchmark 为调试签名的测试构建，不是正式发布签名 |

已确认的构建结果：

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `:core:test` | 通过，19 项，失败 0、错误 0、跳过 0 | `core/build/test-results/test/TEST-cn.local.imagepdf.core.CoreTest.xml` |
| `:core:engineProbe` | 生成 100、300、500 页 PDF 和原始宿主指标 | `build/engine-probe/host-metrics.json` |
| `:app:assembleDebug` | 最新 1.0.2 APK 已生成，主应用已成功覆盖安装 | `folder-final-build.log`、`app/build/outputs/apk/debug/app-debug.apk`、设备 dumpsys 版本确认 |
| `:app:assembleBenchmark` | 1.0.2 测试应用已生成；仅 benchmark 包可见性配置已安装并执行测试，但没有解决授权失败 | `benchmark-visibility-build.log`（SUCCESS，1 s）、`device-folder-visible-test.log` |
| `:app:assembleDebugAndroidTest` | 先前版本测试 APK 已生成；不表示最新版本已执行测试 | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| `:app:assembleBenchmarkAndroidTest` | 最终 BFS 选择器测试 APK 515,234 bytes 已成功安装并运行四项通过 | `device-folder-bfs-test.log`；较早授权桥及广播失败记录分轮保留 |
| `:app:lintDebug` | 最新主应用构建通过：0 errors、8 warnings | `folder-final-build.log`（SUCCESS，1m 3s）、`app/build/reports/lint-results-debug.html` |
| 先前 USB 安装 | 首次拒绝后，用户允许，旧包与测试包安装成功 | 旧包真实设备快测已取得结果 |
| 应用 USB 安装 | 旧隔离包曾通过非流式安装成功；本日主应用 1.0.2 已由用户允许覆盖安装成功，未执行卸载或清数据 | 主应用 dumpsys 确认 versionCode 3、versionName 1.0.2；读取主应用私有状态失败，未实测其现存图片数量 |
| 旧包 `PipelineInstrumentedTest` | 通过：4 tests；JUnit 报告总耗时 0.205 s | `device-quick-test.log` 中 `OK (4 tests)`，4 项状态均成功 |
| 旧包扩展功能测试 | 实际 4 项通过、1 项 Assume 跳过；不是 5 项全部通过 | `device-functional-test.log`，AppFlow 的 `INSTRUMENTATION_STATUS_CODE: -4` |
| 1.0.1 六项功能测试 | **6 项通过、0 失败、0 跳过**；总耗时 187.622 s | `device-functional-isolated-test.log`，Pipeline 4 + AppFlow 1 + BatchImport 1，完成状态均为 0；不代替 1.0.2 回归 |
| 1.0.1 单独批量导入回归 | **1 项通过、0 失败、0 跳过**；总耗时 1.838 s | `device-batch-import-test.log`，单次 500 / 501 URI 导入边界 |
| 1.0.2 Kotlin 文件夹夹具首轮 | **0 通过、3 失败、1 Assume 跳过**；总耗时 0.44 s | `device-folder-test-first-attempt.log`；三项在测试 Tree URI 授权前置失败，实际全选按钮用例因已有草稿跳过；后续版本结果另列 |
| 1.0.2 Java 广播授权版本 | **0 通过、4 失败、0 跳过** | `device-folder-broadcast-attempt.log`、该次 `device-folder-verification-test.log`；授权前置回执为 0 / null，未完成目录授权，不代表生产应用或后续桥方案的结果 |
| 1.0.2 同步授权桥版本 | **0 通过、4 失败、0 跳过**；总耗时 0.019 s | `device-folder-bridge-test.log`；组件安装成功后实际执行，四项均为 `Unknown authority cn.local.imagepdf.benchmark.test.folder-test-grants`，现场 dumpsys 确认该 authority 正确注册，不把它称作字符串拼错 |
| 仅 benchmark 包可见性候选修复 | **0 通过、4 失败、0 跳过**；总耗时 0.029 s | `device-folder-visible-test.log`；安装后实际执行，四项仍为相同 Unknown authority，未将候选修复记为成功；生产主应用权限未改变 |
| 四个合成目录临时预授权轮次 | **3 通过、1 失败、0 跳过** | `device-folder-pregranted-logcat.log`，设备 PID 32297 最终 `run finished: 4 tests, 1 failed, 0 ignored`；UI 失败为 `UiAutomation.connect` 的 `DeadObjectException`。host 仪器日志仅保存开头，不凭开头统计成功 |
| 设备端持久运行首轮 | **3 通过、1 失败、0 跳过**；总耗时 107.023 s | `device-folder-detached-test.log`；授权和三个元信息用例通过，UI 在首次点击全选前等待可点击控件超时 15 s，不把它误称为按钮已点击或导入成功 |
| 最终 BFS 控件选择器轮次 | **4 通过、0 失败、0 跳过**；总耗时 4.488 s | `device-folder-bfs-test.log`；四项均状态 0，含实际全选 / 导入 500、ID / 顺序 / 有效性和初始运行时状态保护断言；此耗时不属于 12MP 转换 |
| 首次 100 / 300 / 500 压力尝试 | 1.0.1 的 100、300 张完成，首轮 500 张被系统一键清理强停 | `device-performance-test.log`、`device-exit-info.log`、`build/device-evidence/` 原始数据保留 |
| 最终 500 张连续三次长测 | **完整完成，JUnit 1 test 通过**；三轮各 500 页、各 500 行采样；首轮超过五分钟，性能时间门槛未全部通过 | `device-performance-final-test.log`（总耗时 1,062.493 s）、`build/device-evidence-final/device-summary.json` 和原始 CSV |
| 最终真机 PDF 独立校验 | **500 / 500 页顺序条码正确，逐页横向 A4，严格解析 / 独立渲染通过** | `build/device-evidence-final/independent-validation.json`、`validated-pdf.sha256`；对应最终留存 PDF，不能扩写为所有历史输出逐页都已独立检查 |
| 完整 UI / SAF / 前台服务流程 | 真实前台服务及协调器集成部分通过；完整系统界面仍待测 | AppFlow 直接调用运行时及受控 URI，不操作系统选择器或外部 SAF 保存界面 |

最新主应用统一构建检查在 `folder-final-build.log` 记录 `BUILD SUCCESSFUL in 1m 3s`；Java 广播夹具在 `folder-verification-build.log` 记录 `BUILD SUCCESSFUL in 8s`，同步授权桥在 `test-evidence-build.log` 记录 `BUILD SUCCESSFUL in 10s`，仅 benchmark 可见性配置在 `benchmark-visibility-build.log` 记录 `BUILD SUCCESSFUL in 1s`。先前记录保留于 `folder-build.log`、`device-build.log` 和 `build-check.log`。EXIF 受限 API、Compose Activity 获取方式及备份配置问题已修复并复查；8 项 lint 警告均为 KTX 扩展写法建议。首轮 Kotlin 测试夹具的 `toTypedArray` 泛型推断警告也随 Java 改写消除，不再将旧警告算作最终编译结果。先前交付 APK 已通过 apksigner v2 签名验证；aapt2 核对没有 INTERNET、READ_MEDIA_IMAGES 或广泛存储权限。

## 1.1 1.0.2 应用内全选的本轮验证状态

`OpenDocumentTree` 只负责授权目录，500 张图片的选择移到应用内进行。`FolderRepositoryInstrumentedTest` 四项分别针对目录 500 项完整枚举、目录 / 非图片过滤和稳定自然排序、超 10,000 项明确失败及空目录、实际点击“全选 → 导入已选 500 张”。最终 `device-folder-bfs-test.log` 取得四项全部通过、无跳过；下面保留每轮失败及执行条件，避免把此前的编译成功或安装成功误当作测试通过。

2026-10-27 首轮 `device-folder-test.log` 显示：三项均在测试夹具的 Tree URI 授权前置断言处失败（`expected -1 but was 0`），未取得有效目录授权；实际全选按钮用例因隔离应用仍有草稿或结果而 Assume 跳过（状态 `-4`）。准确统计是 3 失败、1 跳过，没有 1 项通过。

设备 logcat 定位了 Kotlin 夹具首轮失败原因：独立测试 APK 的 `FolderTestGrantReceiver` 进程加载 Kotlin 类时出现 `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`；AGP 没有把与目标应用重复的 stdlib 再装入测试 APK，独立 receiver 进程无法加载它。该问题属于独立测试夹具，不能直接归因为生产应用目录枚举错误。改为 Java 后的广播版本后来安装成功，但四项均因授权前置返回 0 / null 而失败，且没有新的 receiver 日志或 AndroidRuntime 崩溃证据，不能把旧 Kotlin 崩溃继续当作这次四项失败的原因。

用户继续后，14:54 测试组件曾安装成功，但 runner 当时找不到 `cn.local.imagepdf.benchmark` 目标包；随后补装目标包后，才取得上述 Java 广播版本四项失败结果。这里不推测目标包此前为何不存在。旧 benchmark 160 张图片和一份结果仅是先前读取的历史状态，不代表补装后的当前数量，也没有据此声称此次状态保护比对通过；主应用数量未取到。

最新夹具已移除广播 receiver，改用纯 Java `ContentProvider.call` 同步授予桥，在夹具 owner 进程使用 `Binder.clearCallingIdentity`，严格限制合成目录与目标包。它只改变测试授权路径，未改生产应用。编译在 10 秒内通过，514,570 bytes 的更新 APK 起初被拒绝；用户再次允许后已安装并运行，`device-folder-bridge-test.log` 最终为四项全部失败、没有跳过，错误均为 `IllegalArgumentException: Unknown authority cn.local.imagepdf.benchmark.test.folder-test-grants`。失败发生在取得夹具授权桥之前，不把它归因为生产应用目录枚举，也不把旧广播轮次的结果混到同步桥轮次。

现场 dumpsys 确认错误中这个完整 authority 确实注册，不能把异常简单解释为名称拼错或未声明 provider。候选修复是在 **benchmark 专属 manifest** 中增加对固定测试包的 `<queries>` 可见性声明，完成 1 秒构建并由 aapt2 核对包名；它不改变生产主应用权限。用户暂停后恢复，手机重新连接，包含此配置的 benchmark APK 安装后实际重跑四项，`device-folder-visible-test.log` 最终为四项全部失败、没有跳过，总耗时 0.029 秒，错误仍是 `Unknown authority cn.local.imagepdf.benchmark.test.folder-test-grants`。因此单独增加这个可见性声明在本轮没有解决问题，不能将它记为已证实根因或成功修复。

UI 验证方案为临时显示生产 FolderPicker，以独立 ImageRepository 导入合成图片并断言运行时状态前后相等。早先多轮在授权或 UiAutomation 连接阶段失败；最终更换测试控件查找方式、保持设备端 runner 后，这套真实点击和保护断言均已通过。主应用生产代码没有因测试夹具调整而变更。

### 合成目录预授权后的进一步结果

为隔离跨 APK 夹具授权的启动问题，仅通过 ADB shell 调用这个测试 APK 自有 GrantProvider，向 `.benchmark` 授予 `five-hundred`、`mixed`、`too-many`、`empty` 四个受控合成根的非持久 prefix 读取权限；四次返回 `success=true / detail=ok`。这证明该 provider 能启动且同步调用可执行；它属于测试夹具 setup，不涉及用户目录、系统权限设置或生产应用权限，也不证明真实系统文件选择器授权流程已经验收。测试应用还通过普通 MAIN / LAUNCHER Intent 启动，没有改变手机系统设置。

这一轮宿主 `device-folder-pregranted-test.log` 因 ADB 前台仪器 watcher 提前断开而只有开头，不能把缺少最终 host 输出误作整套未运行或成功。保存的设备日志 `device-folder-pregranted-logcat.log` 记录 PID 32297 最终完成四项：**3 通过、1 失败、0 ignored**。三个元信息用例（完整列出 500 项、不受剩余导入名额截断；目录和非图片过滤及同名稳定自然排序；超 10,000 项明确失败和空目录）通过。UI 用例失败堆栈为 `Instrumentation.getUiAutomation → UiAutomation.connectWithTimeout → IUiAutomationConnection.connect` 的 `DeadObjectException`，尚未成功点击“全选”，不把它写作生产按钮逻辑失败或通过。

后续改为设备端持久运行 `toybox nohup am instrument`，写受控临时日志 `/data/local/tmp/imagepdf-folder-detached.log`，减少 ADB shell watcher 生命周期对 UiAutomation Binder 的影响。这一轮 `device-folder-detached-test.log` 最终为 3 通过、1 失败，耗时 107.023 秒；UI 不是 DeadObjectException，而是在第一次点击全选前等待 15 秒超时，日志没有“clicked actual 全选”阶段。没有把它算为全选或导入成功。

### 最终四项通过的条件与范围

最终只在测试代码中把文字查找改为有上限的 BFS 遍历无障碍节点，同时匹配文本或内容描述，并严格限制只能点击本测试应用所属的可见节点。更新的测试 APK 为 515,234 bytes，实际安装成功。继续使用四个合成根的非持久 prefix 预授权和设备端 `toybox nohup am instrument`；开始约两秒后用标准 MAIN / LAUNCHER Intent 将测试应用带到前台（flags `0x30000000`），没有改变手机设置或读取用户目录。

`device-folder-bfs-test.log` 最终为 **`Time: 4.488`、`OK (4 tests)`**，四项测试结束状态全部为 0，没有失败或 Assume 跳过。UI 日志明确记录 `activity launched`、`content mounted`、`clicked actual 全选 button` 和 `actual 全选 and 导入 buttons imported all 500 fixture images`。实际断言包括 500 项导入无异常、内部 ID 唯一、名称和原始顺序准确，以及运行时初始 `images` / `result` 前后布尔相等。

这里每个合成文档由同一个微小 1×1 PNG 夹具提供像素；500 项有不同的文档 URI 和名称。测试临时挂载生产 FolderPicker，经实际按钮操作选出 500 URI，再由独立 ImageRepository 导入，避免向用户现有任务追加图片。因此 **4.488 秒只代表这套控件和微小图片夹具测试，绝不是 500 张 12MP 转换耗时**。这也没有验证用户个人目录的系统选择 / 授权界面、普通应用首次跨 provider 自动获取授权，或新的 500 张连续三次性能门槛。

安装期间先后出现新验证包、benchmark 更新和单独测试 APK 被阻止的记录，返回码包含 `INSTALL_FAILED_ABORTED: User rejected permissions`。现场已核对 vivo 安装器的“超级守护”提示：应用未经 vivo 人工亲测，需要用户对本次安装手动授权；授权后组件实际安装成功。因此不能仅凭这个字面返回码推断是用户主动拒绝，也不把系统等待或阻止写成代码崩溃。此前失败轮次分别为 Kotlin 首轮 3 失败 / 1 跳过、Java 广播轮次 4 失败、同步桥轮次 4 失败、可见性配置轮次 4 失败；最终成功轮次另列。没有把安装成功本身当作测试通过；主应用更新、夹具失败、USB 断开和最终实际四项通过分别保留证据。

另有 `device-performance-retry-test.log`，目前日志只到 300 张组的 250/300 进度，未出现 JUnit 最终结果。没有把它当作完整重试成功，也没有用重试覆盖首次中断的原始证据。

## 2. 已执行的核心与宿主检查

JVM 的 19 项测试覆盖：毫米 / point 换算、A4 / A5 / Letter / 按图片比例、自动及固定方向、完整显示 / 铺满裁切、极端长宽比、独立页码区域、自然排序和同值稳定性、中文及非法文件名、1～500 张边界、质量档实际参数、真实 JPEG 页对象、xref 偏移、未完成文档和取消不写 trailer、额外页及错误输入拒绝。

Windows 中文工作目录下曾遇到 Java / Kotlin 目标版本不一致及 Gradle 测试 worker 类路径编码问题，已修正并复跑取得上述 19 项通过结果；未通过忽略测试来绕过。

先前版本 benchmark 管线快测四项通过，之后最新隔离 benchmark 1.0.1 的六项运行中再次通过这四项。两者均为可调试、接近 release 的测试签名构建；最新运行与用户主应用数据分离。管线断言包括：

- 四角颜色样本在 EXIF 1～8 方向与用户 0° / 90° / 180° / 270° 旋转的 32 个组合下，预览像素位置断言正确；该测试直接设置 `ImageEntry.exifOrientation`，不代表已验证全部真实相机 EXIF 文件解析。
- 透明 PNG 预览的透明区域为白色、非透明区域保色；生成一页中文文件名 PDF，实际由 Android `PdfRenderer` 打开，页数和自动横向方向正确。
- 第一页完成后抛出协程取消异常，确认转换没有保留 `.pdf`、`.partial` 或单页 JPEG；这验证清理路径，不等于已测取消按钮、保存中取消或最坏响应时间。
- 损坏图片及 GIF 都保留异常条目，GIF 原因明确，条目 ID 不同且原始 URI 保留。

首次仪器运行发现初始化方法返回类型不符合 JUnit 要求，已显式声明 `Unit` 并重新构建；上述四项结果来自修复后的实际执行，这一次四项管线测试没有跳过。快测直接访问应用私有合成源，不访问个人相册，也未覆盖 Photo Picker、真实 SAF 提供方和系统后台行为。

之后加入 AppFlow 的旧包五项测试运行在 `device-functional-test.log` 末尾显示 `OK (5 tests)`，但 AppFlow 返回 Assume 跳过状态 `-4`，原因是主应用已有用户草稿，测试主动保护用户数据。因此该次历史记录准确统计为 **4 项通过、1 项跳过**，不能写为五项全部通过。最新 benchmark 增加 `.benchmark` 包名后缀和独立应用名后，完整六项已实际执行通过，没有 Assume 跳过。

已执行的 1.0.1 AppFlow 在应用自有 Content URI 上实际验证了：导入和重复提示、自然排序、删除及旋转、A5 / 省空间 / 页码配置、前台服务生成、重复点击防重与配置顺序冻结、真实 PDF 页数和最终方向、Activity 重建后状态保持、取消另存保留结果、导出到受控私有 `file://` 目标后逐字节 SHA-256 相同，以及新建任务仍保留已生成私有 PDF。该导出不是系统 SAF 保存对话框或第三方文档提供方验收。

最新 BatchImport 用一次包含 **500 个 Content URI** 的选择响应调用导入，确认 500 项无异常、内部 ID 唯一、原始顺序准确，旋转末项不改变顺序；另一次提交 501 项时，确认导入前等待显式确认、取消保留空状态，确认后仅纳入允许的前 500 项。该用例在六项套件中通过，随后独立重跑再通过；样本为应用自有的 320×200 JPEG，它证明导入响应的单批数量与边界逻辑，不等于已手动操作真实系统文件选择器勾选 500 张。

宿主样本为可重复生成的、每页带唯一编号及图案的 **900×1200 合成 RGB JPEG**；没有私人图片，也不是 4000×3000 的 12MP 安卓样本。Probe 在同一宿主 JVM 中依次运行，最大堆设置为 128 MiB，每页生成并编码 JPEG 后流式写入。耗时包含宿主 AWT 绘制和 ImageIO 默认 JPEG 编码；布局为 A4、10 mm 边距、页码开启。它不验证安卓三档编码质量差异或采样解码成本。首次运行包含宿主类加载 / 编码初始化影响，后两组存在热运行影响。

以下数值逐字节取自 `build/engine-probe/host-metrics.json`：

| 页数 | 宿主耗时 | PDF 实际字节数 | 按页采样的 Java 已用堆峰值 |
| --- | --- | --- | --- |
| 100 | 3,036 ms | 3,654,524 | 128,322,008 bytes |
| 300 | 9,098 ms | 10,908,557 | 100,278,344 bytes |
| 500 | 14,794 ms | 18,215,793 | 99,051,784 bytes |

500 页组在第 100 / 200 / 300 / 400 / 500 页记录的已用 Java 堆分别为 79,128,840 / 79,145,352 / 79,155,672 / 79,165,992 / 79,176,312 bytes。该组检查点没有出现与 JPEG 文档大小同量级的逐页增长，但这些离散值受 GC 影响，不能作为 Android Java 堆低于 70%、原生内存恒定或无泄漏的充分证据。未测宿主原生分配或 RSS / PSS，也未取得安卓内存曲线。

独立检查由 `scripts/verify_pdf.py` 执行，结果文件为 `build/engine-probe/independent-validation.json`，三组均无失败：

| PDF | 严格解析页数 | 每页唯一 JPEG 数量 | MuPDF 实际渲染页 |
| --- | --- | --- | --- |
| `host-100.pdf` | 100 | 100 | 1、51、100 |
| `host-300.pdf` | 300 | 300 | 1、151、300 |
| `host-500.pdf` | 500 | 500 | 1、251、500 |

逐页检验了 A4 MediaBox（595.27559 × 841.88976 point）、递增页码和 JPEG 图像对象；pypdf 使用严格模式，MuPDF 未修复文档即可打开并渲染。PNG 在 `build/engine-probe/previews/`；已目视检查 500 页文档末页，图像编号和页码均为 500。其余渲染成功不等于已逐页目视审阅全部图片；当前有 Android `PdfRenderer` 的单页实际打开记录，但没有两种安卓阅读器的打开记录。

## 2.1 首次真机 12MP 压力尝试：保留中断记录

此轮使用 **1.0.1(2)、benchmark / debuggable=true、合成样本版本 2**，设备为 vivo V2505A / API 36，RAM 为 15,952,412,672 bytes，应用堆上限为 268,435,456 bytes。样本是 500 张 4000×3000、含确定性纹理、编号与九位条码的实际编码 JPEG；配置为标准质量（150 DPI、JPEG 85）、A4 自动方向、10 mm 边距。源文件生成耗时不计入转换耗时；源 I/O 缓存为热或未知，不声称冷缓存。该测试直接调用 `ImageConverter`，不代表系统选择器、前台服务或熄屏流程的性能。

原始证据保存在 `build/device-evidence/`，包含 summary、逐页 measurement、渲染抽查 PNG、未完成 PDF 及外部内存 JSONL；`device-summary.json` 为这些文件的只读汇总。后续重试应使用独立的 `build/device-evidence-retry/`，不覆盖这次中断记录。

| 规模 / 轮次 | 状态 | 转换耗时 | 完整 PDF 字节数 | 逐页采样最大 Java 已用堆 | 逐页采样最大 Native 分配 | 逐页采样最大 PSS / RSS |
| --- | --- | --- | --- | --- | --- | --- |
| 100 / 第 1 次转换调用 | 完成，Android PdfRenderer 页数和首中末标记断言通过 | 7,515 ms | 30,683,585 | 6,788,376 bytes | 134,370,832 bytes | 233,700 / 327,092 KiB |
| 300 / 第 2 次转换调用 | 完成，Android PdfRenderer 页数和首中末标记断言通过 | 21,760 ms | 92,231,902 | 7,702,760 bytes | 106,653,600 bytes | 205,336 / 315,296 KiB |
| 500 / 第 1 轮 | **中断，未生成完整结果** | 不填完成耗时；最后采样为第 326 页、25,452 ms | 不填完整文件大小；最后临时文件记录为 100,220,522 bytes | 不作为完整 500 张峰值 | 不作为完整 500 张峰值 | 不作为完整 500 张峰值 |
| 500 / 第 2、3 轮 | 未执行 | — | — | — | — | — |

内部内存指标在每页写入及位图回收后采样，会漏掉解码和编码中的瞬时峰值。已完成两组的采样 Java 堆分别约占上限 2.53% / 2.87%，**不能据此宣称全程峰值已低于 70% 门槛**。原生分配和 RSS/PSS 必须同时保留，不用较小的 Java 数值替代总进程内存。

外部约一秒 `dumpsys meminfo` 记录共 249 份，其中 166 份取得有效应用内存数据。整段监测含启动和源样本生成，未与单轮精确对齐；采样最大 TOTAL PSS 为 211,549 KiB、TOTAL RSS 为 358,428 KiB、Dalvik HeapAlloc 为 4,835 KiB、Native HeapAlloc 为 101,433 KiB。这些与逐页采样口径和时点不同，不应相互替换或称为连续真实峰值。

中断时仪器日志返回 `shortMsg=Process crashed`；独立的系统退出记录显示压力测试进程 PID 28012 为 `reason=10 (USER REQUESTED)`、`subreason=21 (FORCE STOP)`，描述为 `stop cn.local.imagepdf.benchmark due to single-cleaner`，退出记录 RSS 为 325 MB。可确认是系统一键清理引起的强停，没有 Java OOM 的证据；这次事件不能作为应用 500 张稳定性通过的证据。100 / 300 张完成结果保留为有效局部测量；当时欠缺的三轮 500 张已在后续独立目录重测，结果见 2.2，不覆盖此次中断。

## 2.2 最终 1.0.2 合成 12MP 三轮 500 张结果

此轮为同一台 vivo V2505A / Android 16（API 36），benchmark 1.0.2(3)、debuggable=true，应用堆上限 268,435,456 bytes（256 MiB），设备 RAM 15,952,412,672 bytes。使用固定的 sample version 2：500 张含纹理、编号与九位条码的实际编码 **4000×3000 合成 JPEG**，标准质量 150 DPI / JPEG 85，A4 自动方向、10 mm 边距。不是空白 PDF 页，也不是私人照片；仍需另补真实相机样本及低内存设备。

证据独立存放在 `build/device-evidence-final/`，没有覆盖首次中断目录或未完成的较早重试目录。`device-performance-final-test.log` 记录 `OK (1 test)`，长测用例内部完整执行三轮；JUnit 套件总耗时 **1,062.493 s** 包含源样本生成、检查和证据镜像等，不是单轮转换耗时。每轮下表耗时由转换调用单独测量，包含调用期间的实际墙钟停顿，排除源样本生成和完成后证据镜像复制，不扣除系统冻结时间；源 I/O 缓存为热或未知，不声称冷缓存。

| 500 张轮次 | 实测转换耗时 | 页数 / 实际 PDF 字节数 | 逐页采样最大 Java 已用堆 | 逐页采样最大 Native 分配 | 逐页采样最大 PSS / RSS | 五分钟目标 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **319,554 ms（5 分 19.554 秒）** | 500 / 153,891,616 | 7,405,352 bytes | 133,083,920 bytes | 210,895 / 346,056 KiB | **未通过** |
| 2 | 208,408 ms（3 分 28.408 秒） | 500 / 153,891,616 | 7,202,792 bytes | 84,615,424 bytes | 139,253 / 294,560 KiB | 本轮低于五分钟 |
| 3 | 130,215 ms（2 分 10.215 秒） | 500 / 153,891,616 | 7,355,168 bytes | 84,764,160 bytes | 140,890 / 292,668 KiB | 本轮低于五分钟 |

三个原始 measurement CSV 均有按顺序的第 1～500 页记录，汇总值已与 CSV 复核。Java 采样最大值分别约为堆上限的 2.76% / 2.68% / 2.74%；这些值不代表总进程占用，位图、原生分配及 RSS/PSS 另列。三个轮次在第 100 / 300 / 500 页的 Java 已用堆分别为：

| 轮次 | 第 100 页 | 第 300 页 | 第 500 页 |
| --- | --- | --- | --- |
| 1 | 5,593,960 bytes | 5,856,312 bytes | 5,087,408 bytes |
| 2 | 5,858,624 bytes | 4,941,280 bytes | 5,557,200 bytes |
| 3 | 5,877,672 bytes | 5,147,424 bytes | 5,405,240 bytes |

这些检查点和采样最大值未显示与文档体积同量级的逐页内存累积，但内部采样发生在页面写入和位图回收后，会漏掉解码 / 编码瞬间；不能据此声称全程连续峰值已经低于 70% 门槛。完整曲线和 Native / RSS / PSS 检查点保留在 CSV / `device-summary.json`。旧版 100 / 300 张与本轮三次 500 张不在完全相同的前台辅助条件下，不能伪装成同一条件下的规模对比认证。

外部 `dumpsys meminfo` 共 **1,066 份快照，1,058 份有效**，覆盖约 1,069 秒，包含启动、源样本生成、转换和辅助前台阶段。整段采样最大 TOTAL PSS 为 **169,441 KiB**、TOTAL RSS 为 **327,660 KiB**、Dalvik HeapAlloc 为 **5,286 KiB**、Native HeapAlloc 为 **107,293 KiB**。外部约一秒采样与逐页采样的时点和口径不同，不能合并成一个连续峰值，也不把较小的外部值替代逐页较大值。

测试期间外部记录明确出现 `APP ... is frozen`，同时观察到 CPU 停顿。普通 MAIN / LAUNCHER Intent 将测试应用带回前台后可继续工作；后续期间使用 `watch_foreground.py` 约每十秒投递本测试应用的普通前台 Intent，完成后通过 stop-file 停止观察和辅助。没有改系统电源、锁屏或安全设置。**首轮实测超过五分钟的事实不改变；没有精确量化冻结占用时间，不能把所有超时都归因于冻结，也不能推算扣除冻结后的达标时间。**后两轮采用前台辅助条件，不能当作无辅助后台执行或熄屏转换通过。

每轮在 Android `PdfRenderer` 中验证页数为 500，并抽查首、中、末页标记。最终留存的 `files/results/external-latest/latest.pdf` 另由 pypdf 严格解析、MuPDF 独立渲染，检查 **500 / 500 页九位条码按 1～500 排列**，每页为横向 A4，文档无需修复；首 / 中 / 末渲染的颜色标记也正确。最终文件 SHA-256 为 `36a220df697cb5402d0e9f8846e12fe1da821c0f55e8702b6b20d5c7a9940e5f`，与独立校验文件和此前已校验首轮 PDF 的 hash 相同。第二轮有 Android 页数 / 首中末断言，未将其扩写为另一次独立 500 页全序检查。

本轮证明这台高内存手机在上述合成样本及前台辅助条件下，转换管线完成三次 500 张，并取得最终 PDF 的逐页独立正确性证据；不把它扩写为全机型稳定性、真实相机照片、4 GB 设备、系统 SAF、生产前台服务的熄屏后台或完整 V1 通过。

## 3. 功能验收 F01～F10

“宿主部分通过”只表示该行列出的 JVM / PDF 检查有执行证据；“实现待设备”表示存在相应实现或测试代码，尚未在安卓设备执行。

| ID | 场景 | 当前状态及证据 / 待执行项 |
| --- | --- | --- |
| F01 | 0、1、499、500、501 张边界 | **真机单批导入与全选控件部分通过**。1.0.1 的 500 URI 导入及 501 显式确认通过；1.0.2 在预授权合成目录上完整列出 500 项、实际点击全选和导入 500 项且 ID / 顺序正确通过。真实系统目录授权与真实相机图片的整条批量流程仍待验证。 |
| F02 | 多批导入、重复、取消选择 | **真机协调器和全选控件部分通过**。1.0.1 AppFlow 重复项、唯一 ID 与提示通过；501 取消后状态保留。1.0.2 的真实全选 / 导入控件与独立管线通过，原运行时图片和结果保持相等；实际系统目录选择和取消仍待测。 |
| F03 | 拖动、自然排序、同名、删除 | **真机协调器及目录排序部分通过，手势待测**。1.0.1 AppFlow 的自然排序、删除、旋转、快照冻结通过；1.0.2 在预授权合成目录上的 1 / 2 / 10 排序、同名稳定顺序和目录 / 非图片过滤通过。网格手势拖动及同名实图输出顺序仍待验证。 |
| F04 | EXIF 八方向 × 用户旋转 | **真机管线部分通过**。四角颜色样本 8 × 4 组合实际像素断言通过；真实相机 EXIF 标签读取与混合图册仍待验证。 |
| F05 | 页面、方向、边距、页码 | **宿主及真机部分通过**。全部几何设置单测通过；AppFlow 在 A5、5 mm、省空间和页码开启下生成三页，第一张旋转后为纵向、第二张为横向，状态与设置不被重复点击修改。全部安卓设置组合及精确尺寸独立核对仍待测。 |
| F06 | 透明 PNG、长图、小图、混排 | **真机管线部分通过**。透明白底及一页 PDF 打开已实际通过，极端比例几何单测通过；长图实际解码、HEIF / WebP 设备分支和混排仍待测。 |
| F07 | 三档质量 | **实现待设备**。DPI / JPEG 参数常量测试通过，已接入采样及编码；尚无真实设备三档实际体积和画质对照，不能把常量测试视为质量验收通过。 |
| F08 | 中文、空名、非法字符、同名 | **宿主部分通过，SAF 待设备**。名称清理与统一扩展名测试通过；系统另存的重名处理、中文目标与不可覆盖行为待验证。 |
| F09 | 取消另存、重试、分享 | **真机协调器部分通过**。取消另存保留完整 PDF，再导出到受控私有 file URI 后大小与 SHA-256 相同，新建仍保留私有结果；实际 SAF 保存对话框、提供方失败和第三方分享接收应用尚未验证。 |
| F10 | 独立 PDF 验证 | **宿主和最终真机 500 页独立校验通过**。最终留存 PDF 逐页检查 500 / 500 顺序条码及横向 A4，pypdf 严格解析、MuPDF 首中末渲染通过，无修复且 SHA 匹配；三轮有 Android 页数 / 首中末断言。至少两种独立安卓阅读器的手动打开记录仍待补。 |

## 4. 稳定性验收 S01～S10

| ID | 场景 | 当前状态及待执行项 |
| --- | --- | --- |
| S01 | 500 张 12MP 连续三次 | **合成样本三轮完成，性能及发布门槛未全部通过**。最终三轮各 500 页，耗时 319,554 / 208,408 / 130,215 ms，首轮超过五分钟；测试期间观察到冻结并使用前台辅助。真实相机样本、4 GB 设备和完整 ANR / 后台验证仍待补；历史 single-cleaner 中断不覆盖删除。 |
| S02 | 100 / 300 / 500 内存曲线 | **真机采样已取得，完整门槛仍待验证**。保留旧版 100 / 300 张与最终三轮 500 张 Java / native / RSS / PSS 曲线，及 1,066 份外部快照。采样未见文档级逐页累积，但不能代替连续真实峰值；各组前台条件不完全相同，尚无 4 GB 设备。 |
| S03 | 48MP、超长、损坏 | **真机异常部分通过**。损坏图保留错误、GIF 拒绝已实际通过，几何极端比例单测通过；48MP、超长、动态 WebP / APNG 和设备预算边界尚待真实解码验证。 |
| S04 | 生成 / 保存中取消 | **真机清理部分通过**。第一页完成后的协程取消异常会清理未完成输出；writer 不写完整 trailer 单测通过。UI 取消、保存中取消及最坏不可中断编码耗时仍未测。 |
| S05 | 临时 / 目标空间不足 | **实现待设备**。存在预检、异常清理、导出失败保留私有结果路径；尚未注入真实 ENOSPC 或目标提供方写入失败。 |
| S06 | URI 撤权、源删除、云端离线 | **实现待设备**。访问错误保留异常条目及重新选择路径已实现；真实 Content URI 授权撤销和云端不可用尚未测试。 |
| S07 | 熄屏、后台、旋转、通知拒绝 | **前台与重建部分通过，后台仍待验证**。旧 AppFlow 的真实前台服务生成、防重和重建通过；最终长测直接调用转换管线，观察到系统冻结并使用普通前台 Intent 辅助，不能代替生产前台服务的熄屏后台测试。横竖屏切换、通知拒绝、版本分支及超时仍待测。 |
| S08 | 进程杀死、强停、重启 | **实现待设备**。持久状态恢复识别未完成任务，从头重试；未执行杀进程 / 重启验证，不声明断点续写。 |
| S09 | U 盘移除 / 保存失败 | **实现待设备**。导出流关闭、尽力删除新建不完整目标、保留完整私有结果已实现；尚未接入实际移动卷或注入提供方错误。 |
| S10 | 新建、清缓存、删除历史 | **真机单次保留部分通过**。AppFlow 新建后完整私有 PDF 仍存在；长时间连续操作、缓存清理误删保护及文件描述符趋势仍待测。历史管理属于 P1，不计作已验收功能。 |

## 5. 可复现命令与后续执行

宿主检查：

```powershell
.\gradlew.bat :core:test :core:engineProbe --console=plain
python scripts/verify_pdf.py build/engine-probe/host-100.pdf build/engine-probe/host-300.pdf build/engine-probe/host-500.pdf --expect-a4 --expect-numbered --render-dir build/engine-probe/previews --report build/engine-probe/independent-validation.json
.\gradlew.bat :app:assembleDebug :app:assembleBenchmark :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

若宿主临时目录也包含非 ASCII 字符，可以通过 `-Pimagepdf.testTempDir=C:/ascii/path` 指定测试运行 JAR 的临时目录；正常构建输出和报告仍在项目目录。

1.0.1 的六项功能测试与单批导入回归、首次 100 / 300 张及 500 张中断记录都保留。1.0.2 最终 FolderRepository 套件四项通过；最终长测使用 `performance=true`、`sizes=500`、`repeats500=3`，完整完成三轮，证据位于 `build/device-evidence-final/`。运行条件包含测试夹具预授权、设备端持久 runner 及前台辅助，不能不注明条件直接与普通后台行为等同。生成样本时间不计入每轮转换耗时；合成样本不能代替真实相机组及 4 GB 设备验收，首轮超过五分钟也未被删除或调整。完整命令及证据提取按构建运行说明执行。

后续仍需补充真实相机 / 混合格式、4 GB 设备、连续峰值、取消最坏耗时，以及 F01～F10、S01～S10 中实际系统界面、后台、异常注入和至少两种安卓阅读器的证据。应在固定且注明的前台 / 后台条件下复核五分钟目标。已有三轮耗时、超标、冻结观察及历史中断全部保留；未执行项目不填推测值。
