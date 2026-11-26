package fr.vorace.modeles;

import fr.vorace.Vorace;
import fr.vorace.paquets.Paquet;
import fr.vorace.paquets.Sens;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Morceaux du pantin montés sur le joueur seulement chez les clients.
 *
 * <p>De vrais passagers suivent le joueur sans aucun retard (son jeu les place lui-même à chaque image), mais Paper
 * refuse alors de téléporter le joueur ({@code Player#teleport} renvoie faux sans rien dire tant qu'il porte quelqu'un) :
 * impossible avec un pantin permanent. Ici, le serveur ne connaît aucun passager : les morceaux sont annoncés comme
 * passagers du joueur dans les paquets envoyés aux clients, et leurs déplacements (inutiles, le client les place) ne
 * sont plus envoyés. Le serveur continue de les garder près du joueur pour qu'ils restent suivis.</p>
 */
public final class Montures implements Listener {

    private final Vorace plugin;
    /** Numéro d'entité du joueur porteur → numéros de ses morceaux. */
    private final Map<Integer, Set<Integer>> parPorteur = new ConcurrentHashMap<>();
    /** Numéro d'un morceau → numéro de son porteur. */
    private final Map<Integer, Integer> porteurDe = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> joueurs = new ConcurrentHashMap<>();
    /** Morceaux déjà annoncés comme passagers aux clients (avant : ils sont encore posés aux pieds du joueur). */
    private final Set<Integer> annonces = ConcurrentHashMap.newKeySet();
    private boolean actif;

    private Constructor<?> paquetPassagers;
    private Method handle;

    public Montures(Vorace plugin) {
        this.plugin = plugin;
    }

    public boolean demarrer() {
        if (plugin.paquets() == null) return false;
        try {
            ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
            Class<?> entite = Class.forName("net.minecraft.world.entity.Entity", false, cl);
            paquetPassagers = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket", false, cl).getConstructor(entite);
            handle = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".entity.CraftEntity", false, cl).getMethod("getHandle");
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Montures par paquets indisponibles : le pantin sera monté pour de vrai (téléportations bloquées pendant une animation)", e);
            return false;
        }
        plugin.paquets().ecouter(plugin, Sens.SORTANT, this::passagers, "ClientboundSetPassengersPacket");
        plugin.paquets().ecouter(plugin, Sens.SORTANT, this::deplacement, "ClientboundTeleportEntityPacket", "ClientboundEntityPositionSyncPacket",
            "ClientboundMoveEntityPacket.Pos", "ClientboundMoveEntityPacket.PosRot", "ClientboundMoveEntityPacket.Rot");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        actif = true;
        return true;
    }

    public boolean actif() {
        return actif;
    }

    /** Ce morceau est-il monté sur un joueur chez les clients (passagers déjà annoncés) ? */
    public boolean monte(Entity e) {
        return e != null && annonces.contains(e.getEntityId());
    }

    /** Morceau qui vient d'apparaître et attend d'être annoncé comme passager (le pantin le garde invisible). */
    public boolean enAttente(Entity e) {
        return e != null && porteurDe.containsKey(e.getEntityId()) && !annonces.contains(e.getEntityId());
    }

    /** Ce morceau est-il enregistré sur un joueur (annoncé ou pas encore) ? */
    public boolean enregistre(Entity e) {
        return e != null && porteurDe.containsKey(e.getEntityId());
    }

    /** Monte ces morceaux sur le joueur, chez tous les clients qui le voient (lui compris). */
    public void monter(Player porteur, Collection<? extends Entity> morceaux) {
        Set<Integer> ids = parPorteur.computeIfAbsent(porteur.getEntityId(), k -> ConcurrentHashMap.newKeySet());
        for (Entity e : morceaux) {
            if (e == null) continue;
            ids.add(e.getEntityId());
            porteurDe.put(e.getEntityId(), porteur.getEntityId());
        }
        joueurs.put(porteur.getEntityId(), porteur.getUniqueId());
        // les morceaux viennent d'apparaître : le client ne les connaît qu'après le prochain passage du suivi des entités
        Bukkit.getScheduler().runTaskLater(plugin, () -> annoncer(porteur), 2L);
    }

    /** Oublie ces morceaux (retirés du monde). */
    public void oublier(Collection<? extends Entity> morceaux) {
        for (Entity e : morceaux) {
            if (e == null) continue;
            annonces.remove(e.getEntityId());
            Integer p = porteurDe.remove(e.getEntityId());
            if (p == null) continue;
            Set<Integer> ids = parPorteur.get(p);
            if (ids != null) {
                ids.remove(e.getEntityId());
                if (ids.isEmpty()) {
                    parPorteur.remove(p);
                    joueurs.remove(p);
                }
            }
        }
    }

    /** Tous les morceaux descendent du joueur chez les clients (caméra de cinéma : le pantin reste sur place). */
    public void descendre(Player porteur) {
        Set<Integer> ids = parPorteur.remove(porteur.getEntityId());
        joueurs.remove(porteur.getEntityId());
        if (ids == null) return;
        for (Integer i : ids) {
            porteurDe.remove(i);
            annonces.remove(i);
        }
        Set<Player> cibles = new HashSet<>();
        cibles.add(porteur);
        try {
            cibles.addAll(porteur.getTrackedBy());
        } catch (Throwable t) {
            cibles.addAll(porteur.getWorld().getPlayers());
        }
        try {
            Object paquet = paquetPassagers.newInstance(handle.invoke(porteur));   // ses vrais passagers seulement
            for (Player c : cibles) plugin.paquets().envoyer(c, paquet, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Montures : descente de " + porteur.getName(), e);
        }
    }

    /** Envoie les passagers du joueur à lui et à tous ceux qui le voient. */
    void annoncer(Player porteur) {
        if (!porteur.isOnline()) return;
        Set<Player> cibles = new HashSet<>();
        cibles.add(porteur);
        try {
            cibles.addAll(porteur.getTrackedBy());
        } catch (Throwable t) {
            cibles.addAll(porteur.getWorld().getPlayers());
        }
        for (Player c : cibles) annoncer(porteur, c);
        Set<Integer> ids = parPorteur.get(porteur.getEntityId());
        if (ids != null) annonces.addAll(ids);
    }

    private void annoncer(Player porteur, Player a) {
        Set<Integer> ids = parPorteur.get(porteur.getEntityId());
        if (ids == null || ids.isEmpty()) return;
        try {
            Object paquet = paquetPassagers.newInstance(handle.invoke(porteur));
            plugin.paquets().envoyer(a, avecNotres(paquet, porteur.getEntityId()), false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Montures : passagers de " + porteur.getName(), e);
        }
    }

    /** Le paquet avec nos morceaux ajoutés à ses vrais passagers. */
    private Object avecNotres(Object paquet, int porteur) throws ReflectiveOperationException {
        Set<Integer> ids = parPorteur.get(porteur);
        if (ids == null || ids.isEmpty()) return paquet;
        java.lang.reflect.Field f = champ(paquet.getClass(), "passengers");
        int[] vrais = (int[]) f.get(paquet);
        Set<Integer> tous = new LinkedHashSet<>();
        if (vrais != null) for (int v : vrais) tous.add(v);
        tous.addAll(ids);
        f.set(paquet, tous.stream().mapToInt(Integer::intValue).toArray());
        return paquet;
    }

    private static java.lang.reflect.Field champ(Class<?> c, String nom) throws NoSuchFieldException {
        java.lang.reflect.Field f = c.getDeclaredField(nom);
        f.setAccessible(true);
        return f;
    }

    /** Un client voit apparaître le joueur ou un de ses morceaux : on lui renvoie les passagers au tic suivant. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void vue(io.papermc.paper.event.player.PlayerTrackEntityEvent e) {
        if (!actif) return;
        int id = e.getEntity().getEntityId();
        Integer porteur = parPorteur.containsKey(id) ? Integer.valueOf(id) : porteurDe.get(id);
        if (porteur == null) return;
        UUID u = joueurs.get(porteur);
        Player a = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = u == null ? null : Bukkit.getPlayer(u);
            if (p != null && a.isOnline()) annoncer(p, a);
        }, 1L);
    }

    // ------------------------------------------------------------------ fil réseau

    /** Le jeu annonce les vrais passagers du joueur (souvent aucun) : nos morceaux y sont ajoutés. */
    private void passagers(Paquet p) throws ReflectiveOperationException {
        Object v = p.lire("vehicle");
        if (v instanceof Integer n && parPorteur.containsKey(n)) avecNotres(p.brut(), n);
    }

    /** Déplacement d'un morceau monté : inutile (le client le place sur le joueur), il n'est pas envoyé. */
    private void deplacement(Paquet p) {
        Object id = p.nom().startsWith("ClientboundMoveEntityPacket") ? p.lire("entityId") : p.lire("id");
        if (id instanceof Integer n && porteurDe.containsKey(n)) p.annuler();
    }
}
