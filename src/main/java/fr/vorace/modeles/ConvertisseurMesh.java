package fr.vorace.modeles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maillages de Blockbench (meshes) : les modèles de Minecraft n'acceptent que des pavés, donc chaque face plate et
 * rectangulaire d'un maillage devient un élément plat (épaisseur nulle, texturé des deux côtés), tourné comme la face.
 * Les triangles et les faces qui ne sont pas des rectangles plats ne peuvent pas être affichés : ils sont comptés et
 * signalés.
 *
 * <p>Les éléments produits ont la forme des cubes d'un .bbmodel (from, to, origin, rotation, faces) pour passer par
 * le même chemin que les cubes. Rotation à la manière de Blockbench : Z puis Y puis X, en degrés.
 */
final class ConvertisseurMesh {

    /** Ce que donne un maillage : les éléments plats et le nombre de faces qui n'ont pas pu être converties. */
    record Resultat(List<JsonObject> cubes, int facesIgnorees, int facesTotales) {}

    private ConvertisseurMesh() { }

    static Resultat convertir(JsonObject mesh) {
        double[] origine = vecteur(mesh, "origin");
        double[][] rotation = matrice(mesh.has("rotation") ? vecteur(mesh, "rotation") : new double[3]);
        JsonObject sommets = mesh.has("vertices") && mesh.get("vertices").isJsonObject() ? mesh.getAsJsonObject("vertices") : new JsonObject();
        JsonObject faces = mesh.has("faces") && mesh.get("faces").isJsonObject() ? mesh.getAsJsonObject("faces") : new JsonObject();
        String nom = mesh.has("name") ? mesh.get("name").getAsString() : "mesh";
        List<JsonObject> cubes = new ArrayList<>();
        int ignorees = 0, total = 0;
        for (Map.Entry<String, JsonElement> e : faces.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject f = e.getValue().getAsJsonObject();
            if (!f.has("texture") || f.get("texture").isJsonNull()) continue; // face sans texture : invisible dans Blockbench aussi
            total++;
            JsonObject cube = face(f, sommets, origine, rotation, nom + "_" + cubes.size());
            if (cube == null) ignorees++;
            else cubes.add(cube);
        }
        return new Resultat(cubes, ignorees, total);
    }

    /** Une face de maillage en élément plat, ou null si ce n'est pas un rectangle plat avec une texture droite. */
    private static JsonObject face(JsonObject f, JsonObject sommets, double[] origine, double[][] rot, String nom) {
        JsonArray ids = f.has("vertices") && f.get("vertices").isJsonArray() ? f.getAsJsonArray("vertices") : null;
        JsonObject uvs = f.has("uv") && f.get("uv").isJsonObject() ? f.getAsJsonObject("uv") : null;
        if (ids == null || ids.size() != 4 || uvs == null) return null;
        double[][] p = new double[4][];
        double[][] t = new double[4][];
        for (int i = 0; i < 4; i++) {
            String id = ids.get(i).getAsString();
            if (!sommets.has(id) || !uvs.has(id)) return null;
            double[] v = tableau(sommets.getAsJsonArray(id), 3);
            p[i] = plus(origine, fois(rot, v));
            t[i] = tableau(uvs.getAsJsonArray(id), 2);
        }
        // Blockbench ne range pas les sommets d'une face dans l'ordre du tour : le plus loin du premier est l'opposé
        int opp = 1;
        for (int i = 2; i < 4; i++) if (dist(p[0], p[i]) > dist(p[0], p[opp])) opp = i;
        int a = -1, b = -1;
        for (int i = 1; i < 4; i++) if (i != opp) { if (a < 0) a = i; else b = i; }
        double[] e1 = moins(p[a], p[0]), e2 = moins(p[b], p[0]);
        double l1 = norme(e1), l2 = norme(e2);
        if (l1 < 1e-6 || l2 < 1e-6) return null;
        double eps = 1e-3 * Math.max(1, Math.max(l1, l2));
        // rectangle plat : angle droit et quatrième sommet à sa place
        if (Math.abs(scalaire(e1, e2)) > eps * Math.max(l1, l2)) return null;
        if (dist(p[opp], plus(p[0], plus(e1, e2))) > eps) return null;
        double[] d1 = moins(t[a], t[0]), d2 = moins(t[b], t[0]);
        if (dist(t[opp], plus(t[0], plus(d1, d2))) > 1e-3 * Math.max(1, norme(d1) + norme(d2))) return null;
        // texture droite : u ne change que le long d'un côté, v le long de l'autre
        double[] ex, ey, tx, ty;
        if (Math.abs(d1[1]) < 1e-4 && Math.abs(d2[0]) < 1e-4) { ex = e1; ey = e2; tx = d1; ty = d2; }
        else if (Math.abs(d1[0]) < 1e-4 && Math.abs(d2[1]) < 1e-4) { ex = e2; ey = e1; tx = d2; ty = d1; }
        else return null;
        double lx = norme(ex), ly = norme(ey);
        double[] x = div(ex, lx), y = div(ey, ly), z = vectoriel(x, y);
        double[] centre = plus(p[0], fois(0.5, plus(ex, ey)));

        int texture = f.get("texture").getAsInt();
        double u0 = t[0][0], u1 = t[0][0] + tx[0];
        double vBas = t[0][1], vHaut = t[0][1] + ty[1];
        JsonObject faces = new JsonObject();
        faces.add("south", faceCube(new double[]{u0, vHaut, u1, vBas}, texture));
        faces.add("north", faceCube(new double[]{u1, vHaut, u0, vBas}, texture));

        JsonObject c = new JsonObject();
        c.addProperty("name", nom);
        c.addProperty("type", "cube");
        c.add("from", json(centre[0] - lx / 2, centre[1] - ly / 2, centre[2]));
        c.add("to", json(centre[0] + lx / 2, centre[1] + ly / 2, centre[2]));
        c.add("origin", json(centre[0], centre[1], centre[2]));
        double[] euler = euler(new double[][]{{x[0], y[0], z[0]}, {x[1], y[1], z[1]}, {x[2], y[2], z[2]}});
        c.add("rotation", json(arrondi(euler[0]), arrondi(euler[1]), arrondi(euler[2])));
        c.add("faces", faces);
        return c;
    }

    private static JsonObject faceCube(double[] uv, int texture) {
        JsonObject f = new JsonObject();
        JsonArray a = new JsonArray();
        for (double d : uv) a.add(d);
        f.add("uv", a);
        f.addProperty("texture", texture);
        return f;
    }

    // ------------------------------------------------------------------ rotations (Z puis Y puis X, en degrés)

    /** Matrice R = Rz · Ry · Rx, comme Blockbench et l'animateur. */
    static double[][] matrice(double[] deg) {
        double rx = Math.toRadians(deg[0]), ry = Math.toRadians(deg[1]), rz = Math.toRadians(deg[2]);
        double cx = Math.cos(rx), sx = Math.sin(rx), cy = Math.cos(ry), sy = Math.sin(ry), cz = Math.cos(rz), sz = Math.sin(rz);
        return new double[][]{
            {cy * cz, sx * sy * cz - cx * sz, cx * sy * cz + sx * sz},
            {cy * sz, sx * sy * sz + cx * cz, cx * sy * sz - sx * cz},
            {-sy, sx * cy, cx * cy}};
    }

    /** Angles (x, y, z) en degrés d'une matrice de rotation, pour R = Rz · Ry · Rx. */
    static double[] euler(double[][] m) {
        double ry = Math.asin(Math.max(-1, Math.min(1, -m[2][0])));
        double rx, rz;
        if (Math.abs(m[2][0]) < 1 - 1e-9) {
            rx = Math.atan2(m[2][1], m[2][2]);
            rz = Math.atan2(m[1][0], m[0][0]);
        } else {
            rx = 0;
            rz = Math.atan2(-m[0][1], m[1][1]);
        }
        return new double[]{Math.toDegrees(rx), Math.toDegrees(ry), Math.toDegrees(rz)};
    }

    /** -0 et les poussières de calcul ramenés à des nombres propres (une face droite reste sans rotation). */
    private static double arrondi(double d) {
        double r = Math.round(d * 1e4) / 1e4;
        return r == 0 ? 0 : r;
    }

    // ------------------------------------------------------------------ vecteurs

    private static double[] fois(double[][] m, double[] v) {
        return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2], m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    private static double[] fois(double k, double[] v) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) r[i] = k * v[i];
        return r;
    }

    private static double[] plus(double[] a, double[] b) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] + b[i];
        return r;
    }

    private static double[] moins(double[] a, double[] b) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] - b[i];
        return r;
    }

    private static double[] div(double[] a, double k) { return fois(1 / k, a); }

    private static double scalaire(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double norme(double[] a) { return Math.sqrt(scalaire(a, a)); }

    private static double dist(double[] a, double[] b) { return norme(moins(a, b)); }

    private static double[] vectoriel(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] vecteur(JsonObject o, String cle) {
        return o.has(cle) && o.get(cle).isJsonArray() ? tableau(o.getAsJsonArray(cle), 3) : new double[3];
    }

    private static double[] tableau(JsonArray a, int n) {
        double[] v = new double[n];
        for (int i = 0; i < n && i < a.size(); i++) v[i] = a.get(i).getAsDouble();
        return v;
    }

    private static JsonArray json(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }
}
