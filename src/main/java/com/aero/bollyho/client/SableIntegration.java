package com.aero.bollyho.client;

import com.aero.bollyho.BollyhoMod;
import com.aero.bollyho.block.ScopeBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Sable / 航空学（Simulated）集成工具类
 *
 * <p>负责跟踪安装在航空学旋转轴承（SwivelBearing）上的炮镜。
 * 使用<b>几何变换法</b>：通过 SubLevel 的 logicalPose().position()
 * 获取 SubLevel 原点，计算炮镜相对于原点的偏移量，
 * 每帧通过 renderPose.transformPosition() 将偏移量变换到世界坐标。</p>
 *
 * <h3>核心原理</h3>
 * <p>旋转轴承装配后，结构上的方块被移入 SubLevel。</p>
 * <ol>
 *   <li>SubLevel 原点 = logicalPose.position()（初始时 = 锚点方块中心 = 第一个被装配的方块）</li>
 *   <li>炮镜在 SubLevel 中的局部偏移 = 炮镜世界坐标 - SubLevel 原点</li>
 *   <li>每帧：worldPos = renderPose(partialTick).transformPosition(localOffset)</li>
 * </ol>
 *
 * <h3>关键反射方法（均已通过源码验证）</h3>
 * <ul>
 *   <li>{@code SubLevelContainer.getContainer(ClientLevel)} → ClientSubLevelContainer</li>
 *   <li>{@code SubLevelContainer.getSubLevel(UUID)} → SubLevel</li>
 *   <li>{@code SwivelBearingBlockEntity.getSubLevelID()} → UUID</li>
 *   <li>{@code SubLevel.isRemoved()} → boolean</li>
 *   <li>{@code SubLevel.logicalPose()} → Pose3dc</li>
 *   <li>{@code ClientSubLevel.renderPose(float)} → Pose3dc</li>
 *   <li>{@code Pose3dc.transformPosition(Vec3)} → Vec3</li>
 *   <li>{@code Pose3dc.transformNormal(Vec3)} → Vec3</li>
 *   <li>{@code Pose3dc.position()} → org.joml.Vector3dc</li>
 * </ul>
 */
public final class SableIntegration {

    private static final SableIntegration INSTANCE = new SableIntegration();

    // ---- Sable 类名（运行时反射加载） ----
    private static final String SUBCONTAINER_CLASS =
            "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final String SUBCLIENT_CLASS =
            "dev.ryanhcode.sable.sublevel.ClientSubLevel";
    private static final String POSE3DC_CLASS =
            "dev.ryanhcode.sable.companion.math.Pose3dc";
    private static final String SUBLEVEL_CLASS =
            "dev.ryanhcode.sable.sublevel.SubLevel";
    private static final String PLOT_CLASS =
            "dev.ryanhcode.sable.sublevel.plot.LevelPlot";

    // ---- 航空学类名 ----
    private static final String SWIVEL_BE_CLASS =
            "dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity";
    private static final String SWIVEL_PLATE_BE_CLASS =
            "dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlockEntity";

    // ---- JOML 类型 ----
    private static final String VECTOR3DC_CLASS = "org.joml.Vector3dc";

    // ---- 缓存的 Method 引用 ----
    private Method getContainerMethod;
    private Method getSubLevelMethod;
    private Method getAllSubLevelsMethod;
    private Method getPlotMethod;
    private Method plotGetSubLevelMethod;
    private Method isRemovedMethod;
    private Method logicalPoseMethod;
    private Method renderPoseMethod;
    private Method transformPosMethod;
    private Method transformNormalMethod;
    private Method posePositionMethod;
    private Method vec3dcXMethod;
    private Method vec3dcYMethod;
    private Method vec3dcZMethod;
    private Method subLevelGetLevelMethod;
    private Method subLevelGetUniqueIdMethod;
    private boolean methodsResolved;

    // ---- 跟踪状态 ----
    /** 跟踪的 SubLevel（Object 以避免编译依赖） */
    private Object trackedSubLevel;

    /** 炮镜在 SubLevel 局部空间中的偏移量（相对 SubLevel 原点 = logicalPose.position()） */
    private Vec3 localScopeOffset;

    /** 目标方块（相机位置）在 SubLevel 局部空间中的偏移量 */
    private Vec3 localTargetOffset;

    /** 炮镜朝向在 SubLevel 局部空间中的方向向量 */
    private Vec3 localFacingVec;

    /** 炮镜上方在 SubLevel 局部空间中的方向向量（用于世界空间上方向量） */
    private Vec3 localUpVec = new Vec3(0, 1, 0);

    // ==================== 运动学轴承跟踪（主世界模式） ====================
    /** 运动学模式下缓存的轴承方块位置（null 表示未跟踪） */
    private BlockPos kinematicBearingPos;
    /** 运动学模式下缓存的轴承当前旋转角度（度） */
    private float kinematicBearingAngle;

    // ==================== 调试计数器 ====================
    /** 每帧日志节流计数器（每 60 帧输出一次） */
    private int debugFrameCounter;
    /** getRenderPoseObj 回退到 logicalPose 的次数 */
    private int renderPoseFallbackCount;
    /** isTracking 因 isRemoved 异常失效的次数 */
    private int isRemovedExceptionCount;
    /** 是否已打印首帧的详细调试信息 */
    private boolean firstFrameDebugDone;
    /** isTracking() 返回 false 时的日志防刷屏计数器 */
    private int isTrackingDebugCount;

    public static SableIntegration getInstance() {
        return INSTANCE;
    }

    private SableIntegration() {}

    // ==================== 公开 API ====================

    /**
     * 检查是否正在跟踪 SubLevel 中的炮镜
     */
    public boolean isTracking() {
        if (trackedSubLevel == null || localScopeOffset == null) {
            if (isTrackingDebugCount < 3) {
                isTrackingDebugCount++;
                BollyhoMod.LOGGER.warn("[DEBUG] Sable.isTracking()=false: subLevel={}, offset={} (第{}次)",
                        trackedSubLevel != null, localScopeOffset != null, isTrackingDebugCount);
            }
            return false;
        }
        try {
            if (!methodsResolved) resolveMethods();
            if (isRemovedMethod != null) {
                boolean removed = (boolean) isRemovedMethod.invoke(trackedSubLevel);
                if (removed && debugFrameCounter == 0) {
                    BollyhoMod.LOGGER.warn("[DEBUG] Sable.isTracking()=false: SubLevel.isRemoved()=true");
                }
                return !removed;
            }
        } catch (Exception e) {
            isRemovedExceptionCount++;
            if (isRemovedExceptionCount <= 3) {
                BollyhoMod.LOGGER.error("[DEBUG] Sable.isTracking(): isRemoved 异常 (第{}次): {}",
                        isRemovedExceptionCount, e.toString());
            }
            clearCache();
            return false;
        }
        return true;
    }

    /**
     * 初始化 SubLevel 跟踪
     *
     * <p>在主世界中搜索炮镜附近的 SwivelBearingBlockEntity，
     * 获取其 SubLevel，使用 SubLevel 的 logicalPose.position()
     * 作为原点计算局部偏移量。</p>
     *
     * <p>SubLevel 原点由 Sable 的 assembleBlocks() 设置：
     * 初始 = 锚点方块中心（第一个被装配的方块），
     * 随后调整为质心。此原点在轴承旋转时不变（只有朝向改变）。</p>
     *
     * @param clientLevel    客户端主世界
     * @param scopeWorldPos  炮镜在主世界的位置（装配前的最后已知位置）
     * @param targetWorldPos 目标方块在主世界的位置（相机位置）
     * @param scopeFacing    炮镜朝向
     * @return true 如果成功初始化跟踪
     */
    public boolean tryTrackInSubLevel(
            ClientLevel clientLevel,
            BlockPos scopeWorldPos,
            BlockPos targetWorldPos,
            Direction scopeFacing
    ) {
        clearCache();

        if (!resolveMethods()) {
            BollyhoMod.LOGGER.warn("Sable: 反射方法解析失败，无法初始化跟踪");
            return false;
        }

        try {
            // 1. 获取 SubLevelContainer
            Object container = getContainerMethod.invoke(null, clientLevel);
            if (container == null) {
                BollyhoMod.LOGGER.debug("Sable: SubLevelContainer 为 null");
                return false;
            }

            // 2. 用坐标直接定位 SubLevel（参考 createrenxin 的 findSubLevelId）：
            //    container.getPlot(new ChunkPos(pos)).getSubLevel()
            //    炮镜可能在主世界坐标中（装配前最后位置），也可能在 SubLevel 内部
            //    坐标中。先按给定坐标找 plot，找不到再遍历所有 SubLevel。
            Object subLevel = findSubLevelByPos(container, scopeWorldPos);
            if (subLevel == null) {
                // 回退：遍历所有已加载 SubLevel，验证炮镜在哪个内部 Level 中
                subLevel = findSubLevelContainingScope(container, scopeWorldPos);
            }
            if (subLevel == null || (boolean) isRemovedMethod.invoke(subLevel)) {
                BollyhoMod.LOGGER.debug("Sable: 坐标定位 SubLevel 失败");
                return false;
            }

            // 3. 直接用 SubLevel 内部坐标（plot 坐标）作为偏移量。
            //    关键（参考 createrenxin）：不要减去 origin！
            //    renderPose.transformPosition(内部坐标) 内部会完成 plot→世界的转换。
            //    scopeWorldPos / targetWorldPos 是玩家所在 Level 中的坐标
            //    （如果玩家在 SubLevel 内，它们就是内部坐标）。
            Vec3 scopeCenter = new Vec3(
                    scopeWorldPos.getX() + 0.5,
                    scopeWorldPos.getY() + 0.5,
                    scopeWorldPos.getZ() + 0.5
            );
            Vec3 targetCenter = new Vec3(
                    targetWorldPos.getX() + 0.5,
                    targetWorldPos.getY() + 0.5,
                    targetWorldPos.getZ() + 0.5
            );
            Vec3 facingVec = new Vec3(
                    scopeFacing.getStepX(),
                    scopeFacing.getStepY(),
                    scopeFacing.getStepZ()
            );

            this.localScopeOffset = scopeCenter;
            this.localTargetOffset = targetCenter;
            this.localFacingVec = facingVec;
            this.trackedSubLevel = subLevel;

            Object uuid = subLevelGetUniqueIdMethod.invoke(subLevel);
            BollyhoMod.LOGGER.info(
                    "Sable: 已初始化 SubLevel 跟踪(坐标定位): subLevelId={}, scopeOffset(plot)={}, targetOffset(plot)={}, facing=({})",
                    uuid, localScopeOffset, localTargetOffset, localFacingVec);
            return true;

        } catch (Exception e) {
            BollyhoMod.LOGGER.error("Sable: 初始化 SubLevel 跟踪失败", e);
            clearCache();
            return false;
        }
    }

    /**
     * 通过坐标定位 SubLevel（参考 createrenxin 的 findSubLevelId）
     *
     * <p>流程：{@code container.getPlot(new ChunkPos(pos)).getSubLevel()}</p>
     *
     * @param container SubLevelContainer
     * @param pos       玩家所在 Level 中的坐标（可能为 SubLevel 内部坐标）
     * @return SubLevel 或 null
     */
    private Object findSubLevelByPos(Object container, BlockPos pos) {
        try {
            Object plot = getPlotMethod.invoke(container, new net.minecraft.world.level.ChunkPos(pos));
            if (plot == null) return null;
            Object subLevel = plotGetSubLevelMethod.invoke(plot);
            return subLevel;
        } catch (Exception e) {
            BollyhoMod.LOGGER.debug("[DEBUG] Sable: findSubLevelByPos 异常: {}", e.toString());
            return null;
        }
    }

    /**
     * 遍历所有已加载 SubLevel，验证哪个包含炮镜方块（回退方案）
     *
     * @param container SubLevelContainer
     * @param scopeWorldPos 炮镜最后已知位置
     * @return 包含炮镜的 SubLevel 或 null
     */
    private Object findSubLevelContainingScope(Object container, BlockPos scopeWorldPos) {
        try {
            Object allSubLevels = getAllSubLevelsMethod.invoke(container);
            if (!(allSubLevels instanceof List<?> list)) return null;

            for (Object subLevel : list) {
                if (subLevel == null || (boolean) isRemovedMethod.invoke(subLevel)) continue;
                Level internalLevel = (Level) subLevelGetLevelMethod.invoke(subLevel);
                if (internalLevel == null) continue;

                // 在内部 Level 中验证炮镜方块（内部坐标 = 世界大小坐标）
                BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
                int radius = 8;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            mutable.set(
                                    scopeWorldPos.getX() + dx,
                                    scopeWorldPos.getY() + dy,
                                    scopeWorldPos.getZ() + dz);
                            if (internalLevel.getBlockState(mutable).getBlock() instanceof ScopeBlock) {
                                BollyhoMod.LOGGER.info(
                                        "[DEBUG] Sable: 在SubLevel内部验证到炮镜! subLevelId={}, scopePos={}",
                                        subLevelGetUniqueIdMethod.invoke(subLevel), mutable.immutable());
                                return subLevel;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            BollyhoMod.LOGGER.warn("[DEBUG] Sable: findSubLevelContainingScope 异常", e);
        }
        return null;
    }

    /**
     * 获取炮镜在当前帧的世界坐标位置
     *
     * @param partialTick 部分 tick
     * @return 世界坐标（方块中心），如果未跟踪则返回 null
     */
    public Vec3 getWorldPosition(float partialTick) {
        if (!isTracking()) return null;

        try {
            Object pose = getRenderPoseObj(trackedSubLevel, partialTick);
            if (pose == null) return null;
            Vec3 result = (Vec3) transformPosMethod.invoke(pose, localScopeOffset);
            debugLogPerFrame("getWorldPosition", result);
            return result;
        } catch (Exception e) {
            BollyhoMod.LOGGER.error("Sable: 世界坐标变换失败", e);
            return null;
        }
    }

    /**
     * 获取目标方块（相机位置）在当前帧的世界坐标
     *
     * @param partialTick 部分 tick
     * @return 世界坐标（方块中心），如果未跟踪则返回 null
     */
    public Vec3 getWorldTargetPosition(float partialTick) {
        if (!isTracking() || localTargetOffset == null) return null;

        try {
            Object pose = getRenderPoseObj(trackedSubLevel, partialTick);
            if (pose == null) return null;
            return (Vec3) transformPosMethod.invoke(pose, localTargetOffset);
        } catch (Exception e) {
            BollyhoMod.LOGGER.error("Sable: 目标坐标变换失败", e);
            return null;
        }
    }

    /**
     * 获取炮镜在当前帧的世界朝向
     *
     * @param partialTick 部分 tick
     * @return 世界朝向，如果未跟踪则返回 null
     */
    public Direction getWorldFacing(float partialTick) {
        if (!isTracking() || localFacingVec == null) return null;

        try {
            Object pose = getRenderPoseObj(trackedSubLevel, partialTick);
            if (pose == null) return null;
            Vec3 worldDir = (Vec3) transformNormalMethod.invoke(pose, localFacingVec);
            return Direction.getNearest(worldDir.x, worldDir.y, worldDir.z);
        } catch (Exception e) {
            BollyhoMod.LOGGER.error("Sable: 世界朝向变换失败", e);
            return null;
        }
    }

    /**
     * 获取炮镜在当前帧的世界空间前向向量（连续精度）
     *
     * <p>用 {@code renderPose.transformNormal(localTargetOffset - localScopeOffset)}
     * 计算前向（参考 createrenxin 的 lookLocal - cameraLocal），
     * 归一化后返回。此方法供 CameraMixin 设置完整四元数旋转。</p>
     *
     * @param partialTick 部分 tick
     * @return 归一化世界空间前向向量，失败返回 null
     */
    public Vec3 getWorldForward(float partialTick) {
        if (!isTracking() || localScopeOffset == null || localTargetOffset == null) return null;

        try {
            Object pose = getRenderPoseObj(trackedSubLevel, partialTick);
            if (pose == null) return null;
            // 局部前向 = target - scope（plot 坐标差是纯方向）
            Vec3 localDir = localTargetOffset.subtract(localScopeOffset);
            double dirLen = localDir.length();
            if (dirLen < 0.0001) {
                // 退化：fallback 到 facing 方向
                localDir = localFacingVec;
            }
            Vec3 worldDir = (Vec3) transformNormalMethod.invoke(pose, localDir);
            double len = worldDir.length();
            if (len < 0.0001) return null;
            return new Vec3(worldDir.x / len, worldDir.y / len, worldDir.z / len);
        } catch (Exception e) {
            BollyhoMod.LOGGER.error("Sable: 世界前向向量计算失败", e);
            return null;
        }
    }

    /**
     * 获取炮镜在当前帧的世界空间上方向量
     *
     * <p>用 {@code renderPose.transformNormal(0, 1, 0)} 计算
     * （参考 createrenxin 的 up 向量）。</p>
     *
     * @param partialTick 部分 tick
     * @return 归一化世界空间上方向量，失败返回 null
     */
    public Vec3 getWorldUp(float partialTick) {
        if (!isTracking() || localUpVec == null) return null;

        try {
            Object pose = getRenderPoseObj(trackedSubLevel, partialTick);
            if (pose == null) return null;
            Vec3 worldUp = (Vec3) transformNormalMethod.invoke(pose, localUpVec);
            double len = worldUp.length();
            if (len < 0.0001) return null;
            return new Vec3(worldUp.x / len, worldUp.y / len, worldUp.z / len);
        } catch (Exception e) {
            BollyhoMod.LOGGER.error("Sable: 世界上方向量计算失败", e);
            return null;
        }
    }

    /**
     * 获取相机在当前帧的连续偏航角（非离散 Direction）
     *
     * <p>通过 {@link #getWorldForward(float)} 计算连续偏航角。
     * MC 偏航角: atan2(x, z); 0=南, 90=西, 180/-180=北, -90=东</p>
     *
     * @param partialTick 部分 tick
     * @return 偏航角（度），如果未跟踪则返回 null
     */
    public Float getCameraYaw(float partialTick) {
        Vec3 worldForward = getWorldForward(partialTick);
        if (worldForward == null) return null;
        float yaw = (float) Math.toDegrees(Math.atan2(worldForward.x, worldForward.z));
        debugLogPerFrame("getCameraYaw", "worldForward=("
                + String.format("%.2f,%.2f,%.2f", worldForward.x, worldForward.y, worldForward.z)
                + ") yaw=" + String.format("%.1f", yaw));
        return yaw;
    }

    /**
     * 获取相机在当前帧的连续俯仰角（非离散 Direction）
     *
     * <p>通过 {@link #getWorldForward(float)} 计算俯仰角。
     * MC 俯仰角：负=朝上，正=朝下。</p>
     *
     * @param partialTick 部分 tick
     * @return 俯仰角（度），如果未跟踪则返回 null
     */
    public Float getCameraPitch(float partialTick) {
        Vec3 worldForward = getWorldForward(partialTick);
        if (worldForward == null) return null;
        double horizontalDist = Math.sqrt(
                worldForward.x * worldForward.x + worldForward.z * worldForward.z);
        return (float) Math.toDegrees(Math.atan2(-worldForward.y, horizontalDist));
    }

    /**
     * 清除所有跟踪缓存
     */
    public void clearCache() {
        if (trackedSubLevel != null) {
            BollyhoMod.LOGGER.info("[DEBUG] Sable.clearCache(): 清除所有跟踪状态"
                    + " (renderPose回退次数={}, isRemoved异常次数={})",
                    renderPoseFallbackCount, isRemovedExceptionCount);
        }
        trackedSubLevel = null;
        localScopeOffset = null;
        localTargetOffset = null;
        localFacingVec = null;
        localUpVec = new Vec3(0, 1, 0);
        clearKinematicTracking();
        debugFrameCounter = 0;
        renderPoseFallbackCount = 0;
        isRemovedExceptionCount = 0;
        firstFrameDebugDone = false;
    }

    /**
     * 每帧调试日志（节流：第0帧输出初始化信息，之后每60帧输出摘要）
     */
    private void debugLogPerFrame(String method, Object value) {
        if (!firstFrameDebugDone) {
            firstFrameDebugDone = true;
            BollyhoMod.LOGGER.info("[DEBUG] Sable.{}(): 首帧值={}, localOffset=({}), localFacing=({})",
                    method, value, localScopeOffset, localFacingVec);
            return;
        }
        debugFrameCounter++;
        if (debugFrameCounter % 60 == 0) {
            BollyhoMod.LOGGER.info("[DEBUG] Sable.{}(): 第{}帧 值={}",
                    method, debugFrameCounter, value);
        }
    }

    // ==================== 反射初始化 ====================

    private boolean resolveMethods() {
        if (methodsResolved) return getContainerMethod != null;

        try {
            // SubLevelContainer
            Class<?> subContainerClass = Class.forName(SUBCONTAINER_CLASS);
            getContainerMethod = subContainerClass.getMethod("getContainer", ClientLevel.class);
            getSubLevelMethod = subContainerClass.getMethod("getSubLevel", UUID.class);
            getAllSubLevelsMethod = subContainerClass.getMethod("getAllSubLevels");
            getPlotMethod = subContainerClass.getMethod("getPlot", net.minecraft.world.level.ChunkPos.class);

            // LevelPlot.getSubLevel() → SubLevel
            Class<?> levelPlotClass = Class.forName(PLOT_CLASS);
            plotGetSubLevelMethod = levelPlotClass.getMethod("getSubLevel");

            // SubLevel
            Class<?> subLevelClass = Class.forName(SUBLEVEL_CLASS);
            isRemovedMethod = subLevelClass.getMethod("isRemoved");
            logicalPoseMethod = subLevelClass.getMethod("logicalPose");
            subLevelGetLevelMethod = subLevelClass.getMethod("getLevel");
            subLevelGetUniqueIdMethod = subLevelClass.getMethod("getUniqueId");

            // ClientSubLevel.renderPose(float) → Pose3dc
            try {
                Class<?> clientSubLevelClass = Class.forName(SUBCLIENT_CLASS);
                renderPoseMethod = clientSubLevelClass.getMethod("renderPose", float.class);
            } catch (ClassNotFoundException e) {
                // 服务端环境，无 ClientSubLevel
                renderPoseMethod = null;
            }

            // Pose3dc
            Class<?> pose3dcClass = Class.forName(POSE3DC_CLASS);
            transformPosMethod = pose3dcClass.getMethod("transformPosition", Vec3.class);
            transformNormalMethod = pose3dcClass.getMethod("transformNormal", Vec3.class);
            posePositionMethod = pose3dcClass.getMethod("position");

            // org.joml.Vector3dc
            Class<?> vec3dcClass = Class.forName(VECTOR3DC_CLASS);
            vec3dcXMethod = vec3dcClass.getMethod("x");
            vec3dcYMethod = vec3dcClass.getMethod("y");
            vec3dcZMethod = vec3dcClass.getMethod("z");

            methodsResolved = true;
            BollyhoMod.LOGGER.info("Sable: 反射方法解析成功");
            return true;
        } catch (Exception e) {
            BollyhoMod.LOGGER.warn("Sable: 反射解析失败，Sable/航空学可能未安装", e);
            methodsResolved = true;
            return false;
        }
    }

    // ==================== 运动学轴承跟踪（主世界模式） ====================

    /**
     * 检查是否正在进行运动学轴承跟踪
     *
     * <p>运动学模式：轴承未装配（ASSEMBLED=false），方块留在主世界，
     * 但轴承的 targetAngleDegrees 随旋转更新。本方法返回 true 表示已找到
     * 附近的 SwivelBearing 并开始跟踪其角度。</p>
     *
     * @return true 如果正在跟踪运动学轴承
     */
    public boolean isKinematicTracking() {
        return kinematicBearingPos != null;
    }

    /**
     * 初始化运动学轴承跟踪
     *
     * <p>在 scope 主世界位置附近搜索 SwivelBearingBlockEntity，
     * 缓存其位置并读取初始角度。与 SubLevel 跟踪不同，
     * 运动学跟踪不需要方块被移入 SubLevel。</p>
     *
     * @param level    当前世界
     * @param scopePos scope 方块在主世界的位置
     * @return true 如果成功找到并初始化
     */
    public boolean tryTrackKinematicBearing(Level level, BlockPos scopePos) {
        clearKinematicTracking();

        if (level == null) return false;

        try {
            Class<?> swivelBeClass = Class.forName(SWIVEL_BE_CLASS);
            Class<?> swivelPlateBeClass = Class.forName(SWIVEL_PLATE_BE_CLASS);
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            int radius = 8;
            int beCount = 0;
            String foundTypes = "";
            BlockPos platePos = null;
            BlockPos bearingPos = null;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        mutable.set(scopePos.getX() + dx, scopePos.getY() + dy, scopePos.getZ() + dz);
                        BlockEntity be = level.getBlockEntity(mutable);
                        if (be != null) {
                            beCount++;
                            // 直接找到轴承主体
                            if (swivelBeClass.isInstance(be)) {
                                bearingPos = mutable.immutable();
                            }
                            // 找到连接板（scope 在连接板上）
                            if (swivelPlateBeClass.isInstance(be)) {
                                platePos = mutable.immutable();
                            }
                            // 记录前5个 BlockEntity 类型用于诊断
                            if (foundTypes.isEmpty()) {
                                foundTypes = be.getClass().getSimpleName();
                            } else if (foundTypes.split(",").length < 5) {
                                foundTypes += ", " + be.getClass().getSimpleName();
                            }
                        }
                    }
                }
            }

            // 优先使用直接找到的轴承主体
            if (bearingPos != null) {
                BlockEntity be = level.getBlockEntity(bearingPos);
                Method getAngleMethod = swivelBeClass.getMethod("getTargetAngleDegrees");
                this.kinematicBearingPos = bearingPos;
                this.kinematicBearingAngle = ((Number) getAngleMethod.invoke(be)).floatValue();
                BollyhoMod.LOGGER.info(
                        "[DEBUG] Sable: 已初始化运动学轴承跟踪(直接): pos={}, initialAngle={}",
                        this.kinematicBearingPos, this.kinematicBearingAngle);
                return true;
            }

            // 通过连接板找到父轴承
            if (platePos != null) {
                BlockEntity plateBe = level.getBlockEntity(platePos);
                // 反射读取 plate.parent (BlockPos 字段)
                java.lang.reflect.Field parentField = swivelPlateBeClass.getDeclaredField("parent");
                parentField.setAccessible(true);
                BlockPos parentBearingPos = (BlockPos) parentField.get(plateBe);
                if (parentBearingPos != null) {
                    BlockEntity bearingBe = level.getBlockEntity(parentBearingPos);
                    if (bearingBe != null && swivelBeClass.isInstance(bearingBe)) {
                        Method getAngleMethod = swivelBeClass.getMethod("getTargetAngleDegrees");
                        this.kinematicBearingPos = parentBearingPos;
                        this.kinematicBearingAngle = ((Number) getAngleMethod.invoke(bearingBe)).floatValue();
                        BollyhoMod.LOGGER.info(
                                "[DEBUG] Sable: 已初始化运动学轴承跟踪(通过板): platePos={}, bearingPos={}, initialAngle={}",
                                platePos, this.kinematicBearingPos, this.kinematicBearingAngle);
                        return true;
                    }
                }
                BollyhoMod.LOGGER.info(
                        "[DEBUG] Sable: 找到连接板 platePos={} 但无法解析父轴承 parentField={}",
                        platePos, parentBearingPos);
            }

            // 搜索完成但未找到
            BollyhoMod.LOGGER.info(
                    "[DEBUG] Sable: 附近未找到运动学 SwivelBearing (scope={}, radius={}, 共{}个BE, 类型={})",
                    scopePos, radius, beCount, foundTypes.isEmpty() ? "无" : foundTypes);
        } catch (Exception e) {
            BollyhoMod.LOGGER.warn("[DEBUG] Sable.tryTrackKinematicBearing: 搜索异常", e);
        }

        return false;
    }

    /**
     * 每帧从缓存的轴承 BlockEntity 读取最新的目标角度
     *
     * <p>应在每帧 onClientTick 中调用以保持角度同步。
     * 如果缓存的轴承方块已消失（被破坏/卸载），自动清除跟踪。</p>
     *
     * @param level 当前世界
     */
    public void readBearingAngle(Level level) {
        if (kinematicBearingPos == null || level == null) return;

        BlockEntity be = level.getBlockEntity(kinematicBearingPos);
        if (be == null) {
            BollyhoMod.LOGGER.info("[DEBUG] Sable: 运动学轴承方块消失，清除跟踪 (pos={})", kinematicBearingPos);
            clearKinematicTracking();
            return;
        }

        try {
            Class<?> swivelBeClass = Class.forName(SWIVEL_BE_CLASS);
            if (swivelBeClass.isInstance(be)) {
                Method getAngleMethod = swivelBeClass.getMethod("getTargetAngleDegrees");
                float newAngle = ((Number) getAngleMethod.invoke(be)).floatValue();
                if (Math.abs(this.kinematicBearingAngle - newAngle) > 0.01f && debugFrameCounter % 60 == 0) {
                    BollyhoMod.LOGGER.info("[DEBUG] Sable.readBearingAngle: 角度变化 {} → {}",
                            String.format("%.1f", this.kinematicBearingAngle),
                            String.format("%.1f", newAngle));
                }
                this.kinematicBearingAngle = newAngle;
            }
        } catch (Exception e) {
            BollyhoMod.LOGGER.error("[DEBUG] Sable.readBearingAngle: 读取失败", e);
            clearKinematicTracking();
        }
    }

    /**
     * 获取运动学轴承的当前旋转角度
     *
     * @return 旋转角度（度）
     */
    public float getKinematicBearingAngle() {
        return kinematicBearingAngle;
    }

    /**
     * 清除运动学轴承跟踪状态
     */
    public void clearKinematicTracking() {
        if (kinematicBearingPos != null) {
            BollyhoMod.LOGGER.info("[DEBUG] Sable: 清除运动学轴承跟踪 (pos={})", kinematicBearingPos);
        }
        kinematicBearingPos = null;
        kinematicBearingAngle = 0;
    }

    // ==================== 私有辅助 ====================

    /**
     * 获取 SubLevel 的渲染姿态
     *
     * <p>ClientSubLevel.renderPose(partialTick) 通过快照插值器
     * 在两帧之间平滑插值，保证渲染流畅。</p>
     */
    private Object getRenderPoseObj(Object subLevel, float partialTick) throws Exception {
        if (renderPoseMethod != null) {
            try {
                return renderPoseMethod.invoke(subLevel, partialTick);
            } catch (Exception e) {
                renderPoseFallbackCount++;
                if (renderPoseFallbackCount <= 3) {
                    BollyhoMod.LOGGER.warn("[DEBUG] Sable.getRenderPoseObj: renderPose(float) 失败 (第{}次), "
                            + "回退到 logicalPose(). 异常: {}", renderPoseFallbackCount, e.toString());
                } else if (renderPoseFallbackCount == 4) {
                    BollyhoMod.LOGGER.warn("[DEBUG] Sable.getRenderPoseObj: 之后不再报告此回退日志");
                }
            }
        } else {
            if (renderPoseFallbackCount == 0) {
                BollyhoMod.LOGGER.warn("[DEBUG] Sable.getRenderPoseObj: renderPoseMethod=null, 始终使用 logicalPose()");
                renderPoseFallbackCount = -1; // 只报一次
            }
        }
        return logicalPoseMethod.invoke(subLevel);
    }
}
