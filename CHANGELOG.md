# Journal des changements

## 1.7.7 (novembre 2026) : armure fondue dans la peau, deux fois moins de paquets en armure

Chantier réseau validé par Antoine (« j'ai peur qu'avec beaucoup de gens, ça lag »).

- **Chaque couche d'armure est fondue dans le morceau de peau qui la porte** (`pantin.armures-fondues`) : un seul affichage
  au lieu de deux. Un pantin en armure complète passe de 26 morceaux (11 de corps, 15 d'armure) à 11, donc de 26 paquets
  par image et par spectateur à 11 ; sa copie pour lui-même aussi. L'objet du morceau porte la clé de l'armure
  (custom_model_data, chaînes 0 et 1 : plastron et jambières sur le tronc, jambières et bottes sur les jambes) et sa
  couleur (cuir teint) ; la définition du morceau dans le pack dessine l'armure par-dessus la peau, avec les mêmes modèles
  qu'avant, au même endroit.
- Mettre ou retirer une pièce d'armure ne refait plus le pantin : seul l'objet des morceaux concernés change.
- Gardent leur propre affichage : une pièce enchantée (son reflet couvrirait la peau), les armures en 3D et les objets
  portés sur la tête, et tout pantin dont la peau n'est pas celle du pack.
- **Le pack de ressources change** (définitions des morceaux de peau) : il est régénéré au démarrage. Avec l'ancien pack,
  l'armure du pantin ne s'afficherait pas.
- `pantin.armures-fondues: false` remet un affichage par couche, comme avant.

## 1.7.6 (novembre 2026) : tronc d'un bloc, marche qui ne repart plus de zéro, course à grande vitesse

Retours d'Antoine en jeu : « quand je marche, j'ai des parties de mon pantin qui se désassemblent : les jambes, la moitié
de mon tronc » ; « quand je vais un peu vite ou que je fais tout droit, à gauche, à droite, ça recommence l'animation de
zéro : je m'arrête, je reprends, je m'arrête, je reprends » ; « quand je vais trop vite, ma course est un peu chelou ».

- **Tronc d'un bloc** dans tous les mouvements et tous les gestes : il ne se tord plus et ne roule plus, il se penche
  seulement, autour des hanches, comme le corps du jeu. Le tronc est en deux morceaux et les jambes tiennent à la racine,
  pas au bassin : tourner le haut sur le bas (16° à la marche, 32° pour viser à l'arc) ou le bassin sur les jambes ouvrait
  des cassures à la taille et aux hanches. Le contrôle (`outils/verifier-mouvements.py`) et les tests le refusent
  maintenant. Le bras d'arc se tend devant l'épaule au lieu de compter sur la torsion.
- **La marche ne s'interrompt plus quand on change de direction** : tout droit, de biais ou de côté, c'est le même
  mouvement (le corps se tourne vers là où il va) ; seule la marche à reculons en est un autre, comme dans le jeu (à partir
  de 100° du regard, jusqu'à 85° pour en sortir). Avant, le sens était lu par rapport au corps en train de tourner : la
  marche, le pas de côté et le recul se relayaient à chaque changement de direction, chacun repartant de zéro.
- **Vitesse lissée** : le serveur reçoit les déplacements par à-coups (aucun pendant un tic, deux au suivant) ; la course
  sautait d'une allure à l'autre, repassait par le repos, et sa cadence tremblait. De la marche à la course à 0,24 bloc par
  tic, retour à la marche à 0,2 seulement.
- **À grande vitesse** (potion, élan d'une compétence) : la cadence de la course ne suit plus qu'à 40 % au-delà de son
  allure et plafonne à 1,3 fois, au lieu de 1,8 (les jambes moulinaient). En courant par bonds, un seul tic au sol entre
  deux sauts ne ramène plus la course.
- Le `pantin_mouvements.bbmodel` de la 1.7.5 est remplacé tout seul s'il n'a pas été retouché.

## 1.7.5 (novembre 2026) : un coup ne bouge que le bras qui frappe, moins de paquets

Retours d'Antoine en jeu : « pourquoi quand je tape, l'autre main se baisse en même temps ? Normalement il n'y a qu'une
main » ; « quand je tape, il y a la moitié de mon corps qui bouge, la moitié de l'armure qui ne devrait pas bouger » ;
« vérifie au niveau des paquets si on a fait le maximum pour pas que ça lag avec beaucoup de gens ».

- **Un coup ne bouge que le bras qui frappe** (`coup_poing`, `coup_arme`, `coup_hache`, `geste_main`) : plus de contrepoids
  de l'autre bras, plus de rotation du buste (la moitié haute du corps et de l'armure tournait sur la moitié basse, la cassure
  se voyait à la taille). Les tailles restent en diagonale, d'en haut à droite vers le bas à gauche.
- **Images presque identiques non renvoyées** (`modeles.economie-images`) : un morceau dont l'image a bougé de moins d'un
  quarantième de pixel et de 0,3° depuis la dernière envoyée n'est pas renvoyé. Avant, un pantin au repos envoyait un paquet
  par morceau à chaque tic (il respire : chaque image différait d'un cheveu). Vaut aussi pour les mobs animés.
- **Foule** (`pantin.permanent.foule`, 8) : quand plus de 8 autres joueurs sont à moins de 32 blocs d'un pantin, ses images
  partent tous les 2 tics au lieu de chaque tic (3 tics au-delà de 24 joueurs) ; le jeu adoucit entre deux. Sa propre copie
  reste à chaque tic.
- Le `pantin_mouvements.bbmodel` de la 1.7.4 est remplacé tout seul s'il n'a pas été retouché.

## 1.7.4 (novembre 2026) : tailles en diagonale, corps tourné vers là où il va, course et saut de combattant

Retours d'Antoine en jeu : « quand je cours en F1, je vois trop les bras » ; « le saut, c'est pas un saut normal : toute
l'animation doit être réaliste, mais stylée, un mec qui sait se battre » ; « pour les coups, regarde les compétences et fais
un coup en diagonale, d'en haut vers le bas, de droite à gauche » ; « quand tu avances et que tu vas vers la droite en même
temps sans bouger ton regard, ça fait un truc bizarre : le corps doit se décaler vers là où tu vas, la tête reste à regarder
au même endroit ».

- **Le corps se tourne vers là où il va**, comme le jeu (mesuré dans LivingEntity.tick de Minecraft 1.21.4) : en marche, il
  rejoint la direction du déplacement (30 % de l'écart par tic), sans s'écarter de plus de 50° du regard ; à plus de 95° du
  regard, il marche à reculons. En avançant de biais sans tourner la tête, le corps part de biais et marche droit devant
  lui, la tête regarde toujours devant. Pendant un coup ou une visée, il revient face au regard. Le mouvement joué (marche,
  recul, pas de côté) est choisi par rapport au corps. `pantin.corps-vers-le-mouvement: false` pour l'ancien réglage.
- **Tailles en diagonale** (`coup_arme`, `coup_hache`), sur le modèle de celles des compétences (Lame du vent, Taille
  croisée) : d'en haut à droite vers le bas à gauche. L'arme monte au-dessus de l'épaule, à l'extérieur, le buste se tourne
  (24°) ; il revient (jusqu'à -36°) et plonge, la lame traverse devant lui et finit basse devant l'autre hanche. Le coude
  plie dans le plan de la taille : la lame tranche sur son fil.
- **Course** : les bras restent bas et tirés vers l'arrière, un peu écartés (ils balancent de 23° devant à 37° derrière), le
  buste en avant. À la première personne, les mains restent à 60° sous le regard, hors du champ.
- **Saut** : un bond d'athlète (un genou haut, l'autre jambe repliée dessous, le buste en avant, les coudes tirés en
  arrière) au lieu des bras ouverts. **Chute** : maîtrisée, genoux pliés prêts à amortir, bras à peine ouverts au lieu
  d'être levés en croix.
- Le recul de sa copie à la première personne suit l'axe du regard (le corps peut maintenant être tourné de 50°).
- Le `pantin_mouvements.bbmodel` de la 1.7.3 est remplacé tout seul s'il n'a pas été retouché.

## 1.7.3 (novembre 2026) : arme portée sur le côté, un vrai coup par arme, tête et épaules à la première personne

Retours d'Antoine en jeu : « quand on marche, il faudrait que l'épée soit pas aussi devant nous, elle devrait être sur le
côté ; c'est bon pour un arc parce qu'il est court » ; « pour chaque coup, faut une vraie animation : un vrai coup de poing,
un vrai tapage à l'épée, à la hache, tous les coups possibles » ; « quand je regarde par terre en marchant, je vois ma
tête » ; « c'est un peu bizarre de voir ses épaules aussi grosses sur soi ».

- **Arme longue portée sur le côté** (`tenir_arme` : épée, hache, pioche, pelle, houe, lance, trident, masse) : le bras
  pend, la lame pointe vers le sol devant le pied, un peu ouverte vers l'extérieur. Elle ne barre plus la vue. Les autres
  objets (arc, bloc, nourriture) restent tenus devant, comme dans le jeu. Variantes pour les deux mains : `tenir_arme_gauche`,
  `tenir_arme_deux` (arme et objet), `tenir_deux_arme`, `tenir_armes`.
- **Un coup par sorte d'objet**, pour la main droite et la gauche :
  - main vide : `coup_poing`, un direct (le poing se ramène aux côtes, part droit devant, le buste tourne, l'autre main
    monte en garde) ;
  - épée, trident, lance : `coup_arme`, une taille de haut en bas avec tout le haut du corps (élan au-dessus de l'épaule, le
    buste revient et plonge, le poignet se casse, l'autre bras fait contrepoids), qui part du côté et y revient ;
  - hache, masse : `coup_hache`, plus lourd (élan plus long, la hache tombe de plus haut et finit basse) ;
  - pioche, pelle, houe, bloc, objet : `geste_main`, le coup court.
- **Un coup va au bout avant d'être relancé** : en minant, le jeu relance le bras tous les 3 ou 4 tics ; le geste repartait
  à chaque fois de son élan et ne frappait jamais. Il n'est relancé qu'après avoir porté (60 % de sa durée).
- **Sa tête n'est plus dessinée à la première personne** (pantin permanent) : la tête de sa copie porte la marque que le
  shader du pack efface près de la caméra, comme ses bras en vue « main-du-jeu ». En F5, elle est là. Elle se penche avec le
  regard et passait devant la caméra quand il regardait par terre.
- **Recul quand il regarde par terre** (`pantin.recul-regard-bas`, 5 pixels) : les yeux du jeu sont 3 pixels au-dessus du
  torse ; en baissant les yeux, le torse et les épaules remplissaient l'écran. Le corps recule à mesure que le regard
  descend (de 20° à 80°) : il voit son ventre en bas de l'écran et ses pieds.
- **Geste de fond** (la main qui tient un objet) : joué à 85 %, le bras qui porte l'arme balance un peu avec la marche au
  lieu d'être figé ; on passe d'un objet à l'autre en fondu (le bras ne retombe plus entre les deux) ; il s'efface à la
  nage, en rampant, en vol, sur une échelle et au lit, où les deux bras font le mouvement.
- Le `pantin_mouvements.bbmodel` de la 1.7.2 est remplacé tout seul s'il n'a pas été retouché (60 animations).

## 1.7.2 (novembre 2026) : course, vol en créatif, coup d'épée droit, manches courtes, épée en double

Retours d'Antoine en jeu : « comment tu cours, c'est pas beau, surtout les deux mains qui font comme ça ; c'est encore plus
dégueulasse en vol, quand tu voles en créatif, faudrait une animation spéciale » ; « quand je tape, j'ai l'impression que je
tape pas droit » ; « l'armure en diamant qui va sur la deuxième partie du bras, ça fait un bug de texture » ; « je vois l'épée
qui est dans ma main visible, qui est pas dans le pantin ». Mesures reprises sur Mo' Bends (course, coup d'outil, vol).

- **Course** : les bras restent longs, comme ceux du jeu (avant-bras plié de 20 à 46° au lieu de 72 à 88°), et le buste tourne
  deux fois moins. Les coudes pliés à 90° qui pompaient faisaient monter les deux mains dans le champ à la première personne.
- **Vol en créatif** : deux mouvements à lui au lieu de la course dans le vide. `planer` (sur place, en reculant ou de
  côté) : le corps flotte, bras un peu ouverts, une jambe pliée. `planer_avance` (vers l'avant) : le corps penché de 26°,
  les bras le long du corps, les jambes qui traînent ; la tête reste à la place du joueur.
- **Coup de bras droit** : de haut en bas devant l'épaule, comme le jeu, au lieu d'une diagonale venue du côté. Le bras
  reste dans le plan de l'épaule, l'épée descend sur son tranchant ; le poignet se casse pendant le coup (45°), la lame part
  de derrière la tête et finit pointée devant, un peu vers le bas.
- **Armures à manches courtes** : plus de couche d'armure sur l'avant-bras pour les armures du jeu. Mesuré sur leurs
  textures : la manche du plastron s'arrête au-dessus du coude (lignes 0 à 4 ou 5 des 12 du bras, 6 pour la netherite) ;
  la couche de l'avant-bras n'en montrait que le bord, qui partait avec l'avant-bras et se décollait de l'épaulière dès que
  le coude pliait. Pour une armure d'un pack : `pantin.armures-manches-courtes` dans config.yml.
- **Épée dessinée deux fois** (dans la main du jeu et dans celle du pantin) après l'avoir prise dans un inventaire, celui du
  mode créatif surtout : le client y déplace lui-même sa copie de l'objet, sans la marque « caché en main », et le serveur ne
  lui renvoyait rien. Sa barre lui est maintenant renvoyée un tic après chaque clic dans un inventaire et à sa fermeture.
- `outils/apercu-mouvements.py` : le fichier de relecture Blockbench avec une épée en main.
- Le `pantin_mouvements.bbmodel` de la 1.7.1 est remplacé tout seul s'il n'a pas été retouché.

## 1.7.1 (novembre 2026) : animations du pantin plus belles, contrôlées une à une

Retour d'Antoine : « c'est bien, mais faut vraiment aller beaucoup plus loin : des animations super belles, qui ne sont pas
buggées. Magnifiques, mais simples, pas trop compliquées. »

- **Un contrôle rejoue chaque animation tic par tic** (`outils/verifier-mouvements.py`) et signale : une main, un avant-bras ou
  un coude dans la tête ou le buste, un pied sous le sol, un os qui saute (plus de 70° en un tic, 90° pour un coude), une
  boucle qui ne se referme pas. Il a trouvé 12 défauts dans la 1.7.0, tous corrigés ; les 47 animations passent.
  - coup de bras : le bras tournait de 100° en un tic (il sautait à l'écran) ;
  - viser à l'arc : la main de corde entrait dans la tête ; elle s'arrête maintenant sous la mâchoire ;
  - manger : l'avant-bras touchait le visage ;
  - tir à l'arc : le bras de corde sautait au lâcher.
- **Coup de bras** refait (9 tics au lieu de 6) : la main suit une courbe (armé sur le côté, à hauteur d'épaule, bref arrêt,
  puis le coup accélère en diagonale), au lieu d'aller en ligne droite d'une pose à l'autre.
- **Arc bandé qui respire** (`viser_arc_tenu`) : après la traction (1 s), le pantin n'est plus figé, l'arc et la main de corde
  bougent à peine. **Tir** : la main de corde part en arrière, l'arc tient un instant puis redescend (10 tics).
- **Repos** : en plus de la respiration, le poids du corps passe lentement d'une jambe à l'autre (8 s), la tête suit à peine.
- **Marche et course** : l'avant-bras suit le bras avec un peu de retard (le coude se plie quand le bras arrive devant), et
  le corps monte et descend sans à-coup quand le pied d'appui change.
- **Coup reçu** : le bassin, le buste et la tête partent en arrière l'un après l'autre ; les bras gardent ce qu'ils faisaient
  (la main qui tient l'épée ne lâche plus sa pose). Il n'est plus joué pendant un geste tenu (arc bandé, bouclier levé,
  repas) : il coupait le geste, qui repartait de zéro.
- **D'un geste à l'autre sans que le bras retombe** : le geste tenu qu'on quitte reste sous le suivant le temps qu'il entre,
  puis sort en fondu (traction de l'arc → arc bandé → tir, fin d'un repas, bouclier baissé).
- Le fichier `plugins/Vorace/modeles-animes/pantin_mouvements.bbmodel` de la 1.7.0 est remplacé tout seul s'il n'a pas été
  retouché.

## 1.7.0 (novembre 2026) : toutes les animations du pantin refaites

Retour d'Antoine : « fais des animations plus réalistes que celles de Minecraft, prends en base les mods et les MMO ».

- **Les mouvements du pantin sont réécrits** (`pantin_mouvements.bbmodel`, généré par `outils/mouvements-pantin.py`) : 45
  animations au lieu de 24. Chaque mouvement part de celui du jeu (formules de Minecraft 1.21.4) et des mesures prises sur
  Mo' Bends (buste qui tourne de ±20° en marche, bras levés sur les côtés en chute...), avec des genoux et des coudes, les
  épaules qui tournent à l'inverse du bassin, et la racine qui descend pour que le pied d'appui touche le sol.
  - Mouvements : repos, marche, course, recul, pas de côté, accroupi, accroupi en marche, saut, chute, nage (la brasse du
    jeu), **ramper** (pose de nage hors de l'eau), échelle, assis, dormir, vol, **vrille** (trident avec Impulsion).
  - Gestes, pour la main droite et la main gauche (`_gauche`) : **tenir** (la main qui tient un objet, bras levé comme dans
    le jeu), coup de bras (armé près de l'oreille puis coup en diagonale, le buste tourne), manger, **boire**, viser à l'arc
    (la main de corde tire jusqu'à la joue en 1 s, le buste s'ouvre, puis tient), **tir à l'arc** (au lâcher de la flèche),
    **charger l'arbalète**, viser à l'arbalète (arbalète chargée en main), bouclier, trident, longue-vue, **cor**,
    **brosse**, coup reçu.
  - Les mains sont posées par cinématique inverse : le script dit où va la main (la bouche, la corde, l'œil), il trouve les
    angles du bras. Un test vérifie que Vorace les pose au même endroit que Blockbench.
  - Le fichier `plugins/Vorace/modeles-animes/pantin_mouvements.bbmodel` de la 1.6.0 est remplacé tout seul au démarrage
    s'il n'a pas été retouché ; un fichier retouché est gardé (le supprimer pour recevoir le nouveau).
- **Cadence réglée sur la vitesse du joueur**, comme le jeu : marche, course, recul, pas de côté, accroupi, échelle et
  ramper avancent de la distance parcourue. Les pieds ne glissent plus sous une lenteur ou un bonus de vitesse, et un joueur
  arrêté sur une échelle ne grimpe plus dans le vide. D'une allure à l'autre, le même pied reste devant.
- **Les bras qui visent suivent le regard** vers le haut et le bas (arc, arbalète, bouclier, longue-vue, cor), comme le jeu.
- **Le corps se penche vers le regard** à la nage, en vol et en vrille (le jeu fait pareil) ; la tête du pantin est à la place
  du joueur dans ces mouvements (ses yeux restent dans la tête du pantin, il ne voit plus son dos à la première personne).
- **Dormir** : le pantin est couché dans le sens du lit, la tête sur l'oreiller.
- **Geste de fond** : la main qui tient un objet reste levée sous les autres gestes ; un coup de bras part de cette pose et y
  revient, sans que le bras retombe. Les postures de classe (« combat ») gardent leurs propres bras.
- **Gestes tenus** (`hold`) : l'arc bandé et l'arbalète chargée restent sur leur dernière pose au lieu de rejouer. Un geste
  court (coup de bras) entre et sort en 2 tics : avec le fondu de 4 tics, il n'atteignait jamais sa pleine amplitude.

## 1.6.0 (novembre 2026) : pantin permanent, corps fantôme, armures en volume

- **Pantin permanent** (`pantin.permanent.actif`, ou `/vorace pantin permanent on`) : le joueur est remplacé tout le temps
  par son pantin. Il joue de lui-même le repos, la marche, la course, le recul, les pas de côté, l'accroupi, le saut, la
  chute, la nage, l'échelle, assis et couché ; par-dessus, les gestes du bras (frapper, poser, miner), manger, viser à l'arc
  ou à l'arbalète, le bouclier, le trident, la longue-vue, et un sursaut aux coups reçus. Les compétences se jouent sur le
  même pantin, qui revient ensuite à ses mouvements. Entre deux compétences, son corps tourne comme celui du jeu (en retard
  sur le regard, la tête suit). Retiré en spectateur, sous une vraie potion d'invisibilité et à la mort, remis tout seul.
- **Modèle des mouvements** `modeles-animes/pantin_mouvements.bbmodel` (installé s'il manque, généré par
  `outils/mouvements-pantin.py`) : ses animations sont prêtées à tout modèle au squelette du joueur (les modèles des
  classes) par le nom des os ; un modèle qui a sa propre « marche » garde la sienne. Postures : « marche_combat »,
  « repos_blesse »... sont prises à la place de « marche », « repos » quand la posture est donnée (API, commande).
- **Corps fantôme** : le vrai joueur n'est plus invisible pour le serveur (les monstres de vorace-mob ignoraient un joueur
  invisible) mais seulement dans les paquets. Chez lui, une équipe qui ne contient que lui le fait dessiner en fantôme ; le
  shader du pack le rend opaque dans les menus (il se voit dans son inventaire, armure comprise) et l'efface dans le monde.
  Les dessins d'armure portée du pack sont marqués (opacité 253) pour n'être dessinés sur le vrai joueur que dans les menus.
  Avec un pack de shaders Iris ou OptiFine, le fantôme reste semi-transparent (`pantin.fantome.equipe-soi: false` le coupe).
- **Montures par paquets** (`pantin.monter-par-paquets`) : les morceaux du pantin ne sont passagers du joueur que chez les
  clients ; les téléportations des plugins ne sont plus bloquées et les déplacements des morceaux ne sont plus envoyés.
- **Réseau** : au-delà de `pantin.permanent.distance` (48), un joueur ne reçoit plus les morceaux du pantin et voit le joueur
  du jeu (corps et équipement rendus chez lui seul) ; quand personne n'est tout près, les morceaux sont mis à jour moins
  souvent (la copie pour soi reste à chaque tic).
- **Armures** : objets portés sur la tête (casque 3D de vorace-item, citrouille, tête) posés sur la tête du pantin comme le
  jeu le fait ; armures en volume tirées d'un .bbmodel (`pantin.armures-3d`, groupes « casque », « plastron »,
  « jambieres », « bottes »).
- **Première personne comme le mod First-person Model** (`pantin.permanent.premiere-personne`, essai en jeu avec
  `/vorace pantin vue pantin|main-du-jeu`) :
  - `pantin` (défaut, le réglage d'origine du mod) : le pantin tient l'objet dans ses mains, à la première personne comme
    en F5 ; la main du jeu est cachée (l'objet garde son icône dans la barre). Le joueur voit ses bras et son arme bouger
    avec ses animations.
  - `main-du-jeu` (« Vanilla Hands » du mod) : la main du jeu tient l'objet ; les bras de sa propre copie du pantin (skin et
    armure) sont effacés quand la caméra est dans sa tête, par le shader du pack (ils portent une marque dans leur teinte,
    deux bits bas des couleurs, que les autres pantins évitent) ; sa copie ne tient rien hors compétence.
  Un pantin permanent a toujours sa copie pour lui-même, même avec `recul-pour-soi: 0`.
- **La tête du pantin suit le regard, comme dans le jeu** : l'os de tête du pantin (« peau_tete ») n'était pas reconnu, la
  tête ne tournait donc jamais, ni de côté ni de haut en bas. Elle regarde maintenant où regarde le joueur (jusqu'à 90°
  vers le haut ou le bas). À l'arrêt, elle tourne seule sans que le corps bouge, jusqu'à 50° (`pantin.tete-max`) ; au-delà,
  elle entraîne le corps. En marche, le corps rejoint le regard (`pantin.corps-suit-regard`). Pendant une compétence, le
  corps est face au regard tout de suite et la tête reste à l'animation ; pendant un coup de bras ou une visée, il
  s'aligne en deux ou trois tics.
- **Gestes en boucle (viser à l'arc, manger, bouclier levé)** : les bras ne retombent plus à chaque tour de l'animation
  (le fondu d'entrée repartait de zéro à chaque boucle, toutes les secondes pour l'arc).
- **Pantin à la taille des joueurs du jeu** (`pantin.taille: 0.9375`, 1,875 bloc) : à 1, il faisait 2 blocs et son crâne
  entrait dans le plafond.
- **Arc tendu dans la main du pantin** : pendant que le joueur vise, l'arc de son pantin est dessiné bandé (arc du jeu :
  définition du pack ; arcs de vorace-item 0.14.13 et plus : drapeau 5 « tendu »). Un affichage d'objet n'a pas de porteur,
  le jeu l'aurait laissé au repos.
- **Barre d'objets du pantin permanent** : les copies « cachées en main » des objets du jeu gardent leur vraie icône
  (potion à sa couleur, boussole et horloge qui tournent, arbalète chargée) et les blocs tenus sont cachés aussi (définitions
  du jeu 1.21.4 livrées avec Vorace ; sur une autre version, icônes simples et blocs non cachés, comme avant). Un objet
  dont l'apparence vient des nombres ou textes de custom_model_data (pack d'un autre plugin) n'est plus remplacé.
- **Mode créatif** : un objet de la barre déplacé avec le pantin permanent ne garde plus la marque « caché » (drapeau 4 ou
  0 et définition `cache_tiers/` ou `cache_en_main/` : il restait invisible en main pour tout le monde). Le serveur le
  reçoit nettoyé ; les objets déjà abîmés sont réparés tout seuls.
- **Armures effacées dans le monde seulement avec le pantin permanent** : sans lui (réglage par défaut), le pack ne touche
  plus aux armures portées. Avant, `cacher-toutes-armures` retirait leur armure à tous les joueurs, monstres et
  porte-armures même sans pantin permanent. Après `/vorace pantin permanent on|off`, la commande dit s'il faut reconstruire
  le pack. L'armure du joueur n'est plus jamais dessinée sur son corps invisible, même avec un pack pas encore reconstruit.
- **Caméra de cinéma avec les montures par paquets** : à la descente des morceaux, leur position exacte est renvoyée aux
  clients (le pantin d'un ultime pouvait être dessiné chez les autres joueurs décalé du chemin parcouru depuis son
  apparition).
- **Charge** : un joueur qui arrive reçoit une seule annonce de passagers par pantin (une par morceau avant) ; les morceaux
  montés par paquets ne sont plus déplacés sur le serveur à chaque tic, seulement quand le joueur s'éloigne de 2 blocs.
- Essai : `pantin.soi-tourne-avec-la-camera` (copie pour soi en billboard, plus de retard en tournant à la 1re personne).
- Commandes : `/vorace pantin permanent on|off`, `vue pantin|main-du-jeu`, `posture <posture|aucune> [joueur]`, `modele <modèle> [joueur]`.
- API : `pantinPermanentActif`, `pantinPermanent`, `modelePantin`, `posturePantin`, `autoriserPantin`. `arreterJoueur` et
  `relacherJoueur` n'arrêtent que l'animation d'un pantin permanent.

## 1.5.5 (novembre 2026) : fin d'animation sans saut vers le joueur

- **Le pantin revient à la pose du vrai joueur avant l'échange** : après sa dernière pose (gardée 0,1 s pour enchaîner un
  combo), il glisse en 0,25 s vers la pose que le jeu donne au joueur, puis l'échange se fait. Plus de « retour au skin de
  base » d'un coup à la fin d'une animation.
- **Le corps tourne comme celui du jeu** : le jeu fait tourner le corps du joueur en retard sur son regard ; le pantin part de
  cette orientation au début de l'animation et y revient à la fin, la tête continuant de regarder où regarde le joueur. Le
  torse ne pivote plus au moment de l'échange.
- **Bras comme le jeu les met** : le bras qui tient un objet est levé de 18° vers l'avant, comme sur le vrai joueur (main
  gauche des gauchers comprise), au départ comme au retour.
- Le coup suivant d'un combo lancé pendant le retour reprend le même pantin, en fondu.
- Nouveaux réglages `pantin.pause-fin-secondes` (0,1) et `pantin.retour-secondes` (0,25 ; 0 = échange immédiat comme avant).
- API : `VoraceAPI.relacherJoueur(joueur)` arrête une animation en boucle en douceur (`arreterJoueur` reste immédiat, à
  garder avant une téléportation).

## 1.5.4 (novembre 2026) : ranger les modèles animés dans des dossiers

- **Un modèle animé s'appelle par le nom de son fichier, quel que soit son dossier** : on peut ranger
  `plugins/Vorace/modeles-animes/` en sous-dossiers (par exemple `classes/mage/mage.bbmodel`), le modèle s'appelle toujours
  `mage`. Avant, il s'appelait `classes/mage/mage` et les plugins qui le demandaient par son nom ne le trouvaient plus.
- L'ancien nom par chemin marche toujours (`modelengine/…`, mobs déjà posés).
- Si deux fichiers portent le même nom dans deux dossiers, chacun garde son chemin comme nom et la console le signale.

## 1.5.3 (novembre 2026) : icônes « cachées en main » voulues

- **Une icône peut garder sa définition « caché en main »** : un objet du serveur marqué `vorace:cache_en_main_voulu` (donnée
  persistante) n'est plus « réparé » par Vorace. Matsuri Cœur 0.42.1 s'en sert pour les icônes de compétences de la barre
  (dessinées dans la barre, rien dans la main quand on passe sur leur case) ; sans la marque, la réparation des copies du mode
  créatif leur retirait leur modèle toutes les 2 secondes et la barre ne montrait plus que des papiers.

## 1.5.2 (novembre 2026) : objets du jeu cachés en main, bouclier sanglé

(La 1.5.1, branche `tenue-retournee`, est abandonnée.)

- **L'arme de la main droite ne disparaît plus chez les autres joueurs après une animation** : à la fin du pantin,
  l'équipement réel était renvoyé aux autres avant que le joueur ne soit retiré des entités cachées ; l'écoute des paquets
  (fil réseau) vidait encore le premier envoyé, la main droite, et l'arme restait invisible pour les autres jusqu'au
  changement suivant. Le joueur est maintenant retiré d'abord, les deux mains sont renvoyées encore une fois au tick
  suivant, et le filet qui rattrape un joueur resté caché renvoie aussi son équipement aux autres.
- **Pendant une animation du pantin, plus d'arme en double** : un objet à l'apparence du jeu (épée de netherite, épée de fer,
  bâton de blaze, bouclier...) n'a pas de définition de vorace-item qui lise le drapeau « caché en main » ; le joueur le voyait
  encore dans sa main (première personne et F5) en plus de celui du pantin. Sa copie prend maintenant la définition
  `vorace:cache_en_main/<objet>` du pack de Vorace : rien en main, l'icône du jeu dans l'inventaire, au sol et dans un cadre.
  Une copie revenue du client en mode créatif est réparée (modèle retiré).
- **Bouclier dans la main gauche du pantin** : il est sanglé à l'avant-bras (plaque à l'extérieur du bras, haut vers le
  coude, centre 4 pixels au-dessus du poing), comme l'écu des animations. Avant, le réglage « main gauche » du bouclier du jeu
  était pris égal au réglage droit (translation (10, 6, -4)) alors qu'il vaut (10, 6, 12) : la plaque flottait à 28 pixels du
  poing, près de 2 blocs.
- **Poignet qui tourne autour de la poignée** : l'objet tenu part maintenant du bout de l'avant-bras calculé depuis le pivot de
  l'os du poing (milieu du bas du cube de l'avant-bras), et non plus du pivot lui-même. Un pantin dont le pivot du poing est
  posé sur la poignée de l'arme (matsuri-classes 0.39.0) plie le poignet sans que la poignée sorte du poing ; un pantin au
  pivot classique (bout de l'avant-bras) ne change pas.
- **Apparition posée sur la cible** : clé « apparition <modèle> <animation> [taille] posee[=centre] ». Visée, elle n'est plus
  étirée entre la main et la cible : tout le modèle, dessiné centré à `centre` blocs devant le lanceur (2 par défaut), est
  déplacé d'un bloc jusqu'à la vraie cible et à sa hauteur (`Visee.posee`). Pour ce qui se pose au sol (sceau, zone, pluie
  d'éclairs) : étiré, un cercle s'allongeait et se décalait de la cible.
- **Double d'un joueur** : `VoraceAPI.attacherDouble(mob, joueur, modèle, animation)` habille un mob du skin, de l'armure et des
  objets en main d'un joueur, sur un modèle au squelette du pantin (Clone d'ombre de l'Assassin : avant, un zombie coiffé de sa tête).

## 1.5.0 (novembre 2026) : apparitions visées

- **Une apparition peut viser un point** : `VoraceAPI.viser(joueur, point)` donne aux apparitions que lance le pantin de ce joueur
  (clé « apparition <modèle> <animation> ») un point à atteindre pendant deux secondes. Un sort dessiné pour une cible à deux blocs
  devant le lanceur vole alors jusqu'à la vraie cible, à n'importe quelle distance et hauteur : l'apparition se tourne vers elle,
  et les places de ses pièces sont étirées entre la main (11/16 de bloc devant) et la cible dessinée (2 blocs devant, à 21/16 de
  bloc de haut). Le dessin des pièces ne change pas ; une pièce et ses filles bougent ensemble.
- **Pièces tendues** : une pièce dont le nom finit par `_tendu` (éclair, rayon tiré d'un point vers un autre, le long de son -z)
  est en plus retournée et allongée pour rejoindre le point suivant.
- `VoraceAPI.viser(joueur, null)` efface le point (compétence sans cible, ultime). Sans appel, rien ne change.
- Tests : `TestVisee` (cible dessinée sur la vraie cible, mi-course, lacet, trait tendu, taille).

## 1.4.10 (novembre 2026)

- Caméra de cinéma : de nouveau en mode spectateur rattaché à un affichage invisible (fluide, sans barre d'objets ni
  interface). `pantin.camera-mode: monture` pour garder la barre d'objets (moins fluide).
- Emojis du chat collés tels quels : gardés pour un joueur qui a leur permission (glyphes à raccourci, hors fonds de
  menus, 16 pixels de haut au plus). Les autres caractères des polices (interface, espaces négatifs) restent retirés.

## 1.4.9 (novembre 2026)

- Caméra de cinéma : la monture est un support d'armure invisible (marqueur) au lieu d'un affichage d'objet ; le client
  adoucit ses déplacements, le joueur monté dessus suit la caméra sans à-coups.
- Arme par paire : de nouveau montrée au joueur lui-même dans sa main gauche (deux armes en F5) ; la case de la main
  gauche à côté de la barre d'objets est transparente dans le pack (`pantin.cacher-case-main-gauche`), et la copie n'y
  montre pas d'icône. `pantin.paires-soi` pour revenir à « les autres seulement ».

## 1.4.8 (novembre 2026) : sécurité

- Caractères des polices du pack (zone d'usage privé d'Unicode : images de l'interface, espaces négatifs) retirés de ce
  qu'écrivent les joueurs : chat, commandes, panneaux, livres, renommage à l'enclume. Ils pouvaient couvrir l'écran des
  autres ou imiter l'interface et les messages du serveur. Les emojis passent toujours par leurs raccourcis. Permission
  `vorace.glyphes.bruts` (op) pour les écrire quand même.
- Skins du pantin : téléchargés seulement depuis les serveurs de Mojang (un profil trafiqué ne fait plus télécharger
  n'importe quelle adresse au serveur), 256 Ko au plus.
- `/vorace pack` : une demande toutes les 10 secondes pour un joueur.

## 1.4.7 (novembre 2026)

- **Caméra de cinéma, interface gardée** : le jeu ne dessine la barre d'objets que si la caméra est le joueur lui-même ;
  le joueur (invisible) monte donc sur l'entité caméra, son regard est tourné vers la cible à chaque tic
  (ClientboundPlayerRotationPacket) ; Maj ne le fait pas descendre. Sans monture possible : mode spectateur.
- **Arme par paire** : la copie de la main gauche n'est plus envoyée au joueur lui-même (la case de la main gauche
  apparaissait à côté de sa barre d'objets) ; les autres le voient toujours avec une arme dans chaque main.
- Couleurs du bras à la première personne appliquées aussi quand tout l'inventaire est renvoyé.

## 1.4.6 (novembre 2026)

- **Armure invisible, casque posé sur la tête** : en mode créatif, le client renvoie ses objets au serveur ; les copies
  d'armure de l'animation devenaient de vrais objets. Elles sont maintenant marquées (drapeau 3) et refusées, et les
  objets déjà abîmés sont réparés toutes les deux secondes (armure du jeu rendue à son équipement d'origine ; les objets
  de vorace-item retrouvent le leur à la connexion suivante).
- **Caméra de cinéma sans mode spectateur** : la caméra est posée par le paquet du jeu ; le joueur garde son mode de
  jeu, sa barre d'objets et son interface, immobile (marche, saut et vol à zéro, ramené à sa place) et intouchable
  jusqu'à la fin. Sans la couche paquets, mode spectateur comme avant.

## 1.4.5 (novembre 2026)

- **Bras à la première personne avec une arme de poing** : le jeu ne dessine plus le bras dès qu'un objet est en main ;
  Vorace fournit un modèle du bras droit (un carré teinté par pixel, large et fin) posé exactement là où le jeu met le
  bras à main vide (`PeauUV.PREMIERE_ROTATION` / `PREMIERE_TRANSLATION`), et envoie au joueur seulement ses armes par
  paire avec les couleurs de son skin (custom_model_data, drapeau 2 « bras fin ») ; vorace-item 0.14.9 les assemble.
  En créatif, ces couleurs sont retirées de ce que le client renvoie.
- **Casque pendant une animation** : il apparaissait comme un objet posé sur la tête (pièce sans modèle porté) ; il
  reçoit maintenant un équipement sans couche (`vorace:vide`).
- **Manches sur le pantin** : avec une arme par paire, la manche d'armure des avant-bras n'est plus posée (elle ressortait
  par endroits à travers l'arme pendant les coups).

## 1.4.4 (novembre 2026)

- Fin d'animation : l'armure et les objets du joueur ne revenaient pas chez lui (la fin ne rendait pas la main à
  `ObjetsCaches`) ; filet en plus : chaque seconde, un joueur qui n'est plus caché par son pantin revoit tout.
- Arme par paire : la copie de la main gauche (qui n'existe que chez le client) ne peut plus devenir un vrai objet en
  mode créatif (paquet de case créative refusé, inventaire renvoyé). Revue toutes les demi-secondes (mode combat et
  plugins qui changent l'inventaire sans événement), renvoyée seulement quand elle change.

## 1.4.3 (novembre 2026)

- **Rien sur le corps invisible** : pendant une animation, le joueur ne voit plus son armure ni ses objets sur son
  corps caché (F5), même quand son inventaire change (arme usée par un coup). Plus d'objets « invisibles » dans
  l'inventaire : les icônes restent, seuls les modèles portés et tenus disparaissent. Vorace change les objets des
  paquets envoyés (`ObjetsCaches` : armure sans modèle porté, objets en main avec le drapeau 0 de custom_model_data),
  jamais l'inventaire du serveur, et le renvoie tel quel à la fin. Les autres reçoivent son équipement vide pendant
  toute l'animation.
- **Arme par paire** : la copie de la main gauche porte le drapeau 1 (« paire ») : vue en F5 et par les autres, pas à
  la première personne ni dans la case de la main gauche. Renvoyée aussi quand un joueur en voit arriver un autre.

## 1.4.2 (novembre 2026)

- **Taille du pantin** : le bas du torse ne remonte plus dans le haut ; le haut du torse descend d'un demi-pixel
  par-dessus (un vingtième de pixel plus large), comme une veste sur une ceinture. Quand le buste tourne ou se penche, le
  bas ne ressort plus sur les côtés. Le bas du torse a un dessus. `joueur.bbmodel` d'une version précédente remplacé
  s'il n'a pas été retouché.
- **Objets tenus, main gauche** : épée, outil ou objet à plat pris avec le réglage droit de leur modèle (c'est ce que le
  jeu fait dans la main gauche) ; bouclier replacé comme le jeu le tient ; arme par paire avec son modèle de main gauche.
- **Plus de double** : pendant l'animation, le jeu ne dessine plus les objets tenus du joueur chez lui (première
  personne, F5), seulement ceux de sa copie du pantin. `pantin.cacher-objets-soi`.
- **Armes par paire** (armes de poing) : sans rien dans la main gauche, l'arme y est aussi montrée hors animation, à
  tous et au joueur (copie envoyée au client, inventaire inchangé). `pantin.paires`.

## 1.4.1 (novembre 2026)

- **Objets tenus sur le pantin** : l'arme (ou tout objet) en main droite et en main gauche est dans les mains du
  pantin, placée et tournée comme le jeu la tient à la troisième personne (même réglage « en main » de son modèle) ;
  une arme marquée « par paire » par vorace-item (`voraceitem:paire`, armes de poing) va dans les deux mains si la
  main gauche est vide. La copie du pantin pour soi les tient aussi. `pantin.objets-en-main`.

## 1.4.0 (novembre 2026) : pantin des joueurs et réactions des monstres

### Pantin des joueurs (animations du corps entier avec le vrai skin, sans mod)
- **Skin dessiné pixel par pixel** (même technique que BetterModel) : chaque morceau du pantin est un modèle du pack
  fait d'un carré par pixel, coloré avec le skin du joueur par le composant custom_model_data (seconde couche
  affichée selon un drapeau par pixel ; bras fins pour les skins « slim »). Plus de MineSkin ni de clé : le skin est lu
  chez Mojang une fois puis gardé dans `peaux/`. Le jeu dessine ces modèles faces arrière élaguées : le joueur voit son
  propre pantin depuis ses yeux, tête comprise, sans en voir l'intérieur, et se voit avec sa vraie tête en F5.
- Copie du pantin pour le joueur lui-même, reculée de `pantin.recul-pour-soi` pixels (4, comme le mod « First Person
  Model ») : à la première personne, il voit ses bras et ses poings partir devant lui sans les épaules dans la caméra.
- `VoraceAPI.animerJoueur(joueur, modèle, animation)` : pendant l'animation, le joueur est caché (corps invisible,
  équipement vide envoyé aux autres) et remplacé par un modèle animé dont chaque os « peau_... » porte le morceau de
  skin correspondant. Le pantin disparaît à la fin.
- Squelette `modeles-animes/joueur.bbmodel` (tête + tronc, bras et jambes en deux morceaux), remis s'il manque ; un
  modèle de classe reprend ce squelette et ajoute ses animations. Animations d'essai : `essai`, `salut`, `croix`.
- Skin préparé à la connexion ; pendant la préparation : habits par défaut du squelette.
- Le pantin est monté sur le joueur (ses morceaux sont des passagers) : il le suit sans aucun retard, même à la
  première personne, et regarde là où le joueur regarde. Pendant une animation, Paper refuse de téléporter un joueur
  qui porte des passagers : appeler d'abord `VoraceAPI.arreterJoueur`. Le pantin s'arrête à la mort et au départ.
  `pantin.monter-sur-le-joueur: false` rend l'ancien suivi.
- Pantin mis à jour à chaque tic.
- Armure sur le pantin : casque, plastron, jambières et bottes portés sont dessinés sur le pantin et suivent
  l'animation (modèles générés pour chaque os avec les textures d'équipement du jeu, ajoutées à l'atlas des objets ;
  cuir teint de sa couleur, éclat des pièces enchantées). `pantin.armure`, `pantin.armures-perso` pour les armures d'un
  pack. Les ornements (trims) ne sont pas dessinés.
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

### Caméra de cinéma
- Une animation du pantin dont l'os « camera » est animé (et « camera_cible », le point regardé) se voit comme un film :
  le joueur passe en spectateur rattaché à une caméra invisible (entité d'affichage, mouvements adoucis), le pantin
  reste sur place, puis le joueur retrouve son mode de jeu, sa place et son regard à la fin (même après un arrêt du
  serveur, à sa connexion). `pantin.camera-ultimes`.
- Fin d'une caméra de cinéma : le joueur ne reste plus invisible (son état d'avant était lu alors qu'il était déjà
  spectateur). `/vorace pantin visible [joueur]` rend visible un joueur resté invisible.
- Maj pendant une caméra de cinéma ne la détache plus (plus d'à-coups).
- La caméra est redonnée au joueur quelques tics après son apparition : le client ignore une caméra qu'il ne connaît
  pas encore, et le joueur restait dans ses propres yeux.

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
