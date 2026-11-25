package fr.vorace.modeles;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.vorace.Vorace;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantin des joueurs : pendant une animation (compétence, emote), le joueur est caché et remplacé par un modèle animé
 * dont chaque morceau porte son vrai skin, sans mod.
 *
 * <p>Chaque morceau du pantin (tête, moitiés du tronc, des bras et des jambes) est un modèle du pack fait d'un carré
 * par pixel, coloré avec le skin du joueur par le composant custom_model_data ({@link PeauUV}, même technique que
 * BetterModel). Tant que ses morceaux ne sont pas prêts, le pantin garde le dessin du modèle « joueur ».
 */
public final class Pantins implements Listener {

    /** Modèle du squelette du joueur (plugins/Vorace/modeles-animes/joueur.bbmodel). */
    public static final String SQUELETTE = "joueur";

    private final Vorace plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Lecture des skins en arrière-plan, une à la fois. */
    private final ExecutorService file = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Vorace-Peaux");
        t.setDaemon(true);
        return t;
    });
    /** Têtes prêtes par joueur : nom du morceau -> tête de joueur qui porte ce morceau. */
    private final Map<UUID, Map<String, ItemStack>> peaux = new ConcurrentHashMap<>();
    /** État de la préparation par joueur (pour /vorace pantin etat). */
    private final Map<UUID, String> etats = new ConcurrentHashMap<>();

    public Pantins(Vorace plugin) {
        this.plugin = plugin;
    }

    public boolean actif() {
        return plugin.getConfig().getBoolean("pantin.actif", true);
    }

    public void charger() {
        // rien à charger : les skins lus sont gardés dans peaux/<empreinte>.png
    }

    public void arreter() {
        file.shutdownNow();
        for (Player p : Bukkit.getOnlinePlayers()) montrer(p);
        if (fantomes != null) fantomes.arreter();
    }

    // ------------------------------------------------------------------ préparation

    public String etat(Player p) {
        Map<String, ItemStack> m = peaux.get(p.getUniqueId());
        String e = etats.getOrDefault(p.getUniqueId(), "pas préparé");
        return e + " (" + (m == null ? 0 : m.size()) + "/" + (Peau.MORCEAUX.size() + 1) + " morceaux prêts)";
    }

    /**
     * Prépare les morceaux du skin du joueur (en arrière-plan) : le skin est lu sur les serveurs de Mojang (gardé ensuite
     * dans plugins/Vorace/peaux/ sous son empreinte, sans nouveau téléchargement), puis chaque morceau devient un objet
     * coloré pixel par pixel ({@link PeauUV}). Aucun service extérieur.
     */
    public void preparer(Player p) {
        UUID id = p.getUniqueId();
        Map<String, ItemStack> m = new ConcurrentHashMap<>();
        peaux.put(id, m);
        String[] texture = texture(p.getPlayerProfile());
        if (texture == null) {
            etats.put(id, "skin introuvable (joueur sans skin ou serveur hors ligne) : habits par défaut");
            return;
        }
        ModeleBB squelette = plugin.modeles() == null ? null : plugin.modeles().modele(SQUELETTE);
        if (squelette == null) {
            etats.put(id, "squelette « joueur » absent : habits par défaut");
            return;
        }
        etats.put(id, "en cours");
        file.submit(() -> {
            try {
                String nom = texture[0].substring(texture[0].lastIndexOf('/') + 1).replaceAll("[^0-9a-zA-Z]", "");
                File local = new File(new File(plugin.getDataFolder(), "peaux"), nom + ".png");
                byte[] octets;
                if (local.isFile()) {
                    octets = java.nio.file.Files.readAllBytes(local.toPath());
                } else {
                    HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(texture[0])).timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() != 200) throw new IllegalStateException("skin : HTTP " + r.statusCode());
                    octets = r.body();
                    if (octets.length > 256 * 1024) throw new IllegalStateException("skin trop lourd (" + octets.length + " octets)");
                    local.getParentFile().mkdirs();
                    java.nio.file.Files.write(local.toPath(), octets);
                }
                BufferedImage skin = ImageIO.read(new ByteArrayInputStream(octets));
                if (skin == null) throw new IllegalStateException("skin illisible");
                boolean fin = "slim".equals(texture[1]);
                Map<String, PeauUV.Donnees> donnees = PeauUV.donnees(squelette, skin, fin);
                int[] bras = PeauUV.couleursBrasTenu(skin, fin);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Map.Entry<String, PeauUV.Donnees> e : donnees.entrySet()) m.put(e.getKey(), objetPeau(PeauUV.morceauObjet(e.getKey(), fin), e.getValue()));
                    etats.put(id, "prêt");
                    // bras du joueur à la première personne avec une arme de poing (vorace-item)
                    if (caches != null && p.isOnline()) caches.bras(p, bras, fin);
                });
            } catch (Exception ex) {
                etats.put(id, "erreur : " + ex.getMessage() + " (habits par défaut)");
                plugin.getLogger().warning("Pantins : skin de " + p.getName() + " : " + ex.getMessage());
            }
        });
    }

    /** Objet d'un morceau : modèle du pack (pantin_peau/...) et couleurs de ses pixels dans custom_model_data. */
    private static ItemStack objetPeau(String morceau, PeauUV.Donnees d) {
        ItemStack o = new ItemStack(Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = o.getItemMeta();
        meta.setItemModel(new org.bukkit.NamespacedKey(fr.vorace.objets.GenerateurPack.NS, PeauUV.id(morceau)));
        org.bukkit.inventory.meta.components.CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        java.util.List<org.bukkit.Color> couleurs = new java.util.ArrayList<>(d.couleurs().length);
        for (int c : d.couleurs()) couleurs.add(org.bukkit.Color.fromRGB(c & 0xFFFFFF));
        java.util.List<Boolean> drapeaux = new java.util.ArrayList<>(d.drapeaux().length);
        for (boolean b : d.drapeaux()) drapeaux.add(b);
        cmd.setColors(couleurs);
        cmd.setFlags(drapeaux);
        meta.setCustomModelDataComponent(cmd);
        o.setItemMeta(meta);
        return o;
    }

    /** URL du skin et modèle (« slim » ou « classic ») depuis la propriété « textures » du profil, ou null. */
    static String[] texture(PlayerProfile profil) {
        for (ProfileProperty pp : profil.getProperties()) {
            if (!pp.getName().equals("textures")) continue;
            try {
                JsonObject j = JsonParser.parseString(new String(Base64.getDecoder().decode(pp.getValue()), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject skin = j.getAsJsonObject("textures").getAsJsonObject("SKIN");
                String modele = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                    ? skin.getAsJsonObject("metadata").get("model").getAsString() : "classic";
                String url = skin.get("url").getAsString();
                // seulement les serveurs de skins de Mojang : un profil trafiqué ne fait pas télécharger n'importe quoi
                // au serveur (adresse interne, gros fichier)
                java.net.URI u = java.net.URI.create(url);
                String hote = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
                if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || !(hote.equals("textures.minecraft.net")
                    || hote.endsWith(".minecraft.net") || hote.endsWith(".mojang.com"))) return null;
                return new String[]{url, modele};
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }


    private static ItemStack tete(PlayerProfile profil) {
        ItemStack t = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) t.getItemMeta();
        meta.setPlayerProfile(profil);
        t.setItemMeta(meta);
        return t;
    }


    // ------------------------------------------------------------------ animation

    /** Joue une animation du pantin du joueur (modèle « joueur » ou un modèle au même squelette). */
    public boolean jouer(Player p, String modele, String animation) {
        return jouer(p, modele, animation, false);
    }

    /**
     * Rend le joueur visible quoi qu'il arrive (pantin arrêté, invisibilité retirée, armure renvoyée) : secours si un
     * joueur est resté invisible.
     */
    public void rendreVisible(Player p) {
        arreter(p);
        invisiblesAvant.remove(p.getUniqueId());
        p.setInvisible(false);
        // d'abord ne plus cacher le joueur dans les paquets, ensuite renvoyer son équipement (voir montrer)
        boolean etaitCache = caches != null && caches.cache(p);
        if (etaitCache) caches.montrer(p);
        renvoyerEquipement(p, false);
        if (!etaitCache) for (EquipmentSlot e : ARMURE_SOI) p.sendEquipmentChange(p, e, p.getEquipment().getItem(e));
        envoyerPaire(p);
    }

    // ------------------------------------------------------------------ armes par paire

    private final Map<UUID, ItemStack> dernieresPaires = new java.util.concurrent.ConcurrentHashMap<>();

    /** Joueurs à qui l'on montre une arme par paire dans la main gauche (copie, l'inventaire n'est pas touché). */
    private final java.util.Set<UUID> pairesMontrees = new java.util.HashSet<>();

    /**
     * L'arme par paire à montrer dans la main gauche de ce joueur (main gauche vide), ou null : une copie avec le
     * drapeau « paire » (vue à la troisième personne seulement : à la première personne, comme le jeu).
     */
    private ItemStack paire(Player p) {
        if (!plugin.getConfig().getBoolean("pantin.paires", true) || caches == null || invisiblesAvant.containsKey(p.getUniqueId())) return null;
        ItemStack d = p.getInventory().getItemInMainHand(), g = p.getInventory().getItemInOffHand();
        if (d == null || d.getType().isAir() || !d.hasItemMeta() || !d.getItemMeta().getPersistentDataContainer().has(PAIRE)) return null;
        return g == null || g.getType().isAir() ? ObjetsCaches.avecDrapeau(d, ObjetsCaches.PAIRE) : null;
    }

    /**
     * Arme portée par paire (armes de poing) sans rien dans l'autre main : le jeu la montre aussi dans la main gauche,
     * à tous et au joueur (une copie envoyée au client ; l'inventaire du serveur ne change pas).
     */
    public void envoyerPaire(Player p) {
        envoyerPaire(p, true);
    }

    /** @param toujours renvoyer même si rien n'a changé (sinon : seulement quand la copie montrée change) */
    private void envoyerPaire(Player p, boolean toujours) {
        if (!p.isOnline()) return;
        ItemStack copie = paire(p);
        ItemStack avant = dernieresPaires.get(p.getUniqueId());
        if (!toujours && (copie == null ? avant == null : copie.equals(avant))) return;
        if (copie == null) dernieresPaires.remove(p.getUniqueId());
        else dernieresPaires.put(p.getUniqueId(), copie);
        if (caches != null) caches.paire(p, copie);
        if (copie == null && !pairesMontrees.remove(p.getUniqueId())) return;
        if (copie != null) pairesMontrees.add(p.getUniqueId());
        ItemStack montre = copie != null ? copie : p.getInventory().getItemInOffHand();
        for (Player autre : p.getWorld().getPlayers()) {
            if (autre == p && !plugin.getConfig().getBoolean("pantin.paires-soi", true)) continue;
            if (autre != p && autre.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
            autre.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, montre);
        }
    }

    private void plusTard(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> envoyerPaire(p), 1);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireMain(org.bukkit.event.player.PlayerItemHeldEvent e) { plusTard(e.getPlayer()); }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireEchange(org.bukkit.event.player.PlayerSwapHandItemsEvent e) { plusTard(e.getPlayer()); }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireInventaire(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) plusTard(p);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireGlisser(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) plusTard(p);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireJete(org.bukkit.event.player.PlayerDropItemEvent e) { plusTard(e.getPlayer()); }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireRamassee(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) plusTard(p);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireRetour(org.bukkit.event.player.PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        pairesMontrees.remove(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> envoyerPaire(p), 5);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireMonde(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        pairesMontrees.remove(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> envoyerPaire(p), 5);
    }

    /** Un joueur en voit un autre arriver : le jeu lui envoie la main gauche vide, on lui renvoie la paire. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void paireVue(io.papermc.paper.event.player.PlayerTrackEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !pairesMontrees.contains(p.getUniqueId())) return;
        Player vu = p, voit = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ItemStack copie = paire(vu);
            if (copie != null && voit.isOnline()) voit.sendEquipmentChange(vu, EquipmentSlot.OFF_HAND, copie);
        }, 2);
    }

    /**
     * Un mob devient le double de ce joueur : son skin, son armure et ce qu'il tient, sur le modèle {@code modele} (squelette du
     * pantin ; null : celui de Vorace). Faux si le modèle manque.
     */
    public boolean double_(org.bukkit.entity.LivingEntity mob, Player p, String modele, String animation) {
        Modeles m = plugin.modeles();
        if (m == null || mob == null) return false;
        Map<String, ItemStack> pretes = peaux.get(p.getUniqueId());
        if (pretes == null) {
            preparer(p);
            pretes = peaux.getOrDefault(p.getUniqueId(), Map.of());
        }
        java.util.List<Map.Entry<String, ItemStack>> porte = armure(p);
        porte.addAll(mains(p));
        return m.attacherDouble(mob, modele == null || modele.isBlank() ? SQUELETTE : modele, new LinkedHashMap<>(pretes), porte, animation) != null;
    }

    /** Arrête le pantin du joueur (fin d'une animation en boucle). */
    public void arreter(Player p) {
        if (plugin.modeles() != null) plugin.modeles().arreterJoueur(p);
    }

    /** Arrête le pantin du joueur en douceur : retour à la pose du vrai joueur, puis échange sans saut. */
    public void relacher(Player p) {
        if (plugin.modeles() != null) plugin.modeles().relacherJoueur(p);
    }

    /** @param boucle animation répétée jusqu'à {@link #arreter(Player)} (Reprise de souffle) */
    public boolean jouer(Player p, String modele, String animation, boolean boucle) {
        Modeles m = plugin.modeles();
        if (m == null || !actif()) return false;
        Map<String, ItemStack> pretes = peaux.get(p.getUniqueId());
        if (pretes == null) {
            preparer(p);
            pretes = peaux.getOrDefault(p.getUniqueId(), Map.of());
        }
        java.util.List<Map.Entry<String, ItemStack>> porte = armure(p);
        java.util.List<Map.Entry<String, ItemStack>> tenus = mains(p);
        // arme par paire (gantelets, bandes...) : elle couvre les avant-bras ; la manche d'armure dessous ressortirait
        // par endroits pendant les coups, on ne la pose pas
        boolean paire = false;
        for (Map.Entry<String, ItemStack> t : tenus) {
            ItemStack o = t.getValue();
            if (o.hasItemMeta() && o.getItemMeta().getPersistentDataContainer().has(PAIRE)) paire = true;
        }
        if (paire) porte.removeIf(c -> c.getKey().startsWith("peau_bras_") && c.getKey().endsWith("_bas"));
        porte.addAll(tenus);
        boolean ok = m.animerJoueur(p, modele == null || modele.isBlank() ? SQUELETTE : modele, animation, boucle, new LinkedHashMap<>(pretes),
            porte, () -> montrer(p));
        if (ok) cacher(p);
        return ok;
    }

    private final Map<UUID, Boolean> invisiblesAvant = new HashMap<>();

    private static final EquipmentSlot[] ARMURE = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /**
     * Armure portée par le joueur, à poser sur son pantin : pour chaque pièce, les couches de {@link ArmuresPantin}
     * (cuir teint de sa couleur, éclat si enchantée). Une pièce sans modèle d'équipement connu (citrouille, tête...) est
     * ignorée.
     */
    java.util.List<Map.Entry<String, ItemStack>> armure(Player p) {
        java.util.List<Map.Entry<String, ItemStack>> l = new java.util.ArrayList<>();
        if (!plugin.getConfig().getBoolean("pantin.armure", true) || plugin.objets() == null) return l;
        java.util.List<String> connus = plugin.modeles() == null ? ArmuresPantin.VANILLE : plugin.modeles().armuresPantin();
        for (EquipmentSlot e : ARMURE) {
            ItemStack porte = p.getEquipment().getItem(e);
            String asset = asset(porte);
            if (asset == null || !connus.contains(asset)) continue;
            for (ArmuresPantin.Couche c : ArmuresPantin.couches(Map.of(e.name(), asset))) l.add(Map.entry(c.os(), objetArmure(c, porte)));
        }
        return l;
    }

    /** Os où le pantin tient ses objets (squelette « joueur » : au bout des avant-bras). */
    public static final String MAIN_DROITE = Modeles.MAIN + "poing_droit", MAIN_GAUCHE = Modeles.MAIN_G + "poing_gauche";

    /** Clé posée par vorace-item sur une arme portée par paire (armes de poing) : montrée dans les deux mains. */
    public static final org.bukkit.NamespacedKey PAIRE = new org.bukkit.NamespacedKey("voraceitem", "paire");

    /**
     * Objets tenus, à mettre dans les mains du pantin comme le jeu les tient (même placement qu'à la troisième
     * personne) : main droite, main gauche ; une arme « par paire » sans rien dans l'autre main va dans les deux.
     */
    java.util.List<Map.Entry<String, ItemStack>> mains(Player p) {
        java.util.List<Map.Entry<String, ItemStack>> l = new java.util.ArrayList<>();
        if (!plugin.getConfig().getBoolean("pantin.objets-en-main", true)) return l;
        ItemStack droite = p.getInventory().getItemInMainHand(), gauche = p.getInventory().getItemInOffHand();
        boolean d = droite != null && !droite.getType().isAir(), g = gauche != null && !gauche.getType().isAir();
        if (d) l.add(Map.entry(MAIN_DROITE, droite.clone()));
        if (g) l.add(Map.entry(MAIN_GAUCHE, gauche.clone()));
        else if (d && droite.hasItemMeta() && droite.getItemMeta().getPersistentDataContainer().has(PAIRE)) l.add(Map.entry(MAIN_GAUCHE, droite.clone()));
        return l;
    }

    /** Modèle d'équipement d'une pièce d'armure (« minecraft:iron »), ou null. */
    static String asset(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        try {
            org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
            if (meta != null && meta.hasEquippable() && meta.getEquippable().getModel() != null) return meta.getEquippable().getModel().toString();
        } catch (Throwable ignore) {
            // API sans composant equippable : on se sert du nom de l'objet
        }
        String n = it.getType().name();
        if (n.equals("TURTLE_HELMET")) return "minecraft:turtle_scute";
        if (!(n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS"))) return null;
        String mat = n.substring(0, n.lastIndexOf('_'));
        return switch (mat) {
            case "LEATHER" -> "minecraft:leather";
            case "CHAINMAIL" -> "minecraft:chainmail";
            case "IRON" -> "minecraft:iron";
            case "GOLDEN" -> "minecraft:gold";
            case "DIAMOND" -> "minecraft:diamond";
            case "NETHERITE" -> "minecraft:netherite";
            default -> null;
        };
    }

    private ItemStack objetArmure(ArmuresPantin.Couche c, ItemStack porte) {
        ItemStack o = plugin.objets().objetVisuel(c.id());
        if (c.teinte()) {
            o = o.withType(Material.LEATHER_HORSE_ARMOR);
            if (o.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) {
                org.bukkit.Color couleur = porte.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta pm ? pm.getColor()
                    : org.bukkit.Color.fromRGB(0xA06540);
                lm.setColor(couleur);
                o.setItemMeta(lm);
            }
        }
        if (!porte.getEnchantments().isEmpty()) {
            org.bukkit.inventory.meta.ItemMeta meta = o.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                o.setItemMeta(meta);
            }
        }
        return o;
    }

    private static final EquipmentSlot[] EMPLACEMENTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Armure que le joueur lui-même ne doit plus voir sur son vrai corps invisible (son pantin la porte). */
    private static final EquipmentSlot[] ARMURE_SOI = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /**
     * Le vrai joueur disparaît pour les autres (corps invisible, équipement vide envoyé à ceux qui le voient). Pour
     * lui-même, son armure de corps disparaît aussi (en F5 elle flotterait autour du pantin) ; ses mains ne changent pas.
     */
    private void cacher(Player p) {
        if (!plugin.getConfig().getBoolean("pantin.cacher-joueur", true)) return;
        // pendant une caméra de cinéma le joueur est spectateur, donc invisible pour le jeu : ce n'est pas son état à lui
        boolean cinema = p.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, Modeles.CLE_CAMERA));
        invisiblesAvant.putIfAbsent(p.getUniqueId(), !cinema && p.getGameMode() != org.bukkit.GameMode.SPECTATOR && p.isInvisible());
        // invisible seulement dans les paquets (les monstres le voient toujours) ; à défaut, invisible pour le serveur
        if (fantomes != null && fantomes.actif()) fantomes.cacher(p);
        else p.setInvisible(true);
        ItemStack vide = new ItemStack(Material.AIR);
        for (Player autre : p.getWorld().getPlayers()) {
            if (autre == p || autre.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
            for (EquipmentSlot e : EMPLACEMENTS) autre.sendEquipmentChange(p, e, vide);
        }
        // chez lui : ni armure sur son corps invisible (F5), ni objets en main (ils sont dans les mains de son pantin) ;
        // l'inventaire du serveur n'est pas touché, seules les copies envoyées changent (ObjetsCaches)
        if (caches != null && caches.actif() && plugin.getConfig().getBoolean("pantin.cacher-objets-soi", true)) caches.cacher(p);
        else if (!armureInventaire()) for (EquipmentSlot e : ARMURE_SOI) p.sendEquipmentChange(p, e, vide);
    }

    private ObjetsCaches caches;
    private Fantomes fantomes;

    /** Le corps fantôme est en place : l'armure reste envoyée au joueur (il la voit dans son inventaire, le shader l'efface en F5). */
    boolean armureInventaire() {
        return fantomes != null && fantomes.actif() && plugin.getConfig().getBoolean("pantin.fantome.armure-inventaire", true);
    }

    public Fantomes fantomes() {
        return fantomes;
    }

    /** Après la couche paquets de Vorace : ce que le joueur voit sur lui pendant une animation, armes par paire. */
    public void brancherObjetsCaches() {
        if (plugin.getConfig().getBoolean("pantin.fantome.actif", true)) {
            Fantomes f = new Fantomes(plugin);
            if (f.demarrer()) fantomes = f;
        }
        ObjetsCaches c = new ObjetsCaches(plugin);
        if (!c.demarrer()) return;
        c.garderArmureSoi(armureInventaire());
        caches = c;
        // filet : un joueur qui n'est plus caché par son pantin revoit tout ce qu'il porte
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (caches.cache(p) && !invisiblesAvant.containsKey(p.getUniqueId())) {
                    caches.montrer(p);
                    renvoyerEquipement(p, true);
                }
                // l'inventaire change aussi sans événement (mode combat, plugins) : la paire suit
                envoyerPaire(p, false);
            }
        }, 20, 10);
        // réparation : objets abîmés par une copie revenue du client en mode créatif (versions 1.4.3 à 1.4.5)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) reparer(p);
        }, 40, 40);
    }

    /** Remet d'aplomb les objets de l'inventaire d'un joueur marqués par Vorace (armure invisible, casque posé...). */
    public void reparer(Player p) {
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        boolean change = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack r = ObjetsCaches.reparer(inv.getItem(i));
            if (r != null) {
                inv.setItem(i, r);
                change = true;
            }
        }
        if (change) plugin.getLogger().info("Objets réparés chez " + p.getName() + " (copies de l'animation revenues en mode créatif)");
    }

    private void montrer(Player p) {
        Boolean avant = invisiblesAvant.remove(p.getUniqueId());
        if (avant == null || !p.isOnline()) return;
        if (fantomes != null && fantomes.cache(p)) fantomes.montrer(p);
        else p.setInvisible(avant);
        // D'abord retirer le joueur des entités cachées : les paquets d'équipement partent sur le fil réseau, où
        // l'écoute d'ObjetsCaches vide tout ce qui concerne une entité encore cachée. Renvoyés avant, le premier
        // (la main droite) arrivait souvent vide chez les autres et l'arme restait invisible jusqu'au changement suivant.
        boolean etaitCache = caches != null && caches.cache(p);
        if (etaitCache) caches.montrer(p);
        renvoyerEquipement(p, true);
        if (!etaitCache) for (EquipmentSlot e : ARMURE_SOI) p.sendEquipmentChange(p, e, p.getEquipment().getItem(e));
        envoyerPaire(p);
        // filet : les deux mains encore une fois au tick suivant, si aucune animation n'a repris entre-temps
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || invisiblesAvant.containsKey(p.getUniqueId())) return;
            for (Player autre : p.getWorld().getPlayers()) {
                if (autre == p || autre.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
                autre.sendEquipmentChange(p, EquipmentSlot.HAND, p.getEquipment().getItem(EquipmentSlot.HAND));
                autre.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, p.getEquipment().getItem(EquipmentSlot.OFF_HAND));
            }
            envoyerPaire(p);
        }, 1L);
    }

    /** L'équipement réel du joueur, renvoyé aux autres joueurs (à 128 blocs si {@code proches}). */
    private void renvoyerEquipement(Player p, boolean proches) {
        for (Player autre : p.getWorld().getPlayers()) {
            if (autre == p || proches && autre.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
            for (EquipmentSlot e : EMPLACEMENTS) autre.sendEquipmentChange(p, e, p.getEquipment().getItem(e));
        }
    }

    // ------------------------------------------------------------------ événements

    /**
     * Pendant une caméra de cinéma, Maj ne détache pas le joueur de la caméra (le serveur la lui redonnait aussitôt, d'où
     * de petits à-coups).
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void maj(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (e.isSneaking() && e.getPlayer().getGameMode() == org.bukkit.GameMode.SPECTATOR
            && e.getPlayer().getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, Modeles.CLE_CAMERA))) {
            e.setCancelled(true);
        }
    }

    private static final org.bukkit.NamespacedKey FIGE = new org.bukkit.NamespacedKey("vorace", "cinema_fige");

    /** Caméra de cinéma : le joueur reste monté sur la caméra (Maj ne le fait pas descendre). */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void descenteCinema(org.bukkit.event.entity.EntityDismountEvent e) {
        if (e.getEntity() instanceof Player p && p.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, Modeles.CLE_CAMERA))
            && p.isOnline() && !p.isDead()) {
            e.setCancelled(true);
        }
    }

    /** Caméra de cinéma : vitesse de marche et saut à zéro (modificateurs d'attributs, retirés ensuite). */
    static void figerMouvements(Player p, boolean oui) {
        for (org.bukkit.attribute.Attribute a : new org.bukkit.attribute.Attribute[]{org.bukkit.attribute.Attribute.MOVEMENT_SPEED,
            org.bukkit.attribute.Attribute.JUMP_STRENGTH}) {
            org.bukkit.attribute.AttributeInstance i = p.getAttribute(a);
            if (i == null) continue;
            i.removeModifier(FIGE);
            if (oui) i.addModifier(new org.bukkit.attribute.AttributeModifier(FIGE, -1, org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                org.bukkit.inventory.EquipmentSlotGroup.ANY));
        }
    }

    /** Arrêt du serveur pendant une caméra de cinéma : le joueur retrouve son mode de jeu à sa connexion. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void retourCinema(PlayerJoinEvent e) {
        figerMouvements(e.getPlayer(), false);
        org.bukkit.NamespacedKey cle = new org.bukkit.NamespacedKey(plugin, Modeles.CLE_CAMERA);
        String mode = e.getPlayer().getPersistentDataContainer().get(cle, org.bukkit.persistence.PersistentDataType.STRING);
        if (mode == null) return;
        e.getPlayer().getPersistentDataContainer().remove(cle);
        // caméra par paquet interrompue : plus intouchable, vol rendu
        e.getPlayer().setInvulnerable(false);
        if (e.getPlayer().getFlySpeed() <= 0f) e.getPlayer().setFlySpeed(0.1f);
        try {
            e.getPlayer().setGameMode(org.bukkit.GameMode.valueOf(mode));
        } catch (IllegalArgumentException ignore) {
            e.getPlayer().setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
    }

    @EventHandler
    public void arrivee(PlayerJoinEvent e) {
        if (!actif() || !plugin.getConfig().getBoolean("pantin.preparer-a-la-connexion", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) preparer(p); }, 40);
    }

    /** Mort : le pantin s'arrête (ses morceaux sont des passagers du joueur). */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void mort(org.bukkit.event.entity.PlayerDeathEvent e) {
        if (plugin.modeles() != null) plugin.modeles().arreterJoueur(e.getEntity());
    }

    @EventHandler
    public void depart(PlayerQuitEvent e) {
        pairesMontrees.remove(e.getPlayer().getUniqueId());
        dernieresPaires.remove(e.getPlayer().getUniqueId());
        if (caches != null) caches.oublier(e.getPlayer());
        if (plugin.modeles() != null) plugin.modeles().arreterJoueur(e.getPlayer());
        montrer(e.getPlayer());
        invisiblesAvant.remove(e.getPlayer().getUniqueId());
        peaux.remove(e.getPlayer().getUniqueId());
        etats.remove(e.getPlayer().getUniqueId());
    }
}
