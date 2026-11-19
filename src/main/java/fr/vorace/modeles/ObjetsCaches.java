package fr.vorace.modeles;

import fr.vorace.Vorace;
import fr.vorace.paquets.Paquet;
import fr.vorace.paquets.Sens;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.inventory.meta.components.EquippableComponent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Ce que le jeu dessine sur le joueur lui-même pendant une animation du pantin, et l'arme par paire de la main gauche,
 * sans jamais toucher à l'inventaire du serveur : Vorace change seulement, au passage, les objets des paquets envoyés
 * aux clients.
 *
 * <ul>
 * <li>Pendant une animation, le joueur est invisible et son pantin le remplace. Chez lui, le jeu dessinerait encore
 * son armure et ses objets tenus sur son corps invisible (en F5), et ses objets en main (première personne), à chaque
 * mise à jour de son inventaire (un coup qui use une arme suffit). Ses pièces d'armure lui sont donc envoyées sans
 * modèle porté (composant equippable sans asset) et ses objets en main avec le drapeau 0 de custom_model_data
 * (« caché en main », lu par les définitions de vorace-item) : les icônes de l'inventaire restent.</li>
 * <li>Les autres joueurs reçoivent son équipement vide pendant l'animation.</li>
 * <li>Arme par paire (armes de poing) sans rien dans l'autre main : sa main gauche reçoit une copie avec le drapeau 1
 * (« paire » : vue à la troisième personne seulement).</li>
 * </ul>
 * Les objets du serveur ne portent jamais ces changements : à la fin, l'inventaire est renvoyé tel quel.
 */
public final class ObjetsCaches {

    /** Drapeaux de custom_model_data lus par les définitions d'objets de vorace-item. */
    public static final int CACHE_EN_MAIN = 0, PAIRE = 1, BRAS_FIN = 2, ARMURE_CACHEE = 3;

    private final Vorace plugin;
    private final Set<UUID> cachesSoi = ConcurrentHashMap.newKeySet();
    private final Set<Integer> entitesCachees = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Object> pairesSoi = new ConcurrentHashMap<>();
    private final Map<Integer, Object> pairesEntites = new ConcurrentHashMap<>();
    private boolean actif;

    private Method versBukkit, versJeu, estVide;
    private Object vide;
    private Class<?> classePaire;
    private Method paireDe;

    public ObjetsCaches(Vorace plugin) {
        this.plugin = plugin;
    }

    /** Branche les écoutes de paquets ; faux si la couche paquets de Vorace n'est pas là. */
    public boolean demarrer() {
        if (plugin.paquets() == null) return false;
        try {
            Class<?> craft = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".inventory.CraftItemStack");
            versJeu = craft.getMethod("asNMSCopy", ItemStack.class);
            Class<?> objetJeu = versJeu.getReturnType();
            versBukkit = craft.getMethod("asBukkitCopy", objetJeu);
            estVide = objetJeu.getMethod("isEmpty");
            vide = versJeu.invoke(null, new ItemStack(org.bukkit.Material.AIR));
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "Objets cachés du pantin : CraftItemStack introuvable", e);
            return false;
        }
        plugin.paquets().ecouter(plugin, Sens.SORTANT, this::paquet, "ClientboundSetEquipmentPacket",
            "ClientboundContainerSetSlotPacket", "ClientboundContainerSetContentPacket", "ClientboundSetPlayerInventoryPacket");
        // mode créatif : le client dit lui-même ce qu'il pose dans son inventaire ; une copie marquée (paire de la main
        // gauche, objet caché) n'existe que chez lui et ne doit jamais devenir un vrai objet
        plugin.paquets().ecouter(plugin, Sens.ENTRANT, this::creatif, "ServerboundSetCreativeModeSlotPacket");
        actif = true;
        return true;
    }

    private void creatif(Paquet p) throws ReflectiveOperationException {
        Object o = p.lire("itemStack");
        if (o == null || (boolean) estVide.invoke(o)) return;
        ItemStack b = (ItemStack) versBukkit.invoke(null, o);
        Player j = p.joueur();
        if (marque(b)) {
            p.annuler();
            if (j != null) Bukkit.getScheduler().runTask(plugin, j::updateInventory);
            return;
        }
        // arme par paire revenue du client avec les couleurs de son bras : on les retire, l'objet reste le sien
        if (j != null && brasSoi.containsKey(j.getUniqueId()) && b.hasItemMeta()
            && b.getItemMeta().getPersistentDataContainer().has(Pantins.PAIRE)) {
            ItemMeta m = b.getItemMeta();
            CustomModelDataComponent cmd = m.getCustomModelDataComponent();
            cmd.setColors(List.of());
            List<Boolean> f = new ArrayList<>(cmd.getFlags());
            if (f.size() > BRAS_FIN) f.set(BRAS_FIN, false);
            while (!f.isEmpty() && !f.get(f.size() - 1)) f.remove(f.size() - 1);
            cmd.setFlags(f);
            m.setCustomModelDataComponent(cmd);
            b.setItemMeta(m);
            p.ecrire("itemStack", versJeu.invoke(null, b));
        }
    }

    /** Copie marquée par Vorace (drapeau 0 ou 1 de custom_model_data). */
    static boolean marque(ItemStack o) {
        if (o == null || !o.hasItemMeta()) return false;
        ItemMeta m = o.getItemMeta();
        List<Boolean> f = m.getCustomModelDataComponent().getFlags();
        for (int i : new int[]{CACHE_EN_MAIN, PAIRE, ARMURE_CACHEE}) if (i < f.size() && Boolean.TRUE.equals(f.get(i))) return true;
        return m.hasEquippable() && VIDE.equals(m.getEquippable().getModel());
    }

    public boolean actif() {
        return actif;
    }

    // ------------------------------------------------------------------ état (fil principal)

    /** Début d'une animation : le joueur ne voit plus rien sur lui, les autres plus rien en main ni sur lui. */
    public void cacher(Player p) {
        cachesSoi.add(p.getUniqueId());
        entitesCachees.add(p.getEntityId());
        p.updateInventory();
    }

    /** Fin de l'animation : l'inventaire du serveur est renvoyé tel quel. */
    public void montrer(Player p) {
        boolean etait = cachesSoi.remove(p.getUniqueId());
        entitesCachees.remove(p.getEntityId());
        if (etait && p.isOnline()) p.updateInventory();
    }

    public boolean cache(Player p) {
        return cachesSoi.contains(p.getUniqueId());
    }

    /** Arme par paire montrée dans la main gauche (copie avec le drapeau « paire »), ou null pour ne plus rien montrer. */
    public void paire(Player p, ItemStack copie) {
        if (copie == null) {
            pairesSoi.remove(p.getUniqueId());
            pairesEntites.remove(p.getEntityId());
            return;
        }
        try {
            // au joueur aussi (deux poings en F5) : la case de la main gauche à côté de la barre d'objets est rendue
            // invisible par le pack (pantin.cacher-case-main-gauche) et la copie n'y montre pas d'icône (drapeau 1)
            Object jeu = versJeu.invoke(null, copie);
            if (plugin.getConfig().getBoolean("pantin.paires-soi", true)) pairesSoi.put(p.getUniqueId(), jeu);
            pairesEntites.put(p.getEntityId(), jeu);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Paire : " + e);
        }
    }

    public void oublier(Player p) {
        brasSoi.remove(p.getUniqueId());
        cachesSoi.remove(p.getUniqueId());
        entitesCachees.remove(p.getEntityId());
        pairesSoi.remove(p.getUniqueId());
        pairesEntites.remove(p.getEntityId());
    }

    /** Copie d'un objet avec un drapeau de custom_model_data levé (les autres valeurs gardées). */
    public static ItemStack avecDrapeau(ItemStack o, int index) {
        ItemStack c = o.clone();
        ItemMeta m = c.getItemMeta();
        if (m == null) return c;
        CustomModelDataComponent cmd = m.getCustomModelDataComponent();
        List<Boolean> f = new ArrayList<>(cmd.getFlags());
        while (f.size() <= index) f.add(false);
        f.set(index, true);
        cmd.setFlags(f);
        m.setCustomModelDataComponent(cmd);
        c.setItemMeta(m);
        return c;
    }

    /** Copie d'une pièce d'armure qui ne se dessine plus sur le corps (equippable sans modèle porté). */
    static ItemStack sansModelePorte(ItemStack o, EquipmentSlot place) {
        ItemStack c = o.clone();
        ItemMeta m = c.getItemMeta();
        if (m == null) return c;
        EquippableComponent e = m.getEquippable();
        e.setSlot(place);
        // un équipement sans couche (pas « aucun modèle » : un casque sans modèle porté serait dessiné comme un objet
        // posé sur la tête)
        e.setModel(VIDE);
        m.setEquippable(e);
        c.setItemMeta(m);
        // marquée : en mode créatif, le client renvoie ses objets ; celle-ci ne doit jamais redevenir un vrai objet
        return avecDrapeau(c, ARMURE_CACHEE);
    }

    static final org.bukkit.NamespacedKey VIDE = new org.bukkit.NamespacedKey(fr.vorace.objets.GenerateurPack.NS, "vide");

    /**
     * Objet du serveur abîmé par une copie revenue du client en mode créatif (avant la 1.4.6 : armure sans modèle porté
     * ou avec l'équipement vide, drapeaux 0, 1 ou 3) : sa version réparée, ou null s'il est sain.
     */
    public static ItemStack reparer(ItemStack o) {
        if (o == null || o.getType().isAir() || !o.hasItemMeta()) return null;
        ItemMeta m = o.getItemMeta();
        boolean change = false;
        CustomModelDataComponent cmd = m.getCustomModelDataComponent();
        List<Boolean> f = new ArrayList<>(cmd.getFlags());
        for (int i : new int[]{CACHE_EN_MAIN, PAIRE, ARMURE_CACHEE}) {
            if (i < f.size() && Boolean.TRUE.equals(f.get(i))) {
                f.set(i, false);
                change = true;
            }
        }
        if (change) {
            while (!f.isEmpty() && !f.get(f.size() - 1)) f.remove(f.size() - 1);
            cmd.setFlags(f);
            m.setCustomModelDataComponent(cmd);
        }
        if (m.hasEquippable()) {
            org.bukkit.NamespacedKey modele = m.getEquippable().getModel();
            String n = o.getType().name();
            // les casques 3D de vorace-item n'ont volontairement pas de modèle porté : on ne touche qu'aux armures du jeu
            boolean voraceItem = m.getPersistentDataContainer().has(new org.bukkit.NamespacedKey("voraceitem", "modele"));
            boolean armureDuJeu = !voraceItem && (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS"));
            if (VIDE.equals(modele) || (modele == null && armureDuJeu)) {
                m.setEquippable(null);   // retour à l'équipement d'origine de l'objet (vorace-item refait le sien à la connexion)
                change = true;
            }
        }
        if (!change) return null;
        ItemStack r = o.clone();
        r.setItemMeta(m);
        return r;
    }

    // ------------------------------------------------------------------ paquets (fil réseau)

    private void paquet(Paquet p) throws ReflectiveOperationException {
        Player j = p.joueur();
        if (j == null) return;
        switch (p.nom()) {
            case "ClientboundSetEquipmentPacket" -> equipement(p, j);
            case "ClientboundContainerSetSlotPacket" -> {
                if ((int) p.lire("containerId") != 0) return;
                Object o = p.lire("itemStack");
                Object n = caseMenu(j, (int) p.lire("slot"), o);
                if (n != o) p.remplacer(nouveau(p.brut().getClass(), p.lire("containerId"), p.lire("stateId"), p.lire("slot"), n));
            }
            case "ClientboundContainerSetContentPacket" -> contenu(p, j);
            case "ClientboundSetPlayerInventoryPacket" -> {
                int s = (int) p.lire("slot");
                Object o = p.lire("contents");
                // numéros de l'inventaire : 0 à 8 barre, 36 pieds à 39 tête, 40 main gauche ; vers ceux du menu
                int menu = s <= 8 ? 36 + s : s >= 36 && s <= 39 ? 8 - (s - 36) : s == 40 ? 45 : -1;
                if (menu < 0) return;
                Object n = caseMenu(j, menu, o);
                if (n != o) p.ecrire("contents", n);
            }
            default -> { }
        }
    }

    /** Objet d'une case du menu de l'inventaire (5 à 8 armure, 36 à 44 barre, 45 main gauche) tel que le joueur le voit. */
    private Object caseMenu(Player j, int slot, Object o) throws ReflectiveOperationException {
        boolean vid = (boolean) estVide.invoke(o);
        boolean cache = cachesSoi.contains(j.getUniqueId());
        if (vid) {
            if (!cache && slot == 45) {
                Object paire = pairesSoi.get(j.getUniqueId());
                if (paire != null) return paire;
            }
            return o;
        }
        if (cache && slot >= 5 && slot <= 8) return armure(o, PLACES_MENU[slot - 5]);
        boolean barre = slot >= 36 && slot <= 44;
        Bras bras = barre ? brasSoi.get(j.getUniqueId()) : null;
        if (!cache && bras == null) return o;
        if (!barre && slot != 45) return o;
        ItemStack b = (ItemStack) versBukkit.invoke(null, o);
        boolean change = false;
        if (bras != null && b.hasItemMeta() && b.getItemMeta().getPersistentDataContainer().has(Pantins.PAIRE)) {
            b = avecBras(b, bras);
            change = true;
        }
        if (cache) {
            b = avecDrapeau(b, CACHE_EN_MAIN);
            change = true;
        }
        return change ? versJeu.invoke(null, b) : o;
    }

    /** Couleurs du bras droit du joueur (son skin) et bras fin ou non. */
    public record Bras(java.util.List<org.bukkit.Color> couleurs, boolean fin) {}

    private final Map<UUID, Bras> brasSoi = new ConcurrentHashMap<>();

    /**
     * Arme par paire tenue à la première personne : vorace-item dessine le bras du joueur avec (le jeu ne le dessine
     * plus dès qu'un objet est en main). Ses couleurs viennent du skin, sur la copie envoyée au joueur seulement.
     */
    public void bras(Player p, int[] couleurs, boolean fin) {
        java.util.List<org.bukkit.Color> l = new ArrayList<>(couleurs.length);
        for (int c : couleurs) l.add(org.bukkit.Color.fromRGB(c & 0xFFFFFF));
        brasSoi.put(p.getUniqueId(), new Bras(List.copyOf(l), fin));
        if (p.isOnline()) p.updateInventory();
    }

    /** Couleurs du bras (custom_model_data « colors ») et drapeau 2 « bras fin ». */
    static ItemStack avecBras(ItemStack o, Bras bras) {
        ItemStack c = o.clone();
        ItemMeta m = c.getItemMeta();
        if (m == null) return c;
        CustomModelDataComponent cmd = m.getCustomModelDataComponent();
        cmd.setColors(bras.couleurs());
        List<Boolean> f = new ArrayList<>(cmd.getFlags());
        while (f.size() <= BRAS_FIN) f.add(false);
        f.set(BRAS_FIN, bras.fin());
        cmd.setFlags(f);
        m.setCustomModelDataComponent(cmd);
        c.setItemMeta(m);
        return c;
    }

    private static final EquipmentSlot[] PLACES_MENU = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private Object armure(Object o, EquipmentSlot place) throws ReflectiveOperationException {
        return versJeu.invoke(null, sansModelePorte((ItemStack) versBukkit.invoke(null, o), place));
    }

    private Object enMain(Object o) throws ReflectiveOperationException {
        return versJeu.invoke(null, avecDrapeau((ItemStack) versBukkit.invoke(null, o), CACHE_EN_MAIN));
    }

    private void contenu(Paquet p, Player j) throws ReflectiveOperationException {
        if ((int) p.lire("containerId") != 0) return;
        if (!cachesSoi.contains(j.getUniqueId()) && !pairesSoi.containsKey(j.getUniqueId()) && !brasSoi.containsKey(j.getUniqueId())) return;
        @SuppressWarnings("unchecked")
        List<Object> objets = (List<Object>) p.lire("items");
        List<Object> nouveaux = new ArrayList<>(objets);
        boolean change = false;
        for (int s = 0; s < nouveaux.size(); s++) {
            Object n = caseMenu(j, s, nouveaux.get(s));
            if (n != nouveaux.get(s)) {
                nouveaux.set(s, n);
                change = true;
            }
        }
        if (!change) return;
        try {
            // paquet « record » (liste simple)
            p.remplacer(nouveau(p.brut().getClass(), p.lire("containerId"), p.lire("stateId"), nouveaux, p.lire("carriedItem")));
        } catch (NoSuchMethodException e) {
            // classe qui veut une NonNullList et la recopie : on lui donne l'ancienne, puis on change sa copie
            Object neuf = nouveau(p.brut().getClass(), p.lire("containerId"), p.lire("stateId"), objets, p.lire("carriedItem"));
            @SuppressWarnings("unchecked")
            List<Object> sienne = (List<Object>) champ(neuf, "items").get(neuf);
            for (int s = 0; s < nouveaux.size() && s < sienne.size(); s++) sienne.set(s, nouveaux.get(s));
            p.remplacer(neuf);
        }
    }

    private void equipement(Paquet p, Player voit) throws ReflectiveOperationException {
        int id = (int) p.lire("entity");
        boolean soi = id == voit.getEntityId();
        boolean cacheAutres = !soi && entitesCachees.contains(id);
        boolean cacheSoi = soi && cachesSoi.contains(voit.getUniqueId());
        Object paire = cacheAutres || cacheSoi ? null : soi ? pairesSoi.get(voit.getUniqueId()) : pairesEntites.get(id);
        if (!cacheAutres && !cacheSoi && paire == null) return;
        @SuppressWarnings("unchecked")
        List<Object> places = (List<Object>) p.lire("slots");
        List<Object> nouvelles = new ArrayList<>();
        boolean change = false;
        for (Object pr : places) {
            Object place = pr.getClass().getMethod("getFirst").invoke(pr);
            Object objet = pr.getClass().getMethod("getSecond").invoke(pr);
            String nom = ((Enum<?>) place).name();
            Object n = objet;
            if (cacheAutres) {
                n = vide;
            } else if (cacheSoi) {
                if (!(boolean) estVide.invoke(objet)) {
                    switch (nom) {
                        case "HEAD" -> n = armure(objet, EquipmentSlot.HEAD);
                        case "CHEST" -> n = armure(objet, EquipmentSlot.CHEST);
                        case "LEGS" -> n = armure(objet, EquipmentSlot.LEGS);
                        case "FEET" -> n = armure(objet, EquipmentSlot.FEET);
                        case "MAINHAND", "OFFHAND" -> n = enMain(objet);
                        default -> { }
                    }
                }
            } else if (nom.equals("OFFHAND") && (boolean) estVide.invoke(objet)) {
                n = paire;
            }
            if (n != objet) change = true;
            nouvelles.add(n == objet ? pr : paire(pr, place, n));
        }
        if (change) p.remplacer(nouveau(p.brut().getClass(), id, nouvelles));
    }

    private Object paire(Object exemple, Object a, Object b) throws ReflectiveOperationException {
        if (paireDe == null || classePaire != exemple.getClass()) {
            classePaire = exemple.getClass();
            paireDe = classePaire.getMethod("of", Object.class, Object.class);
        }
        return paireDe.invoke(null, a, b);
    }

    // ------------------------------------------------------------------ réflexion

    private static final Map<Class<?>, Constructor<?>[]> CONSTRUCTEURS = new ConcurrentHashMap<>();

    /** Nouveau paquet du même type, par le constructeur qui prend ces valeurs (types primitifs ou compatibles). */
    private static Object nouveau(Class<?> c, Object... valeurs) throws ReflectiveOperationException {
        for (Constructor<?> k : CONSTRUCTEURS.computeIfAbsent(c, Class::getDeclaredConstructors)) {
            Class<?>[] t = k.getParameterTypes();
            if (t.length != valeurs.length) continue;
            boolean ok = true;
            for (int i = 0; i < t.length && ok; i++) {
                Object v = valeurs[i];
                if (t[i].isPrimitive()) ok = v instanceof Integer && t[i] == int.class;
                else ok = v == null || t[i].isInstance(v);
            }
            if (!ok) continue;
            k.setAccessible(true);
            return k.newInstance(valeurs);
        }
        throw new NoSuchMethodException("constructeur de " + c.getSimpleName() + " introuvable");
    }

    private static Field champ(Object o, String nom) throws NoSuchFieldException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(nom);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
                // classe mère
            }
        }
        throw new NoSuchFieldException(nom);
    }
}
