package fr.vorace.objets;

import fr.vorace.Messages;
import fr.vorace.Vorace;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Essai de la mise à jour des objets (sert à l'essai automatique sur un vrai serveur, sans joueur) :
 * une épée d'exemple est renommée, reçoit des données, une ligne de description et un attribut d'un autre plugin,
 * passe par un enregistrement complet, puis sa définition change. Ce qui appartient à l'exemplaire doit rester,
 * ce qui vient de la définition doit changer.
 */
public final class EssaiObjets {

    private final Vorace plugin;

    public EssaiObjets(Vorace plugin) { this.plugin = plugin; }

    public void lancer(CommandSender s) {
        Objets objets = plugin.objets();
        DefinitionObjet d = objets.definition("epee_rubis");
        if (d == null) { s.sendMessage(Messages.brut("<red>Objet d'exemple « epee_rubis » introuvable.")); return; }
        NamespacedKey qualite = new NamespacedKey("essai", "qualite");
        NamespacedKey force = new NamespacedKey("essai", "force");
        Attribute degats = attribut();
        List<String> echecs = new ArrayList<>();

        // 1. objet renommé, avec les ajouts d'un autre plugin
        ItemStack renomme = objets.fabriquer(d, 1);
        ItemMeta m = renomme.getItemMeta();
        m.displayName(Component.text("Lame d'Antoine"));
        m.getPersistentDataContainer().set(qualite, PersistentDataType.STRING, "rare");
        List<Component> lore = new ArrayList<>(m.lore());
        lore.add(Component.text("Qualité : rare"));
        m.lore(lore);
        if (degats != null) m.addAttributeModifier(degats, new AttributeModifier(force, 2.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        if (m instanceof Damageable dm) dm.setDamage(7);
        if (m instanceof Repairable r) r.setRepairCost(5);
        renomme.setItemMeta(m);
        // 2. même objet sans rien de plus
        ItemStack simple = objets.fabriquer(d, 1);

        // enregistrés puis relus, comme après un redémarrage du serveur
        renomme = ItemStack.deserializeBytes(renomme.serializeAsBytes());
        simple = ItemStack.deserializeBytes(simple.serializeAsBytes());

        // la définition change : autre nom, autre description
        DefinitionObjet change = modifiee(d, "<gold>Épée de rubis taillé", List.of("<gray>Nouvelle description."));
        ItemStack a = objets.mettreAJour(renomme, change);
        ItemStack b = objets.mettreAJour(simple, change);
        ItemMeta ma = a.getItemMeta(), mb = b.getItemMeta();

        verifier(echecs, "nom-garde", "Lame d'Antoine".equals(texte(ma.displayName())));
        verifier(echecs, "donnees-gardees", "rare".equals(ma.getPersistentDataContainer().get(qualite, PersistentDataType.STRING)));
        verifier(echecs, "description", List.of("Nouvelle description.", "Qualité : rare").equals(lignes(ma)));
        verifier(echecs, "attribut-garde", degats == null || ma.getAttributeModifiers(degats) != null
            && ma.getAttributeModifiers(degats).stream().anyMatch(cle(force)));
        verifier(echecs, "usure", ma instanceof Damageable da && da.getDamage() == 7);
        verifier(echecs, "reparation", ma instanceof Repairable ra && ra.getRepairCost() == 5);
        verifier(echecs, "nom-neuf", "Épée de rubis taillé".equals(texte(mb.displayName())));
        verifier(echecs, "description-neuve", List.of("Nouvelle description.").equals(lignes(mb)));
        verifier(echecs, "version", !objets.perime(a, change));
        // deuxième mise à jour : rien ne doit se perdre ni se doubler
        ItemStack a2 = objets.mettreAJour(ItemStack.deserializeBytes(a.serializeAsBytes()), d);
        ItemMeta m2 = a2.getItemMeta();
        verifier(echecs, "deuxieme-maj", "Lame d'Antoine".equals(texte(m2.displayName()))
            && List.of("Forgée avec des rubis.", "", "Clic droit : bond en avant", "Qualité : rare").equals(lignes(m2))
            && (degats == null || m2.getAttributeModifiers(degats).stream().filter(cle(force)).count() == 1));

        String verdict = "ESSAI OBJETS echecs=" + echecs.size() + (echecs.isEmpty() ? "" : " " + String.join(",", echecs))
            + " | " + texte(ma.displayName()) + " | " + lignes(ma);
        plugin.getLogger().info(verdict);
        s.sendMessage(Messages.brut((echecs.isEmpty() ? "<green>" : "<red>") + Messages.echapper(verdict)));
    }

    private static void verifier(List<String> echecs, String quoi, boolean ok) { if (!ok) echecs.add(quoi); }

    private static Predicate<AttributeModifier> cle(NamespacedKey k) { return mod -> k.equals(mod.getKey()); }

    private static String texte(Component c) { return c == null ? null : PlainTextComponentSerializer.plainText().serialize(c); }

    private static List<String> lignes(ItemMeta m) {
        List<String> r = new ArrayList<>();
        if (m.hasLore() && m.lore() != null) for (Component c : m.lore()) r.add(texte(c));
        return r;
    }

    private static Attribute attribut() {
        for (String n : new String[]{"attack_damage", "generic.attack_damage"}) {
            try {
                Attribute a = org.bukkit.Registry.ATTRIBUTE.get(NamespacedKey.minecraft(n));
                if (a != null) return a;
            } catch (Throwable ignore) {
                // nom de l'autre version
            }
        }
        return null;
    }

    /** Copie de la définition avec un autre nom et une autre description. */
    private static DefinitionObjet modifiee(DefinitionObjet d, String nom, List<String> description) {
        try {
            RecordComponent[] cs = DefinitionObjet.class.getRecordComponents();
            Object[] v = new Object[cs.length];
            Class<?>[] types = new Class<?>[cs.length];
            for (int i = 0; i < cs.length; i++) {
                types[i] = cs[i].getType();
                v[i] = switch (cs[i].getName()) {
                    case "nom" -> nom;
                    case "description" -> description;
                    default -> cs[i].getAccessor().invoke(d);
                };
            }
            return DefinitionObjet.class.getDeclaredConstructor(types).newInstance(v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
