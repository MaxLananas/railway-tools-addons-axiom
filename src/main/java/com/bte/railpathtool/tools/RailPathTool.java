package com.bte.railpathtool.tools;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.core_rendering.AxiomRenderPipelines;
import com.moulberry.axiom.core_rendering.AxiomRenderer;
import com.moulberry.axiom.editor.ImGuiHelper;
import com.moulberry.axiom.render.AxiomWorldRenderContext;
import com.moulberry.axiom.render.Shapes;
import com.moulberry.axiom.render.VertexConsumerProvider;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.tools.Tool;
import com.moulberry.axiom.utils.RegionHelper;
import imgui.ImGui;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.enums.WallShape;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class RailPathTool implements Tool {

    private enum RailStyle { CLASSIC, NATURAL }

    private static final String[] STYLE_LABELS = { "Classique", "Naturel" };

    private static BlockState makeWall(Direction... dirs) {
        var s = Blocks.MUD_BRICK_WALL.getDefaultState()
                .with(Properties.UP,               false)
                .with(Properties.WATERLOGGED,      false)
                .with(Properties.NORTH_WALL_SHAPE, WallShape.NONE)
                .with(Properties.SOUTH_WALL_SHAPE, WallShape.NONE)
                .with(Properties.EAST_WALL_SHAPE,  WallShape.NONE)
                .with(Properties.WEST_WALL_SHAPE,  WallShape.NONE);
        for (Direction d : dirs) {
            s = switch (d) {
                case NORTH -> s.with(Properties.NORTH_WALL_SHAPE, WallShape.LOW);
                case SOUTH -> s.with(Properties.SOUTH_WALL_SHAPE, WallShape.LOW);
                case EAST  -> s.with(Properties.EAST_WALL_SHAPE,  WallShape.LOW);
                case WEST  -> s.with(Properties.WEST_WALL_SHAPE,  WallShape.LOW);
                default    -> s;
            };
        }
        return s;
    }

    private static final BlockState WALL_NS = makeWall(Direction.NORTH, Direction.SOUTH);
    private static final BlockState WALL_EW = makeWall(Direction.EAST,  Direction.WEST);
    private static final BlockState WALL_NE = makeWall(Direction.NORTH, Direction.EAST);
    private static final BlockState WALL_NW = makeWall(Direction.NORTH, Direction.WEST);
    private static final BlockState WALL_SE = makeWall(Direction.SOUTH, Direction.EAST);
    private static final BlockState WALL_SW = makeWall(Direction.SOUTH, Direction.WEST);

    private static BlockState makeCoral(Direction facing) {
        return Blocks.DEAD_FIRE_CORAL_WALL_FAN.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, facing)
                .with(Properties.WATERLOGGED, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState makeShelf(Direction facing) {
        var id = Identifier.of("minecraft", "spruce_shelf");
        if (!Registries.BLOCK.containsId(id)) {
            return Blocks.SPRUCE_TRAPDOOR.getDefaultState()
                    .with(Properties.HORIZONTAL_FACING, facing)
                    .with(Properties.OPEN,              true)
                    .with(Properties.WATERLOGGED,       false);
        }
        var block = Registries.BLOCK.get(id);
        var state = block.getDefaultState();
        for (Property<?> prop : state.getProperties()) {
            try {
                switch (prop.getName()) {
                    case "facing" -> {
                        if (prop instanceof EnumProperty ep && ep.getValues().contains(facing))
                            state = state.with(ep, facing);
                    }
                    case "waterlogged", "powered" -> {
                        if (prop instanceof BooleanProperty bp)
                            state = state.with(bp, false);
                    }
                }
            } catch (Exception ignored) {}
        }
        return state;
    }

    private static final BlockState SHELF_N = makeShelf(Direction.NORTH);
    private static final BlockState SHELF_S = makeShelf(Direction.SOUTH);
    private static final BlockState SHELF_E = makeShelf(Direction.EAST);
    private static final BlockState SHELF_W = makeShelf(Direction.WEST);

    private static final int LEAF_STRAIGHT = 2;
    private static final int LEAF_DIAGONAL = 3;

    private final List<BlockPos>     points  = new ArrayList<>();
    private final ChunkedBlockRegion preview = new ChunkedBlockRegion();
    private boolean dirty = true;

    private final int[]     density      = {10};
    private final boolean[] snapToGround = {false};
    private final boolean[] showPreview  = {true};
    private final int[]     styleIndex   = {0};

    @Override public String name()        { return "BTE Rail Path"; }
    @Override public char   iconChar()    { return '\ue912'; }
    @Override public String keybindId()   { return "bte_rail_path"; }
    @Override public int defaultKeybind() { return 0; }

    @Override
    public EnumSet<AxiomPermission> requiredPermissions() {
        return EnumSet.of(AxiomPermission.TOOL, AxiomPermission.BUILD_SECTION);
    }

    @Override public void reset() { points.clear(); preview.clear(); dirty = false; }

    @Override
    public void writeSettings(NbtCompound t) {
        t.putInt("density",  density[0]);
        t.putBoolean("snap", snapToGround[0]);
        t.putBoolean("prev", showPreview[0]);
        t.putInt("style",    styleIndex[0]);
    }

    @Override
    public void loadSettings(NbtCompound t) {
        if (t.contains("density")) density[0]      = t.getInt("density").orElse(density[0]);
        if (t.contains("snap"))    snapToGround[0] = t.getBoolean("snap").orElse(snapToGround[0]);
        if (t.contains("prev"))    showPreview[0]  = t.getBoolean("prev").orElse(showPreview[0]);
        if (t.contains("style"))   styleIndex[0]   = t.getInt("style").orElse(styleIndex[0]);
    }

    private RailStyle currentStyle() {
        return styleIndex[0] == 1 ? RailStyle.NATURAL : RailStyle.CLASSIC;
    }

    @Override
    public void render(AxiomWorldRenderContext ctx) {
        if (dirty) {
            preview.clear();
            if (points.size() >= 2) buildRail(preview);
            dirty = false;
        }

        long time = System.currentTimeMillis();
        float pulse = 0.6f + 0.2f * (float) Math.sin(time / 400_000.0);

        if (showPreview[0] && !preview.isEmpty()) {
            preview.render(ctx, Vec3d.ZERO, null, pulse, 0.25f);
        }

        if (points.size() >= 2) renderControlLines();

        Tool.renderRaycastOverlay(ctx,
                Tool.raycastBlock(false, true, Tool.defaultIncludeFluids()));
    }

    private void renderControlLines() {
        MinecraftClient mc = MinecraftClient.getInstance();
        var camera = mc.gameRenderer.getCamera();
        MatrixStack matrices = new MatrixStack();

        var vcp = VertexConsumerProvider.shared();
        matrices.push();
        matrices.translate(-camera.getPos().x, -camera.getPos().y, -camera.getPos().z);
        var buf  = vcp.begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR_NORMAL);
        var pose = matrices.peek();
        for (int i = 0; i < points.size() - 1; i++)
            Shapes.line(buf, pose,
                    Vec3d.ofCenter(points.get(i)),
                    Vec3d.ofCenter(points.get(i + 1)));
        var mesh = vcp.build();
        AxiomRenderer.setShaderColour(1f, 0.5f, 0.1f, 1f);
        AxiomRenderer.renderPipeline(AxiomRenderPipelines.LINES_WITHOUT_WRITE_DEPTH, null, mesh, false);
        AxiomRenderer.setShaderColour(1f, 0.5f, 0.1f, 0.35f);
        AxiomRenderer.renderPipeline(AxiomRenderPipelines.LINES_IGNORE_DEPTH, null, mesh, true);
        AxiomRenderer.setShaderColour(1f, 1f, 1f, 1f);
        matrices.pop();
    }

    @Override
    public void displayImguiOptions() {
        ImGuiHelper.separatorWithText("BTE Rail Path Tool");

        boolean changed = false;

        ImGui.text("Points : " + points.size());
        ImGui.separator();

        if (ImGui.sliderInt("Densite (pts/bloc)", density, 2, 32)) changed = true;

        if (ImGui.checkbox("Coller au sol", snapToGround[0])) {
            snapToGround[0] = !snapToGround[0];
            changed = true;
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Apercu", showPreview[0])) {
            showPreview[0] = !showPreview[0];
        }

        ImGui.separator();
        ImGuiHelper.separatorWithText("Style");

        for (int i = 0; i < STYLE_LABELS.length; i++) {
            if (i > 0) ImGui.sameLine();
            if (ImGui.radioButton(STYLE_LABELS[i], styleIndex[0] == i)) {
                styleIndex[0] = i;
                changed = true;
            }
        }

        ImGui.textDisabled(switch (currentStyle()) {
            case CLASSIC -> "Corail + murs + etageres";
            case NATURAL -> "Pupitre + gravier + feuilles";
        });

        if (changed) dirty = true;

        ImGui.separator();
        if (points.size() >= 2 && ImGui.button("Valider")) confirm();
        if (!points.isEmpty()) {
            if (ImGui.button("Annuler dernier")) {
                points.remove(points.size() - 1);
                dirty = true;
            }
            ImGui.sameLine();
            if (ImGui.button("Reinitialiser")) reset();
        }
        ImGui.separator();
        ImGui.textDisabled("Clic droit: point | Entree: valider | Suppr: annuler");
    }

    @Override
    public UserAction.ActionResult callAction(UserAction action, Object ctx) {
        return switch (action) {
            case RIGHT_MOUSE -> {
                var hit = Tool.raycastBlock(false, true, Tool.defaultIncludeFluids());
                if (hit == null) yield UserAction.ActionResult.NOT_HANDLED;
                points.add(hit.getBlockPos().up());
                dirty = true;
                yield UserAction.ActionResult.USED_STOP;
            }
            case ENTER -> {
                if (points.size() < 2) yield UserAction.ActionResult.NOT_HANDLED;
                confirm();
                yield UserAction.ActionResult.USED_STOP;
            }
            case DELETE -> {
                if (points.isEmpty()) yield UserAction.ActionResult.NOT_HANDLED;
                points.remove(points.size() - 1);
                dirty = true;
                yield UserAction.ActionResult.USED_STOP;
            }
            case ESCAPE -> {
                if (points.isEmpty()) yield UserAction.ActionResult.NOT_HANDLED;
                reset();
                yield UserAction.ActionResult.USED_STOP;
            }
            default -> UserAction.ActionResult.NOT_HANDLED;
        };
    }

    @Override public String listenForEsc()   { return points.isEmpty()   ? null : "Reinitialiser"; }
    @Override public String listenForEnter() { return points.size() >= 2 ? "Valider" : null; }

    private void confirm() {
        var r = new ChunkedBlockRegion();
        buildRail(r);
        RegionHelper.pushBlockRegionChange(r, "BTE Rail Path");
        reset();
    }

    private record Seg(int cx, int cy, int cz, int dx, int dz) {}
    private record Pt(double x, double y, double z, int bx, int by, int bz) {}

    private void buildRail(ChunkedBlockRegion region) {
        List<Pt> sp = catmullRom(points, density[0]);
        if (sp.size() < 2) return;
        if (snapToGround[0]) sp = snapAllToGround(sp);

        List<Seg> segs = buildSegments(sp);
        if (segs.isEmpty()) return;

        Set<BlockPos> centers = new HashSet<>();
        for (Seg s : segs) centers.add(new BlockPos(s.cx(), s.cy(), s.cz()));

        for (int i = 0; i < segs.size(); i++) {
            Seg cur = segs.get(i);
            Seg prv = i > 0             ? segs.get(i - 1) : null;
            Seg nxt = i < segs.size()-1 ? segs.get(i + 1) : null;
            switch (currentStyle()) {
                case CLASSIC -> placeSegmentClassic(region, cur, prv, nxt, centers);
                case NATURAL -> placeSegmentNatural(region, cur, prv, nxt, segs, i, centers);
            }
        }
    }

    private List<Seg> buildSegments(List<Pt> sp) {
        List<Seg> out  = new ArrayList<>();
        BlockPos  last = null;
        for (Pt p : sp) {
            BlockPos bp = new BlockPos(p.bx(), p.by(), p.bz());
            if (bp.equals(last)) continue;
            int dx = 0, dz = 0;
            if (last != null) {
                dx = bp.getX() - last.getX();
                dz = bp.getZ() - last.getZ();
                if (dx != 0) dx = dx > 0 ? 1 : -1;
                if (dz != 0) dz = dz > 0 ? 1 : -1;
            }
            out.add(new Seg(bp.getX(), bp.getY(), bp.getZ(), dx, dz));
            last = bp;
        }
        return out;
    }

    private void placeSegmentClassic(ChunkedBlockRegion region,
                                      Seg cur, Seg prv, Seg nxt,
                                      Set<BlockPos> centers) {
        int cx = cur.cx(), cy = cur.cy(), cz = cur.cz();

        int tx, tz;
        if (prv != null && nxt != null) { tx = nxt.cx() - prv.cx(); tz = nxt.cz() - prv.cz(); }
        else if (prv != null)           { tx = cx - prv.cx();        tz = cz - prv.cz(); }
        else if (nxt != null)           { tx = nxt.cx() - cx;        tz = nxt.cz() - cz; }
        else                            { tx = 1; tz = 0; }

        Direction tang = cardinalFromVec(tx, tz);
        region.addBlock(cx, cy, cz, makeCoral(tang));

        int lx, lz, rx, rz;
        switch (tang) {
            case NORTH -> { lx = cx-1; lz = cz;   rx = cx+1; rz = cz;   }
            case SOUTH -> { lx = cx+1; lz = cz;   rx = cx-1; rz = cz;   }
            case EAST  -> { lx = cx;   lz = cz-1; rx = cx;   rz = cz+1; }
            case WEST  -> { lx = cx;   lz = cz+1; rx = cx;   rz = cz-1; }
            default    -> { lx = cx+1; lz = cz;   rx = cx-1; rz = cz;   }
        }

        int dx = cur.dx(), dz = cur.dz();
        int ndx = nxt != null ? nxt.dx() : dx;
        int ndz = nxt != null ? nxt.dz() : dz;

        safePlace(region, lx, cy, lz, computeSideBlockClassic(tang, dx, dz, ndx, ndz, true),  centers);
        safePlace(region, rx, cy, rz, computeSideBlockClassic(tang, dx, dz, ndx, ndz, false), centers);
    }

    private BlockState computeSideBlockClassic(Direction tang,
                                                int dx, int dz, int ndx, int ndz,
                                                boolean isLeft) {
        Direction latCur = lateralComponent(dx,  dz,  tang);
        Direction latNxt = lateralComponent(ndx, ndz, tang);

        boolean curDiag = Math.abs(dx)  == 1 && Math.abs(dz)  == 1;
        boolean nxtDiag = Math.abs(ndx) == 1 && Math.abs(ndz) == 1;

        if (curDiag && nxtDiag && latCur == latNxt)
            return diagWall(tang, dx, dz, isLeft);

        Direction lat = latCur != null ? latCur : latNxt;
        if (lat != null) return shelfFromDir(lat);

        return (tang == Direction.NORTH || tang == Direction.SOUTH) ? WALL_NS : WALL_EW;
    }

    private BlockState diagWall(Direction tang, int dx, int dz, boolean isLeft) {
        return switch (tang) {
            case NORTH, SOUTH -> dx > 0
                    ? (isLeft ? WALL_NE : WALL_SW)
                    : (isLeft ? WALL_SE : WALL_NW);
            case EAST, WEST -> dz > 0
                    ? (isLeft ? WALL_NW : WALL_SE)
                    : (isLeft ? WALL_SW : WALL_NE);
            default -> WALL_NS;
        };
    }

    private BlockState shelfFromDir(Direction dir) {
        return switch (dir) {
            case NORTH -> SHELF_N;
            case SOUTH -> SHELF_S;
            case EAST  -> SHELF_E;
            default    -> SHELF_W;
        };
    }

    private Direction lateralComponent(int dx, int dz, Direction tang) {
        boolean ns = tang == Direction.NORTH || tang == Direction.SOUTH;
        if (ns) { return dx > 0 ? Direction.EAST  : dx < 0 ? Direction.WEST  : null; }
        else    { return dz > 0 ? Direction.SOUTH : dz < 0 ? Direction.NORTH : null; }
    }

    private Direction cardinalFromVec(int tx, int tz) {
        if (Math.abs(tx) >= Math.abs(tz)) return tx >= 0 ? Direction.EAST  : Direction.WEST;
        else                              return tz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void placeSegmentNatural(ChunkedBlockRegion region,
                                      Seg cur, Seg prv, Seg nxt,
                                      List<Seg> all, int idx,
                                      Set<BlockPos> centers) {
        int cx = cur.cx(), cy = cur.cy(), cz = cur.cz();

        int tx = 0, tz = 0;
        if (prv != null) { tx += cx - prv.cx(); tz += cz - prv.cz(); }
        if (nxt != null) { tx += nxt.cx() - cx; tz += nxt.cz() - cz; }
        if (tx == 0 && tz == 0) { tx = cur.dx(); tz = cur.dz(); }

        boolean axisNS = Math.abs(tz) >= Math.abs(tx);
        boolean rising = isRising(all, idx);

        Direction facing = axisNS ? Direction.NORTH : Direction.EAST;

        if (!rising) {
            region.addBlock(cx, cy,     cz, lectern(facing));
            region.addBlock(cx, cy + 1, cz, Blocks.PALE_MOSS_CARPET.getDefaultState());
        } else {
            region.addBlock(cx, cy,     cz, Blocks.PALE_MOSS_BLOCK.getDefaultState());
            region.addBlock(cx, cy + 1, cz, oakButton(facing));
        }

        BlockState gravel = Blocks.GRAVEL.getDefaultState();
        if (axisNS) {
            region.addBlock(cx + 1, cy, cz, gravel);
            region.addBlock(cx - 1, cy, cz, gravel);
        } else {
            region.addBlock(cx, cy, cz + 1, gravel);
            region.addBlock(cx, cy, cz - 1, gravel);
        }

        String d1 = neighborDir(prv, cx, cz);
        String d2 = neighborDir(nxt, cx, cz);

        if (axisNS) {
            placeLeafNS(region, cx, cy, cz, d1, d2, centers);
            placeDiagonalJoints(region, cx, cy, cz, true, centers);
        } else {
            placeLeafEW(region, cx, cy, cz, d1, d2, centers);
            placeDiagonalJoints(region, cx, cy, cz, false, centers);
        }
    }

    private boolean isRising(List<Seg> all, int idx) {
        Seg cur = all.get(idx);
        if (idx > 0 && all.get(idx - 1).cy() == cur.cy() - 1) return true;
        if (idx < all.size() - 1 && all.get(idx + 1).cy() == cur.cy() - 1) return true;
        return false;
    }

    private void placeLeafNS(ChunkedBlockRegion region,
                              int cx, int cy, int cz,
                              String d1, String d2,
                              Set<BlockPos> centers) {
        int eAmt = LEAF_STRAIGHT; String eFace = "north";
        int wAmt = LEAF_STRAIGHT; String wFace = "south";

        if      (pair(d1,d2,"N","S"))   { eAmt=2; eFace="north"; wAmt=2; wFace="south"; }
        else if (pair(d1,d2,"N","SE"))  { eAmt=3; eFace="south"; wAmt=2; wFace="south"; }
        else if (pair(d1,d2,"N","SO"))  { eAmt=2; eFace="north"; wAmt=3; wFace="east";  }
        else if (pair(d1,d2,"S","NE"))  { eAmt=3; eFace="west";  wAmt=2; wFace="south"; }
        else if (pair(d1,d2,"S","NO"))  { eAmt=2; eFace="north"; wAmt=3; wFace="north"; }
        else if (pair(d1,d2,"NE","SO")) { eAmt=3; eFace="west";  wAmt=3; wFace="east";  }
        else if (pair(d1,d2,"NO","SE")) { eAmt=3; eFace="south"; wAmt=3; wFace="north"; }

        safePlace(region, cx + 1, cy + 1, cz, leafLitter(eAmt, eFace), centers);
        safePlace(region, cx - 1, cy + 1, cz, leafLitter(wAmt, wFace), centers);
    }

    private void placeLeafEW(ChunkedBlockRegion region,
                              int cx, int cy, int cz,
                              String d1, String d2,
                              Set<BlockPos> centers) {
        int nAmt = LEAF_STRAIGHT; String nFace = "west";
        int sAmt = LEAF_STRAIGHT; String sFace = "east";

        if      (pair(d1,d2,"O","E"))   { nAmt=2; nFace="west";  sAmt=2; sFace="east";  }
        else if (pair(d1,d2,"E","NO"))  { nAmt=3; nFace="south"; sAmt=2; sFace="east";  }
        else if (pair(d1,d2,"O","NE"))  { nAmt=3; nFace="east";  sAmt=2; sFace="east";  }
        else if (pair(d1,d2,"O","SE"))  { nAmt=2; nFace="west";  sAmt=3; sFace="north"; }
        else if (pair(d1,d2,"E","SO"))  { nAmt=2; nFace="west";  sAmt=3; sFace="west";  }
        else if (pair(d1,d2,"NE","SO")) { nAmt=3; nFace="east";  sAmt=3; sFace="west";  }
        else if (pair(d1,d2,"NO","SE")) { nAmt=3; nFace="south"; sAmt=3; sFace="north"; }

        safePlace(region, cx, cy + 1, cz - 1, leafLitter(nAmt, nFace), centers);
        safePlace(region, cx, cy + 1, cz + 1, leafLitter(sAmt, sFace), centers);
    }

    private void placeDiagonalJoints(ChunkedBlockRegion region,
                                      int cx, int cy, int cz,
                                      boolean axisNS,
                                      Set<BlockPos> centers) {
        int[][] offsets = {{1,-1},{-1,-1},{1,1},{-1,1}};
        for (int[] off : offsets) {
            int ddx = off[0], ddz = off[1];
            String face;
            if (axisNS) {
                face = ddx > 0 ? (ddz < 0 ? "east" : "north") : (ddz < 0 ? "south" : "west");
            } else {
                face = ddz < 0 ? (ddx > 0 ? "north" : "south") : (ddx > 0 ? "east" : "west");
            }
            int jx = axisNS ? cx       : cx + ddx;
            int jz = axisNS ? cz + ddz : cz;
            if (!centers.contains(new BlockPos(jx, cy, jz))) {
                region.addBlock(jx, cy,     jz, Blocks.GRAVEL.getDefaultState());
                region.addBlock(jx, cy + 1, jz, leafLitter(LEAF_DIAGONAL, face));
            }
        }
    }

    private static String neighborDir(Seg seg, int cx, int cz) {
        if (seg == null) return "";
        int dx = sign(seg.cx() - cx);
        int dz = sign(seg.cz() - cz);
        String d = "";
        if (dz == -1) d = "N"; else if (dz == 1) d = "S";
        if (dx ==  1) d = d + "E"; else if (dx == -1) d = d + "O";
        return d;
    }

    private static boolean pair(String a, String b, String x, String y) {
        return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }

    private static int sign(int v) { return v == 0 ? 0 : (v > 0 ? 1 : -1); }

    private static void safePlace(ChunkedBlockRegion region,
                                   int x, int y, int z,
                                   BlockState state,
                                   Set<BlockPos> centers) {
        if (!centers.contains(new BlockPos(x, y, z)))
            region.addBlock(x, y, z, state);
    }

    private static BlockState lectern(Direction facing) {
        return Blocks.LECTERN.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, facing)
                .with(LecternBlock.HAS_BOOK, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState oakButton(Direction facing) {
        BlockState state = Blocks.OAK_BUTTON.getDefaultState();
        for (var prop : state.getProperties()) {
            if (prop.getName().equals("face") && prop instanceof EnumProperty ep) {
                for (Object val : ep.getValues()) {
                    if (val.toString().equalsIgnoreCase("floor")) {
                        state = state.with(ep, (Comparable) val);
                        break;
                    }
                }
            } else if (prop.getName().equals("facing") && prop instanceof EnumProperty ep) {
                if (ep.getValues().contains(facing)) state = state.with(ep, facing);
            } else if (prop.getName().equals("powered") && prop instanceof BooleanProperty bp) {
                state = state.with(bp, true);
            }
        }
        return state;
    }

    private static BlockState leafLitter(int amount, String facing) {
        Direction dir = switch (facing.toLowerCase(java.util.Locale.ROOT)) {
            case "south" -> Direction.SOUTH;
            case "east"  -> Direction.EAST;
            case "west"  -> Direction.WEST;
            default      -> Direction.NORTH;
        };

        BlockState state = Blocks.LEAF_LITTER.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, dir);

        for (var prop : state.getProperties()) {
            if (prop.getName().equals("segment_amount") && prop instanceof IntProperty ip) {
                int min = ip.getValues().stream().mapToInt(Integer::intValue).min().orElse(1);
                int max = ip.getValues().stream().mapToInt(Integer::intValue).max().orElse(4);
                state = state.with(ip, Math.max(min, Math.min(max, amount)));
                break;
            }
        }
        return state;
    }

    private List<Pt> snapAllToGround(List<Pt> pts) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return pts;
        List<Pt> out = new ArrayList<>(pts.size());
        for (Pt p : pts) {
            int sy = findSurface(world, p.bx(), p.by(), p.bz());
            out.add(new Pt(p.x(), sy + 0.5, p.z(), p.bx(), sy, p.bz()));
        }
        return out;
    }

    private static int findSurface(ClientWorld world, int x, int startY, int z) {
        var pos = new BlockPos.Mutable(x, startY, z);
        for (int dy = 0; dy >= -256; dy--) {
            pos.setY(startY + dy);
            if (!world.getBlockState(pos).isAir()) return startY + dy + 1;
        }
        for (int dy = 1; dy <= 256; dy++) {
            pos.setY(startY + dy);
            if (!world.getBlockState(pos).isAir()) return startY + dy + 1;
        }
        return startY;
    }

    private List<Pt> catmullRom(List<BlockPos> pts, int dens) {
        List<Vec3d> v = new ArrayList<>();
        for (BlockPos p : pts) v.add(Vec3d.ofCenter(p));

        List<Vec3d> ext = new ArrayList<>();
        ext.add(v.get(0).add(v.get(0).subtract(v.get(1))));
        ext.addAll(v);
        ext.add(v.getLast().add(v.getLast().subtract(v.get(v.size() - 2))));

        List<Vec3d> raw = new ArrayList<>();
        for (int i = 1; i < ext.size() - 2; i++) {
            Vec3d p0 = ext.get(i-1), p1 = ext.get(i),
                  p2 = ext.get(i+1), p3 = ext.get(i+2);
            int steps = Math.max(1, (int) Math.ceil(p1.distanceTo(p2) * dens));
            for (int s = 0; s < steps; s++)
                raw.add(crEval(p0, p1, p2, p3, (double) s / steps));
        }
        raw.add(v.getLast());

        List<Pt> out  = new ArrayList<>();
        BlockPos last = null;
        for (Vec3d q : raw) {
            int bx = (int) Math.floor(q.x);
            int by = (int) Math.floor(q.y);
            int bz = (int) Math.floor(q.z);
            BlockPos bp = new BlockPos(bx, by, bz);
            if (!bp.equals(last)) {
                out.add(new Pt(q.x, q.y, q.z, bx, by, bz));
                last = bp;
            }
        }
        return out;
    }

    private static Vec3d crEval(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return new Vec3d(
                crComp(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                crComp(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                crComp(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
    }

    private static double crComp(double a, double b, double c, double d,
                                  double t, double t2, double t3) {
        return 0.5 * (2*b + (-a+c)*t + (2*a-5*b+4*c-d)*t2 + (-a+3*b-3*c+d)*t3);
    }
}
