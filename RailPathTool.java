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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.WallShape;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.*;

public class RailPathTool implements Tool {

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

    private static BlockState makeCoral(Direction tang) {
        return Blocks.DEAD_FIRE_CORAL_WALL_FAN.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, tang)
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

    private final List<BlockPos>     points  = new ArrayList<>();
    private final ChunkedBlockRegion preview = new ChunkedBlockRegion();
    private boolean dirty = true;

    private final int[]     density       = {10};
    private final boolean[] placeOnGround = {false};
    private final boolean[] showPreview   = {true};

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
        t.putInt("DN",     density[0]);
        t.putBoolean("PG", placeOnGround[0]);
        t.putBoolean("SP", showPreview[0]);
    }

    @Override
    public void loadSettings(NbtCompound t) {
        if (t.contains("DN")) density[0]       = t.getInt("DN").orElse(density[0]);
        if (t.contains("PG")) placeOnGround[0] = t.getBoolean("PG").orElse(placeOnGround[0]);
        if (t.contains("SP")) showPreview[0]   = t.getBoolean("SP").orElse(showPreview[0]);
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
            float op = 0.6f + 0.2f * (float) Math.sin(time / 1_000_000.0 / 400.0);
            preview.render(camera, Vec3d.ZERO, null, matrices, projection, op, 0.25f);
        }
        if (points.size() >= 2) drawLines(matrices, camera);
        Tool.renderRaycastOverlay(
                Tool.raycastBlock(false, true, Tool.defaultIncludeFluids()),
                matrices, camera);
    }

    private void drawLines(MatrixStack matrices, Camera camera) {
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
        AxiomRenderer.renderPipeline(AxiomRenderPipelines.LINES_IGNORE_DEPTH,        null, mesh, true);
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
        if (ImGui.checkbox("Coller au sol", placeOnGround[0])) {
            placeOnGround[0] = !placeOnGround[0];
            changed = true;
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Preview", showPreview[0]))
            showPreview[0] = !showPreview[0];
        if (changed) dirty = true;

        ImGui.separator();
        if (points.size() >= 2 && ImGui.button("Valider", -1, 0)) confirm();
        if (!points.isEmpty()) {
            if (ImGui.button("Annuler dernier", -1, 0)) {
                points.remove(points.size() - 1); dirty = true;
            }
            ImGui.sameLine();
            if (ImGui.button("Réinitialiser", -1, 0)) reset();
        }
        ImGui.separator();
        ImGui.textColored(0xFFAAAAAA,
                "Clic droit: point | Entrée: valider | Suppr: annuler");
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
        var r = new ChunkedBlockRegion();
        buildRail(r);
        RegionHelper.pushBlockRegionChange(r, "BTE Rail Path");
        reset();
    }

    private record Seg(int cx, int cy, int cz, int dx, int dz) {}

    private void buildRail(ChunkedBlockRegion region) {
        List<Pt> sp = catmullRom(points, density[0]);
        if (sp.size() < 2) return;
        if (placeOnGround[0]) sp = snapToGround(sp);

        // 1. Construire la séquence de blocs-centre uniques
        List<Seg> segs = buildSegments(sp);
        if (segs.isEmpty()) return;

        // 2. Ensemble de tous les centres (pour éviter l'écrasement)
        Set<BlockPos> centers = new HashSet<>();
        for (Seg s : segs) centers.add(new BlockPos(s.cx(), s.cy(), s.cz()));

        // 3. Placer chaque segment
        for (int i = 0; i < segs.size(); i++) {
            Seg cur = segs.get(i);
            Seg prv = i > 0              ? segs.get(i - 1) : null;
            Seg nxt = i < segs.size()-1  ? segs.get(i + 1) : null;
            placeSegment(region, cur, prv, nxt, centers);
        }
    }

    private List<Seg> buildSegments(List<Pt> sp) {
        List<Seg> out = new ArrayList<>();
        BlockPos last = null;

        for (Pt p : sp) {
            BlockPos bp = new BlockPos(p.bx(), p.by(), p.bz());
            if (bp.equals(last)) continue;

            int dx = 0, dz = 0;
            if (last != null) {
                dx = bp.getX() - last.getX();
                dz = bp.getZ() - last.getZ();
                // Clamp à -1/0/+1 (au cas où la spline saute 2 blocs)
                if (dx != 0) dx = dx > 0 ? 1 : -1;
                if (dz != 0) dz = dz > 0 ? 1 : -1;
            }
            out.add(new Seg(bp.getX(), bp.getY(), bp.getZ(), dx, dz));
            last = bp;
        }
        return out;
    }

    private void placeSegment(ChunkedBlockRegion region,
                               Seg cur, Seg prv, Seg nxt,
                               Set<BlockPos> centers) {

        int cx = cur.cx(), cy = cur.cy(), cz = cur.cz();

        int tx, tz;
        if (prv != null && nxt != null) {
            tx = nxt.cx() - prv.cx();
            tz = nxt.cz() - prv.cz();
        } else if (prv != null) {
            tx = cx - prv.cx();
            tz = cz - prv.cz();
        } else if (nxt != null) {
            tx = nxt.cx() - cx;
            tz = nxt.cz() - cz;
        } else {
            tx = 1; tz = 0;
        }

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

        BlockState leftBlock  = computeSideBlock(tang, dx, dz, ndx, ndz, true);
        BlockState rightBlock = computeSideBlock(tang, dx, dz, ndx, ndz, false);

        safePlace(region, lx, cy, lz, leftBlock,  centers);
        safePlace(region, rx, cy, rz, rightBlock, centers);
    }

    private BlockState computeSideBlock(Direction tang,
                                         int dx, int dz,
                                         int ndx, int ndz,
                                         boolean isLeft) {

        Direction latCur = lateralComponent(dx, dz, tang);
        Direction latNxt = lateralComponent(ndx, ndz, tang);

        boolean curDiag = Math.abs(dx)  == 1 && Math.abs(dz)  == 1;
        boolean nxtDiag = Math.abs(ndx) == 1 && Math.abs(ndz) == 1;

        if (curDiag && nxtDiag && latCur == latNxt) {
            return diagWall(tang, dx, dz, isLeft);
        }

        Direction lat = latCur != null ? latCur : latNxt;
        if (lat != null) {
            return shelfFromDir(lat);
        }

        return (tang == Direction.NORTH || tang == Direction.SOUTH) ? WALL_NS : WALL_EW;
    }

    private BlockState diagWall(Direction tang, int dx, int dz, boolean isLeft) {
        return switch (tang) {
            case NORTH, SOUTH -> {
                if (dx > 0) {
                    yield isLeft ? WALL_NE : WALL_SW;
                } else {
                    yield isLeft ? WALL_SE : WALL_NW;
                }
            }
            case EAST, WEST -> {
                if (dz > 0) {
                    yield isLeft ? WALL_NW : WALL_SE;
                } else {
                    yield isLeft ? WALL_SW : WALL_NE;
                }
            }
            default -> WALL_NS;
        };
    }

    private BlockState shelfFromDir(Direction dir) {
        return switch (dir) {
            case NORTH -> SHELF_N;
            case SOUTH -> SHELF_S;
            case EAST  -> SHELF_E;
            case WEST  -> SHELF_W;
            default    -> SHELF_E;
        };
    }

    private Direction lateralComponent(int dx, int dz, Direction tang) {
        boolean ns = (tang == Direction.NORTH || tang == Direction.SOUTH);
        if (ns) {
            if (dx > 0) return Direction.EAST;
            if (dx < 0) return Direction.WEST;
        } else {
            if (dz > 0) return Direction.SOUTH;
            if (dz < 0) return Direction.NORTH;
        }
        return null;
    }

    private Direction cardinalFromVec(int tx, int tz) {
        if (Math.abs(tx) >= Math.abs(tz)) {
            return tx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return tz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private void safePlace(ChunkedBlockRegion region,
                            int x, int y, int z,
                            BlockState state,
                            Set<BlockPos> centers) {
        if (!centers.contains(new BlockPos(x, y, z)))
            region.addBlock(x, y, z, state);
    }

    private List<Pt> snapToGround(List<Pt> pts) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return pts;
        List<Pt> out = new ArrayList<>(pts.size());
        for (Pt p : pts) {
            int y = findSurface(world, p.bx(), p.by(), p.bz());
            out.add(new Pt(p.x(), y + 0.5, p.z(), p.bx(), y, p.bz()));
        }
        return out;
    }

    private int findSurface(ClientWorld world, int x, int startY, int z) {
        var pos = new BlockPos.Mutable(x, startY, z);
        for (int dy = 0; dy >= -128; dy--) {
            pos.setY(startY + dy);
            if (!world.getBlockState(pos).isAir()) return startY + dy + 1;
        }
        for (int dy = 1; dy <= 128; dy++) {
            pos.setY(startY + dy);
            if (!world.getBlockState(pos).isAir()) return startY + dy + 1;
        }
        return startY;
    }

    private record Pt(double x, double y, double z, int bx, int by, int bz) {}

    private List<Pt> catmullRom(List<BlockPos> pts, int dens) {
        List<Vec3d> v = new ArrayList<>();
        for (BlockPos p : pts) v.add(Vec3d.ofCenter(p));

        List<Vec3d> e = new ArrayList<>();
        e.add(v.get(0).add(v.get(0).subtract(v.get(1))));
        e.addAll(v);
        e.add(v.getLast().add(v.getLast().subtract(v.get(v.size() - 2))));

        List<Vec3d> raw = new ArrayList<>();
        for (int i = 1; i < e.size() - 2; i++) {
            Vec3d p0 = e.get(i-1), p1 = e.get(i),
                    p2 = e.get(i+1), p3 = e.get(i+2);
            int steps = Math.max(1, (int) Math.ceil(p1.distanceTo(p2) * dens));
            for (int s = 0; s < steps; s++)
                raw.add(crPoint(p0, p1, p2, p3, (double) s / steps));
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

    private Vec3d crPoint(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t*t, t3 = t2*t;
        return new Vec3d(
                crComp(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                crComp(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                crComp(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
    }

    private double crComp(double a, double b, double c, double d,
                           double t, double t2, double t3) {
        return 0.5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t2+(-a+3*b-3*c+d)*t3);
    }
}