package fr.vorace.modeles;

import fr.vorace.Vorace;
import fr.vorace.paquets.Paquet;
import fr.vorace.paquets.Sens;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Corps fantôme du vrai joueur, quand son pantin le remplace.
 *
 * <p>Le joueur reste visible pour le serveur (les monstres de vorace-mob ignorent un joueur invisible) : seul le bit
 * « invisible » des données d'entité envoyées aux clients est levé, chez tout le monde, lui compris. Les autres ne le
 * voient donc plus du tout ; lui, chez lui seulement, est mis dans une équipe qui ne contient que lui, avec l'option
 * « voir les alliés invisibles » : le jeu le dessine en fantôme (semi-transparent). Le shader du pack de Vorace
 * (rendertype_item_entity_translucent_cull) rend ce fantôme opaque dans les menus (son personnage dans l'inventaire,
 * armure comprise) et l'efface dans le monde (F5) : là, c'est son pantin qu'il voit.</p>
 *
 * <p>Sans le shader (pack de shaders Iris ou OptiFine actif), le fantôme reste semi-transparent, dans l'inventaire comme
 * en F5 : c'est le repli prévu ({@code pantin.fantome.equipe-soi: false} le coupe, le joueur redevient alors totalement
 * invisible chez lui comme avant).</p>
 */
public final class Fantomes implements Listener {

    /** Bit « invisible » de l'octet de drapeaux partagés des entités (index 0). */
    static final byte INVISIBLE = 0x20;
    /** Nom de l'équipe envoyée à chaque joueur, qui ne contient que lui. */
    static final String EQUIPE = "vorace_soi";

    private final Vorace plugin;
    /** Joueurs cachés, par numéro d'entité (lus sur le fil réseau). */
    private final java.util.Map<Integer, Player> caches = new ConcurrentHashMap<>();
    /** Joueurs qui ont reçu leur équipe fantôme. */
    private final Set<UUID> equipes = ConcurrentHashMap.newKeySet();
    private boolean actif, equipeSoi;

    private Object accesseurDrapeaux;
    private Method creerValeur, handle, donneesEntite, lireDonnee;
    private Constructor<?> paquetDonnees;
    private Constructor<?> equipeJeu, tableauScores;
    private Method voirInvisibles, joueursEquipe, paquetEquipe, paquetEquipeRetrait;
    private Class<?> classeValeur;
    private RecordComponent[] champsValeur;
    private Constructor<?> valeurCanonique;

    public Fantomes(Vorace plugin) {
        this.plugin = plugin;
    }

    /** Pantin permanent : (entité, joueur qui regarde) → ce joueur est trop loin et voit le joueur du jeu. */
    private volatile java.util.function.BiPredicate<Integer, Player> lointain = (e, j) -> false;

    public void lointain(java.util.function.BiPredicate<Integer, Player> l) {
        lointain = l == null ? (e, j) -> false : l;
    }

    /** Branche les écoutes ; faux si la couche paquets manque ou si les classes du jeu ne sont pas celles attendues. */
    public boolean demarrer() {
        if (plugin.paquets() == null) return false;
        equipeSoi = plugin.getConfig().getBoolean("pantin.fantome.equipe-soi", true);
        try {
            ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
            Class<?> entite = Class.forName("net.minecraft.world.entity.Entity", false, cl);
            Field f = entite.getDeclaredField("DATA_SHARED_FLAGS_ID");
            f.setAccessible(true);
            accesseurDrapeaux = f.get(null);
            Class<?> accesseur = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", false, cl);
            Class<?> donnees = Class.forName("net.minecraft.network.syncher.SynchedEntityData", false, cl);
            classeValeur = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", false, cl);
            creerValeur = classeValeur.getMethod("create", accesseur, Object.class);
            champsValeur = classeValeur.getRecordComponents();
            Class<?>[] types = new Class<?>[champsValeur.length];
            for (int i = 0; i < types.length; i++) types[i] = champsValeur[i].getType();
            valeurCanonique = classeValeur.getDeclaredConstructor(types);
            valeurCanonique.setAccessible(true);
            donneesEntite = entite.getMethod("getEntityData");
            lireDonnee = donnees.getMethod("get", accesseur);
            Class<?> paquet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket", false, cl);
            paquetDonnees = paquet.getConstructor(int.class, List.class);
            handle = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".entity.CraftEntity", false, cl).getMethod("getHandle");

            Class<?> scores = Class.forName("net.minecraft.world.scores.Scoreboard", false, cl);
            Class<?> equipe = Class.forName("net.minecraft.world.scores.PlayerTeam", false, cl);
            tableauScores = scores.getConstructor();
            equipeJeu = equipe.getConstructor(scores, String.class);
            voirInvisibles = equipe.getMethod("setSeeFriendlyInvisibles", boolean.class);
            joueursEquipe = equipe.getMethod("getPlayers");
            Class<?> paquetE = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket", false, cl);
            paquetEquipe = paquetE.getMethod("createAddOrModifyPacket", equipe, boolean.class);
            paquetEquipeRetrait = paquetE.getMethod("createRemovePacket", equipe);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Fantômes : classes du jeu inattendues, le joueur sera caché à l'ancienne (invisible pour le serveur)", e);
            return false;
        }
        plugin.paquets().ecouter(plugin, Sens.SORTANT, this::donnees, "ClientboundSetEntityDataPacket");
        plugin.paquets().ecouter(plugin, Sens.SORTANT, this::equipeAutre, "ClientboundSetPlayerTeamPacket");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        actif = true;
        if (equipeSoi) for (Player p : Bukkit.getOnlinePlayers()) envoyerEquipe(p);
        return true;
    }

    public boolean actif() {
        return actif;
    }

    /** Le joueur est-il caché (ses données d'entité partent avec le bit invisible) ? */
    public boolean cache(Player p) {
        return caches.containsKey(p.getEntityId());
    }

    // ------------------------------------------------------------------ fil principal

    /** Cache le vrai corps du joueur chez tout le monde (lui compris : fantôme chez lui avec son équipe). */
    public void cacher(Player p) {
        if (!actif || caches.putIfAbsent(p.getEntityId(), p) != null) return;
        if (equipeSoi && !equipes.contains(p.getUniqueId())) envoyerEquipe(p);
        envoyerDrapeaux(p);
    }

    /** Rend son vrai corps au joueur, chez tout le monde. */
    public void montrer(Player p) {
        if (caches.remove(p.getEntityId()) == null) return;
        if (p.isOnline()) envoyerDrapeaux(p);
    }

    /** Envoie à lui et à tous ceux qui le voient l'octet de drapeaux actuel (bit invisible ajouté s'il est caché). */
    private void envoyerDrapeaux(Player p) {
        Set<Player> cibles = new HashSet<>();
        cibles.add(p);
        try {
            cibles.addAll(p.getTrackedBy());
        } catch (Throwable t) {
            for (Player autre : p.getWorld().getPlayers()) if (autre.getLocation().distanceSquared(p.getLocation()) < 128 * 128) cibles.add(autre);
        }
        for (Player c : cibles) envoyerDrapeaux(p, c);
    }

    /** Octet de drapeaux de {@code p} envoyé au seul joueur {@code a}. */
    void envoyerDrapeaux(Player p, Player a) {
        try {
            byte d = drapeaux(p);
            if (caches.containsKey(p.getEntityId()) && (a == p || !lointain.test(p.getEntityId(), a))) d |= INVISIBLE;
            Object valeur = creerValeur.invoke(null, accesseurDrapeaux, d);
            plugin.paquets().envoyer(a, paquetDonnees.newInstance(p.getEntityId(), List.of(valeur)), false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Fantômes : drapeaux de " + p.getName(), e);
        }
    }

    /** Octet de drapeaux partagés du joueur, tel que le serveur le connaît. */
    private byte drapeaux(Player p) throws ReflectiveOperationException {
        return (byte) lireDonnee.invoke(donneesEntite.invoke(handle.invoke(p)), accesseurDrapeaux);
    }

    /** L'équipe qui ne contient que lui, avec « voir les alliés invisibles », envoyée à lui seul. */
    private void envoyerEquipe(Player p) {
        try {
            plugin.paquets().envoyer(p, paquetEquipe.invoke(null, equipe(p), true), false);
            equipes.add(p.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Fantômes : équipe de " + p.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object equipe(Player p) throws ReflectiveOperationException {
        Object e = equipeJeu.newInstance(tableauScores.newInstance(), EQUIPE);
        voirInvisibles.invoke(e, true);
        ((Collection<String>) joueursEquipe.invoke(e)).add(p.getName());
        return e;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void arrivee(PlayerJoinEvent e) {
        if (actif && equipeSoi) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline()) envoyerEquipe(e.getPlayer());
        }, 2L);
    }

    /** Un joueur caché entre dans la vue d'un autre : le jeu ne renvoie pas un octet de drapeaux nul, on l'envoie. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void vue(io.papermc.paper.event.player.PlayerTrackEntityEvent e) {
        if (!actif || !(e.getEntity() instanceof Player cible) || !caches.containsKey(cible.getEntityId())) return;
        Player a = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (a.isOnline() && cible.isOnline()) envoyerDrapeaux(cible, a);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void depart(PlayerQuitEvent e) {
        caches.remove(e.getPlayer().getEntityId());
        equipes.remove(e.getPlayer().getUniqueId());
    }

    public void arreter() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            montrer(p);
            if (equipes.remove(p.getUniqueId())) {
                try {
                    plugin.paquets().envoyer(p, paquetEquipeRetrait.invoke(null, equipe(p)), false);
                } catch (ReflectiveOperationException | RuntimeException ignore) {
                    // le joueur garde l'équipe jusqu'à sa reconnexion : sans effet s'il n'est plus caché
                }
            }
        }
        caches.clear();
        actif = false;
    }

    // ------------------------------------------------------------------ fil réseau

    /**
     * Données d'entité d'un joueur caché : le bit invisible est ajouté à chaque octet de drapeaux envoyé. Un paquet sans
     * octet de drapeaux en reçoit un : c'est le cas des données envoyées avec l'apparition du joueur chez quelqu'un (le
     * jeu n'y met que les valeurs différentes de celles par défaut), le joueur n'est donc jamais visible, même un instant.
     */
    private void donnees(Paquet p) throws ReflectiveOperationException {
        Object id = p.lire("id");
        Player cache = id instanceof Integer n ? caches.get(n) : null;
        if (cache == null) return;
        // pantin permanent trop loin de ce joueur : il voit le joueur du jeu
        Player voit = p.joueur();
        if (voit != null && voit != cache && lointain.test(cache.getEntityId(), voit)) return;
        List<?> valeurs = (List<?>) p.lire("packedItems");
        if (valeurs == null) return;
        List<Object> nouvelles = null;
        boolean trouve = false;
        for (int i = 0; i < valeurs.size(); i++) {
            Object v = valeurs.get(i);
            Object[] parts = new Object[champsValeur.length];
            for (int k = 0; k < parts.length; k++) parts[k] = champsValeur[k].getAccessor().invoke(v);
            if (!(parts[0] instanceof Integer index) || index != 0 || !(parts[2] instanceof Byte b)) continue;
            trouve = true;
            if ((b & INVISIBLE) != 0) continue;
            parts[2] = (byte) (b | INVISIBLE);
            if (nouvelles == null) nouvelles = new ArrayList<>(valeurs);
            nouvelles.set(i, valeurCanonique.newInstance(parts));
        }
        if (!trouve) {
            if (nouvelles == null) nouvelles = new ArrayList<>(valeurs);
            nouvelles.add(creerValeur.invoke(null, accesseurDrapeaux, (byte) (drapeaux(cache) | INVISIBLE)));
        }
        if (nouvelles != null) p.ecrire("packedItems", nouvelles);
    }

    /**
     * Le serveur (tableau des scores principal, plugins) met ce joueur dans une autre équipe chez lui : le jeu l'enlève
     * alors de son équipe fantôme. On la lui renvoie juste après.
     */
    @SuppressWarnings("unchecked")
    private void equipeAutre(Paquet p) {
        Player j = p.joueur();
        if (j == null || !equipeSoi || !equipes.contains(j.getUniqueId())) return;
        Object nom = p.lire("name");
        if (EQUIPE.equals(nom)) return;
        Object joueurs = p.lire("players");
        if (joueurs instanceof Collection<?> c && c.contains(j.getName())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (j.isOnline()) envoyerEquipe(j);
            });
        }
    }
}
