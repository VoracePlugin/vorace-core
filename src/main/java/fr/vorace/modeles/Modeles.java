package fr.vorace.modeles;

import fr.vorace.Vorace;
import fr.vorace.modeles.Animateur.Pose;
import fr.vorace.modeles.ModeleBB.Animation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Modèles animés (rôle de ModelEngine) : chaque os d'un .bbmodel est une entité d'affichage dont la transformation
 * suit l'animation. Les entités d'affichage n'existent que si un joueur est à portée (modeles.distance-affichage) ;
 * les animations se fondent entre elles, les dégâts et attaques se jouent par-dessus la marche, la tête suit le regard. Un modèle est soit posé (une entité Interaction sert de base et de hitbox, sauvegardée avec
 * le monde), soit porté par un mob (rendu invisible) qui joue tout seul « repos », « marche », « attaque »...
 */
public final class Modeles {

    /** Noms d'animations reconnus automatiquement (anglais de ModelEngine et français). */
    public static final Map<String, List<String>> AUTO = Map.ofEntries(
        Map.entry("repos", List.of("idle", "repos", "attente")),
        Map.entry("marche", List.of("walk", "marche", "walking")),
        Map.entry("course", List.of("run", "course", "running")),
        Map.entry("degat", List.of("hurt", "degat", "damage")),
        Map.entry("mort", List.of("death", "mort", "die")),
        Map.entry("attaque", List.of("attack", "attaque")),
        Map.entry("clic", List.of("interact", "clic", "interaction")),
        Map.entry("saisi", List.of("saisi", "grabbed", "grab")),
        Map.entry("plaque", List.of("plaque", "slam", "slammed")),
        Map.entry("souleve", List.of("souleve", "launched", "launch")),
        Map.entry("projete", List.of("projete", "thrown", "throw")),
        Map.entry("repousse", List.of("repousse", "knockback", "pushed")),
        Map.entry("etourdi", List.of("etourdi", "stunned", "stun")),
        Map.entry("resiste", List.of("resiste", "resist")));

    /** Réactions qu'une compétence peut demander à un modèle (VoraceAPI.reaction). */
    public static final List<String> REACTIONS = List.of("saisi", "plaque", "souleve", "projete", "repousse", "etourdi", "resiste");

    private final Vorace plugin;
    private final NamespacedKey clePose, cleMob, clePartie, cleTaille;
    /** Réglages (config.yml, section modeles). */
    private int intervalle = 2, distance = 48, fonduTics = 4;
    private boolean teinteDegats = true, suiviTete = true, effetsActifs = true;
    /** Pantin des joueurs : rotation (degrés) et décalage vertical (blocs) des têtes de joueur qui portent le skin. */
    private float peauRotationY = 0f, peauDecalageY = 0.25f;
    /** Pantin monté sur le joueur (passagers) : il suit le joueur sans aucun retard, même pour lui-même. */
    private boolean pantinMonte = true;
    /**
     * Tête du pantin pour le joueur lui-même. Le jeu dessine les têtes de joueur sans élaguer les faces : quand la tête
     * du pantin entoure sa caméra (première personne), il en verrait l'intérieur sur tout l'écran. Elle lui est donc
     * cachée tant qu'elle est autour de ses yeux, et remplacée par sa propre tête portée en casque sur son vrai corps
     * (invisible) : le jeu ne dessine jamais son casque à la première personne, mais bien en F5. Dès que la tête du
     * pantin s'éloigne des yeux (saut d'un ultime), elle redevient visible et le casque disparaît. Les autres joueurs
     * voient toujours la tête du pantin.
     */
    private boolean pantinCacherTete = false;
    /**
     * Copie du pantin pour son propre joueur, reculée de quelques pixels (comme le mod « First Person Model ») : à la
     * première personne il voit ses bras et ses poings partir devant lui, sans avoir les épaules dans la caméra. Les autres
     * voient le pantin à sa vraie place. 0 : pas de copie.
     */
    private float pantinRecul = 4f;
    /** Caméra de cinéma en montant le joueur sur un support (barre d'objets gardée, moins fluide) au lieu du mode spectateur. */
    private boolean cameraMonture;
    /** Caméra de cinéma pour les animations qui ont un os « camera » animé (ultimes). */
    private boolean cameraCinema = true;
    /** Préfixes de nom d'os des objets tenus par le pantin (« main:poing_droit ») dans la liste des couches d'armure. */
    public static final String MAIN = "main:", MAIN_G = "main_gauche:";

    /**
     * Orientation d'un objet tenu dans le repère de l'avant-bras du modèle (Blockbench : y en haut, droite en +x, avant
     * en -z) : celle du jeu (ItemInHandLayer : rotation X -90°, puis Y 180°, dans le repère des modèles d'entité, y en
     * bas et x inversé), suivie du passage de ce repère à celui de Blockbench (demi-tour autour de z).
     */
    static final Quaternionf TENUE;
    static {
        Quaternionf c = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf jeu = new Quaternionf().rotateX((float) (-Math.PI / 2)).rotateY((float) Math.PI);
        TENUE = new Quaternionf(c).mul(jeu);
    }

    /** Mode de jeu du joueur avant une caméra de cinéma (remis à sa connexion si le serveur s'est arrêté pendant). */
    public static final String CLE_CAMERA = "camera_mode";
    private volatile Map<String, ModeleBB> modeles = Map.of();
    private final Map<UUID, Instance> instances = new LinkedHashMap<>();
    /** Ce que vise chaque joueur (point du monde, heure limite) : ses prochaines apparitions volent jusque-là. */
    private final Map<UUID, Object[]> visees = new HashMap<>();
    private BukkitTask tache;
    private long tic;
    /** Dernière erreur du calcul des animations (affichée par /vorace modele info). */
    private volatile String derniereErreur;
    private boolean erreurJournalisee;

    public Modeles(Vorace plugin) {
        this.plugin = plugin;
        clePose = new NamespacedKey(plugin, "modele");
        cleMob = new NamespacedKey(plugin, "modele_mob");
        clePartie = new NamespacedKey(plugin, "modele_partie");
        cleTaille = new NamespacedKey(plugin, "modele_taille");
    }

    public Path dossier() { return plugin.getDataFolder().toPath().resolve("modeles-animes"); }
    public Collection<ModeleBB> tous() { return modeles.values(); }
    public ModeleBB modele(String id) { return id == null ? null : modeles.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<Instance> instances() { return instances.values(); }
    public long tics() { return tic; }
    public boolean actif() { return tache != null && !tache.isCancelled(); }
    public String derniereErreur() { return derniereErreur; }

    // ------------------------------------------------------------------ chargement

    public void charger() {
        lireReglages();
        installerExemple();
        Map<String, ModeleBB> lus = new LinkedHashMap<>();
        List<String> problemes = new ArrayList<>();
        List<Path> fichiers;
        try (Stream<Path> f = Files.walk(dossier())) {
            fichiers = f.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel")).sorted().toList();
        } catch (IOException e) {
            fichiers = List.of();
        }
        for (Path p : fichiers) {
            String nom = dossier().relativize(p).toString().replace('\\', '/');
            String id = nom.substring(0, nom.length() - ".bbmodel".length()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
            try {
                ModeleBB m = LecteurBBModel.lire(id, Files.readString(p, StandardCharsets.UTF_8), problemes);
                lus.put(id, m);
            } catch (Exception e) {
                problemes.add(nom + " : fichier illisible (" + e.getMessage() + ")");
            }
        }
        modeles = lus;
        problemes.forEach(m -> plugin.getLogger().warning("Modèles animés : " + m));
        plugin.getLogger().info(lus.size() + " modèle(s) animé(s) chargé(s)");
        if (Bukkit.isPrimaryThread()) for (Instance i : new ArrayList<>(instances.values())) i.reconstruire();
    }

    /** Identifiants des objets d'apparence de toutes les parties visibles (pour le pack). */
    public List<String> idsParties() {
        List<String> ids = new ArrayList<>();
        for (ModeleBB m : modeles.values()) {
            for (int i = 0; i < m.parties().size(); i++) if (m.parties().get(i).visible()) ids.add(GenerateurModeles.idPartie(m.id(), i));
        }
        return ids;
    }

    public Map<String, byte[]> fichiersPack() {
        Map<String, byte[]> f = new java.util.TreeMap<>();
        for (ModeleBB m : modeles.values()) f.putAll(GenerateurModeles.generer(m));
        f.putAll(ArmuresPantin.generer(modele(Pantins.SQUELETTE), armuresPantin()));
        f.putAll(PeauUV.generer(modele(Pantins.SQUELETTE)));
        // objets du jeu tenus pendant une animation du pantin : rien en main, leur icône dans l'inventaire (ObjetsCaches)
        for (org.bukkit.Material t : org.bukkit.Material.values()) {
            if (ObjetsCaches.objetDuJeu(t)) {
                f.put("assets/" + fr.vorace.objets.GenerateurPack.NS + "/items/" + ObjetsCaches.CACHE_DU_JEU + t.getKey().getKey() + ".json",
                    ObjetsCaches.definitionCachee(t));
            }
        }
        // case de la main gauche à côté de la barre d'objets : transparente (arme par paire montrée en F5 sans case)
        if (plugin.getConfig().getBoolean("pantin.cacher-case-main-gauche", true)) {
            for (String c : List.of("hotbar_offhand_left", "hotbar_offhand_right")) {
                f.put("assets/minecraft/textures/gui/sprites/hud/" + c + ".png", imageVide(29, 24));
            }
        }
        return f;
    }

    private static byte[] imageVide(int l, int h) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(l, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", o);
            return o.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Armures que le pantin sait porter : celles du jeu et celles de pantin.armures-perso (« babel:mythril »). */
    public List<String> armuresPantin() {
        List<String> l = new ArrayList<>(ArmuresPantin.VANILLE);
        for (String a : plugin.getConfig().getStringList("pantin.armures-perso")) {
            String n = a.toLowerCase(Locale.ROOT).trim();
            if (n.isEmpty()) continue;
            if (!n.contains(":")) n = "minecraft:" + n;
            if (!l.contains(n)) l.add(n);
        }
        return l;
    }

    /** Objets d'armure du pantin à déclarer dans le pack (identifiant -> teint). */
    public Map<String, Boolean> idsArmuresPantin() {
        return ArmuresPantin.ids(modele(Pantins.SQUELETTE), armuresPantin());
    }

    public void demarrer() {
        lireReglages();
        tache = Bukkit.getScheduler().runTaskTimer(plugin, this::tic, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) charger(w.getEntities());
        });
    }

    public void arreter() {
        if (tache != null) tache.cancel();
        for (Instance i : new ArrayList<>(instances.values())) {
            i.supprimerParties();
            i.finir();
        }
        instances.clear();
    }

    /**
     * Le pantin d'un joueur joue une animation de ce modèle (« joueur », ou un modèle qui a le même squelette).
     * Les os « peau_... » portent les têtes de {@code peaux} (nom sans « peau_ » : tete, corps_haut...) ; sans tête
     * prête, la partie garde le dessin du modèle. Le pantin disparaît à la fin de l'animation ; {@code fin} est appelé.
     *
     * @return faux si le modèle ou l'animation n'existe pas
     */
    public boolean animerJoueur(Player joueur, String id, String animation, Map<String, ItemStack> peaux, Runnable fin) {
        return animerJoueur(joueur, id, animation, false, peaux, fin);
    }

    /** Arrête l'animation du pantin du joueur (animation en boucle : Reprise de souffle) ; il disparaît. */
    public void arreterJoueur(Player joueur) {
        Instance i = instances.get(joueur.getUniqueId());
        if (i == null || !i.pantin) return;
        i.supprimerParties();
        instances.remove(joueur.getUniqueId());
        i.finir();
    }

    /** @param boucle l'animation recommence jusqu'à {@link #arreterJoueur} */
    public boolean animerJoueur(Player joueur, String id, String animation, boolean boucle, Map<String, ItemStack> peaux, Runnable fin) {
        return animerJoueur(joueur, id, animation, boucle, peaux, List.of(), fin);
    }

    /**
     * @param armure couches d'armure : nom d'os (« peau_corps_haut ») et objet affiché (modèle de {@link ArmuresPantin}),
     *               posées par-dessus les morceaux du skin et animées avec eux
     */
    public boolean animerJoueur(Player joueur, String id, String animation, boolean boucle, Map<String, ItemStack> peaux,
                                List<Map.Entry<String, ItemStack>> armure, Runnable fin) {
        ModeleBB m = modele(id);
        if (m == null) return false;
        Instance ancien = instances.get(joueur.getUniqueId());
        if (ancien != null && ancien.pantin && ancien.modele == m && ancien.trouver(animation) != null && ancien.memeArmure(armure)) {
            // coups enchaînés : le même pantin joue la suite, sans disparaître entre deux coups
            ancien.demarrer(animation, boucle, false);
            ancien.debutCinema(animation);
            return true;
        }
        if (ancien != null) {
            ancien.supprimerParties();
            instances.remove(joueur.getUniqueId());
            ancien.finir();
        }
        Instance i = new Instance(m, joueur, true, joueur.getLocation().getYaw());
        if (i.trouver(animation) == null) return false;
        i.pantin = true;
        i.proprietaire = joueur.getUniqueId();
        i.fin = fin;
        for (int k = 0; k < m.parties().size(); k++) {
            String n = m.parties().get(k).nom() == null ? "" : m.parties().get(k).nom().toLowerCase(Locale.ROOT);
            if (n.startsWith("peau_") && peaux.get(n.substring(5)) != null) i.peaux.put(k, peaux.get(n.substring(5)));
        }
        for (Map.Entry<String, ItemStack> c : armure) {
            String os = c.getKey();
            int main = os.startsWith(MAIN_G) ? 2 : os.startsWith(MAIN) ? 1 : 0;
            if (main == 2) os = os.substring(MAIN_G.length());
            else if (main == 1) os = os.substring(MAIN.length());
            for (int k = 0; k < m.parties().size(); k++) {
                if (!os.equalsIgnoreCase(m.parties().get(k).nom())) continue;
                i.armureOs.add(k);
                i.armureObjets.add(c.getValue());
                i.armureMain.add(main);
                break;
            }
        }
        instances.put(joueur.getUniqueId(), i);
        i.demarrer(animation, boucle, false);
        i.debutCinema(animation);
        return true;
    }

    /**
     * Apparition : le modèle est posé le temps d'une seule animation, puis il disparaît (phénix, météorite ou titan
     * d'un ultime). Rien n'est gardé avec le monde. Depuis une animation, la clé de script (piste « Effets »,
     * timeline) {@code apparition <modèle> <animation> [taille]} le fait apparaître à l'endroit du modèle qui la joue,
     * tourné comme lui : on dessine donc l'apparition dans le même repère que l'animation qui l'appelle.
     *
     * @return null si le modèle ou l'animation n'existe pas
     */
    public Instance apparition(String id, Location ou, float yaw, String animation, float taille) {
        ModeleBB m = modele(id);
        if (m == null || ou == null || ou.getWorld() == null) return null;
        Location l = ou.clone();
        l.setYaw(yaw);
        l.setPitch(0);
        org.bukkit.entity.Marker base = l.getWorld().spawn(l, org.bukkit.entity.Marker.class, e -> e.setPersistent(false));
        Instance i = new Instance(m, base, false, yaw);
        if (i.trouver(animation) == null) { base.remove(); return null; }
        i.pantin = true;  // comme un pantin : il ne joue que cette animation et disparaît à la fin
        i.taille = Math.max(0.05f, Math.min(16f, taille));
        i.fin = base::remove;
        instances.put(base.getUniqueId(), i);
        i.demarrer(animation, false, false);
        return i;
    }

    /**
     * Donne aux prochaines apparitions du pantin de ce joueur un point à atteindre (le torse de sa cible) : un sort dessiné
     * pour une cible à deux blocs vole alors jusqu'à ce point, à n'importe quelle distance et hauteur (voir {@link Visee}).
     * Vaut deux secondes ; {@code null} l'efface (une compétence sans cible, un ultime).
     */
    public void viser(Player joueur, Location cible) {
        if (joueur == null) return;
        if (cible == null || cible.getWorld() == null) visees.remove(joueur.getUniqueId());
        else visees.put(joueur.getUniqueId(), new Object[] {cible.clone(), System.currentTimeMillis() + 2000});
    }

    /** Le point visé par ce joueur s'il est encore valable et dans ce monde, sinon null. */
    private Location visee(UUID joueur, org.bukkit.World monde) {
        Object[] v = joueur == null ? null : visees.get(joueur);
        if (v == null) return null;
        if ((Long) v[1] < System.currentTimeMillis()) {
            visees.remove(joueur);
            return null;
        }
        Location l = (Location) v[0];
        return l.getWorld() == monde ? l : null;
    }

    /** Le joueur a un pantin en train de jouer. */
    public boolean pantinActif(Player joueur) {
        Instance i = instances.get(joueur.getUniqueId());
        return i != null && i.pantin;
    }

    // ------------------------------------------------------------------ création

    /** Pose un modèle (sauvegardé avec le monde). */
    public Instance poser(String id, Location ou, float yaw, String animation) {
        ModeleBB m = modele(id);
        if (m == null) return null;
        Location l = ou.clone();
        l.setYaw(yaw);
        l.setPitch(0);
        float largeur = (float) Math.max(0.3, Math.min(8, m.largeur() / 16.0)), hauteur = (float) Math.max(0.3, Math.min(16, m.hauteur() / 16.0));
        Interaction base = l.getWorld().spawn(l, Interaction.class, e -> {
            e.setInteractionWidth(largeur);
            e.setInteractionHeight(hauteur);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(clePose, PersistentDataType.STRING, m.id() + "|" + (animation == null ? "" : animation) + "|" + yaw);
        });
        Instance i = new Instance(m, base, false, yaw);
        instances.put(base.getUniqueId(), i);
        i.reconstruire();
        if (animation != null && !animation.isBlank()) i.jouer(animation, false);
        return i;
    }

    /** Un mob porte le modèle : il devient invisible et le modèle suit ses mouvements. */
    public Instance attacher(LivingEntity mob, String id) {
        ModeleBB m = modele(id);
        if (m == null) return null;
        Instance ancien = instances.get(mob.getUniqueId());
        if (ancien != null) ancien.supprimerParties();
        mob.getPersistentDataContainer().set(cleMob, PersistentDataType.STRING, m.id());
        mob.setInvisible(true);
        Instance i = new Instance(m, mob, true, mob.getLocation().getYaw());
        Float t = mob.getPersistentDataContainer().get(cleTaille, PersistentDataType.FLOAT);
        if (t != null) i.taille = Math.max(0.05f, Math.min(16f, t));
        instances.put(mob.getUniqueId(), i);
        i.reconstruire();
        return i;
    }

    public void retirer(Instance i) {
        i.supprimerParties();
        instances.remove(i.base.getUniqueId());
        if (i.mob) {
            i.base.getPersistentDataContainer().remove(cleMob);
            i.base.getPersistentDataContainer().remove(cleTaille);
            if (i.base instanceof LivingEntity le) le.setInvisible(false);
        } else {
            i.base.remove();
        }
    }

    public Instance instance(Entity base) {
        return base == null ? null : instances.get(base.getUniqueId());
    }

    /** Instance la plus proche d'un endroit, ou null. */
    public Instance plusProche(Location l, double rayon) {
        Instance meilleur = null;
        double d = rayon * rayon;
        for (Instance i : instances.values()) {
            if (!i.base.getWorld().equals(l.getWorld())) continue;
            double x = i.base.getLocation().distanceSquared(l);
            if (x <= d) { d = x; meilleur = i; }
        }
        return meilleur;
    }

    /** Recrée les modèles des entités chargées (démarrage, chargement d'un chunk). */
    public void charger(Collection<? extends Entity> entites) {
        for (Entity e : entites) {
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            if (e instanceof Display && pdc.has(clePartie, PersistentDataType.BYTE)) { e.remove(); continue; } // reste d'un arrêt brutal
            if (instances.containsKey(e.getUniqueId())) continue;
            String pose = pdc.get(clePose, PersistentDataType.STRING);
            if (pose != null && e instanceof Interaction) {
                String[] p = pose.split("\\|", -1);
                ModeleBB m = modele(p[0]);
                if (m == null) continue;
                float yaw = 0;
                try { yaw = p.length > 2 ? Float.parseFloat(p[2]) : e.getLocation().getYaw(); } catch (NumberFormatException ignore) { }
                Instance i = new Instance(m, e, false, yaw);
                try { if (p.length > 3) i.taille = Math.max(0.05f, Math.min(16f, Float.parseFloat(p[3]))); } catch (NumberFormatException ignore) { }
                instances.put(e.getUniqueId(), i);
                i.reconstruire();
                if (p.length > 1 && !p[1].isBlank()) i.jouer(p[1], false);
                continue;
            }
            String mob = pdc.get(cleMob, PersistentDataType.STRING);
            if (mob != null && e instanceof LivingEntity le && !le.isDead()) {
                ModeleBB m = modele(mob);
                if (m == null) continue;
                Instance i = new Instance(m, e, true, e.getLocation().getYaw());
                Float t = pdc.get(cleTaille, PersistentDataType.FLOAT);
                if (t != null) i.taille = Math.max(0.05f, Math.min(16f, t));
                instances.put(e.getUniqueId(), i);
                i.reconstruire();
            }
        }
    }

    // ------------------------------------------------------------------ animation

    /** Relit les réglages de la section « modeles » de config.yml. */
    void lireReglages() {
        try {
            intervalle = Math.max(1, Math.min(5, plugin.getConfig().getInt("modeles.intervalle-tics", 2)));
            distance = Math.max(0, plugin.getConfig().getInt("modeles.distance-affichage", 48));
            fonduTics = (int) Math.round(Math.max(0, Math.min(2, plugin.getConfig().getDouble("modeles.fondu-secondes", 0.2))) * 20);
            teinteDegats = plugin.getConfig().getBoolean("modeles.teinte-degats", true);
            suiviTete = plugin.getConfig().getBoolean("modeles.suivi-tete", true);
            effetsActifs = plugin.getConfig().getBoolean("modeles.effets", true);
            peauRotationY = (float) plugin.getConfig().getDouble("pantin.correction.rotation-y", 0);
            peauDecalageY = (float) plugin.getConfig().getDouble("pantin.correction.decalage-y", 0.25);
            pantinMonte = plugin.getConfig().getBoolean("pantin.monter-sur-le-joueur", true);
            pantinCacherTete = plugin.getConfig().getBoolean("pantin.cacher-sa-tete", false);
            pantinRecul = (float) Math.max(0, Math.min(12, plugin.getConfig().getDouble("pantin.recul-pour-soi", 4)));
            cameraCinema = plugin.getConfig().getBoolean("pantin.camera-ultimes", true);
            cameraMonture = "monture".equalsIgnoreCase(plugin.getConfig().getString("pantin.camera-mode", "spectateur"));
        } catch (Throwable t) {
            intervalle = 2;
        }
    }

    private void tic() {
        tic++;
        boolean verifier = tic % 10 == 0;
        Map<World, List<Player>> joueurs = new HashMap<>();
        for (Instance i : new ArrayList<>(instances.values())) {
            try {
                if (verifier || i.distanceJoueur < 0) i.verifierDistance(joueurs);
                if (i.endormi) {
                    // personne ne regarde : aucune entité d'affichage, aucun calcul ; seulement la fin du modèle
                    if (i.fixe != null || !i.base.isValid() || i.base.isDead() || (i.pantin && i.forcee == null)) {
                        i.supprimerParties();
                        instances.remove(i.base.getUniqueId());
                        i.finir();
                    }
                    continue;
                }
                int iv = i.pantin && i.mob ? 1 : i.lointain ? Math.min(10, intervalle * 2) : intervalle;  // pantin d'un joueur : chaque tic
                if (!i.mettreAJour(iv)) {
                    i.supprimerParties();
                    instances.remove(i.base.getUniqueId());
                    i.finir();
                }
            } catch (Throwable ex) {
                // une erreur (même de type Error) ne doit jamais figer tous les modèles : on la note une fois et on continue
                derniereErreur = i.modele.id() + " : " + ex;
                if (!erreurJournalisee) {
                    erreurJournalisee = true;
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Animation du modèle " + i.modele.id() + " impossible", ex);
                }
            }
        }
    }

    /** Rôles automatiques joués par-dessus l'animation en cours (seuls les os qu'ils animent changent). */
    static final java.util.Set<String> PAR_DESSUS = java.util.Set.of("degat", "attaque", "clic");

    /** Un modèle en jeu. */
    public final class Instance {
        final ModeleBB modele;
        final Entity base;
        final boolean mob;
        float yaw;
        ItemDisplay[] parties = new ItemDisplay[0];
        private TextDisplay[] etiquettes = new TextDisplay[0];
        private ItemStack[] objets = new ItemStack[0], rouges;
        private final Transformation[] envoyees;
        private final boolean[] estEtiquette;
        private final boolean aEtiquettes;
        /** Animation demandée (commande, API, clic, dégâts) ; null = animation automatique. */
        private String forcee;
        private boolean forceeBoucle, forceePartielle;
        private long debutForcee;
        private String auto;
        private long debutAuto;
        /** Animation complète affichée, et la précédente pendant le fondu. */
        private String pleine, avant;
        private long debutPleine, debutAvant, debutFondu = Long.MIN_VALUE / 2;
        private boolean pleineBoucle, avantBoucle;
        /** Derniers temps vus pour les clés d'effet (animation complète, animation par-dessus). */
        private double tEffetPleine = -1e-9, tEffetDessus = -1e-9;
        private String effetDessusCle;
        private Location derniere;
        /** Mort : la base n'existe plus, le modèle reste ici le temps de l'animation. */
        private Location fixe;
        /** Joueurs qui voient le modèle ; null = tout le monde. */
        private java.util.Set<UUID> spectateurs;
        private float taille = 1f;
        /** Personne à portée : les entités d'affichage sont retirées. */
        boolean endormi = true;
        /** Joueur le plus proche au-delà de la moitié de la distance d'affichage : images deux fois moins souvent. */
        boolean lointain;
        double distanceJoueur = -1;
        private long finTeinte = -1;
        private Pose[] dernieresPoses;
        /** Regard imposé par l'API (degrés, par rapport au corps) ; NaN = celui du mob. */
        private float regardYaw = Float.NaN, regardPitch = Float.NaN;
        /** Pantin d'un joueur : disparaît à la fin de son animation, ses os « peau_ » portent des têtes de joueur. */
        boolean pantin;
        UUID proprietaire;
        /** Apparition visée : ses pièces volent jusqu'à une vraie cible (null : jouée telle qu'elle est dessinée). */
        Visee visee;
        Runnable fin;
        final Map<Integer, ItemStack> peaux = new HashMap<>();

        /** Appelé une fois quand le modèle disparaît (pantin : le joueur redevient visible). */
        void finir() {
            finCinema();
            Runnable r = fin;
            fin = null;
            if (r != null) {
                try { r.run(); } catch (Throwable t) { plugin.getLogger().warning("Fin du pantin : " + t); }
            }
        }

        Instance(ModeleBB modele, Entity base, boolean mob, float yaw) {
            this.modele = modele;
            this.base = base;
            this.mob = mob;
            this.yaw = yaw;
            int n = modele.parties().size();
            this.envoyees = new Transformation[n];
            this.estEtiquette = new boolean[n];
            boolean e = false;
            for (int k = 0; k < n; k++) { estEtiquette[k] = modele.parties().get(k).etiquette(); e |= estEtiquette[k]; }
            this.aEtiquettes = e;
        }

        public ModeleBB modele() { return modele; }
        public Entity base() { return base; }
        public boolean surMob() { return mob; }
        public String animation() { return forcee != null ? forcee : auto; }
        public float taille() { return taille; }
        /** Vrai si aucun joueur n'est à portée (le modèle n'a pas d'entité d'affichage en ce moment). */
        public boolean endormi() { return endormi; }

        /** Seuls ces joueurs voient le modèle (null ou vide : tout le monde). */
        public void visiblePour(Collection<? extends Player> joueurs) {
            spectateurs = joueurs == null || joueurs.isEmpty() ? null : new java.util.HashSet<>(joueurs.stream().map(Player::getUniqueId).toList());
            appliquerVisibilite();
            distanceJoueur = -1; // revérifie la portée avec les nouveaux spectateurs
        }

        public boolean prive() { return spectateurs != null; }

        public boolean voit(UUID joueur) { return spectateurs == null || spectateurs.contains(joueur); }

        private List<Display> affichages() {
            List<Display> l = new ArrayList<>(parties.length + etiquettes.length);
            for (ItemDisplay d : parties) if (d != null && d.isValid()) l.add(d);
            for (ItemDisplay d : armures) if (d != null && d.isValid()) l.add(d);
            for (ItemDisplay d : partiesSoi) if (d != null && d.isValid()) l.add(d);
            for (ItemDisplay d : armuresSoi) if (d != null && d.isValid()) l.add(d);
            for (TextDisplay d : etiquettes) if (d != null && d.isValid()) l.add(d);
            return l;
        }

        void appliquerVisibilite() {
            for (Display d : affichages()) cacher(d);
        }

        private void cacher(Display d) {
            d.setVisibleByDefault(spectateurs == null);
            if (spectateurs != null) for (UUID u : spectateurs) {
                Player j = Bukkit.getPlayer(u);
                if (j != null) j.showEntity(plugin, d);
            }
        }

        public int partiesValides() {
            int n = 0;
            for (ItemDisplay d : parties) if (d != null && d.isValid()) n++;
            return n;
        }

        /** Résumé de l'état réel des entités d'affichage (pour /vorace modele info). */
        public String etat() {
            double somme = 0;
            int n = 0;
            for (ItemDisplay d : parties) {
                if (d == null || !d.isValid()) continue;
                Transformation tr = d.getTransformation();
                Vector3f v = tr.getTranslation();
                Quaternionf q = tr.getLeftRotation();
                somme += v.x * 1.3 + v.y * 1.7 + v.z * 1.9 + q.x * 2.3 + q.y * 2.9 + q.z * 3.1 + q.w * 3.7;
                n++;
                if (n == 1) {
                    somme += d.getInterpolationDuration() * 1000;
                }
            }
            long tAnim = forcee != null ? tic - debutForcee : tic - debutAuto;
            String vue = endormi ? "endormi (aucun joueur à " + distance + " blocs)" : lointain ? "lointain" : "proche";
            return "animation=" + animation() + (forcee != null ? " (demandée" + (forceeBoucle ? ", en boucle" : "") + (forceePartielle ? ", par-dessus" : "") + ")" : " (auto)")
                + " t=" + String.format(Locale.ROOT, "%.2f", tAnim / 20.0) + "s parties=" + n + "/" + modele.parties().stream().filter(ModeleBB.Partie::visible).count()
                + " taille=" + String.format(Locale.ROOT, "%.2f", taille) + " vue=" + vue
                + " empreinte=" + String.format(Locale.ROOT, "%.4f", somme);
        }

        // -------------------------------------------------------------- portée

        /** Joueur le plus proche (parmi ceux qui voient le modèle) : endort ou réveille le modèle. */
        void verifierDistance(Map<World, List<Player>> cache) {
            Location l = position();
            World w = l.getWorld();
            double meilleur = Double.MAX_VALUE;
            if (w != null) {
                for (Player j : cache.computeIfAbsent(w, World::getPlayers)) {
                    if (!voit(j.getUniqueId())) continue;
                    meilleur = Math.min(meilleur, j.getLocation().distanceSquared(l));
                }
            }
            distanceJoueur = meilleur == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(meilleur);
            if (distance <= 0) {
                lointain = false;
                if (endormi) reveiller();
                return;
            }
            // un peu de marge pour ne pas faire apparaître et disparaître le modèle à chaque pas à la limite
            double rayon = distance + modele.largeur() / 16.0 * taille;
            if (endormi ? distanceJoueur <= rayon : distanceJoueur <= rayon + 4) {
                lointain = distanceJoueur > rayon / 2;
                if (endormi) reveiller();
            } else if (!endormi) {
                supprimerParties();
                endormi = true;
            }
        }

        private void reveiller() {
            endormi = false;
            reconstruire();
        }

        private float porteeVue() {
            // view_range 1 = 64 blocs (réglage « distance des entités » du joueur à 100 %)
            return distance <= 0 ? 2f : (float) Math.max(0.5, (distance + 8) / 64.0);
        }

        // -------------------------------------------------------------- entités

        void reconstruire() {
            supprimerParties();
            if (endormi) return;
            Location l = position();
            l.setYaw(0);
            l.setPitch(0);
            int n = modele.parties().size();
            parties = new ItemDisplay[n];
            etiquettes = new TextDisplay[aEtiquettes ? n : 0];
            objets = new ItemStack[n];
            rouges = null;
            finTeinte = -1;
            java.util.Arrays.fill(envoyees, null);
            float vue = porteeVue();
            for (int k = 0; k < n; k++) {
                if (!modele.parties().get(k).visible()) continue;
                String id = GenerateurModeles.idPartie(modele.id(), k);
                ItemStack peau = peaux.get(k);
                ItemStack objet = peau != null ? peau : plugin.objets().objetVisuel(id);
                final int k2 = k;
                objets[k] = objet;
                parties[k] = l.getWorld().spawn(l, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setItemStack(objet);
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.getPersistentDataContainer().set(clePartie, PersistentDataType.BYTE, (byte) 1);
                    d.setTeleportDuration(2);
                    d.setViewRange(vue);
                    // os « lum_... » : toujours éclairé à fond (feu, météorite, fissures qui brillent dans le noir)
                    String nomOs = modele.parties().get(k2).nom();
                    if (nomOs != null && nomOs.toLowerCase(Locale.ROOT).startsWith("lum_")) d.setBrightness(new Display.Brightness(15, 15));
                    if (spectateurs != null) d.setVisibleByDefault(false);
                });
            }
            armures = new ItemDisplay[armureObjets.size()];
            armuresEnvoyees = new Transformation[armures.length];
            for (int a = 0; a < armures.length; a++) {
                ItemStack objet = armureObjets.get(a);
                final ItemDisplay.ItemDisplayTransform reglage = tenue(a);
                armures[a] = l.getWorld().spawn(l, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setItemStack(objet);
                    // objet tenu : le réglage « en main » de son modèle (épée, arme de poing…), comme le jeu le tient
                    d.setItemDisplayTransform(reglage);
                    d.getPersistentDataContainer().set(clePartie, PersistentDataType.BYTE, (byte) 1);
                    d.setTeleportDuration(2);
                    d.setViewRange(vue);
                    if (spectateurs != null) d.setVisibleByDefault(false);
                });
            }
            Player proprio = proprietaire == null ? null : Bukkit.getPlayer(proprietaire);
            if (copieSoi() && proprio != null) {
                partiesSoi = new ItemDisplay[n];
                envoyeesSoi = new Transformation[n];
                for (int k = 0; k < n; k++) {
                    if (parties[k] == null) continue;
                    String nomOs = modele.parties().get(k).nom();
                    partiesSoi[k] = afficher(l, objets[k], vue, nomOs != null && nomOs.toLowerCase(Locale.ROOT).startsWith("lum_"), proprio);
                    proprio.hideEntity(plugin, parties[k]);
                }
                armuresSoi = new ItemDisplay[armures.length];
                armuresEnvoyeesSoi = new Transformation[armures.length];
                for (int a = 0; a < armures.length; a++) {
                    armuresSoi[a] = afficher(l, armureObjets.get(a), vue, false, proprio);
                    if (main(a) != 0) armuresSoi[a].setItemDisplayTransform(tenue(a));
                    proprio.hideEntity(plugin, armures[a]);
                }
            }
            derniere = l;
            if (spectateurs != null) appliquerVisibilite();
            Player soi = proprietaire == null ? null : Bukkit.getPlayer(proprietaire);
            if (soi != null && pantinCacherTete) {
                // cachée d'abord ; teteProprietaire la remontre si elle s'éloigne des yeux
                int kt = indexTete();
                if (kt >= 0 && kt < parties.length && parties[kt] != null) soi.hideEntity(plugin, parties[kt]);
                for (int a = 0; a < armures.length; a++) if (armures[a] != null && armureOs.get(a) == kt) soi.hideEntity(plugin, armures[a]);
                teteSoi = null;
            }
            // pantin monté : chaque morceau est passager du joueur ; son jeu le place lui-même à chaque image, sans retard
            if (monte()) for (Display d : affichages()) base.addPassenger(d);
            mettreAJour(1);
        }

        /** Couches d'armure du pantin : os porteur et objet affiché ; leurs entités et dernières transformations. */
        final List<Integer> armureOs = new ArrayList<>();
        final List<ItemStack> armureObjets = new ArrayList<>();
        /** 0 : couche d'armure ; 1 : objet tenu en main droite ; 2 : en main gauche. */
        final List<Integer> armureMain = new ArrayList<>();

        private int main(int a) {
            return a < armureMain.size() ? armureMain.get(a) : 0;
        }

        /**
         * Réglage « en main » d'un objet tenu. Le jeu dessine la main gauche avec le réglage gauche du modèle retourné
         * (translation x, rotations y et z de signe opposé) ; un affichage d'objet ne le retourne pas. Les modèles du
         * jeu (épée, outil, objet à plat) ont un réglage gauche déjà retourné dans leur fichier : leur réglage droit,
         * tel quel, est donc exactement ce que le jeu fait dans la main gauche. Une arme par paire de vorace-item a son
         * propre modèle de main gauche (réglage neutre) : réglage gauche. Le bouclier : réglage gauche, corrigé à part.
         */
        private ItemDisplay.ItemDisplayTransform tenue(int a) {
            int main = main(a);
            if (main == 1) return ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND;
            if (main == 0) return ItemDisplay.ItemDisplayTransform.NONE;
            ItemStack o = armureObjets.get(a);
            boolean paire = o.hasItemMeta() && o.getItemMeta().getPersistentDataContainer().has(Pantins.PAIRE);
            if (o.getType() == org.bukkit.Material.SHIELD) return ItemDisplay.ItemDisplayTransform.NONE;   // placé à la main (ECU)
            return paire ? ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND : ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND;
        }

        private boolean bouclierGauche(int a) {
            return main(a) == 2 && armureObjets.get(a).getType() == org.bukkit.Material.SHIELD;
        }
        private ItemDisplay[] armures = new ItemDisplay[0];
        private Transformation[] armuresEnvoyees = new Transformation[0];

        boolean memeArmure(List<Map.Entry<String, ItemStack>> autre) {
            if (autre.size() != armureObjets.size()) return false;
            for (int k = 0; k < autre.size(); k++) if (!autre.get(k).getValue().isSimilar(armureObjets.get(k))) return false;
            return true;
        }

        /** Copie pour le joueur lui-même (voir pantinRecul) : morceaux et armure, et leurs dernières transformations. */
        private ItemDisplay[] partiesSoi = new ItemDisplay[0], armuresSoi = new ItemDisplay[0];
        private Transformation[] envoyeesSoi = new Transformation[0], armuresEnvoyeesSoi = new Transformation[0];

        boolean copieSoi() {
            return pantin && mob && proprietaire != null && pantinRecul > 0 && !pantinCacherTete;
        }

        /** Entité d'affichage d'un objet, montrée seulement au joueur {@code seul} s'il n'est pas null. */
        private ItemDisplay afficher(Location l, ItemStack objet, float vue, boolean lumiere, Player seul) {
            ItemDisplay d = l.getWorld().spawn(l, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setItemStack(objet);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.getPersistentDataContainer().set(clePartie, PersistentDataType.BYTE, (byte) 1);
                e.setTeleportDuration(2);
                e.setViewRange(vue);
                if (lumiere) e.setBrightness(new Display.Brightness(15, 15));
                if (seul != null || spectateurs != null) e.setVisibleByDefault(false);
            });
            if (seul != null) seul.showEntity(plugin, d);
            return d;
        }

        private void envoyer(ItemDisplay d, Transformation t, Transformation[] cache, int i, int intervalle) {
            if (d == null || !d.isValid() || t.equals(cache[i])) return;
            cache[i] = t;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(intervalle);
            d.setTransformation(t);
        }

        /** Tête du pantin montrée au joueur lui-même (null : pas encore décidé). */
        private Boolean teteSoi;

        /** Index de l'os « peau_tete » (-1 : aucun). */
        private int indexTete() {
            for (int k = 0; k < modele.parties().size(); k++) if ("peau_tete".equalsIgnoreCase(modele.parties().get(k).nom())) return k;
            return -1;
        }

        /**
         * Tête du pantin pour son propre joueur : cachée tant qu'elle entoure ses yeux (casque à la place, visible en F5),
         * montrée dès qu'elle s'en éloigne. Seuil avec un écart pour ne pas clignoter.
         */
        private void teteProprietaire(Pose[] poses) {
            if (!pantin || proprietaire == null || !pantinCacherTete) return;
            Player soi = Bukkit.getPlayer(proprietaire);
            int k = indexTete();
            if (soi == null || k < 0 || k >= parties.length || parties[k] == null || poses[k] == null) return;
            ModeleBB.Partie partie = modele.parties().get(k);
            if (partie.cubes().isEmpty()) return;
            ModeleBB.Cube c = partie.cubes().get(0);
            double[] piv = partie.pivot();
            Vector3f centre = new Vector3f((float) (((c.de()[0] + c.a()[0]) / 2 - piv[0]) / 16), (float) (((c.de()[1] + c.a()[1]) / 2 - piv[1]) / 16),
                (float) (((c.de()[2] + c.a()[2]) / 2 - piv[2]) / 16)).mul(taille).rotate(poses[k].rotation()).add(poses[k].translation());
            double d = Math.sqrt(centre.x * centre.x + Math.pow(centre.y - soi.getEyeHeight(), 2) + centre.z * centre.z);
            boolean montrer = Boolean.TRUE.equals(teteSoi) ? d > 0.45 : d > 0.55;
            if (teteSoi != null && teteSoi == montrer) return;
            teteSoi = montrer;
            for (int a = 0; a < armures.length; a++) {
                if (armures[a] == null || armureOs.get(a) != k) continue;
                if (montrer) soi.showEntity(plugin, armures[a]); else soi.hideEntity(plugin, armures[a]);
            }
            if (montrer) {
                soi.showEntity(plugin, parties[k]);
                soi.sendEquipmentChange(soi, org.bukkit.inventory.EquipmentSlot.HEAD, new ItemStack(org.bukkit.Material.AIR));
            } else {
                soi.hideEntity(plugin, parties[k]);
                ItemStack casque = peaux.get(k);
                soi.sendEquipmentChange(soi, org.bukkit.inventory.EquipmentSlot.HEAD, casque != null ? casque : new ItemStack(org.bukkit.Material.AIR));
            }
        }

        /** Pantin d'un joueur dont les morceaux sont ses passagers. */
        boolean monte() {
            return pantin && mob && pantinMonte && base instanceof Player && ancre == null;
        }

        void supprimerParties() {
            for (Display d : affichages()) d.remove();
            parties = new ItemDisplay[0];
            armures = new ItemDisplay[0];
            partiesSoi = new ItemDisplay[0];
            armuresSoi = new ItemDisplay[0];
            etiquettes = new TextDisplay[0];
        }

        Location position() {
            return fixe != null ? fixe.clone() : ancre != null ? ancre.clone() : base.getLocation();
        }

        // -------------------------------------------------------------- caméra de cinéma (ultimes)

        /** Pendant une caméra de cinéma : le pantin reste là où le joueur était (le joueur, spectateur, suit la caméra). */
        private Location ancre;
        /** Monture de la caméra : support d'armure invisible (le client adoucit ses déplacements, contrairement à un affichage). */
        private org.bukkit.entity.Entity camera;
        private org.bukkit.GameMode modeAvant;
        /** Tics depuis le début de la caméra : le client ne connaît l'entité caméra qu'au tic suivant son apparition. */
        private int ticsCamera;

        private int indexOs(String nom) {
            for (int k = 0; k < modele.parties().size(); k++) if (nom.equalsIgnoreCase(modele.parties().get(k).nom())) return k;
            return -1;
        }

        /**
         * Animation dont l'os « camera » est animé (et « camera_cible », le point regardé) : le joueur la voit comme un
         * film, depuis cette caméra (mode spectateur rattaché à une entité d'affichage invisible, comme les plugins de
         * cinématiques). Le pantin reste sur place ; à la fin, le joueur retrouve son mode de jeu, sa place et son regard.
         */
        void debutCinema(String nomAnimation) {
            if (!pantin || proprietaire == null || !cameraCinema || camera != null) return;
            Animation a = trouver(nomAnimation);
            int kc = indexOs("camera");
            if (a == null || kc < 0 || a.os().get(modele.parties().get(kc).uuid()) == null) return;
            Player p = Bukkit.getPlayer(proprietaire);
            if (p == null) return;
            ancre = p.getLocation();
            modeAvant = p.getGameMode();
            base.eject();   // les morceaux descendent du joueur : le pantin reste à sa place
            derniere = null;
            // le joueur voit le vrai pantin (pas sa copie reculée)
            for (ItemDisplay d : parties) if (d != null) p.showEntity(plugin, d);
            for (ItemDisplay d : armures) if (d != null) p.showEntity(plugin, d);
            for (ItemDisplay d : partiesSoi) if (d != null) p.hideEntity(plugin, d);
            for (ItemDisplay d : armuresSoi) if (d != null) p.hideEntity(plugin, d);
            Location depart = p.getEyeLocation();
            p.getPersistentDataContainer().set(new NamespacedKey(plugin, CLE_CAMERA), PersistentDataType.STRING, modeAvant.name());
            ticsCamera = 0;
            cameraParPaquet = false;
            if (!cameraMonture) {
                // mode spectateur rattaché à un affichage invisible : mouvement fluide (interpolé par le client), sans
                // barre d'objets ni interface, comme une vraie cinématique
                camera = depart.getWorld().spawn(depart, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setTeleportDuration(2);
                    d.getPersistentDataContainer().set(clePartie, PersistentDataType.BYTE, (byte) 1);
                });
                p.setGameMode(org.bukkit.GameMode.SPECTATOR);
                p.setSpectatorTarget(camera);
                majCamera();
                return;
            }
            camera = depart.getWorld().spawn(depart, org.bukkit.entity.ArmorStand.class, d -> {
                d.setPersistent(false);
                d.setMarker(true);
                d.setInvisible(true);
                d.setGravity(false);
                d.setSilent(true);
                d.setInvulnerable(true);
                d.setBasePlate(false);
                d.getPersistentDataContainer().set(clePartie, PersistentDataType.BYTE, (byte) 1);
            });
            // le joueur (invisible) monte sur l'entité caméra : ses yeux suivent la caméra, il garde son mode de jeu, sa
            // barre d'objets et son interface (le jeu ne les dessine que si la caméra est le joueur lui-même) ; son
            // regard est tourné vers la cible à chaque tic. Si la monture est refusée : mode spectateur comme avant.
            camera.teleport(depart.clone().subtract(0, hauteurYeux, 0));
            cameraParPaquet = camera.addPassenger(p);
            if (cameraParPaquet) {
                figer(p, true);
            } else {
                p.setGameMode(org.bukkit.GameMode.SPECTATOR);
                p.setSpectatorTarget(camera);
            }
            majCamera();
        }

        /** Hauteur des yeux du joueur au-dessus de sa monture (mesurée pendant la caméra ; 1,02 pour un joueur debout). */
        private double hauteurYeux = 1.02;

        /** Tourne le regard du joueur (paquet ClientboundPlayerRotationPacket, sans bouger sa position). */
        private void regarder(Player p, float yaw, float pitch) {
            try {
                if (plugin.paquets() == null) throw new ClassNotFoundException("couche paquets");
                Class<?> c = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket");
                for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                    Class<?>[] t = k.getParameterTypes();
                    if (t.length == 2 && t[0] == float.class && t[1] == float.class) {
                        k.setAccessible(true);
                        plugin.paquets().envoyer(p, k.newInstance(yaw, pitch), false);
                        return;
                    }
                }
                p.setRotation(yaw, pitch);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                p.setRotation(yaw, pitch);
            }
        }

        /** Vrai : le joueur est monté sur la caméra (il reste dans son mode, immobile et intouchable). */
        private boolean cameraParPaquet;
        private boolean invulnerableAvant;
        private float vitesseVolAvant;

        /** Pendant la caméra : le joueur ne bouge plus et ne prend pas de coups ; rendu tel quel à la fin. */
        private void figer(Player p, boolean oui) {
            if (oui) {
                invulnerableAvant = p.isInvulnerable();
                vitesseVolAvant = p.getFlySpeed();
                p.setInvulnerable(true);
                p.setFlySpeed(0f);
                Pantins.figerMouvements(p, true);
            } else {
                p.setInvulnerable(invulnerableAvant);
                p.setFlySpeed(vitesseVolAvant <= 0 ? 0.1f : vitesseVolAvant);
                Pantins.figerMouvements(p, false);
            }
        }

        /** Place la caméra sur l'os « camera », tournée vers « camera_cible ». */
        private void majCamera() {
            if (camera == null || !camera.isValid()) return;
            Location c = positionOs("camera");
            if (c == null) return;
            Location v = positionOs("camera_cible");
            if (v != null && v.distanceSquared(c) > 1e-6) c.setDirection(v.toVector().subtract(c.toVector()));
            Player p = proprietaire == null ? null : Bukkit.getPlayer(proprietaire);
            if (cameraParPaquet && p != null) {
                ticsCamera++;
                if (camera.getPassengers().contains(p)) {
                    double h = p.getEyeLocation().getY() - camera.getLocation().getY();
                    if (h > 0.2 && h < 2.5) hauteurYeux = h;
                } else {
                    camera.addPassenger(p);   // remis en selle s'il est descendu
                }
                camera.teleport(c.clone().subtract(0, hauteurYeux, 0), io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
                regarder(p, c.getYaw(), c.getPitch());
                return;
            }
            camera.teleport(c);
            if (p == null) return;
            if (p.getGameMode() != org.bukkit.GameMode.SPECTATOR) return;
            ticsCamera++;
            // Le paquet « caméra » envoyé le tic où l'entité apparaît arrive avant elle chez le client, qui l'ignore
            // (il reste dans les yeux du joueur). Le serveur, lui, croit la caméra posée et ne la renvoie pas :
            // on la redonne une fois l'entité connue du client (tics 3 et 8, au cas où le réseau traîne).
            if (ticsCamera == 3 || ticsCamera == 8) {
                p.setSpectatorTarget(null);
                p.setSpectatorTarget(camera);
            } else if (!camera.equals(p.getSpectatorTarget())) p.setSpectatorTarget(camera);
        }

        void finCinema() {
            if (camera == null) return;
            Player p = proprietaire == null ? null : Bukkit.getPlayer(proprietaire);
            if (p != null && cameraParPaquet) {
                p.getPersistentDataContainer().remove(new NamespacedKey(plugin, CLE_CAMERA));   // avant : la descente est permise
                camera.removePassenger(p);
                figer(p, false);
                if (ancre != null) p.teleport(ancre);
            } else if (p != null) {
                p.setSpectatorTarget(null);
                p.setGameMode(modeAvant == null ? org.bukkit.GameMode.SURVIVAL : modeAvant);
                p.setSneaking(false);
                if (ancre != null) p.teleport(ancre);
                p.getPersistentDataContainer().remove(new NamespacedKey(plugin, CLE_CAMERA));
            }
            camera.remove();
            camera = null;
            ancre = null;
            cameraParPaquet = false;
        }

        // -------------------------------------------------------------- taille, regard, repères

        /** Agrandit le modèle entier (1 = taille du .bbmodel). Gardé avec le monde. */
        public void taille(float t) {
            taille = Math.max(0.05f, Math.min(16f, t));
            if (base instanceof Interaction it) {
                it.setInteractionWidth((float) Math.max(0.3, Math.min(8, modele.largeur() / 16.0 * taille)));
                it.setInteractionHeight((float) Math.max(0.3, Math.min(16, modele.hauteur() / 16.0 * taille)));
            }
            if (mob) {
                if (Math.abs(taille - 1f) < 1e-4) base.getPersistentDataContainer().remove(cleTaille);
                else base.getPersistentDataContainer().set(cleTaille, PersistentDataType.FLOAT, taille);
            } else {
                memoriser();
            }
            java.util.Arrays.fill(envoyees, null);
            distanceJoueur = -1;
        }

        /**
         * Impose le regard des os de tête (degrés par rapport au corps, pitch positif = vers le bas).
         * NaN : la tête suit de nouveau le regard du mob (ou reste droite pour un modèle posé).
         */
        public void regard(float yawRelatif, float pitch) {
            regardYaw = yawRelatif;
            regardPitch = pitch;
        }

        /** Position actuelle d'un os (nom de Blockbench, sans tenir compte des majuscules), ou null s'il n'existe pas. */
        public Location positionOs(String nom) {
            int k = -1;
            for (int i = 0; i < modele.parties().size(); i++) if (modele.parties().get(i).nom().equalsIgnoreCase(nom)) { k = i; break; }
            if (k < 0) return null;
            Pose[] p = dernieresPoses;
            if (p == null || endormi) p = Animateur.posesCompletes(modele, List.of(), yaw, taille, 0f, 0f);
            if (p[k] == null) return null;
            Vector3f t = p[k].translation();
            return position().add(t.x, t.y, t.z);
        }

        // -------------------------------------------------------------- animations

        /** Joue une animation ; boucle = la répéter jusqu'à un autre ordre. Faux si elle n'existe pas. */
        public boolean jouer(String nom, boolean boucle) {
            return demarrer(nom, boucle, false);
        }

        /**
         * Joue une animation par-dessus l'animation en cours : seuls les os qu'elle anime changent (attaquer en marchant).
         * Faux si elle n'existe pas.
         */
        public boolean jouerParDessus(String nom, boolean boucle) {
            return demarrer(nom, boucle, true);
        }

        boolean demarrer(String nom, boolean boucle, boolean dessus) {
            Animation a = trouver(nom);
            if (a == null) return false;
            if (Bukkit.isPrimaryThread()) {
                fr.vorace.api.VoraceModeleAnimationEvent ev = new fr.vorace.api.VoraceModeleAnimationEvent(base, modele.id(), a.nom(), boucle);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) return false;
                a = trouver(ev.getAnimation());
                if (a == null) return false;
                boucle = ev.isBoucle();
            }
            forcee = a.nom();
            forceeBoucle = boucle;
            forceePartielle = dessus;
            debutForcee = tic;
            if (!mob) memoriser();
            return true;
        }

        /** Revient aux animations automatiques. */
        public void arreterAnimation() {
            forcee = null;
            forceePartielle = false;
            if (!mob) memoriser();
        }

        /** Joue une animation automatique (dégâts, attaque...) si le modèle l'a. */
        public boolean jouerAuto(String role) {
            if (pantin) return false;  // le pantin d'un joueur ne joue que l'animation demandée
            for (String n : AUTO.getOrDefault(role, List.of())) {
                if (modele.animations().containsKey(n)) {
                    if (Bukkit.isPrimaryThread()) {
                        fr.vorace.api.VoraceModeleAnimationEvent ev = new fr.vorace.api.VoraceModeleAnimationEvent(base, modele.id(), n, false);
                        Bukkit.getPluginManager().callEvent(ev);
                        if (ev.isCancelled()) return false;
                        Animation choisie = trouver(ev.getAnimation());
                        if (choisie == null) return false;
                        n = choisie.nom();
                    }
                    // une animation demandée en boucle (commande, API) n'est pas coupée par un coup reçu
                    if (PAR_DESSUS.contains(role) && forcee != null && !forceePartielle && forceeBoucle) return false;
                    forcee = n;
                    forceeBoucle = false;
                    forceePartielle = PAR_DESSUS.contains(role);
                    debutForcee = tic;
                    return true;
                }
            }
            return false;
        }

        /** Le mob est touché : animation « degat » par-dessus et teinte rouge un court instant. */
        public void blesser() {
            if (pantin) return;
            jouerAuto("degat");
            if (!teinteDegats || endormi) return;
            if (rouges == null) {
                rouges = new ItemStack[objets.length];
                for (int k = 0; k < objets.length; k++) if (objets[k] != null) rouges[k] = Teinte.teinter(objets[k], COULEUR_DEGATS);
            }
            boolean fait = false;
            for (int k = 0; k < parties.length && k < rouges.length; k++) {
                if (parties[k] != null && parties[k].isValid() && rouges[k] != null) { parties[k].setItemStack(rouges[k]); fait = true; }
            }
            if (fait) finTeinte = tic + 8;
        }

        private void finirTeinte() {
            finTeinte = -1;
            for (int k = 0; k < parties.length && k < objets.length; k++) {
                if (parties[k] != null && parties[k].isValid() && objets[k] != null) parties[k].setItemStack(objets[k]);
            }
        }

        /** Le mob est mort : le modèle joue « mort » sur place puis disparaît. */
        public void mourir() {
            if (pantin) { forcee = null; return; }
            fixe = base.getLocation();
            if (finTeinte >= 0) finirTeinte();
            if (!jouerAuto("mort")) fixe = null;
        }

        private void memoriser() {
            base.getPersistentDataContainer().set(clePose, PersistentDataType.STRING,
                modele.id() + "|" + (forcee != null && forceeBoucle && !forceePartielle ? forcee : "") + "|" + yaw
                    + (Math.abs(taille - 1f) < 1e-4 ? "" : "|" + taille));
        }

        Animation trouver(String nom) {
            if (nom == null) return null;
            String n = nom.toLowerCase(Locale.ROOT);
            Animation a = modele.animations().get(n);
            if (a != null) return a;
            for (String alias : AUTO.getOrDefault(n, List.of())) if (modele.animations().containsKey(alias)) return modele.animations().get(alias);
            return null;
        }

        private double tempsPiste(Animation a, long debut, boolean boucle) {
            double t = (tic - debut) / 20.0;
            if (a != null && boucle && a.duree() > 0) t = t % a.duree();
            return t;
        }

        /** Faux quand le modèle doit disparaître. */
        boolean mettreAJour(int intervalle) {
            boolean mort = fixe != null;
            if (!mort && (!base.isValid() || base.isDead())) return false;
            Location l = position();

            float teteYaw = 0, tetePitch = 0;
            if (mob && !mort) {
                LivingEntity le = (LivingEntity) base;
                // pantin : le corps regarde où regarde le joueur (pas le corps du jeu, qui tourne en retard)
                yaw = ancre != null ? ancre.getYaw() : pantin && le instanceof Player ? l.getYaw() : corps(le);
                double dx = derniere == null ? 0 : l.getX() - derniere.getX(), dz = derniere == null ? 0 : l.getZ() - derniere.getZ();
                double vitesse = Math.sqrt(dx * dx + dz * dz);
                String voulue = vitesse > 0.15 && trouver("course") != null ? "course" : vitesse > 0.01 ? "marche" : "repos";
                Animation a = trouver(voulue);
                String nom = a == null ? null : a.nom();
                if (nom != null && !nom.equals(auto)) { auto = nom; debutAuto = tic; }
                if (nom == null) auto = null;
                if (suiviTete && Float.isNaN(regardYaw)) {
                    teteYaw = limiter(enDegres(tete(le) - yaw), 75);
                    tetePitch = limiter(l.getPitch(), 45);
                }
            } else if (auto == null && forcee == null) {
                Animation a = trouver("repos");
                if (a != null) { auto = a.nom(); debutAuto = tic; }
            }
            if (!Float.isNaN(regardYaw)) {
                teteYaw = limiter(regardYaw, 90);
                tetePitch = limiter(Float.isNaN(regardPitch) ? 0 : regardPitch, 60);
            }

            // déplacement des parties avec la base (pantin monté : elles sont passagères ; on remonte celles qui sont tombées)
            if (monte()) {
                for (Display d : affichages()) {
                    if (d.getVehicle() != null) continue;
                    Location c = l.clone();
                    c.setYaw(0);
                    c.setPitch(0);
                    d.teleport(c);
                    base.addPassenger(d);
                }
                derniere = l;
            } else if (derniere == null || !derniere.getWorld().equals(l.getWorld()) || derniere.distanceSquared(l) > 1e-6) {
                Location c = l.clone();
                c.setYaw(0);
                c.setPitch(0);
                for (Display d : affichages()) d.teleport(c);
                derniere = l;
            }

            if (finTeinte >= 0 && tic >= finTeinte) finirTeinte();
            if (tic % intervalle != 0 && intervalle > 1) return true;

            // fin d'une animation demandée
            if (forcee != null) {
                Animation fa = modele.animations().get(forcee);
                double tf = (tic - debutForcee) / 20.0;
                if (fa == null) {
                    forcee = null;
                } else if (!forceeBoucle && !fa.boucle().equals("loop") && tf >= fa.duree()) {
                    if (mort) return false;
                    // pantin : garde la dernière pose 0,25 s (le coup suivant d'un combo reprend le même pantin)
                    if (pantin && tf < fa.duree() + 0.25) { /* attend */ }
                    else if (!fa.boucle().equals("hold") || forceePartielle || pantin) forcee = null;
                }
            }
            if (pantin && forcee == null) return false;  // fin de l'animation du joueur : le pantin disparaît

            // animation complète (demandée ou automatique), avec fondu depuis la précédente
            String pNom = forcee != null && !forceePartielle ? forcee : auto;
            long pDebut = forcee != null && !forceePartielle ? debutForcee : debutAuto;
            boolean pBoucle = forcee != null && !forceePartielle && forceeBoucle;
            if (!java.util.Objects.equals(pNom, pleine) || pDebut != debutPleine) {
                if (!java.util.Objects.equals(pNom, pleine) && fonduTics > 0) {
                    avant = pleine;
                    debutAvant = debutPleine;
                    avantBoucle = pleineBoucle;
                    debutFondu = tic;
                }
                pleine = pNom;
                debutPleine = pDebut;
                pleineBoucle = pBoucle;
                tEffetPleine = -1e-9;
            }
            List<Animateur.Couche> couches = new ArrayList<>(3);
            Animation pa = pleine == null ? null : modele.animations().get(pleine);
            double tp = tempsPiste(pa, debutPleine, pleineBoucle);
            float poids = 1f;
            if (tic - debutFondu < fonduTics) {
                Animation aa = avant == null ? null : modele.animations().get(avant);
                couches.add(new Animateur.Couche(aa, tempsPiste(aa, debutAvant, avantBoucle), 1f, false));
                poids = (tic - debutFondu) / (float) fonduTics;
            }
            couches.add(new Animateur.Couche(pa, tp, poids, false));

            // animation par-dessus (dégâts, attaque...) : entre et sort en fondu
            Animation da = null;
            double td = 0;
            if (forcee != null && forceePartielle) {
                da = modele.animations().get(forcee);
                td = tempsPiste(da, debutForcee, forceeBoucle);
                float w = 1f;
                if (fonduTics > 0 && da != null) {
                    w = Math.min(1f, (float) (td * 20 / fonduTics));
                    if (!forceeBoucle && !da.boucle().equals("loop") && !da.boucle().equals("hold")) w = Math.min(w, (float) ((da.duree() - td) * 20 / fonduTics));
                }
                couches.add(new Animateur.Couche(da, td, Math.max(0f, w), true));
            }

            // clés d'effet (sons, particules, scripts)
            if (pa != null) tEffetPleine = effets(pa, tEffetPleine, (tic - debutPleine) / 20.0, pleineBoucle);
            String cleDessus = da == null ? null : da.nom() + "@" + debutForcee;
            if (!java.util.Objects.equals(cleDessus, effetDessusCle)) { effetDessusCle = cleDessus; tEffetDessus = -1e-9; }
            if (da != null) tEffetDessus = effets(da, tEffetDessus, (tic - debutForcee) / 20.0, forceeBoucle);

            Pose[] poses = Animateur.posesCompletes(modele, couches, yaw, taille, teteYaw, tetePitch);
            if (visee != null) poses = visee.appliquer(modele, poses);
            dernieresPoses = poses;
            teteProprietaire(poses);
            majCamera();
            Quaternionf droite = Animateur.demiTour();
            float siege = monte() ? (float) base.getHeight() : 0f;
            // copie pour soi : reculée vers l'arrière du corps (l'avant du modèle regarde -z)
            Vector3f recul = partiesSoi.length == 0 ? new Vector3f()
                : new Quaternionf().rotateY((float) (Math.PI - Math.toRadians(yaw))).transform(new Vector3f(0, 0, pantinRecul / 16f * taille));
            for (int k = 0; k < parties.length; k++) {
                ItemDisplay d = parties[k];
                if (d == null || poses[k] == null || !d.isValid()) continue;
                Pose p = poses[k];
                // morceau du skin : modèle coloré pixel par pixel (PeauUV), à l'échelle 1 comme les couches d'armure
                Transformation tr = peaux.containsKey(k)
                    ? new Transformation(p.translation(), p.rotation(), new Vector3f(p.echelle()).div((float) Math.max(1e-6, modele.parties().get(k).echelle())), droite)
                    : new Transformation(p.translation(), p.rotation(), p.echelle(), droite);
                if (siege != 0f && base.equals(d.getVehicle())) {
                    // passager : le jeu le place au sommet de la boîte du joueur (1,8 debout, 1,5 accroupi) ; on redescend
                    tr = new Transformation(new Vector3f(tr.getTranslation()).sub(0, siege, 0), tr.getLeftRotation(), tr.getScale(), tr.getRightRotation());
                }
                if (k < partiesSoi.length && partiesSoi[k] != null) {
                    envoyer(partiesSoi[k], new Transformation(new Vector3f(tr.getTranslation()).add(recul), tr.getLeftRotation(), tr.getScale(),
                        tr.getRightRotation()), envoyeesSoi, k, intervalle);
                }
                if (tr.equals(envoyees[k])) continue;
                envoyees[k] = tr;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(intervalle);
                d.setTransformation(tr);
            }
            // couches d'armure : même pose que l'os qui les porte (modèle généré à l'échelle 1, pivot en 8, 8, 8)
            for (int a = 0; a < armures.length; a++) {
                ItemDisplay d = armures[a];
                int k = armureOs.get(a);
                if (d == null || !d.isValid() || k >= poses.length || poses[k] == null) continue;
                Pose p = poses[k];
                Vector3f ech = new Vector3f(p.echelle()).div((float) Math.max(1e-6, modele.parties().get(k).echelle()));
                Vector3f tr = new Vector3f(p.translation());
                if (siege != 0f && base.equals(d.getVehicle())) tr.sub(0, siege, 0);
                Quaternionf rot = p.rotation();
                if (bouclierGauche(a)) {
                    // bouclier sanglé à l'avant-bras gauche (comme l'écu des animations, epee.py) : la plaque à l'extérieur du
                    // bras, son haut vers le coude, son centre 4,5 pixels à l'extérieur et 4 au-dessus du poing. Dessiné sans
                    // réglage « en main » (NONE) : le jeu pose le modèle spécial du bouclier en (-0,5, -0,5, -0,5), retourné en
                    // y et z ; la rotation Ry(-90°) amène sa face dehors (-x de l'os) et son haut vers le coude (+y).
                    // (Avant la 1.5.2, le réglage gauche du bouclier était pris égal au droit ; il vaut (10, 6, 12) et non
                    // (10, 6, -4) : la plaque flottait à 28 pixels du poing, près de 2 blocs.)
                    float e = ech.x;
                    rot = new Quaternionf(p.rotation()).rotateY((float) (-Math.PI / 2));
                    tr.add(new Quaternionf(p.rotation()).transform(new Vector3f(-3f / 16f, 4f / 16f, 0).mul(e)));
                    tr.add(new Quaternionf(rot).transform(new Vector3f(0.5f, 0.5f, 0.5f).mul(e)));
                } else if (main(a) != 0) {
                    // objet tenu : au point de prise du jeu (2 pixels devant le bout de l'avant-bras), orienté comme en main
                    tr.add(new Quaternionf(p.rotation()).transform(new Vector3f(0, 0, -2f / 16f).mul(ech)));
                    rot = new Quaternionf(p.rotation()).mul(TENUE);
                }
                Transformation t = new Transformation(tr, rot, ech, droite);
                if (a < armuresSoi.length && armuresSoi[a] != null) {
                    envoyer(armuresSoi[a], new Transformation(new Vector3f(tr).add(recul), rot, ech, droite), armuresEnvoyeesSoi, a, intervalle);
                }
                if (t.equals(armuresEnvoyees[a])) continue;
                armuresEnvoyees[a] = t;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(intervalle);
                d.setTransformation(t);
            }
            if (aEtiquettes) etiquettes(poses, l, intervalle);
            return true;
        }

        /**
         * Os « peau_ » : une tête de joueur (cube de 8 pixels que le jeu dessine entre y = -0,5 et 0 autour de l'origine
         * de l'entité) étirée sur le cube de l'os. Le jeu tourne lui-même l'objet d'un demi-tour, ce qui met le visage
         * de la tête vers l'avant du modèle : pas de demi-tour à annuler ici. Réglages de secours :
         * pantin.correction.rotation-y et decalage-y.
         */
        private Transformation transformationPeau(int k, Pose p) {
            ModeleBB.Partie partie = modele.parties().get(k);
            ModeleBB.Cube c = partie.cubes().get(0);
            double[] piv = partie.pivot();
            float ech = (float) (1.0 / Math.max(1e-6, partie.echelle()));
            // T · R · S1 · translation(c) · S2 · translation(0, dy, 0) · Ry, sans cisaillement :
            // translation = T + R(S1 ∘ (c + S2 ∘ d)), rotation gauche R, échelle S1 ∘ S2, rotation droite Ry
            Vector3f s1 = new Vector3f(p.echelle()).mul(ech);
            Vector3f s2 = new Vector3f((float) ((c.a()[0] - c.de()[0]) / 8), (float) ((c.a()[1] - c.de()[1]) / 8), (float) ((c.a()[2] - c.de()[2]) / 8));
            Vector3f centre = new Vector3f((float) (((c.de()[0] + c.a()[0]) / 2 - piv[0]) / 16), (float) (((c.de()[1] + c.a()[1]) / 2 - piv[1]) / 16),
                (float) (((c.de()[2] + c.a()[2]) / 2 - piv[2]) / 16));
            Vector3f local = new Vector3f(0, peauDecalageY, 0).mul(s2).add(centre).mul(s1);
            Vector3f translation = new Vector3f(local).rotate(p.rotation()).add(p.translation());
            return new Transformation(translation, new Quaternionf(p.rotation()), new Vector3f(s1).mul(s2),
                new Quaternionf().rotateY((float) Math.toRadians(peauRotationY)));
        }

        /** Joue les clés d'effet atteintes depuis le dernier passage ; renvoie le nouveau temps vu. */
        private double effets(Animation a, double avant, double maintenant, boolean boucle) {
            if (!effetsActifs || a.evenements().isEmpty()) return maintenant;
            for (ModeleBB.Evenement e : Animateur.evenements(a, avant, maintenant, boucle)) effet(a, e);
            return maintenant;
        }

        /** Sons et particules joués depuis l'apparition (pour /vorace mob info). */
        private int sonsJoues, particulesJouees;
        public int sonsJoues() { return sonsJoues; }
        public int particulesJouees() { return particulesJouees; }

        private void effet(Animation a, ModeleBB.Evenement e) {
            fr.vorace.api.VoraceModeleEffetEvent ev = new fr.vorace.api.VoraceModeleEffetEvent(base, modele.id(), a.nom(), e.type(), e.valeur());
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled()) return;
            EffetSpec sp = EffetSpec.lire(e.valeur());
            Location l = sp.os() != null ? positionOs(sp.os()) : null;
            if (l == null) l = position().add(0, modele.hauteur() / 32.0 * taille, 0);
            World w = l.getWorld();
            if (w == null) return;
            switch (e.type()) {
                case "sound" -> {
                    sonsJoues++;
                    if (spectateurs == null) w.playSound(l, sp.nom(), org.bukkit.SoundCategory.PLAYERS, sp.volume(), sp.hauteur());
                    else for (UUID u : spectateurs) { Player j = Bukkit.getPlayer(u); if (j != null) j.playSound(l, sp.nom(), org.bukkit.SoundCategory.PLAYERS, sp.volume(), sp.hauteur()); }
                }
                case "particle" -> {
                    org.bukkit.Particle p = null;
                    try { p = org.bukkit.Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(sp.nom())); } catch (IllegalArgumentException ignore) { }
                    if (p == null) return;
                    Object donnees = null;
                    if (p.getDataType() == org.bukkit.Particle.DustOptions.class) {
                        donnees = new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(sp.couleur() < 0 ? 0xFFFFFF : sp.couleur()), sp.taille());
                    } else if (p.getDataType() == org.bukkit.Color.class) {
                        donnees = org.bukkit.Color.fromARGB(0xFF000000 | (sp.couleur() < 0 ? 0xFFFFFF : sp.couleur()));
                    } else if (p.getDataType() != Void.class) {
                        return;  // particule qui demande d'autres données (bloc, objet) : seul l'événement est envoyé
                    }
                    particulesJouees++;
                    double r = sp.ecart() * taille;
                    if (spectateurs == null) w.spawnParticle(p, l, sp.nombre(), r, r, r, sp.vitesse(), donnees, true);
                    else for (UUID u : spectateurs) { Player j = Bukkit.getPlayer(u); if (j != null) j.spawnParticle(p, l, sp.nombre(), r, r, r, sp.vitesse(), donnees, true); }
                }
                case "timeline" -> {
                    // « apparition <modèle> <animation> [taille] » : un modèle éphémère au même endroit, tourné pareil
                    String[] p = e.valeur().trim().split("\\s+");
                    if (p.length < 3 || !p[0].equalsIgnoreCase("apparition")) return;
                    float t = 1f;
                    try { if (p.length > 3) t = Float.parseFloat(p[3]); } catch (NumberFormatException ignore) { }
                    Location ici = position();
                    // le joueur vise un point : l'apparition se tourne vers lui et vole jusqu'à lui
                    float lacet = yaw;
                    Visee vers = null;
                    Location cible = visee(proprietaire, ici.getWorld());
                    if (cible != null) {
                        double dx = cible.getX() - ici.getX(), dz = cible.getZ() - ici.getZ(), d = Math.hypot(dx, dz);
                        if (d >= 0.8) {
                            lacet = Visee.yawVers(dx, dz);
                            vers = new Visee(lacet, (float) d, (float) (cible.getY() - ici.getY()), t * taille);
                        }
                    }
                    Instance inv = apparition(p[1], ici, lacet, p[2], t * taille);
                    if (inv != null) inv.visee = vers;
                    if (inv != null && spectateurs != null) {
                        List<Player> vus = new ArrayList<>();
                        for (UUID u : spectateurs) { Player j = Bukkit.getPlayer(u); if (j != null) vus.add(j); }
                        inv.visiblePour(vus);
                    }
                }
                default -> { }
            }
        }

        /** Nom du mob (ou de la base) affiché à l'os « tag_ ». */
        private void etiquettes(Pose[] poses, Location l, int intervalle) {
            net.kyori.adventure.text.Component nom = base.customName();
            for (int k = 0; k < etiquettes.length; k++) {
                if (!estEtiquette[k]) continue;
                TextDisplay t = etiquettes[k];
                if (nom == null || poses[k] == null) {
                    if (t != null && t.isValid()) t.remove();
                    etiquettes[k] = null;
                    continue;
                }
                if (t == null || !t.isValid()) {
                    Location c = l.clone();
                    c.setYaw(0);
                    c.setPitch(0);
                    float vue = porteeVue();
                    t = l.getWorld().spawn(c, TextDisplay.class, d -> {
                        d.setPersistent(false);
                        d.setBillboard(Display.Billboard.CENTER);
                        d.getPersistentDataContainer().set(clePartie, PersistentDataType.BYTE, (byte) 1);
                        d.setTeleportDuration(2);
                        d.setViewRange(vue);
                    });
                    cacher(t);
                    etiquettes[k] = t;
                }
                if (!nom.equals(t.text())) t.text(nom);
                Vector3f v = new Vector3f(poses[k].translation()).add(0, 0.2f, 0);
                Transformation tr = new Transformation(v, new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());
                if (!tr.equals(t.getTransformation())) {
                    t.setInterpolationDelay(0);
                    t.setInterpolationDuration(intervalle);
                    t.setTransformation(tr);
                }
            }
        }
    }

    private static final int COULEUR_DEGATS = 0xFF6464;


    private static float limiter(float v, float max) {
        return Math.max(-max, Math.min(max, v));
    }

    /** Angle ramené entre -180 et 180. */
    static float enDegres(float a) {
        float r = a % 360f;
        if (r >= 180f) r -= 360f;
        if (r < -180f) r += 360f;
        return r;
    }

    private static float corps(LivingEntity e) {
        try {
            return e.getBodyYaw();
        } catch (Throwable t) {
            return e.getLocation().getYaw();
        }
    }

    /** Méthodes du jeu pour l'orientation de la tête (Paper : noms de Mojang), par classe. */
    private static final Map<Class<?>, java.lang.reflect.Method> POIGNEES = new HashMap<>();
    private static java.lang.reflect.Method rotationTete;
    private static boolean teteIndisponible;

    /** Orientation de la tête d'un mob (où il regarde), ou celle de son entité si le serveur ne la donne pas. */
    private static float tete(LivingEntity e) {
        if (!teteIndisponible) {
            try {
                java.lang.reflect.Method g = POIGNEES.get(e.getClass());
                if (g == null) {
                    g = e.getClass().getMethod("getHandle");
                    POIGNEES.put(e.getClass(), g);
                }
                Object h = g.invoke(e);
                if (rotationTete == null || !rotationTete.getDeclaringClass().isInstance(h)) rotationTete = h.getClass().getMethod("getYHeadRot");
                return ((Number) rotationTete.invoke(h)).floatValue();
            } catch (Throwable t) {
                teteIndisponible = true;
            }
        }
        return e.getLocation().getYaw();
    }

    // ------------------------------------------------------------------ exemple

    /** Empreintes SHA-256 des joueur.bbmodel livrés avant le 19 nov. 2026 (bas du torse qui remontait de 2 pixels). */
    private static final java.util.Set<String> SQUELETTES_ANCIENS = java.util.Set.of(
        "53dc0e84042526bc011b2c5d7315b6e511fd3ef39ffd1159916d85d7d80f19eb",
        "d41ee45f17054bf74ac955343158f24f6223d6e79c2037b7ebcd036bec5590c8");

    static String empreinte(byte[] octets) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(octets));
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private void installerExemple() {
        try {
            Files.createDirectories(dossier());
            Path liste = plugin.getDataFolder().toPath().resolve("donnees").resolve("exemples.txt");
            List<String> faits = new ArrayList<>(Files.isRegularFile(liste) ? Files.readAllLines(liste) : List.of());
            // squelette du pantin des joueurs et mannequin des réactions : toujours remis s'ils manquent
            for (String f : List.of("joueur.bbmodel", "mannequin_reactions.bbmodel")) {
                Path c = dossier().resolve(f);
                // squelette livré par une version précédente et pas retouché : remplacé par celui-ci
                if (Files.isRegularFile(c) && SQUELETTES_ANCIENS.contains(empreinte(Files.readAllBytes(c)))) Files.delete(c);
                if (Files.exists(c)) continue;
                try (InputStream in = plugin.getClass().getClassLoader().getResourceAsStream("exemples/modeles/" + f)) {
                    if (in != null) Files.copy(in, c);
                }
            }
            if (faits.contains("modeles")) return;
            Path cible = dossier().resolve("golem_rubis.bbmodel");
            if (!Files.exists(cible)) {
                try (InputStream in = plugin.getClass().getClassLoader().getResourceAsStream("exemples/modeles/golem_rubis.bbmodel")) {
                    if (in != null) Files.copy(in, cible);
                }
            }
            faits.add("modeles");
            Files.createDirectories(liste.getParent());
            Files.write(liste, faits);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible de créer l'exemple de modèle animé : " + e.getMessage());
        }
    }

}
