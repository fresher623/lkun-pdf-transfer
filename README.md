# l君のpdf转换

按本目录《安卓图片合并PDF_App需求文档.md》实现的原生安卓应用。支持 Android 8.0 / API 26 及以上。当前本地候选为 **1.1.0 / code 6**，保留图片转 PDF，新增独立 PDF 合并页，沿用 1.0.4 的项目发布签名配置。合并引擎、独立界面及原有图片流程已通过本轮真机回归，50 份 / 1000 页合成输出通过独立复核，主应用已同签名覆盖更新。用户随后反馈 vivo 恶意软件风险提示，原因仍未确认；2026-10-28 用户知悉后要求继续，现恢复源码交付与推送流程，不据此认定已安全或属于误报。

源码仓库为 [fresher623/lkun-pdf-transfer](https://github.com/fresher623/lkun-pdf-transfer) 的 `main`，工程直接位于仓库根目录；1.1.0 功能源码提交 `5cbeb59` 已推送到该分支并完成远端核验，交付文档在本轮同步。历史手机测试按原版本保留，仓库迁移状态见 [GitHub 交付说明](docs/GITHUB_DELIVERY.md) 和 [仓库迁移记录](docs/ITERATIONS.md#仓库迁移状态)。原番茄仓库的分支由用户自行处理。

PDF 合并的使用与严格兼容范围见 [PDF 合并说明](docs/PDF_MERGE.md)。M0 引擎与来源治理、M1 独立合并页已有候选实现，统一构建和目标手机功能回归完成，安全告警仍待定位；[百宝箱计划](docs/TOOLBOX_PLAN.md) 中的 M2 首页整合、M3 解混淆仍未实施。下次自行提交和推送见 [Git 推送教程](docs/GIT_GUIDE.md)。

## 安装与使用

1.1.0 提供发布签名包 `dist/l君のpdf转换-1.1.0-release.apk`，以及用于沿用本机旧调试签名的 `dist/l君のpdf转换-1.1.0-test.apk`；最终打包状态以测试报告为准。手机原为 1.0.3 调试签名，本轮已用同签名 debug 1.1.0 / code 6 覆盖安装成功，未卸载或清数据，首次安装时间保持不变；没有逐项读取比对原草稿内容。发布证书不同，不能直接覆盖旧调试签名。发布包继续沿用 1.0.4 发布证书，从该版本覆盖更新的数据保留尚未实测。应用无需账号、无需网络或相册全量访问权限。

1. 点击“选择文件夹 · 批量导入”，在系统界面选择图片所在的具体文件夹并允许访问。返回应用后，顶部固定显示“全选 / 取消全选”，底部点击“导入已选 N 张”，即可一次导入 1～500 张。文件夹超过剩余额度时先明确确认选择前 N 张，不静默截取；也可逐项勾选。只列出所选文件夹当前层的图片，不递归子文件夹。仍保留“逐张选择图片”和“系统相册”辅助入口。可多次追加，但任务合计最多 500 张。
2. 长按拖动图片调整顺序，或使用前后移动按钮；支持文件名自然升序、拍摄时间升序、导入顺序、旋转、删除和单张预览。
3. 设置文件名、纸张、方向、完整显示或裁切、边距、三档画质和页码。
4. 点击生成。前台通知和应用内显示真实页数，可以取消；离开页面可在首页返回任务。
5. 完成后可内置预览、另存、分享或调用外部阅读器。取消另存不会丢失完整的应用内 PDF。

清晰 / 标准 / 省空间分别使用 200 / 150 / 100 DPI 与 90 / 85 / 75 的 JPEG 编码质量。每图一页，透明区域合成白底，不放大小图像素。按图片比例模式长边为 297 mm，再添加边距与独立页码区域；该模式采用完整显示。无法在固定内存预算内处理的图片会明确提示，不自动降质或跳页。

合并已有 PDF：从首页“打开 PDF 合并 →”进入，添加 2～50 份文档，预览并调整文件顺序，填写名称后开始；完成后可预览、另存或分享。按文件顺序加入全部页面，不套用图片工具的纸张或画质设置。上限为总计 1000 页、单份 256 MiB、输入合计和输出各 512 MiB。首版仅接收严格检查的基础静态 PDF；加密、签名、书签、标签、注释、链接、表单、附件及许多文档级设置（包括 Metadata / Lang / ViewerPreferences）都会拒绝，不能视为通用 PDF 合并器。两类草稿独立，重任务互斥，返回另一工具保留草稿。完整边界见 [PDF 合并说明](docs/PDF_MERGE.md)。

## 构建

已固定可复现的版本：Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20、Compose BOM 2025.04.01；compileSdk / targetSdk 35。推荐 JDK 17 或 21，安装 Android SDK Platform 35 和 Build Tools 35.0.0。

在项目根目录创建本机 `local.properties`，例如：

```properties
sdk.dir=C:/Android/Sdk
```

Windows PowerShell：

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。`benchmark` 是去掉 UI 调试工具但保留调试能力的 release-like 变体，应用标识为 `cn.local.imagepdf.benchmark`、名称“l君のpdf转换 · 测试”，与用户使用的版本隔离，仍使用测试签名。正式发布需所有者提供签名、确认应用标识和渠道要求。

Windows 中文目录通过 AGP 的路径选项支持。core 测试额外把测试类打包到临时目录，规避 Windows JDK 参数文件的中文路径问题；临时目录也含中文时传入 `-Pimagepdf.testTempDir=C:/temp/imagepdf-tests`。

## 验证

1.1.0 最新统一构建成功（`merge-memory-fix-build.log`，46 s），lint 为 0 errors / 14 warnings，旧核心测试为 up-to-date。引擎 / 隔离套件 **10 项通过、无失败或跳过，4.895 s**，包含 50 份 / 1000 页微小合成 PDF；1000 页文字、顺序、MediaBox / CropBox / 旋转及解码内容流经独立复核全部一致，MuPDF 无修复并抽查首 / 中 / 末页像素一致。导入 1,038 ms、合并 650 ms，输入 / 输出为 664,693 / 667,281 bytes；这不是大输入性能或全程内存峰值认证。

最终流程套件 **15 项通过、0 失败、0 跳过，26.551 s**，覆盖独立合并界面、真实合并 / 图片前台服务、重建 / 导出、500 / 501 导入、文件夹全选和通知取消。主应用已同签名覆盖安装，版本为 1.1.0 / code 6，可启动并进入合并页；已安装 APK 与构建 debug 文件 SHA-256 完全相同；用户反馈的 vivo 安全告警仍未定位，本机 Defender 扫描完成、项目匹配检测为 0 也不能消除该警报。调查见 [安全核查记录](docs/SECURITY_REVIEW.md)。首轮失败和系统强停保留于 [测试报告](docs/TEST_REPORT.md)，不把功能测试通过写成安全结论。以下结果保持历史版本归属。

```powershell
.\gradlew.bat :core:test :core:engineProbe
python -m pip install -r scripts/requirements-verify.txt
python scripts/verify_pdf.py build/engine-probe/host-100.pdf build/engine-probe/host-300.pdf build/engine-probe/host-500.pdf --expect-a4 --expect-numbered --render-dir build/engine-probe/previews --report build/engine-probe/independent-validation.json
```

宿主验证生成带编号的真实编码 JPEG PDF，由 pypdf 严格解析，并由 MuPDF 独立渲染。它证明 PDF 结构、页面尺寸、页码和图片流正确，不能代替 Android 大批量解码、系统后台限制和低内存设备测试。

USB 真机快速测试及压力测试步骤见 `scripts/test-device.ps1` 和 `docs/TEST_REPORT.md`。测试只生成应用私有的合成样本，不访问个人相册。500 张三次测试为显式长测，报告包含 Java 堆、native、PSS、RSS、耗时和文件大小；合成纹理样本也不能代替真实相机样本及 4 GB 设备验收。

1.0.2 已在 vivo V2505A 完成文件夹四项测试，实际点击应用内“全选”和“导入已选 500 张”，验证数量、顺序及原草稿保护。另用 500 张 4000×3000 合成 JPEG 连续转换三次，均生成完整 500 页 PDF，耗时分别为 319.554 / 208.408 / 130.215 秒。最终文件经严格解析、逐页条码顺序检查和独立渲染通过。测试期间观察到系统冻结，并辅助将测试应用带回前台；首轮超过五分钟目标，不能认定全部性能及后台验收通过。

历史 1.0.3 更名后已安装并启动，正式名称和批量入口核对通过；隔离文件夹回归四项通过，耗时 6.231 秒，使用预授权合成夹具。当次没有重跑 12MP 长测，上述三轮数字仍对应 1.0.2。安装前未检测到主包，因此不作为从 1.0.2 升级的数据迁移验证，也不代替 1.1.0 回归。

若设备的 `run-as` 返回 `packagelist_parse failed`，压力测试也会将成功轮次的 CSV、抽样 PNG 和最近一份完整 500 页 PDF 镜像到测试应用专属外部目录；使用 `scripts/test-device.ps1 -Performance -ExternalEvidence` 或 `python scripts/collect_device.py --adb <adb路径> --serial <设备序列号> --output build/device-evidence --external` 提取。只镜像合成测试文件，不导出用户创建的 PDF。

## 架构与数据

`core/` 是不依赖 Android 的布局、排序、数量校验和流式图片 PDF 写入器。`app/platform/` 处理 URI、受控源缓存、EXIF 八方向、采样解码、编码、结果校验和 SAF 导出。`AppRuntime` / `StateStore` 管理图片快照与原子草稿；新增 `merge/` 独立管理 PDF 页面对象复制、草稿和合并前台任务。`TaskGate` 协调双工具的重任务互斥，Compose 观察 StateFlow，Activity 接系统选择器和分享入口。

图片仍逐张解码、编码为临时 JPEG 并写入 PDF，原写入器只保留对象偏移和页索引。PDF 合并使用 PdfBox-Android 2.0.27.0 和有界磁盘 scratch，页面对象元数据仍可能随复杂度增长，不宣称恒定内存。源 scratch 256 MiB、目标 scratch 512 MiB，Java 堆检查点越过上限 70% 时最多触发一次 GC 并复查，仍超限则停止；1 秒内再次越线直接拒绝，不循环等待。这些是预算，不是实测峰值。未加入 OCR、PDF 密码处理、云上传或广告。

- 支持持久授权的图片 URI 保留读取授权；其他来源流式复制到私有 `files/sources/`。原始 URI 仅存于私有草稿用于重复检测，不写日志。
- PDF 输入全部复制到独立 `files/merge-sources/`，不取得长期 PDF URI 授权；合并使用独立原子 `merge-session.json`。图片清理只释放登记拥有的图片授权，不撤销未知或目录树授权。
- 未引用源副本至少保护 24 小时，后续编辑或启动清理；正在导入和当前任务的源不会被清理。
- 未完成的 `.partial` 和单页中间文件在取消、失败或下次启动时清理。
- 完整 PDF 位于 `files/results/<任务ID>/`，不会被缓存清理删除。首页只显示最近一份；新建后旧结果仍保留，但 V1 暂无历史管理入口，重要结果应先另存。
- 另存的外部文件由用户管理；保存失败会尽力删除此次新建的不完整目标，并保留私有完整结果。
- 应用无网络权限；分享只授权一个结果 URI，FileProvider 不暴露图片源缓存。禁用云备份和设备迁移，避免图片与 URI 授权进入备份。
- 活跃任务遇进程终止会标记中断，从头重试，不声称 PDF 断点续写。

## 当前限制

1.1.0 已完成本台手机上的合并引擎、独立界面、图片回归和同签名更新，首轮失败和系统中断完整保留。用户确认 vivo 恶意软件风险提示针对主应用，已取得“高危病毒／危险行为程序”截图，具体检测引擎、威胁标识和触发规则尚未取得；此前曾暂缓交付；2026-10-28 用户知悉后要求继续，现恢复源码交付与推送流程，告警仍未确认原因，见 [安全核查记录](docs/SECURITY_REVIEW.md)。严格兼容白名单会拒绝不少看起来普通的 PDF。候选代码与预算不能代替合并验收、4 GB、后台 / 熄屏或连续真实内存峰值证据。M2 百宝箱和 M3 解混淆尚未实现。

vivo V2505A 上，1.0.1 的六项仪器测试已通过，包含 EXIF 8×4 方向组合、透明白底、取消清理、坏图/GIF、协调器前台生成与重建、单批 500/501 个 URI 导入。1.0.2 主应用已成功覆盖安装，应用内全选测试与三轮 500 张 12MP 合成图转换均已完成。文件夹测试使用预授权合成目录；长测直接调用转换管线并有前台辅助，设备总 RAM 约 16 GB。真实外部文件夹的系统授权、真实相机图组、后台/熄屏、取消时延、SAF 提供方异常和 4 GB 设备仍待验证；离散内存采样不能代表全程真实峰值。历史强停与失败轮次保留在报告中。WebP/HEIF 能否处理取决于设备解码器；GIF、动态 WebP、APNG、RAW、多页 TIFF 不保证支持。未实现 P1 历史管理、批量编辑、显式跳过坏图或大小估算。

详细状态以 [测试报告](docs/TEST_REPORT.md)、[一次批量导入补充](docs/BATCH_IMPORT.md)、[引擎选型记录](docs/ENGINE.md) 和 [依赖许可证](THIRD_PARTY_NOTICES.md) 为准。原始需求文档保持原样，用户补充的一次选择 1～500 张要求优先于原建议的 Photo Picker 主入口，未降低 500 张性能验收门槛。

文档入口：[PDF 合并说明](docs/PDF_MERGE.md)、[修改记录（修改 md）](docs/CHANGELOG.md)、[迭代记录（迭代 md）](docs/ITERATIONS.md)、[首次交付实施计划](PLAN.md)、[GitHub 交付说明](docs/GITHUB_DELIVERY.md)、[Git推送教程](docs/GIT_GUIDE.md)。
