package fr.vorace.modeles;

import fr.vorace.modeles.ModeleBB.Cube;
import fr.vorace.modeles.ModeleBB.Partie;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Maillages de Blockbench : faces rectangulaires converties en éléments plats, triangles signalés. */
public final class TestMaillages {
    static int echecs = 0;
    static void verifier(boolean ok, String quoi) { System.out.println((ok ? "OK    " : "ECHEC ") + quoi); if (!ok) echecs++; }
    static boolean proche(double[] a, double... b) {
        for (int i = 0; i < b.length; i++) if (Math.abs(a[i] - b[i]) > 1e-3) return false;
        return true;
    }

    public static void main(String[] args) {
        // conversion matrice <-> angles (Z puis Y puis X) dans les deux sens
        for (double[] r : new double[][]{{0, 0, 0}, {30, 0, 0}, {0, -60, 0}, {10, 20, 30}, {-45, 70, 120}, {90, 0, 0}}) {
            double[] e = ConvertisseurMesh.euler(ConvertisseurMesh.matrice(r));
            double[][] m1 = ConvertisseurMesh.matrice(r), m2 = ConvertisseurMesh.matrice(e);
            boolean ok = true;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) ok &= Math.abs(m1[i][j] - m2[i][j]) < 1e-9;
            verifier(ok, "rotation " + Arrays.toString(r) + " relue en " + Arrays.toString(e));
        }

        List<String> problemes = new ArrayList<>();
        ModeleBB m = LecteurBBModel.lire("maillage", modele(), problemes);
        Partie corps = m.parties().stream().filter(p -> p.nom().equals("corps")).findFirst().orElse(null);
        verifier(corps != null, "os « corps » lu");
        if (corps == null) { fin(); return; }
        int iCorps = m.parties().indexOf(corps);
        List<Partie> detaches = m.parties().stream().filter(p -> p.parent() == iCorps).toList();

        // face droite dans le plan XY : élément plat dans l'os, sans rotation
        Cube plat = corps.cubes().stream().filter(c -> proche(c.de(), -4, 0, 0)).findFirst().orElse(null);
        verifier(plat != null && proche(plat.a(), 4, 8, 0) && plat.axe() == null, "face droite : élément plat de -4,0,0 à 4,8,0 dans l'os");
        verifier(plat != null && proche(plat.faces().get("south").uv(), 0, 0, 8, 8) && proche(plat.faces().get("north").uv(), 8, 0, 0, 8),
            "face droite : texture des deux côtés, à l'endroit");

        // face tournée de 22.5° autour de Y par le maillage : rotation permise dans un modèle d'objet, reste dans l'os
        Cube penche = corps.cubes().stream().filter(c -> "y".equals(c.axe())).findFirst().orElse(null);
        verifier(penche != null && Math.abs(penche.angle() - 22.5) < 1e-3, "maillage tourné de 22.5° : élément tourné dans l'os");

        // face inclinée de 30° autour de X par ses sommets : partie à elle seule, tournée par l'entité d'affichage
        Partie incline = detaches.stream().filter(p -> Math.abs(p.rotation()[0] - 30) < 1e-3 || Math.abs(p.rotation()[0] + 30) < 1e-3).findFirst().orElse(null);
        verifier(incline != null && Math.abs(incline.rotation()[1]) < 1e-3 && Math.abs(incline.rotation()[2]) < 1e-3,
            "face inclinée de 30° : partie détachée tournée de 30° autour de X " + (incline == null ? detaches.stream().map(p -> Arrays.toString(p.rotation())).toList() : ""));
        if (incline != null) {
            Cube c = incline.cubes().get(0);
            verifier(Math.abs(c.a()[0] - c.de()[0] - 6) < 1e-3 && Math.abs(c.a()[1] - c.de()[1] - 10) < 1e-3 && Math.abs(c.a()[2] - c.de()[2]) < 1e-6,
                "face inclinée : 6 × 10, épaisseur nulle");
        }

        verifier(problemes.stream().anyMatch(p -> p.contains("« ailes »") && p.contains("1 face(s) sur 2")), "triangle signalé : " + problemes);
        verifier(problemes.stream().noneMatch(p -> p.contains("type mesh")), "le maillage n'est plus « ignoré »");
        fin();
    }

    static void fin() {
        System.out.println(echecs == 0 ? "TOUS LES TESTS PASSENT" : echecs + " ECHEC(S)");
        System.exit(echecs == 0 ? 0 : 1);
    }

    static String modele() {
        double c = Math.cos(Math.toRadians(30)), s = Math.sin(Math.toRadians(30));
        // face de 6 × 10 inclinée de 30° autour de X : (x, y·cos, y·sin)
        String incl = String.format(java.util.Locale.ROOT, "\"i1\":[-3,0,0],\"i2\":[3,0,0],\"i3\":[3,%.6f,%.6f],\"i4\":[-3,%.6f,%.6f]", 10 * c, 10 * s, 10 * c, 10 * s);
        return """
            {"meta":{"format_version":"4.10","model_format":"free","box_uv":false},
             "resolution":{"width":16,"height":16},
             "textures":[{"name":"t","source":"data:image/png;base64,AAAA"}],
             "elements":[
              {"name":"ailes","type":"mesh","uuid":"m1","origin":[0,0,0],"rotation":[0,0,0],
               "vertices":{"a":[-4,0,0],"b":[4,0,0],"c":[4,8,0],"d":[-4,8,0],"e":[0,12,0]},
               "faces":{
                 "f1":{"vertices":["a","c","b","d"],"uv":{"a":[0,8],"b":[8,8],"c":[8,0],"d":[0,0]},"texture":0},
                 "f2":{"vertices":["c","d","e"],"uv":{"c":[8,8],"d":[0,8],"e":[4,0]},"texture":0},
                 "f3":{"vertices":["a","b","c","d"],"uv":{"a":[0,0],"b":[0,0],"c":[0,0],"d":[0,0]},"texture":null}}},
              {"name":"cape","type":"mesh","uuid":"m2","origin":[2,0,0],"rotation":[0,22.5,0],
               "vertices":{"a":[-2,0,0],"b":[2,0,0],"c":[2,4,0],"d":[-2,4,0]},
               "faces":{"f":{"vertices":["a","b","c","d"],"uv":{"a":[0,4],"b":[4,4],"c":[4,0],"d":[0,0]},"texture":0}}},
              {"name":"voile","type":"mesh","uuid":"m3","origin":[0,2,0],"rotation":[0,0,0],
               "vertices":{%s},
               "faces":{"f":{"vertices":["i1","i2","i3","i4"],"uv":{"i1":[0,10],"i2":[6,10],"i3":[6,0],"i4":[0,0]},"texture":0}}}
             ],
             "outliner":[{"name":"corps","uuid":"g1","origin":[0,0,0],"children":["m1","m2","m3"]}],
             "animations":[]}
            """.formatted(incl);
    }
}
