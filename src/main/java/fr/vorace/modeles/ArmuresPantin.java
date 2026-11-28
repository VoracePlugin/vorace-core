package fr.vorace.modeles;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.vorace.objets.GenerateurPack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Armure portée par le pantin d'un joueur (sans Bukkit).
 *
 * <p>Le jeu dessine l'armure d'un joueur avec ses textures d'équipement (textures/entity/equipment/humanoid/fer.png...,
 * même disposition qu'un skin 64 x 32). Pour qu'elle suive le pantin, chaque os « peau_... » reçoit en plus un modèle
 * d'objet : un pavé un peu plus grand que le morceau du skin (1 pixel pour casque, plastron et bottes, 0,5 pour les
 * jambières, comme le jeu), dont les faces prennent la zone voulue de la texture d'équipement. Ces textures sont dans
 * le jeu lui-même : le pack les ajoute seulement à l'atlas des objets (atlases/blocks.json). Le cuir est teint par le
 * composant dyed_color de l'objet affiché ; l'éclat des enchantements passe par enchantment_glint_override.
 */
public final class ArmuresPantin {

    private ArmuresPantin() {}

    /** Équipements du jeu (1.21.4). Le cuir a une seconde couche non teinte (leather_overlay). */
    public static final List<String> VANILLE = List.of("minecraft:leather", "minecraft:chainmail", "minecraft:iron", "minecraft:gold",
        "minecraft:diamond", "minecraft:netherite", "minecraft:turtle_scute");

    private static final java.util.regex.Pattern EQUIPEMENT = java.util.regex.Pattern.compile("^assets/([a-z0-9_.-]+)/equipment/([a-z0-9_./-]+)\\.json$");

    /**
     * Modèle d'équipement déclaré par un fichier de pack (« assets/voraceitem/equipment/babel/etage1/e1_veuve.json » ->
     * « voraceitem:babel/etage1/e1_veuve ») ; null pour un autre fichier ou un équipement du jeu (espace minecraft).
     */
    public static String assetDepuisFichier(String chemin) {
        java.util.regex.Matcher m = EQUIPEMENT.matcher(chemin);
        if (!m.matches() || m.group(1).equals("minecraft")) return null;
        return m.group(1) + ":" + m.group(2);
    }

    /** Couches d'équipement du jeu : casque, plastron et bottes ; jambières. */
    public static final String HUMANOID = "humanoid", JAMBIERES = "humanoid_leggings";

    /** Une couche à afficher sur un os du pantin. */
    public record Couche(String os, String id, float gonfle, boolean teinte) {}

    /** Os couverts par chaque emplacement d'armure, avec la couche et le gonflement du jeu. */
    public static List<String[]> morceaux(String emplacement) {
        return switch (emplacement) {
            case "HEAD" -> List.<String[]>of(new String[]{"tete", HUMANOID});
            case "CHEST" -> List.of(new String[]{"corps_haut", HUMANOID}, new String[]{"corps_bas", HUMANOID},
                new String[]{"bras_droit_haut", HUMANOID}, new String[]{"bras_droit_bas", HUMANOID},
                new String[]{"bras_gauche_haut", HUMANOID}, new String[]{"bras_gauche_bas", HUMANOID});
            case "LEGS" -> List.of(new String[]{"corps_haut", JAMBIERES}, new String[]{"corps_bas", JAMBIERES},
                new String[]{"jambe_droite_haut", JAMBIERES}, new String[]{"jambe_droite_bas", JAMBIERES},
                new String[]{"jambe_gauche_haut", JAMBIERES}, new String[]{"jambe_gauche_bas", JAMBIERES});
            case "FEET" -> List.of(new String[]{"jambe_droite_haut", HUMANOID}, new String[]{"jambe_droite_bas", HUMANOID},
                new String[]{"jambe_gauche_haut", HUMANOID}, new String[]{"jambe_gauche_bas", HUMANOID});
            default -> List.of();
        };
    }

    public static float gonflement(String couche) {
        return JAMBIERES.equals(couche) ? 0.5f : 1.0f;
    }

    /** « minecraft:iron » -> « minecraft/iron ». */
    static String chemin(String asset) {
        String a = asset.toLowerCase(Locale.ROOT);
        if (!a.contains(":")) a = "minecraft:" + a;
        return a.replace(':', '/');
    }

    /** Identifiant de l'objet affiché (« pantin_armure/minecraft/iron/humanoid/corps_haut »). */
    public static String id(String asset, String couche, String morceau) {
        return "pantin_armure/" + chemin(asset) + "/" + couche + "/" + morceau;
    }

    /**
     * Couche d'armure fondue dans le morceau de peau qui la porte (un seul affichage, donc un seul paquet par image, au
     * lieu de deux) : la clé que l'objet du morceau porte dans custom_model_data (chaîne 0 ou 1) et que sa définition
     * reconnaît (PeauUV.generer). « minecraft/iron/humanoid ».
     */
    public static String cle(String asset, String couche) {
        return chemin(asset) + "/" + couche;
    }

    /** Clé d'après l'identifiant de l'objet d'une couche (« pantin_armure/minecraft/iron/humanoid/corps_haut ») ; null sinon. */
    public static String cleDepuisId(String id) {
        if (id == null || !id.startsWith("pantin_armure/")) return null;
        int fin = id.lastIndexOf('/');
        return fin <= "pantin_armure/".length() ? null : id.substring("pantin_armure/".length(), fin);
    }

    /** Cette couche existe-t-elle pour ce morceau ? (carapace de tortue : la tête seule ; jambières : ni tête ni bras) */
    static boolean existe(String morceau, String asset, String couche) {
        if (asset.endsWith("turtle_scute") && (couche.equals(JAMBIERES) || !morceau.equals("tete"))) return false;
        return !(couche.equals(JAMBIERES) && (morceau.equals("tete") || morceau.startsWith("bras")));
    }

    public static boolean cuir(String asset) {
        return chemin(asset).equals("minecraft/leather");
    }

    /** Zone d'une partie dans une texture d'équipement (disposition d'un skin 64 x 32) : u, v, largeur, hauteur, épaisseur. */
    private static int[] zone(String morceau) {
        if (morceau.startsWith("tete")) return new int[]{0, 0, 8, 8, 8};
        if (morceau.startsWith("corps")) return new int[]{16, 16, 8, 12, 4};
        if (morceau.startsWith("bras")) return new int[]{40, 16, 4, 12, 4};
        return new int[]{0, 16, 4, 12, 4};   // jambes
    }

    /** Tous les objets d'armure à mettre dans le pack, avec leur modèle. */
    public static Map<String, byte[]> generer(ModeleBB squelette, List<String> assets) {
        Map<String, byte[]> sortie = new TreeMap<>();
        if (squelette == null) return sortie;
        // les textures d'équipement du jeu (et des packs) dans l'atlas des objets
        JsonObject atlas = new JsonObject();
        JsonArray sources = new JsonArray();
        JsonObject src = new JsonObject();
        src.addProperty("type", "directory");
        src.addProperty("source", "entity/equipment");
        src.addProperty("prefix", "entity/equipment/");
        sources.add(src);
        atlas.add("sources", sources);
        sortie.put("assets/minecraft/atlases/blocks.json", atlas.toString().getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, ModeleBB.Partie> e : os(squelette).entrySet()) {
            String morceau = e.getKey();
            for (String asset : assets) {
                for (String couche : List.of(HUMANOID, JAMBIERES)) {
                    if (asset.endsWith("turtle_scute") && (couche.equals(JAMBIERES) || !morceau.equals("tete"))) continue;
                    if (couche.equals(JAMBIERES) && (morceau.equals("tete") || morceau.startsWith("bras"))) continue;
                    JsonObject m = modele(e.getValue(), morceau, asset, couche);
                    sortie.put("assets/" + GenerateurPack.NS + "/models/" + id(asset, couche, morceau) + ".json", m.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return sortie;
    }

    /** Identifiants d'objets à déclarer (objet visuel par modèle) ; vrai = teint (cuir). */
    public static Map<String, Boolean> ids(ModeleBB squelette, List<String> assets) {
        Map<String, Boolean> ids = new LinkedHashMap<>();
        if (squelette == null) return ids;
        for (String morceau : os(squelette).keySet()) {
            for (String asset : assets) {
                for (String couche : List.of(HUMANOID, JAMBIERES)) {
                    if (asset.endsWith("turtle_scute") && (couche.equals(JAMBIERES) || !morceau.equals("tete"))) continue;
                    if (couche.equals(JAMBIERES) && (morceau.equals("tete") || morceau.startsWith("bras"))) continue;
                    ids.put(id(asset, couche, morceau), cuir(asset));
                }
            }
        }
        return ids;
    }

    /** Os « peau_... » avec un cube, par nom de morceau (sans « peau_ »). */
    static Map<String, ModeleBB.Partie> os(ModeleBB m) {
        Map<String, ModeleBB.Partie> r = new LinkedHashMap<>();
        for (ModeleBB.Partie p : m.parties()) {
            String n = p.nom() == null ? "" : p.nom().toLowerCase(Locale.ROOT);
            if (n.startsWith("peau_") && !p.cubes().isEmpty()) r.put(n.substring(5), p);
        }
        return r;
    }

    /** Modèle d'une couche : pavé autour du cube de l'os (repère : pivot de l'os en 8, 8, 8, comme les parties). */
    static JsonObject modele(ModeleBB.Partie p, String morceau, String asset, String couche) {
        String[] ns = chemin(asset).split("/", 2);
        String tex = ns[0] + ":entity/equipment/" + couche + "/" + ns[1];
        JsonObject j = new JsonObject();
        JsonObject textures = new JsonObject();
        textures.addProperty("0", tex);
        if (cuir(asset)) textures.addProperty("1", tex + "_overlay");
        textures.addProperty("particle", "#0");
        j.add("textures", textures);
        JsonArray elements = new JsonArray();
        float g = gonflement(couche);
        // teintes : 0 = cuir (dyed_color) ou custom_model_data ; 1 = surcouche du cuir (custom_model_data). Blanches
        // d'ordinaire ; marquées sur les bras de sa propre copie (MarqueSoi)
        elements.add(element(p, morceau, g, "#0", 0));
        if (cuir(asset)) elements.add(element(p, morceau, g + 0.02f, "#1", 1));
        j.add("elements", elements);
        return j;
    }

    private static JsonObject element(ModeleBB.Partie p, String morceau, float g, String texture, int teinte) {
        ModeleBB.Cube c = p.cubes().get(0);
        double[] piv = p.pivot();
        JsonObject e = new JsonObject();
        JsonArray de = new JsonArray(), a = new JsonArray();
        for (int i = 0; i < 3; i++) {
            de.add(arrondi(c.de()[i] - piv[i] + 8 - g));
            a.add(arrondi(c.a()[i] - piv[i] + 8 + g));
        }
        e.add("from", de);
        e.add("to", a);
        int[] z = zone(morceau);
        int u = z[0], v = z[1], l = z[2], h = z[3], d = z[4];
        // lignes de la zone : tête entière, moitié haute (0 à 5) ou basse avec le recouvrement (4 à 11), comme Peau
        boolean tete = morceau.startsWith("tete");
        boolean bas = morceau.endsWith("_bas");
        int r0 = tete ? 0 : bas ? h / 2 - Peau.recouvrement(morceau) : 0;
        int rh = tete ? h : bas ? h / 2 + Peau.recouvrement(morceau) : h / 2;
        double haut = v + d + r0, fond = haut + rh;
        boolean gauche = morceau.contains("gauche");   // le jeu dessine bras et jambe gauches en miroir des droits
        Map<String, double[]> uv = new LinkedHashMap<>();
        uv.put("north", new double[]{u + d, haut, u + d + l, fond});
        uv.put("east", new double[]{u, haut, u + d, fond});
        uv.put("south", new double[]{u + 2 * d + l, haut, u + 2 * d + 2 * l, fond});
        uv.put("west", new double[]{u + d + l, haut, u + 2 * d + l, fond});
        uv.put("up", new double[]{u + d + l, v + d, u + d, v});
        uv.put("down", new double[]{u + d + 2 * l, v, u + d + l, v + d});
        // bouchon de l'articulation d'un membre (genou, coude) : la face de jointure prend la ligne de la face avant qui la
        // touche, étirée. Sans elle, la pièce était un tube ouvert et l'on voyait au travers d'un genou plié (PeauUV.bouchon).
        boolean membre = morceau.startsWith("bras") || morceau.startsWith("jambe");
        if (membre && bas) uv.put("up", new double[]{u + d, haut, u + d + l, haut + 1});
        if (membre && !bas) uv.put("down", new double[]{u + d, fond - 1, u + d + l, fond});
        if (gauche) {
            double[] est = uv.get("east"), ouest = uv.get("west");
            uv.put("east", ouest);
            uv.put("west", est);
            for (double[] f : uv.values()) { double t = f[0]; f[0] = f[2]; f[2] = t; }
        }
        JsonObject faces = new JsonObject();
        for (Map.Entry<String, double[]> f : uv.entrySet()) {
            // la face de jointure du tronc (dessous du haut) reste vide ; celle d'un membre est son bouchon (ci-dessus)
            if (!tete && !membre && ((!bas && f.getKey().equals("down")) || (bas && f.getKey().equals("up") && !morceau.startsWith("corps_bas")))) continue;
            JsonObject o = new JsonObject();
            JsonArray t = new JsonArray();
            double[] w = f.getValue();
            t.add(arrondi(w[0] * 16 / 64.0));
            t.add(arrondi(w[1] * 16 / 32.0));
            t.add(arrondi(w[2] * 16 / 64.0));
            t.add(arrondi(w[3] * 16 / 32.0));
            o.add("uv", t);
            o.addProperty("texture", texture);
            o.addProperty("tintindex", teinte);
            faces.add(f.getKey(), o);
        }
        e.add("faces", faces);
        return e;
    }

    private static double arrondi(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** Liste des couches d'un ensemble porté : emplacement -> asset (null : rien). Les armures du jeu ont des manches courtes. */
    public static List<Couche> couches(Map<String, String> portes) {
        return couches(portes, new java.util.HashSet<>(VANILLE));
    }

    /**
     * @param manchesCourtes armures dont la manche s'arrête au-dessus du coude. Celles du jeu (mesuré sur leurs textures :
     *     la manche du plastron couvre les lignes 0 à 4 ou 5 des 12 du bras, 6 pour la netherite) et celles de
     *     pantin.armures-manches-courtes. Elles n'ont pas de couche sur l'avant-bras : cette couche ne montrait que le
     *     bord de la manche (les 2 lignes de recouvrement au-dessus du coude), qui partait avec l'avant-bras et se
     *     décollait de l'épaulière dès que le coude pliait.
     */
    public static List<Couche> couches(Map<String, String> portes, java.util.Set<String> manchesCourtes) {
        List<Couche> l = new ArrayList<>();
        for (Map.Entry<String, String> e : portes.entrySet()) {
            if (e.getValue() == null) continue;
            String asset = e.getValue().toLowerCase(Locale.ROOT);
            boolean courtes = manchesCourtes.contains(asset.contains(":") ? asset : "minecraft:" + asset);
            for (String[] m : morceaux(e.getKey())) {
                if (e.getValue().endsWith("turtle_scute") && !m[0].equals("tete")) continue;
                if (courtes && m[0].startsWith("bras_") && m[0].endsWith("_bas")) continue;
                l.add(new Couche("peau_" + m[0], id(e.getValue(), m[1], m[0]), gonflement(m[1]), cuir(e.getValue())));
            }
        }
        return l;
    }
}
