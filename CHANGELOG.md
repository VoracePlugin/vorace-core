# Journal des changements

## 1.3.1 (novembre 2026) : corrections

- **Joueurs déconnectés par « Recursive update »** : la couche paquets nommait les paquets rangés dans un autre
  (`ServerboundMovePlayerPacket.Pos`, envoyé dès qu'un joueur bouge) avec un `computeIfAbsent` qui se rappelait
  lui-même sur la même table. Selon le démarrage du serveur, les deux noms tombaient dans la même case de la table :
  Java levait `IllegalStateException: Recursive update` au milieu du paquet et le joueur était déconnecté (vu une fois
  dans l'essai serveur). Le nom est maintenant calculé hors de la table. Test : plus de 200 classes imbriquées du JDK.
- **Pack introuvable juste après une reconstruction** : le serveur web ne servait que le dernier pack. Un joueur qui
  avait reçu l'adresse du pack juste avant une reconstruction (`/vorace reload`, vorace-item au démarrage) recevait un
  404, et était expulsé si le pack est obligatoire. Les deux packs précédents restent servis par leur empreinte.

## 1.3.0 (novembre 2026) : Paper 1.21.4 au minimum

Vorace demande maintenant un serveur **Paper 1.21.4 ou plus récent** (`api-version: '1.21.4'`) : il ne se charge
plus sur 1.21 à 1.21.3. La dernière version pour ces serveurs est la 1.2.1.

- Le plugin est compilé contre l'API Paper 1.21.4 (au lieu de 1.21.1), y compris la dépendance JitPack.
- La compilation automatique vérifie 1.21.4, 1.21.11 et 26.2.
- Rien ne change dans les commandes, la config ni l'API.

## 1.2.1 (novembre 2026)

### Mise à jour des objets : chaque exemplaire garde ce qui lui appartient

Avant, quand la définition d'un objet changeait, l'objet refait à la connexion ne gardait que la quantité, l'usure et
les enchantements : le nom donné à l'enclume et tout ce qu'un autre plugin avait mis dessus disparaissaient.
Maintenant sont aussi gardés :

- le nom donné à l'enclume (ou par un plugin) ;
- les données des autres plugins (PersistentDataContainer : stats, qualité, forgeron...) ;
- les lignes de description ajoutées par d'autres plugins, à leur place, avant ou après celles de Vorace (qui, elles,
  sont remplacées par la définition actuelle) ;
- les modificateurs d'attributs des autres plugins, la garniture d'armure et le coût de réparation.

Vorace note pour cela sur chaque objet l'empreinte du nom et des lignes qu'il a écrits (`vorace:nom`,
`vorace:description`). Les objets fabriqués avant la 1.2.1 n'ont pas ces repères : leur première mise à jour se fait
comme avant. Les objets de Nexo et d'Oraxen convertis sont refaits comme avant.

Nouvel événement `VoraceObjetMisAJourEvent` : un plugin y remet ce que Vorace ne garde pas tout seul.
Essai sur un vrai serveur : `/vorace maj essai` (objet renommé, enregistré puis relu, définition changée).

### Maillages Blockbench (meshes)

Les maillages des .bbmodel ne sont plus ignorés : chaque face plate et rectangulaire devient un élément plat
(épaisseur nulle, texturé des deux côtés), tourné comme la face. Une face droite ou tournée sur un seul axe par pas
de 22,5° reste dans son os ; sinon elle devient une partie à elle seule. Les triangles et les faces tordues ne
peuvent pas entrer dans le format de Minecraft : le nombre de faces non affichées est signalé au chargement.

## 1.2.0 (novembre 2026) : vorace-core

Le dépôt devient **vorace-core** dans l'organisation [VoracePlugin](https://github.com/VoracePlugin). Le plugin
garde son nom (`Vorace`), ses commandes et ses dossiers.

- Les mobs (cerveau, troupeaux, apparitions, volants, décors vivants) sortent dans le plugin
  [vorace-mob](https://github.com/VoracePlugin/vorace-mob), qui dépend de vorace-core. Retirés d'ici : le paquet
  `fr.vorace.mobs`, `/vorace mob` (remplacé par `/vmob`), la section `mobs` de `config.yml`, les méthodes de mobs de
  `VoraceAPI` (maintenant `VoraceMobAPI`) et `VoraceMobActionEvent` (maintenant dans `fr.vorace.mobs`).
- Vorace-mob reprend tout seul les fichiers de `plugins/Vorace/mobs`, `zones` et `apparitions`.
- Dépendance JitPack : `com.github.VoracePlugin:vorace-core:<version>`.
- L'essai sur la map d'Orario part dans vorace-mob ; l'essai serveur d'ici ne vérifie plus que l'affichage.

## 1.1.0 (novembre 2026, branche `ameliorations-modeles` du fork d'Antoine)

Par rapport à la 1.0.0 (`main`) : modèles animés plus fluides et moins coûteux, et un système de mobs complet
(le rôle de MythicMobs). Rien n'est retiré, aucune commande ni méthode d'API existante ne change. Le détail de
chaque réglage est dans le README (sections « Modèles animés » et « Mobs »).

Ces changements ont été faits pour le serveur Babel d'Antoine (ville d'Orario : 2 185 animaux sur 1 083 points
d'apparition) et testés sur un vrai serveur Paper 1.21.4 avec un faux joueur (voir « Tests » plus bas).

### À savoir avant de mettre à jour

- **Les modèles animés ne s'affichent plus au-delà de 48 blocs** d'un joueur (`modeles.distance-affichage`).
  Mettre `0` pour les afficher toujours, comme en 1.0.
- Le passage d'une animation à l'autre se fait en fondu de 0,2 s (`modeles.fondu-secondes: 0` pour une coupure nette).
- Le pack généré donne aux parties des modèles animés une teinte réglée par le serveur (pour le rouge des dégâts) :
  regénérer le pack après la mise à jour.
- Nouvelles sections `modeles` et `mobs` dans `config.yml`, avec des valeurs par défaut : une ancienne
  configuration marche sans rien changer. Les mobs se coupent avec `mobs.actif: false`.
- Nouveaux dossiers `plugins/Vorace/mobs`, `zones` et `apparitions` (exemples fournis).

### Modèles animés

- Portée d'affichage : les entités d'un modèle n'existent que près des joueurs ; au-delà de la moitié de la
  portée, les images sont envoyées deux fois moins souvent.
- Fondus entre animations.
- Couches : `degat`, `attaque` et `clic` se jouent par-dessus l'animation en cours (un mob attaque en marchant).
- Tête qui suit le regard du mob (os `h_...`, `head` ou `tete`).
- Textures animées de Blockbench (le `.mcmeta` est écrit tout seul).
- Teinte rouge quand le mob est touché (1.21.4 et plus).
- Clés d'effet de Blockbench : sons, particules, scripts transmis par `VoraceModeleEffetEvent`.
- Os spéciaux : `tag_...` (nom du mob), position de n'importe quel os par l'API.
- Taille par modèle, gardée avec le monde.
- Molang dans les clés d'animation (opérations, conditions, `math.*`, `query.anim_time`).

### Mobs (nouveau)

- Cerveau : besoins (faim, soif, fatigue), émotions (peur, colère) ; chaque action reçoit une note et la meilleure
  l'emporte : repos, errer, manger, boire, dormir, rejoindre le troupeau, rentrer, fuir, attaquer.
- Caractères paisible, craintif, territorial, agressif ; horaires diurne, nocturne, toujours.
- Troupeaux, maison et laisse, zones (rectangle, cercle, polygone) avec mangeoire, abreuvoir et abri.
- Points d'apparition : population gardée autour d'un endroit, morts remplacés après un délai, rien n'apparaît sans
  joueur à moins de 96 blocs.
- Volants (oiseaux, papillons) : vrais mobs qu'on peut taper, posés au sol ou en hauteur, envol quand on approche,
  vol de point en point, atterrissage dans la laisse ; les oiseaux qui marchent (pigeons) trottinent et picorent ;
  volée : toute la bande part ensemble ; ils ne se posent pas à côté d'un joueur.
- Décors vivants (`type: decor`) : modèle sans entité (bain d'oiseaux, nid) qui joue `envol` quand on approche et
  `retour` 15 s après le départ du dernier joueur.
- Mob coincé : repart quelques blocs plus loin, ou reposé sur un sol libre s'il n'a de chemin nulle part.
- Un mob monté ou tenu en laisse obéit au joueur.
- Les joueurs en créatif font vivre et effraient les mobs comme les autres, sans jamais être attaqués.
- IA au choix : `vorace` (le cerveau), `jeu` (IA du jeu gardée en laisse : poissons, abeilles) ou `aucune`.
- Performances : un mob à plus de 64 blocs de tout joueur ne réfléchit pas ; les autres deux fois par seconde,
  chacun à son tour.

Corrections trouvées sur la vraie map pendant les essais :
- le sol d'un oiseau se cherche sous lui en sautant les barrières (un plafond de barrières invisibles était pris
  pour le sol : les oiseaux ne marchaient ni ne s'envolaient) ;
- les joueurs en créatif étaient ignorés, donc aucun mob ne « réfléchissait » autour d'eux ;
- un mob qui broutait loin de son troupeau faisait des allers-retours ; il broute maintenant près de lui ;
- une fuite sans chemin prend une autre direction, sinon le mob se calme.

### Commandes et API

- `/vorace mob [liste|invoquer <mob> [nombre]|info [début|*] [joueur]|apparitions|retirer|recharger]` ; `info` donne
  l'action, les besoins, la position, la distance parcourue et, pour un décor, l'animation, les sons et les
  particules joués ; depuis la console, le nom d'un joueur relève les mobs autour de lui.
- `/vorace modele taille <facteur>` et `/vorace modele anim <animation> [boucle] dessus`.
- API : `invoquerMob`, `mobVorace`, `actionMob`, `effrayerMob`, `jouerAnimationParDessus`, `tailleModele`,
  `regardModele`, `positionOs`.
- Événements : `VoraceMobActionEvent` (changement d'action d'un mob), `VoraceModeleEffetEvent` (clé d'effet, annulable).

### Tests

- `gradle tests` : nouveaux tests sans serveur `TestMobs` et `TestModelesAvances`.
- `outils/essai-serveur.sh` (workflow « Compilation ») : vrai serveur Paper avec un faux joueur (mineflayer) et un
  « zoo » de mobs.
- `outils/essai-orario.sh` (workflow « Essai Orario ») : propre au serveur d'Antoine. Il lance un serveur sur un
  extrait de sa map rangé dans la branche `essai-orario-donnees` et suit chaque animal. Sans cette branche, le
  workflow ne fait rien.

### Fichiers principaux

- `src/main/java/fr/vorace/mobs/` (nouveau) : `Mobs` (gestion et pilotage), `Cerveau` (choix des actions), `Vol`
  (volants), `Apparition`, `DefinitionMob`, `Zone`, `Geometrie`, `EcouteurMobs`.
- `src/main/java/fr/vorace/modeles/` : `Modeles`, `Animateur`, `Molang` et `Teinte` (nouveaux), `LecteurBBModel`,
  `GenerateurModeles`.
- `src/main/java/fr/vorace/api/` : nouvelles méthodes et les deux événements.

Les messages de commit décrivent chaque changement ; ceux marqués `[orario]` lançaient l'essai sur la map d'Antoine.
