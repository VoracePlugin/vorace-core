package fr.vorace.modeles;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.vorace.Vorace;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
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
    /** Un seul envoi à MineSkin à la fois (limite de l'offre gratuite). */
    private final ExecutorService file = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Vorace-Peaux");
        t.setDaemon(true);
        return t;
    });
    /** Morceaux signés, par empreinte de l'image du morceau : « value » et « signature ». */
    private final Map<String, String[]> cache = new ConcurrentHashMap<>();
    /** Têtes prêtes par joueur : nom du morceau -> tête de joueur qui porte ce morceau. */
    private final Map<UUID, Map<String, ItemStack>> peaux = new ConcurrentHashMap<>();
    /** État de la préparation par joueur (pour /vorace pantin etat). */
    private final Map<UUID, String> etats = new ConcurrentHashMap<>();
    private File fichierCache;

    public Pantins(Vorace plugin) {
        this.plugin = plugin;
    }

    private String cle() {
        return plugin.getConfig().getString("pantin.mineskin-cle", "").trim();
    }

    public boolean actif() {
        return plugin.getConfig().getBoolean("pantin.actif", true);
    }

    public void charger() {
        fichierCache = new File(new File(plugin.getDataFolder(), "peaux"), "cache.yml");
        if (!fichierCache.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(fichierCache);
        for (String k : y.getKeys(false)) {
            String v = y.getString(k + ".value"), sig = y.getString(k + ".signature");
            if (v != null && sig != null) cache.put(k, new String[]{v, sig});
        }
        plugin.getLogger().info("Pantins : " + cache.size() + " morceau(x) de skin déjà signé(s)");
    }

    public void arreter() {
        file.shutdownNow();
        for (Player p : Bukkit.getOnlinePlayers()) montrer(p);
    }

    private synchronized void sauverCache(String hash, String value, String signature) {
        cache.put(hash, new String[]{value, signature});
        try {
            fichierCache.getParentFile().mkdirs();
            YamlConfiguration y = fichierCache.isFile() ? YamlConfiguration.loadConfiguration(fichierCache) : new YamlConfiguration();
            y.set(hash + ".value", value);
            y.set(hash + ".signature", signature);
            y.save(fichierCache);
        } catch (Exception e) {
            plugin.getLogger().warning("Pantins : cache des skins non sauvé : " + e.getMessage());
        }
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
     * coloré pixel par pixel ({@link PeauUV}). Rien n'est envoyé à MineSkin.
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
                    local.getParentFile().mkdirs();
                    java.nio.file.Files.write(local.toPath(), octets);
                }
                BufferedImage skin = ImageIO.read(new ByteArrayInputStream(octets));
                if (skin == null) throw new IllegalStateException("skin illisible");
                boolean fin = "slim".equals(texture[1]);
                Map<String, PeauUV.Donnees> donnees = PeauUV.donnees(squelette, skin, fin);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Map.Entry<String, PeauUV.Donnees> e : donnees.entrySet()) m.put(e.getKey(), objetPeau(PeauUV.morceauObjet(e.getKey(), fin), e.getValue()));
                    etats.put(id, "prêt");
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
                return new String[]{skin.get("url").getAsString(), modele};
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static byte[] png(BufferedImage img) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    /**
     * Envoie un morceau à MineSkin (POST /v2/generate, attend le résultat) ; renvoie {value, signature}.
     * Trop de demandes (429) : attend puis recommence (5 essais).
     */
    /** Heure (ms) avant laquelle MineSkin ne veut pas de nouvel envoi (rateLimit.next / delay de ses réponses). */
    private volatile long prochainEnvoi;

    private void attendreMineSkin() throws InterruptedException {
        long minimum = Math.max(0, plugin.getConfig().getLong("pantin.delai-ms", 3500));
        long attente = Math.max(prochainEnvoi - System.currentTimeMillis(), 0);
        Thread.sleep(Math.max(attente, minimum));
    }

    /** Lit le délai demandé par MineSkin dans sa réponse (succès ou erreur). */
    private void noterDelai(String corps) {
        try {
            JsonObject rl = JsonParser.parseString(corps).getAsJsonObject().getAsJsonObject("rateLimit");
            if (rl == null) return;
            long next = rl.has("next") && rl.getAsJsonObject("next").has("absolute") ? rl.getAsJsonObject("next").get("absolute").getAsLong() : 0;
            long delai = rl.has("delay") && rl.getAsJsonObject("delay").has("millis") ? rl.getAsJsonObject("delay").get("millis").getAsLong() : 0;
            prochainEnvoi = Math.max(next, System.currentTimeMillis() + delai) + 250;
        } catch (RuntimeException ignore) {
            // réponse sans JSON : on garde le délai de la config
        }
    }

    /** Message lisible d'une erreur MineSkin : code et texte de la première erreur. */
    static String erreurMineSkin(int statut, String corps) {
        try {
            JsonObject j = JsonParser.parseString(corps).getAsJsonObject();
            if (j.has("errors") && j.getAsJsonArray("errors").size() > 0) {
                JsonObject e = j.getAsJsonArray("errors").get(0).getAsJsonObject();
                return "MineSkin : HTTP " + statut + " " + (e.has("code") ? e.get("code").getAsString() : "") + " " + (e.has("message") ? e.get("message").getAsString() : "");
            }
        } catch (RuntimeException ignore) {
            // pas du JSON
        }
        return "MineSkin : HTTP " + statut + " " + corps.substring(0, Math.min(160, corps.length()));
    }

    private String[] mineskin(String cle, byte[] png, String nom) throws Exception {
        String limite = "----vorace" + UUID.randomUUID().toString().replace("-", "");
        String derniere = "MineSkin : trop de demandes, réessaie plus tard";
        for (int essai = 0; essai < 5; essai++) {
            attendreMineSkin();
            ByteArrayOutputStream corps = new ByteArrayOutputStream();
            champ(corps, limite, "variant", "classic");
            champ(corps, limite, "visibility", "unlisted");
            champ(corps, limite, "name", "vorace_" + nom);
            corps.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + nom + ".png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            corps.write(png);
            corps.write(("\r\n--" + limite + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(plugin.getConfig().getString("pantin.mineskin-adresse", "https://api.mineskin.org") + "/v2/generate"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + cle)
                .header("User-Agent", "Vorace/" + plugin.getPluginMeta().getVersion())
                .header("Content-Type", "multipart/form-data; boundary=" + limite)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corps.toByteArray()))
                .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            noterDelai(r.body());
            if (r.statusCode() == 429 || r.statusCode() / 100 == 5) {
                // trop de demandes ou MineSkin en difficulté : on attend de plus en plus longtemps et on recommence
                derniere = erreurMineSkin(r.statusCode(), r.body());
                plugin.getLogger().info("Pantins : " + derniere + " (essai " + (essai + 1) + "/5, nouvel essai)");
                long attente = r.headers().firstValueAsLong("retry-after").orElse(0) * 1000;
                Thread.sleep(Math.max(attente, 4000L * (essai + 1)));
                continue;
            }
            if (r.statusCode() / 100 != 2) throw new IllegalStateException(erreurMineSkin(r.statusCode(), r.body()));
            JsonObject data = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("skin")
                .getAsJsonObject("texture").getAsJsonObject("data");
            return new String[]{data.get("value").getAsString(), data.get("signature").getAsString()};
        }
        throw new IllegalStateException(derniere);
    }

    private static void champ(ByteArrayOutputStream o, String limite, String nom, String valeur) {
        o.writeBytes(("--" + limite + "\r\nContent-Disposition: form-data; name=\"" + nom + "\"\r\n\r\n" + valeur + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    private static ItemStack tete(PlayerProfile profil) {
        ItemStack t = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) t.getItemMeta();
        meta.setPlayerProfile(profil);
        t.setItemMeta(meta);
        return t;
    }

    private static ItemStack tete(String value, String signature, String hash) {
        PlayerProfile profil = Bukkit.createProfile(UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)), "vorace_peau");
        profil.setProperty(new ProfileProperty("textures", value, signature));
        return tete(profil);
    }

    // ------------------------------------------------------------------ animation

    /** Joue une animation du pantin du joueur (modèle « joueur » ou un modèle au même squelette). */
    public boolean jouer(Player p, String modele, String animation) {
        return jouer(p, modele, animation, false);
    }

    /** Arrête le pantin du joueur (fin d'une animation en boucle). */
    public void arreter(Player p) {
        if (plugin.modeles() != null) plugin.modeles().arreterJoueur(p);
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
        boolean ok = m.animerJoueur(p, modele == null || modele.isBlank() ? SQUELETTE : modele, animation, boucle, new LinkedHashMap<>(pretes),
            armure(p), () -> montrer(p));
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
        invisiblesAvant.putIfAbsent(p.getUniqueId(), p.isInvisible());
        p.setInvisible(true);
        ItemStack vide = new ItemStack(Material.AIR);
        for (Player autre : p.getWorld().getPlayers()) {
            if (autre == p || autre.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
            for (EquipmentSlot e : EMPLACEMENTS) autre.sendEquipmentChange(p, e, vide);
        }
        for (EquipmentSlot e : ARMURE_SOI) p.sendEquipmentChange(p, e, vide);
    }

    private void montrer(Player p) {
        Boolean avant = invisiblesAvant.remove(p.getUniqueId());
        if (avant == null || !p.isOnline()) return;
        p.setInvisible(avant);
        for (Player autre : p.getWorld().getPlayers()) {
            if (autre == p || autre.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
            for (EquipmentSlot e : EMPLACEMENTS) autre.sendEquipmentChange(p, e, p.getEquipment().getItem(e));
        }
        for (EquipmentSlot e : ARMURE_SOI) p.sendEquipmentChange(p, e, p.getEquipment().getItem(e));
    }

    // ------------------------------------------------------------------ événements

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
        if (plugin.modeles() != null) plugin.modeles().arreterJoueur(e.getPlayer());
        montrer(e.getPlayer());
        invisiblesAvant.remove(e.getPlayer().getUniqueId());
        peaux.remove(e.getPlayer().getUniqueId());
        etats.remove(e.getPlayer().getUniqueId());
    }
}
