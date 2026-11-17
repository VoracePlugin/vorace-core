package fr.vorace.modeles;

import java.util.Locale;

/**
 * Écriture d'une clé d'effet d'animation (piste « Effets » de Blockbench), sans Bukkit.
 *
 * <ul>
 *   <li>Particule : {@code nom[#RRGGBB[:taille]][*nombre][@os][~écart][^vitesse]}, par exemple
 *   {@code flame*30@peau_bras_droit_bas~0.3^0.05} ou {@code dust#FF6A00:1.6*40~1.2}. Sans os : au milieu du modèle.</li>
 *   <li>Son : {@code nom[*volume][^hauteur]}, par exemple {@code entity.generic.explode*1.5^0.7}.</li>
 * </ul>
 */
public record EffetSpec(String nom, int couleur, float taille, int nombre, String os, double ecart, double vitesse,
                        float volume, float hauteur) {

    public static EffetSpec lire(String texte) {
        String s = texte == null ? "" : texte.trim();
        String nom = s;
        int couleur = -1, nombre = -1;
        float taille = 1f, volume = 1f, hauteur = 1f;
        String os = null;
        double ecart = -1, vitesse = -1;
        int i = premier(s, "#*@~^");
        if (i >= 0) {
            nom = s.substring(0, i);
            String reste = s.substring(i);
            while (!reste.isEmpty()) {
                char c = reste.charAt(0);
                int j = premier(reste.substring(1), "#*@~^");
                String v = j < 0 ? reste.substring(1) : reste.substring(1, j + 1);
                reste = j < 0 ? "" : reste.substring(j + 1);
                try {
                    switch (c) {
                        case '#' -> {
                            String[] p = v.split(":");
                            couleur = Integer.parseInt(p[0], 16) & 0xFFFFFF;
                            if (p.length > 1) taille = Float.parseFloat(p[1]);
                        }
                        case '*' -> {
                            double d = Double.parseDouble(v);
                            nombre = (int) Math.round(d);
                            volume = (float) d;
                        }
                        case '@' -> os = v.isBlank() ? null : v;
                        case '~' -> ecart = Double.parseDouble(v);
                        case '^' -> {
                            vitesse = Double.parseDouble(v);
                            hauteur = (float) vitesse;
                        }
                        default -> { }
                    }
                } catch (NumberFormatException ignore) {
                    // valeur illisible : on garde celle par défaut
                }
            }
        }
        nom = nom.toLowerCase(Locale.ROOT).trim();
        if (nom.startsWith("minecraft:")) nom = nom.substring(10);
        return new EffetSpec(nom, couleur, taille, nombre < 0 ? 6 : Math.min(400, nombre), os, ecart < 0 ? 0.2 : Math.min(8, ecart),
            vitesse < 0 ? 0.02 : vitesse, Math.max(0.05f, Math.min(4f, volume)), Math.max(0.5f, Math.min(2f, hauteur)));
    }

    private static int premier(String s, String signes) {
        int m = -1;
        for (char c : signes.toCharArray()) {
            int k = s.indexOf(c);
            if (k >= 0 && (m < 0 || k < m)) m = k;
        }
        return m;
    }
}
