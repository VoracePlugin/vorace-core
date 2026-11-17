package fr.vorace.modeles;

import fr.vorace.ConfigBlocs;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pantin des joueurs : découpage du skin, squelette « joueur », mannequin des réactions, sections de config.yml. */
public final class TestPantins {
    static int echecs = 0;
    static void verifier(boolean ok, String quoi) { System.out.println((ok ? "OK    " : "ECHEC ") + quoi); if (!ok) echecs++; }

    static ModeleBB lire(String nom) throws Exception {
        try (InputStream in = TestPantins.class.getClassLoader().getResourceAsStream("exemples/modeles/" + nom + ".bbmodel")) {
            List<String> pb = new ArrayList<>();
            ModeleBB m = LecteurBBModel.lire(nom, new String(in.readAllBytes(), StandardCharsets.UTF_8), pb);
            pb.forEach(p -> System.out.println("  problème : " + p));
            verifier(pb.isEmpty(), nom + ".bbmodel lu sans problème");
            return m;
        }
    }

    public static void main(String[] args) throws Exception {
        // squelette : 11 os « peau_ » d'un cube, tailles du joueur
        ModeleBB j = lire("joueur");
        long peaux = j.parties().stream().filter(p -> p.nom() != null && p.nom().startsWith("peau_") && p.cubes().size() == 1).count();
        verifier(peaux == 11, "joueur : 11 os peau_ d'un seul cube (tête + 10 morceaux), trouvé " + peaux);
        boolean tousLesMorceaux = true;
        for (String mo : Peau.MORCEAUX) tousLesMorceaux &= j.parties().stream().anyMatch(p -> ("peau_" + mo).equals(p.nom()));
        verifier(tousLesMorceaux, "joueur : un os par morceau de skin (" + Peau.MORCEAUX.size() + ")");
        ModeleBB.Partie bras = j.parties().stream().filter(p -> "peau_bras_droit_haut".equals(p.nom())).findFirst().orElseThrow();
        ModeleBB.Cube c = bras.cubes().get(0);
        verifier(c.a()[0] - c.de()[0] == 4 && c.a()[1] - c.de()[1] == 6 && c.a()[2] - c.de()[2] == 4 && c.de()[0] > 0,
            "joueur : haut du bras droit 4 x 6 x 4, côté +x (droite quand l'avant regarde le nord)");
        verifier(j.animations().containsKey("essai") && j.animations().containsKey("croix") && j.animations().containsKey("salut"),
            "joueur : animations essai, salut, croix");

        // mannequin des réactions
        ModeleBB m = lire("mannequin_reactions");
        List<String> manquantes = new ArrayList<>();
        for (String r : List.of("saisi", "plaque", "souleve", "projete", "repousse", "etourdi", "resiste")) if (!m.animations().containsKey(r)) manquantes.add(r);
        verifier(manquantes.isEmpty() && m.animations().containsKey("repos"), "mannequin : repos et les 7 réactions " + manquantes);

        // découpage : chaque face d'un morceau atterrit au bon endroit de la zone tête
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) skin.setRGB(x, y, 0xFF000000 | (x << 16) | (y << 8));
        Map<String, BufferedImage> d = Peau.decouper(skin, false);
        verifier(d.size() == 10, "découpage : 10 morceaux");
        BufferedImage h = d.get("bras_droit_haut"), b = d.get("bras_droit_bas");
        // avant du bras droit : (44, 20) 4 x 12 ; haut = lignes 20 à 25, bas = 26 à 31 ; avant de la tête : (8, 8) 8 x 8
        verifier(h.getRGB(8, 8) == skin.getRGB(44, 20) && h.getRGB(15, 15) == skin.getRGB(47, 25),
            "bras droit (haut) : avant du bras -> avant de la tête, 4 x 6 étiré en 8 x 8");
        verifier(b.getRGB(8, 8) == skin.getRGB(44, 24) && b.getRGB(15, 15) == skin.getRGB(47, 31), "bras droit (bas) : lignes 24 à 31 (deux de recouvrement)");
        verifier(h.getRGB(40, 8) == skin.getRGB(44, 36), "manche droite -> chapeau (seconde couche)");
        verifier(h.getRGB(8, 0) == skin.getRGB(44, 16), "dessus du bras -> dessus de la tête");
        BufferedImage corps = d.get("corps_haut");
        verifier(corps.getRGB(8, 8) == skin.getRGB(20, 20) && corps.getRGB(15, 15) == skin.getRGB(27, 25), "corps (haut) : avant 8 x 6");
        verifier(corps.getRGB(40, 40) == 0 && corps.getRGB(0, 40) == 0, "le reste du skin d'un morceau est vide");
        BufferedImage fin = Peau.decouper(skin, true).get("bras_droit_haut");
        verifier(fin.getRGB(15, 8) == skin.getRGB(46, 20), "bras fins : 3 pixels de large étirés sur 8");
        BufferedImage ancien = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) ancien.setRGB(x, y, skin.getRGB(x, y));
        verifier(Peau.decouper(ancien, false).get("bras_gauche_haut").getRGB(8, 8) == skin.getRGB(44, 20),
            "ancien skin 64 x 32 : le bras gauche reprend le droit");

        // config.yml : sections de premier niveau avec leurs commentaires
        String yaml = "# en-tête\npack:\n  a: 1\n\n# le pantin\npantin:\n  # clé\n  mineskin-cle: \"\"\n  correction:\n    rotation-y: 0\nautre: 3\n";
        List<String> blocs = ConfigBlocs.blocs(yaml);
        verifier(blocs.size() == 3 && ConfigBlocs.cle(blocs.get(1)).equals("pantin") && blocs.get(1).startsWith("\n# le pantin\npantin:")
                && blocs.get(1).contains("    rotation-y: 0") && ConfigBlocs.cle(blocs.get(2)).equals("autre"),
            "config.yml : sections découpées avec leurs commentaires " + blocs);
        // clés d'effet enrichies
        EffetSpec f = EffetSpec.lire("minecraft:flame*24@poing_droit~0.2^0.04");
        verifier(f.nom().equals("flame") && f.nombre() == 24 && "poing_droit".equals(f.os()) && Math.abs(f.ecart() - 0.2) < 1e-9
            && Math.abs(f.vitesse() - 0.04) < 1e-9, "particule : nom, nombre, os, écart, vitesse");
        EffetSpec du = EffetSpec.lire("dust#FF6A00:1.8*18");
        verifier(du.nom().equals("dust") && du.couleur() == 0xFF6A00 && Math.abs(du.taille() - 1.8f) < 1e-6 && du.nombre() == 18 && du.os() == null,
            "particule colorée : dust#RRGGBB:taille");
        EffetSpec son = EffetSpec.lire("entity.generic.explode*1.6^0.8");
        verifier(son.nom().equals("entity.generic.explode") && Math.abs(son.volume() - 1.6f) < 1e-6 && Math.abs(son.hauteur() - 0.8f) < 1e-6,
            "son : volume et hauteur");
        EffetSpec simple = EffetSpec.lire("heart");
        verifier(simple.nom().equals("heart") && simple.nombre() == 6 && simple.os() == null, "ancienne écriture (nom seul) inchangée");
        boolean reperes = j.parties().stream().filter(p -> List.of("poing_droit", "poing_gauche", "pied_droit", "pied_gauche").contains(p.nom())).count() == 4;
        verifier(reperes, "joueur : repères poing_droit, poing_gauche, pied_droit, pied_gauche");
        if (echecs > 0) System.exit(1);
    }
}
