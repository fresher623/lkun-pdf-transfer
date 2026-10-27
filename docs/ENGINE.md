# PDF 引擎与验证记录

## 选择

采用本工程原创的 `StreamingPdfWriter`，实现当前产品所需的 PDF 1.4 子集：每页一个 RGB JPEG 图像、页面内容流、可选 Helvetica 数字页码、交叉引用表及目录。通过 `PdfEngine` 隔离接口，Android 平台采样、EXIF、透明区域白色合成及 JPEG 编码由 `BitmapPipeline` / `ImageConverter` 负责。

这里选择受控的小型专用写入器，是因为产品每图一页、没有 PDF 编辑或复杂字体需求。相较于在内存中持有完整文档，写入器在每次 `addPage` 内将当前 JPEG 临时文件按 64 KiB 流式复制到输出，写完立即刷新，并且只保存最多 500 页的对象偏移数组。文件直到全部页面写入并且 `finish()` 完成后才有完整 trailer；调用 `close()` 不会把取消的半成品标为完成。Android 协调器仍负责删除半成品。

候选评估：

| 方案 | 采用情况 | 原因 / 尚需验证 |
| --- | --- | --- |
| 平台 `PdfDocument` | 未采用 | 未取得其整份 500 页图片文档的原生内存曲线；不能根据逐页绘制推断恒定内存 |
| 第三方完整 PDF 库 | 未引入 | 当前范围可由小型 JPEG 写入器覆盖，避免 APK 体积及商业分发许可依赖；未对所有候选做性能对比 |
| 本工程逐页 JPEG 写入器 | 当前实现 | 宿主可检验字节结构与渲染；Android 500 张 12MP、原生内存和后台稳定性仍需实体机验证 |

当前格式限制：只接收平台处理后产生的 RGB JPEG；没有 Unicode 文本、加密、PDF 合并、OCR 或高级 PDF 编辑功能。页码仅使用 PDF 内置字体的 ASCII 数字。透明图片在 JPEG 编码前合成到白色。输出要求独立解析和渲染成功；这不构成 PDF/A 合规声明。

## 几何规则

- `1 inch = 72 point`；毫米换算为 `mm ÷ 25.4 × 72`。
- A4、A5、Letter 支持按最终图片方向自动横竖或固定方向；FIT 取较小比例，FILL 取较大比例并裁剪到内容区域。
- 按图片比例模式以 297 mm 为长边，短边按比例，随后增加边距和独立页码区域；极端比例为避免退化内容矩形，短边至少保留 1 point。该模式采用完整显示。
- 标准物理页面的页码独占内容区下方 22 point；不会绘制在正文上。
- 所有设置在任务开始时固定。高质量 200 DPI / JPEG 90，标准 150 / 85，省空间 100 / 75；内存不足会明确失败，不暗中降低所选质量。

## 可复现的宿主检查

```powershell
.\gradlew.bat :core:test :core:engineProbe --console=plain
python scripts/verify_pdf.py build/engine-probe/host-100.pdf build/engine-probe/host-300.pdf build/engine-probe/host-500.pdf --expect-a4 --expect-numbered --render-dir build/engine-probe/previews --report build/engine-probe/independent-validation.json
```

`engineProbe` 使用限制为 128 MiB 最大堆的宿主 JVM，串行生成每页带唯一编号及图案的 **900×1200 合成 RGB JPEG**，没有私人图片和空白页替代。每页被重新编码后立即流式写入并覆盖当前 JPEG 临时文件。结果在根目录 `build/engine-probe/`，`host-metrics.json` 记录总耗时、输出体积、按页采样的 Java 堆峰值及每 100 页的堆用量。

这些值受宿主 JVM、AWT 编码及 GC 影响。它们用于发现专用写入器明显的文档级内存累积，**不能替代安卓解码、12MP 样本、原生内存或实体机性能结论**。宿主堆用量的锯齿波动也不能当成泄漏或恒定峰值证明。`verify_pdf.py` 使用独立的 pypdf 严格解析与 MuPDF 渲染，检查页数、A4 页尺寸、顺序页码、每页独立 JPEG、首中末渲染，并可保存 PNG 供目视检查。MuPDF 仅为开发者在宿主机独立安装的验证工具，不会链接到 APK。

## 阶段一保留的发布阻塞

尚需在至少一台 4 GB RAM 实体设备和一台较新设备，以 release 或 benchmark 构建完成：

1. 固定 500 张约 12MP JPEG 连续三次，核对首、中、末及全部页序，记录真实型号、系统、应用堆上限、耗时与输出大小。
2. 100 / 300 / 500 张的 Java 堆、原生分配、RSS / PSS 曲线；Java 堆低于应用堆上限 70%，没有随页数持续不可回收增长。
3. EXIF 八方向叠加用户四档旋转；透明 PNG、极端尺寸、坏图、HEIF/WebP 设备解码分支。
4. 取消、锁屏、切后台、通知权限拒绝、前台服务超时与进程被杀的状态及清理。
5. 不足空间、失效 URI、移动目标卷、取消和失败另存；至少两种安卓阅读器打开结果。

在这些项目完成前，安装包属于待实体机验收的测试构建，不声明达成文档的完整 V1 性能门槛。
