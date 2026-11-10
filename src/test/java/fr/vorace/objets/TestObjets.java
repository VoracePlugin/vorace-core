package fr.vorace.objets;

import fr.vorace.pack.FormatPack;
import fr.vorace.pack.Journal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Teste la génération du pack des objets pour les trois familles de versions. */
public final class TestObjets {
    static int echecs = 0;
    static void verifier(boolean ok, String quoi) { System.out.println((ok ? "OK    " : "ECHEC ") + quoi); if (!ok) echecs++; }
    static String s(Map<String, byte[]> m, String k) { byte[] b = m.get(k); return b == null ? null : new String(b, StandardCharsets.UTF_8); }

    static List<String> fusion(List<String> avant, List<String> empreintesVorace, List<String> nouvelle) {
        return FusionDescription.fusionner(avant, avant.stream().map(FusionDescription::empreinte).toList(), empreintesVorace, nouvelle);
    }

    public static void main(String[] a) throws Exception {
        Path r = Files.createTempDirectory("vorace-res");
        Files.createDirectories(r.resolve("textures/armes"));
        Files.createDirectories(r.resolve("modeles"));
        Files.write(r.resolve("textures/armes/rubis.png"), new byte[]{1, 2, 3});
        Files.write(r.resolve("textures/couronne_or.png"), new byte[]{4});
        Files.writeString(r.resolve("textures/couronne_or.png.mcmeta"), "{\"animation\":{}}");
        // export Blockbench typique : textures sans espace de noms, et une texture du jeu de base
        Files.writeString(r.resolve("modeles/couronne.json"), "{\"credit\":\"Made with Blockbench\",\"textures\":{\"0\":\"couronne_or\",\"1\":\"item/couronne_or\",\"particle\":\"minecraft:block/gold_block\",\"2\":\"#0\"},\"elements\":[]}");

        List<Visuel> v = List.of(
            new Visuel("rubis", "paper", "armes/rubis", null, false, 1001, null),
            new Visuel("epee", "diamond_sword", "armes/rubis", null, true, 1002, null),
            new Visuel("couronne", "paper", null, "couronne", false, 1003, null),
            new Visuel("bottes", "leather_boots", "armes/rubis", null, false, 1004, 0xFF3366FF),
            new Visuel("absent", "paper", "nexiste/pas", null, false, 1005, null));

        Journal j = new Journal();
        Map<String, byte[]> m = GenerateurPack.generer(v, r, new FormatPack(46, 0, "t"), j);
        verifier(s(m, "assets/vorace/items/rubis.json").equals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"vorace:item/rubis\"}}"), "1.21.4+ : définition items/rubis.json");
        verifier(s(m, "assets/vorace/models/item/rubis.json").contains("\"minecraft:item/generated\"") && s(m, "assets/vorace/models/item/rubis.json").contains("vorace:item/armes/rubis"), "modèle plat généré");
        verifier(s(m, "assets/vorace/models/item/epee.json").contains("item/handheld"), "épée : modèle tenu en main");
        verifier(m.containsKey("assets/vorace/textures/item/armes/rubis.png"), "texture copiée");
        String cour = s(m, "assets/vorace/models/item/couronne.json");
        verifier(cour.contains("\"0\":\"vorace:item/couronne_or\"") && cour.contains("\"1\":\"vorace:item/couronne_or\"") && cour.contains("minecraft:block/gold_block") && cour.contains("\"#0\""), "Blockbench : textures reliées, texture du jeu gardée " + cour);
        verifier(m.containsKey("assets/vorace/textures/item/couronne_or.png.mcmeta"), "animation de texture copiée");
        verifier(s(m, "assets/vorace/items/bottes.json").contains("\"minecraft:dye\""), "cuir : teinte dans la définition");
        verifier(!m.containsKey("assets/vorace/items/absent.json") && j.compter(Journal.Niveau.ERREUR) == 1, "texture absente : erreur claire, objet ignoré");
        verifier(m.keySet().stream().noneMatch(k -> k.startsWith("assets/minecraft/")), "1.21.4+ : aucun fichier du jeu de base modifié");

        Map<String, byte[]> m42 = GenerateurPack.generer(v, r, new FormatPack(42, 0, "t"), new Journal());
        verifier(m42.containsKey("assets/vorace/models/item/rubis.json") && !m42.containsKey("assets/vorace/items/rubis.json"), "1.21.2/3 : modèle direct, pas de définition items/");

        Map<String, byte[]> m34 = GenerateurPack.generer(v, r, new FormatPack(34, 0, "t"), new Journal());
        String paper = s(m34, "assets/minecraft/models/item/paper.json");
        verifier(paper != null && paper.indexOf("1001") < paper.indexOf("1003") && paper.contains("minecraft:item/paper") && paper.contains("item/generated"), "1.21/1.21.1 : overrides custom_model_data triés " + paper);
        verifier(s(m34, "assets/minecraft/models/item/diamond_sword.json").contains("item/handheld"), "1.21/1.21.1 : épée de base tenue en main");
        // mise à jour des objets : les lignes de Vorace changent, celles des autres plugins restent à leur place
        List<String> vorace = List.of("Forgée avec des rubis.", "", "Clic droit : bond");
        List<String> ev = vorace.stream().map(FusionDescription::empreinte).toList();
        List<String> neuves = List.of("Nouvelle description.");
        verifier(List.of("Nouvelle description.", "Qualité : rare").equals(fusion(List.of("Forgée avec des rubis.", "", "Clic droit : bond", "Qualité : rare"), ev, neuves)), "description : ligne d'un autre plugin gardée après");
        verifier(List.of("Forgeron : Antoine", "Nouvelle description.", "Qualité : rare").equals(fusion(List.of("Forgeron : Antoine", "Forgée avec des rubis.", "", "Clic droit : bond", "Qualité : rare"), ev, neuves)), "description : lignes gardées avant et après");
        verifier(List.of("Nouvelle description.").equals(fusion(vorace, ev, neuves)), "description : seulement celle de Vorace");
        verifier(fusion(List.of("Forgée avec des rubis.", "Clic droit : bond", "Qualité : rare"), ev, neuves) == null, "description : lignes de Vorace changées par un autre plugin -> gardée telle quelle");
        verifier(List.of("Nouvelle description.", "Qualité : rare").equals(fusion(List.of("Qualité : rare"), List.of(), neuves)), "description : objet sans description Vorace");
        verifier(List.of().equals(FusionDescription.lire("")) && FusionDescription.lire(FusionDescription.joindre(ev)).equals(ev), "empreintes rangées puis relues");
        System.out.println(echecs == 0 ? "TOUS LES TESTS PASSENT" : echecs + " ECHEC(S)");
        System.exit(echecs == 0 ? 0 : 1);
    }
}
