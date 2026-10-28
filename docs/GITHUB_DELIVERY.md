# GitHub 与构建交付

应用：**l君のpdf转换**，当前源码版本 1.1.0 / versionCode 6；新增 PDF 合并的实现与验证见 [合并说明](PDF_MERGE.md) 和 [测试报告](TEST_REPORT.md)。

**当前交付暂缓**：功能回归完成后，用户反馈 vivo 对主包提示恶意软件，原因尚未定位。1.1.0 仅在本机保留源码和构建物，未据此判定安全或发布。排查事实见 [安全排查记录](SECURITY_REVIEW.md)；不能用更换签名、包名或关闭检测代替问题核查。

## 当前交付目标与仓库布局

用户最新指定独立仓库 [fresher623/lkun-pdf-transfer](https://github.com/fresher623/lkun-pdf-transfer)。交付分支为 **`main`**，PDF 工程直接位于**仓库根目录**，包含 Gradle Wrapper、`app`、`core`、`docs` 和脚本。Android Studio 应打开该仓库根目录；命令行也在根目录执行构建。

独立仓库已建立，首次源码提交为 `430548d`，后续已包含 1.0.4 发布签名、外部口令配置和 `74b298f` 百宝箱计划。本轮 PDF 合并在该计划提交上实施；推送前须核对远端，使用普通推送，不重写历史。历史手机测试仍按原版本记录，结果见 [仓库迁移状态](ITERATIONS.md#仓库迁移状态)。

## 旧目标的历史记录

首次交付曾使用 [fresher623/lkun-tomato-shuffle](https://github.com/fresher623/lkun-tomato-shuffle) 的 `feat/lkun-pdf-1.0.3` 分支，PDF 工程位于该分支的 `lkun-pdf/` 目录。历史源码提交 `82e09cb19ae473a7f2370386e2c8951ad7fe99ca` 推送成功并核对过远端；这些是历史事实，不是当前拉取入口。

用户随后说明旧分支由其自行处理。本轮**不对旧仓库或旧分支执行写入、删除或历史重写**，也不把旧分支标为已删除。旧仓库原有番茄项目的 `main` 不属于本轮迁移修改范围。

## 从新仓库取回源码并构建

取回当前源码使用以下命令；本机已经有工作目录时直接使用 [Git 推送教程](GIT_GUIDE.md)：

```powershell
git clone --branch main https://github.com/fresher623/lkun-pdf-transfer.git
cd lkun-pdf-transfer
```

准备 JDK 17 或 21、Android SDK Platform 35 与 Build Tools 35.0.0，然后在本工程目录创建仅属于本机的 `local.properties`：

```properties
sdk.dir=C:/Android/Sdk
```

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug --console=plain
```

macOS / Linux 使用 `chmod +x gradlew` 后执行 `./gradlew` 的同名任务。主 APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。

需要独立测试包时：

```powershell
.\gradlew.bat :app:assembleBenchmark :app:assembleBenchmarkAndroidTest -PtestBuildType=benchmark --console=plain
```

安装 `app-benchmark.apk` 与对应的 `app-benchmark-androidTest.apk` 后，按 `scripts/run_folder_test.py --help` 运行受控文件夹回归。该脚本仅给固定合成目录授予临时权限，并在最后撤销；不向用户主应用草稿导入测试图片。

如果已有测试包存在草稿，不清空它。可在上面的构建命令中追加 `'-PbenchmarkIdSuffix=.mergeqa.benchmark'`（PowerShell 中保留这一对引号），构建另一个独立验证包；运行脚本时对应传入 `--package cn.local.imagepdf.mergeqa.benchmark`。`--classes` 选择套件，`--merge-boundary` 显式开启 50 份 / 1000 页合成边界测试。前台辅助条件、跳过和失败均应按测试报告记录。

## 本地交付物

先完成对应构建，再执行 `python scripts/package_source.py` 将根据源码与 APK 的实际版本生成：

- `dist/l君のpdf转换-1.1.0-release.apk`
- `dist/l君のpdf转换-1.1.0-source.zip`
- `dist/SHA256.json`
- `dist/verification/` 下的本机验证记录（存在相应本地证据时复制）

追加 `--variant debug` 会生成 `dist/l君のpdf转换-1.1.0-test.apk`，适用于沿用本机旧调试证书的手机。脚本会检查 APK 版本和源码更新时间，避免交付过期 APK。

1.0.4 起 release 使用项目发布密钥，密钥和口令配置保存在仓库外。2026-10-28 实测本机手机仍装有 1.0.3 调试签名，因此应使用同一调试证书的新 debug 包保留数据升级；不能直接覆盖安装 release，也不能把此过程写成 1.0.4 release 的覆盖升级验证。换电脑构建的 debug APK 可能使用另一把密钥。覆盖升级须核对同一证书，不应通过卸载含重要草稿的旧应用绕过证书限制。详细签名变更见 [修改记录](CHANGELOG.md)。

源码提交不包含 APK、源码 ZIP、SDK、构建缓存、原始设备日志、本机 `local.properties`、签名密钥或个人照片。原始验证文件留在开发机交付目录；仓库中的 Markdown 报告明确版本、结果与限制。本轮直接向新独立仓库的 `main` 交付，不自动创建 GitHub Release 或调整仓库可见性。

## 文档索引

- [执行计划](../PLAN.md)
- [修改记录](CHANGELOG.md)
- [迭代记录](ITERATIONS.md)
- [测试报告](TEST_REPORT.md)
- [批量导入说明](BATCH_IMPORT.md)
- [Git 推送教程](GIT_GUIDE.md)
- [PDF 合并与百宝箱计划](TOOLBOX_PLAN.md)
