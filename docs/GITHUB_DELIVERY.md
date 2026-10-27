# GitHub 与构建交付

应用：**l君のpdf转换**，当前测试版本 1.0.3 / versionCode 4。

## 当前交付目标与仓库布局

用户最新指定独立仓库 [fresher623/lkun-pdf-transfer](https://github.com/fresher623/lkun-pdf-transfer)。交付分支为 **`main`**，PDF 工程直接位于**仓库根目录**，包含 Gradle Wrapper、`app`、`core`、`docs` 和脚本。Android Studio 应打开该仓库根目录；命令行也在根目录执行构建。

本轮迁移只涉及源码、文档与仓库布局，应用仍为 **1.0.3 / versionCode 4**；APK 和手机代码不变，不为迁移重新构建或重跑测试。新仓库提交、推送和远端一致性核验由迁移流程执行，当前不提前记为成功，结果见 [仓库迁移状态](ITERATIONS.md#仓库迁移状态)。

## 旧目标的历史记录

首次交付曾使用 [fresher623/lkun-tomato-shuffle](https://github.com/fresher623/lkun-tomato-shuffle) 的 `feat/lkun-pdf-1.0.3` 分支，PDF 工程位于该分支的 `lkun-pdf/` 目录。历史源码提交 `82e09cb19ae473a7f2370386e2c8951ad7fe99ca` 推送成功并核对过远端；这些是历史事实，不是当前拉取入口。

用户随后说明旧分支由其自行处理。本轮**不对旧仓库或旧分支执行写入、删除或历史重写**，也不把旧分支标为已删除。旧仓库原有番茄项目的 `main` 不属于本轮迁移修改范围。

## 从新仓库取回源码并构建

新仓库 `main` 完成推送后使用以下命令：

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

## 本地交付物

执行 `python scripts/package_source.py` 将生成：

- `dist/l君のpdf转换-1.0.3-test.apk`
- `dist/l君のpdf转换-1.0.3-source.zip`
- `dist/SHA256.json`
- `dist/verification/` 下的本机验证记录（存在相应本地证据时复制）

APK 使用测试签名；其他机器首次构建通常会生成另一把 debug 密钥，不能默认覆盖用户手机上本次签名的版本。不要为解决签名不匹配而卸载含用户数据的应用；正式发行需单独管理长期签名，密钥不进入仓库。

源码提交不包含 APK、源码 ZIP、SDK、构建缓存、原始设备日志、本机 `local.properties`、签名密钥或个人照片。原始验证文件留在开发机交付目录；仓库中的 Markdown 报告明确版本、结果与限制。本轮直接向新独立仓库的 `main` 交付，不自动创建 GitHub Release 或调整仓库可见性。

## 文档索引

- [执行计划](../PLAN.md)
- [修改记录](CHANGELOG.md)
- [迭代记录](ITERATIONS.md)
- [测试报告](TEST_REPORT.md)
- [批量导入说明](BATCH_IMPORT.md)
