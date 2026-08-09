package com.aero.bollyho.client;

import com.aero.bollyho.BollyhoMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * CBC（Create Big Cannons）集成工具类
 *
 * <p>负责检测并读取 CBC 火炮底座的俯仰角和偏航角，用纯反射实现，不硬依赖 CBC。
 * 如果 CBC 或相关子模组未安装，本类的所有方法都不会抛出异常，只是返回 null。</p>
 *
 * <h3>火炮类型</h3>
 * <p>区分两种火炮底座，行为不同：</p>
 * <ul>
 *   <li><b>{@link CannonType#ORIGINAL_CBC ORIGINAL_CBC}</b>（原版 CBC 火炮底座）
 *       — 可水平旋转，旋转轴在炮镜相机所在方块中心。
 *       读取偏航角（yaw）和俯仰角（pitch），相机放在火炮底座中心。</li>
 *   <li><b>{@link CannonType#COMPACT_MOUNT COMPACT_MOUNT}</b>（紧凑式火炮底座）
 *       — 不可水平旋转，取消偏航角跟随，只跟随俯仰角。</li>
 * </ul>
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>检查 CBC 及子模组是否已加载</li>
 *   <li>在炮镜方块周围 7×7×7（±3 格）区域扫描所有 CBC 相关方块</li>
 *   <li>对所有候选火炮按距离排序，距离相同时优先选左侧</li>
 *   <li>识别火炮类型（原版 CBC vs 紧凑炮座）</li>
 *   <li>优先通过公开方法读取偏航/俯仰角，回退到反射读取字段</li>
 * </ol>
 *
 * <h3>缓存策略</h3>
 * <p>火炮位置和类型在进入视角时扫描一次并缓存，之后每帧只读缓存的 BlockEntity。
 * 如果缓存的火炮方块被破坏，自动重新扫描。</p>
 */
public final class CbcIntegration {

    /**
     * 火炮底座类型
     *
     * <p>决定相机旋转行为：</p>
     * <ul>
     *   <li>{@link #ORIGINAL_CBC} — 可水平旋转，相机放在火炮底座中心，
     *       偏航角和俯仰角都从火炮读取</li>
     *   <li>{@link #COMPACT_MOUNT} — 不可水平旋转，相机放在目标方块中心，
     *       只有俯仰角从火炮读取</li>
     *   <li>{@link #NONE} — 未找到火炮，使用默认行为</li>
     * </ul>
     */
    public enum CannonType {
        /** 未找到任何火炮底座 */
        NONE,
        /** 原版 CBC 火炮底座（createbigcannons 命名空间） */
        ORIGINAL_CBC,
        /** 紧凑式火炮底座（cbc_compact_mount 命名空间） */
        COMPACT_MOUNT
    }

    /**
     * CBC 及其子模组的 modId 集合
     * <p>扫描时会匹配这些命名空间下的所有方块。</p>
     */
    private static final Set<String> CBC_MOD_IDS = Set.of(
            "createbigcannons",        // 机械动力：火炮（本体）
            "cbc_compact_mount",       // CBC 紧凑炮座
            "cbc_firepower_components", // CBC 火力组件
            "cbcmodernwarfare",        // CBC 现代战争
            "cbcmoreshells",           // CBC 军事补充
            "cbc_enhanced_shells"      // CBC 更多炮弹
    );

    /**
     * 紧凑炮座的 modId（用于区分类型）
     */
    private static final String COMPACT_MOUNT_MOD_ID = "cbc_compact_mount";

    /**
     * 尝试读取俯仰值的字段名列表
     * <p>按顺序尝试，找到第一个可读的 float/double 字段就返回。
     * 覆盖了 CBC 不同版本可能使用的字段名。</p>
     */
    private static final String[] PITCH_FIELD_NAMES = {
            "pitch",          // 最常见：直接叫 pitch
            "barrelPitch",    // 显式命名：炮管俯仰
            "elevation",      // 军事术语：仰角
            "cannonPitch",    // 带前缀
            "currentPitch",   // 和 targetPitch 配对
            "targetPitch",    // 目标俯仰角
            "elevationAngle"  // 完整描述
    };

    /**
     * 尝试读取偏航值的字段名列表
     * <p>按顺序尝试，找到第一个可读的 float/double 字段就返回。</p>
     */
    private static final String[] YAW_FIELD_NAMES = {
            "yaw",            // 最常见
            "barrelYaw",      // 炮管偏航
            "baseYaw",        // 底座偏航
            "cannonYaw",      // 带前缀
            "currentYaw",     // 和 targetYaw 配对
            "targetYaw",      // 目标偏航角
            "rotationYaw"     // 完整描述
    };

    /** 缓存的火炮位置（进入视角时扫描确定） */
    private BlockPos cachedCannonPos;

    /** 缓存的火炮类型 */
    private CannonType cachedCannonType = CannonType.NONE;

    /**
     * 缓存的俯仰读取方式
     * <p>优先使用方法（更可靠），为 null 时回退到字段。</p>
     */
    private Method cachedPitchMethod;
    private Field cachedPitchField;

    /**
     * 缓存的偏航读取方式
     * <p>优先使用方法（更可靠），为 null 时回退到字段。</p>
     */
    private Method cachedYawMethod;
    private Field cachedYawField;

    /**
     * 获取单例实例（线程安全）
     */
    private static final CbcIntegration INSTANCE = new CbcIntegration();

    public static CbcIntegration getInstance() {
        return INSTANCE;
    }

    private CbcIntegration() {}

    // ==================== 公开 API ====================

    /**
     * 检查是否有任何 CBC 相关模组已加载
     *
     * @return true 如果至少一个 CBC 模组已安装
     */
    public boolean isCbcLoaded() {
        return CBC_MOD_IDS.stream().anyMatch(id -> ModList.get().isLoaded(id));
    }

    /**
     * 扫描炮镜周围，寻找最近的 CBC 火炮底座并缓存
     *
     * <p>在进入炮镜视角时调用（只调用一次，不是每帧）。
     * 同时识别火炮类型（原版 CBC vs 紧凑炮座）。</p>
     *
     * @param level     当前世界
     * @param scopePos  炮镜方块位置
     * @param facing    炮镜朝向（用于"左侧优先"平局判定）
     */
    public void scanForCannon(Level level, BlockPos scopePos, Direction facing) {
        cachedCannonPos = null;
        cachedCannonType = CannonType.NONE;
        cachedPitchMethod = null;
        cachedPitchField = null;
        cachedYawMethod = null;
        cachedYawField = null;

        if (!isCbcLoaded() || level == null) {
            return;
        }

        BlockPos bestPos = null;
        CannonType bestType = CannonType.NONE;
        double bestDistSq = Double.MAX_VALUE;
        double bestLeftness = Double.NEGATIVE_INFINITY;
        Method bestPitchMethod = null;
        Field bestPitchField = null;
        Method bestYawMethod = null;
        Field bestYawField = null;

        // "左侧"方向 = 面向方向的逆时针方向
        Direction leftDir = facing.getCounterClockWise();

        // 扫描 7×7×7 范围（各方向 ±3 格，覆盖 6×6×6 区域）
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    mutable.set(
                            scopePos.getX() + dx,
                            scopePos.getY() + dy,
                            scopePos.getZ() + dz
                    );

                    // 获取该方块注册名，检查是否属于 CBC 模组
                    ResourceLocation blockId = BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(mutable).getBlock());

                    // 检查是否属于 CBC 及相关子模组
                    String namespace = blockId.getNamespace();
                    if (!CBC_MOD_IDS.contains(namespace)) {
                        continue;
                    }

                    // 检查 BlockEntity 中是否可读取俯仰值
                    BlockEntity be = level.getBlockEntity(mutable);
                    if (be == null) continue;

                    // 优先找公开方法 getPitchOffset(float)，回退到字段
                    Method pitchMethod = findPitchMethod(be);
                    Field pitchField = pitchMethod != null ? null : findPitchField(be);
                    if (pitchMethod == null && pitchField == null) continue;

                    // 识别火炮类型
                    CannonType type = namespace.equals(COMPACT_MOUNT_MOD_ID)
                            ? CannonType.COMPACT_MOUNT
                            : CannonType.ORIGINAL_CBC;

                    // 尝试读取偏航（仅对原版 CBC 有意义）
                    Method yawMethod = null;
                    Field yawField = null;
                    if (type == CannonType.ORIGINAL_CBC) {
                        yawMethod = findYawMethod(be);
                        yawField = yawMethod != null ? null : findYawField(be);
                    }

                    // ---- 该方块是有效的 CBC 火炮底座 ----
                    // 计算距离平方（用于最近优先排序）
                    double dx2 = dx * dx;
                    double dy2 = dy * dy;
                    double dz2 = dz * dz;
                    double distSq = dx2 + dy2 + dz2;

                    // 计算"左侧度"：沿 leftDir 方向的投影（越小/越负 = 越靠左）
                    double leftness = dx * leftDir.getStepX()
                            + dy * leftDir.getStepY()
                            + dz * leftDir.getStepZ();

                    // 比较逻辑：距离近的优先，距离相同时左侧优先
                    boolean isBetter = distSq < bestDistSq
                            || (distSq == bestDistSq && leftness < bestLeftness);

                    if (isBetter) {
                        bestDistSq = distSq;
                        bestLeftness = leftness;
                        bestPos = mutable.immutable();
                        bestType = type;
                        bestPitchMethod = pitchMethod;
                        bestPitchField = pitchField;
                        bestYawMethod = yawMethod;
                        bestYawField = yawField;
                    }
                }
            }
        }

        cachedCannonPos = bestPos;
        cachedCannonType = bestType;
        cachedPitchMethod = bestPitchMethod;
        cachedPitchField = bestPitchField;
        cachedYawMethod = bestYawMethod;
        cachedYawField = bestYawField;

        if (cachedCannonPos != null) {
            BollyhoMod.LOGGER.info("CBC 火炮底座已找到: pos={}, type={}, distSq={}",
                    cachedCannonPos, cachedCannonType, bestDistSq);
        } else {
            BollyhoMod.LOGGER.debug("未在 ±3 格范围内找到 CBC 火炮底座");
        }
    }

    /**
     * 读取缓存火炮的当前俯仰角
     *
     * <p>如果缓存失效（火炮方块被破坏），返回 null 并清除缓存。</p>
     *
     * @param level  当前世界
     * @return 俯仰角（度），0=水平，正=仰角；如果无火炮则返回 null
     */
    public Float readCannonPitch(Level level) {
        // 无缓存
        if (cachedCannonPos == null || (cachedPitchMethod == null && cachedPitchField == null)) {
            return null;
        }

        // 检查缓存是否仍然有效
        BlockEntity be = level.getBlockEntity(cachedCannonPos);
        if (be == null) {
            // 火炮方块消失了
            clearCache();
            return null;
        }

        // 优先使用公开方法 getPitchOffset(1.0f)（兼容紧凑炮座等子模组）
        if (cachedPitchMethod != null) {
            try {
                Object value = cachedPitchMethod.invoke(be, 1.0f);
                if (value instanceof Number num) {
                    return num.floatValue();
                }
            } catch (Exception e) {
                BollyhoMod.LOGGER.error("调用 getPitchOffset 失败", e);
                clearCache();
                return null;
            }
        }

        // 回退：通过缓存的 Field 读取俯仰值
        if (cachedPitchField != null) {
            try {
                Object value = cachedPitchField.get(be);
                if (value instanceof Number num) {
                    return num.floatValue();
                }
            } catch (IllegalAccessException e) {
                BollyhoMod.LOGGER.error("读取 CBC 俯仰字段失败", e);
                clearCache();
            }
        }

        return null;
    }

    /**
     * 读取缓存火炮的当前偏航角
     *
     * <p>仅对 {@link CannonType#ORIGINAL_CBC} 类型有效。
     * 紧凑炮座不支持偏航角，始终返回 null。</p>
     *
     * @param level  当前世界
     * @return 偏航角（度），0=南，90=西，180=北，-90=东；如果无火炮或不可用则返回 null
     */
    public Float readCannonYaw(Level level) {
        // 仅原版 CBC 支持偏航
        if (cachedCannonType != CannonType.ORIGINAL_CBC) {
            return null;
        }

        // 无缓存
        if (cachedCannonPos == null || (cachedYawMethod == null && cachedYawField == null)) {
            return null;
        }

        // 检查缓存是否仍然有效
        BlockEntity be = level.getBlockEntity(cachedCannonPos);
        if (be == null) {
            clearCache();
            return null;
        }

        // 优先使用公开方法 getYawOffset(1.0f)
        if (cachedYawMethod != null) {
            try {
                Object value = cachedYawMethod.invoke(be, 1.0f);
                if (value instanceof Number num) {
                    return num.floatValue();
                }
            } catch (Exception e) {
                BollyhoMod.LOGGER.error("调用 getYawOffset 失败", e);
                clearCache();
                return null;
            }
        }

        // 回退：通过缓存的 Field 读取偏航值
        if (cachedYawField != null) {
            try {
                Object value = cachedYawField.get(be);
                if (value instanceof Number num) {
                    return num.floatValue();
                }
            } catch (IllegalAccessException e) {
                BollyhoMod.LOGGER.error("读取 CBC 偏航字段失败", e);
                clearCache();
            }
        }

        return null;
    }

    /**
     * 获取缓存的火炮底座类型
     *
     * @return 火炮类型（{@link CannonType#NONE} 表示未找到）
     */
    public CannonType getCannonType() {
        return cachedCannonType;
    }

    /**
     * 获取缓存的火炮底座位置
     *
     * @return 火炮底座坐标（可能为 null）
     */
    public BlockPos getCachedCannonPos() {
        return cachedCannonPos;
    }

    /**
     * 清除缓存（退出视角或火炮失效时调用）
     */
    public void clearCache() {
        cachedCannonPos = null;
        cachedCannonType = CannonType.NONE;
        cachedPitchMethod = null;
        cachedPitchField = null;
        cachedYawMethod = null;
        cachedYawField = null;
    }

    // ==================== 私有辅助 ====================

    /**
     * 在 BlockEntity 中寻找 {@code getPitchOffset(float)} 方法
     *
     * <p>优先于字段反射，因为：</p>
     * <ul>
     *   <li>公开 API，兼容不同 CBC 子模组（紧凑炮座、现代战争等）</li>
     *   <li>自动处理部分 tick 插值和朝向乘数</li>
     *   <li>不受字段名变更或访问修饰符影响</li>
     * </ul>
     *
     * @param be BlockEntity 实例
     * @return 找到的 Method，或 null
     */
    private static Method findPitchMethod(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("getPitchOffset", float.class);
            if (m.getReturnType() == float.class) {
                return m;
            }
        } catch (NoSuchMethodException ignored) {
            // 该方法不存在，回退到字段
        }
        return null;
    }

    /**
     * 在 BlockEntity 中寻找 {@code getYawOffset(float)} 方法
     *
     * <p>与 {@link #findPitchMethod(BlockEntity)} 对称，
     * 用于读取原版 CBC 火炮底座的偏航角。</p>
     *
     * @param be BlockEntity 实例
     * @return 找到的 Method，或 null
     */
    private static Method findYawMethod(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("getYawOffset", float.class);
            if (m.getReturnType() == float.class) {
                return m;
            }
        } catch (NoSuchMethodException ignored) {
            // 该方法不存在，回退到字段
        }
        return null;
    }

    /**
     * 在 BlockEntity 中寻找俯仰字段
     *
     * <p>按 {@link #PITCH_FIELD_NAMES} 顺序尝试，返回第一个
     * 类型为 float 或 double 的字段。</p>
     *
     * @param be BlockEntity 实例
     * @return 找到的 Field（已设为 accessible），或 null
     */
    private Field findPitchField(BlockEntity be) {
        Class<?> clazz = be.getClass();

        for (String fieldName : PITCH_FIELD_NAMES) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                Class<?> type = field.getType();
                // 只接受 float 或 double 类型的字段
                if (type == float.class || type == double.class) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // 该字段不存在，尝试下一个
            }
        }

        return null;
    }

    /**
     * 在 BlockEntity 中寻找偏航字段
     *
     * <p>按 {@link #YAW_FIELD_NAMES} 顺序尝试，返回第一个
     * 类型为 float 或 double 的字段。</p>
     *
     * @param be BlockEntity 实例
     * @return 找到的 Field（已设为 accessible），或 null
     */
    private Field findYawField(BlockEntity be) {
        Class<?> clazz = be.getClass();

        for (String fieldName : YAW_FIELD_NAMES) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                Class<?> type = field.getType();
                // 只接受 float 或 double 类型的字段
                if (type == float.class || type == double.class) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // 该字段不存在，尝试下一个
            }
        }

        return null;
    }
}
