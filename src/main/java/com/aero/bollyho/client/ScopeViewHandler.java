package com.aero.bollyho.client;

import com.aero.bollyho.BollyhoMod;
import com.aero.bollyho.block.ScopeBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 炮镜视角管理器（纯客户端）
 *
 * <p>管理炮镜视角的进入、退出和状态追踪。使用单例模式。</p>
 *
 * <h3>核心概念</h3>
 * <ul>
 *   <li><b>炮镜方块位置（scopePos）</b> — 玩家右键的那个炮镜方块的坐标</li>
 *   <li><b>炮镜朝向（scopeFacing）</b> — 炮镜镜头指向的方向（{@link Direction}）</li>
 *   <li><b>目标方块位置（targetPos）</b> — 炮镜前方紧挨着的那个方块，
 *       相机会放在这个方块的中心，从这个位置"向外看"</li>
 * </ul>
 *
 * <h3>使用流程</h3>
 * <ol>
 *   <li>玩家右键炮镜方块 → 调用 {@link #enterScopeView(BlockPos, Direction)}</li>
 *   <li>相机事件每帧检查 → 如果在视角中，覆盖相机朝向；
 *       位置由 {@link com.aero.bollyho.mixin.CameraMixin} 在 {@code Camera.setup()} 末尾覆盖</li>
 *   <li>玩家按潜行键 → 调用 {@link #exitScopeView()}</li>
 * </ol>
 *
 * @see ScopeBlock 炮镜方块类
 */
@EventBusSubscriber(modid = BollyhoMod.MODID, value = Dist.CLIENT)
public final class ScopeViewHandler {

    // ==================== 单例字段 ====================

    /** 单例实例，通过 {@link #getInstance()} 访问 */
    private static final ScopeViewHandler INSTANCE = new ScopeViewHandler();

    /** 是否正在炮镜视角中 */
    private boolean isInScopeView;

    /** 炮镜方块所在位置 */
    private BlockPos scopePos;

    /** 炮镜镜头朝向 */
    private Direction scopeFacing;

    /** 目标方块位置（炮镜前方紧挨着的方块，相机放在这里） */
    private BlockPos targetPos;

    // ==================== 退出相关 ====================

    /** 上一帧潜行键是否被按下（用于检测"按下瞬间"） */
    private boolean wasSneaking;

    // ==================== 构造函数（私有：单例模式） ====================

    private ScopeViewHandler() {}

    /** @return 单例实例 */
    public static ScopeViewHandler getInstance() {
        return INSTANCE;
    }

    // ==================== 公开 API ====================

    /**
     * 进入炮镜视角
     *
     * <p>只在客户端调用。保存炮镜位置和朝向，沿 facing 方向穿透所有实心方块，
     * 将相机定在最前方实心方块的中心。配合 Mixin 隐藏该方块，
     * 形成视觉穿透——不修改任何方块，纯视觉效果。</p>
     *
     * <p>示例：炮镜朝北 → [石头] [石头] [泥土] [空气]
     * <br>→ 穿透石头和泥土，停在泥土中心，Mixin 隐藏泥土，看得到空气</p>
     *
     * @param pos    炮镜方块的世界坐标
     * @param facing 炮镜镜头的朝向
     */
    public void enterScopeView(BlockPos pos, Direction facing) {
        this.isInScopeView = true;
        this.scopePos = pos.immutable();
        this.scopeFacing = facing;

        // 穿透扫描：沿 facing 方向穿透所有实心方块，停在最前方
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.player != null ? mc.player.level() : null;
        this.targetPos = findPenetrationTarget(level, pos, facing);

        // 标记目标方块所在区块为"脏"，触发重新烘焙
        // Mixin 会在渲染时跳过该方块（因为 isInScopeView 现在是 true）
        markSectionDirty(this.targetPos);

        // 扫描周围 3×3×3 区域，寻找 CBC 火炮底座
        if (level != null) {
            CbcIntegration.getInstance().scanForCannon(level, scopePos, scopeFacing);
        }

        BollyhoMod.LOGGER.debug("进入炮镜视角: scopePos={}, facing={}, targetPos={}",
                scopePos, scopeFacing, targetPos);
    }

    /**
     * 退出炮镜视角
     *
     * <p>清除视角状态，标记区块重新渲染以恢复隐藏的方块。</p>
     */
    public void exitScopeView() {
        // 先保存旧的目标方块位置（需要在清除前保存，用于标记区块脏）
        BlockPos oldTargetPos = this.targetPos;

        // 清除状态
        this.isInScopeView = false;
        this.scopePos = null;
        this.scopeFacing = null;
        this.targetPos = null;

        // 标记旧目标方块所在区块为"脏"，触发重新烘焙
        // 此时 isInScopeView 已经是 false，Mixin 不会跳过方块，区块正常渲染
        if (oldTargetPos != null) {
            markSectionDirty(oldTargetPos);
        }

        // 清除 CBC 火炮缓存
        CbcIntegration.getInstance().clearCache();

        BollyhoMod.LOGGER.debug("退出炮镜视角");
    }

    // ==================== 状态查询 ====================

    /** @return 是否正在炮镜视角中 */
    public boolean isInScopeView() {
        return isInScopeView && scopePos != null;
    }

    /** @return 炮镜方块位置（可能为 null） */
    public BlockPos getScopePos() {
        return scopePos;
    }

    /** @return 炮镜镜头朝向（可能为 null） */
    public Direction getScopeFacing() {
        return scopeFacing;
    }

    /** @return 目标方块位置（相机位置，可能为 null） */
    public BlockPos getTargetPos() {
        return targetPos;
    }

    // ==================== 事件处理 ====================

    /**
     * 每帧客户端 Tick 事件
     *
     * <p>职责：</p>
     * <ul>
     *   <li>检测潜行键按下 → 退出炮镜视角</li>
     *   <li>检测炮镜方块是否被破坏 → 自动退出</li>
     * </ul>
     *
     * @param event 客户端 Tick 事件（Post 阶段）
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        ScopeViewHandler handler = getInstance();

        if (!handler.isInScopeView() || player == null) {
            return;
        }

        // ---- 检测潜行键按下（只触发一次，不是按住） ----
        boolean isSneaking = mc.options.keyShift.isDown();
        if (isSneaking && !handler.wasSneaking) {
            // 潜行键"刚刚按下" → 退出炮镜视角
            handler.exitScopeView();
            return;
        }
        handler.wasSneaking = isSneaking;

        // ---- 检测炮镜方块是否仍然存在 ----
        Level level = player.level();
        BlockState state = level.getBlockState(handler.scopePos);
        if (!(state.getBlock() instanceof ScopeBlock)) {
            // 炮镜方块被破坏或替换了 → 自动退出
            BollyhoMod.LOGGER.debug("炮镜方块消失，自动退出炮镜视角");
            handler.exitScopeView();
        }
    }

    /**
     * 相机角度计算事件
     *
     * <p>在炮镜视角模式下，覆盖相机的<b>朝向</b>。
     * 相机<b>位置</b>由 {@link com.aero.bollyho.mixin.CameraMixin} 在
     * {@code Camera.setup()} 末尾覆盖（因为原版在事件触发后才写入实体位置）。</p>
     *
     * <p>这个事件每帧在相机计算时触发，优先级高于原版相机逻辑。</p>
     *
     * @param event 相机角度计算事件
     */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        ScopeViewHandler handler = getInstance();
        if (!handler.isInScopeView()) {
            return;
        }

        // ---- 设置相机朝向 ----
        // 相机位置由 CameraMixin 在 Camera.setup() 末尾设置（在实体位置被写入之后）
        // 炮镜朝向 direction，所以从目标方块看出去的视线方向就是 facing
        // 把 Direction 转成偏航角（yaw）和俯仰角（pitch）
        float[] rotations = directionToYawPitch(handler.scopeFacing);
        float yaw = rotations[0];
        float pitch = rotations[1];

        // ---- 叠加 CBC 火炮俯仰 ----
        // 如果有 CBC 火炮底座在附近，同步俯仰角
        // CBC 俯仰：正=仰角（炮口朝上）；MC 相机俯仰：负=仰角
        // 所以用减法：cameraPitch = basePitch - cannonPitch
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Float cannonPitch = CbcIntegration.getInstance()
                    .readCannonPitch(mc.player.level());
            if (cannonPitch != null) {
                pitch -= cannonPitch;
            }
        }

        // 设置偏航角、俯仰角、翻滚角
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(0);
    }

    /**
     * 手部渲染事件
     *
     * <p>在炮镜视角中取消手部渲染，避免玩家的手/手持物品遮住视野。</p>
     *
     * @param event 手部渲染事件
     */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (getInstance().isInScopeView()) {
            event.setCanceled(true);
        }
    }

    /**
     * GUI 图层渲染前事件
     *
     * <p>在炮镜视角中取消准星的渲染。
     * 准星是游戏 HUD 中的一个图层（layer），通过图层名识别。</p>
     *
     * <p>注意：这里匹配图层资源路径中包含 "crosshair" 的所有图层，
     * 确保兼容各种模组修改后的准星。</p>
     *
     * @param event GUI 图层渲染前事件
     */
    @SubscribeEvent
    public static void onRenderGuiLayerPre(RenderGuiLayerEvent.Pre event) {
        if (getInstance().isInScopeView()) {
            // 检查图层名称是否包含 "crosshair"（准星）
            // 使用小写匹配，兼容不同模组的命名方式
            String layerPath = event.getName().getPath().toLowerCase();
            if (layerPath.contains("crosshair")) {
                event.setCanceled(true);
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 穿透扫描：沿 facing 方向穿透所有非空气方块，找到最前方的方块
     *
     * <p>从炮镜前方第一个方块出发，沿 facing 方向逐个检查：</p>
     * <ul>
     *   <li>遇到 <b>非空气方块</b> → 记录位置，继续前进（穿透）</li>
     *   <li>遇到 <b>空气方块</b> → 停止，返回最后一个非空气方块</li>
     *   <li>如果第一个方块就是空气 → 返回该空气方块位置</li>
     * </ul>
     *
     * <p>此方法 <b>不会修改任何方块</b>，只是读取和计算。
     * 视觉穿透效果由 Mixin 在渲染阶段跳过该方块的绘制来实现。</p>
     *
     * <p>限制最大穿透 50 格，防止扫描到世界尽头。</p>
     *
     * @param level    当前世界（可为 null）
     * @param scopePos 炮镜方块位置
     * @param facing   穿透方向
     * @return 最前方方块的坐标（相机应放置的位置）
     */
    private static BlockPos findPenetrationTarget(Level level, BlockPos scopePos, Direction facing) {
        BlockPos current = scopePos.relative(facing); // 前方第一个方块
        BlockPos lastSolid = null;                    // 最后一个非空气方块

        int maxDistance = 50;
        for (int i = 0; i < maxDistance; i++) {
            // 安全检查：如果 world 为 null，直接返回当前
            if (level == null) {
                return current;
            }

            // 检查是否还在世界范围内
            if (!level.isInWorldBounds(current)) {
                break;
            }

            BlockState state = level.getBlockState(current);

            if (state.isAir()) {
                // 遇到空气 → 停止
                break;
            }

            // 非空气方块 → 记录下来，继续向前穿透
            lastSolid = current;
            current = current.relative(facing);
        }

        // 如果穿透过至少一个方块 → 返回最后一个
        // 如果第一个方块就是空气 → 返回第一个方块（空气）
        return lastSolid != null ? lastSolid : scopePos.relative(facing);
    }

    /**
     * 标记指定方块所在的区块为"脏"，触发重新烘焙
     *
     * <p>区块烘焙（chunk baking）负责把方块几何体编译成 GPU 可用的顶点缓冲区。
     * 标记区块为"脏"后，Minecraft 会在下一帧重新烘焙该区块。</p>
     *
     * <p>这是配合 {@link BlockRenderDispatcherMixin} 的关键操作：
     * 进入视角时标记 → 烘焙时 Mixin 跳过目标方块；
     * 退出视角时标记 → 烘焙时 Mixin 不再跳过，目标方块恢复渲染。</p>
     *
     * @param pos 需要刷新烘焙的方块位置
     */
    private static void markSectionDirty(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            // 区块坐标 = 方块坐标 / 16（用右移4位实现）
            int sectionX = pos.getX() >> 4;
            int sectionY = pos.getY() >> 4;
            int sectionZ = pos.getZ() >> 4;
            mc.levelRenderer.setSectionDirty(sectionX, sectionY, sectionZ);
        }
    }

    /**
     * 将 {@link Direction} 转换为偏航角（yaw）和俯仰角（pitch）
     *
     * <p>偏航角：水平旋转角，0=南，90=西，180=北，-90/270=东</p>
     * <p>俯仰角：垂直旋转角，-90=朝上，0=水平，90=朝下</p>
     *
     * @param facing 方向
     * @return float[2] — [yaw, pitch]
     */
    private static float[] directionToYawPitch(Direction facing) {
        return switch (facing) {
            case DOWN  -> new float[]{0, 90};       // 朝下看
            case UP    -> new float[]{0, -90};      // 朝上看
            case NORTH -> new float[]{180, 0};      // 朝北
            case SOUTH -> new float[]{0, 0};        // 朝南
            case WEST  -> new float[]{90, 0};       // 朝西
            case EAST  -> new float[]{-90, 0};      // 朝东
        };
    }
}
