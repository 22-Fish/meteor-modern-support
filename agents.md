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

## 原版挖掘机制（ServerPlayerGameMode，26.1.1 字节码确认过）
* START：记 destroyPos 和 destroyProgressStart，回一个破坏动画包
* STOP：进度 = 当前手持工具的单tick进度 × (经过tick + 1)，≥ 0.7 当场破坏并直接 return；< 0.7 记进「延迟破坏」名额（全场只有一个，先占者得）
* 延迟破坏：每 tick 用当前手持工具重算同一个进度，够 1.0 才破坏 —— 这个方块必须手里拿着挖得动的工具
* 给新位置发 START 不会清掉延迟破坏名额，所以双挖时先点的那个还能照常被服务端补完
* 结束包那一下算出来不够 0.7 时方块也会掉进延迟破坏名额（不是只有「先点的那个」会）：结束包发出去几 tick 后方块还在 → 拿 1 tick 最佳工具让服务端补完，别只等着
* 麻烦的方块：挖完变成水/岩浆的（冰、水下方块），别用「是不是空气」判断挖完了

## 查原版代码
* 反编译成品：%USERPROFILE%\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.1\minecraft-merged-deobf-26.1.1.jar
* 没有源码时用 javap -p -c -cp <jar> net.minecraft.server.level.ServerPlayerGameMode 看字节码

## Grim 挖掘检查（发包挖掘按这个对包写逻辑，源码在 ..\Grim-2.0）
* FastBreak 的延迟部分：收到开始包时算「距上一个结束包过了多久」，≥275ms 才把缓冲降 10%，否则加 (300ms − 间隔)；缓冲 >1000ms → 标记 + 取消这个包
* 结论：结束包不能乱发 —— 每 tick 补发 STOP 的兜底，在服务端眼里就是「刚挖完一个方块」，间隔直接算成 0，延迟设多大都没用（Grim 只看包，不看方块有没有掉）
* 反过来也一样：**每发一个结束包都要把挖掘延迟重新计时**。只在「延迟没归零」时重置会漏 —— 挖得慢的方块（挖的时间 ≥ 延迟）结束时延迟早就归零了，下一个开始包会紧跟着发出去，间隔被算成 0
* 挖掘延迟要「按包算」而不是「按方块掉没掉算」：服务端/反作弊看到的就是开始包和结束包的时间差
* 一个 tick 里对两个不同位置发挖掘包 = 「一瞬间点了两下」（Grim 的 MultiBreak、PacketOrder 那一类都会抓）：同一个 tick 里要发主包和假包时，**主包放前面**，被连坐取消的是后面那个假包
* 双挖开局那个结束包（占延迟破坏名额用的）单独放一个 tick 发（原版 meteor-miku 是延时 50ms 发）：和 START、高空包挤在同一个 tick 的话容易被一起标记
* FastBreak 的速度部分：结束包会拿「预测时间 ceil(1/单tick进度)×50ms」比实际时间，快了就加缓冲，超 1000ms 一样标记 —— 所以「切工具阈值」放 100 以下就是提前收尾，挖多了会被驳回
* 但预测时间用的是「最后一次开始包指的那个方块」的挖掘速度：高空包（y 超上限、那个位置是空气）算出来是无限快 → 预测时间 0 → 只衰减不累计，70% 收尾才敢用
* WrongBreak：结束包的位置必须等于「最后一次开始包的位置」，否则标记 + 取消；同样被高空包绕过（空气算秒破 → 这条检查直接跳过）
* PositionBreakA：报的面必须在玩家眼睛那一侧（眼睛在方块西边就不能报东面），否则标记 + 取消；挖掘期间人一动，原来点的面就可能不成立 → 每次发包前按眼睛位置重算面
* RotationBreak（实验性）：发包那一刻「眼睛 → 方块」的射线要打得到方块（接受上一 tick 的角度），打不到先累计、之后取消包 → 准星不在方块上时先发一个角度包
* FarBreak（实验性）：眼睛到方块碰撞箱的距离超过交互距离（默认 4.5 格）→ 标记 + 取消，所以「挖掘范围」别超过交互距离
* AirLiquidBreak：对空气/水/岩浆/不可破坏的方块发开始/结束包 → 标记 + 取消（高空包必然吃到这条）
* MultiBreak（实验性）：同一个移动 tick 里破坏两个不同位置/不同面 → 先标记 + 取消第二个；高空包在同一 tick 多发一组包就会被它抓到
* 所有检查都会跑完（一个检查 cancel 不影响别的检查继续处理同一个包），所以取消某个包的同时，别的检查该记账还是照记
* 左键（攻击）相关的：PacketOrderB「攻击前没挥手 → 取消这次攻击」、PacketOrderI「同一 tick 里既挖方块又攻击 → 取消这次攻击」、NoSwingBreak「挖了没挥手 → 只记录」、MultiActionsF「同一 tick 里既碰实体又挖方块 → 取消挖掘」，所以挖掘的时候左键攻击会被驳回是正常的
