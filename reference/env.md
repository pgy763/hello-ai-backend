# 环境变量速查

所有示例都遵循同一条规矩：**密钥只从环境变量读，不落进任何文件。**

## 一、本书示例用到的全部变量

| 变量 | 用于 | 不设时的行为 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 第 2、3 章（真实调用） | 相关示例退回替身模式 |
| `HELLOAI_MODE` | 第 14 章 | 默认 `fake`（不联网） |
| `HELLOAI_BASE_URL` | 第 14 章 | 默认一个示例地址，需自己改 |
| `HELLOAI_API_KEY` | 第 14 章 | 空 → real 模式直接报错，不发请求 |
| `HELLOAI_MODEL` | 第 14 章 | 默认示例模型名 |
| `HELLOAI_SMALL_MODEL` | 第 14 章降级 | 不设则与主模型相同 |

**其余 10 个示例完全不需要环境变量** —— 它们用假上游，克隆下来就能跑。

## 二、怎么设（按场景）

### PowerShell（只在当前窗口生效）

```powershell
$env:HELLOAI_MODE = "real"
$env:HELLOAI_API_KEY = "sk-你的key"
mvn spring-boot:run

# 用完立刻清
Remove-Item Env:\HELLOAI_API_KEY, Env:\HELLOAI_MODE
```

### bash / zsh

```bash
export HELLOAI_MODE=real
export HELLOAI_API_KEY="sk-你的key"
mvn spring-boot:run

unset HELLOAI_API_KEY HELLOAI_MODE
```

### 只对一条命令生效（最推荐）

```bash
HELLOAI_MODE=real HELLOAI_API_KEY="sk-你的key" mvn spring-boot:run
```

进程退出，变量自动消失，不会留在终端历史之外的地方。

### IDEA / VS Code

在运行配置的 Environment variables 里填。**注意不要把填好的运行配置提交**（`.idea/` 已在 `.gitignore`）。

## 三、四条红线

| 不要做 | 为什么 | 该怎么做 |
| --- | --- | --- |
| 写进 `application.yml` | 会被提交 | 用 `${ENV_VAR:}` 占位 |
| 写进 `application-local.yml` | 虽然 gitignore 了，但仍是文件 | 优先环境变量 |
| 贴进 Issue / 聊天 / 截图 | 一旦贴出就当泄漏 | 立刻**吊销**，不是删文件 |
| 提交前 `git add .` 不检查 | 一次疏忽永久留在历史 | `git diff --cached` 看一眼 |

**吊销比清理重要**：清理历史要几小时，吊销只要一分钟。而且历史可能已经被 clone 走了。

## 四、自查命令

提交前扫一遍，看有没有 `sk-` 形态的字符串（`sk-xxxx` 这类占位符除外）：

```powershell
Select-String -Path (Get-ChildItem -Recurse -File -Include *.java,*.yml,*.yaml,*.md |
  Where-Object { $_.FullName -notmatch '\\target\\|\\node_modules\\|\\.git\\' }) `
  -Pattern 'sk-(?!x{8,})[A-Za-z0-9]{16,}'
```

```bash
grep -rnE 'sk-(x{8,})?[A-Za-z0-9]{16,}' --include='*.java' --include='*.yml' --include='*.md' . \
  | grep -v target/
```

没有输出就是干净的。

## 五、CI 里的变量

GitHub Actions 里用 **Secrets**，不要写进 workflow 文件：

```yaml
- name: Run
  env:
    HELLOAI_API_KEY: ${{ secrets.HELLOAI_API_KEY }}
  run: mvn spring-boot:run
```

本书的 CI（`.github/workflows/examples.yml`）**刻意不配任何密钥** —— 11 个模块都是离线可跑的，这条约束本身就是质量保证：如果一个示例非要 key 才能编译，它就跑不进 CI。

## 相关章节

- 第 4 章：虚拟密钥与租户额度
- 第 12 章：模式 A（密钥进 git 历史怎么处理）
- 第 14 章：real 模式的完整用法