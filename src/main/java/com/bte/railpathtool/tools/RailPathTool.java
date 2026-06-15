package com.bte.railpathtool.tools;

import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.IAxiomWorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.BlockHitResult;

import java.lang.reflect.Method;
import java.util.*;

public class RailPathTool implements CustomTool {

    private static final String[] STYLE_LABELS = {"Classique", "Naturel"};

    private final List<BlockPos> points = new ArrayList<>();
    private boolean dirty = true;
    private final int[] density = {10};
    private final boolean[] snapToGround = {false};
    private final boolean[] showPreview = {true};
    private final int[] styleIndex = {0};
    private final int[] themeIndex = {0};

    private Object previewRegion = null;
    private Object toolService = null;
    private Object regionProvider = null;

    private static Class<?> IMGUI_CLASS = null;
    private static boolean IMGUI_RESOLVED = false;

    private static Class<?> imguiClass() {
        if (!IMGUI_RESOLVED) {
            IMGUI_RESOLVED = true;
            try { IMGUI_CLASS = Class.forName("imgui.ImGui"); } catch (ClassNotFoundException ignored) {}
        }
        return IMGUI_CLASS;
    }

    private static void igText(String s) {
        try { Class<?> c = imguiClass(); if (c != null) c.getMethod("text", String.class).invoke(null, s); } catch (Exception ignored) {}
    }

    private static void igTextDisabled(String s) {
        try { Class<?> c = imguiClass(); if (c != null) c.getMethod("textDisabled", String.class).invoke(null, s); } catch (Exception ignored) {}
    }

    private static void igSeparator() {
        try { Class<?> c = imguiClass(); if (c != null) c.getMethod("separator").invoke(null); } catch (Exception ignored) {}
    }

    private static void igSameLine() {
        try { Class<?> c = imguiClass(); if (c != null) c.getMethod("sameLine").invoke(null); } catch (Exception ignored) {}
    }

    private static boolean igSliderInt(String label, int[] v, int min, int max) {
        try { Class<?> c = imguiClass(); if (c != null) return (boolean) c.getMethod("sliderInt", String.class, int[].class, int.class, int.class).invoke(null, label, v, min, max); } catch (Exception ignored) {}
        return false;
    }

    private static boolean igCheckbox(String label, boolean[] v) {
        try { Class<?> c = imguiClass(); if (c != null) return (boolean) c.getMethod("checkbox", String.class, boolean[].class).invoke(null, label, v); } catch (Exception ignored) {}
        return false;
    }

    private static boolean igRadioButton(String label, boolean active) {
        try { Class<?> c = imguiClass(); if (c != null) return (boolean) c.getMethod("radioButton", String.class, boolean.class).invoke(null, label, active); } catch (Exception ignored) {}
        return false;
    }

    private static boolean igButton(String label) {
        try { Class<?> c = imguiClass(); if (c != null) return (boolean) c.getMethod("button", String.class).invoke(null, label); } catch (Exception ignored) {}
        return false;
    }

    private Object getToolService() {
        if (toolService != null) return toolService;
        try {
            Class<?> svcClass = Class.forName("com.moulberry.axiomclientapi.service.ToolService");
            ServiceLoader<?> loader = ServiceLoader.load(svcClass);
            for (Object s : loader) { toolService = s; break; }
        } catch (Exception ignored) {}
        return toolService;
    }

    private Object getRegionProvider() {
        if (regionProvider != null) return regionProvider;
        try {
            Class<?> svcClass = Class.forName("com.moulberry.axiomclientapi.service.RegionProvider");
            ServiceLoader<?> loader = ServiceLoader.load(svcClass);
            for (Object s : loader) { regionProvider = s; break; }
        } catch (Exception ignored) {}
        return regionProvider;
    }

    private Object createBlockRegion() {
        try {
            Object rp = getRegionProvider();
            if (rp != null) return rp.getClass().getMethod("createBlock").invoke(rp);
        } catch (Exception ignored) {}
        return null;
    }

    private void regionClear(Object region) {
        if (region == null) return;
        try { region.getClass().getMethod("clear").invoke(region); } catch (Exception ignored) {}
    }

    private void regionAddBlock(Object region, int x, int y, int z, BlockState state) {
        if (region == null) return;
        try { region.getClass().getMethod("addBlock", int.class, int.class, int.class, BlockState.class).invoke(region, x, y, z, state); } catch (Exception ignored) {}
    }

    private boolean regionIsEmpty(Object region) {
        if (region == null) return true;
        try { return (boolean) region.getClass().getMethod("isEmpty").invoke(region); } catch (Exception ignored) { return true; }
    }

    private void regionRender(Object region, IAxiomWorldRenderContext rc, float opacity) {
        if (region == null) return;
        try { region.getClass().getMethod("render", IAxiomWorldRenderContext.class, net.minecraft.world.phys.Vec3.class, float.class, float.class).invoke(region, rc, net.minecraft.world.phys.Vec3.ZERO, opacity, 0.25f); } catch (Exception ignored) {}
    }

    private void pushRegionChange(Object region, String name) {
        try {
            Object svc = getToolService();
            if (svc != null) svc.getClass().getMethod("pushBlockRegionChange", Class.forName("com.moulberry.axiomclientapi.regions.BlockRegion")).invoke(svc, region);
        } catch (Exception ignored) {}
    }

    private BlockHitResult doRaycast() {
        try {
            Object svc = getToolService();
            if (svc != null) return (BlockHitResult) svc.getClass().getMethod("raycastBlock").invoke(svc);
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public String name() { return "BTE Rail Path"; }

    @Override
    public void reset() {
        points.clear();
        regionClear(previewRegion);
        dirty = false;
    }

    @Override
    public void render(IAxiomWorldRenderContext rc) {
        if (previewRegion == null) previewRegion = createBlockRegion();
        if (dirty) {
            regionClear(previewRegion);
            if (points.size() >= 2) buildRail(previewRegion);
            dirty = false;
        }
        if (showPreview[0] && !regionIsEmpty(previewRegion)) {
            float pulse = 0.6f + 0.2f * (float) Math.sin(System.currentTimeMillis() / 400_000.0);
            regionRender(previewRegion, rc, pulse);
        }
    }

    @Override
    public void displayImguiOptions() {
        igText("=== BTE Rail Path Tool ===");
        igText("Points : " + points.size());
        igSeparator();

        boolean changed = false;
        if (igSliderInt("Densite (pts/bloc)", density, 2, 32)) changed = true;

        if (igCheckbox("Coller au sol", snapToGround)) changed = true;
        igSameLine();
        igCheckbox("Apercu", showPreview);

        igSeparator();
        igText("Style :");
        for (int i = 0; i < STYLE_LABELS.length; i++) {
            if (i > 0) igSameLine();
            if (igRadioButton(STYLE_LABELS[i], styleIndex[0] == i)) { styleIndex[0] = i; changed = true; }
        }

        igText("Theme :");
        String[] themes = {"Sombre (mud+shelf)", "Clair (andesite+door)"};
        for (int i = 0; i < themes.length; i++) {
            if (i > 0) igSameLine();
            if (igRadioButton(themes[i], themeIndex[0] == i)) { themeIndex[0] = i; changed = true; }
        }

        igTextDisabled(styleIndex[0] == 0
                ? "Corail + murs + etageres"
                : "Pupitre + pale moss + feuilles");

        if (changed) dirty = true;

        igSeparator();
        if (points.size() >= 2 && igButton("Valider")) confirm();
        if (!points.isEmpty()) {
            if (igButton("Annuler dernier")) { points.remove(points.size() - 1); dirty = true; }
            igSameLine();
            if (igButton("Reinitialiser")) reset();
        }
        igSeparator();
        igTextDisabled("Clic droit: poser point | Entree: valider | Suppr: annuler");
    }

    @Override
    public boolean callUseTool() {
        BlockHitResult hit = doRaycast();
        if (hit == null) return false;
        points.add(hit.getBlockPos().above());
        dirty = true;
        return true;
    }

    @Override
    public boolean callConfirm() {
        if (points.size() < 2) return false;
        confirm();
        return true;
    }

    @Override
    public boolean callDelete() {
        if (points.isEmpty()) return false;
        points.remove(points.size() - 1);
        dirty = true;
        return true;
    }

    private void confirm() {
        Object region = createBlockRegion();
        buildRail(region);
        pushRegionChange(region, "BTE Rail Path");
        reset();
    }

    private record Seg(int cx, int cy, int cz, int dx, int dz) {}
    private record Pt(double x, double y, double z, int bx, int by, int bz) {}

    private void buildRail(Object region) {
        List<Pt> sp = catmullRom(points, density[0]);
        if (sp.size() < 2) return;
        if (snapToGround[0]) sp = snapAllToGround(sp);

        List<Seg> segs = buildSegments(sp);
        if (segs.isEmpty()) return;

        Set<BlockPos> centers = new HashSet<>();
        for (Seg s : segs) centers.add(new BlockPos(s.cx(), s.cy(), s.cz()));

        for (int i = 0; i < segs.size(); i++) {
            Seg cur = segs.get(i);
            Seg prv = i > 0 ? segs.get(i - 1) : null;
            Seg nxt = i < segs.size() - 1 ? segs.get(i + 1) : null;
            if (styleIndex[0] == 0) placeClassic(region, cur, prv, nxt, centers);
            else placeNatural(region, cur, prv, nxt, segs, i, centers);
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
                dx = Integer.compare(bp.getX(), last.getX());
                dz = Integer.compare(bp.getZ(), last.getZ());
            }
            out.add(new Seg(bp.getX(), bp.getY(), bp.getZ(), dx, dz));
            last = bp;
        }
        return out;
    }

    private BlockState wallBlock() {
        return themeIndex[0] == 0
                ? Blocks.MUD_BRICK_WALL.defaultBlockState()
                : Blocks.ANDESITE_WALL.defaultBlockState();
    }

    private BlockState makeWall(Direction... dirs) {
        BlockState s = wallBlock()
                .setValue(BlockStateProperties.UP, false)
                .setValue(BlockStateProperties.WATERLOGGED, false)
                .setValue(BlockStateProperties.NORTH_WALL, WallSide.NONE)
                .setValue(BlockStateProperties.SOUTH_WALL, WallSide.NONE)
                .setValue(BlockStateProperties.EAST_WALL, WallSide.NONE)
                .setValue(BlockStateProperties.WEST_WALL, WallSide.NONE);
        for (Direction d : dirs) {
            s = switch (d) {
                case NORTH -> s.setValue(BlockStateProperties.NORTH_WALL, WallSide.LOW);
                case SOUTH -> s.setValue(BlockStateProperties.SOUTH_WALL, WallSide.LOW);
                case EAST  -> s.setValue(BlockStateProperties.EAST_WALL,  WallSide.LOW);
                case WEST  -> s.setValue(BlockStateProperties.WEST_WALL,  WallSide.LOW);
                default    -> s;
            };
        }
        return s;
    }

    private BlockState makeSide(Direction facing) {
        if (themeIndex[0] == 0) {
            var id = net.minecraft.resources.ResourceLocation.parse("minecraft:spruce_shelf");
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (block != Blocks.AIR) {
                BlockState st = block.defaultBlockState();
                for (var prop : st.getProperties()) {
                    try {
                        if (prop.getName().equals("facing")) {
                            st = setEnumProp(st, prop, facing.getName());
                        } else if (prop.getName().equals("waterlogged") || prop.getName().equals("powered")) {
                            st = setBoolProp(st, prop, false);
                        }
                    } catch (Exception ignored) {}
                }
                return st;
            }
            return Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.OPEN, true)
                    .setValue(BlockStateProperties.WATERLOGGED, false);
        } else {
            return Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.DOOR_HINGE, net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT)
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
                    .setValue(BlockStateProperties.POWERED, false)
                    .setValue(BlockStateProperties.OPEN, false);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState setEnumProp(BlockState st, net.minecraft.world.level.block.state.properties.Property prop, String value) {
        for (Object v : prop.getPossibleValues()) {
            if (v.toString().equalsIgnoreCase(value)) return st.setValue(prop, (Comparable) v);
        }
        return st;
    }

    @SuppressWarnings({"unchecked"})
    private BlockState setBoolProp(BlockState st, net.minecraft.world.level.block.state.properties.Property prop, boolean value) {
        if (prop instanceof net.minecraft.world.level.block.state.properties.BooleanProperty bp) return st.setValue(bp, value);
        return st;
    }

    private BlockState makeCoral(Direction facing) {
        return Blocks.DEAD_BUBBLE_CORAL_WALL_FAN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    private void placeClassic(Object region, Seg cur, Seg prv, Seg nxt, Set<BlockPos> centers) {
        int cx = cur.cx(), cy = cur.cy(), cz = cur.cz();

        int tx, tz;
        if (prv != null && nxt != null) { tx = nxt.cx() - prv.cx(); tz = nxt.cz() - prv.cz(); }
        else if (prv != null) { tx = cx - prv.cx(); tz = cz - prv.cz(); }
        else if (nxt != null) { tx = nxt.cx() - cx; tz = nxt.cz() - cz; }
        else { tx = 1; tz = 0; }

        Direction tang = cardinal(tx, tz);
        regionAddBlock(region, cx, cy, cz, makeCoral(tang));

        int lx, lz, rx, rz;
        switch (tang) {
            case NORTH -> { lx = cx - 1; lz = cz; rx = cx + 1; rz = cz; }
            case SOUTH -> { lx = cx + 1; lz = cz; rx = cx - 1; rz = cz; }
            case EAST  -> { lx = cx; lz = cz - 1; rx = cx; rz = cz + 1; }
            default    -> { lx = cx; lz = cz + 1; rx = cx; rz = cz - 1; }
        }

        int dx = cur.dx(), dz = cur.dz();
        int ndx = nxt != null ? nxt.dx() : dx;
        int ndz = nxt != null ? nxt.dz() : dz;

        safeAdd(region, lx, cy, lz, sideBlockClassic(tang, dx, dz, ndx, ndz, true), centers);
        safeAdd(region, rx, cy, rz, sideBlockClassic(tang, dx, dz, ndx, ndz, false), centers);
    }

    private BlockState sideBlockClassic(Direction tang, int dx, int dz, int ndx, int ndz, boolean left) {
        Direction latCur = lateral(dx, dz, tang);
        Direction latNxt = lateral(ndx, ndz, tang);
        boolean curDiag = Math.abs(dx) == 1 && Math.abs(dz) == 1;
        boolean nxtDiag = Math.abs(ndx) == 1 && Math.abs(ndz) == 1;

        if (curDiag && nxtDiag && latCur == latNxt) {
            return switch (tang) {
                case NORTH, SOUTH -> dx > 0
                        ? (left ? makeWall(Direction.NORTH, Direction.EAST) : makeWall(Direction.SOUTH, Direction.WEST))
                        : (left ? makeWall(Direction.SOUTH, Direction.EAST) : makeWall(Direction.NORTH, Direction.WEST));
                case EAST, WEST -> dz > 0
                        ? (left ? makeWall(Direction.NORTH, Direction.WEST) : makeWall(Direction.SOUTH, Direction.EAST))
                        : (left ? makeWall(Direction.SOUTH, Direction.WEST) : makeWall(Direction.NORTH, Direction.EAST));
                default -> makeWall(Direction.NORTH, Direction.SOUTH);
            };
        }

        Direction lat = latCur != null ? latCur : latNxt;
        if (lat != null) return makeSide(lat);
        return (tang == Direction.NORTH || tang == Direction.SOUTH)
                ? makeWall(Direction.NORTH, Direction.SOUTH)
                : makeWall(Direction.EAST, Direction.WEST);
    }

    private Direction lateral(int dx, int dz, Direction tang) {
        boolean ns = tang == Direction.NORTH || tang == Direction.SOUTH;
        if (ns) return dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : null;
        else return dz > 0 ? Direction.SOUTH : dz < 0 ? Direction.NORTH : null;
    }

    private Direction cardinal(int tx, int tz) {
        if (Math.abs(tx) >= Math.abs(tz)) return tx >= 0 ? Direction.EAST : Direction.WEST;
        else return tz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void placeNatural(Object region, Seg cur, Seg prv, Seg nxt, List<Seg> all, int idx, Set<BlockPos> centers) {
        int cx = cur.cx(), cy = cur.cy(), cz = cur.cz();

        int tx = 0, tz = 0;
        if (prv != null) { tx += cx - prv.cx(); tz += cz - prv.cz(); }
        if (nxt != null) { tx += nxt.cx() - cx; tz += nxt.cz() - cz; }
        if (tx == 0 && tz == 0) { tx = cur.dx(); tz = cur.dz(); }

        boolean axisNS = Math.abs(tz) >= Math.abs(tx);
        Direction facing = axisNS ? Direction.NORTH : Direction.EAST;
        boolean rising = idx > 0 && all.get(idx - 1).cy() == cy - 1
                || idx < all.size() - 1 && all.get(idx + 1).cy() == cy - 1;

        if (!rising) {
            regionAddBlock(region, cx, cy, cz, Blocks.LECTERN.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK, false));
            regionAddBlock(region, cx, cy + 1, cz, Blocks.PALE_MOSS_CARPET.defaultBlockState());
        } else {
            regionAddBlock(region, cx, cy, cz, Blocks.PALE_MOSS_BLOCK.defaultBlockState());
            regionAddBlock(region, cx, cy + 1, cz, oakButton(facing));
        }

        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        if (axisNS) {
            regionAddBlock(region, cx + 1, cy, cz, gravel);
            regionAddBlock(region, cx - 1, cy, cz, gravel);
        } else {
            regionAddBlock(region, cx, cy, cz + 1, gravel);
            regionAddBlock(region, cx, cy, cz - 1, gravel);
        }

        String d1 = neighborDir(prv, cx, cz);
        String d2 = neighborDir(nxt, cx, cz);

        if (axisNS) placeLeafNS(region, cx, cy, cz, d1, d2, centers);
        else placeLeafEW(region, cx, cy, cz, d1, d2, centers);

        placeDiagJoints(region, cx, cy, cz, axisNS, centers);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState oakButton(Direction facing) {
        BlockState state = Blocks.OAK_BUTTON.defaultBlockState();
        for (var prop : state.getProperties()) {
            if (prop.getName().equals("face")) {
                state = setEnumProp(state, prop, "floor");
            } else if (prop.getName().equals("facing")) {
                state = setEnumProp(state, prop, facing.getName());
            } else if (prop.getName().equals("powered")) {
                state = setBoolProp(state, prop, true);
            }
        }
        return state;
    }

    private BlockState leafLitter(int amount, String facing) {
        Direction dir = switch (facing.toLowerCase(Locale.ROOT)) {
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.NORTH;
        };
        BlockState state = Blocks.LEAF_LITTER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir);
        for (var prop : state.getProperties()) {
            if (prop.getName().equals("segment_amount") && prop instanceof net.minecraft.world.level.block.state.properties.IntegerProperty ip) {
                int mn = ip.getPossibleValues().stream().mapToInt(v -> v).min().orElse(1);
                int mx = ip.getPossibleValues().stream().mapToInt(v -> v).max().orElse(4);
                state = state.setValue(ip, Math.max(mn, Math.min(mx, amount)));
                break;
            }
        }
        return state;
    }

    private void placeLeafNS(Object region, int cx, int cy, int cz, String d1, String d2, Set<BlockPos> centers) {
        int eA = 2; String eF = "north";
        int wA = 2; String wF = "south";
        if (pair(d1, d2, "N", "S"))     { eA=2; eF="north"; wA=2; wF="south"; }
        else if (pair(d1,d2,"N","SE"))  { eA=3; eF="south"; wA=2; wF="south"; }
        else if (pair(d1,d2,"N","SO"))  { eA=2; eF="north"; wA=3; wF="east";  }
        else if (pair(d1,d2,"S","NE"))  { eA=3; eF="west";  wA=2; wF="south"; }
        else if (pair(d1,d2,"S","NO"))  { eA=2; eF="north"; wA=3; wF="north"; }
        else if (pair(d1,d2,"NE","SO")) { eA=3; eF="west";  wA=3; wF="east";  }
        else if (pair(d1,d2,"NO","SE")) { eA=3; eF="south"; wA=3; wF="north"; }
        safeAdd(region, cx + 1, cy + 1, cz, leafLitter(eA, eF), centers);
        safeAdd(region, cx - 1, cy + 1, cz, leafLitter(wA, wF), centers);
    }

    private void placeLeafEW(Object region, int cx, int cy, int cz, String d1, String d2, Set<BlockPos> centers) {
        int nA = 2; String nF = "west";
        int sA = 2; String sF = "east";
        if (pair(d1,d2,"O","E"))        { nA=2; nF="west";  sA=2; sF="east";  }
        else if (pair(d1,d2,"E","NO"))  { nA=3; nF="south"; sA=2; sF="east";  }
        else if (pair(d1,d2,"O","NE"))  { nA=3; nF="east";  sA=2; sF="east";  }
        else if (pair(d1,d2,"O","SE"))  { nA=2; nF="west";  sA=3; sF="north"; }
        else if (pair(d1,d2,"E","SO"))  { nA=2; nF="west";  sA=3; sF="west";  }
        else if (pair(d1,d2,"NE","SO")) { nA=3; nF="east";  sA=3; sF="west";  }
        else if (pair(d1,d2,"NO","SE")) { nA=3; nF="south"; sA=3; sF="north"; }
        safeAdd(region, cx, cy + 1, cz - 1, leafLitter(nA, nF), centers);
        safeAdd(region, cx, cy + 1, cz + 1, leafLitter(sA, sF), centers);
    }

    private void placeDiagJoints(Object region, int cx, int cy, int cz, boolean axisNS, Set<BlockPos> centers) {
        int[][] offs = {{1,-1},{-1,-1},{1,1},{-1,1}};
        for (int[] o : offs) {
            int ddx = o[0], ddz = o[1];
            String face;
            if (axisNS) face = ddx > 0 ? (ddz < 0 ? "east" : "north") : (ddz < 0 ? "south" : "west");
            else face = ddz < 0 ? (ddx > 0 ? "north" : "south") : (ddx > 0 ? "east" : "west");
            int jx = axisNS ? cx : cx + ddx;
            int jz = axisNS ? cz + ddz : cz;
            if (!centers.contains(new BlockPos(jx, cy, jz))) {
                regionAddBlock(region, jx, cy, jz, Blocks.GRAVEL.defaultBlockState());
                regionAddBlock(region, jx, cy + 1, jz, leafLitter(3, face));
            }
        }
    }

    private static String neighborDir(Seg seg, int cx, int cz) {
        if (seg == null) return "";
        int dx = Integer.compare(seg.cx(), cx);
        int dz = Integer.compare(seg.cz(), cz);
        String d = "";
        if (dz == -1) d = "N"; else if (dz == 1) d = "S";
        if (dx == 1) d = d + "E"; else if (dx == -1) d = d + "O";
        return d;
    }

    private static boolean pair(String a, String b, String x, String y) {
        return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }

    private void safeAdd(Object region, int x, int y, int z, BlockState state, Set<BlockPos> centers) {
        if (!centers.contains(new BlockPos(x, y, z))) regionAddBlock(region, x, y, z, state);
    }

    private List<Pt> snapAllToGround(List<Pt> pts) {
        var world = Minecraft.getInstance().level;
        if (world == null) return pts;
        List<Pt> out = new ArrayList<>();
        for (Pt p : pts) {
            int sy = findSurface(world, p.bx(), p.by(), p.bz());
            out.add(new Pt(p.x(), sy + 0.5, p.z(), p.bx(), sy, p.bz()));
        }
        return out;
    }

    private static int findSurface(net.minecraft.client.multiplayer.ClientLevel world, int x, int startY, int z) {
        var pos = new BlockPos.MutableBlockPos(x, startY, z);
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
        List<net.minecraft.world.phys.Vec3> v = new ArrayList<>();
        for (BlockPos p : pts) v.add(net.minecraft.world.phys.Vec3.atCenterOf(p));

        List<net.minecraft.world.phys.Vec3> ext = new ArrayList<>();
        ext.add(v.get(0).add(v.get(0).subtract(v.get(1))));
        ext.addAll(v);
        ext.add(v.get(v.size()-1).add(v.get(v.size()-1).subtract(v.get(v.size()-2))));

        List<net.minecraft.world.phys.Vec3> raw = new ArrayList<>();
        for (int i = 1; i < ext.size() - 2; i++) {
            var p0 = ext.get(i-1); var p1 = ext.get(i);
            var p2 = ext.get(i+1); var p3 = ext.get(i+2);
            int steps = Math.max(1, (int) Math.ceil(p1.distanceTo(p2) * dens));
            for (int s = 0; s < steps; s++) raw.add(crEval(p0, p1, p2, p3, (double) s / steps));
        }
        raw.add(v.get(v.size()-1));

        List<Pt> out = new ArrayList<>();
        BlockPos last = null;
        for (var q : raw) {
            int bx = (int) Math.floor(q.x); int by = (int) Math.floor(q.y); int bz = (int) Math.floor(q.z);
            BlockPos bp = new BlockPos(bx, by, bz);
            if (!bp.equals(last)) { out.add(new Pt(q.x, q.y, q.z, bx, by, bz)); last = bp; }
        }
        return out;
    }

    private static net.minecraft.world.phys.Vec3 crEval(net.minecraft.world.phys.Vec3 p0, net.minecraft.world.phys.Vec3 p1, net.minecraft.world.phys.Vec3 p2, net.minecraft.world.phys.Vec3 p3, double t) {
        double t2 = t*t, t3 = t2*t;
        return new net.minecraft.world.phys.Vec3(cr(p0.x,p1.x,p2.x,p3.x,t,t2,t3), cr(p0.y,p1.y,p2.y,p3.y,t,t2,t3), cr(p0.z,p1.z,p2.z,p3.z,t,t2,t3));
    }

    private static double cr(double a, double b, double c, double d, double t, double t2, double t3) {
        return 0.5*(2*b+(-a+c)*t+(2*a-5*b+4*c-d)*t2+(-a+3*b-3*c+d)*t3);
    }
}
