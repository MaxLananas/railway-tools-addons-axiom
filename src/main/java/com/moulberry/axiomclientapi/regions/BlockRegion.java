package com.moulberry.axiomclientapi.regions;

import com.moulberry.axiomclientapi.IAxiomWorldRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public interface BlockRegion {
    void clear();
    void render(IAxiomWorldRenderContext rc, Vec3 translation, float blockOpacity, float outlineOpacity);
    void addBlockIfNotPresent(int x, int y, int z, BlockState block);
    void addBlock(int x, int y, int z, BlockState block);
    BlockPos min();
    BlockPos max();
    boolean isEmpty();
    int count();
}
