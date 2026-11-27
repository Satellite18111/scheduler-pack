# 调度包（Scheduler Pack）

一个很轻的 Android root 调度前端：**不自己调度，只当 uperf 的遥控器 + 看板**。
界面是 WebView 套一张单页 HTML，原生侧只有几个 Java 类负责 root shell、悬浮窗、通知和日志。

- 包名：`dev.local.netspeed`
- 版本：2.1.0（versionCode 3），minSdk 26 / targetSdk 34
- 验证设备：iPlay30 平板（MT6771V/W，8 核 Mali-G72 MP3），LineageOS 21 GSI（Android 14），Magisk root
- 依赖：**零第三方库**，只用 `android.jar`；构建不依赖 Gradle

## 它做什么

| 能力 | 实现方式 |
| --- | --- |
| 五档调度切换（auto / balance / powersave / performance / fast） | 写 `/sdcard/Android/yc/uperf/cur_powermode.txt` |
| 分应用调度、熄屏规则、默认规则 | 读写 `perapp_powermode.txt` |
| 悬浮窗叠加网速 / FPS / CPU 频率 / GPU 频率 / 温度 | 前台服务 + `TYPE_APPLICATION_OVERLAY` |
| 常驻通知 + 三个快捷切档按钮 | `MonitorService` |
| 可拖动的原生调度面板 | 自绘深色卡片窗口，位置记在 `ns.xml` |
| 实时监控（FPS、负载、逐核频率、内存、电池、温度） | 每秒读 sysfs / `dumpsys SurfaceFlinger` |
| 帧率记录导出 CSV | 9 列采样，落 `/sdcard/NetSpeedLogs/perf-*.csv` |
| 充电曲线 + 充电阈值提醒 | 读 `battery/`，到阈值发通知（不断电，见限制） |
| 温度保护 | 超阈值降档，回落 3°C 恢复 |
| 诊断日志导出 | crash / ANR / system / dmesg 打包 |

## 它不是

- 不是调频工具。`scaling_min_freq` / `scaling_max_freq` 在本机是 `0444` 只读，MTK 的 `/proc/ppm/policy/*` 被 uperf 占用，所以**没有做频率滑杆**，只做只读展示。
- 不是充电控制器。本机内核没有 `charging_enabled` / `input_suspend` 这类停充节点，充电阈值只能提醒。
- 不是 Scene / VTools 的移植版，也不含它们任何代码，详见下面的许可说明。

## 架构

```
scheduler-web/
├─ assets/www/
│  ├─ index.html            # 全部界面：五个 tab、Canvas 曲线、开关、弹层、toast
│  └─ icon.png              # 由 res 图标复制，header 使用
├─ src/dev/local/netspeed/
│  ├─ MainActivity.java     # WebView 宿主，注入 Native 桥
│  ├─ NativeBridge.java     # 每秒快照回传、切档、规则、偏好、CSV/日志导出
│  ├─ MonitorService.java   # 悬浮窗、通知、调度面板、充电与温控自动化
│  ├─ PerAppRules.java      # perapp_powermode.txt 解析/写回
│  ├─ InfoCollector.java    # SoC / CPU / GPU 静态信息
│  ├─ ModeUtil.java         # 五档常量与索引
│  └─ BootReceiver.java     # 开机拉起服务
├─ res/mipmap-*/            # 图标五密度
├─ AndroidManifest.xml
├─ build.ps1                # aapt2 → javac → d8 → zipalign → apksigner
└─ docs/                    # 审计与实测记录
```

原生与网页的数据约定：`window.__ev(<json>)` 单向推送，`type` 为 `snap` / `info` / `apps` / `log` / `toast`；
shell 采样用自定的 `R:/T:/C:/G:/F:/CT:/BT:/BS:/P:` 标记 + `__NS_END__` 结束符，避免 `dumpsys` 输出干扰解析。

## 构建

环境：JDK 17+（本项目用 JDK 25 编译 `--release 17`）、Android SDK `build-tools` 与 `platforms/android-<api>`，不需要 Gradle。

```powershell
# 修改 build.ps1 顶部三个变量指向本机 SDK 路径
$root = "…\scheduler-web"
$bt   = "…\android-sdk\build-tools\35.0.0"
$jar  = "…\android-sdk\platforms\android-35\android.jar"

.\build.ps1
adb install -r .\调度包-2.1.0.apk
```

两个已知坑：

1. 资源先复制到 `%TEMP%` 再 `aapt2 compile`，否则中文路径会失败；
2. **不要用 `aapt2 link -A assets`**，Windows 下会把分隔符写成反斜杠，WebView 报 `ERR_FILE_NOT_FOUND`；脚本改为链接后用 `System.IO.Compression` 以正斜杠写入 `assets/www/index.html`。

仓库里的 `debug.keystore` 是 debug 签名（口令均为 `android`），只用于本机覆盖升级。要对外分发请换自己的密钥重新签名。

## 数据源

| 项 | 路径 |
| --- | --- |
| 当前模式 | `/sdcard/Android/yc/uperf/cur_powermode.txt` |
| 分应用规则 | `/sdcard/Android/yc/uperf/perapp_powermode.txt` |
| 逐核负载 | `/sys/devices/system/cpu/rq-stats/cpu_normalized_load` |
| 逐核频率 | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` |
| GPU 占用 / 频率 | `/sys/module/ged/parameters/gpu_loading`、`/sys/kernel/debug/clk/mfg_sel/clk_rate` |
| CPU 温度 | thermal_zone type `mtktscpu` |
| 电池 | `/sys/class/power_supply/battery/{current_now,voltage_now,temp,capacity,status}` |
| FPS | `dumpsys SurfaceFlinger` 的 FPS ring buffer 最新一条 |
| 偏好 | `/data/data/dev.local.netspeed/shared_prefs/ns.xml` |

## 已知限制（都是实测结论，不是没做）

- 逐核频率与上限**只读**：本机 cpufreq 的 `scaling_*_freq` 是 `0444`；PPM 节点被 uperf 抢占，连写 6 次上限在 1131→1625 MHz 之间自行跳变，写了等于没写。
- 充电阈值**只提醒不断电**：`battery/` 下无 `charging_enabled` / `input_suspend`；`en_power_path` 写 0 后 `status` 仍是 Charging；`input_current` 写入会被截断（写 100 读回 256）。
- GPU 空闲读 0%，是 `gpu_loading` 本身行为；静态页面 FPS 会掉到个位数，因为 SurfaceFlinger 只在有帧时计数。
- 采样是每秒起一次 `su` shell，比常驻 daemon 费电，精度也只有 1 秒级。
- 只在上述设备与 ROM 上验证过。换机型、换内核、换 uperf 版本都可能失效。

## 许可

本项目采用 **MIT License**（见 [LICENSE](LICENSE)）：可自由使用、修改、商用、再分发，保留版权声明即可，无 copyleft 义务。

与几个同名项目的真实关系，避免误读：

| 项目 | 许可 | 与本项目的关系 |
| --- | --- | --- |
| [chihqiang/vtools](https://github.com/chihqiang/vtools) | Apache-2.0 | 独立的 Vue 网页工具站，**只借鉴「卡片式工具集」的界面形态**，无代码关系 |
| [helloklf/vtools](https://github.com/helloklf/vtools)（Scene / 骁龙工具箱） | GPL-3.0 | **只对齐功能清单**，未引用任何源码、未引入任何二进制；调度能力由 uperf 提供 |
| [yc9559/uperf](https://github.com/yc9559/uperf) | Apache-2.0 | 真正的调度内核。本项目只读写它的文本配置，属外部互操作，不链接其代码 |

审计过程与证据见 [docs/GPL-审计-2026-11-27.md](docs/GPL-审计-2026-11-27.md)：dex 内 40 个类全为 `Ldev/local/netspeed/…`，`omarea` / `helloklf` / `vtools` / `kotlin` 命中数为 0。

## 免责声明（请务必读）

1. **作者与来历**：本项目的界面与代码由 AI 助手（豆包）在作者指导下编写，作者在自有设备上逐项实测验证。它是「AI 写代码 + 人验收」的产物，不是传统意义上的手工项目。
2. **高风险操作**：本项目需要 root，会读写系统 sysfs、常驻前台服务、创建悬浮窗、执行 `su` shell。**只在你自己拥有、愿意承担风险的设备上使用**。刷机、变砖、数据丢失、硬件损坏、保修失效，均与作者无关。
3. **按现状提供**：MIT 许可以「AS IS」提供，不附带任何明示或默示担保，包括但不限于适销性、特定用途适用性、无侵权。
4. **机型与 ROM 差异**：代码里写死的 sysfs 路径与 uperf 文件格式来自上述测试机。别的设备上取不到数、切档无效、甚至异常耗电都属正常，作者不保证兼容。
5. **不要用于他人设备或商业交付**：涉及 root 与调度参数的改动会直接影响设备稳定性，用于客户机器或量产环境风险自负。
6. **图标素材**：`res/mipmap-*/ic_launcher.png` 为通用 3D 卡通形象，若你需要对外发布，请自行确认素材来源与授权。

## 反馈

有问题请开 [Issue](https://github.com/Satellite18111/scheduler-pack/issues)，附上机型、ROM、`adb logcat -b crash -d` 以及「更多 → 导出诊断日志」生成的 `diag-*.txt`，比只说「用不了」有用得多。
功能请求也欢迎，但请先看上面的「已知限制」——频率锁频和充电断电在测试机上做不到，不是漏做。
