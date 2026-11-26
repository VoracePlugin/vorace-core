package fr.vorace.modeles;

import org.bukkit.Color;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bras de sa propre copie du pantin, effacés à la première personne (comme « Vanilla Hands » de First-person Model).
 *
 * <p>Le serveur ne sait pas si le joueur est en F1 ou en F5. Le shader des objets du pack
 * (rendertype_item_entity_translucent_cull) le devine : à la première personne, la caméra est dans la tête, à moins
 * de deux blocs des bras ; en F5, elle est à quatre blocs. Il efface donc les sommets marqués tout près de la caméra.
 * La marque est dans la teinte des sommets (couleurs du composant custom_model_data, qui arrivent telles quelles au
 * shader) : les deux bits bas de rouge, vert et bleu valent 1, 2 et 1. L'écart (au plus 3 sur 255) ne se voit pas.
 * Les couleurs des pantins des autres évitent cette marque ; un sommet sans teinte est blanc (3, 3, 3).
 */
public final class MarqueSoi {

    private MarqueSoi() {}

    static final int BITS = 0x030303, MARQUE = 0x010201;

    /** Couleur marquée (bras de sa propre copie). */
    static int marquer(int rgb) {
        return (rgb & 0xFFFFFF & ~BITS) | MARQUE;
    }

    /** Couleur qui ne porte pas la marque par hasard (skin, cuir des pantins vus par les autres). */
    public static int eviter(int rgb) {
        rgb &= 0xFFFFFF;
        return (rgb & BITS) == MARQUE ? rgb ^ 0x000001 : rgb;
    }

    /** Copie marquée d'un objet du pantin : toutes ses teintes, celle du cuir comprise (blanc marqué par défaut). */
    static ItemStack marquer(ItemStack o) {
        if (o == null) return null;
        ItemStack c = o.clone();
        ItemMeta meta = c.getItemMeta();
        if (meta == null) return c;
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        List<Color> avant = cmd.getColors();
        List<Color> apres = new ArrayList<>(Math.max(1, avant.size()));
        for (Color col : avant) apres.add(Color.fromRGB(marquer(col.asRGB())));
        if (apres.isEmpty()) apres.add(Color.fromRGB(marquer(0xFFFFFF)));
        cmd.setColors(apres);
        meta.setCustomModelDataComponent(cmd);
        if (meta instanceof LeatherArmorMeta cuir) cuir.setColor(Color.fromRGB(marquer(cuir.getColor().asRGB())));
        c.setItemMeta(meta);
        return c;
    }

    /** Os du bras (ou d'un de ses enfants : avant-bras, poing) : « bras… », « …_bras… », « arm », « left_arm »… */
    static boolean bras(ModeleBB m, int k) {
        for (int i = k; i >= 0 && i < m.parties().size(); i = m.parties().get(i).parent()) {
            String n = m.parties().get(i).nom();
            if (n == null) continue;
            n = n.toLowerCase(Locale.ROOT);
            if (n.startsWith("peau_")) n = n.substring(5);
            if (n.startsWith("bras") || n.contains("_bras") || n.contains("avant-bras") || n.contains("forearm")) return true;
            if (n.matches("(.*[_\\-.])?(left|right)?_?arms?([_\\-.].*)?") || n.matches(".*(left|right)arm.*")) return true;
        }
        return false;
    }
}
