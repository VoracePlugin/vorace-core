# Journal des changements

## 1.4.0 (novembre 2026) : pantin des joueurs et réactions des monstres

### Pantin des joueurs (animations du corps entier avec le vrai skin, sans mod)
- `VoraceAPI.animerJoueur(joueur, modèle, animation)` : pendant l'animation, le joueur est caché (corps invisible,
  équipement vide envoyé aux autres) et remplacé par un modèle animé dont chaque os « peau_... » porte une tête de
  joueur étirée sur son cube. Le joueur ne voit pas sa propre tête (première personne). Le pantin disparaît à la fin.
- Squelette `modeles-animes/joueur.bbmodel` (tête + tronc, bras et jambes en deux morceaux), remis s'il manque ; un
  modèle de classe reprend ce squelette et ajoute ses animations. Animations d'essai : `essai`, `salut`, `croix`.
- Skin découpé en dix morceaux (`Peau`), signés par MineSkin (clé `pantin.mineskin-cle`, un envoi à la fois) et gardés
  dans `peaux/cache.yml` ; préparé à la connexion. Sans clé ou pendant la préparation : habits par défaut du squelette.
- Envois à MineSkin au rythme qu'il demande (délai lu dans chaque réponse, 3,5 s au moins) ; une erreur 5xx ou 429 est
  réessayée cinq fois en attendant de plus en plus ; un morceau refusé n'arrête plus les autres et est renvoyé à la fin.
  Message d'erreur lisible (code et texte de MineSkin) dans `/vorace pantin etat` et la console.
- Le pantin est monté sur le joueur (ses morceaux sont des passagers) : il le suit sans aucun retard, même à la
  première personne, et regarde là où le joueur regarde. Pendant une animation, Paper refuse de téléporter un joueur
  qui porte des passagers : appeler d'abord `VoraceAPI.arreterJoueur`. Le pantin s'arrête à la mort et au départ.
  `pantin.monter-sur-le-joueur: false` rend l'ancien suivi.
- Pantin mis à jour à chaque tic. Le joueur ne voit pas la tête de son propre pantin (`pantin.cacher-sa-tete`) : sa
  caméra est dedans et le jeu en dessinerait l'intérieur ; les autres la voient.
- Coudes, genoux et taille fermés : la moitié basse de chaque membre et du tronc reprend deux lignes du skin au-dessus
  de la jointure et remonte de deux pixels dans le morceau du dessus (un peu plus fine, sans scintillement).
- `/vorace pantin preparer|etat [joueur]`, `jouer <joueur> <animation> [modèle]`, `essai [animation]`.
- Réglages de secours `pantin.correction.rotation-y` et `decalage-y` si les têtes sont mal tournées en jeu.
- Coups enchaînés : le même pantin joue l'animation suivante sans disparaître, et garde sa dernière pose 0,25 s.
- `VoraceAPI.animerJoueurEnBoucle` et `arreterJoueur` (animation tenue, comme la Reprise de souffle).
- Repères `poing_droit`, `poing_gauche`, `pied_droit`, `pied_gauche` dans le squelette (sans cube).

### Clés d'effet plus riches (toutes les animations)
- Particule : `nom[#RRGGBB[:taille]][*nombre][@os][~écart][^vitesse]`, par exemple `flame*30@poing_droit~0.3^0.05` ou
  `dust#FF6A00:1.6*40~1.2` (particules colorées) ; placée sur l'os nommé, sinon au milieu du modèle.
- Son : `nom[*volume][^hauteur]`, par exemple `entity.generic.explode*1.5^0.7`. L'ancienne écriture (nom seul) ne change pas.

### Apparitions (phénix, météorite, titan des ultimes)
- `VoraceAPI.apparition(modèle, endroit, yaw, animation, taille)` : un modèle animé apparaît le temps d'une
  animation puis disparaît (rien n'est gardé avec le monde ; base invisible côté joueurs).
- Depuis une animation : clé de script (piste « Effets », timeline) `apparition <modèle> <animation> [taille]` ; le
  modèle apparaît à l'endroit du modèle qui joue l'animation, tourné comme lui (le pantin d'un joueur : à ses pieds,
  dans la direction où il regarde). On dessine donc l'apparition dans le même repère que l'animation qui l'appelle.
- Os dont le nom commence par `lum_` : toujours éclairés à fond (feu, braise, yeux qui brillent la nuit).
- `/vorace modele apparition <modèle> <animation> [taille]` (devant soi), avec l'auto-complétion.

### Réactions des monstres
- `VoraceAPI.reaction(entité, réaction)` : le modèle du monstre joue `saisi`, `plaque`, `souleve`, `projete`,
  `repousse`, `etourdi` ou `resiste` s'il a cette animation (noms anglais acceptés : grabbed, slam, launched...).
- Mannequin d'essai `mannequin_reactions.bbmodel` avec les sept réactions ; `/vorace modele reaction <réaction>`.

### Divers
- config.yml : les sections de premier niveau ajoutées par une nouvelle version sont écrites à la fin du fichier du
  serveur, avec leurs commentaires.

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
