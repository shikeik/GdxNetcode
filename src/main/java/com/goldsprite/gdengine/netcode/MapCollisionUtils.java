package com.goldsprite.gdengine.netcode;

import java.util.Random;

/**
 * 地图碰撞工具类 — 提供纯静态方法供 Server/Client 共用。
 * <p>
 * 包含:
 * <ul>
 *   <li>AABB 矩形重叠检测</li>
 *   <li>坐标边界限制 (Clamp)</li>
 *   <li>确定性墙体生成（相同种子 = 相同布局）</li>
 * </ul>
 */
public class MapCollisionUtils {

    /**
     * AABB 矩形重叠检测（严格重叠，边缘接触不算）。
     * @param ax, ay, aw, ah 矩形 A (左下角 + 尺寸)
     * @param bx, by, bw, bh 矩形 B (左下角 + 尺寸)
     * @return true 如果两个矩形有严格重叠
     */
    public static boolean rectOverlap(float ax, float ay, float aw, float ah,
                                       float bx, float by, float bw, float bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    /**
     * 将坐标限制在地图边界内（Clamp），考虑实体半径。
     * @param x, y       当前坐标
     * @param halfSize   实体半径（如坦克 15px）
     * @param mapWidth   地图宽度
     * @param mapHeight  地图高度
     * @return [clampedX, clampedY]
     */
    public static float[] clampToBoundary(float x, float y, float halfSize, float mapWidth, float mapHeight) {
        float cx = Math.max(halfSize, Math.min(mapWidth - halfSize, x));
        float cy = Math.max(halfSize, Math.min(mapHeight - halfSize, y));
        return new float[]{cx, cy};
    }

    /**
     * 根据种子确定性生成随机墙体布局。
     * 相同种子 + 相同地图尺寸 = 完全相同的墙体布局。
     * @param seed      随机种子
     * @param mapWidth  地图宽度
     * @param mapHeight 地图高度
     * @return 墙体数组，每个元素 = {x, y, width, height}
     */
    public static float[][] generateWalls(long seed, float mapWidth, float mapHeight) {
        Random rng = new Random(seed);
        float margin = 100f;

        int wallCount = 10 + rng.nextInt(11); // 5~10 个墙体
        float[][] walls = new float[wallCount][4];

        for (int i = 0; i < wallCount; i++) {
            float w = 60 + rng.nextFloat() * 140; // 宽 60~200
            float h = 20 + rng.nextFloat() * 80;  // 高 20~100
            float x = margin + rng.nextFloat() * (mapWidth - 2 * margin - w);
            float y = margin + rng.nextFloat() * (mapHeight - 2 * margin - h);
            walls[i] = new float[]{x, y, w, h};
        }

        return walls;
    }
}
