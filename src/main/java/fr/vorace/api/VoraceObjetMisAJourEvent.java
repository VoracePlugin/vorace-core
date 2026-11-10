package fr.vorace.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Un objet Vorace vient d'être refait avec sa définition actuelle (connexion, /vorace maj, /vorace recharger).
 *
 * <p>Vorace garde déjà sur le nouvel objet : quantité, usure, enchantements, nom donné à l'enclume, données des autres
 * plugins (PersistentDataContainer), lignes de description ajoutées par d'autres plugins, leurs modificateurs
 * d'attributs, garniture d'armure et coût de réparation. Un plugin qui range autre chose sur ses objets (par exemple
 * un custom_model_data) le remet ici sur {@link #getNouveau()}, ou remplace l'objet avec {@link #setNouveau}.
 */
public final class VoraceObjetMisAJourEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String objet;
    private final ItemStack ancien;
    private ItemStack nouveau;

    public VoraceObjetMisAJourEvent(String objet, ItemStack ancien, ItemStack nouveau) {
        this.objet = objet;
        this.ancien = ancien;
        this.nouveau = nouveau;
    }

    /** Identifiant Vorace de l'objet. */
    public String getObjet() { return objet; }
    /** L'objet tel qu'il était (à lire seulement). */
    public ItemStack getAncien() { return ancien; }
    /** L'objet qui va le remplacer. */
    public ItemStack getNouveau() { return nouveau; }
    public void setNouveau(ItemStack nouveau) { if (nouveau != null) this.nouveau = nouveau; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
