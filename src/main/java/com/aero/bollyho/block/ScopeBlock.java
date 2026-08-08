package com.aero.bollyho.block;

import com.aero.bollyho.client.ScopeViewHandler;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * 炮镜方块
 *
 * <p>一个可放置的瞄准镜方块，使用 face + 水平 facing 属性控制放置面与朝向。
 * blockstates 格式与原版砂轮（Grindstone）/ 钟（Bell）一致。</p>
 *
 * <h3>方块属性</h3>
 * <ul>
 *   <li>{@link #FACE} — 附着面（floor / wall / ceiling）</li>
 *   <li>{@link #FACING} — 水平朝向（北/南/西/东）</li>
 * </ul>
 *
 * @see net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
 */
public class ScopeBlock extends Block {

    /** 附着面：地板、墙面、天花板 */
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;

    /** 水平朝向（4方向：北/南/西/东） */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /**
     * 基准碰撞箱：face=FLOOR, facing=NORTH
     * <p>模型平放在地上、目镜朝北（-Z）的包围盒。
     * 坐标 (minX, minY, minZ, maxX, maxY, maxZ)，单位 1/16 方块。</p>
     */
    private static final VoxelShape BASE_SHAPE = Block.box(
            1.5, 0, 3,     // minX, minY, minZ
            14.5, 5.8, 10  // maxX, maxY, maxZ
    );

    /** 所有 face × facing 组合的碰撞箱缓存 */
    private static final Map<AttachFace, Map<Direction, VoxelShape>> SHAPE_CACHE =
            new EnumMap<>(AttachFace.class);

    static {
        for (AttachFace face : AttachFace.values()) {
            Map<Direction, VoxelShape> dirMap = new EnumMap<>(Direction.class);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                dirMap.put(dir, computeShape(face, dir));
            }
            SHAPE_CACHE.put(face, dirMap);
        }
    }

    public ScopeBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(
                this.stateDefinition.any()
                        .setValue(FACE, AttachFace.FLOOR)
                        .setValue(FACING, Direction.NORTH)
        );
    }

    // ==================== Codec ====================

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(ScopeBlock::new);
    }

    // ==================== 方块状态定义 ====================

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING);
    }

    // ==================== 放置逻辑 ====================

    /**
     * 根据玩家点击的面决定 face 和水平 facing。
     *
     * <ul>
     *   <li>点击顶面 → face=floor，facing=玩家面向的反方向（目镜对着玩家看的方向）</li>
     *   <li>点击侧面 → face=wall，facing=点击面（目镜朝外，面对玩家）</li>
     *   <li>点击底面 → face=ceiling，facing=玩家面向的反方向</li>
     * </ul>
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        AttachFace face;
        Direction facing;

        switch (clickedFace) {
            case DOWN -> {
                face = AttachFace.CEILING;
                facing = context.getHorizontalDirection().getOpposite();
            }
            case UP -> {
                face = AttachFace.FLOOR;
                facing = context.getHorizontalDirection().getOpposite();
            }
            default -> {
                face = AttachFace.WALL;
                facing = clickedFace;
            }
        }

        return this.defaultBlockState()
                .setValue(FACE, face)
                .setValue(FACING, facing);
    }

    // ==================== 碰撞箱 ====================

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE_CACHE
                .getOrDefault(state.getValue(FACE), Map.of())
                .getOrDefault(state.getValue(FACING), BASE_SHAPE);
    }

    // ==================== 右键交互 ====================

    /**
     * 右键炮镜方块进入炮镜视角。
     * 将 face + 水平 facing 转换为实际的视线方向传给 ScopeViewHandler。
     */
    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hitResult
    ) {
        if (level.isClientSide()) {
            Direction viewDirection = getViewDirection(state);
            ScopeViewHandler.getInstance().enterScopeView(pos, viewDirection);
        }

        return InteractionResult.SUCCESS;
    }

    // ==================== 旋转 / 镜像 ====================

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 face + 水平 facing 推算出实际视线方向。
     *
     * <ul>
     *   <li>floor → 视线朝上（UP）</li>
     *   <li>wall  → 视线 = 水平 facing</li>
     *   <li>ceiling → 视线朝下（DOWN）</li>
     * </ul>
     */
    public static Direction getViewDirection(BlockState state) {
        AttachFace face = state.getValue(FACE);
        Direction facing = state.getValue(FACING);
        facing = facing.getOpposite();

        return switch (face) {
            case FLOOR -> Direction.DOWN;
            case WALL -> facing;
            case CEILING -> Direction.UP;
        };
    }

    // ==================== 碰撞箱旋转计算 ====================

    /**
     * 计算指定 face + facing 的旋转碰撞箱。
     *
     * <p>旋转顺序与 blockstates JSON 一致：先绕 X 轴，再绕 Y 轴。</p>
     *
     * @param face   附着面
     * @param facing 水平朝向
     * @return 旋转后的 VoxelShape
     */
    private static VoxelShape computeShape(AttachFace face, Direction facing) {
        // 确定 Y 旋转角度（floor/wall 基准）
        int floorY = switch (facing) {
            case NORTH -> 0;
            case SOUTH -> 180;
            case WEST -> 270;
            case EAST -> 90;
            default -> 0;
        };

        // X 旋转角度
        int xRot = switch (face) {
            case FLOOR -> 0;
            case WALL -> 90;
            case CEILING -> 180;
        };

        // ceiling 的 Y 旋转 = (floorY + 180) % 360（x=180 反转后方向相反）
        int yRot = (face == AttachFace.CEILING) ? (floorY + 180) % 360 : floorY;

        double mnX = 1.5, mnY = 0, mnZ = 3;
        double mxX = 14.5, mxY = 5.8, mxZ = 10;

        // Step 1: 绕 X 轴旋转（与 blockstates "x" 字段一致）
        switch (xRot) {
            case 90 -> {
                // x=90: y' = z,   z' = 16 - y
                double ny1 = mnZ, ny2 = mxZ;
                double nz1 = 16 - mxY, nz2 = 16 - mnY;
                mnY = ny1; mxY = ny2;
                mnZ = nz1; mxZ = nz2;
            }
            case 180 -> {
                // x=180: y' = 16 - y,  z' = 16 - z
                double ny1 = 16 - mxY, ny2 = 16 - mnY;
                double nz1 = 16 - mxZ, nz2 = 16 - mnZ;
                mnY = ny1; mxY = ny2;
                mnZ = nz1; mxZ = nz2;
            }
            // 0: 不变
        }

        // Step 2: 绕 Y 轴旋转（与 blockstates "y" 字段一致）
        switch (yRot) {
            case 90 -> {
                // y=90 CW: 北→东,  x' = 16 - z, z' = x
                double nx1 = 16 - mxZ, nx2 = 16 - mnZ;
                double nz1 = mnX, nz2 = mxX;
                mnX = nx1; mxX = nx2;
                mnZ = nz1; mxZ = nz2;
            }
            case 180 -> {
                // y=180: x' = 16 - x, z' = 16 - z
                double nx1 = 16 - mxX, nx2 = 16 - mnX;
                double nz1 = 16 - mxZ, nz2 = 16 - mnZ;
                mnX = nx1; mxX = nx2;
                mnZ = nz1; mxZ = nz2;
            }
            case 270 -> {
                // y=270 CCW: 北→西, x' = z, z' = 16 - x
                double nx1 = mnZ, nx2 = mxZ;
                double nz1 = 16 - mxX, nz2 = 16 - mnX;
                mnX = nx1; mxX = nx2;
                mnZ = nz1; mxZ = nz2;
            }
            // 0: 不变
        }

        return Block.box(
                Math.min(mnX, mxX), Math.min(mnY, mxY), Math.min(mnZ, mxZ),
                Math.max(mnX, mxX), Math.max(mnY, mxY), Math.max(mnZ, mxZ)
        );
    }
}
