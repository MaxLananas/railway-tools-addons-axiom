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
import net.minecraft.block.LecternBlock;
import net.minecraft.block.enums.Attachment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.*;

/**
 * BTE Rail Path Tool
 *
 * Traduit depuis le Lua Axiom :
 *  - Script 1 : pose du rail (red wool = axe NS, blue wool = axe EW)
 *  - Script 2 : validation du tracé (white wool → gold → air/grass)
 *
 * Structure générée par segment :
 *   Axe NS  →  lectern(facing=north) | pale_moss_carpet | gravier x±1 | leaf_litter
 *   Axe EW  →  lectern(facing=east)  | pale_moss_carpet | gravier z±1 | leaf_litter
 *   Montée  →  pale_moss_block + oak_button
 */
public class RailPathTool implements Tool {

    // =========================================================
    //  CONSTANTES DE BLOCS
    // =========================================================

    /** Segment_amount disponibles pour leaf_litter (propriété string dans les data-packs BTE) */
    private static final int LEAF_AMT_STRAIGHT  = 2;
    private static final int LEAF_AMT_DIAGONAL  = 3;

    // =========================================================
    //  ÉTAT DE L'OUTIL
    // =========================================================

    /** Points de contrôle cliqués par l'utilisateur */
    private final List<BlockPos>     points  = new ArrayList<>();
    /** Région de prévisualisation (mise à jour à chaque dirty) */
    private final ChunkedBlockRegion preview = new ChunkedBlockRegion();
    private boolean dirty = true;

    // ---- Options ImGui ----
    private final int[]     density       = {10};
    private final boolean[] placeOnGround = {false};
    private final boolean[] showPreview   = {true};

    // =========================================================
    //  MÉTADONNÉES DE L'OUTIL
    // =========================================================

    @Override public String name()        { return "BTE Rail Path"; }
    @Override public char   iconChar()    { return '\ue912'; }
    @Override public String keybindId()   { return "bte_rail_path"; }
    @Override public int defaultKeybind() { return 0; }

    @Override
    public EnumSet<AxiomPermission> requiredPermissions() {
        return EnumSet.of(AxiomPermission.TOOL, AxiomPermission.BUILD_SECTION);
    }

    // =========================================================
    //  PERSISTANCE DES PARAMÈTRES
    // =========================================================

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

    // =========================================================
    //  RENDU
    // =========================================================

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

        if (points.size() >= 2) drawControlLines(matrices, camera);

        Tool.renderRaycastOverlay(
                Tool.raycastBlock(false, true, Tool.defaultIncludeFluids()),
                matrices, camera);
    }

    private void drawControlLines(MatrixStack matrices, Camera camera) {
        var vcp = VertexConsumerProvider.shared();
        matrices.push();
        matrices.translate(-camera.getPos().x, -camera.getPos().y, -camera.getPos().z);

        var buf  = vcp.begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR_NORMAL);
        var pose = matrices.peek();
        for (int i = 0; i < points.size() - 1; i++) {
            Shapes.line(buf, pose,
                    Vec3d.ofCenter(points.get(i)),
                    Vec3d.ofCenter(points.get(i + 1)));
        }
        var mesh = vcp.build();

        AxiomRenderer.setShaderColour(1f, 0.45f, 0.1f, 1f);
        AxiomRenderer.renderPipeline(AxiomRenderPipelines.LINES_WITHOUT_WRITE_DEPTH, null, mesh, false);
        AxiomRenderer.setShaderColour(1f, 0.45f, 0.1f, 0.3f);
        AxiomRenderer.renderPipeline(AxiomRenderPipelines.LINES_IGNORE_DEPTH, null, mesh, true);
        AxiomRenderer.setShaderColour(1f, 1f, 1f, 1f);

        matrices.pop();
    }

    // =========================================================
    //  INTERFACE ImGui
    // =========================================================

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
        if (ImGui.checkbox("Aperçu", showPreview[0]))
            showPreview[0] = !showPreview[0];

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
        ImGui.textColored(0xFFAAAAAA, "Clic droit: point | Entrée: valider | Suppr: annuler");
    }

    // =========================================================
    //  ACTIONS CLAVIER / SOURIS
    // =========================================================

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

    // =========================================================
    //  VALIDATION
    // =========================================================

    private void confirm() {
        var r = new ChunkedBlockRegion();
        buildRail(r);
        RegionHelper.pushBlockRegionChange(r, "BTE Rail Path");
        reset();
    }

    // =========================================================
    //  STRUCTURE DE DONNÉES INTERNES
    // =========================================================

    /**
     * Un point interpolé sur la spline.
     *
     * @param x  coordonnée monde réelle X
     * @param y  coordonnée monde réelle Y
     * @param z  coordonnée monde réelle Z
     * @param bx bloc X
     * @param by bloc Y
     * @param bz bloc Z
     */
    private record Pt(double x, double y, double z, int bx, int by, int bz) {}

    /**
     * Un segment = un bloc-centre + direction d'entrée (dx, dz ∈ {-1,0,+1}).
     *
     * @param cx  centre X
     * @param cy  centre Y
     * @param cz  centre Z
     * @param dx  delta X depuis le segment précédent
     * @param dz  delta Z depuis le segment précédent
     */
    private record Seg(int cx, int cy, int cz, int dx, int dz) {}

    // =========================================================
    //  CONSTRUCTION DU RAIL  (cœur de la logique Lua)
    // =========================================================

    private void buildRail(ChunkedBlockRegion region) {
        List<Pt> sp = catmullRom(points, density[0]);
        if (sp.size() < 2) return;
        if (placeOnGround[0]) sp = snapToGround(sp);

        List<Seg> segs = buildSegments(sp);
        if (segs.isEmpty()) return;

        // Ensemble des positions-centre pour éviter d'écraser la voie centrale
        Set<BlockPos> centers = new HashSet<>();
        for (Seg s : segs) centers.add(new BlockPos(s.cx(), s.cy(), s.cz()));

        for (int i = 0; i < segs.size(); i++) {
            Seg prv = i > 0             ? segs.get(i - 1) : null;
            Seg cur =                     segs.get(i);
            Seg nxt = i < segs.size()-1 ? segs.get(i + 1) : null;
            placeRailSegment(region, prv, cur, nxt, segs, i, centers);
        }
    }

    // ---- Construction de la liste de segments depuis la spline ----

    private List<Seg> buildSegments(List<Pt> sp) {
        List<Seg> out  = new ArrayList<>();
        BlockPos  last = null;

        for (Pt p : sp) {
            BlockPos bp = new BlockPos(p.bx(), p.by(), p.bz());
            if (bp.equals(last)) continue;

            int dx = 0, dz = 0;
            if (last != null) {
                dx = clamp(bp.getX() - last.getX());
                dz = clamp(bp.getZ() - last.getZ());
            }
            out.add(new Seg(bp.getX(), bp.getY(), bp.getZ(), dx, dz));
            last = bp;
        }
        return out;
    }

    private static int clamp(int v) {
        return v == 0 ? 0 : (v > 0 ? 1 : -1);
    }

    // =========================================================
    //  PLACEMENT D'UN SEGMENT  (traduction directe du Lua)
    // =========================================================

    /**
     * Traduit la logique des scripts Lua (red_wool / blue_wool) en blocs Java.
     *
     * Le Lua utilise l'axe de la laine pour choisir :
     *  - lectern ou moss_block (centre de voie)
     *  - pale_moss_carpet ou oak_button (dessus)
     *  - gravier (rails latéraux)
     *  - leaf_litter (décorations de courbure)
     *
     * Ici on détermine l'axe à partir de la tangente de la spline.
     */
    private void placeRailSegment(ChunkedBlockRegion region,
                                   Seg prv, Seg cur, Seg nxt,
                                   List<Seg> segs, int idx,
                                   Set<BlockPos> centers) {

        int cx = cur.cx(), cy = cur.cy(), cz = cur.cz();

        // --- Tangente locale (vecteur somme des deltas voisins) ---
        int tx = 0, tz = 0;
        if (prv != null) { tx += cur.cx() - prv.cx(); tz += cur.cz() - prv.cz(); }
        if (nxt != null) { tx += nxt.cx() - cur.cx(); tz += nxt.cz() - cur.cz(); }
        if (tx == 0 && tz == 0) { tx = cur.dx(); tz = cur.dz(); }

        // --- Axe principal : NS (|tz|>=|tx|) ou EW (|tx|>|tz|) ---
        boolean axisNS = Math.abs(tz) >= Math.abs(tx);

        // --- Détection de montée (is_moss dans le Lua) ---
        boolean isMoss = detectHeightChange(segs, idx, axisNS);

        // --- Bloc de centre (y) ---
        if (!isMoss) {
            Direction facing = axisNS ? Direction.NORTH : Direction.EAST;
            region.addBlock(cx, cy, cz, makeLectern(facing));
            region.addBlock(cx, cy + 1, cz, makePaleMossCarpet());
        } else {
            region.addBlock(cx, cy, cz, Blocks.PALE_MOSS_BLOCK.getDefaultState());
            Direction btnFacing = axisNS ? Direction.NORTH : Direction.EAST;
            region.addBlock(cx, cy + 1, cz, makeOakButton(btnFacing));
        }

        // --- Gravier (rails) ---
        if (axisNS) {
            // Axe NS → gravier à x±1
            region.addBlock(cx + 1, cy, cz, Blocks.GRAVEL.getDefaultState());
            region.addBlock(cx - 1, cy, cz, Blocks.GRAVEL.getDefaultState());
        } else {
            // Axe EW → gravier à z±1
            region.addBlock(cx, cy, cz + 1, Blocks.GRAVEL.getDefaultState());
            region.addBlock(cx, cy, cz - 1, Blocks.GRAVEL.getDefaultState());
        }

        // --- Leaf litter (décorations de courbure) ---
        if (axisNS) {
            placeLeafLitterNS(region, cx, cy, cz, prv, nxt, centers);
        } else {
            placeLeafLitterEW(region, cx, cy, cz, prv, nxt, centers);
        }

        // --- Intersections diagonales (traduction du bloc rouge/bleu du Lua) ---
        if (axisNS) {
            placeDiagonalIntersectionsNS(region, cx, cy, cz, centers);
        } else {
            placeDiagonalIntersectionsEW(region, cx, cy, cz, centers);
        }
    }

    // =========================================================
    //  DÉTECTION DE MONTÉE  (is_moss dans le Lua)
    // =========================================================

    /**
     * Vérifie si un segment voisin est à y-1 dans la direction de l'axe courant.
     * Cela correspond aux conditions is_moss du Lua (laine au niveau y-1 dans
     * la direction de déplacement ou en diagonale).
     */
    private boolean detectHeightChange(List<Seg> segs, int idx, boolean axisNS) {
        // On cherche si l'un des voisins immédiats est en dessous du segment courant
        Seg cur = segs.get(idx);
        if (axisNS) {
            // Lua vérifie : red_wool à (x, y-1, z±1) ou (x±1, y-1, z±1)
            for (int di = -1; di <= 1; di += 2) {
                int ni = idx + di;
                if (ni >= 0 && ni < segs.size()) {
                    Seg n = segs.get(ni);
                    if (n.cy() == cur.cy() - 1) return true;
                }
            }
        } else {
            // Lua vérifie : blue_wool à (x±1, y-1, z) ou (x±1, y-1, z±1)
            for (int di = -1; di <= 1; di += 2) {
                int ni = idx + di;
                if (ni >= 0 && ni < segs.size()) {
                    Seg n = segs.get(ni);
                    if (n.cy() == cur.cy() - 1) return true;
                }
            }
        }
        return false;
    }

    // =========================================================
    //  LEAF LITTER – AXE NORD-SUD  (red wool dans le Lua)
    // =========================================================

    /**
     * Place les leaf_litter à x+1 et x-1 selon la courbure.
     *
     * Traduction de la table Lua :
     * <pre>
     *  (N,S)          → E:2/north  O:2/south
     *  (N,SE) ou sym  → E:3/south  O:2/south
     *  (N,SO) ou sym  → E:2/north  O:3/east
     *  (S,NE) ou sym  → E:3/west   O:2/south
     *  (S,NO) ou sym  → E:2/north  O:3/north
     *  (NE,SO)        → E:3/west   O:3/east
     *  (NO,SE)        → E:3/south  O:3/north
     * </pre>
     */
    private void placeLeafLitterNS(ChunkedBlockRegion region,
                                    int cx, int cy, int cz,
                                    Seg prv, Seg nxt,
                                    Set<BlockPos> centers) {

        String d1 = dirName(prv, cx, cy, cz);
        String d2 = dirName(nxt, cx, cy, cz);

        // Valeurs par défaut
        int   eAmt = LEAF_AMT_STRAIGHT; String eFace = "north";
        int   oAmt = LEAF_AMT_STRAIGHT; String oFace = "south";

        if (match(d1,d2,"N","S"))   { eAmt=2; eFace="north"; oAmt=2; oFace="south"; }
        else if (match(d1,d2,"N","SE")) { eAmt=3; eFace="south"; oAmt=2; oFace="south"; }
        else if (match(d1,d2,"N","SO")) { eAmt=2; eFace="north"; oAmt=3; oFace="east";  }
        else if (match(d1,d2,"S","NE")) { eAmt=3; eFace="west";  oAmt=2; oFace="south"; }
        else if (match(d1,d2,"S","NO")) { eAmt=2; eFace="north"; oAmt=3; oFace="north"; }
        else if (match(d1,d2,"NE","SO")){ eAmt=3; eFace="west";  oAmt=3; oFace="east";  }
        else if (match(d1,d2,"NO","SE")){ eAmt=3; eFace="south"; oAmt=3; oFace="north"; }

        safePlace(region, cx + 1, cy + 1, cz, makeLeafLitter(eAmt, eFace), centers);
        safePlace(region, cx - 1, cy + 1, cz, makeLeafLitter(oAmt, oFace), centers);
    }

    // =========================================================
    //  LEAF LITTER – AXE EST-OUEST  (blue wool dans le Lua)
    // =========================================================

    /**
     * Place les leaf_litter à z-1 (nord) et z+1 (sud) selon la courbure.
     *
     * Traduction de la table Lua :
     * <pre>
     *  (O,E)          → N:2/west   S:2/east
     *  (E,NO) ou sym  → N:3/south  S:2/east
     *  (O,NE) ou sym  → N:3/east   S:2/east
     *  (O,SE) ou sym  → N:2/west   S:3/north
     *  (E,SO) ou sym  → N:2/west   S:3/west
     *  (NE,SO)        → N:3/east   S:3/west
     *  (NO,SE)        → N:3/south  S:3/north
     * </pre>
     */
    private void placeLeafLitterEW(ChunkedBlockRegion region,
                                    int cx, int cy, int cz,
                                    Seg prv, Seg nxt,
                                    Set<BlockPos> centers) {

        String d1 = dirName(prv, cx, cy, cz);
        String d2 = dirName(nxt, cx, cy, cz);

        int   nAmt = LEAF_AMT_STRAIGHT; String nFace = "west";
        int   sAmt = LEAF_AMT_STRAIGHT; String sFace = "east";

        if (match(d1,d2,"O","E"))     { nAmt=2; nFace="west";  sAmt=2; sFace="east";  }
        else if (match(d1,d2,"E","NO")) { nAmt=3; nFace="south"; sAmt=2; sFace="east";  }
        else if (match(d1,d2,"O","NE")) { nAmt=3; nFace="east";  sAmt=2; sFace="east";  }
        else if (match(d1,d2,"O","SE")) { nAmt=2; nFace="west";  sAmt=3; sFace="north"; }
        else if (match(d1,d2,"E","SO")) { nAmt=2; nFace="west";  sAmt=3; sFace="west";  }
        else if (match(d1,d2,"NE","SO")){ nAmt=3; nFace="east";  sAmt=3; sFace="west";  }
        else if (match(d1,d2,"NO","SE")){ nAmt=3; nFace="south"; sAmt=3; sFace="north"; }

        safePlace(region, cx, cy + 1, cz - 1, makeLeafLitter(nAmt, nFace), centers);
        safePlace(region, cx, cy + 1, cz + 1, makeLeafLitter(sAmt, sFace), centers);
    }

    // =========================================================
    //  INTERSECTIONS DIAGONALES  (cases if/else du Lua red wool)
    // =========================================================

    /**
     * Traduit exactement les 12 cas d'intersection du script Lua (axe NS).
     *
     * Dans le Lua original, on teste si un bloc de laine bleue est présent
     * à (x±1, y+dy, z±1). Ici, comme la génération est algorithmique, on
     * ne peut pas tester des blocs existants : on place les blocs de jonction
     * systématiquement aux quatre coins diagonaux autour du centre.
     *
     * Les blocs posés correspondent au pattern Lua :
     *   gravel  à (x, y+dy, z±1)
     *   leaf    à (x, y+dy+1, z±1) avec facing selon la direction x (±1)
     */
    private void placeDiagonalIntersectionsNS(ChunkedBlockRegion region,
                                               int cx, int cy, int cz,
                                               Set<BlockPos> centers) {
        // Coins NE, NO, SE, SO
        int[][] corners = {{1,0,-1},{-1,0,-1},{1,0,1},{-1,0,1}};

        for (int[] c : corners) {
            int ddx = c[0]; // direction x du coin (±1)
            int ddy = c[1]; // décalage y (0 pour l'instant, géré par la spline)
            int ddz = c[2]; // direction z du coin (±1)

            // facing leaf : selon que le coin vient de x+ ou x-
            String leafFacing = ddx > 0
                    ? (ddz < 0 ? "east"  : "north") // NE → east, SE → north
                    : (ddz < 0 ? "south" : "west");  // NO → south, SO → west

            // Position cible (entre le centre et le coin)
            int tx = cx;           // x reste centré (axe NS)
            int ty = cy + ddy;
            int tz = cz + ddz;

            // On évite d'écraser un centre de voie
            if (!centers.contains(new BlockPos(tx, ty, tz))) {
                region.addBlock(tx, ty,     tz, Blocks.GRAVEL.getDefaultState());
                region.addBlock(tx, ty + 1, tz, makeLeafLitter(LEAF_AMT_DIAGONAL, leafFacing));
            }
        }
    }

    /**
     * Traduit exactement les 12 cas d'intersection du script Lua (axe EW).
     * Symétrique à placeDiagonalIntersectionsNS, avec les axes inversés.
     */
    private void placeDiagonalIntersectionsEW(ChunkedBlockRegion region,
                                               int cx, int cy, int cz,
                                               Set<BlockPos> centers) {
        int[][] corners = {{1,0,-1},{-1,0,-1},{1,0,1},{-1,0,1}};

        for (int[] c : corners) {
            int ddx = c[0];
            int ddy = c[1];
            int ddz = c[2];

            String leafFacing = ddz < 0
                    ? (ddx > 0 ? "north" : "south") // z- : NE→north, NO→south
                    : (ddx > 0 ? "east"  : "west");  // z+ : SE→east,  SO→west

            int tx = cx + ddx;
            int ty = cy + ddy;
            int tz = cz;  // z reste centré (axe EW)

            if (!centers.contains(new BlockPos(tx, ty, tz))) {
                region.addBlock(tx, ty,     tz, Blocks.GRAVEL.getDefaultState());
                region.addBlock(tx, ty + 1, tz, makeLeafLitter(LEAF_AMT_DIAGONAL, leafFacing));
            }
        }
    }

    // =========================================================
    //  UTILITAIRES DE DIRECTION  (get_dir_name du Lua)
    // =========================================================

    /**
     * Calcule le nom de direction (N, S, E, O, NE, NO, SE, SO, "")
     * d'un segment voisin par rapport au segment courant.
     * Correspond à la fonction get_dir_name + boucle neighbors du Lua.
     */
    private static String dirName(Seg neighbor, int cx, int cy, int cz) {
        if (neighbor == null) return "";
        int dx = clamp(neighbor.cx() - cx);
        int dz = clamp(neighbor.cz() - cz);
        String d = "";
        if (dz == -1) d = "N"; else if (dz == 1) d = "S";
        if (dx ==  1) d = d + "E"; else if (dx == -1) d = d + "O";
        return d;
    }

    /**
     * Vérifie si {d1,d2} == {a,b} (dans n'importe quel ordre).
     */
    private static boolean match(String d1, String d2, String a, String b) {
        return (d1.equals(a) && d2.equals(b)) || (d1.equals(b) && d2.equals(a));
    }

    // =========================================================
    //  FABRIQUES DE BLOCKSTATE
    // =========================================================

    /** Lectern orienté dans la direction donnée. */
    private static BlockState makeLectern(Direction facing) {
        return Blocks.LECTERN.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, facing)
                .with(LecternBlock.HAS_BOOK, false);
    }

    /** pale_moss_carpet (bloc simple, pas de propriété directionnelle). */
    private static BlockState makePaleMossCarpet() {
        return Blocks.PALE_MOSS_CARPET.getDefaultState();
    }

    /**
     * oak_button posé sur le sol, orienté, activé.
     * Propriétés : face=floor, facing=<dir>, powered=true
     */
    private static BlockState makeOakButton(Direction facing) {
        return Blocks.OAK_BUTTON.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, facing)
                .with(Properties.ATTACHMENT_TYPE, Attachment.FLOOR)
                .with(Properties.POWERED, true);
    }

    /**
     * pale_moss_block (bloc massif, aucune propriété).
     */
    private static BlockState makePaleMossBlock() {
        return Blocks.PALE_MOSS_BLOCK.getDefaultState();
    }

    /**
     * Leaf litter avec segment_amount et facing.
     *
     * Note : leaf_litter (ajout 1.21.4) possède les propriétés
     * "segment_amount" (1-4) et "facing" (north/south/east/west).
     * L'API Yarn expose ces propriétés via Properties.SEGMENT_AMOUNT
     * et Properties.HORIZONTAL_FACING.
     */
    private static BlockState makeLeafLitter(int amount, String facing) {
        Direction dir = switch (facing.toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east"  -> Direction.EAST;
            case "west"  -> Direction.WEST;
            default      -> Direction.NORTH;
        };

        var state = Blocks.LEAF_LITTER.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, dir);

        // Properties.SEGMENT_AMOUNT : IntProperty (1-4) ajouté en 1.21.4
        // On l'applique seulement si la propriété existe pour rester compatible.
        try {
            var prop = net.minecraft.state.property.IntProperty.of("segment_amount", 1, 4);
            // Chercher la propriété réelle dans le bloc
            for (var p : state.getProperties()) {
                if (p.getName().equals("segment_amount") &&
                        p instanceof net.minecraft.state.property.IntProperty ip) {
                    int clamped = Math.max(1, Math.min(4, amount));
                    state = state.with(ip, clamped);
                    break;
                }
            }
        } catch (Exception ignored) {}

        return state;
    }

    // =========================================================
    //  PLACEMENT SÉCURISÉ
    // =========================================================

    /** Place un bloc seulement si la position n'est pas un centre de voie. */
    private static void safePlace(ChunkedBlockRegion region,
                                   int x, int y, int z,
                                   BlockState state,
                                   Set<BlockPos> centers) {
        if (!centers.contains(new BlockPos(x, y, z)))
            region.addBlock(x, y, z, state);
    }

    // =========================================================
    //  SNAP TO GROUND
    // =========================================================

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

    private static int findSurface(ClientWorld world, int x, int startY, int z) {
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

    // =========================================================
    //  SPLINE DE CATMULL-ROM
    // =========================================================

    private List<Pt> catmullRom(List<BlockPos> pts, int dens) {
        List<Vec3d> v = new ArrayList<>();
        for (BlockPos p : pts) v.add(Vec3d.ofCenter(p));

        // Points fantômes aux extrémités
        List<Vec3d> e = new ArrayList<>();
        e.add(v.get(0).add(v.get(0).subtract(v.get(1))));
        e.addAll(v);
        e.add(v.getLast().add(v.getLast().subtract(v.get(v.size() - 2))));

        List<Vec3d> raw = new ArrayList<>();
        for (int i = 1; i < e.size() - 2; i++) {
            Vec3d p0 = e.get(i - 1), p1 = e.get(i),
                  p2 = e.get(i + 1), p3 = e.get(i + 2);
            int steps = Math.max(1, (int) Math.ceil(p1.distanceTo(p2) * dens));
            for (int s = 0; s < steps; s++)
                raw.add(crPoint(p0, p1, p2, p3, (double) s / steps));
        }
        raw.add(v.getLast());

        // Dédoublonnage
        List<Pt>  out  = new ArrayList<>();
        BlockPos  last = null;
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
        double t2 = t * t, t3 = t2 * t;
        return new Vec3d(
                crComp(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                crComp(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                crComp(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
    }

    private static double crComp(double a, double b, double c, double d,
                                  double t, double t2, double t3) {
        return 0.5 * ((2 * b) + (-a + c) * t
                + (2 * a - 5 * b + 4 * c - d) * t2
                + (-a + 3 * b - 3 * c + d) * t3);
    }

    // Import manquant pour Locale (à placer en haut du fichier)
    private static final java.util.Locale Locale = java.util.Locale.ROOT;
}
