package fr.vorace;

import fr.vorace.pack.Constructeur;

import java.util.List;

/** Teste les packs gardés après une reconstruction (servis encore un moment par leur empreinte). */
public final class TestGestionPack {
    static int echecs = 0;
    static void verifier(boolean ok, String quoi) { System.out.println((ok ? "OK    " : "ECHEC ") + quoi); if (!ok) echecs++; }

    static Constructeur.Resultat pack(String sha1) { return new Constructeur.Resultat(new byte[0], sha1, 0, null, 0); }

    static String empreintes(List<Constructeur.Resultat> l) {
        return String.join(",", l.stream().map(Constructeur.Resultat::sha1).toList());
    }

    public static void main(String[] a) {
        Constructeur.Resultat p1 = pack("a"), p2 = pack("b"), p3 = pack("c"), p4 = pack("d");
        List<Constructeur.Resultat> l = GestionPack.garder(List.of(), null, p1);
        verifier(l.isEmpty(), "première construction : rien à garder");
        l = GestionPack.garder(l, p1, p2);
        verifier(empreintes(l).equals("a"), "2e construction : le 1er pack est gardé");
        l = GestionPack.garder(l, p2, p3);
        verifier(empreintes(l).equals("b,a"), "3e construction : les deux précédents, le plus récent d'abord");
        l = GestionPack.garder(l, p3, p4);
        verifier(empreintes(l).equals("c,b"), "4e construction : le plus ancien n'est plus gardé (" + GestionPack.PACKS_GARDES + " au plus)");
        verifier(GestionPack.garder(l, p4, pack("d")) == l, "même empreinte (rien n'a changé) : rien ne bouge");
        verifier(empreintes(GestionPack.garder(List.of(p2), p3, p2)).equals("c"), "retour à un ancien pack : il n'est pas gardé en double");

        System.out.println(echecs == 0 ? "TOUS LES TESTS PASSENT" : echecs + " ECHEC(S)");
        System.exit(echecs == 0 ? 0 : 1);
    }
}
