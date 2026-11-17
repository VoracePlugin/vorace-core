package fr.vorace;

import java.util.ArrayList;
import java.util.List;

/** Découpe un config.yml en sections de premier niveau, chacune avec les commentaires qui la précèdent (sans Bukkit). */
public final class ConfigBlocs {

    private ConfigBlocs() {}

    public static List<String> blocs(String yaml) {
        List<String> res = new ArrayList<>();
        StringBuilder courant = new StringBuilder(), commentaires = new StringBuilder();
        boolean dansBloc = false;
        for (String ligne : yaml.split("\n", -1)) {
            boolean niveau0 = !ligne.isEmpty() && !Character.isWhitespace(ligne.charAt(0)) && !ligne.startsWith("#") && ligne.contains(":");
            if (niveau0) {
                if (dansBloc) res.add(courant.toString());
                courant = new StringBuilder(commentaires);
                commentaires = new StringBuilder();
                dansBloc = true;
                courant.append(ligne).append('\n');
            } else if (dansBloc && (ligne.startsWith(" ") || ligne.startsWith("\t"))) {
                courant.append(commentaires).append(ligne).append('\n');
                commentaires = new StringBuilder();
            } else {
                commentaires.append(ligne).append('\n');
            }
        }
        if (dansBloc) res.add(courant.toString());
        return res;
    }

    /** Clé de premier niveau d'un bloc (ligne « cle: ... »), ou null. */
    public static String cle(String bloc) {
        for (String l : bloc.split("\n")) {
            if (!l.isEmpty() && !Character.isWhitespace(l.charAt(0)) && !l.startsWith("#") && l.contains(":")) return l.substring(0, l.indexOf(':')).trim();
        }
        return null;
    }
}
