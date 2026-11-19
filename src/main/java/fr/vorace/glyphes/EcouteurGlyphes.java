package fr.vorace.glyphes;

import fr.vorace.Vorace;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Emojis dans le chat et sur les panneaux, suggestions du chat. */
public final class EcouteurGlyphes implements Listener {

    private final Vorace plugin;

    public EcouteurGlyphes(Vorace plugin) {
        this.plugin = plugin;
    }

    /**
     * Caractères des polices du pack (zone d'usage privé d'Unicode : U+E000 à U+F8FF et plans 15-16) : images de
     * l'interface, espaces négatifs, emojis. Tapés ou collés par un joueur, ils pourraient couvrir l'écran des autres,
     * imiter l'interface ou un message du serveur : on les retire de ce qu'écrivent les joueurs. Les emojis du chat
     * (glyphes à raccourci, hors fonds de menus, pas plus hauts qu'une ligne) restent, collés tels quels ou par leurs
     * raccourcis, si le joueur a leur permission.
     */
    static final java.util.regex.Pattern PRIVES = java.util.regex.Pattern.compile("[\\uE000-\\uF8FF\\x{F0000}-\\x{10FFFF}]");

    static boolean libre(org.bukkit.entity.Player p) {
        return p.hasPermission("vorace.admin") || p.hasPermission("vorace.glyphes.bruts");
    }

    /** Retire les caractères privés, sauf les emojis du chat que ce joueur a le droit d'utiliser (collés tels quels). */
    String nettoyer(org.bukkit.entity.Player p, String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = PRIVES.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, emojiPermis(p, m.group()) ? java.util.regex.Matcher.quoteReplacement(m.group()) : "");
        m.appendTail(b);
        return b.toString();
    }

    Component nettoyer(org.bukkit.entity.Player p, Component c) {
        return c == null ? null : c.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().match(PRIVES)
            .replacement((r, t) -> emojiPermis(p, r.group()) ? t : Component.empty()).build());
    }

    private boolean emojiPermis(org.bukkit.entity.Player p, String car) {
        return plugin.glyphes() != null && plugin.glyphes().emojiPermis(p, car.codePointAt(0));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chatNettoye(AsyncChatEvent e) {
        if (!libre(e.getPlayer())) e.message(nettoyer(e.getPlayer(), e.message()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("glyphes.chat", true)) return;
        e.message(plugin.glyphes().remplacer(e.getPlayer(), e.message()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void commande(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        if (!libre(e.getPlayer()) && PRIVES.matcher(e.getMessage()).find()) e.setMessage(nettoyer(e.getPlayer(), e.getMessage()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void livre(org.bukkit.event.player.PlayerEditBookEvent e) {
        if (libre(e.getPlayer())) return;
        org.bukkit.inventory.meta.BookMeta m = e.getNewBookMeta();
        java.util.List<Component> pages = new java.util.ArrayList<>();
        for (Component c : m.pages()) pages.add(nettoyer(e.getPlayer(), c));
        m.pages(pages);
        if (m.title() != null) m.title(nettoyer(e.getPlayer(), m.title()));
        e.setNewBookMeta(m);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void enclume(org.bukkit.event.inventory.PrepareAnvilEvent e) {
        if (!(e.getView().getPlayer() instanceof org.bukkit.entity.Player p) || libre(p)) return;
        String nom = e.getView().getRenameText();
        if (nom == null || !PRIVES.matcher(nom).find() || e.getResult() == null) return;
        String propre = nettoyer(p, nom);
        if (propre.equals(nom)) return;
        org.bukkit.inventory.ItemStack r = e.getResult().clone();
        r.editMeta(m -> m.displayName(Component.text(propre)));
        e.setResult(r);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void panneau(SignChangeEvent e) {
        boolean libre = libre(e.getPlayer());
        for (int i = 0; i < 4; i++) {
            Component l = e.line(i);
            if (l == null) continue;
            if (!libre) l = nettoyer(e.getPlayer(), l);
            if (plugin.getConfig().getBoolean("glyphes.panneaux", true)) l = plugin.glyphes().remplacer(e.getPlayer(), l);
            e.line(i, l);
        }
    }

    @EventHandler
    public void arrivee(PlayerJoinEvent e) {
        plugin.glyphes().mettreAJourSuggestions(e.getPlayer());
    }

    @EventHandler
    public void depart(PlayerQuitEvent e) {
        plugin.glyphes().oublier(e.getPlayer());
    }
}
