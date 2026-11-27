# 调度包 2.1（网页 UI 版）

形态参考 [chihqiang/vtools](https://github.com/chihqiang/vtools)（Vue 写的网页工具站，Apache-2.0）的「卡片式工具集」布局思路，
不再用原生控件堆页面，改成 **WebView + 单页 HTML/CSS/JS**，原生只保留必要能力（root shell、uperf 调度、悬浮窗服务、通知、日志导出）。

> 两个同名仓库容易混：`chihqiang/vtools` 是网页工具站（Apache-2.0，与本项目无代码关系，只借鉴界面形态）；
> `helloklf/vtools` 是 Scene 骁龙工具箱（GPL-3.0，本项目只对齐功能清单、不引用任何源码、不引入其二进制）。
> 详见 `docs/GPL-审计-2026-11-27.md`。

## 结构

```
scheduler-web/
├─ assets/www/index.html      # 全部界面（深色卡片、五个 tab、Canvas 曲线、开关/弹层）
├─ src/dev/local/netspeed/
│  ├─ MainActivity.java       # WebView 宿主 + 注入 Native 桥
│  ├─ NativeBridge.java       # 快照/切模式/分应用规则/偏好/日志导出
│  ├─ MonitorService.java     # 悬浮窗、通知、调度面板、充电与温控自动化（沿用）
│  ├─ PerAppRules.java        # perapp_powermode.txt 读写（沿用）
│  ├─ InfoCollector.java      # SoC/CPU/GPU 静态信息（沿用）
│  ├─ ModeUtil.java / BootReceiver.java
├─ res/mipmap-*/ic_launcher.png
└─ build.ps1                  # aapt2 + javac + d8 + zipalign + apksigner（无需 Gradle）
```

包名仍是 `dev.local.netspeed`，与旧版同签名，`adb install -r` 即覆盖升级，root 授权、悬浮窗权限、偏好与分应用规则都保留。

## 界面

- **首页**：当前模式大字 + 五档快捷切换 + 六张工具卡（实时监控 / 分应用调度 / 悬浮窗 / 日志导出 / 自动化 / 设备信息）。
- **调度**：五档单选、分应用默认规则与熄屏规则、弹出面板与重启服务。
- **监控**：FPS / CPU 负载 / GPU 占用 / 最高频率四个数字块，60 秒 Canvas 曲线，八核频率横条，内存、电池电流电压、CPU 与电池温度，帧率记录与 CSV 导出。
- **应用**：搜索 + 应用列表，点开弹层选档或「跟随默认」。
- **更多**：六个悬浮窗显示项、充电与温控自动化、诊断日志导出、设备信息。

## 构建

```powershell
cd E:\项目\bilibili\scheduler-web
.\build.ps1
adb install -r .\调度包-2.1.0.apk
```

- 工具链：JDK 25 + `E:\bili-build\android-sdk\build-tools\37.0.0` + `platforms\android-37`。
- 资源先拷到 `%TEMP%\spres` 再 `aapt2 compile`（中文路径问题）。
- **assets 不要用 `aapt2 link -A`**：Windows 下会把目录分隔符写成反斜杠，WebView 找不到 `assets/www/index.html`；
  `build.ps1` 改为链接后按正斜杠写入 zip 条目。

## 数据源

| 项 | 来源 |
| --- | --- |
| 调度模式 | `/sdcard/Android/yc/uperf/cur_powermode.txt`（写文件即热切换） |
| uperf 守护 | `ps -A -o NAME | grep -c '^uperf$'` |
| 逐核负载 | `/sys/devices/system/cpu/rq-stats/cpu_normalized_load` |
| 逐核频率 | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` |
| GPU 占用 | `/sys/module/ged/parameters/gpu_loading` |
| FPS | `dumpsys SurfaceFlinger` 的 FPS ring buffer 最新一条 |
| 温度 | thermal_zone `mtktscpu` / `battery/temp` |
| 分应用规则 | `/sdcard/Android/yc/uperf/perapp_powermode.txt` |

## 已知限制

- GPU 空闲时读 0%，属 `gpu_loading` 本身行为；FPS 在静态页面会掉到个位数（SurfaceFlinger 只在有帧时计数）。
- 面板是原生 View（沿用旧服务），未跟随网页主题；打开面板时会按偏好回填开关。
- 模式、分应用规则依赖 root；无 root 时只能看数据。

## 修复记录 2026-11-26（设置开关点不动）

- 现象：「更多」页悬浮窗/自动化开关，手指点在右侧开关本体区域无效，点同行文字区有效。
- 实测：设备横屏 2000×1200，x≈1880 之后约 120px 的右边缘竖条会吞掉触摸（系统手势/边缘区域），而开关原来贴着卡片右边缘（中心 x≈1860、右端 x≈1936），大部分落在死区里。
- 修复：`.row/.modeitem/.approw` 增加 `padding-right:58px`，开关与右上角档位标签整体内缩约 100px（屏幕像素），落在可点区域；`.sw` 保持 `pointer-events:none`，整行即热区。
- 顺带修：调度页在快照到达前就渲染，导致「全局模式」单选全空、状态显示「uperf 未运行」。新增 `syncSched()`，在每次快照和切页时同步单选/状态/默认规则文本。

## 2026-11-26 二次调整：左上角换成程序图标

- 左上角原来的滑杆图标只是占位，被点一下跳「更多」页容易误解为设置入口。
- 现改为直接显示应用图标：`res/mipmap-xxxhdpi/ic_launcher.png` 复制为 `assets/www/icon.png`，header 用 `<img class="logo">` 渲染（52px 圆角），构建脚本自动枚举 assets 目录，无需改打包逻辑。
- 同时移除 `.brand` 的点击行为，品牌区只作标识，不再兼任跳转。

## 2026-11-26 三次调整：原生调度面板换皮

- 旧面板是系统默认灰色 LinearLayout + RadioGroup + Switch，跟网页主题完全不搭。
- 重写为深色圆角卡片：`roundBg()` 画圆角/描边，自绘 `ToggleSwitch`（圆角轨道+白滑块），档位改成「圆点 + 中文 + 英文」的行式单选，选中行高亮蓝底+加粗，关掉窗口右上角 ✕，底部一个「关闭面板」幽灵按钮。
- 面板固定宽度 336dp、位置 (14dp, 44dp)，避免超出可用高度被系统裁掉。
- 开关整格可点（不只是那个小开关），面板打开时按 `ns.xml` 回填状态，和网页端保持一致。

## 2026-11-26 四次调整：面板可拖动

- 面板本身是 TYPE_APPLICATION_OVERLAY 窗口，位置写在 LayoutParams 里，拖动即改 lp.x/lp.y 后 updateViewLayout。
- 拖动热区放在标题栏和面板空白区（`panel.setOnTouchListener` + `head` 同款监听），档位行/开关格/关闭按钮这些可点控件不受影响。
- 位移超过 8px 才算拖动，松手写 `panelX`/`panelY` 到 ns.xml，下次弹出沿用上次位置。

## 2026-11-27 v2.1：许可口径与新增监控项

### 许可口径（重要）

- 参考对象更正为 [helloklf/vtools](https://github.com/helloklf/vtools)（即 Scene / 骁龙工具箱），其 LICENSE 为 **GPLv3**。
- 本项目**不引用其任何源码**，只对齐功能思路：调度走 uperf 数据文件（`cur_powermode.txt` / `perapp_powermode.txt`），监控与提醒直读 sysfs。接口与文件格式不构成衍生作品，因此本项目不受 GPL 传染。
- 如需引入 Scene 的代码，整个工程必须改为 GPLv3 并公开源码；分发 APK 才触发义务，自用不算。

### 新增

- **帧率记录升级**：每行记录 `时间,FPS,CPU 负载,GPU,最高频率,CPU 温度,电池温度,电流,电量`，上限 600 条，导出 `perf-<时间戳>.csv` 到 `/sdcard/NetSpeedLogs/`；统计行给出平均值、1% 低帧、卡顿条数。
- **充电卡片**：120 秒双线曲线（电流 mA / 电量 %），行内显示充电状态、电量、电流电压。
- **充电阈值提醒**：`chargeAlert` + `chargeLimit`（50~100%，默认 80%），由 MonitorService 在后台每秒检测电量与 `status`，达到阈值发一条 `ns-alert` 通知（同一充电周期只提醒一次，拔出或掉 3% 复位）。
- **频率范围只读展示**：小核 0-3 与大核 4-7 的 `scaling_min_freq` / `scaling_max_freq`，明确标注由 uperf 托管。

### 为什么没有做「CPU 频率上下限」滑杆

实测本机（MT6771 / LineageOS 21 GSI）无法可靠改频：

- `.../cpufreq/policy{0,4}/scaling_min_freq` 与 `scaling_max_freq` 权限是 `0444`，`echo` 直接 Permission denied；`scaling_governor` 可写但同样被 uperf 覆盖。
- MTK 的 `/proc/ppm/policy/hard_userlimit_cpu_freq` 与 `userlimit_max_cpu_freq` 可写，但 uperf 自己就在用这套节点：连续写入 6 次，`scaling_max_freq` 在 1131→1625MHz 之间自行跳变，限制立刻被覆盖。
- 结论：在这台机器上做滑杆只会得到一个假开关，因此只做只读展示，等以后换 ROM 或不装 uperf 时再评估。

### 内核里没有停充节点

- `battery/` 下无 `charging_enabled` / `input_suspend`；`/proc/mtk_battery_cmd/en_power_path` 写 0 后 `status` 仍是 Charging；`/sys/devices/platform/charger/input_current` 写入会被截断（写 100 读回 256，写 -1 读回 0）。
- 所以「充电阈值」只能做到提醒，无法自动断电，UI 已按此写明。

