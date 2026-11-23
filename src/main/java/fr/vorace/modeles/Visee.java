package fr.vorace.modeles;

import fr.vorace.modeles.Animateur.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Apparition visée : un sort dessiné pour une cible à deux blocs devant le lanceur est joué vers une vraie cible, à
 * n'importe quelle distance et n'importe quelle hauteur.
 *
 * <p>Le modèle garde son dessin : ce sont les <b>places</b> de ses pièces qui changent. Entre la main du lanceur
 * ({@link #MAIN}) et la cible dessinée ({@link #CIBLE}), les distances sont multipliées pour que la cible dessinée
 * tombe sur la vraie ; ce qui est derrière la main ne bouge pas, ce qui est au-delà de la cible la suit sans s'étirer.
 * La hauteur monte ou descend en même temps, jusqu'à celle de la vraie cible. Une pièce et ses pièces filles bougent
 * ensemble (un projectile ne se défait pas). Une pièce dont le nom finit par {@code _tendu} est un trait tiré d'un
 * point vers un autre (éclair, rayon) : elle est en plus retournée et allongée pour rejoindre le point suivant.
 */
public final class Visee {

    /** Repères du dessin, en blocs à la taille 1 : la main, la cible, la hauteur de la cible. */
    public static final float MAIN = 11f / 16f, CIBLE = 2f, HAUTEUR = 21f / 16f;
    public static final String TENDU = "_tendu";

    private final float ax, az;          // l'avant du modèle dans le monde (horizontal, de longueur 1)
    private final float s0, sc;          // la main et la cible dessinée, à la taille du modèle
    private final float distance;        // la vraie cible : distance au sol depuis le lanceur
    private final float distanceVraie;   // la même, sans minimum (mode posé)
    private final float monte;           // ce qu'il faut monter (ou descendre) pour être à sa hauteur
    private final float k;               // facteur d'allongement entre la main et la cible
    private final float taille;
    /** Sort posé sur la cible (sceau au sol, zone, pluie d'éclairs) : tout le modèle se déplace d'un bloc, sans s'étirer. */
    private boolean pose;
    private float centrePose;

    /**
     * @param yaw      l'orientation donnée à l'apparition (voir {@link #yawVers})
     * @param distance distance horizontale du lanceur à la vraie cible, en blocs
     * @param hauteur  hauteur de la vraie cible au-dessus des pieds du lanceur, en blocs
     * @param taille   taille de l'apparition
     */
    public Visee(float yaw, float distance, float hauteur, float taille) {
        Vector3f avant = new Quaternionf().rotateY((float) (Math.PI - Math.toRadians(yaw))).transform(new Vector3f(0, 0, -1));
        this.ax = avant.x;
        this.az = avant.z;
        this.taille = taille;
        this.s0 = MAIN * taille;
        this.sc = CIBLE * taille;
        this.distance = Math.max(sc * 0.75f, distance);
        this.distanceVraie = distance;
        this.monte = hauteur - HAUTEUR * taille;
        this.k = (this.distance - s0) / (sc - s0);
    }

    /**
     * Mode « posé sur la cible » : le modèle, dessiné centré à {@code centre} blocs devant le lanceur (2 par défaut, la cible
     * dessinée), est déplacé d'un seul bloc jusqu'à la vraie cible. Rien n'est étiré : un cercle au sol reste un cercle, centré
     * sur la cible (une zone étirée entre la main et la cible s'allongeait et se décalait).
     */
    public Visee posee(float centre) {
        this.pose = true;
        this.centrePose = centre * taille;
        return this;
    }

    /** L'orientation (lacet du jeu) qui tourne l'avant d'un modèle vers un point situé à (dx, dz) de lui. */
    public static float yawVers(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** La nouvelle place d'un point du modèle (repère du monde, autour de la base de l'apparition). */
    public Vector3f deplacer(Vector3f t) {
        if (pose) {
            float dd = distanceVraie - centrePose;
            return new Vector3f(t.x + ax * dd, t.y + monte, t.z + az * dd);
        }
        float s = t.x * ax + t.z * az;
        if (s <= s0) return new Vector3f(t);
        float s2 = s <= sc ? s0 + (s - s0) * k : distance + (s - sc);
        float f = Math.min(1f, (s - s0) / (sc - s0));
        return new Vector3f(t.x + ax * (s2 - s), t.y + monte * f, t.z + az * (s2 - s));
    }

    /**
     * Une pièce tendue d'un point vers un autre : ses deux bouts sont déplacés, et sa rotation est corrigée (sur place) pour
     * qu'elle aille de l'un à l'autre.
     *
     * @param t        la place de son départ (son pivot)
     * @param longueur sa longueur dessinée, en blocs
     * @return de combien elle doit s'allonger (son échelle le long de son axe)
     */
    public float tendre(Vector3f t, Quaternionf rotation, float longueur) {
        if (longueur <= 1e-4f) return 1f;
        Vector3f u = rotation.transform(new Vector3f(0, 0, -1));
        Vector3f bout = new Vector3f(u).mul(longueur).add(t);
        Vector3f v = deplacer(bout).sub(deplacer(t));
        float l = v.length();
        if (l < 1e-5f) return 1f;
        v.div(l);
        new Quaternionf().rotationTo(u, v).mul(rotation, rotation);
        return l / longueur;
    }

    /** Les poses d'un modèle, visées : chaque pièce suit le déplacement de la pièce de tête dont elle descend. */
    public Pose[] appliquer(ModeleBB m, Pose[] poses) {
        int n = poses.length;
        Pose[] r = new Pose[n];
        for (int i = 0; i < n; i++) {
            Pose p = poses[i];
            if (p == null) continue;
            int a = tete(m, i);
            Pose pa = poses[a] == null ? p : poses[a];
            Vector3f t = new Vector3f(p.translation()).add(deplacer(pa.translation()).sub(pa.translation()));
            String nom = m.parties().get(i).nom();
            if (nom != null && nom.endsWith(TENDU)) {
                Quaternionf q = new Quaternionf(p.rotation());
                float l = tendre(p.translation(), q, longueur(m.parties().get(i)) * taille);
                r[i] = new Pose(t, q, new Vector3f(p.echelle()).mul(1, 1, l));
            } else {
                r[i] = new Pose(t, p.rotation(), p.echelle());
            }
        }
        return r;
    }

    /** Longueur dessinée d'une pièce tendue, en blocs à la taille 1 : de son pivot au bout de ses pavés, le long de son -z. */
    static float longueur(ModeleBB.Partie p) {
        double bout = p.pivot()[2];
        for (ModeleBB.Cube c : p.cubes()) bout = Math.min(bout, Math.min(c.de()[2], c.a()[2]));
        return (float) ((p.pivot()[2] - bout) / 16.0);
    }

    /** La pièce de tête d'une pièce : son ancêtre posé juste sous la racine du modèle (elle-même si elle n'en a pas). */
    static int tete(ModeleBB m, int i) {
        int a = i;
        for (int garde = 0; garde < 64; garde++) {
            int parent = m.parties().get(a).parent();
            if (parent < 0 || m.parties().get(parent).parent() < 0) return a;
            a = parent;
        }
        return a;
    }
}
