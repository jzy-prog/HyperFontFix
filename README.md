# HyperFontFix - 拯救你的状态栏字重

**通过主题的方式换了第三方可变字体之后，HyperOS 的状态栏 / 通知中心时间 / 锁屏状态栏运营商会变细。**
这个模块把「系统本来就想要的字重」用你当前这套字体重新烘回去：按控件分角色、逐个可调、字重支持热修改、不需要重启。

| 项目   | 说明                                                                                      |
| ---- | --------------------------------------------------------------------------------------- |
| 运行环境 | LSPosed（`xposed` 入口）或 libxposed（`java_init.list`，API 101），需要已 root                      |
| 作用域  | 仅 `com.android.systemui` 一个进程                                                           |
| 实测机型 | 小米17(pudding) / HyperOS 4.0.0.32.XPCCNXM.D01 Beta / Android 17 / SDK 37（2026-10），其他版本未测 |
| 配置方式 | `Settings.System` 的 `hf_ff_*` 键，热生效；除了第一次安装并启用时，无需重启 SystemUI                           |
| 不做的事 | 不改字体文件、不改 `fonts.xml`、不用伪粗体（fakeBold）、不改文案、不改布局                                         |

---

## 1. 它解决什么

在 HyperOS 上通过主题的方式应用一个第三方可变字体（带 `wght` 轴的 .ttf/.otf）之后，正文确实换成了新字体，但系统状态栏的字会明显变细，常见具体表现：

- 状态栏时间、电量数字、4G/5G 标签、锁屏状态栏运营商名 —— 全比原生MiSans VF细；

HyperFontFix 只修这些状态栏文字，并且**保留你选的字体**：它换掉的只是 Typeface 实例里的可变轴取值。

### 1.1 附带效果：锁屏日期也归主题管了

主题商店换字体其实**管不到锁屏界面的日期**：那块文字用的是 `MiuiConfigs` 在 SystemUI 启动时烘好的静态 Typeface，烘的时候读的是系统字体路径（`mi_font_path`），不是主题落地的 `/data/system/theme/fonts/`。所以你换了字体，锁屏日期照旧是 MiSans。

本模块把这条通道交出来的 Typeface 换成「从主题字体文件重新烘出来的克隆实例」，于是锁屏日期也一并变成了主题里的字体。这是原设计之外的副作用，不过方向是对的：状态栏和锁屏的文字终于完整跟随同一套字体。

## 2. 现有方案

这里既有社区常见做法的弊端，也有**本项目自己踩过的坑**：

1. **直接在系统设置中修改字重**：虽然调粗能改善状态栏的观感，但也会影响第三方 APP 的界面，导致整体变粗，层级不分明。
2. **字体模块：替换字体文件 / 改 `fonts.xml` / 重打包**：需要安装元模块、Font Loader 等前置模块，并且需要在 ROOT 管理器中关闭 “默认卸载模块”，步骤繁琐且增加痕迹暴露风险。
3. **Hyper Helper + HyperLight**（本项目诞生前，我的临时解决方案）：Hyper Helper 提供了大量自定义功能，允许用户修改状态栏各元素的字重，实测状态栏时间字重还能生效，但通知中心时间字重已失效，毕竟是为 OS3 构建的。于是我用 HyperLight 修改了通知中心的时间字重。但这个方案有两大弊端：

    - 下拉通知中心的过程中，时钟字重会「闪」一下（动画前后不一致）；
	- 切换主题 / 重新应用字体之后，之前调好的字重又掉回去；
		原因在于：HyperOS 的下拉动画（`VarFontUtils` / `updateWeight(F)` / Folme）每帧都在改属性，主题重应用也会重写一遍 → 表现为「下拉之后又变细」「动画中途闪一下」。必须在**绘制期**做最终裁决。

4. 本项目构建过程中曾采用**进程级重写 `mipro-*` 家族**的方案（把某档字重统一顶高）：这类 hook 看不到「是谁在请求」，于是超级岛**胶囊形态**的文字、控制中心日期会被一起顶到最高字重（超级岛的展开态没事，它不走这条取字通道）。本项目 v4.0 就是这样：判定粗细写的是 `familyName.contains("bold")`，而 `mipro-semibold`、`mipro-demibold` **都含子串 "bold"**，于是任何调用方都被顶到最高字重，日志里 `H0a mipro-demibold re-bake -> axis 660` 刷成一片。现在这类进程级通道**默认关闭**，只作为可选项。

## 3. 安装与配置

### 3.1 安装步骤

前置条件：已 root，装了 LSPosed（或 libxposed 系框架，如 LSPosed 的 modern API 分支）。**不需要** Magisk 元模块、不需要 Font Loader、不读写系统分区。

1. 安装 `HyperFontFix-v1.0-alpha.apk`（在本仓库的 [Releases](https://github.com/jzy-prog/HyperFontFix/releases) 下载）；
2. 打开 LSPosed → 模块 → 启用 HyperFontFix，作用域只勾 **System UI（`com.android.systemui`）**（模块自带 scope 声明，正常只会列出这一个）；
3. **重启一次SystemUI**。这是唯一必须重启的一步：注入只发生在进程启动时；
4. 之后在主题商店应用你的第三方可变字体，状态栏那几处应该已经回到原生观感；
5. 验证：

```bash
adb logcat -s HyperFontFix | head
# 期望看到：v1.0-alpha PER-ROLE installed, hooks=... clock=500 net=660 carrier=430 ...
```

没有这一行就是没注入成功，回去检查作用域和模块开关；有这一行但某处还是偏细，跳到 §3.7 排查。

卸载 / 停用：LSPosed 里关掉开关即可；想临时停用但保留配置，用 `hf_ff_enable 0`（见下）。覆盖升级后如果行为没变，在 LSPosed 里重新勾选一次作用域，或 `pm enable com.hyperfontfix.module` 之后再重启。

### 3.2 配置放在哪里

全部配置都是 `Settings.System` 里的整型键，前缀 `hf_ff_`。SystemUI 在绘制路径上按 2 秒节流重读一次，所以**写完就生效，不需要重启、不需要重启 SystemUI、不需要重新激活模块**。

```bash
adb shell settings get  system hf_ff_clock          # 看当前值（null = 用模块内置默认）
adb shell settings put  system hf_ff_clock 560      # 改
adb shell settings delete system hf_ff_clock        # 删掉键 = 回到内置默认
adb shell settings put  system hf_ff_enable 0       # 临时整体停用（hook 还在，但什么都不改）
```

没有电脑时，在手机上用 root 终端执行同样的命令（去掉 `adb shell` 前缀）即可。OTA / 恢复出厂会清空这些键，模块不会因此异常 —— 每个键都有内置默认值，键不存在就等于默认配置。

### 3.3 键总表

| 键 | 默认 | 作用位置 | 备注 |
| ---- | ---- | ---- | ---- |
| `hf_ff_enable` | `1` | 总开关 | `0` = 只保留注入，不改任何字重 |
| `hf_ff_clock` | `500` | 状态栏时钟、下拉面板大时钟、锁屏时钟 | ROM 原值（pad 版是 460） |
| `hf_ff_network` | `660` | 4G / 5G 网络类型标签 | 自绘 Drawable，走画笔通道 |
| `hf_ff_carrier` | `430` | 状态栏 + 锁屏状态栏运营商名（含 `HD`、`+`） | ROM 原值 `sMiproTypeface` |
| `hf_ff_battery` | `500` | 电量数字 | 按实例身份识别，不牵连别处的充电文字 |
| `hf_ff_mark` | `600` | 电量的 `%` 号 | 与电量数字是两个不同 TextView |
| `hf_ff_hollow` | `620` | 镂空电池图标内的数字 | `MiuiBatteryUtils.FONT_WGHT` |
| `hf_ff_date` | `0` | 控制中心 / 通知中心 / 锁屏日期 | **默认 0：完全不碰**（详见 §1.1：这块原本压根不受主题字体影响） |
| `hf_ff_island` | `0` | 超级岛**胶囊形态**的文字 | **默认 0：完全不碰**；展开态本来就不受影响 |
| `hf_ff_other` | `0` | 其它流经 `applyStatusBarTypeface` 但没归类的文字 | **默认 0：完全不碰** |
| `hf_ff_global` | `460` | `getMiProTypefaceWght460()` 的名义字重 | 只在 `hf_ff_g1` 或 `hf_ff_all` 打开时参与 |
| `hf_ff_family` | `0` | 进程级 `mipro-*` 家族重写（H0a/H0b/H0c） | 实验项，默认关闭 |
| `hf_ff_g1` | `0` | 进程级共享 Typeface getter 重写（G1） | 实验项，默认关闭 |
| `hf_ff_all` | `0` | 「v4.0 模式」：所有能命中的状态栏文字统一顶到 N | 实验项，默认关闭 |
| `hf_ff_log` | `0` | 日志级别 | `1` 概要 / `2` 逐控件诊断 |
| `hf_ff_aod` | `0` | 是否把 `com.miui.aod` 纳入作用域 | 本 ROM 该进程没有可改控件，仅留作实验 |

### 3.4 取值语义

- **`0` 的含义是「这个角色不动」**，不是「设成 0 字重」。日期、超级岛、未分类文字默认就是这个 `0`，所以装完模块它们和没装一样。
- 非零值就是 `wght` 轴的目标取值，建议 100–900 整十取。模块会把它**夹到你这套字体 `fvar` 的真实范围内**（例如测试字体只有 150–700，填 `900` 实际落 `700`），所以填大了不会崩、不会出豆腐块，只是不再变粗。
- 写进非整数字符串会让 `getInt()` 抛异常，模块捕获后**所有键一起退回内置默认**，所以别写成 `settings put system hf_ff_clock bold`。
- 「哪个键管哪块字」由控件角色决定而不是由位置决定，所以同一处 UI 里「时间」和「日期」分别归 `hf_ff_clock` / `hf_ff_date` 管。

### 3.5 优先级

```
hf_ff_all 大于 0     → 统一字重（v4.0 行为，覆盖面最大，会牵连未分类控件）
        ↓ 为 0
hf_ff_（角色名）      → 按角色取值（推荐，默认就是这个模式）
        ↓ 该角色为 0
不动这一块
hf_ff_global         → 只服务 G1 这条进程级通道：调用方能归类就用角色的值，否则用 global 兜底
```

`hf_ff_family` / `hf_ff_g1` 打开时仍然要过「调用方角色否决」：只要 `hf_ff_island` / `hf_ff_date` 还是 `0`，即使进程级通道开着，超级岛和日期也不会被写。

### 3.6 常见配方

```bash
# 只把时钟再调粗一点，其余保持原生
adb shell settings put system hf_ff_clock 560

# 日期也重新调回原生字重（默认不动它）
adb shell settings put system hf_ff_date 500

# 超级岛胶囊态的文字也加粗
adb shell settings put system hf_ff_island 600

# 一键回到 v4.0 那种「状态栏全部加粗」的观感
adb shell settings put system hf_ff_all 660

# 关掉所有实验通道，回到精确模式
adb shell settings put system hf_ff_all 0
adb shell settings put system hf_ff_family 0
adb shell settings put system hf_ff_g1 0

# 全部恢复默认
adb shell "for k in enable clock network carrier battery mark hollow date island other global family g1 all log aod; do settings delete system hf_ff_$k; done"
```

### 3.7 排查：某个控件还是偏细怎么办

```bash
adb shell settings put system hf_ff_log 2
adb logcat -s HyperFontFix
```

| 日志行 | 说明 |
| ---- | ---- |
| `v1.0-alpha PER-ROLE installed, hooks=N clock=... net=...` | 注入成功 + 当前全部配置快照。**没有这一行 = 模块根本没进 SystemUI**，先去 LSPosed 查作用域 |
| `SELFTEST file=... AXIS_TF=true AXIS_PAINT=false` | 三条写入通道的自证结果，判断「是不是字体文件路径没找到」 |
| `T11 face 660 -> XxxView` | 绘制期确实给这个控件换上了 Typeface |
| `T11 face refused XxxView` | 当前字体含 `HVAR`/`MVAR`，绘制期覆写自动跳过（防重排） |
| `T11DIAG role=... @类名 want= ourtf= tvaxis= paint= layout=` | 单控件全链路读数：分不清「没命中」还是「没生效」时看这条 |
| `T11SEEN 类名 id=资源名` | 我们**故意不碰**的文字（角色权重为 0）。还是偏细的那个控件十有八九在这里，按它的角色开对应的键 |
| `SKIP 类名 role=...` | 明确跳过记录 |

所有日志都做了同类去重 / 时间限流，开到 `2` 也不会刷屏；排查完 `settings put system hf_ff_log 0`。

### 3.8 给将来的 GUI 留的口子

在 libxposed 框架上（框架声明 `PROP_CAP_REMOTE` 时），模块还会监听一个名为 `hyperfontfix` 的 remote preference store：键名是去掉 `hf_ff_` 前缀的同名键（`clock` / `network` / `carrier` / `enable` / `log` …），任一项变化立刻热加载，**优先级高于 `Settings.System`**。所以下一版图形化配置界面不需要改注入逻辑，写这个 store 就行。

## 4. 为什么换了字体会变细

### 4.1 HyperOS 的字重是「烘」进 Typeface 实例的

HyperOS 不走 Android 标准的 `textFontWeight` 排版链路，而是在 `MiuiConfigs` 里**预先烘好一批带轴的 Typeface 静态实例**，再由 `MiuiConfigs.applyStatusBarTypeface(Typeface, TextView[])` 发给各个控件；`getMiProTypefaceWght460()` 是本 ROM 上唯一的 Typeface 取用口。烘进去的是字面量：

```java
new Typeface.Builder(FONT_MIPRO_PATH).setFontVariationSettings("'wght' 460").build()
```

460 / 500 / 660 / 620 / 600 / 430 这些数字是**为 MiSansVF 调的**，而 MiSansVF 的 `wght` 轴大约是 100–900。

### 4.2 主题引擎把 16 个字重槽位全指向同一个文件

主题引擎落地字体后，`/data/system/theme/fonts/` 下面长这样（真机 `ls -l` 摘录）：

```
lrwxrwxrwx Miui-Bold.ttf      -> /data/system/theme/fonts/Roboto-Regular.ttf
lrwxrwxrwx Roboto-Black.ttf   -> /data/system/theme/fonts/Roboto-Regular.ttf
lrwxrwxrwx Roboto-Light.ttf   -> /data/system/theme/fonts/Roboto-Regular.ttf
... 共 16 个改名 symlink
-rwxr-xr-x Roboto-Regular.ttf                      34737744   ← 真正的可变字体
```

于是「regular / medium / bold / black」这些名字全部解析到**同一个文件**，字重信息只剩文件里 `fvar` 的**默认实例**。以本次实测用的字体为例：

| 项目              | 系统原生 MiSansVF | 测试字体        |
| --------------- | ------------- | ----------- |
| `wght` 范围       | 约 100–900     | **150–700** |
| `wght` 默认值      | 400 附近        | **330**     |
| `HVAR` / `MVAR` | 有             | **无**       |

结论：**所有字重请求都塌回 330**，这就是「变细」的全部原因。跟 DPI、抗锯齿、字体损坏都没有关系。

### 4.3 `mipro-*` 只是别名，主题下拿不到真实字重

`/system/etc/fonts.xml` 里：

```xml
<alias name="mipro-thin"     to="sans-serif" weight="100"/>
<alias name="mipro-medium"   to="sans-serif" weight="500"/>
<alias name="mipro-demibold" to="sans-serif" weight="700"/>
<alias name="mipro-bold"     to="sans-serif" weight="900"/>
```

别名只提供「家族 + 权重」的线索，落到可变主题字体上并不会自动设置 `wght`，因此 4G/5G 标签这类走 `Typeface.create("mipro-bold", 0)` 的控件，最终字重完全由主题自己的解析结果决定（可能偏细，也可能偏粗）。

## 5. 实现原理

### 5.1 字重来源：一律抄 ROM 自己的作业

默认值不是审美决定，而是 ROM 在这个位置的原始取值（从字节码调用点扫出来的）：时钟 500（pad 版 460）、4G/5G 660（`MobileTypeDrawable.<clinit>` 烘 `sMiproTypeface` 用的就是 `'wght' 660`）、运营商 430、电量数字 500、`%` 600、镂空电池 620（`MiuiBatteryUtils.FONT_WGHT`）。**日期 / 超级岛 / 未分类文字默认 0 = 完全不动。**

### 5.2 先命中，再改重

模块内把系统栏文字归成角色：`clock / network / carrier / battery / mark / hollow / date / island / other`，每个角色一个字重键。命中一个控件有三条路，按可靠性从高到低：

| 识别方式 | 用在哪 | 为什么需要 |
|---|---|---|
| 实例身份（反射拿字段） | 电量数字 vs `%` | 它们是**两个不同 TextView**，用文案正则匹配会把超级岛里的充电通知文字一起加粗 |
| 类名 `classify()` | 时钟 / 运营商 / 日期 / 网络类型 | 便宜，可按类缓存，覆盖大部分场景 |
| 资源 entry 名 + 父链容器 | 锁屏状态栏运营商 | 真正写字的是普通 `android.widget.TextView`，类名零线索；靠 `id/carrier_text` 与「位于 carrier 容器内」判定，顺带把同块的 `hd_text`、`plus_text` 一起纳管 |

反向还有一层否决：`island / capsule / deepspace` 等类名与父链匹配的一律排除；日期与超级岛属于「权重 > 0 才解锁」的显式开关，默认绝不触碰。

### 5.3 写入通道优先级：真 Typeface > 画笔 Typeface > 轴字符串

```
1) 重建 Typeface：new Typeface.Builder(当前生效的字体文件).setFontVariationSettings("'wght' N").build()
   —— 真 fvar 实例，主题字体也继续用主题自己的字形；
2) 画笔级 Paint.setTypeface(...)：非 TextView 的自绘控件（4G/5G 标签、镂空电量数字）只能走这条；
3) Paint.setFontVariationSettings("'wght' N")：仅作兜底，对原生 Typeface 无效（真机自证：§3.7 的 `SELFTEST ... AXIS_PAINT=false`）。
```

字体文件取值优先级：`/data/system/theme/fonts/` 里最大的那个文件（主题字体）→ 系统属性 `ro.miui.ui.font.mi_font_path`（原生 MiSansVF）→ 都不可用则放弃烘文件、退回按名字解析。**这一步是「不换字形、只换笔画粗细」的关键。**

### 5.4 绘制期裁决：谁最后写谁赢

`TextView.onDraw` 之前是最后一块阵地。动画、`setTextAppearance`、主题重应用写下的任何东西，都会在下一帧绘制前被覆写回去 —— 所以「下拉动画闪烁」和「切主题后掉回细体」是从机制上消失，而不是靠延时补写糊过去。

同时把烘好的 Typeface 写进 `TextView#getPaint()` **和** `Layout#getPaint()`（真正参与光栅化的是后者）。之所以敢在绘制期动画笔而不触发重排：主题字体**没有 `HVAR`/`MVAR`**，`wght` 轴不改变字宽与行高，笔画变粗不可能引起重排或裁切；若系统字体含这两个表，绘制期覆写会自动跳过。

### 5.5 爆炸半径：白名单，而不是「全进程」

- 绘制期改写只放行状态栏 / 面板 chrome 的包名，外加 `carrier` 角色；
- **锁屏钟面永远不改**：`com.miui.clock.*`、`com.android.keyguard.clock.*`、`com.android.systemui.customization.clocks.*` 的类名同样含 "clock"，但它们自己就在做 `wght` 轴动画（`DigitalClockTextView$AxisAnimation`、`ClockAxisStyle`），写进去等于制造新 bug；
- 进程级 Typeface 重写（`Typeface.create(String,int)` / `Builder.build()` / `getMiProTypefaceWght460`）**默认关闭**，即便打开也要过「调用方角色」这一关。

## 6. 已知限制

- 只在小米17 / HyperOS 4.0.0.32 Beta / Android 17 上实测过。角色识别靠类名与资源名，其它 ROM（尤其 OS2/OS3）的控件类名与写入点不同，需要重新核对才能确认覆盖。
- 绘制期改写的前提是主题字体**没有** `HVAR`/`MVAR`（绝大多数第三方 VF 字体都没有）；如果你的字体带这两个表，模块会自动放弃绘制期覆写，此时偏细无法修复，因为改字重会改字宽。
- 性能与功耗只做过代码层面的结构分析（无常驻线程轮询、稳态每帧只走缓存查表、不 `requestLayout`、不持 wakelock），**CPU 占用、帧时间、电流这些定量数字尚不明确**：没有实测过，也不拍脑袋给数字。

## 7. 自己构建

不用 Gradle：`tools/build_v4.ps1`（PowerShell + 本地 JDK）完成 javac → r8 → aapt2 → zip 塞入 → apksig 签名 → 校验，一条命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_v4.ps1 -Ver 1.0-alpha
# 产物：HyperFontFix-v1.0-alpha.apk
```

脚本按仓库相对路径推导工作目录，但工具链**不入库**（体积 / 许可原因），需要自备并放到约定位置：

| 需要的东西 | 放置路径 | 用途 |
| ---- | ---- | ---- |
| JDK 21（含 `javac`/`jar`/`java`） | `tools/jdk/jdk-21.0.12.1+1/bin/` | 编译、打包、跑 r8 |
| Android `android.jar`（API 34） | `tools/sdk/android-34/android.jar` | `-bootclasspath` |
| `aapt2.exe` | `tools/aapt2/aapt2.exe` | 编译 / 链接 `res` 与 manifest |
| `r8.jar` | `tools/jars/r8.jar` | dex 化 + 按 `keep3.pro` 保留入口 |
| Xposed 传统 API | `tools/jars/xposed-api-82.jar` | `de.robv.android.xposed` 入口 |
| libxposed API | `refs/libxposed/api-102.0.0-classes.jar` | `io.github.libxposed.api` 入口（Maven 中央仓库可取） |
| apksig | `tools/jars/apksig-8.1.4.jar` | 签名（`tools/signsrc/SignApk.java` 是它的小包装，脚本会自动编译） |
| 签名密钥 | `tools/debug.keystore`（alias `hf`） | 自带的是 debug key，自行发布请换成自己的 |

`module/` 下只有三个源文件（`Core.java` 是全部逻辑）、唯一的 manifest 和 `assets/xposed_init`；`META-INF/xposed/{module.prop,java_init.list,scope.list}` 由脚本生成，作用域固定 `com.android.systemui`。

## 8. 许可

GPL-3.0-only，全文见 [LICENSE](LICENSE)。

```
HyperFontFix - restore HyperOS status-bar font weights under a themed variable font
Copyright (C) 2026 Jzy (github.com/jzy-prog)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```
