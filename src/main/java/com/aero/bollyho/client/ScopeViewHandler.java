package com.aero.bollyho.client;

import com.aero.bollyho.BollyhoMod;
import com.aero.bollyho.block.ScopeBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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

    // ==================== SubLevel 跟踪重试 ====================

    /**
     * SubLevel 跟踪重试计数器
     *
     * <p>当炮镜从主世界消失（被旋转轴承装配到 SubLevel）时，
     * SubLevel 数据可能还没从服务器同步到客户端（1-2 tick 延迟）。
     * 此计数器允许在放弃前重试若干次。</p>
     */
    private int subLevelRetryCount;

    /**
     * SubLevel 跟踪最大重试次数（20 ticks = 1 秒）
     * <p>远超典型的网络同步延迟（1-3 ticks），确保不会误退出。</p>
     */
    private static final int MAX_SUBLEVEL_RETRIES = 20;

    // ==================== SubLevel 连续角度跟踪 ====================

    /**
     * 从 SubLevel 的 renderPose 计算出的连续偏航角（度）
     *
     * <p>不同于 {@link #scopeFacing}（离散 Direction），这是从
     * {@code renderPose.transformNormal()} 通过 atan2 计算的连续值，
     * 使得轴承旋转时视角平滑过渡而非跳变。</p>
     */
    private float subLevelYaw;

    /**
     * 从 SubLevel 的 renderPose 计算出的连续俯仰角（度）
     */
    private float subLevelPitch;

    /**
     * 从 SubLevel 的 renderPose 计算出的相机世界坐标（Vec3 精度）
     *
     * <p>相比 {@link #targetPos}（BlockPos 精度），此字段保留小数部分，
     * 在 SubLevel 旋转时相机位置更加平滑。</p>
     */
    private Vec3 subLevelCameraPos;

    /** 调试：SubLevel 跟踪帧计数器 */
    int debugSubLevelTick;

    /** 调试：onCameraAngles 帧计数器 */
    int debugCameraAnglesTick;

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

        // 立即尝试 SubLevel 跟踪（参考 createrenxin）：
        // 玩家在船上右键炮镜时，level = SubLevel 内部 Level，pos = 内部坐标。
        // tryTrackInSubLevel 用坐标定位 SubLevel，无需搜索轴承。
        if (level instanceof ClientLevel cl) {
            boolean tracked = SableIntegration.getInstance()
                    .tryTrackInSubLevel(cl, scopePos, targetPos, scopeFacing);
            if (tracked) {
                BollyhoMod.LOGGER.info("[DEBUG] ScopeView: 进入视角即激活 SubLevel 跟踪！"
                        + " scopePos={} targetPos={} facing={}", scopePos, targetPos, scopeFacing);
            }
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
        this.subLevelRetryCount = 0;
        this.subLevelYaw = 0;
        this.subLevelPitch = 0;
        this.subLevelCameraPos = null;
        this.debugSubLevelTick = 0;
        this.debugCameraAnglesTick = 0;

        // 标记旧目标方块所在区块为"脏"，触发重新烘焙
        // 此时 isInScopeView 已经是 false，Mixin 不会跳过方块，区块正常渲染
        if (oldTargetPos != null) {
            markSectionDirty(oldTargetPos);
        }

        // 清除 CBC 火炮缓存和 Sable 跟踪
        CbcIntegration.getInstance().clearCache();
        SableIntegration.getInstance().clearCache();

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

    /** @return 从 SubLevel 计算的连续偏航角（度） */
    public float getSubLevelYaw() {
        return subLevelYaw;
    }

    /** @return 从 SubLevel 计算的连续俯仰角（度） */
    public float getSubLevelPitch() {
        return subLevelPitch;
    }

    /** @return 从 SubLevel 计算的相机世界坐标（Vec3 精度，可能为 null） */
    public Vec3 getSubLevelCameraPos() {
        return subLevelCameraPos;
    }

    // ==================== 事件处理 ====================

    /**
     * 每帧客户端 Tick 事件
     *
     * <p>职责：</p>
     * <ul>
     *   <li>检测潜行键按下 → 退出炮镜视角</li>
     *   <li><b>动态跟踪</b>：检测炮镜方块是否被旋转轴承等装置移动/旋转，
     *       自动更新位置、朝向和目标方块。
     *       支持主世界跟踪和 Sable SubLevel 跟踪两种模式。</li>
     *   <li>检测炮镜方块是否被破坏 → 自动退出</li>
     * </ul>
     *
     * <p>跟踪逻辑：</p>
     * <ol>
     *   <li>如果正在 SubLevel 中跟踪 → 每帧从 SubLevel pose 更新世界坐标</li>
     *   <li>否则检查主世界炮镜是否还在原位 → 朝向变了就更新</li>
     *   <li>主世界找不到 → 在附近搜索 → 还找不到就尝试 SubLevel</li>
     * </ol>
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
            handler.exitScopeView();
            return;
        }
        handler.wasSneaking = isSneaking;

        // ---- 动态跟踪：检测炮镜方块的位置和朝向变化 ----
        Level level = player.level();
        SableIntegration sable = SableIntegration.getInstance();

        if (sable.isTracking()) {
            // ============================================================
            // SubLevel 跟踪模式：炮镜在旋转轴承的物理子世界中
            // 每帧从 SubLevel 的 renderPose 解算世界坐标
            // ============================================================
            handler.recalculateFromSubLevel(mc);
            handler.subLevelRetryCount = 0; // 跟踪正常，重置重试计数
            // DEBUG: 节流日志（每 60 帧）
            handler.debugSubLevelTick++;
            if (handler.debugSubLevelTick % 60 == 1) {
                BollyhoMod.LOGGER.info("[DEBUG] ScopeView: SubLevel跟踪中 第{}帧 | "
                        + "yaw={} pitch={} cameraPos=({}) targetPos={}",
                        handler.debugSubLevelTick, handler.subLevelYaw, handler.subLevelPitch,
                        handler.subLevelCameraPos, handler.targetPos);
            }
        } else {
            // ============================================================
            // 主世界跟踪模式
            // ============================================================

            // 周期性尝试 SubLevel 跟踪（用玩家所在 Level，可能是 SubLevel 内部 Level）。
            // 即使炮镜在内部 Level"原位"可见，也应尝试用坐标定位 SubLevel。
            if (handler.subLevelRetryCount <= MAX_SUBLEVEL_RETRIES
                    && handler.debugSubLevelTick % 20 == 0
                    && level instanceof ClientLevel clientLevel2
                    && sable.tryTrackInSubLevel(clientLevel2, handler.scopePos,
                            handler.targetPos, handler.scopeFacing)) {
                handler.subLevelRetryCount = 0;
                BollyhoMod.LOGGER.info("[DEBUG] ScopeView: 炮镜已转入 SubLevel 跟踪模式(周期尝试)！"
                        + " scopePos={} targetPos={} facing={}",
                        handler.scopePos, handler.targetPos, handler.scopeFacing);
                handler.recalculateFromSubLevel(mc);
                return;
            }
            if (handler.debugSubLevelTick % 20 == 0 && handler.subLevelRetryCount <= MAX_SUBLEVEL_RETRIES) {
                handler.subLevelRetryCount++;
            }
            handler.debugSubLevelTick++;

            BlockState state = level.getBlockState(handler.scopePos);
            BlockPos oldTargetPos = handler.targetPos;

            if (state.getBlock() instanceof ScopeBlock) {
                // 炮镜仍在原位 — 检查朝向是否变化
                handler.subLevelRetryCount = 0; // 炮镜还在，重置
                Direction currentFacing = ScopeBlock.getViewDirection(state);
                if (currentFacing != handler.scopeFacing) {
                    BollyhoMod.LOGGER.debug("炮镜朝向变化: {} → {}", handler.scopeFacing, currentFacing);
                    handler.scopeFacing = currentFacing;
                    handler.targetPos = findPenetrationTarget(level, handler.scopePos, currentFacing);
                }

                // 尝试运动学轴承跟踪（主世界模式，方块未被移入 SubLevel）
                if (!sable.isKinematicTracking()) {
                    sable.tryTrackKinematicBearing(level, handler.scopePos);
                }
                if (sable.isKinematicTracking()) {
                    sable.readBearingAngle(level);
                }
            } else {
                // 炮镜不在原位 — 尝试定位新位置
                // 先清除运动学轴承跟踪（如果进入 SubLevel，由 SubLevel 跟踪接管）
                sable.clearKinematicTracking();
                BlockPos newPos = findNearbyScope(level, handler.scopePos);
                if (newPos != null) {
                    handler.subLevelRetryCount = 0; // 在主世界找到了，重置
                    BlockState newState = level.getBlockState(newPos);
                    Direction newFacing = ScopeBlock.getViewDirection(newState);
                    BollyhoMod.LOGGER.debug("炮镜位置变化: {} → {}, 朝向: {}",
                            handler.scopePos, newPos, newFacing);
                    handler.scopePos = newPos;
                    handler.scopeFacing = newFacing;
                    handler.targetPos = findPenetrationTarget(level, newPos, newFacing);
                } else if (level instanceof ClientLevel clientLevel
                        && sable.tryTrackInSubLevel(clientLevel, handler.scopePos,
                                handler.targetPos, handler.scopeFacing)) {
                    // 主世界找不到 → 在 Sable SubLevel 中搜索成功
                    handler.subLevelRetryCount = 0; // 跟踪成功，重置
                    BollyhoMod.LOGGER.info("[DEBUG] ScopeView: 炮镜已转入 SubLevel 跟踪模式！"
                            + " scopePos={} targetPos={} facing={}",
                            handler.scopePos, handler.targetPos, handler.scopeFacing);
                    handler.recalculateFromSubLevel(mc);
                    return;
                } else if (handler.subLevelRetryCount < MAX_SUBLEVEL_RETRIES) {
                    // SubLevel 数据可能还在从服务器同步中 → 继续重试
                    handler.subLevelRetryCount++;
                    if (handler.subLevelRetryCount <= 3 || handler.subLevelRetryCount % 5 == 0) {
                        BollyhoMod.LOGGER.info("[DEBUG] ScopeView: SubLevel 跟踪尝试失败，重试 {}/{}"
                                + " (scopePos={}, targetPos={}, facing={})",
                                handler.subLevelRetryCount, MAX_SUBLEVEL_RETRIES,
                                handler.scopePos, handler.targetPos, handler.scopeFacing);
                    }
                    // 保持当前视角不变（不更新 targetPos），给网络同步留时间
                    return;
                } else {
                    // 重试次数耗尽 → 真正退出
                    BollyhoMod.LOGGER.debug("炮镜方块消失（已重试 {} 次），自动退出炮镜视角",
                            handler.subLevelRetryCount);
                    handler.exitScopeView();
                    return;
                }
            }

            // 如果目标方块位置变了，刷新旧/新目标方块的渲染
            if (oldTargetPos != null && !oldTargetPos.equals(handler.targetPos)) {
                markSectionDirty(oldTargetPos);
            }
            if (handler.targetPos != null && !handler.targetPos.equals(oldTargetPos)) {
                markSectionDirty(handler.targetPos);
            }
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

        Minecraft mc = Minecraft.getInstance();
        CbcIntegration cbc = CbcIntegration.getInstance();
        SableIntegration sable = SableIntegration.getInstance();
        CbcIntegration.CannonType cannonType = cbc.getCannonType();

        // ============================================================
        // SubLevel 跟踪模式：使用 renderPose 解算的连续角度
        // ============================================================
        if (sable.isTracking()) {
            // 基础角度来自 SubLevel 的 renderPose（连续，跟随轴承旋转）
            float yaw = handler.subLevelYaw;
            float pitch = handler.subLevelPitch;

            if (cannonType == CbcIntegration.CannonType.ORIGINAL_CBC && mc.player != null) {
                // 原版 CBC 火炮底座：叠加火炮偏航和俯仰
                Float cannonYaw = cbc.readCannonYaw(mc.player.level());
                Float cannonPitch = cbc.readCannonPitch(mc.player.level());
                if (cannonYaw != null) yaw += cannonYaw;
                if (cannonPitch != null) pitch -= cannonPitch;
            } else if (cannonType == CbcIntegration.CannonType.COMPACT_MOUNT && mc.player != null) {
                // 紧凑式火炮底座：只叠加俯仰，偏航完全跟随轴承
                Float cannonPitch = cbc.readCannonPitch(mc.player.level());
                if (cannonPitch != null) pitch -= cannonPitch;
            }
            // 无火炮 → 纯 SubLevel 旋转

            // DEBUG: 首帧和每 60 帧输出一次
            handler.debugCameraAnglesTick++;
            if (handler.debugCameraAnglesTick <= 2 || handler.debugCameraAnglesTick % 60 == 0) {
                BollyhoMod.LOGGER.info("[DEBUG] ScopeView.onCameraAngles 第{}帧: "
                        + "subLevel(yaw={:.1f}, pitch={:.1f}) cannonType={} → event(yaw={:.1f}, pitch={:.1f})",
                        handler.debugCameraAnglesTick,
                        handler.subLevelYaw, handler.subLevelPitch,
                        cannonType, yaw, pitch);
            }

            event.setYaw(yaw);
            event.setPitch(pitch);
            event.setRoll(0);
            return;
        }

        // ============================================================
        // 运动学轴承跟踪模式：从 SwivelBearing 直接读取旋转角度
        // ============================================================
        if (sable.isKinematicTracking()) {
            float[] baseRotations = directionToYawPitch(handler.scopeFacing);
            float yaw = baseRotations[0] - sable.getKinematicBearingAngle();
            float pitch = baseRotations[1];

            if (cannonType == CbcIntegration.CannonType.ORIGINAL_CBC && mc.player != null) {
                Float cannonYaw = cbc.readCannonYaw(mc.player.level());
                Float cannonPitch = cbc.readCannonPitch(mc.player.level());
                if (cannonYaw != null) yaw += cannonYaw;
                if (cannonPitch != null) pitch -= cannonPitch;
            } else if (cannonType == CbcIntegration.CannonType.COMPACT_MOUNT && mc.player != null) {
                Float cannonPitch = cbc.readCannonPitch(mc.player.level());
                if (cannonPitch != null) pitch -= cannonPitch;
            }

            event.setYaw(yaw);
            event.setPitch(pitch);
            event.setRoll(0);
            return;
        }

        // ============================================================
        // 主世界模式：现有逻辑
        // ============================================================

        if (cannonType == CbcIntegration.CannonType.ORIGINAL_CBC && mc.player != null) {
            // 原版 CBC 火炮底座：旋转轴在炮镜视角所在方块中心
            Float cannonYaw = cbc.readCannonYaw(mc.player.level());
            Float cannonPitch = cbc.readCannonPitch(mc.player.level());

            float yaw;
            if (cannonYaw != null) {
                yaw = cannonYaw;
            } else {
                yaw = directionToYawPitch(handler.scopeFacing)[0];
            }

            float pitch = directionToYawPitch(handler.scopeFacing)[1];
            if (cannonPitch != null) {
                pitch -= cannonPitch;
            }

            event.setYaw(yaw);
            event.setPitch(pitch);
            event.setRoll(0);

        } else if (cannonType == CbcIntegration.CannonType.COMPACT_MOUNT && mc.player != null) {
            Float cannonPitch = cbc.readCannonPitch(mc.player.level());

            float[] rotations = directionToYawPitch(handler.scopeFacing);
            float yaw = rotations[0];
            float pitch = rotations[1];

            if (cannonPitch != null) {
                pitch -= cannonPitch;
            }

            event.setYaw(yaw);
            event.setPitch(pitch);
            event.setRoll(0);

        } else {
            float[] rotations = directionToYawPitch(handler.scopeFacing);
            event.setYaw(rotations[0]);
            event.setPitch(rotations[1]);
            event.setRoll(0);
        }
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
     * 在指定中心周围搜索炮镜方块
     *
     * <p>当旋转轴承或活塞移动了炮镜方块后，原位置不再有炮镜，
     * 此方法在半径 5 格范围内搜索炮镜方块的新位置。</p>
     *
     * <p>搜索范围 11×11×11（±5 各方向），足以覆盖绝大多数
     * 机械动力旋转轴承上的结构活动范围。</p>
     *
     * @param level  当前世界
     * @param center 搜索中心（通常是旧的 scopePos）
     * @return 找到的炮镜方块位置，或 null
     */
    private static BlockPos findNearbyScope(Level level, BlockPos center) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int radius = 5;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mutable.set(
                            center.getX() + dx,
                            center.getY() + dy,
                            center.getZ() + dz
                    );
                    if (level.getBlockState(mutable).getBlock() instanceof ScopeBlock) {
                        return mutable.immutable();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 Sable SubLevel 的 renderPose 重新计算炮镜的世界坐标
     *
     * <p>当炮镜安装在旋转轴承上（被移入 SubLevel），每帧从
     * SubLevel 的 renderPose 变换局部偏移量到世界坐标。
     * 使用几何变换法——不需要搜索 SubLevel 内部方块。</p>
     *
     * <p>工作流程：</p>
     * <ol>
     *   <li>从 SableIntegration 读取当前帧的世界 scopePos</li>
     *   <li>从 SableIntegration 读取当前帧的世界 targetPos（相机位置）</li>
     *   <li>从 SableIntegration 读取当前帧的世界 facing</li>
     *   <li>更新 handler 状态</li>
     * </ol>
     *
     * @param mc Minecraft 实例
     */
    private void recalculateFromSubLevel(Minecraft mc) {
        SableIntegration sable = SableIntegration.getInstance();
        if (!sable.isTracking()) {
            return;
        }

        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(true);

        Vec3 worldScopePos = sable.getWorldPosition(partialTick);
        Vec3 worldTargetPos = sable.getWorldTargetPosition(partialTick);
        Direction worldFacing = sable.getWorldFacing(partialTick);
        Float continuousYaw = sable.getCameraYaw(partialTick);
        Float continuousPitch = sable.getCameraPitch(partialTick);

        // DEBUG: 所有值都为空时说明跟踪中断
        if (worldScopePos == null || worldTargetPos == null || worldFacing == null) {
            if (this.debugSubLevelTick <= 1) {
                BollyhoMod.LOGGER.warn("[DEBUG] ScopeView.recalculate: 返回null!"
                        + " scopePos={} targetPos={} facing={} yaw={} pitch={}",
                        worldScopePos, worldTargetPos, worldFacing, continuousYaw, continuousPitch);
            }
            return;
        }

        BlockPos oldTargetPos = this.targetPos;

        this.scopePos = BlockPos.containing(worldScopePos);
        this.scopeFacing = worldFacing;
        this.targetPos = BlockPos.containing(worldTargetPos);

        // 连续角度：从 SubLevel renderPose 解算，用于平滑旋转
        if (continuousYaw != null) {
            this.subLevelYaw = continuousYaw;
        }
        if (continuousPitch != null) {
            this.subLevelPitch = continuousPitch;
        }
        // 相机位置保留 Vec3 精度（避免 BlockPos.containing 的截断抖动）
        this.subLevelCameraPos = worldTargetPos;

        // DEBUG: 首帧详细输出
        if (this.debugSubLevelTick == 0) {
            BollyhoMod.LOGGER.info("[DEBUG] ScopeView.recalculate 首帧:"
                    + " scopePos={} targetPos={} facing={} yaw={} pitch={} cameraPos=({}) oldTargetPos={}",
                    this.scopePos, this.targetPos, this.scopeFacing,
                    this.subLevelYaw, this.subLevelPitch, this.subLevelCameraPos, oldTargetPos);
        }

        // 刷新目标方块渲染（如果变化了）
        if (oldTargetPos != null && !oldTargetPos.equals(this.targetPos)) {
            markSectionDirty(oldTargetPos);
        }
        if (this.targetPos != null && !this.targetPos.equals(oldTargetPos)) {
            markSectionDirty(this.targetPos);
        }
    }

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
