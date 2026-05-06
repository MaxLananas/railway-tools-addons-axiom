package com.bte.railpathtool.tools;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.core_rendering.AxiomRenderPipelines;
import com.moulberry.axiom.core_rendering.AxiomRenderer;
import com.moulberry.axiom.editor.ImGuiHelper;
import com.moulberry.axiom.render.Shapes;
import com.moulberry.axiom.render.VertexConsumerProvider;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.tools.Tool;
import com.moulberry.axiom.utils.RegionHelper;
import imgui.ImGui;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.enums.Attachment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.*;

public class RailPathTool implements Tool {

    private static final int LEAF_STRAIGHT = 2;
    private static final int LEAF_DIAGONAL = 3;

    private final List<BlockPos>     points  = new ArrayList<>();
    private final ChunkedBlockRegion preview = new ChunkedBlockRegion();
    private boolean dirty = true;

    private final int[]     density       = {10};
    private final boolean[] snapToGround  = {false};
    private final boolean[] showPreview   = {true};

    @Override public String  name()           { return "BTE Rail Path"; }
    @Override public char    iconChar()       { return '\ue912'; }
    @Override public String  keybindId()      { return "bte_rail_path"; }
    @Override public int     defaultKeybind() { return 0; }

    @Override
    public EnumSet<AxiomPermission> requiredPermissions() {
        return EnumSet.of(AxiomPermission.TOOL, AxiomPermission.BUILD_SECTION);
    }

    @Override public void reset() { points.clear(); preview.clear(); dirty = false; }

    @Override
    public void writeSettings(NbtCompound tag) {
        tag.putInt("density",      density[0]);
        tag.putBoolean("snap",     snapToGround[0]);
        tag.putBoolean("preview",  showPreview[0]);
    }

    @Override
    public void loadSettings(NbtCompound tag) {
        if (tag.contains("density"))  density[0]      = tag.getInt("density").orElse(density[0]);
        if (tag.contains("snap"))     snapToGround[0] = tag.getBoolean("snap").orElse(snapToGround[0]);
        if (tag.contains("preview"))  showPreview[0]  = tag.getBoolean("preview").orElse(showPreview[0]);
    }

    @Override
    public void render(Camera camera, float tickDelta, long time,
                       MatrixStack matrices, Matrix4f projection) {
        if (dirty) {
            preview.clear();
            if (points.size() >= 2) buildRail(preview);
            dirty = false;
        }

        if (showPreview[0] && !preview.isEmpty()) {
            float pulse = 0.6f + 0.2f * (float) Math.sin(time / 400_000.0);
            preview.render(camera, Vec3d.ZERO, null, matrices, projection, pulse, 0.25f);
        }

        if (points.size() >= 2) renderControlLines(matrices, camera);

        Tool.renderRaycastOverlay(
            Tool.raycastBlock(false, true, Tool.defaultIncludeFluids()),
            matrices, camera
        );
    }

    private void renderControlLines(MatrixStack matrices, Camera camera) {
        var provider = VertexConsumerProvider.shared();
        matrices.push();
        matrices.translate(-camera.getPos().x, -camera.getPos().y, -camera.getPos().z);

        var buffer = provider.begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR_NORMAL);
        var pose   = matrices.peek();

        for (int i = 0; i < points.size() - 1; i++) {
            Shapes.line(buffer, pose,
                Vec3d.ofCenter(points.get(i)),
                Vec3d.ofCenter(points.get(i + 1))
            );
        }

        var mesh = provider.build();
        AxiomRenderer.setShaderColour(1f, 0.45f, 0.1f, 1f);
        AxiomRenderer.renderPipeline(AxiomRenderPipelines.LINES_WITHOUT_WRITE_DEPTH, null, mesh, false);
        AxiomRenderer.setShaderColour(1f, 0.45f, 0.1f, 0.3f);
        AxiomRenderer.renderPipeline(AxiomRenderPipelines.LINES_IGNORE_DEPTH, null, mesh, true);
        AxiomRenderer.setShaderColour(1f, 1f, 1f, 1f);

        matrices.pop();
    }

    @Override
    public void displayImguiOptions() {
        ImGuiHelper.separatorWithText("BTE Rail Path Tool");
        ImGui.text("Points : " + points.size());
        ImGui.separator();

        boolean changed = false;
        if (ImGui.sliderInt("Densité (pts/bloc)", density, 2, 32)) changed = true;

        if (ImGui.checkbox("Coller au sol", snapToGround[0])) {
            snapToGround[0] = !snapToGround[0];
            changed = true;
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Aperçu", showPreview[0])) showPreview[0] = !showPreview[0];

        if (changed) dirty = true;

        ImGui.separator();

        if (points.size() >= 2 && ImGui.button("Valider", -1, 0)) confirm();

        if (!points.isEmpty()) {
            if (ImGui.button("Annuler dernier", -1, 0)) {
                points.remove(points.size() - 1);
                dirty = true;
            }
            ImGui.sameLine();
            if (ImGui.button("Réinitialiser", -1, 0)) reset();
        }

        ImGui.separator();
        ImGui.textColored(0xFFAAAAAA, "Clic droit : point  |  Entrée : valider  |  Suppr : annuler");
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

    @Override public String listenForEsc()   { return points.isEmpty()   ? null : "Réinitialiser"; }
    @Override public String listenForEnter() { return points.size() >= 2 ? "Valider" : null; }

    private void confirm() {
        var region = new ChunkedBlockRegion();
        buildRail(region);
        RegionHelper.pushBlockRegionChange(region, "BTE Rail Path");
        reset();
    }

    private record SplinePoint(double wx, double wy, double wz, int bx, int by, int bz) {}

    private record Segment(int cx, int cy, int cz, int dx, int dz) {}

    private void buildRail(ChunkedBlockRegion region) {
        List<SplinePoint> spline = catmullRom(points, density[0]);
        if (spline.size() < 2) return;
        if (snapToGround[0]) spline = snapAllToGround(spline);

        List<Segment> segments = toSegments(spline);
        if (segments.isEmpty()) return;

        Set<BlockPos> centers = new HashSet<>();
        for (Segment s : segments) centers.add(new BlockPos(s.cx(), s.cy(), s.cz()));

        for (int i = 0; i < segments.size(); i++) {
            Segment prev    = i > 0              ? segments.get(i - 1) : null;
            Segment current = segments.get(i);
            Segment next    = i < segments.size() - 1 ? segments.get(i + 1) : null;
            placeSegment(region, prev, current, next, segments, i, centers);
        }
    }

    private List<Segment> toSegments(List<SplinePoint> spline) {
        List<Segment> out  = new ArrayList<>();
        BlockPos      last = null;

        for (SplinePoint p : spline) {
            BlockPos bp = new BlockPos(p.bx(), p.by(), p.bz());
            if (bp.equals(last)) continue;

            int dx = 0, dz = 0;
            if (last != null) {
                dx = sign(bp.getX() - last.getX());
                dz = sign(bp.getZ() - last.getZ());
            }
            out.add(new Segment(bp.getX(), bp.getY(), bp.getZ(), dx, dz));
            last = bp;
        }
        return out;
    }

    private void placeSegment(ChunkedBlockRegion region,
                               Segment prev, Segment cur, Segment next,
                               List<Segment> all, int idx,
                               Set<BlockPos> centers) {

        int cx = cur.cx(), cy = cur.cy(), cz = cur.cz();

        int tx = 0, tz = 0;
        if (prev != null) { tx += cx - prev.cx(); tz += cz - prev.cz(); }
        if (next != null) { tx += next.cx() - cx; tz += next.cz() - cz; }
        if (tx == 0 && tz == 0) { tx = cur.dx(); tz = cur.dz(); }

        boolean axisNS = Math.abs(tz) >= Math.abs(tx);
        boolean rising = isRising(all, idx);

        placeCenterAndTop(region, cx, cy, cz, axisNS, rising);
        placeRails(region, cx, cy, cz, axisNS);

        String d1 = neighborDir(prev, cx, cz);
        String d2 = neighborDir(next, cx, cz);

        if (axisNS) {
            placeLeafNS(region, cx, cy, cz, d1, d2, centers);
            placeDiagonalJoints(region, cx, cy, cz, true, centers);
        } else {
            placeLeafEW(region, cx, cy, cz, d1, d2, centers);
            placeDiagonalJoints(region, cx, cy, cz, false, centers);
        }
    }

    private boolean isRising(List<Segment> all, int idx) {
        Segment cur = all.get(idx);
        if (idx > 0 && all.get(idx - 1).cy() == cur.cy() - 1) return true;
        if (idx < all.size() - 1 && all.get(idx + 1).cy() == cur.cy() - 1) return true;
        return false;
    }

    private void placeCenterAndTop(ChunkedBlockRegion region,
                                    int cx, int cy, int cz,
                                    boolean axisNS, boolean rising) {
        Direction facing = axisNS ? Direction.NORTH : Direction.EAST;

        if (!rising) {
            region.addBlock(cx, cy,     cz, lectern(facing));
            region.addBlock(cx, cy + 1, cz, paleMossCarpet());
        } else {
            region.addBlock(cx, cy,     cz, Blocks.PALE_MOSS_BLOCK.getDefaultState());
            region.addBlock(cx, cy + 1, cz, oakButton(facing));
        }
    }

    private void placeRails(ChunkedBlockRegion region, int cx, int cy, int cz, boolean axisNS) {
        BlockState gravel = Blocks.GRAVEL.getDefaultState();
        if (axisNS) {
            region.addBlock(cx + 1, cy, cz, gravel);
            region.addBlock(cx - 1, cy, cz, gravel);
        } else {
            region.addBlock(cx, cy, cz + 1, gravel);
            region.addBlock(cx, cy, cz - 1, gravel);
        }
    }

    private void placeLeafNS(ChunkedBlockRegion region,
                              int cx, int cy, int cz,
                              String d1, String d2,
                              Set<BlockPos> centers) {

        int eAmt = LEAF_STRAIGHT; String eFace = "north";
        int wAmt = LEAF_STRAIGHT; String wFace = "south";

        if      (pair(d1, d2, "N",  "S" )) { eAmt=2; eFace="north"; wAmt=2; wFace="south"; }
        else if (pair(d1, d2, "N",  "SE")) { eAmt=3; eFace="south"; wAmt=2; wFace="south"; }
        else if (pair(d1, d2, "N",  "SO")) { eAmt=2; eFace="north"; wAmt=3; wFace="east";  }
        else if (pair(d1, d2, "S",  "NE")) { eAmt=3; eFace="west";  wAmt=2; wFace="south"; }
        else if (pair(d1, d2, "S",  "NO")) { eAmt=2; eFace="north"; wAmt=3; wFace="north"; }
        else if (pair(d1, d2, "NE", "SO")) { eAmt=3; eFace="west";  wAmt=3; wFace="east";  }
        else if (pair(d1, d2, "NO", "SE")) { eAmt=3; eFace="south"; wAmt=3; wFace="north"; }

        safeAdd(region, cx + 1, cy + 1, cz, leafLitter(eAmt, eFace), centers);
        safeAdd(region, cx - 1, cy + 1, cz, leafLitter(wAmt, wFace), centers);
    }

    private void placeLeafEW(ChunkedBlockRegion region,
                              int cx, int cy, int cz,
                              String d1, String d2,
                              Set<BlockPos> centers) {

        int nAmt = LEAF_STRAIGHT; String nFace = "west";
        int sAmt = LEAF_STRAIGHT; String sFace = "east";

        if      (pair(d1, d2, "O",  "E" )) { nAmt=2; nFace="west";  sAmt=2; sFace="east";  }
        else if (pair(d1, d2, "E",  "NO")) { nAmt=3; nFace="south"; sAmt=2; sFace="east";  }
        else if (pair(d1, d2, "O",  "NE")) { nAmt=3; nFace="east";  sAmt=2; sFace="east";  }
        else if (pair(d1, d2, "O",  "SE")) { nAmt=2; nFace="west";  sAmt=3; sFace="north"; }
        else if (pair(d1, d2, "E",  "SO")) { nAmt=2; nFace="west";  sAmt=3; sFace="west";  }
        else if (pair(d1, d2, "NE", "SO")) { nAmt=3; nFace="east";  sAmt=3; sFace="west";  }
        else if (pair(d1, d2, "NO", "SE")) { nAmt=3; nFace="south"; sAmt=3; sFace="north"; }

        safeAdd(region, cx, cy + 1, cz - 1, leafLitter(nAmt, nFace), centers);
        safeAdd(region, cx, cy + 1, cz + 1, leafLitter(sAmt, sFace), centers);
    }

    private void placeDiagonalJoints(ChunkedBlockRegion region,
                                      int cx, int cy, int cz,
                                      boolean axisNS,
                                      Set<BlockPos> centers) {
        int[][] offsets = {{1, -1}, {-1, -1}, {1, 1}, {-1, 1}};

        for (int[] off : offsets) {
            int ddx = off[0], ddz = off[1];

            String face;
            if (axisNS) {
                face = ddx > 0 ? (ddz < 0 ? "east"  : "north")
                               : (ddz < 0 ? "south" : "west");
            } else {
                face = ddz < 0 ? (ddx > 0 ? "north" : "south")
                               : (ddx > 0 ? "east"  : "west");
            }

            int jx = axisNS ? cx     : cx + ddx;
            int jz = axisNS ? cz + ddz : cz;

            if (!centers.contains(new BlockPos(jx, cy, jz))) {
                region.addBlock(jx, cy,     jz, Blocks.GRAVEL.getDefaultState());
                region.addBlock(jx, cy + 1, jz, leafLitter(LEAF_DIAGONAL, face));
            }
        }
    }

    private static String neighborDir(Segment seg, int cx, int cz) {
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

    private static int sign(int v) {
        return v == 0 ? 0 : (v > 0 ? 1 : -1);
    }

    private static void safeAdd(ChunkedBlockRegion region,
                                 int x, int y, int z,
                                 BlockState state,
                                 Set<BlockPos> centers) {
        if (!centers.contains(new BlockPos(x, y, z))) region.addBlock(x, y, z, state);
    }

    private static BlockState lectern(Direction facing) {
        return Blocks.LECTERN.getDefaultState()
            .with(Properties.HORIZONTAL_FACING, facing)
            .with(LecternBlock.HAS_BOOK, false);
    }

    private static BlockState paleMossCarpet() {
        return Blocks.PALE_MOSS_CARPET.getDefaultState();
    }

    private static BlockState oakButton(Direction facing) {
        return Blocks.OAK_BUTTON.getDefaultState()
            .with(Properties.HORIZONTAL_FACING, facing)
            .with(Properties.ATTACHMENT_TYPE, Attachment.FLOOR)
            .with(Properties.POWERED, true);
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

        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals("segment_amount") && prop instanceof IntProperty ip) {
                int clamped = Math.max(ip.getMin(), Math.min(ip.getMax(), amount));
                state = state.with(ip, clamped);
                break;
            }
        }

        return state;
    }

    private List<SplinePoint> snapAllToGround(List<SplinePoint> pts) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return pts;

        List<SplinePoint> snapped = new ArrayList<>(pts.size());
        for (SplinePoint p : pts) {
            int sy = findSurface(world, p.bx(), p.by(), p.bz());
            snapped.add(new SplinePoint(p.wx(), sy + 0.5, p.wz(), p.bx(), sy, p.bz()));
        }
        return snapped;
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

    private List<SplinePoint> catmullRom(List<BlockPos> pts, int density) {
        List<Vec3d> v = new ArrayList<>();
        for (BlockPos p : pts) v.add(Vec3d.ofCenter(p));

        List<Vec3d> extended = new ArrayList<>();
        extended.add(v.get(0).add(v.get(0).subtract(v.get(1))));
        extended.addAll(v);
        extended.add(v.getLast().add(v.getLast().subtract(v.get(v.size() - 2))));

        List<Vec3d> raw = new ArrayList<>();
        for (int i = 1; i < extended.size() - 2; i++) {
            Vec3d p0 = extended.get(i - 1);
            Vec3d p1 = extended.get(i);
            Vec3d p2 = extended.get(i + 1);
            Vec3d p3 = extended.get(i + 2);
            int steps = Math.max(1, (int) Math.ceil(p1.distanceTo(p2) * density));
            for (int s = 0; s < steps; s++) {
                raw.add(crEval(p0, p1, p2, p3, (double) s / steps));
            }
        }
        raw.add(v.getLast());

        List<SplinePoint> out  = new ArrayList<>();
        BlockPos          last = null;

        for (Vec3d q : raw) {
            int bx = (int) Math.floor(q.x);
            int by = (int) Math.floor(q.y);
            int bz = (int) Math.floor(q.z);
            BlockPos bp = new BlockPos(bx, by, bz);
            if (!bp.equals(last)) {
                out.add(new SplinePoint(q.x, q.y, q.z, bx, by, bz));
                last = bp;
            }
        }

        return out;
    }

    private static Vec3d crEval(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return new Vec3d(
            crComp(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
            crComp(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
            crComp(p0.z, p1.z, p2.z, p3.z, t, t2, t3)
        );
    }

    private static double crComp(double a, double b, double c, double d,
                                  double t, double t2, double t3) {
        return 0.5 * (2*b + (-a + c)*t + (2*a - 5*b + 4*c - d)*t2 + (-a + 3*b - 3*c + d)*t3);
    }
}
