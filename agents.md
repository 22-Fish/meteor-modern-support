# 编写规则

* 除非明确要求且经过二次确认，请不要在任何代码中包含向公共聊天发生当前坐标的逻辑。非必要不在日志中出现包含当前坐标的日志
* 代码请条理清晰，不要破坏兼容性。当前任务不清晰就说不清晰就二次确认，不蛮干。不要为了当前任务打乱代码逻辑
* 编写代码一般不用考虑被反作弊叠vl。这个插件一般用于无规则服，只要不被回弹就行，vl不用管。
- 描述简单直白，不要过于冗长。不要多行。不要句号。

# 源码查阅

* 项目上一级目录有grim，SlimefunHelper(slm,史莱姆),LiquiBounce(水影)，meteor原版等mod，可以查阅。
* 桌面上有原版minecraft服务端

# 用户习惯
- 用户不会写java，项目基本由AI生成
- 不要考虑太多，改一个模块那就只看这个模块的逻辑，不要开头就调用一大堆工具
- 描述原理时少用内部名，尽量用通俗易懂的语言如移动包，攻击包等来描述
- agents.md不要上传到github

# meteor 常识（26.1 Mojmap，写代码前先看这里）

## 项目结构
* src/main/java/fish22/modernsupport/ 下分 modules（功能模块）/ mixin（注入原版或 meteor）/ utils（公共逻辑）/ gui / settings / aibot
* 新模块：继承 Module 写好后在 ModernSupport.onInitialize() 里 Modules.get().add(new X())
* 放置类模块的公共逻辑都在 PlaceModule，子类只写自己的驱动（见 SimplePlace / Printer）

## 模块与设置
* 设置是 public Setting<T> 字段：sgXxx.add(new XxxSetting.Builder().name().description().defaultValue().build())
* settings.getDefaultGroup() 是默认组，settings.createGroup("名") 建新组，.visible(() -> 条件) 控制显示
* 设置按 name 存进 meteor 配置，改 name = 老配置失效
* 想在别人的设置后面插一项：用 SettingGroupAccessor 拿内部 list 手动插（见 MixinSpeedMine.insertAfter）

## 事件
* @EventHandler 修饰方法、参数是事件对象；Module 激活时自动订阅，静态/工具类用 MeteorClient.EVENT_BUS.subscribe(...)
* 常用事件：TickEvent.Pre/Post、PacketEvent.Send/Receive、Render3DEvent、GameJoinedEvent、ActiveModulesChangedEvent、StartBreakingBlockEvent、BlockBreakingCooldownEvent
* event.cancel() 拦掉后续（不发这个包 / 不跑原版那套）

## 发包与工具（meteor 现成的别自己造）
* 发包：mc.getConnection().send(new XxxPacket(...))
* 要过服务端 sequence 校验：mc.gameMode.startPrediction(mc.level, id -> new XxxPacket(..., id))
* 切槽位 InvUtils.swap(slot, true) + InvUtils.swapBack()；找物品 InvUtils.findInHotbar / InvUtils.find
* 距离 PlayerUtils.distanceTo(pos)；能不能挖 BlockUtils.canBreak(pos, state)；单 tick 进度 BlockUtils.getBreakDelta(slot, state)
* 范围扫描 BlockIterator.register(rx, ry, cb) + BlockIterator.after(cb)（回调在当 tick 稍后才跑）
* 渲染 Render3DEvent，event.renderer.box(...)；画临时框 RenderUtils.renderTickingBlock(...)

## mixin
* @Mixin(value = 目标.class, remap = false)，自己加的成员标 @Unique，读私有成员写 accessor 接口（见 MultiPlayerGameModeDelayAccessor / SettingGroupAccessor）
* 混 meteor 自己的类也是 remap = false
* 要碰的原版字段先在 resources/meteor-modern-support.accesswidener 里开放


