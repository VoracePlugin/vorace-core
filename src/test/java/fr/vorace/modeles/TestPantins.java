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

        // armure du pantin : modèles posés sur les os, zones des textures d'équipement (64 x 32)
        java.util.Map<String, byte[]> arm = ArmuresPantin.generer(j, ArmuresPantin.VANILLE);
        java.util.Map<String, Boolean> idsArm = ArmuresPantin.ids(j, ArmuresPantin.VANILLE);
        verifier(arm.containsKey("assets/minecraft/atlases/blocks.json") && idsArm.size() == 6 * 17 + 1 && arm.size() == idsArm.size() + 1,
            "armure : atlas + un modèle par os, couche et matière (" + idsArm.size() + ")");
        com.google.gson.JsonObject fer = com.google.gson.JsonParser.parseString(new String(arm.get("assets/vorace/models/pantin_armure/minecraft/iron/humanoid/corps_haut.json"),
            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        com.google.gson.JsonObject el = fer.getAsJsonArray("elements").get(0).getAsJsonObject();
        verifier(fer.getAsJsonObject("textures").get("0").getAsString().equals("minecraft:entity/equipment/humanoid/iron")
                && el.get("from").toString().equals("[2.95,6.5,4.95]") && el.get("to").toString().equals("[13.05,15.0,11.05]")
                && el.getAsJsonObject("faces").getAsJsonObject("north").get("uv").toString().equals("[5.0,10.0,7.0,13.0]")
                && !el.getAsJsonObject("faces").has("down"),
            "armure : plastron sur le haut du tronc (qui descend d'un demi-pixel sur le bas), gonflé d'un pixel, avant = zone avant du tronc lignes 0 à 5");
        com.google.gson.JsonObject jb = com.google.gson.JsonParser.parseString(new String(arm.get("assets/vorace/models/pantin_armure/minecraft/iron/humanoid_leggings/jambe_gauche_bas.json"),
            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("elements").get(0).getAsJsonObject();
        verifier(jb.getAsJsonObject("faces").getAsJsonObject("north").get("uv").toString().equals("[2.0,12.0,1.0,16.0]")
                && jb.getAsJsonObject("faces").getAsJsonObject("up").get("uv").toString().equals("[2.0,12.0,1.0,12.5]"),
            "armure : jambière gauche en miroir, moitié basse avec recouvrement (lignes 4 à 11), genou fermé par la ligne du haut");
        com.google.gson.JsonObject cuir = com.google.gson.JsonParser.parseString(new String(arm.get("assets/vorace/models/pantin_armure/minecraft/leather/humanoid/tete.json"),
            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        verifier(cuir.getAsJsonArray("elements").size() == 2 && cuir.getAsJsonObject("textures").get("1").getAsString().endsWith("leather_overlay")
                && idsArm.get("pantin_armure/minecraft/leather/humanoid/tete"),
            "armure : cuir teint + couche non teinte");
        verifier(el.getAsJsonObject("faces").getAsJsonObject("north").get("tintindex").getAsInt() == 0
                && cuir.getAsJsonArray("elements").get(1).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north").get("tintindex").getAsInt() == 1,
            "armure : faces teintables (0 : cuir ou marque, 1 : surcouche du cuir) pour marquer les bras de sa copie");

        // marque des bras de sa propre copie (effacés à la première personne par le shader)
        int marque = MarqueSoi.marquer(0x8A5C3F);
        verifier((marque & 0x030303) == 0x010201 && Math.abs(((marque >> 16) & 0xFF) - 0x8A) <= 3 && Math.abs((marque & 0xFF) - 0x3F) <= 3
                && (MarqueSoi.eviter(marque) & 0x030303) != 0x010201 && MarqueSoi.eviter(0x8A5C3C) == 0x8A5C3C && (0xFFFFFF & 0x030303) != 0x010201,
            "marque : deux bits bas (1, 2, 1), écart invisible, évitée par les autres couleurs, absente du blanc");
        boolean brasOk = true, autresOk = true;
        for (int k = 0; k < j.parties().size(); k++) {
            String n = String.valueOf(j.parties().get(k).nom()).toLowerCase(java.util.Locale.ROOT);
            boolean estBras = MarqueSoi.bras(j, k);
            if (n.contains("bras") || n.startsWith("poing")) brasOk &= estBras; else autresOk &= !estBras;
        }
        verifier(brasOk && autresOk, "marque : bras, avant-bras et poings, rien d'autre (tête, tronc, jambes)");
        // tête de sa copie (pantin permanent) : marquée elle aussi, effacée à la première personne ; elle seule
        boolean teteOk = true;
        for (int k = 0; k < j.parties().size(); k++) {
            String n = String.valueOf(j.parties().get(k).nom()).toLowerCase(java.util.Locale.ROOT);
            teteOk &= MarqueSoi.tete(j, k) == n.contains("tete");
        }
        verifier(teteOk, "marque : la tête (et ce qu'elle porte), pas le tronc ni les membres");

        // images presque identiques : pas renvoyées (0,1° et un centième de pixel) ; un vrai mouvement l'est (1°, ou un pixel)
        org.joml.Vector3f v0 = new org.joml.Vector3f(0.5f, 1f, 0.25f);
        org.joml.Quaternionf q0 = new org.joml.Quaternionf().rotateXYZ(0.4f, 1.2f, -0.3f);
        org.joml.Quaternionf q1 = new org.joml.Quaternionf(q0).rotateX((float) Math.toRadians(0.1)), q2 = new org.joml.Quaternionf(q0).rotateX((float) Math.toRadians(1));
        verifier(Animateur.memeImage(v0, q0, new org.joml.Vector3f(v0).add(0.0006f, 0, 0), q1)
                && Animateur.memeImage(v0, q0, v0, new org.joml.Quaternionf(-q0.x, -q0.y, -q0.z, -q0.w))
                && !Animateur.memeImage(v0, q0, v0, q2) && !Animateur.memeImage(v0, q0, new org.joml.Vector3f(v0).add(0.0625f, 0, 0), q0),
            "économie d'images : un écart invisible n'est pas renvoyé, un vrai mouvement l'est");

        // tête du pantin : l'os « peau_tete » suit le regard (il n'était pas reconnu : la tête ne tournait jamais)
        boolean[] tetes = Animateur.tetes(j);
        int nTetes = 0, osTete = -1;
        for (int k = 0; k < tetes.length; k++) if (tetes[k]) { nTetes++; osTete = k; }
        verifier(nTetes == 1 && "peau_tete".equals(j.parties().get(osTete).nom()), "tête : un seul os suit le regard, « peau_tete »");
        Animateur.Pose[] droit = Animateur.posesCompletes(j, java.util.List.of(), 0f, 1f, 0f, 0f), tourne = Animateur.posesCompletes(j, java.util.List.of(), 0f, 1f, 40f, 30f);
        boolean seule = !droit[osTete].rotation().equals(tourne[osTete].rotation(), 1e-4f);
        for (int k = 0; k < droit.length; k++) if (k != osTete && droit[k] != null && !droit[k].rotation().equals(tourne[k].rotation(), 1e-4f)) seule = false;
        verifier(seule, "tête : le regard tourne la tête et rien d'autre");

        // mouvements du pantin (outils/mouvements-pantin.py) : tous là, et les mains là où le script les pose
        ModeleBB mv = lire("pantin_mouvements");
        boolean tous = true;
        for (String n : List.of("repos", "marche", "course", "recul", "pas_droite", "pas_gauche", "accroupi", "accroupi_marche", "saut", "chute",
                "planer", "planer_avance", "nage", "ramper", "echelle", "assis", "dormir", "vol", "vrille", "tenir_arme", "tenir_arme_gauche", "tenir_arme_deux", "tenir_deux_arme", "tenir_armes", "coup_arme", "coup_arme_gauche", "coup_poing", "coup_poing_gauche", "coup_hache", "coup_hache_gauche", "tenir", "tenir_gauche", "tenir_deux", "geste_main",
                "geste_main_gauche", "manger", "boire", "viser_arc", "viser_arc_gauche", "viser_arc_tenu", "viser_arc_tenu_gauche", "tir_arc", "charger_arbalete", "viser_arbalete",
                "garde_bouclier", "garde_bouclier_gauche", "viser_trident", "longue_vue", "cor", "brosse", "degat")) {
            tous &= mv.animations().containsKey(n);
        }
        // tronc rigide : dans aucun mouvement le tronc ne se tord ni ne roule (le haut sur le bas, le bassin sur les jambes) ;
        // le haut ne se penche pas de plus de 6° sur le bas. Sinon le pantin « se désassemble » à la taille et aux hanches.
        boolean rigide = true;
        for (ModeleBB.Animation an : mv.animations().values()) {
            for (Map.Entry<String, ModeleBB.Canaux> eo : an.os().entrySet()) {
                String os = eo.getKey().toLowerCase(java.util.Locale.ROOT);
                if ((!os.endsWith("corps_haut") && !os.endsWith("corps_bas")) || eo.getValue().rotation() == null) continue;
                for (ModeleBB.Cle cle : eo.getValue().rotation()) {
                    rigide &= Math.abs(cle.y()) < 0.01 && Math.abs(cle.z()) < 0.01 && (!os.endsWith("corps_haut") || Math.abs(cle.x()) < 6.01);
                }
            }
        }
        verifier(rigide, "mouvements du pantin : tronc rigide (ni torsion ni roulis, le haut penché de 6° au plus)");
        verifier(tous && mv.animations().get("viser_arc").boucle().equals("hold") && mv.animations().get("marche").boucle().equals("loop")
                && mv.animations().get("geste_main").boucle().equals("once"), "mouvements du pantin : les 17 mouvements et les gestes, arc tenu (hold)");
        int poingD = -1, poingG = -1;
        for (int k = 0; k < mv.parties().size(); k++) {
            if ("poing_droit".equals(mv.parties().get(k).nom())) poingD = k;
            if ("poing_gauche".equals(mv.parties().get(k).nom())) poingG = k;
        }
        // arc bandé : la main de l'arc en (4,4 ; 23,2 ; -9,6) pixels, celle de la corde sous la mâchoire en (-5,4 ; 22,8 ; -3,0) ;
        // un modèle au yaw 0 regarde vers +z : x et z changent de signe
        List<Animateur.Couche> bande = List.of(new Animateur.Couche(mv.animations().get("viser_arc"), 1.0, 1f, true));
        Animateur.Pose[] arc = Animateur.posesCompletes(mv, bande, 0f, 1f, 0f, 0f);
        verifier(arc[poingD].translation().distance(-4.4f / 16, 23.2f / 16, 9.6f / 16) < 0.01f
                && arc[poingG].translation().distance(5.4f / 16, 22.8f / 16, 3.0f / 16) < 0.01f,
            "arc bandé : les mains là où le script les pose (même calcul que Blockbench)");
        // l'arc bandé qui respire (viser_arc_tenu) commence sur la dernière pose de la traction : pas de saut entre les deux,
        // et sa boucle se referme
        ModeleBB.Animation tenu = mv.animations().get("viser_arc_tenu");
        Animateur.Pose[] t0 = Animateur.posesCompletes(mv, List.of(new Animateur.Couche(tenu, 0.0, 1f, true)), 0f, 1f, 0f, 0f);
        Animateur.Pose[] t1 = Animateur.posesCompletes(mv, List.of(new Animateur.Couche(tenu, tenu.duree(), 1f, true)), 0f, 1f, 0f, 0f);
        verifier(tenu.boucle().equals("loop") && t0[poingD].translation().distance(arc[poingD].translation()) < 0.012f
                && t0[poingG].translation().distance(arc[poingG].translation()) < 0.012f
                && t1[poingD].translation().distance(t0[poingD].translation()) < 1e-4f && t1[poingG].translation().distance(t0[poingG].translation()) < 1e-4f,
            "arc bandé tenu : il part de la fin de la traction et sa boucle se referme");
        // visée : regard 30° vers le bas, la main de l'arc descend ; 30° vers le haut, elle monte ; sans geste, rien ne bouge
        Animateur.Pose[] bas = Animateur.posesCompletes(mv, bande, 0f, 1f, 0f, 0f, 30f), haut = Animateur.posesCompletes(mv, bande, 0f, 1f, 0f, 0f, -30f);
        Animateur.Pose[] sans = Animateur.posesCompletes(mv, List.of(), 0f, 1f, 0f, 0f, 30f), nu = Animateur.posesCompletes(mv, List.of(), 0f, 1f, 0f, 0f);
        verifier(bas[poingD].translation().y < arc[poingD].translation().y - 0.2f && haut[poingD].translation().y > arc[poingD].translation().y + 0.2f
                && sans[poingD].translation().distance(nu[poingD].translation()) < 1e-5f,
            "visée : les bras du geste suivent le regard vers le haut et le bas");
        verifier(ArmuresPantin.couches(java.util.Map.of("CHEST", "minecraft:diamond", "HEAD", "minecraft:turtle_scute")).size() == 5
                && ArmuresPantin.couches(java.util.Map.of("CHEST", "babel:fer"), java.util.Set.of()).size() == 6
                && ArmuresPantin.couches(java.util.Map.of("CHEST", "babel:fer"), java.util.Set.of("babel:fer")).size() == 4
                && ArmuresPantin.couches(java.util.Map.of("CHEST", "minecraft:diamond")).stream().noneMatch(co -> co.os().endsWith("_bas") && co.os().contains("bras")),
            "armure : plastron du jeu = tronc + haut des bras (manches courtes, rien sur l'avant-bras), manches longues = 6 couches, carapace de tortue = tête seule");

        // skin pixel par pixel (PeauUV) : modèles du pack et couleurs d'un skin
        java.util.Map<String, byte[]> uv = PeauUV.generer(j);
        long objetsUV = uv.keySet().stream().filter(k -> k.startsWith("assets/vorace/items/pantin_peau/")).count();
        verifier(objetsUV == 15 && uv.containsKey("assets/vorace/textures/item/pantin_pixel.png"),
            "skin UV : 11 morceaux + 4 bras fins, texture d'un pixel blanc (" + objetsUV + ")");
        com.google.gson.JsonObject baseTete = com.google.gson.JsonParser.parseString(new String(uv.get("assets/vorace/models/pantin_peau/tete/base.json"),
            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        com.google.gson.JsonArray elTete = baseTete.getAsJsonArray("elements");
        com.google.gson.JsonObject premier = elTete.get(0).getAsJsonObject();
        verifier(elTete.size() == 384 && premier.get("from").toString().equals("[11.0,15.0,4.0]") && premier.get("to").toString().equals("[12.0,16.0,4.0]")
                && premier.getAsJsonObject("faces").getAsJsonObject("north").get("tintindex").getAsInt() == 0,
            "skin UV : tête = 384 carrés, premier pixel de l'avant en haut côté +x (droite du joueur)");
        com.google.gson.JsonObject defTete = com.google.gson.JsonParser.parseString(new String(uv.get("assets/vorace/items/pantin_peau/tete.json"),
            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("model");
        verifier(defTete.get("type").getAsString().equals("minecraft:composite") && defTete.getAsJsonArray("models").size() == 1 + 384
                && defTete.getAsJsonArray("models").get(1).getAsJsonObject().get("type").getAsString().equals("minecraft:condition"),
            "skin UV : objet = base + un carré par pixel du chapeau, affiché selon son drapeau");
        java.util.Map<String, PeauUV.Donnees> dd = PeauUV.donnees(j, skin, false);
        PeauUV.Donnees dt = dd.get("tete");
        verifier(dd.size() == 11 && dt.couleurs().length == 768 && dt.drapeaux().length == 384 && dt.couleurs()[0] == (skin.getRGB(8, 8) & 0xFFFFFF),
            "skin UV : couleurs de la tête (base puis chapeau), premier = pixel (8, 8) du skin");
        PeauUV.Donnees bf = PeauUV.donnees(j, skin, true).get("bras_droit_haut");
        verifier(bf != null && PeauUV.morceauObjet("bras_droit_haut", true).equals("bras_droit_haut_fin") && bf.couleurs().length < dd.get("bras_droit_haut").couleurs().length,
            "skin UV : bras fins (3 pixels) pour les skins « slim »");
        // genoux et coudes fermés : chaque moitié de membre a un bouchon du côté de la jointure (une ligne de la face avant)
        boolean bouchons = true;
        for (PeauUV.Morceau mo : PeauUV.morceaux(j, false)) {
            boolean membre = mo.nom().startsWith("bras") || mo.nom().startsWith("jambe");
            String cote = mo.nom().endsWith("_bas") ? "up" : "down";
            long n = mo.base().stream().filter(fa -> fa.nom().equals(cote) && fa.h() == 1).count();
            bouchons &= n == (membre ? 1 : 0) && mo.couche().size() == mo.base().size();
        }
        verifier(bouchons && dd.get("jambe_droite_bas").couleurs().length == 2 * (2 * 4 * 8 + 2 * 4 * 8 + 4 * 4 + 4),
            "skin UV : un bouchon par moitié de membre (genou, coude), aucun sur la tête ni le tronc");

        // armure fondue dans la peau : la définition de chaque morceau choisit jusqu'à deux couches d'armure par les chaînes
        // 0 et 1 de custom_model_data ; leurs teintes suivent celles des pixels ; le modèle choisi existe dans le pack
        java.util.Map<String, byte[]> uvA = PeauUV.generer(j, ArmuresPantin.VANILLE);
        boolean fonduOk = uvA.size() == uv.size();
        for (String morceau : List.of("tete", "corps_haut", "corps_bas", "bras_droit_haut", "bras_droit_haut_fin", "jambe_gauche_bas")) {
            com.google.gson.JsonArray mods = com.google.gson.JsonParser.parseString(new String(uvA.get("assets/vorace/items/pantin_peau/" + morceau + ".json"),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("model").getAsJsonArray("models");
            String os = morceau.replace("_fin", "");
            int pixels = PeauUV.donnees(j, skin, morceau.endsWith("_fin")).get(os).couleurs().length;
            int rang = 0;
            for (com.google.gson.JsonElement me : mods) {
                com.google.gson.JsonObject mo = me.getAsJsonObject();
                if (!mo.get("type").getAsString().equals("minecraft:select")) continue;
                fonduOk &= mo.get("property").getAsString().equals("minecraft:custom_model_data") && mo.get("index").getAsInt() == rang
                    && mo.getAsJsonObject("fallback").get("type").getAsString().equals("minecraft:empty");
                boolean fer2 = false, jamb = false;
                for (com.google.gson.JsonElement ce : mo.getAsJsonArray("cases")) {
                    com.google.gson.JsonObject cas = ce.getAsJsonObject(), mdl = cas.getAsJsonObject("model");
                    String quand = cas.get("when").getAsString(), chemin = mdl.get("model").getAsString();
                    fonduOk &= chemin.equals("vorace:pantin_armure/" + quand + "/" + os) && arm.containsKey("assets/vorace/models/" + chemin.substring(7) + ".json")
                        && quand.equals(ArmuresPantin.cleDepuisId(chemin.substring(7)))
                        && mdl.getAsJsonArray("tints").get(0).getAsJsonObject().get("index").getAsInt() == pixels + 2 * rang
                        && mdl.getAsJsonArray("tints").size() == (quand.startsWith("minecraft/leather/") ? 2 : 1);
                    fer2 |= quand.equals("minecraft/iron/humanoid");
                    jamb |= quand.endsWith("/humanoid_leggings");
                }
                fonduOk &= fer2 && jamb == (os.startsWith("corps") || os.startsWith("jambe"));
                rang++;
            }
            fonduOk &= rang == PeauUV.RANGS_ARMURE;
        }
        verifier(fonduOk && ArmuresPantin.cle("minecraft:iron", "humanoid").equals("minecraft/iron/humanoid")
                && ArmuresPantin.cleDepuisId("pantin_peau/tete") == null,
            "armure fondue : deux choix d'armure par morceau, teintes après les pixels, modèles présents dans le pack");
        if (echecs > 0) System.exit(1);
    }
}
