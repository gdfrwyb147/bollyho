package com.aero.bollyho.mixin;

import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Camera 内部字段访问器
 *
 * <p>用于绕过 Minecraft yaw/pitch 系统的限制，直接操作
 * Camera 内部的四元数旋转和基向量。这是实现任意 3D 旋转
 * （如轴承倾斜时的炮镜视角）的必要组件。</p>
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    /** Camera 内部的旋转四元数 */
    @Accessor("rotation")
    Quaternionf bollho$getRotation();

    /** Camera 的前向基向量（归一化后指向视线方向） */
    @Accessor("forwards")
    Vector3f bollho$getForwards();

    /** Camera 的上方基向量（归一化后指向屏幕上方） */
    @Accessor("up")
    Vector3f bollho$getUp();

    /** Camera 的左方基向量（归一化后指向屏幕左侧） */
    @Accessor("left")
    Vector3f bollho$getLeft();
}
