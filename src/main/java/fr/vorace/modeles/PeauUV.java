package fr.vorace.modeles;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.vorace.objets.GenerateurPack;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Skin du joueur dessiné par des modèles d'objet colorés pixel par pixel (sans Bukkit, sans MineSkin).
 *
 * <p>Même technique que BetterModel (bibliothèque DynamicUV) : pour chaque morceau du pantin, le pack contient un modèle
 * fait d'un petit carré par pixel de chaque face (couche de base), et un carré par pixel de la seconde couche (veste,
 * manches, chapeau), affiché seulement si son drapeau est vrai. Chaque carré prend sa couleur dans le composant
 * custom_model_data de l'objet affiché (liste « colors »), que le serveur remplit avec les pixels du skin du joueur. Le
 * jeu dessine ces modèles comme des objets ordinaires, faces arrière élaguées : la caméra à la première personne, qui
 * est dans la tête, ne voit rien de l'intérieur ; la tête reste donc visible pour tout le monde, joueur compris.
 */
public final class PeauUV {

    private PeauUV() {}

    /** Seconde couche : 0,5 pixel autour de la tête, 0,25 autour du reste (comme le jeu). */
    static final float CHAPEAU = 0.5f, VESTE = 0.25f;

    /** Une face d'un morceau : pixels du skin (x0, y0, largeur, hauteur) et sens de lecture sur le cube. */
    record Face(String nom, int x0, int y0, int l, int h) {}

    /** Un morceau du pantin : os, cube (repère du pivot, + 8), zones de la couche de base et de la seconde couche. */
    record Morceau(String nom, String os, double[] de, double[] a, List<Face> base, List<Face> couche, float gonfle) {}

    /** Les faces d'une zone de skin (u, v, l, h, p), lignes r0 à r0 + rh pour les côtés. */
    static List<Face> faces(int u, int v, int l, int p, int r0, int rh, boolean dessus, boolean dessous) {
        List<Face> f = new ArrayList<>();
        f.add(new Face("north", u + p, v + p + r0, l, rh));
        f.add(new Face("south", u + 2 * p + l, v + p + r0, l, rh));
        f.add(new Face("east", u, v + p + r0, p, rh));
        f.add(new Face("west", u + p + l, v + p + r0, p, rh));
        if (dessus) f.add(new Face("up", u + p, v, l, p));
        if (dessous) f.add(new Face("down", u + p + l, v, l, p));
        return f;
    }

    /**
     * Bouchon d'une articulation (genou, coude) : la face qui ferme le bout d'une moitié de membre du côté de la jointure
     * (le dessous d'une cuisse ou d'un bras, le dessus d'un tibia ou d'un avant-bras). Le skin n'a pas de pixels pour elle :
     * chaque colonne prend la couleur du pixel de la face avant qui la touche, étirée sur toute la profondeur. Sans elle,
     * les deux moitiés étaient des tubes ouverts : quand le genou pliait, le haut du tibia sortait devant la cuisse et l'on
     * voyait au travers (retour d'Antoine : « quand je marche, mon genou disparaît »).
     */
    static Face bouchon(int[] z, int r0, int rh, boolean bas) {
        int u = z[0], v = z[1], l = z[2], p = z[3];
        return bas ? new Face("up", u + p, v + p + r0, l, 1) : new Face("down", u + p, v + p + r0 + rh - 1, l, 1);
    }

    /** Morceaux d'après le squelette (os « peau_... ») ; bras fins : 3 pixels de large, côté extérieur retiré. */
    public static List<Morceau> morceaux(ModeleBB squelette, boolean fin) {
        List<Morceau> l = new ArrayList<>();
        if (squelette == null) return l;
        for (ModeleBB.Partie p : squelette.parties()) {
            String n = p.nom() == null ? "" : p.nom().toLowerCase(Locale.ROOT);
            if (!n.startsWith("peau_") || p.cubes().isEmpty()) continue;
            String nom = n.substring(5);
            ModeleBB.Cube c = p.cubes().get(0);
            double[] de = new double[3], a = new double[3];
            for (int i = 0; i < 3; i++) {
                de[i] = c.de()[i] - p.pivot()[i] + 8;
                a[i] = c.a()[i] - p.pivot()[i] + 8;
            }
            boolean bras = nom.startsWith("bras");
            if (fin && bras) {
                // bras fin : l'os garde sa place, le cube perd son pixel extérieur (droite : +x, gauche : -x)
                if (nom.contains("droit")) a[0] -= (a[0] - de[0]) / 4; else de[0] += (a[0] - de[0]) / 4;
            } else if (fin) {
                continue;   // seuls les bras changent en modèle fin
            }
            int[] zb = zoneBase(nom, fin), zc = zoneCouche(nom, fin);
            boolean tete = nom.equals("tete"), bas = nom.endsWith("_bas");
            int h = tete ? 8 : 12;
            int r0 = tete ? 0 : bas ? h / 2 - Peau.recouvrement(nom) : 0;
            int rh = tete ? 8 : bas ? h / 2 + Peau.recouvrement(nom) : h / 2;
            // le bas du torse a un dessus : si le buste se penche, on voit le haut d'un torse, pas le vide
            boolean dessus = tete || !bas || nom.equals("corps_bas"), dessous = tete || bas;
            List<Face> fb = faces(zb[0], zb[1], zb[2], zb[3], r0, rh, dessus, dessous);
            List<Face> fc = faces(zc[0], zc[1], zc[2], zc[3], r0, rh, dessus, dessous);
            if (bras || nom.startsWith("jambe")) {
                fb.add(bouchon(zb, r0, rh, bas));
                fc.add(bouchon(zc, r0, rh, bas));
            }
            l.add(new Morceau(nom + (fin ? "_fin" : ""), p.nom(), de, a, fb, fc, tete ? CHAPEAU : VESTE));
        }
        return l;
    }

    /** u, v, largeur, épaisseur de la zone de base. */
    private static int[] zoneBase(String nom, boolean fin) {
        int b = fin ? 3 : 4;
        if (nom.equals("tete")) return new int[]{0, 0, 8, 8};
        if (nom.startsWith("corps")) return new int[]{16, 16, 8, 4};
        if (nom.startsWith("bras_droit")) return new int[]{40, 16, b, 4};
        if (nom.startsWith("bras_gauche")) return new int[]{32, 48, b, 4};
        if (nom.startsWith("jambe_droite")) return new int[]{0, 16, 4, 4};
        return new int[]{16, 48, 4, 4};
    }

    private static int[] zoneCouche(String nom, boolean fin) {
        int b = fin ? 3 : 4;
        if (nom.equals("tete")) return new int[]{32, 0, 8, 8};
        if (nom.startsWith("corps")) return new int[]{16, 32, 8, 4};
        if (nom.startsWith("bras_droit")) return new int[]{40, 32, b, 4};
        if (nom.startsWith("bras_gauche")) return new int[]{48, 48, b, 4};
        if (nom.startsWith("jambe_droite")) return new int[]{0, 32, 4, 4};
        return new int[]{0, 48, 4, 4};
    }

    /** Identifiant de l'objet d'un morceau (« pantin_peau/corps_haut »). */
    public static String id(String morceau) {
        return "pantin_peau/" + morceau;
    }

    // ------------------------------------------------------------------ pixels → carrés

    /** Un carré : face, coin de départ et pas (un pixel) dans le repère du modèle. */
    record Carre(String face, double[] de, double[] a) {}

    /** Carrés d'une liste de faces sur un pavé (gonflé de g), dans l'ordre de lecture des pixels. */
    static List<Carre> carres(double[] de0, double[] a0, float g, List<Face> faces) {
        double[] de = {de0[0] - g, de0[1] - g, de0[2] - g}, a = {a0[0] + g, a0[1] + g, a0[2] + g};
        List<Carre> l = new ArrayList<>();
        for (Face f : faces) {
            for (int ty = 0; ty < f.h(); ty++) {
                for (int tx = 0; tx < f.l(); tx++) {
                    l.add(carre(f.nom(), de, a, f.l(), f.h(), tx, ty));
                }
            }
        }
        return l;
    }

    /**
     * Place le pixel (tx, ty) d'une face : comme le jeu (et la boîte UV de Blockbench), l'avant se lit depuis +x,
     * l'arrière depuis -x, le côté +x depuis l'arrière, le côté -x depuis l'avant, dessus et dessous depuis +x et
     * l'arrière.
     */
    private static Carre carre(String face, double[] de, double[] a, int l, int h, int tx, int ty) {
        double sx = (a[0] - de[0]), sy = (a[1] - de[1]), sz = (a[2] - de[2]);
        double[] p0 = new double[3], p1 = new double[3];
        switch (face) {
            case "north" -> {
                p0[0] = a[0] - sx * (tx + 1) / l; p1[0] = a[0] - sx * tx / l;
                p0[1] = a[1] - sy * (ty + 1) / h; p1[1] = a[1] - sy * ty / h;
                p0[2] = p1[2] = de[2];
            }
            case "south" -> {
                p0[0] = de[0] + sx * tx / l; p1[0] = de[0] + sx * (tx + 1) / l;
                p0[1] = a[1] - sy * (ty + 1) / h; p1[1] = a[1] - sy * ty / h;
                p0[2] = p1[2] = a[2];
            }
            case "east" -> {
                p0[2] = a[2] - sz * (tx + 1) / l; p1[2] = a[2] - sz * tx / l;
                p0[1] = a[1] - sy * (ty + 1) / h; p1[1] = a[1] - sy * ty / h;
                p0[0] = p1[0] = a[0];
            }
            case "west" -> {
                p0[2] = de[2] + sz * tx / l; p1[2] = de[2] + sz * (tx + 1) / l;
                p0[1] = a[1] - sy * (ty + 1) / h; p1[1] = a[1] - sy * ty / h;
                p0[0] = p1[0] = de[0];
            }
            case "up" -> {
                p0[0] = a[0] - sx * (tx + 1) / l; p1[0] = a[0] - sx * tx / l;
                p0[2] = a[2] - sz * (ty + 1) / h; p1[2] = a[2] - sz * ty / h;
                p0[1] = p1[1] = a[1];
            }
            default -> {   // down
                p0[0] = a[0] - sx * (tx + 1) / l; p1[0] = a[0] - sx * tx / l;
                p0[2] = a[2] - sz * (ty + 1) / h; p1[2] = a[2] - sz * ty / h;
                p0[1] = p1[1] = de[1];
            }
        }
        return new Carre(face, p0, p1);
    }

    private static JsonObject element(Carre c, int tint) {
        JsonObject e = new JsonObject();
        JsonArray de = new JsonArray(), a = new JsonArray();
        for (int i = 0; i < 3; i++) {
            de.add(arrondi(c.de()[i]));
            a.add(arrondi(c.a()[i]));
        }
        e.add("from", de);
        e.add("to", a);
        JsonObject faces = new JsonObject(), f = new JsonObject();
        JsonArray uv = new JsonArray();
        uv.add(0); uv.add(0); uv.add(16); uv.add(16);
        f.add("uv", uv);
        f.addProperty("texture", "#0");
        f.addProperty("tintindex", tint);
        faces.add(c.face(), f);
        e.add("faces", faces);
        return e;
    }

    private static JsonObject modele(JsonArray elements) {
        JsonObject j = new JsonObject(), t = new JsonObject();
        t.addProperty("0", GenerateurPack.NS + ":item/pantin_pixel");
        t.addProperty("particle", GenerateurPack.NS + ":item/pantin_pixel");
        j.add("textures", t);
        j.add("elements", elements);
        return j;
    }

    private static JsonObject teinte(int index) {
        JsonObject t = new JsonObject();
        t.addProperty("type", "minecraft:custom_model_data");
        t.addProperty("index", index);
        t.addProperty("default", 0xFFFFFF);
        return t;
    }

    /**
     * Fichiers du pack : la texture d'un pixel blanc, un modèle de base par morceau (un carré teinté par pixel), un
     * modèle par pixel de seconde couche, et la définition d'objet qui les assemble.
     */
    public static Map<String, byte[]> generer(ModeleBB squelette) {
        return generer(squelette, List.of());
    }

    /** Nombre de couches d'armure qu'un morceau peut porter fondues dans son objet (plastron et jambières, jambières et bottes). */
    public static final int RANGS_ARMURE = 2;

    /**
     * @param armures armures que le pantin sait porter (« minecraft:iron », voir Modeles.armuresPantin). La définition de
     *     chaque morceau reconnaît alors jusqu'à deux couches d'armure fondues dans son objet : les chaînes 0 et 1 de
     *     custom_model_data (ArmuresPantin.cle) choisissent leur modèle, les teintes qui suivent celles des pixels (deux par
     *     couche : la pièce, puis la surcouche du cuir) leur couleur. Une chaîne absente : pas d'armure.
     */
    public static Map<String, byte[]> generer(ModeleBB squelette, List<String> armures) {
        Map<String, byte[]> sortie = new TreeMap<>(genererBrasTenu());
        if (squelette == null) return sortie;
        sortie.put("assets/" + GenerateurPack.NS + "/textures/item/pantin_pixel.png", pixelBlanc());
        List<Morceau> tous = new ArrayList<>(morceaux(squelette, false));
        tous.addAll(morceaux(squelette, true));
        for (Morceau m : tous) {
            String dossier = "pantin_peau/" + m.nom();
            List<Carre> base = carres(m.de(), m.a(), 0, m.base());
            JsonArray el = new JsonArray();
            for (int i = 0; i < base.size(); i++) el.add(element(base.get(i), i));
            sortie.put("assets/" + GenerateurPack.NS + "/models/" + dossier + "/base.json", octets(modele(el)));

            JsonArray composite = new JsonArray();
            JsonObject mb = new JsonObject();
            mb.addProperty("type", "minecraft:model");
            mb.addProperty("model", GenerateurPack.NS + ":" + dossier + "/base");
            JsonArray tb = new JsonArray();
            for (int i = 0; i < base.size(); i++) tb.add(teinte(i));
            mb.add("tints", tb);
            composite.add(mb);

            List<Carre> couche = carres(m.de(), m.a(), m.gonfle(), m.couche());
            for (int k = 0; k < couche.size(); k++) {
                JsonArray un = new JsonArray();
                un.add(element(couche.get(k), 0));
                sortie.put("assets/" + GenerateurPack.NS + "/models/" + dossier + "/c" + k + ".json", octets(modele(un)));
                JsonObject cond = new JsonObject();
                cond.addProperty("type", "minecraft:condition");
                cond.addProperty("property", "minecraft:custom_model_data");
                cond.addProperty("index", k);
                JsonObject oui = new JsonObject();
                oui.addProperty("type", "minecraft:model");
                oui.addProperty("model", GenerateurPack.NS + ":" + dossier + "/c" + k);
                JsonArray tc = new JsonArray();
                tc.add(teinte(base.size() + k));
                oui.add("tints", tc);
                JsonObject non = new JsonObject();
                non.addProperty("type", "minecraft:empty");
                cond.add("on_true", oui);
                cond.add("on_false", non);
                composite.add(cond);
            }
            // couches d'armure fondues dans le morceau (même repère : pivot en 8, 8, 8, échelle 1)
            String os = m.nom().replace("_fin", "");
            int pixels = base.size() + couche.size();
            for (int rang = 0; rang < RANGS_ARMURE; rang++) {
                JsonArray cas = new JsonArray();
                for (String asset : armures) {
                    for (String c : List.of(ArmuresPantin.HUMANOID, ArmuresPantin.JAMBIERES)) {
                        if (!ArmuresPantin.existe(os, asset, c)) continue;
                        JsonObject un = new JsonObject();
                        un.addProperty("when", ArmuresPantin.cle(asset, c));
                        JsonObject mo = new JsonObject();
                        mo.addProperty("type", "minecraft:model");
                        mo.addProperty("model", GenerateurPack.NS + ":" + ArmuresPantin.id(asset, c, os));
                        JsonArray ta = new JsonArray();
                        ta.add(teinte(pixels + 2 * rang));
                        if (ArmuresPantin.cuir(asset)) ta.add(teinte(pixels + 2 * rang + 1));
                        mo.add("tints", ta);
                        un.add("model", mo);
                        cas.add(un);
                    }
                }
                if (cas.isEmpty()) continue;
                JsonObject choix = new JsonObject();
                choix.addProperty("type", "minecraft:select");
                choix.addProperty("property", "minecraft:custom_model_data");
                choix.addProperty("index", rang);
                choix.add("cases", cas);
                JsonObject rien = new JsonObject();
                rien.addProperty("type", "minecraft:empty");
                choix.add("fallback", rien);
                composite.add(choix);
            }
            JsonObject modele = new JsonObject();
            modele.addProperty("type", "minecraft:composite");
            modele.add("models", composite);
            JsonObject def = new JsonObject();
            def.add("model", modele);
            sortie.put("assets/" + GenerateurPack.NS + "/items/" + id(m.nom()) + ".json", octets(def));
        }
        return sortie;
    }

    // ------------------------------------------------------------------ bras tenu à la première personne

    /**
     * Réglage d'affichage « première personne » qui pose un modèle exprimé dans le repère des armes de poing de
     * vorace-item (bras du joueur en x 6 à 10, y 4 à 8, z 8 à 20, bout du poing en z = 8) exactement là où le jeu
     * dessine le bras du joueur à la première personne quand sa main est vide (ItemInHandRenderer.renderPlayerArm :
     * translation (0,64 ; -0,6 ; -0,72), rotations y 45°, z 120°, x 200°, y -135°, puis le bras au pivot (-5, 2, 0) tourné
     * de 0,1 rad), vu depuis le repère des objets tenus (translation (0,56 ; -0,52 ; -0,72)). Calculé une fois :
     * rotation x, y, z en degrés, translation en seizièmes.
     */
    public static final double[] PREMIERE_ROTATION = {35.569, -21.633, 69.021}, PREMIERE_TRANSLATION = {1.387, 2.296, -6.147};

    /** Modèle du bras droit tenu (« pantin_bras/tenu_droit », « _fin ») pour vorace-item. */
    public static final String BRAS_TENU = "pantin_bras/tenu_droit";

    /** Faces du skin du bras droit entier (haut et bas), comme le jeu le dessine à la première personne. */
    static List<Face> facesBrasTenu(boolean fin) {
        return faces(40, 16, fin ? 3 : 4, 4, 0, 12, true, true);
    }

    /** Nombre de pixels (de teintes) du bras tenu. */
    public static int pixelsBrasTenu(boolean fin) {
        int n = 0;
        for (Face f : facesBrasTenu(fin)) n += f.l() * f.h();
        return n;
    }

    /** Couleurs du bras tenu pour un skin, dans l'ordre des carrés du modèle. */
    public static int[] couleursBrasTenu(BufferedImage skin, boolean fin) {
        BufferedImage s = Peau.format64(skin);
        int[] c = new int[pixelsBrasTenu(fin)];
        int i = 0;
        for (Face f : facesBrasTenu(fin)) for (int ty = 0; ty < f.h(); ty++) for (int tx = 0; tx < f.l(); tx++) c[i++] = MarqueSoi.eviter(pixel(s, f.x0() + tx, f.y0() + ty));   // dessiné tout près de la caméra : jamais la marque
        return c;
    }

    /** Nom d'une face Blockbench une fois passée dans le repère des armes de poing (x + 2, 6 - z, y - 4). */
    private static String faceTenue(String f) {
        return switch (f) {
            case "north" -> "up";
            case "south" -> "down";
            case "up" -> "south";
            case "down" -> "north";
            default -> f;
        };
    }

    /** Modèles du bras droit tenu, large et fin : un carré teinté par pixel, dans le repère des armes de poing. */
    static Map<String, byte[]> genererBrasTenu() {
        Map<String, byte[]> sortie = new TreeMap<>();
        for (boolean fin : new boolean[]{false, true}) {
            // bras droit entier (Blockbench, y en haut, droite en +x) : x 4 à 8 (fin : 7, côté extérieur retiré)
            double[] de = {4, 12, -2}, a = {fin ? 7 : 8, 24, 2};
            List<Carre> carres = carres(de, a, 0, facesBrasTenu(fin));
            JsonArray el = new JsonArray();
            for (int i = 0; i < carres.size(); i++) {
                Carre c = carres.get(i);
                double[] p0 = vers(c.de(), fin), p1 = vers(c.a(), fin);
                double[] d = new double[3], f = new double[3];
                for (int k = 0; k < 3; k++) {
                    d[k] = Math.min(p0[k], p1[k]);
                    f[k] = Math.max(p0[k], p1[k]);
                }
                el.add(element(new Carre(faceTenue(c.face()), d, f), i));
            }
            JsonObject m = modele(el);
            JsonObject display = new JsonObject();
            for (String ctx : List.of("firstperson_righthand", "firstperson_lefthand")) {
                JsonObject t = new JsonObject();
                JsonArray r = new JsonArray(), tr = new JsonArray(), sc = new JsonArray();
                for (double v : PREMIERE_ROTATION) r.add(v);
                for (double v : PREMIERE_TRANSLATION) tr.add(v);
                for (int k = 0; k < 3; k++) sc.add(1);
                t.add("rotation", r);
                t.add("translation", tr);
                t.add("scale", sc);
                display.add(ctx, t);
            }
            m.add("display", display);
            sortie.put("assets/" + GenerateurPack.NS + "/models/" + BRAS_TENU + (fin ? "_fin" : "") + ".json", octets(m));
        }
        // équipement sans couche : une pièce d'armure qui ne se dessine pas (ObjetsCaches, pendant une animation)
        sortie.put("assets/" + GenerateurPack.NS + "/equipment/vide.json", "{\"layers\":{}}".getBytes(StandardCharsets.UTF_8));
        return sortie;
    }

    /** Blockbench (absolu) vers le repère des armes de poing ; bras fin : le jeu le pose un demi-pixel plus bas. */
    private static double[] vers(double[] p, boolean fin) {
        return new double[]{p[0] + 2, 6 - p[2], p[1] - 4 - (fin ? 0.5 : 0)};
    }

    /** Couleurs (base puis seconde couche) et drapeaux (seconde couche visible) d'un morceau pour un skin. */
    public record Donnees(int[] couleurs, boolean[] drapeaux) {}

    /** Les données de chaque morceau pour un skin (64 x 64, ou ancien format 64 x 32). */
    public static Map<String, Donnees> donnees(ModeleBB squelette, BufferedImage skin, boolean fin) {
        BufferedImage s = Peau.format64(skin);
        Map<String, Donnees> r = new LinkedHashMap<>();
        List<Morceau> liste = new ArrayList<>(morceaux(squelette, false));
        if (fin) {
            // bras fins à la place des bras ordinaires
            liste.removeIf(m -> m.nom().startsWith("bras"));
            liste.addAll(morceaux(squelette, true));
        }
        for (Morceau m : liste) {
            List<Integer> c = new ArrayList<>();
            List<Boolean> d = new ArrayList<>();
            for (Face f : m.base()) for (int ty = 0; ty < f.h(); ty++) for (int tx = 0; tx < f.l(); tx++) c.add(pixel(s, f.x0() + tx, f.y0() + ty) & 0xFFFFFF);
            for (Face f : m.couche()) {
                for (int ty = 0; ty < f.h(); ty++) {
                    for (int tx = 0; tx < f.l(); tx++) {
                        int p = pixel(s, f.x0() + tx, f.y0() + ty);
                        c.add(p & 0xFFFFFF);
                        d.add(((p >>> 24) & 0xFF) >= 128);
                    }
                }
            }
            int[] ci = c.stream().mapToInt(Integer::intValue).toArray();
            boolean[] di = new boolean[d.size()];
            for (int i = 0; i < di.length; i++) di[i] = d.get(i);
            r.put(m.nom().replace("_fin", ""), new Donnees(ci, di));
        }
        return r;
    }

    /** Nom de l'objet du morceau selon le modèle de bras (« bras_droit_haut_fin »). */
    public static String morceauObjet(String morceau, boolean fin) {
        return fin && morceau.startsWith("bras") ? morceau + "_fin" : morceau;
    }

    private static int pixel(BufferedImage s, int x, int y) {
        return x >= 0 && y >= 0 && x < s.getWidth() && y < s.getHeight() ? s.getRGB(x, y) : 0;
    }

    private static byte[] octets(JsonObject j) {
        return j.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static double arrondi(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static byte[] pixelBlanc() {
        try {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, 0xFFFFFF);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", o);
            return o.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
