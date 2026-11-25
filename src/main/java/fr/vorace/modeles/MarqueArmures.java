package fr.vorace.modeles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Corps fantôme : les dessins d'armure portée du pack (textures/entity/equipment/humanoid et humanoid_leggings) ont leurs
 * pixels passés à l'opacité 253 (différence invisible à l'œil). Le shader des armures de Vorace (vorace_armure) efface
 * ces pixels hors des menus : l'armure du vrai joueur n'est dessinée que dans son inventaire, et c'est son pantin qui la
 * porte dans le monde. Les armures du jeu absentes du pack ne sont pas marquées (elles restent visibles en F5).
 */
public final class MarqueArmures {

    /** Opacité qui marque un pixel d'armure « seulement dans les menus » (lue par vorace_armure.fsh). */
    public static final int OPACITE = 253;

    /** Fichiers du shader du corps fantôme, dans le .jar sous pack-fantome/. */
    public static final List<String> SHADERS = List.of(
        "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh",
        "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.fsh",
        "assets/minecraft/shaders/core/rendertype_armor_cutout_no_cull.json",
        "assets/minecraft/shaders/core/vorace_armure.vsh",
        "assets/minecraft/shaders/core/vorace_armure.fsh");

    private MarqueArmures() {
    }

    /** Dessin d'armure portée (corps ou jambières) d'un pack, à marquer. */
    static boolean dessinArmure(String chemin) {
        if (!chemin.startsWith("assets/") || !chemin.endsWith(".png")) return false;
        return chemin.contains("/textures/entity/equipment/humanoid/") || chemin.contains("/textures/entity/equipment/humanoid_leggings/");
    }

    /** Marque les dessins d'armure portée de ces fichiers de pack ; renvoie leur nombre. */
    public static int marquer(Map<String, byte[]> fichiers) {
        int n = 0;
        for (Map.Entry<String, byte[]> e : fichiers.entrySet()) {
            if (!dessinArmure(e.getKey())) continue;
            byte[] m = marquer(e.getValue());
            if (m != null) {
                e.setValue(m);
                n++;
            }
        }
        return n;
    }

    /** L'image marquée (tout pixel visible à l'opacité 253), ou null si elle est illisible. */
    static byte[] marquer(byte[] png) {
        try {
            BufferedImage lu = ImageIO.read(new ByteArrayInputStream(png));
            if (lu == null) return null;
            BufferedImage img = new BufferedImage(lu.getWidth(), lu.getHeight(), BufferedImage.TYPE_INT_ARGB);
            img.getGraphics().drawImage(lu, 0, 0, null);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int c = img.getRGB(x, y);
                    // le jeu ne dessine pas un pixel d'armure sous 0,1 d'opacité : ceux-là restent tels quels
                    if ((c >>> 24) < 26) continue;
                    img.setRGB(x, y, (OPACITE << 24) | (c & 0xFFFFFF));
                }
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ImageIO.write(img, "png", o);
            return o.toByteArray();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }
}
