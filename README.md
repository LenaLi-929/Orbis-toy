# PD01 MCP Bridge

让 AI 软件（如 Orbis）通过 MCP 直连 PD01 热敏打印机打印的 Android 桥接 App。
**已实测可用**：中文/英文/多行文本、图片，手机 OPPO Reno13 (ColorOS, Android 16) 验证通过。

```
Orbis (AI) --MCP/Streamable HTTP--> 桥接App(:8790/mcp) --BLE--> PD01 热敏打印机
```

---

## 一、快速开始（三步）

1. 安装 `PD01-MCP-Bridge-v2.3.apk`（或最新版），打开 App 授予蓝牙/通知权限；
2. 点 **① 启动桥接服务** → 点 **⑥ 保存 MAC 并测试连接**（填入打印机 MAC）→ 看到「✓ 已连接」；
3. Orbis → MCP 配置 → 新增服务器：
   - 传输类型：**Streamable HTTP**
   - 地址：`http://127.0.0.1:8790/mcp`

连上后 Al 就有了 `bt_print_text` 等工具，直接说"用 bt_print_text 打印：hello"。

---

## 二、日常使用仪式（重要）

热敏打印机同时只能被一个应用连接，且 ColorOS 会冻结后台进程，所以：

| 应用 | 要求 |
|---|---|
| 厂商官方 App（喵喵机等） | **从最近任务划掉**，绝不能让它在后台占着打印机 |
| 桥接 App | **必须活着**：①启动服务 + ⑤缩成悬浮小窗（或分屏挂在前台） |
| Orbis | 正常使用 |

> ⚠️ 打印时若桥接 App 处于普通后台（被冻结），工具调用会卡住并提示"生成中断"。
> 挂小窗（画中画）即可完全避免——它对系统而言是"前台可见"，不会被速冻。

---

## 三、OPPO / ColorOS 必做设置

速冻是这个项目最大的敌人，务必完成：

1. App 内点 **④ 申请忽略电池优化** → 系统弹窗选「允许」；
2. 设置 → 应用 → PD01 MCP Bridge → 电池 → **允许完全后台行为 / 不优化**；
3. 设置 → 应用 → PD01 MCP Bridge → 打开 **自启动**；
4. 最近任务 → 长按桥接 App 卡片 → **加锁 🔒**；
5. 打印时优先用 **⑤ 悬浮小窗**。

---

## 四、MCP 工具清单

| 工具 | 参数 | 说明 |
|---|---|---|
| `bt_print_text` | `text`, `font_size?`(默认48) | 打印文本，按真实像素宽度自动折行，支持中文/中英混排 |
| `bt_print_image` | `path` | 打印本地图片，自动缩放到 384 宽 + 二值化 |
| `bt_print_test` | — | 打印测试图案（黑条+棋盘格），排查传输/渲染问题 |
| `bt_feed` | `lines` | 走纸 n 行 |
| `bt_scan` | — | 扫描附近打印机 |
| `bt_send_hex` | `hex` | 发送任意协议十六进制包（调试用） |

提示词示例：

> 用 bt_print_text 打印：今日清单\n- 鸡蛋\n- 牛奶

---

## 五、参数调整（重新编译生效）

| 想改什么 | 改哪里 |
|---|---|
| 打印偏淡 / 偏黑 | `Pd01Protocol.kt` → `setEnergy(0xFFFF)`，调大更黑（0x0000~0xFFFF） |
| 仍丢行 | `BlePrinter.kt` → `rowDelayMs`，15 → 25 |
| 打印太慢 | `BlePrinter.kt` → `rowDelayMs`，15 → 10 |
| 默认字号 | `McpServer.kt` → `args.optInt("font_size", 48)` |

---

## 六、常见问题排查

| 现象 | 原因 | 解决 |
|---|---|---|
| Orbis 报 Connect timeout | 服务没起来 / 被速冻 | 回 App 点①启动，挂小窗，②自检确认 ✓ |
| Orbis 报 `Unexpected content type: text/plain` | 旧版协议 bug | 升级到 v1.4+ |
| 提示"扫描也没搜到设备" | 未保存 MAC，走了扫描 | ⑥ 保存 MAC 并测试 |
| 打出白纸 / 只有虚线 | 渲染坐标 bug 或丢行 | v2.0 修渲染，v2.2 修丢行 |
| 打出来全是 `�` | 请求体 UTF-8 解码错误 | v2.3 修复 |
| 只打出零星横道 | BLE 大块发送丢行 | 升级 v2.2+，或调大 `rowDelayMs` |
| 连接打印机失败 | 打印机被厂商 App 占用 / 已休眠 | 划掉厂商 App，唤醒打印机 |
| 手机浏览器连 127.0.0.1 超时 | 国产浏览器走云端代理 | 用系统浏览器或 App 内②自检验证 |

---

## 七、技术细节

- **协议**：PD01/cat printer 私有 `0x5178` 协议
  帧格式 `[0x51][0x78][CMD][0x00][LEN_LO][LEN_HI][DATA...][CRC8][0xFF]`，CRC-8 (poly 0x07, init 0)
  服务 UUID `0000ae30`，写特征 `0000ae01`（write-no-response），384 点宽，1bpp LSB-first
- **传输**：逐帧发送（每行独立帧）+ 15ms 节流，避免打印机来不及处理丢行
- **编码**：HTTP 请求体自行按 Content-Length 读原始字节 + 显式 UTF-8 解码（NanoHTTPD 默认解码不可靠）
- **MCP**：Streamable HTTP，仅监听本地；GET 返回 405 使客户端降级为纯 POST 模式
- 参考实现：rhnvrm/catprinter、rbaron/catprinter、WerWolv/PythonCatPrinter

---

## 八、构建

### 方式 A：GitHub Actions 云端自动构建（推荐，无需本机环境）

1. GitHub 新建仓库（**Private 免费，推荐**）；
2. 上传本目录**全部文件**（注意 `.github` 隐藏文件夹必须包含，否则不会自动构建）；
3. 仓库页 → **Actions** → 最新一次 "Build APK" → 完成后在 **Artifacts** 下载 `pd01-mcp-bridge-debug`，解压即得 APK。

命令行推送：

```bash
cd pd01-mcp-bridge
git init && git add . && git commit -m "init"
git branch -M main
git remote add origin https://github.com/你的用户名/仓库名.git
git push -u origin main
```

### 方式 B：本地构建

需 JDK 17 + Android SDK 34：

```bash
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

---

## 九、版本历史

| 版本 | 修复内容 |
|---|---|
| v1.0-1.3 | 初版：协议、MCP server、前台服务、看门狗 |
| v1.4 | 全部响应改 application/json（解决 SDK 握手失败） |
| v1.5 | WakeLock + 电池优化白名单（对抗速冻） |
| v1.6 | 画中画悬浮小窗（最终解决冻结问题） |
| v1.7 | 修复扫描过滤漏掉 PD01 |
| v1.8 | MAC 直连，完全绕过扫描 |
| v1.9 | 修复"先启动服务后保存 MAC"的时序 bug |
| **v2.0** | **修复文字渲染基线坐标（之前字画到位图外）** |
| v2.1 | 新增 bt_print_test 测试图案 |
| **v2.2** | **逐帧发送 + 15ms 节流，修复丢行** |
| **v2.3** | **显式 UTF-8 解码，修复中文乱码** |

---

## 十、文件结构

```
app/src/main/java/com/shadowbt/
├── Pd01Protocol.kt   # 0x5178 协议帧 + CRC8 + 位图打包
├── BlePrinter.kt     # BLE 连接/逐帧写 + 文本图片渲染
├── McpServer.kt      # 本地 MCP Streamable HTTP server（UTF-8 安全）
├── BridgeService.kt  # 前台服务 + 看门狗 + WakeLock
└── MainActivity.kt   # 授权/启动/自检/扫描/MAC 配置/小窗
```

License: GPL-3.0
