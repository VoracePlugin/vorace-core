package fr.vorace.modeles;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Découpe le skin d'un joueur en morceaux affichables par des têtes de joueur (sans Bukkit).
 *
 * <p>Une tête de joueur ne montre que la zone « tête » de son skin (cube de 8 x 8 x 8 et sa couche « chapeau »). Pour
 * animer tout le corps avec le vrai skin, chaque morceau du corps (moitié haute et moitié basse du tronc, de chaque
 * bras et de chaque jambe) devient un skin à part dont la zone tête contient les faces de ce morceau, agrandies à
 * 8 x 8 (plus proche voisin). Le pantin pose ensuite une tête par morceau, aplatie aux vraies proportions.
 */
public final class Peau {

    private Peau() {}

    /**
     * Une zone du skin : un pavé de taille (l, h, p) dont les faces sont rangées à partir de (u, v) comme pour toutes
     * les parties du joueur : dessus (u+p, v), dessous (u+p+l, v), droite (u, v+p), avant (u+p, v+p),
     * gauche (u+p+l, v+p), arrière (u+2p+l, v+p).
     */
    record Zone(int u, int v, int l, int h, int p) {}

    /**
     * Un morceau du pantin.
     *
     * @param base      zone du skin
     * @param surcouche zone de la seconde couche (veste, manches, jambières), ou null
     * @param haut      vrai : moitié haute (lignes 0 à 5), faux : moitié basse (lignes 4 à 11, recouvrement compris)
     */
    record Morceau(Zone base, Zone surcouche, boolean haut) {}

    /** Lignes du skin (et pixels du cube) que la moitié basse reprend au-dessus de la jointure. */
    public static final int RECOUVREMENT = 2;

    /**
     * Recouvrement d'un morceau du pantin : aucun pour le bas du torse. Son haut (le torse, plus large que lui d'un
     * vingtième de pixel) descend d'un demi-pixel par-dessus, comme une veste sur une ceinture : quand le buste tourne
     * ou se penche, le bas du torse ne ressort plus sur les côtés (retour d'Antoine, 19 nov.). Bras et jambes : 2.
     */
    public static int recouvrement(String morceau) {
        return morceau.startsWith("corps_bas") ? 0 : RECOUVREMENT;
    }

    /** Les dix morceaux, par nom d'os du pantin sans le préfixe « peau_ ». */
    public static final List<String> MORCEAUX = List.of("corps_haut", "corps_bas", "bras_droit_haut", "bras_droit_bas",
        "bras_gauche_haut", "bras_gauche_bas", "jambe_droite_haut", "jambe_droite_bas", "jambe_gauche_haut", "jambe_gauche_bas");

    private static Map<String, Morceau> morceaux(boolean fin) {
        int b = fin ? 3 : 4;  // bras fins (modèle « Alex ») : 3 pixels de large
        Map<String, Morceau> m = new LinkedHashMap<>();
        Zone corps = new Zone(16, 16, 8, 12, 4), veste = new Zone(16, 32, 8, 12, 4);
        Zone brasD = new Zone(40, 16, b, 12, 4), mancheD = new Zone(40, 32, b, 12, 4);
        Zone brasG = new Zone(32, 48, b, 12, 4), mancheG = new Zone(48, 48, b, 12, 4);
        Zone jambeD = new Zone(0, 16, 4, 12, 4), jambiereD = new Zone(0, 32, 4, 12, 4);
        Zone jambeG = new Zone(16, 48, 4, 12, 4), jambiereG = new Zone(0, 48, 4, 12, 4);
        m.put("corps_haut", new Morceau(corps, veste, true));
        m.put("corps_bas", new Morceau(corps, veste, false));
        m.put("bras_droit_haut", new Morceau(brasD, mancheD, true));
        m.put("bras_droit_bas", new Morceau(brasD, mancheD, false));
        m.put("bras_gauche_haut", new Morceau(brasG, mancheG, true));
        m.put("bras_gauche_bas", new Morceau(brasG, mancheG, false));
        m.put("jambe_droite_haut", new Morceau(jambeD, jambiereD, true));
        m.put("jambe_droite_bas", new Morceau(jambeD, jambiereD, false));
        m.put("jambe_gauche_haut", new Morceau(jambeG, jambiereG, true));
        m.put("jambe_gauche_bas", new Morceau(jambeG, jambiereG, false));
        return m;
    }

    /**
     * Découpe un skin (64 x 64, ou ancien format 64 x 32) en dix skins de 64 x 64 dont seules les zones « tête » et
     * « chapeau » sont remplies.
     *
     * @param fin bras fins (modèle « slim »)
     */
    public static Map<String, BufferedImage> decouper(BufferedImage skin, boolean fin) {
        BufferedImage s = format64(skin);
        Map<String, BufferedImage> res = new LinkedHashMap<>();
        for (Map.Entry<String, Morceau> e : morceaux(fin).entrySet()) {
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Morceau mo = e.getValue();
            remplir(s, mo.base(), mo.haut(), img, 0, 0);
            if (mo.surcouche() != null) remplir(s, mo.surcouche(), mo.haut(), img, 32, 0);
            res.put(e.getKey(), img);
        }
        return res;
    }

    /** Ancien skin 64 x 32 : bras et jambe gauches copiés des droits, sans seconde couche du corps. */
    static BufferedImage format64(BufferedImage skin) {
        if (skin.getWidth() == 64 && skin.getHeight() == 64) return skin;
        BufferedImage s = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int hauteur = Math.min(32, skin.getHeight());
        for (int y = 0; y < hauteur; y++) for (int x = 0; x < Math.min(64, skin.getWidth()); x++) s.setRGB(x, y, skin.getRGB(x, y));
        copier(s, 0, 16, 16, 48, 16, 16);   // jambe droite -> jambe gauche
        copier(s, 40, 16, 32, 48, 16, 16);  // bras droit -> bras gauche
        return s;
    }

    private static void copier(BufferedImage s, int x0, int y0, int x1, int y1, int l, int h) {
        for (int y = 0; y < h; y++) for (int x = 0; x < l; x++) s.setRGB(x1 + x, y1 + y, s.getRGB(x0 + x, y0 + y));
    }

    /**
     * Range les faces d'une moitié de zone dans la zone tête (8 x 8 x 8) d'un skin, à partir de (du, dv) :
     * (0, 0) pour la tête, (32, 0) pour le chapeau.
     */
    private static void remplir(BufferedImage src, Zone z, boolean haut, BufferedImage dst, int du, int dv) {
        // moitié basse : deux lignes de plus vers le haut ; son cube remonte de deux pixels dans le morceau du dessus, et
        // la jointure (coude, genou, taille) reste couverte quand le membre se plie
        int r0 = haut ? 0 : z.h() / 2 - RECOUVREMENT, rh = haut ? z.h() / 2 : z.h() / 2 + RECOUVREMENT;
        int u = z.u(), v = z.v(), l = z.l(), p = z.p();
        // côtés : droite, avant, gauche, arrière (lignes de la moitié)
        etirer(src, u, v + p + r0, p, rh, dst, du, dv + 8);
        etirer(src, u + p, v + p + r0, l, rh, dst, du + 8, dv + 8);
        etirer(src, u + p + l, v + p + r0, p, rh, dst, du + 16, dv + 8);
        etirer(src, u + 2 * p + l, v + p + r0, l, rh, dst, du + 24, dv + 8);
        // dessus et dessous (la face de la jointure, cachée, reprend la vraie)
        etirer(src, u + p, v, l, p, dst, du + 8, dv);
        etirer(src, u + p + l, v, l, p, dst, du + 16, dv);
    }

    /** Copie un rectangle (l x h) de src dans un carré 8 x 8 de dst, au plus proche voisin. */
    static void etirer(BufferedImage src, int x0, int y0, int l, int h, BufferedImage dst, int x1, int y1) {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int sx = x0 + Math.min(l - 1, x * l / 8), sy = y0 + Math.min(h - 1, y * h / 8);
                dst.setRGB(x1 + x, y1 + y, src.getRGB(sx, sy));
            }
        }
    }
}
