# meteor现代化支持 (meteor-modern-support)

Meteor Client 扩展模组
功能一览

|功能|说明|
|-|-|
|移动矫正 API|旋转时修正移动方向：走路、鞘翅移动方向与旋转朝向一致（参考 Baritone LookBehavior）|
|KillAura 集成|为 Meteor 的 KillAura 添加「移动矫正」设置（关闭 / 停止移动 / 严格 / 静默）|
|转圈（Misc 分类）|Derp 移植：偏航 5 模式 + 俯仰 3 模式，平滑转头，可配移动矫正|
|配置自动保存|设置修改 / 模块开关后异步防抖保存，强退不丢配置|
|\|18n 语言支持|Meteor 全界面多语言：Config 里可设置语言（默认跟随 Minecraft），游戏目录动态加载语言文件|







## 移动矫正 API 使用文档

### 为什么需要它

Meteor 原版 `Rotations.rotate` 是**静默旋转**：只在发包瞬间修改旋转，移动计算（aiStep）仍按客户端原朝向进行 → 旋转与移动方向不一致（走路、鞘翅方向不对），且旋转结束后有残留锁定（"不归位"）。

本 API 采用 **Baritone LookBehavior** 的机制：**移动计算前真实设置玩家朝向** → WASD 移动方向、鞘翅方向自然跟随旋转；移动包发送后恢复原朝向（客户端静默）。状态由调用方每 tick 刷新，停止调用后自动归位。

### 快速开始（两步）

```java
// 1. 模块内添加「移动矫正」设置项（显示中文选项：关闭/停止移动/严格/静默）
private final Setting<MovementCorrection.Mode> movementCorrection =
    sgGeneral.add(new MovementCorrection.ModeSetting(
        "移动矫正",                       // 设置名
        "旋转时如何矫正移动方向。",         // 描述
        MovementCorrection.Mode.OFF,     // 默认关闭
        null, null, null                 // onChanged / onModuleActivated / visible
    ));

// 2. 每次旋转时调用（例如模块 onTick 里）
MovementCorrection.rotateWithMode(yaw, pitch, movementCorrection.get());
```

### 模式说明

|模式|行为|
|-|-|
|关闭|原版静默旋转，不矫正移动|
|停止移动|暂未实现（选中等同关闭）|
|严格|真实旋转 + 客户端静默：服务器朝向正确、移动方向跟随服务器朝向、客户端视角不动（已通过 Grim 最严格反作弊，0 回弹）|
|静默|在严格基础上映射 WASD 按键：移动方向与**客户端视觉朝向**一致。例如服务器朝正右（90°）、视觉朝正前（0°）时，W 键等效 A 键，人物仍朝视觉正前方移动；斜向自动产生 W+D 组合键效果，moveVector 使用浮点向量，任意角度精确|

### 时序与生命周期

* 调用方每 tick 调用一次（如 KillAura 的 onTick）；`MovementCorrection.rotateWithMode` 会按模式自动分发（严格/静默走移动矫正，其余回退原版 `Rotations.rotate`）
* 移动矫正状态**每 tick 自动清除**：停止调用后下一个 tick 自动归位，不会残留锁定
* 需要回调时用 `MovementCorrection.rotate(yaw, pitch, mode, callback)`，回调在移动包发送完毕后执行

### 注意事项

1. **全局状态**：移动矫正状态是全局静态的，多个模块同时旋转会互相覆盖，避免同时使用
2. **鞘翅飞行**：移动方向跟随服务器朝向，鞘翅方向正确

### 内置示例

* **KillAura 集成**（`mixin/MixinKillAura.java`）：通过 `@Redirect` 拦截 `Rotations.rotate(DD)` 的两个调用点，替换为移动矫正
* **转圈模块**（`modules/Spin.java`）：完整的"设置项 + rotateWithMode"使用范例








## 语言支持（|18n）

Meteor 本体的文字（模块名、设置名、描述）是代码写死的，本 mod 通过 mixin 在构造时替换为语言文件中的翻译，并支持运行时切换（改完立即生效，无需重启）。

### 设置入口

Meteor 设置主界面（`/meteor` → Config）新增 **Language（语言）** 设置（下拉列表）：

* 选项自动拉取**游戏目录 `meteor-lang/` 下所有文件夹**（文件夹名即语言代码），加上内置语言（简体中文 / 英语）
* **首次启动自动选择**：与 Minecraft 游戏语言匹配的语言（MC 简体中文 → 中文，MC 英语 → 英语），无匹配时用英语兜底
* 切换语言立即生效，无需重启；游戏运行中新增的语言文件夹也会出现在列表里

### 语言文件位置

**内置翻译**（初始化兜底）：打包在 mod 内 `assets/meteor-modern-support/lang/`，随 mod 分发。

**外部语言文件**：游戏目录下 `meteor-lang/<语言名>/` 文件夹，**文件夹名即语言显示名**（内置语言为 `简体中文` / `English`），文件夹内**所有 JSON 文件同时生效**（按文件名排序合并，后者覆盖前者）。例如：

```
.minecraft/
└── meteor-lang/
    ├── 简体中文/
    │   ├── meteor.json          ← 主翻译模板（随内置自动同步，不建议手改）
    │   └── my-addon.json        ← 自己的插件翻译
    └── English/
        └── meteor.json
```

> 主翻译文件 `meteor.json` 会在启动/重载时自动与内置模板同步（内容不同才替换），自定义翻译请新建自己的 JSON 文件。

### 键名规则

```
Module.Meteor.<模块内部名>                → 模块显示名
Module.Meteor.<模块内部名>.Description    → 模块描述
Setting.Meteor.<设置内部名>               → 设置显示名
Setting.Meteor.<设置内部名>.Description   → 设置描述
```

`<插件名>` 对 Meteor 本体是 `Meteor`，对其他 addon 是该 addon 在 fabric.mod.json 中的显示名（例如本 mod 是 `meteor现代化支持`）。

### 其他插件适配

其他 Meteor addon 想支持多语言：在初始化时向 `meteor-lang/<语言代码>/` 注入 JSON 文件（键格式如上，插件名用自己 mod 的显示名），游戏启动/切换语言时自动加载；若在运行时注入，可调用 `fish22.modernsupport.utils.I18n.reloadAndApply()` 重新加载并刷新界面。

> 注：为正常显示中文等非 ASCII 字符，本 mod 强制 Meteor 使用原版文字渲染器，Meteor 的「自定义字体」设置（custom-font / font）将不再生效。

### 来源

翻译文件与实现思路基于 [Meteor-I18n-Support-plugin](https://github.com/dingzhen-vape/Meteor-I18n-Support-plugin)（作者 kono\_yalu，CC0 协议(使用cc0应该违反了GPL协议,已告知作者)）。





## 构建

```bash
./gradlew build
```

产物位于 `build/libs/meteor-modern-support-<版本>.jar`。






## 许可证

**GNU General Public License v3.0（GPL-3.0）**

代码来源与参考：

* [Meteor Client](https://github.com/MeteorDevelopment/meteor-client)（GPL-3.0）— 依赖与扩展目标
* [LiquidBounce](https://github.com/CCBlueX/LiquidBounce)（GPL-3.0）— MovementCorrection.SILENT 按键映射算法、Derp 模块、AngleSmooth 平滑机制
* [Baritone](https://github.com/cabaletta/baritone)（LGPL-3.0）— LookBehavior 真实旋转机制（PRE/POST 时序）

移植的代码文件均带有 GPL-3.0 头注释与来源说明。

