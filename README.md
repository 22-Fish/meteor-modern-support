# meteor现代化支持 (meteor-modern-support)

## 前言

* **⚠本项目代码由AI生成**
* 在使用meteor client模组的过程中，经常遇到一些问题。比如现代反作弊对meteor的旋转几乎是百分百拦截。meteor本身也缺少非常多的实用功能。此项目致力于为meteor添加现代化的功能，如加入了合法转头的杀戮光环，绝不回弹卡脚。
* meteor经常因为非正常关闭丢失配置，我们修改了保存机制，让meteor每次状态变化都进行保存。





## 分页功能
* 我们为meteor加入了分页功能。相信你一定遇到过这样一个问题:安装了一堆实用的插件，可是屏幕里根本放不下这么多，非常难受。在安装的插件过多的情况下，一页难以显示，分页是个好主意。
* 在conifg页面，现在有页面设置选项，你可以创建多个页面，这些页面都会显示在顶端页面选择栏(不知道是不是这么表达，就是config,GUI那一栏)上，点击即可进入另一页面。config的页面设置界面点击页面列表中的页面名称，可以选择此页面肇展示的板块。
* 我们会在启动时检查所有模块应用的页面。如果一个模块没有被记录过，那么推断他是新加的插件，自动在主界面开启显示。



## 配置更改

原本的meteor中的模块配置(模块参数设置，开关状态)放在./meteor-client/modules.nbt这跟个文件中，但是他是单个文件，无法做到动态切换。我们将配置文件存放路径改为./meteor-client/config/这个文件夹。内部可以存放多个配置文件。应用的配置可以在顶部栏的config页中替换，还支持在加入某些服务器时自动替换为对应配置，大大提升了meteor的灵活度





## 合法转头 API 使用文档

### 为什么需要它

* Meteor 原版 `Rotations.rotate` 是**发包旋转**：只在发包旋转，移动计算仍按客户端原朝向进行 → 旋转与移动方向不一致,在现代反作弊下回弹几乎是必然。
* 我们旋转同时设置真实移动朝向，移动符合原版，不触发反作弊
* **一般情况下请不要使用严格模式，但是为了可能的特殊需求，仍保留**

### 快速开始（两步）

```java
// 1. 模块内添加「合法转头」设置项（显示中文选项：关闭/停止移动/严格/静默）
private final Setting<LegalRotation.Mode> legalRotation =
    sgGeneral.add(new LegalRotation.ModeSetting(
        "合法转头",                       // 设置名
        "旋转时如何矫正移动方向。",         // 描述
        LegalRotation.Mode.OFF,     // 默认关闭
        null, null, null                 // onChanged / onModuleActivated / visible
    ));

// 2. 每次旋转时调用（例如模块 onTick 里）
LegalRotation.rotateWithMode(yaw, pitch, legalRotation.get());
//调用方每 tick 调用一次,下tick自动转回去
```

### 模式说明

|模式|行为|
|-|-|
|严格|真实旋转 + 客户端静默：服务器朝向正确、移动方向跟随服务器朝向、客户端视角不动|
|静默|在严格基础上映射 WASD 按键：尝试让移动方向与**客户端视觉朝向**一致|


### 优先级（同一 tick 多个模块抢转向）
```java
// 指定优先级（严格模式）
LegalRotation.rotate(yaw, pitch, LegalRotation.Mode.SEVERE, 50);
// 严格模式 + 自己设置项里的优先级
LegalRotation.rotate(yaw, pitch, legalRotationPriority.get());
// 被顶掉就不做后面那件事（放置包必须在「带着这份朝向的移动包」之后才发得合法）
if (!LegalRotation.rotate(aim.yaw(), aim.pitch(), mode, priority)) return;
```


## 语言支持（[Meteor-I18n-Support-plugin](https://github.com/dingzhen-vape/Meteor-I18n-Support-plugin)）
Meteor 本体的文字（模块名、设置名、描述）是代码写死的，本 mod 通过 mixin 在构造时替换为语言文件中的翻译，并支持运行时切换（改完点击按钮刷新，重进界面立即生效，无需重启游戏）
### 设置入口
Meteor 设置主界面（`/meteor` → Config）新增 **Language（语言）** 设置（下拉列表）：
* 选项自动拉取**游戏目录 `meteor-lang/` 下所有文件夹**（文件夹名即语言代码），加上内置语言（简体中文 / 英语）
* **首次启动自动选择**：与 Minecraft 游戏语言匹配的语言（MC 简体中文 → 中文，MC 英语 → 英语），无匹配时用英语兜底
* 切换语言重新打开界面立即生效，无需重启；游戏运行中新增的语言文件夹也会出现在列表里

### 语言文件位置
**内置翻译**（初始化兜底）：打包在 mod 内 `assets/meteor-modern-support/lang/`，随 mod 分发。
**外部语言文件**：游戏目录下 `meteor-lang/<语言名>/` 文件夹，**文件夹名即语言显示名**（内置语言为 `简体中文` / `English`），文件夹内**所有 JSON 文件同时生效**（按文件名排序合并，后者覆盖前者）。例如：
```
.minecraft/
└── meteor-lang/
    ├── 简体中文/
    │   ├── meteor.json          ← 主翻译模板（随内置自动同步，不建议手改）
    │   └── my-addon.json        ← 自己的插件翻译
    ├── English/
    │   └── meteor.json
    └── Alien tongue/              ← 创建一个文件夹，它会被当成全新语言解析，会出现在语言列表
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
翻译文件与实现思路基于 [Meteor-I18n-Support-plugin](https://github.com/dingzhen-vape/Meteor-I18n-Support-plugin)（作者 kono\_yalu）













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
* [Grim](https://github.com/GrimAnticheat/Grim)（GPL-3.0）— 放置相关检查（RotationPlace / PositionPlace / FarPlace）与鼠标灵敏度反推公式：最佳合法角度 API 的「合法」就是按这些规则定的

移植的代码文件均带有 GPL-3.0 头注释与来源说明。
