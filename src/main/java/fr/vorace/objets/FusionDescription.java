package fr.vorace.objets;

import java.util.ArrayList;
import java.util.List;

/**
 * Description d'un objet mis à jour : les lignes écrites par Vorace sont remplacées par celles de la définition
 * actuelle, les lignes ajoutées par un autre plugin (stats, qualité, forgeron) restent à leur place.
 *
 * <p>Vorace note sur l'objet l'empreinte du texte de chacune de ses lignes. À la mise à jour, il cherche ces lignes
 * dans la description de l'objet : ce qui est avant reste avant, ce qui est après reste après.
 */
public final class FusionDescription {

    private FusionDescription() { }

    /**
     * @param ancienne            description actuelle de l'objet
     * @param empreintesAnciennes empreinte du texte de chaque ligne de {@code ancienne} (même ordre)
     * @param empreintesVorace    empreintes notées sur l'objet : les lignes que Vorace y avait écrites
     * @param nouvelle            lignes de la définition actuelle
     * @return la description fusionnée, ou {@code null} si les lignes de Vorace ont été changées par un autre plugin
     *         (la description de l'objet est alors gardée telle quelle)
     */
    public static <T> List<T> fusionner(List<T> ancienne, List<String> empreintesAnciennes, List<String> empreintesVorace, List<T> nouvelle) {
        int n = empreintesVorace.size();
        int debut = -1;
        for (int k = 0; k + n <= empreintesAnciennes.size(); k++) {
            if (empreintesAnciennes.subList(k, k + n).equals(empreintesVorace)) { debut = k; break; }
        }
        if (debut < 0) return null;
        List<T> r = new ArrayList<>(ancienne.subList(0, debut));
        r.addAll(nouvelle);
        r.addAll(ancienne.subList(debut + n, ancienne.size()));
        return r;
    }

    /** Empreinte courte d'un texte (notée sur l'objet pour reconnaître les lignes et le nom écrits par Vorace). */
    public static String empreinte(String texte) {
        return Integer.toHexString((texte == null ? "" : texte).hashCode());
    }

    /** Liste d'empreintes rangée sur l'objet : « » pour aucune ligne, sinon séparées par des virgules. */
    public static String joindre(List<String> empreintes) {
        return String.join(",", empreintes);
    }

    public static List<String> lire(String rangees) {
        return rangees == null || rangees.isEmpty() ? List.of() : List.of(rangees.split(","));
    }
}
