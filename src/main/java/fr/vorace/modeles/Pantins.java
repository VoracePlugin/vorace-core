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
 * <p>Le skin du joueur est découpé en dix morceaux ({@link Peau}) ; chaque morceau devient un skin signé par MineSkin
 * (https://mineskin.org, clé dans config.yml, pantin.mineskin-cle), gardé dans plugins/Vorace/peaux/cache.yml : un
 * même morceau n'est jamais envoyé deux fois. La tête est celle du joueur. Tant que ses morceaux ne sont pas prêts,
 * le pantin garde le dessin du modèle « joueur » (habits par défaut).
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

    /** Prépare les morceaux du skin du joueur (en arrière-plan). */
    public void preparer(Player p) {
        UUID id = p.getUniqueId();
        Map<String, ItemStack> m = new ConcurrentHashMap<>();
        m.put("tete", tete(p.getPlayerProfile()));
        peaux.put(id, m);
        String cle = cle();
        if (cle.isEmpty()) {
            etats.put(id, "pas de clé MineSkin (config.yml, pantin.mineskin-cle) : habits par défaut");
            return;
        }
        String[] texture = texture(p.getPlayerProfile());
        if (texture == null) {
            etats.put(id, "skin introuvable (joueur sans skin ou serveur hors ligne) : habits par défaut");
            return;
        }
        etats.put(id, "en cours");
        file.submit(() -> {
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(texture[0])).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) throw new IllegalStateException("skin : HTTP " + r.statusCode());
                BufferedImage skin = ImageIO.read(new ByteArrayInputStream(r.body()));
                if (skin == null) throw new IllegalStateException("skin illisible");
                boolean fin = "slim".equals(texture[1]);
                int faits = 0;
                for (Map.Entry<String, BufferedImage> e : Peau.decouper(skin, fin).entrySet()) {
                    byte[] png = png(e.getValue());
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)).substring(0, 32);
                    String[] signe = cache.get(hash);
                    if (signe == null) {
                        signe = mineskin(cle, png, e.getKey());
                        sauverCache(hash, signe[0], signe[1]);
                        Thread.sleep(Math.max(0, plugin.getConfig().getLong("pantin.delai-ms", 2000)));
                    }
                    String[] s = signe;
                    String morceau = e.getKey();
                    Bukkit.getScheduler().runTask(plugin, () -> m.put(morceau, tete(s[0], s[1], hash)));
                    faits++;
                    etats.put(id, "en cours (" + faits + "/" + Peau.MORCEAUX.size() + ")");
                }
                etats.put(id, "prêt");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                etats.put(id, "erreur : " + ex.getMessage() + " (habits par défaut pour les morceaux manquants)");
                plugin.getLogger().warning("Pantins : skin de " + p.getName() + " : " + ex.getMessage());
            }
        });
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
    private String[] mineskin(String cle, byte[] png, String nom) throws Exception {
        String limite = "----vorace" + UUID.randomUUID().toString().replace("-", "");
        for (int essai = 0; essai < 5; essai++) {
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
            if (r.statusCode() == 429) {
                long attente = r.headers().firstValueAsLong("retry-after").orElse(10) * 1000;
                Thread.sleep(Math.max(2000, Math.min(60_000, attente)));
                continue;
            }
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("MineSkin : HTTP " + r.statusCode() + " " + r.body().substring(0, Math.min(200, r.body().length())));
            }
            JsonObject data = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("skin")
                .getAsJsonObject("texture").getAsJsonObject("data");
            return new String[]{data.get("value").getAsString(), data.get("signature").getAsString()};
        }
        throw new IllegalStateException("MineSkin : trop de demandes, réessaie plus tard");
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
            () -> montrer(p));
        if (ok) cacher(p);
        return ok;
    }

    private final Map<UUID, Boolean> invisiblesAvant = new HashMap<>();

    private static final EquipmentSlot[] EMPLACEMENTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Le vrai joueur disparaît pour les autres (corps invisible, équipement vide envoyé à ceux qui le voient). */
    private void cacher(Player p) {
        if (!plugin.getConfig().getBoolean("pantin.cacher-joueur", true)) return;
        invisiblesAvant.putIfAbsent(p.getUniqueId(), p.isInvisible());
        p.setInvisible(true);
        ItemStack vide = new ItemStack(Material.AIR);
        for (Player autre : p.getWorld().getPlayers()) {
            if (autre == p || autre.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
            for (EquipmentSlot e : EMPLACEMENTS) autre.sendEquipmentChange(p, e, vide);
        }
    }

    private void montrer(Player p) {
        Boolean avant = invisiblesAvant.remove(p.getUniqueId());
        if (avant == null || !p.isOnline()) return;
        p.setInvisible(avant);
        for (Player autre : p.getWorld().getPlayers()) {
            if (autre == p || autre.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
            for (EquipmentSlot e : EMPLACEMENTS) autre.sendEquipmentChange(p, e, p.getEquipment().getItem(e));
        }
    }

    // ------------------------------------------------------------------ événements

    @EventHandler
    public void arrivee(PlayerJoinEvent e) {
        if (!actif() || !plugin.getConfig().getBoolean("pantin.preparer-a-la-connexion", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) preparer(p); }, 40);
    }

    @EventHandler
    public void depart(PlayerQuitEvent e) {
        montrer(e.getPlayer());
        invisiblesAvant.remove(e.getPlayer().getUniqueId());
        peaux.remove(e.getPlayer().getUniqueId());
        etats.remove(e.getPlayer().getUniqueId());
    }
}
