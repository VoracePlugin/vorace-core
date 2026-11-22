package fr.vorace.modeles;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Apparitions visées : le dessin d'un sort (cible à 2 blocs) rejoué vers une vraie cible. */
public final class TestVisee {
    static int echecs = 0;
    static void verifier(boolean ok, String quoi) { System.out.println((ok ? "OK    " : "ECHEC ") + quoi); if (!ok) echecs++; }
    static boolean pres(Vector3f a, float x, float y, float z) { return a.distance(x, y, z) < 1e-3f; }

    public static void main(String[] args) {
        // lacet 0 : le jeu regarde vers +z ; une cible à 10 blocs, 3 blocs plus haut que les pieds
        float yaw = Visee.yawVers(0, 10);
        verifier(Math.abs(yaw) < 1e-4, "une cible plein sud donne le lacet 0");
        Visee v = new Visee(yaw, 10f, 3f, 1f);
        verifier(pres(v.deplacer(new Vector3f(0.3f, 1.4f, 0.5f)), 0.3f, 1.4f, 0.5f), "ce qui est derrière la main ne bouge pas");
        verifier(pres(v.deplacer(new Vector3f(0, Visee.HAUTEUR, Visee.CIBLE)), 0, 3f, 10f), "la cible dessinée tombe sur la vraie cible");
        float milieu = (Visee.MAIN + Visee.CIBLE) / 2;
        verifier(pres(v.deplacer(new Vector3f(0, Visee.HAUTEUR, milieu)), 0, (Visee.HAUTEUR + 3f) / 2, (Visee.MAIN + 10f) / 2), "à mi-course, le sort est à mi-chemin et à mi-hauteur");
        verifier(pres(v.deplacer(new Vector3f(0.5f, 0f, Visee.CIBLE + 1f)), 0.5f, 3f - Visee.HAUTEUR, 11f), "au-delà de la cible, les pièces la suivent sans s'étirer");
        // une cible vers l'est (+x) : lacet -90
        float est = Visee.yawVers(8, 0);
        verifier(Math.abs(est + 90) < 1e-3, "une cible à l'est donne le lacet -90");
        Visee e = new Visee(est, 8f, Visee.HAUTEUR, 1f);
        verifier(pres(e.deplacer(new Vector3f(Visee.CIBLE, Visee.HAUTEUR, 0)), 8f, Visee.HAUTEUR, 0), "l'avant du modèle suit le lacet");
        // une pièce tendue de la main à la cible : elle s'allonge jusqu'à la vraie cible et pointe vers elle
        Quaternionf q = new Quaternionf().rotateY((float) Math.PI);       // son axe (-z du modèle) regarde vers +z du monde
        float l = v.tendre(new Vector3f(0, Visee.HAUTEUR, Visee.MAIN), q);
        Vector3f bout = q.transform(new Vector3f(0, 0, -1)).mul((Visee.CIBLE - Visee.MAIN) * l).add(0, Visee.HAUTEUR, Visee.MAIN);
        verifier(pres(bout, 0, 3f, 10f), "un trait tendu de la main à la cible arrive sur la vraie cible");
        Quaternionf q2 = new Quaternionf().rotateY((float) Math.PI);
        verifier(Math.abs(v.tendre(new Vector3f(0, 1f, Visee.CIBLE + 0.5f), q2) - 1f) < 1e-4, "un trait au-delà de la cible ne s'étire pas");
        // une cible plus proche que le dessin : le sort raccourcit, sans se retourner
        Visee p = new Visee(yaw, 0.5f, Visee.HAUTEUR, 1f);
        verifier(p.deplacer(new Vector3f(0, Visee.HAUTEUR, Visee.CIBLE)).z >= Visee.MAIN, "une cible collée au lanceur ne renvoie pas le sort en arrière");
        // à la taille 2, les repères doublent
        Visee g = new Visee(yaw, 12f, 2 * Visee.HAUTEUR, 2f);
        verifier(pres(g.deplacer(new Vector3f(0, 2 * Visee.HAUTEUR, 2 * Visee.CIBLE)), 0, 2 * Visee.HAUTEUR, 12f), "la taille de l'apparition est prise en compte");
        if (echecs > 0) { System.out.println(echecs + " échec(s)"); System.exit(1); }
        System.out.println("Tout est bon.");
    }
}
