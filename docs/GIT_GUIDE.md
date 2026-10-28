# Windows / PowerShell：以后自己提交和推送源码

更新日期：2026-10-28。

目标仓库：[fresher623/lkun-pdf-transfer](https://github.com/fresher623/lkun-pdf-transfer)，分支为 `main`，安卓工程直接放在仓库根目录。

当前电脑的 `E:\PDF转化` 已经是该仓库的 Git 工作目录，已有 `main` 和 `origin`，以后可以直接在这里修改和推送。下文的日常命令使用这个路径；换电脑或需要另一份副本时，才执行首次克隆。当前交付信息见 [GitHub 交付说明](GITHUB_DELIVERY.md)。

## 一、第一次使用，只做一次

### 1. 打开 PowerShell，确认 Git 可用

本机已经安装 Git。先运行：

```powershell
git --version
```

看到 `git version ...` 即可。如果换电脑后提示找不到 `git`，先安装 Git for Windows，再重新打开 PowerShell。

### 2. 换电脑或新目录时克隆仓库

当前 `E:\PDF转化` 已有 Git 的情况可跳过本步。需要新副本时运行：

```powershell
Set-Location -LiteralPath 'E:\'
git clone --branch main 'https://github.com/fresher623/lkun-pdf-transfer.git' 'E:\lkun-pdf-transfer'
Set-Location -LiteralPath 'E:\lkun-pdf-transfer'
git remote -v
git branch --show-current
```

检查两点：远端地址是 `fresher623/lkun-pdf-transfer.git`，当前分支是 `main`。如果提示仓库为空或找不到 `main`，先确认首次源码迁移是否完成，不要自行创建另一套提交历史。

如果以前已经克隆过，直接进入该目录，不必再次 `clone`。如果目录已存在且不是空目录，不要删除已有文件来重试；先检查是不是此前的克隆目录。

### 3. 给这个仓库设置提交身份

在你实际使用的工程根目录内运行（当前电脑为 `E:\PDF转化`；若刚执行了克隆，则是 `E:\lkun-pdf-transfer`）：

```powershell
git config --local user.name 'fresher623'
git config --local user.email '89204446+fresher623@users.noreply.github.com'
git config --local --get user.name
git config --local --get user.email
```

这里使用已核对的 GitHub 隐私邮箱，避免推送被 `GH007` 拦截。`--local` 只设置当前仓库，不改变其他项目的身份。这些是提交署名，不是 GitHub 登录密码。

## 二、下次修改和推送，按这个顺序

### 1. 进入克隆目录，先同步远端

```powershell
Set-Location -LiteralPath 'E:\PDF转化'
git status --short
git switch main
git pull --ff-only origin main
```

通常 `git status --short` 没有输出，说明没有未提交改动。若已经有自己的改动，先按下面的检查、暂存、提交步骤保存，再同步；不要为了拉取而丢弃文件。`--ff-only` 会在不能直接快进时停下，避免自动产生一次意外合并。

### 2. 修改文件并保存

以后用编辑器或 Android Studio 打开同一个 Git 工作目录。比如当前电脑打开 `E:\PDF转化`，用记事本修改 README：

```powershell
notepad '.\README.md'
```

编辑后保存。修改 `.md` 与修改 `.kt`、`.xml` 都使用相同的 Git 流程；仅在编辑器中保存，不会自动同步到 GitHub。代码改动应按项目 README 执行相关构建或测试；只改说明文档通常不需要重新打包 APK。

如果使用了另一个克隆目录，就在那一份工程里编辑，并将命令中的路径一并替换。不要在两个目录间覆盖 `.git`、本机配置或构建产物；只同步明确要修改的源码或文档。

### 3. 查看这次到底改了什么

```powershell
git status --short
git diff --stat
git diff
```

常见标记：`M` 是修改，`??` 是新增且未跟踪，`D` 是删除。`git diff` 默认查看已跟踪文件的未暂存改动；新增文件还需要自己打开检查内容。

### 4. 只把本次需要的文件加入暂存区

暂存区就是“选定这次提交要包含哪些文件”。例如本次只修改 README 和 Git 指南：

```powershell
git add -- 'README.md' 'docs/GIT_GUIDE.md'
```

只改一个文件时，就只写那个文件的路径；从仓库根目录开始填写。若已逐项审核所有新增、修改和删除，确认全部都属于本次变更，也可以改用：

```powershell
git add -A
```

随后检查实际暂存内容：

```powershell
git diff --cached --stat
git diff --cached
```

如果误选了某个文件，可取消暂存，保留本地编辑。例如：

```powershell
git restore --staged -- 'docs/GIT_GUIDE.md'
```

### 5. 提交，再推送到 GitHub

提交说明应写清楚本次改动。比如：

```powershell
git commit -m 'docs: 更新使用说明'
git push origin main
git status -sb
```

`commit` 先保存在本机；`push` 才上传到 GitHub。推送后打开仓库页面，检查 `main` 的最新提交和文件内容。成功同步时，`git status -sb` 不应再显示 `ahead` 或 `behind`，也不应有本次遗漏的改动。

## 三、首次推送时怎么登录

Git for Windows 的 Git Credential Manager（GCM）可能在首次推送时打开浏览器。按终端提示选择浏览器登录，在 GitHub 用 `fresher623` 完成授权即可；后续通常由凭据管理器处理。

不要把密码、令牌、验证码或私钥发给助手，也不要把令牌写进远端 URL、命令或仓库文件。上面的 HTTPS 仓库地址不需要附加令牌。

## 四、这些文件不提交到源码仓库

当前 `.gitignore` 已排除以下内容：

- `dist/`、APK / AAB、压缩包及构建输出目录。
- `.jks`、`.keystore`、`.pem`、`.key`、`.p12`、`.pfx` 等密钥文件，以及签名配置。
- `local.properties`、`.env` 和实际环境配置文件。
- 本机工具缓存、IDE 配置、日志和内存转储。

不要用 `git add -f` 强行加入这些文件。`.gitignore` 不会自动移除已经被跟踪的文件，所以仍需检查 `git diff --cached`。APK 如需分发，可作为 GitHub Releases 附件单独上传，不放进源码提交。

## 五、常见报错

### `fatal: not a git repository`

当前目录不对。当前电脑进入 `E:\PDF转化`；如果用了另一个克隆位置，则进入对应目录：

```powershell
Set-Location -LiteralPath 'E:\PDF转化'
git status
```

当前 `E:\PDF转化` 已经初始化并连接了远端，不必再次 `git init`。换电脑时若还没有克隆，先完成第一部分。

### `nothing to commit, working tree clean`

没有可提交的新改动。检查是否保存了文件、是否编辑了正确的克隆目录，以及文件是否被忽略。若本地提交已经完成，但 `git status -sb` 显示 `ahead`，仍需运行 `git push origin main`。

### 推送被拒绝：`non-fast-forward` / `fetch first`

远端有更新。先确保自己的本地改动已经提交，再尝试：

```powershell
git pull --ff-only origin main
git push origin main
```

如果 `pull --ff-only` 明确提示无法快进，说明本地与远端都有新提交。工作区干净时，可以把本地提交接到远端最新提交之后：

```powershell
git fetch origin
git rebase origin/main
```

如遇冲突，按提示编辑冲突文件，保存后只暂存解决好的文件，再运行 `git rebase --continue`。如果不确定如何处理，可用 `git rebase --abort` 回到本次 rebase 开始前。rebase 完成后正常 `git push origin main`，不要使用 `--force` 或 `--force-with-lease` 来绕过问题。

### `GH007: Your push would publish a private email address`

先设置正确的本仓库隐私邮箱：

```powershell
git config --local user.email '89204446+fresher623@users.noreply.github.com'
git log -1 --format='%h %an <%ae>'
```

只改配置不会修正已经生成的提交。若被拒绝的是最近一条、尚未推送的提交，确认暂存区没有混入其他改动后修正署名：

```powershell
git commit --amend --reset-author --no-edit
git push origin main
```

如果多条尚未推送的提交都带旧邮箱，需要逐条检查和修正，不能只改最后一条。不要为此强推或关闭邮箱隐私保护。

### 登录失败或没有推送权限

先用 `git remote -v` 确认地址是本教程的新仓库，再确认浏览器 / GCM 登录的是有该仓库写权限的账号。按凭据管理器提示重新登录，不要把凭据粘贴到聊天或改成带令牌的仓库地址。
