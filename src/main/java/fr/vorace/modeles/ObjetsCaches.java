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
    /**
     * Drapeau 4, « caché à la troisième personne » (pantin permanent, hors animation) : l'objet reste en main à la première
     * personne et dans l'inventaire, mais n'est plus dessiné dans la main de son corps invisible en F5 (c'est le pantin qui
     * le tient). Lu par les définitions de vorace-item (0.14.12 et plus) et par celles de Vorace pour les objets du jeu.
     */
    public static final int CACHE_TIERS = 4;
    /** Dossier des définitions « caché à la troisième personne » des objets du jeu (assets/vorace/items/cache_tiers/). */
    public static final String CACHE_TIERS_DU_JEU = "cache_tiers/";

    private final Vorace plugin;
    private final Set<UUID> cachesSoi = ConcurrentHashMap.newKeySet();
    /** Pantin permanent : objets en main cachés à la troisième personne seulement (le joueur garde son arme à la 1re personne). */
    private final Set<UUID> tiersSoi = ConcurrentHashMap.newKeySet();

    /** Pantin permanent mis ou retiré : ses objets en main ne se dessinent plus (ou de nouveau) sur son corps invisible en F5. */
    public void cacherTiers(Player p, boolean oui) {
        boolean change = oui ? tiersSoi.add(p.getUniqueId()) : tiersSoi.remove(p.getUniqueId());
        if (change && p.isOnline()) p.updateInventory();
    }
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

    private Constructor<?> caseInventaire;
    private boolean caseCherchee;

    /**
     * Renvoie au joueur les objets de sa barre et de sa main gauche tels qu'il doit les voir (les copies marquées « caché
     * en main »). À appeler après un clic dans un inventaire : le client y déplace lui-même SA copie de l'objet, non marquée
     * si elle vient du reste de l'inventaire, d'un coffre ou de l'inventaire du mode créatif ; le serveur, d'accord avec
     * lui, ne renvoie rien, et l'objet restait dessiné dans la main du jeu en plus de celui que tient le pantin. Une case
     * à la fois (ClientboundSetPlayerInventoryPacket) : l'objet sous le curseur n'est pas touché.
     */
    public void rafraichirBarre(Player p) {
        if (!actif || !p.isOnline()) return;
        UUID id = p.getUniqueId();
        if (!cachesSoi.contains(id) && !tiersSoi.contains(id) && !brasSoi.containsKey(id)) return;
        try {
            if (!caseCherchee) {
                caseCherchee = true;
                try {
                    Class<?> c = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket", false,
                        Bukkit.getServer().getClass().getClassLoader());
                    caseInventaire = c.getConstructor(int.class, versJeu.getReturnType());
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().warning("Objets cachés du pantin : paquet d'une case d'inventaire introuvable (" + e + ")");
                }
            }
            if (caseInventaire == null) return;
            for (int s = 0; s <= 9; s++) {
                int inv = s == 9 ? 40 : s;   // 0 à 8 : la barre ; 40 : la main gauche
                ItemStack b = p.getInventory().getItem(inv);
                if (b == null || b.getType().isAir()) continue;
                Object o = versJeu.invoke(null, b);
                Object n = caseMenu(p, inv == 40 ? 45 : 36 + inv, o);
                if (n != o) plugin.paquets().envoyer(p, caseInventaire.newInstance(inv, n), false);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Objets cachés du pantin : barre non renvoyée à " + p.getName(), e);
        }
    }

    private void creatif(Paquet p) throws ReflectiveOperationException {
        Object o = p.lire("itemStack");
        if (o == null || (boolean) estVide.invoke(o)) return;
        ItemStack b = (ItemStack) versBukkit.invoke(null, o);
        Player j = p.joueur();
        if (copie(b)) {
            // copie qui n'existe que chez lui (paire de la main gauche, armure sans modèle porté) : jamais un vrai objet
            p.annuler();
            if (j != null) Bukkit.getScheduler().runTask(plugin, j::updateInventory);
            return;
        }
        if (marque(b)) {
            // objet de sa barre caché en main (pantin permanent : tout le temps) : c'est bien son objet, il le déplace ;
            // le serveur le reçoit sans la marque (sinon l'objet resterait caché en main pour toujours, chez tout le monde)
            ItemStack propre = reparer(b);
            if (propre != null) {
                b = propre;
                p.ecrire("itemStack", versJeu.invoke(null, b));
            }
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

    /** Drapeaux de custom_model_data que Vorace lève sur les copies envoyées aux clients. */
    private static final int[] DRAPEAUX_VORACE = {CACHE_EN_MAIN, PAIRE, ARMURE_CACHEE, CACHE_TIERS};

    /** Objet marqué par Vorace (un drapeau de custom_model_data, ou l'équipement vide). */
    static boolean marque(ItemStack o) {
        if (o == null || !o.hasItemMeta()) return false;
        ItemMeta m = o.getItemMeta();
        List<Boolean> f = m.getCustomModelDataComponent().getFlags();
        for (int i : DRAPEAUX_VORACE) if (i < f.size() && Boolean.TRUE.equals(f.get(i))) return true;
        return m.hasEquippable() && VIDE.equals(m.getEquippable().getModel());
    }

    /** Copie qui n'a pas d'objet du serveur derrière elle ou qui a perdu son équipement : paire, armure sans modèle porté. */
    static boolean copie(ItemStack o) {
        if (o == null || !o.hasItemMeta()) return false;
        ItemMeta m = o.getItemMeta();
        List<Boolean> f = m.getCustomModelDataComponent().getFlags();
        for (int i : new int[]{PAIRE, ARMURE_CACHEE}) if (i < f.size() && Boolean.TRUE.equals(f.get(i))) return true;
        return m.hasEquippable() && VIDE.equals(m.getEquippable().getModel());
    }

    public boolean actif() {
        return actif;
    }

    private volatile boolean garderArmureSoi;
    /** Pantin permanent : (entité, joueur qui regarde) → ce joueur est trop loin et voit le joueur du jeu, équipement compris. */
    private volatile java.util.function.BiPredicate<Integer, Player> lointain = (e, j) -> false;

    public void lointain(java.util.function.BiPredicate<Integer, Player> l) {
        lointain = l == null ? (e, j) -> false : l;
    }

    /** Corps fantôme : l'armure reste envoyée telle quelle au joueur pendant une animation (visible dans son inventaire). */
    public void garderArmureSoi(boolean oui) {
        garderArmureSoi = oui;
    }

    // ------------------------------------------------------------------ état (fil principal)

    /** Début d'une animation : le joueur ne voit plus rien sur lui, les autres plus rien en main ni sur lui. */
    public void cacher(Player p) {
        cachesSoi.add(p.getUniqueId());
        entitesCachees.add(p.getEntityId());
        p.updateInventory();
    }

    /** Pantin permanent : les autres ne reçoivent plus rien de ce qu'il porte ; lui voit toujours tout. */
    public void cacherAutres(Player p) {
        entitesCachees.add(p.getEntityId());
    }

    public void montrerAutres(Player p) {
        entitesCachees.remove(p.getEntityId());
    }

    /** Début d'une animation : il ne voit plus ses objets en main (ils sont dans les mains de son pantin). */
    public void cacherSoi(Player p) {
        if (cachesSoi.add(p.getUniqueId())) p.updateInventory();
    }

    /** Fin d'une animation du pantin permanent : il revoit ses objets en main. */
    public void montrerSoi(Player p) {
        if (cachesSoi.remove(p.getUniqueId()) && p.isOnline()) p.updateInventory();
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
        tiersSoi.remove(p.getUniqueId());
        cachesSoi.remove(p.getUniqueId());
        entitesCachees.remove(p.getEntityId());
        pairesSoi.remove(p.getUniqueId());
        pairesEntites.remove(p.getEntityId());
    }

    /** Drapeau 5, « tendu » : arc dessiné bandé (lu par les arcs de vorace-item 0.14.13 et plus). */
    public static final int TENDU = 5;
    /** Définition de l'arc du jeu bandé (assets/vorace/items/pantin_tendu/bow.json). */
    public static final String ARC_TENDU = "pantin_tendu/bow";

    /**
     * Copie d'un arc dessinée bandée, pour la main du pantin d'un joueur qui vise : un affichage d'objet n'a pas de porteur,
     * le jeu y dessine toujours l'arc au repos. Arc de vorace-item : drapeau « tendu » ; arc du jeu : la définition de Vorace.
     */
    static ItemStack tendu(ItemStack o) {
        ItemStack c = avecDrapeau(o, TENDU);
        ItemMeta m = c.getItemMeta();
        if (m == null || o.getType() != org.bukkit.Material.BOW) return c;
        CustomModelDataComponent cmd = m.getCustomModelDataComponent();
        boolean duJeu = (!m.hasItemModel() || org.bukkit.Material.BOW.getKey().equals(m.getItemModel()))
            && cmd.getFloats().isEmpty() && cmd.getStrings().isEmpty();
        if (!duJeu) return c;
        m.setItemModel(new org.bukkit.NamespacedKey(fr.vorace.objets.GenerateurPack.NS, ARC_TENDU));
        c.setItemMeta(m);
        return c;
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
        for (int i : DRAPEAUX_VORACE) {
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
        if (m.hasItemModel() && m.getItemModel() != null && fr.vorace.objets.GenerateurPack.NS.equals(m.getItemModel().getNamespace())
                && (m.getItemModel().getKey().startsWith(CACHE_DU_JEU) || m.getItemModel().getKey().startsWith(CACHE_TIERS_DU_JEU))
                && !m.getPersistentDataContainer().has(CACHE_VOULU)) {
            m.setItemModel(null);   // copie « cachée en main » d'un objet du jeu revenue du client en mode créatif
            change = true;
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
        boolean tiers = !cache && tiersSoi.contains(j.getUniqueId());
        if (vid) {
            if (!cache && slot == 45) {
                Object paire = pairesSoi.get(j.getUniqueId());
                if (paire != null) return paire;
            }
            return o;
        }
        // son armure, tant que son pantin le remplace (animation, ou pantin permanent) : gardée si le pack l'efface dans le
        // monde (il la voit sur son personnage dans l'inventaire), sinon envoyée sans modèle porté (elle flotterait en F5)
        if ((cache || tiers) && slot >= 5 && slot <= 8) return garderArmureSoi ? o : armure(o, PLACES_MENU[slot - 5]);
        boolean barre = slot >= 36 && slot <= 44;
        Bras bras = barre ? brasSoi.get(j.getUniqueId()) : null;
        if (!cache && !tiers && bras == null) return o;
        if (!barre && slot != 45) return o;
        ItemStack b = (ItemStack) versBukkit.invoke(null, o);
        boolean change = false;
        if (bras != null && b.hasItemMeta() && b.getItemMeta().getPersistentDataContainer().has(Pantins.PAIRE)) {
            b = avecBras(b, bras);
            change = true;
        }
        if (cache) {
            b = sansModeleDuJeu(avecDrapeau(b, CACHE_EN_MAIN));
            change = true;
        } else if (tiers) {
            b = sansModeleDuJeu(avecDrapeau(b, CACHE_TIERS), CACHE_TIERS_DU_JEU);
            change = true;
        }
        return change ? versJeu.invoke(null, b) : o;
    }

    /**
     * Marque d'un objet du serveur qui porte volontairement une définition « caché en main » (icônes de compétences de Matsuri
     * Cœur : dessinées dans la barre, rien en main) : la réparation la lui laisse. Sans elle, l'objet redevenait son objet de base
     * toutes les 2 secondes (des papiers dans la barre).
     */
    public static final org.bukkit.NamespacedKey CACHE_VOULU = new org.bukkit.NamespacedKey("vorace", "cache_en_main_voulu");

    /** Dossier des définitions « caché en main » des objets du jeu (assets/vorace/items/cache_en_main/). */
    public static final String CACHE_DU_JEU = "cache_en_main/";

    /**
     * Objet à l'apparence du jeu (épée de netherite, bouclier...) : il n'a pas de définition de vorace-item qui lise le drapeau
     * « caché en main », le jeu le dessinerait encore dans la main du joueur pendant l'animation, en plus de celui du pantin.
     * Sa copie prend la définition « caché en main » de Vorace (rien en main, l'icône du jeu dans l'inventaire).
     */
    static ItemStack sansModeleDuJeu(ItemStack o) {
        return sansModeleDuJeu(o, CACHE_DU_JEU);
    }

    /** Comme {@link #sansModeleDuJeu(ItemStack)}, avec les définitions de ce dossier (cache_en_main/ ou cache_tiers/). */
    static ItemStack sansModeleDuJeu(ItemStack o, String dossier) {
        ItemMeta m = o.getItemMeta();
        if (m == null) return o;
        org.bukkit.NamespacedKey modele = m.hasItemModel() ? m.getItemModel() : o.getType().getKey();
        if (modele == null || !"minecraft".equals(modele.getNamespace())) return o;
        // apparence choisie par un pack dans la définition du jeu (nombres ou textes de custom_model_data) : la définition de
        // Vorace ne la connaît pas, l'objet deviendrait son objet de base dans la barre ; on le laisse tel quel
        CustomModelDataComponent cmd = m.getCustomModelDataComponent();
        if (!cmd.getFloats().isEmpty() || !cmd.getStrings().isEmpty()) return o;
        org.bukkit.Material type = org.bukkit.Registry.MATERIAL.get(modele);
        if (type == null || !objetDuJeu(type)) type = o.getType();
        if (!objetDuJeu(type)) return o;
        ItemStack c = o.clone();
        ItemMeta cm = c.getItemMeta();
        cm.setItemModel(new org.bukkit.NamespacedKey(fr.vorace.objets.GenerateurPack.NS, dossier + type.getKey().getKey()));
        c.setItemMeta(cm);
        return c;
    }

    /**
     * Objets du jeu qui ont leur définition « caché en main » dans le pack de Vorace. Les blocs aussi quand Vorace a les
     * définitions du jeu de cette version (leur icône est un modèle de bloc, pas « item/<nom> ») ; sinon pas les blocs.
     */
    @SuppressWarnings("deprecation")
    public static boolean objetDuJeu(org.bukkit.Material t) {
        return !t.isLegacy() && t.isItem() && !t.isAir() && (!t.isBlock() || definitionsFiables());
    }

    private static volatile Boolean fiables;

    /**
     * Les définitions de pack-fantome/definitions-jeu-1.21.4.json sont celles du jeu de ce serveur (1.21.4). Sur une autre
     * version, elles pourraient ne plus être valides (ou des objets manquer) : on garde alors les icônes simples.
     */
    static boolean definitionsFiables() {
        Boolean f = fiables;
        if (f == null) {
            try {
                f = "1.21.4".equals(Bukkit.getMinecraftVersion());
            } catch (Throwable t) {
                f = false;   // hors serveur (tests)
            }
            fiables = f;
        }
        return f;
    }

    /** Définition « caché en main » d'un objet du jeu : son dessin dans l'inventaire, au sol, dans un cadre ; rien en main. */
    public static byte[] definitionCachee(org.bukkit.Material t) {
        return definitionCachee(t, false);
    }

    /**
     * Définition « caché en main » ({@code premiere} faux) ou « caché à la troisième personne » ({@code premiere} vrai :
     * l'objet reste aussi dessiné à la première personne) d'un objet du jeu. Là où il reste dessiné, c'est avec la vraie
     * définition du jeu (potion à sa couleur, boussole qui tourne, arc qui se bande) quand Vorace l'a pour cette version :
     * avec le pantin permanent, la barre d'objets du joueur porte ces copies tout le temps.
     */
    public static byte[] definitionCachee(org.bukkit.Material t, boolean premiere) {
        String id = t.getKey().getKey();
        String icone = switch (id) {
            case "shield" -> "{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/shield\",\"model\":{\"type\":\"minecraft:shield\"}}";
            case "compass" -> modeleJeu("compass_16");
            case "recovery_compass" -> modeleJeu("recovery_compass_16");
            case "clock" -> modeleJeu("clock_00");
            default -> modeleJeu(id);
        };
        String json = "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\",\"cases\":[{\"when\":"
            + (premiere ? "[\"gui\",\"ground\",\"fixed\",\"head\",\"firstperson_righthand\",\"firstperson_lefthand\"]"
                : "[\"gui\",\"ground\",\"fixed\",\"head\"]")
            + ",\"model\":" + (definitionsFiables() ? definitionDuJeu(id, icone) : icone) + "}],\"fallback\":{\"type\":\"minecraft:empty\"}}}";
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Définitions des objets du jeu qui ne sont pas un simple modèle (arc qui se bande, trident, teintes...), en 1.21.4. */
    private static volatile Map<String, String> definitionsJeu;

    /**
     * La définition du jeu de cet objet (l'arc se bande à la première personne, le trident est en 3D...), lue dans
     * pack-fantome/definitions-jeu-1.21.4.json (tirée des fichiers du jeu) ; à défaut, {@code icone}.
     */
    static String definitionDuJeu(String id, String icone) {
        Map<String, String> d = definitionsJeu;
        if (d == null) {
            Map<String, String> l = new java.util.HashMap<>();
            try (java.io.InputStream in = ObjetsCaches.class.getResourceAsStream("/pack-fantome/definitions-jeu-1.21.4.json")) {
                if (in != null) {
                    com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) l.put(e.getKey(), e.getValue().toString());
                }
            } catch (java.io.IOException | RuntimeException ignore) {
                // sans le fichier : les icônes simples
            }
            definitionsJeu = d = l;
        }
        return d.getOrDefault(id, icone);
    }

    private static String modeleJeu(String id) {
        return "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/" + id + "\"}";
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
        if (!cachesSoi.contains(j.getUniqueId()) && !tiersSoi.contains(j.getUniqueId()) && !pairesSoi.containsKey(j.getUniqueId())
            && !brasSoi.containsKey(j.getUniqueId())) return;
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
        boolean cacheAutres = !soi && entitesCachees.contains(id) && !lointain.test(id, voit);
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
