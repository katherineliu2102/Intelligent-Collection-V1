# CI 与 Git 提交操作手册

> **适用**：`Intelligent-Collection-V1` Phase 1 仓库  
> **CI 定义**：`.github/workflows/ci.yml`  
> **本地钩子**：`.githooks/commit-msg`（提交信息校验）  
> **格式工具**：Spotless + Google Java Format（AOSP 4 空格）

本文档整理自 2026-07 实际排障经验，供日常提交与 CI 失败时快速复用。

---

## 一、CI 三道闸（PR → main 时全部执行）

| 顺序 | 步骤 | 命令 / 规则 | 本地能否绕过 |
|:--:|------|-------------|:--:|
| 1 | 提交信息门禁 | Angular 规范，校验 `origin/main..HEAD` 每条 commit | 本地 hook 可 `--no-verify` 绕过；**CI 不可** |
| 2 | 代码格式门禁 | `mvn -B -ntp spotless:check`（相对 `origin/main` 增量） | 同上 |
| 3 | 单元测试 | `mvn -B -ntp clean test` | 不可 |

**注意**：CI 检查的是 PR 分支上**全部相对 main 的新增提交**，不是只看最新一条。历史 commit message 不合规也需要改写历史。

**触发条件**：

- `pull_request` → `main`：三道闸都跑
- `push` → `main` / `ca_branch`：不校验 commit message，仍跑 Spotless + 测试

---

## 二、标准提交流程（推荐每次照做）

在项目根目录 `Intelligent-Collection-V1/` 执行：

```powershell
cd d:\AI\Intelligent-Collection-V1
git fetch origin

# 1. 自动格式化（必做）
mvn spotless:apply

# 2. 本地验证（可选但强烈建议）
mvn spotless:check
mvn test

# 3. 提交
git add -u
git commit -m "feat(admin): 你的功能描述"

# 4. 推送
git push origin channel_0706
```

### 提交前自检清单

- [ ] 已执行 `mvn spotless:apply`
- [ ] commit message 符合 `<type>(<scope>): <subject>`（**冒号后有空格**）
- [ ] scope 只用小写字母、数字、`.`、`_`、`-`（**不能用 `/`**）
- [ ] 新增 `@Resource` 依赖的类，单测里已 mock 注入或代码有空安全兜底
- [ ] 本地 `git status` 干净，无 rebase 冲突 / detached HEAD

---

## 三、Commit Message 规范

### 格式

```text
<type>(<scope>): <subject>
```

### type 允许值

`feat` | `fix` | `docs` | `style` | `refactor` | `perf` | `test` | `build` | `ci` | `chore`

### 正则（与 CI 一致）

```regex
^(feat|fix|docs|style|refactor|perf|test|build|ci|chore)(\([a-z0-9._-]+\))?!?: .+
```

### 正确 / 错误示例

| 写法 | 结果 |
|------|------|
| `feat(admin): 新增文案模板热更新功能` | ✅ |
| `feat(admin):新增文案模板热更新功能` | ❌ 冒号后缺空格 |
| `更新后台设计文档` | ❌ 缺少 type |
| `feat(config/service): 补全占位文案` | ❌ scope 含 `/` |
| `feat(config-service): 补全占位文案` | ✅ 用 `-` 代替 `/` |
| `docs: 对齐规格文档` | ✅ scope 可省略 |
| `Merge origin/main: ...` | ✅ 自动跳过 |

### 本地模拟 CI 提交信息检查

```powershell
$pattern = '^(feat|fix|docs|style|refactor|perf|test|build|ci|chore)(\([a-z0-9._-]+\))?!?: .+'
git fetch origin
git log --format=%s origin/main..HEAD | ForEach-Object {
    if ($_ -notmatch '^(Merge |Revert )' -and $_ -notmatch $pattern) {
        Write-Host "FAIL: $_" -ForegroundColor Red
    }
}
```

---

## 四、Spotless 格式规范

### 项目配置要点

- 插件：`spotless-maven-plugin` 2.30.0
- 格式化器：Google Java Format 1.7，**AOSP 风格（4 空格）**
- 增量范围：`ratchetFrom origin/main`（只检查相对 main 改动过的 Java 文件）
- 行尾：`.gitattributes` 强制 `* text=auto eol=lf`

### 常见不合规项

| 问题 | 说明 |
|------|------|
| import 顺序 | `java.*` / `javax.*` 在 `org.*` 之前 |
| Javadoc 换行 | 长注释需按 100 列规则断行，不能随意手动换行 |
| 超长行断行 | 方法链、`@Insert` SQL 拼接、三元表达式需按 GJF 规则折行 |
| 多余空行 | 类内、文件末尾多余空行会被删 |
| `@Resource` 与字段 | 有时要求合并为 `@Resource private Foo bar;` |

### 常用命令

```powershell
# 自动修复（首选）
mvn spotless:apply

# 只检查不修改
mvn spotless:check

# 只处理某个模块
mvn spotless:apply -pl collection-channel -am
mvn spotless:check  -pl collection-channel -am

# 从某个模块继续（CI 报错提示）
mvn spotless:check -rf :collection-channel
```

### 本地模拟 CI 格式检查

```powershell
git fetch --no-tags origin +refs/heads/main:refs/remotes/origin/main
mvn -B -ntp spotless:check
```

---

## 五、单元测试注意事项

CI 第三步 `mvn clean test` 失败时，日志里可能是 **Tests run / Errors**，不是 Spotless。

### 典型坑：单测未注入 Spring 依赖

**现象**：

```text
ScriptLibrarySmsSmokeTest.renderSms_... ? NullPointer
  at ScriptLibrary.renderSms(ScriptLibrary.java:70)
```

**原因**：生产代码新增了 `@Resource ConfigTemplateProvider`，单测用反射只注入了 `channelProperties`，`templateProvider` 为 null。

**修复方式（二选一）**：

1. **代码空安全**（推荐，适合可选 DB 降级场景）：

```java
String tpl = templateProvider != null ? templateProvider.getSms(scriptSlot) : null;
```

2. **单测补注入**：在 `@BeforeEach` 里反射设置 mock 的 `templateProvider`。

### 本地文件被占用导致 clean 失败

**现象**：

```text
Failed to delete collection-admin\target\collection-admin.jar
unable to unlink admin-sms-prod.log.err
```

**处理**：

```powershell
# 查找占用进程
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'collection-admin' }

# 结束进程（替换 PID）
Stop-Process -Id <PID> -Force

# 再跑测试；若 clean 仍失败，可跳过 clean 先验证
mvn test
```

---

## 六、历史 Commit Message 不合规：如何改写

仅新建一条正确 commit **无法**通过 CI，必须改写 Git 历史后 force push。

### 方案 A：交互式 rebase（保留提交粒度）

```powershell
git fetch origin
git rebase -i origin/main
```

把不合规行的 `pick` 改成 `reword`，保存后逐条修改 message，然后：

```powershell
git push --force-with-lease origin channel_0706
```

### 方案 B：filter-branch（批量改指定 SHA 的 message）

适合已知 SHA 的批量修正，不 replay 代码（无 merge 冲突风险）：

```bash
# 在 Git Bash 中执行
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f \
  --msg-filter 'sh /path/to/git-msg-filter.sh' \
  HEAD --not origin/main
```

### 方案 C：压成一条 commit（最快，丢历史）

```powershell
git fetch origin
git reset --soft origin/main
git commit -m "feat(channel): Phase1 联调成果"
git push --force-with-lease origin channel_0706
```

---

## 七、本地仓库状态异常：恢复步骤

### 症状

- `HEAD (no branch)` / detached HEAD
- `UU` / `DU` merge 冲突
- `interactive rebase in progress`
- 存在多个 worktree（`channel_0706` 被占用）

### 标准恢复（在项目根目录）

```powershell
cd d:\AI\Intelligent-Collection-V1

# 1. 停掉占用 jar / 日志的 Java 进程
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'collection-admin' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# 2. 移除额外 worktree（若有）
git worktree list
git worktree remove <path> --force
git worktree prune

# 3. 强制对齐远端分支（会丢弃本地未提交改动）
git fetch origin
Remove-Item -Force admin-sms-prod.log.err -ErrorAction SilentlyContinue
git clean -fd
git reset --hard origin/channel_0706
git switch -C channel_0706 origin/channel_0706
git status -sb
```

恢复后应看到：

```text
## channel_0706...origin/channel_0706
```

---

## 八、CI 仍失败时的排查顺序

1. **确认 GitHub Actions 对应的 commit SHA**  
   页面上的失败 run 必须是你刚 push 的最新 SHA，不要看旧 run。

2. **看失败步骤名称**  
   - `Check commit messages` → 第三节  
   - `Check formatting (Spotless)` → 第四节  
   - `Build & run unit tests` → 第五节

3. **本地完整模拟 CI**

```powershell
git fetch origin
# 提交信息（PR 才检查）
# → 见第三节 PowerShell 脚本

# 格式
mvn -B -ntp spotless:check

# 测试
mvn -B -ntp clean test
```

4. **修复 → 提交 → 推送**

```powershell
mvn spotless:apply          # 若是格式问题
mvn test                    # 若是单测问题
git add -u
git commit -m "style(format): 修复 CI 格式门禁"
git push origin channel_0706
```

---

## 九、快速命令速查

| 场景 | 命令 |
|------|------|
| 提交前格式化 | `mvn spotless:apply` |
| 模拟 CI 格式 | `mvn -B -ntp spotless:check` |
| 模拟 CI 测试 | `mvn -B -ntp clean test` |
| 只看某模块测试 | `mvn test -pl collection-channel -am` |
| 恢复干净分支 | `git reset --hard origin/channel_0706` |
| 安全强推 | `git push --force-with-lease origin channel_0706` |
| 查看 PR 变更文件 | `git diff --name-only origin/main..HEAD` |

---

## 十、相关文件索引

| 文件 | 说明 |
|------|------|
| `.github/workflows/ci.yml` | CI 流水线定义 |
| `.githooks/commit-msg` | 本地提交信息校验 |
| `.gitattributes` | LF 行尾策略 |
| `pom.xml` | Spotless 插件与 `ratchetFrom` 配置 |
| `docs/testing/MOCASA催收系统升级_Phase1_测试文档.md` | 测试 SSOT（含 CI 工作流引用） |

---

## 附录：一次真实排障时间线（2026-07）

| 阶段 | 现象 | 根因 | 处理 |
|------|------|------|------|
| 1 | commit message CI 失败 | 历史提交缺 type、scope 含 `/` | `filter-branch` 改写 4 条 message + force push |
| 2 | Spotless 逐模块失败 | 新代码未跑 `spotless:apply` | 分模块 apply 并推送 `style(*)` commit |
| 3 | 本地混乱、修复反复失败 | detached HEAD + rebase 冲突 + worktree 占用 + jar 锁文件 | 停进程、删 worktree、`git reset --hard origin/channel_0706` |
| 4 | 29882e3 仍报 Spotless | 实为旧 run 或已进入 test 阶段 | 本地 `spotless:check` 已通过；修复 `ScriptLibrary` NPE 后推送 `1d8f06a` |

**经验结论**：不要创建多个临时 clone/worktree 绕问题；直接在主仓库恢复干净状态 → `spotless:apply` → `mvn test` → 推送，是最稳路径。
