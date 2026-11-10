#!/bin/bash
# Essai automatique sur un vrai serveur Paper (CI) : pose le golem d'exemple, lance des animations et vérifie
# que les entités d'affichage changent vraiment, côté serveur (/vorace modele info) et côté joueur (faux client).
# Usage : outils/essai-serveur.sh <version de Paper> <dossier contenant Vorace-*.jar> [version du faux joueur]
# Avec une version de joueur plus récente que le serveur, ViaVersion est installé (comme sur beaucoup de serveurs).
set -uo pipefail
VERSION=${1:-1.21.4}
JAR=$(cd "$2" && ls "$PWD"/Vorace-*.jar | head -1)
CLIENT=${3:-}
OUTILS=$(cd "$(dirname "$0")" && pwd)
mkdir -p essai-serveur/plugins && cd essai-serveur
cp "$JAR" plugins/
URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$VERSION/builds/latest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')
echo "Paper : $URL"
curl -fsSL -o paper.jar "$URL"
if [ -n "$CLIENT" ]; then
  VIA=$(curl -fsSL https://api.github.com/repos/ViaVersion/ViaVersion/releases/latest | python3 -c 'import json,sys; print([a["browser_download_url"] for a in json.load(sys.stdin)["assets"] if a["name"].endswith(".jar")][0])')
  echo "ViaVersion : $VIA"
  curl -fsSL -o plugins/ViaVersion.jar "$VIA"
fi
# contenu d'exemple d'autres plugins, pour essayer /vorace convertir (leurs fichiers seulement, pas les plugins)
cp -r "$OUTILS/exemples-conversion/." plugins/
mkdir -p plugins/ModelEngine/blueprints/monstres
python3 - "$OUTILS/../src/main/resources/exemples/modeles/golem_rubis.bbmodel" plugins/ModelEngine/blueprints/monstres/golem_me.bbmodel <<'PY'
import json, sys
g = json.load(open(sys.argv[1], encoding='utf-8'))
g["elements"].append({"name": "hb", "type": "cube", "uuid": "hitbox-cube-0001", "from": [-8, 0, -8], "to": [8, 40, 8], "origin": [0, 0, 0], "faces": {}})
g["outliner"].append({"name": "hitbox", "origin": [0, 0, 0], "uuid": "hitbox-groupe-0001", "children": ["hitbox-cube-0001"]})
json.dump(g, open(sys.argv[2], 'w', encoding='utf-8'))
PY
# maillages de Blockbench : le golem avec une cape (face droite) et un voile incliné de 30° (partie à part)
mkdir -p plugins/Vorace/modeles-animes
python3 - "$OUTILS/../src/main/resources/exemples/modeles/golem_rubis.bbmodel" plugins/Vorace/modeles-animes/golem_cape.bbmodel <<'PY'
import json, math, sys
g = json.load(open(sys.argv[1], encoding='utf-8'))
c, s = math.cos(math.radians(30)), math.sin(math.radians(30))
g["elements"] += [
  {"name": "cape", "type": "mesh", "uuid": "maillage-cape", "origin": [0, 4, 6], "rotation": [0, 0, 0],
   "vertices": {"a": [-6, 0, 0], "b": [6, 0, 0], "c": [6, 16, 0], "d": [-6, 16, 0]},
   "faces": {"f": {"vertices": ["a", "c", "b", "d"], "uv": {"a": [0, 16], "b": [12, 16], "c": [12, 0], "d": [0, 0]}, "texture": 0}}},
  {"name": "voile", "type": "mesh", "uuid": "maillage-voile", "origin": [0, 20, -6], "rotation": [0, 0, 0],
   "vertices": {"a": [-4, 0, 0], "b": [4, 0, 0], "c": [4, 8 * c, 8 * s], "d": [-4, 8 * c, 8 * s]},
   "faces": {"f": {"vertices": ["a", "b", "c", "d"], "uv": {"a": [0, 8], "b": [8, 8], "c": [8, 0], "d": [0, 0]}, "texture": 0}}}]
racine = next(o for o in g["outliner"] if isinstance(o, dict))
racine["children"] += ["maillage-cape", "maillage-voile"]
json.dump(g, open(sys.argv[2], 'w', encoding='utf-8'))
PY
echo "eula=true" > eula.txt
cat > server.properties <<PROPS
online-mode=false
level-type=minecraft\:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
generate-structures=false
spawn-protection=0
spawn-monsters=false
spawn-animals=false
view-distance=4
simulation-distance=4
PROPS
rm -f console && mkfifo console
exec 3<>console
java -Xmx2G -jar paper.jar --nogui < console > journal.txt 2>&1 &
SERVEUR=$!
cmd() { echo ">>> $1" >> commandes.txt; echo "$1" >&3; }
attendre() { # attendre <motif> <fichier> <secondes>
  for _ in $(seq 1 "$3"); do grep -q "$1" "$2" 2>/dev/null && return 0; sleep 1; done; return 1
}
if ! attendre 'Done (' journal.txt 400; then echo "Le serveur n'a pas démarré"; tail -80 journal.txt; exit 1; fi
cmd "vorace modele"
node "$OUTILS/essai-bot.mjs" 100 $CLIENT > bot.txt 2>&1 &
BOT=$!
attendre 'BOT CONNECTE' bot.txt 60 || { echo "Le faux joueur ne s'est pas connecté"; cat bot.txt; }
sleep 2
cmd "vorace modele poser golem_rubis"
sleep 3
cmd "vorace modele info"; sleep 1; cmd "vorace modele info"
sleep 1
cmd "vorace modele anim marche boucle"
sleep 2
cmd "vorace modele info"; sleep 1; cmd "vorace modele info"; sleep 1; cmd "vorace modele info"
cmd "vorace modele anim attaque"
sleep 0.3
cmd "vorace modele info"
sleep 2
cmd "vorace modele stop"
sleep 1
cmd "vorace modele info"
# améliorations : animation par-dessus, taille du modèle
cmd "vorace modele anim marche boucle"
sleep 0.5
cmd "vorace modele anim salut dessus"
sleep 0.3
cmd "vorace modele info"
cmd "vorace modele taille 1.5"
sleep 1
cmd "vorace modele info"
cmd "vorace modele stop"
# couche paquets : heure remplacée par midi, un message bloqué, un autre qui passe
cmd "gamerule doDaylightCycle false"
cmd "time set 18000"
cmd "vorace paquets essai"
wait $BOT
# conversion du contenu d'exemple de Nexo, ItemsAdder et ModelEngine
AVANT_CONVERSION=$(wc -l < journal.txt)
cmd "vorace convertir"
cmd "vorace convertir tout"
attendre 'Les objets sont dans' journal.txt 90 || echo "La conversion n'a pas fini"
sleep 2
cmd "vorace modele poser modelengine/golem_me"
sleep 2
cmd "vorace modele info"
cmd "vorace modele poser golem_cape"
sleep 2
cmd "vorace modele info"
cmd "vorace meuble liste"
cmd "vorace bloc liste"
cmd "vorace glyphe liste"
# mise à jour des objets : ce qui appartient à chaque exemplaire est gardé quand la définition change
cmd "vorace maj essai"
sleep 2
# menus (1.0) : un faux joueur admin ouvre /vorace et clique
cmd "op Menus"
node "$OUTILS/essai-menus.mjs" $CLIENT > menus.txt 2>&1
sleep 1
cmd "stop"
for _ in $(seq 1 60); do kill -0 $SERVEUR 2>/dev/null || break; sleep 1; done
kill $SERVEUR 2>/dev/null

echo "===== Faux joueur"; cat bot.txt
echo "===== Console (Vorace)"; grep -aiE 'vorace|modèle|empreinte|animation|paquets|exception|error|at fr\.|game mode' journal.txt | grep -v 'Loading server plugin' | head -150
echo "===== Commandes envoyées, dans l'ordre"; cat commandes.txt
echo "===== Fin du journal"; tail -30 journal.txt

# verdict
EMPREINTES=$(grep -ao 'empreinte=[-0-9.]*' journal.txt | sort -u | wc -l)
DISTINCTES=$(grep -ao 'transformations-distinctes=[0-9]*' bot.txt | cut -d= -f2)
echo "empreintes serveur distinctes : $EMPREINTES ; transformations reçues par le joueur : ${DISTINCTES:-0}"
echo "===== Conversion"
tail -n +"$AVANT_CONVERSION" journal.txt | grep -av 'Loading server plugin' | head -80
for f in essai-serveur/plugins/Vorace/conversion/rapport-*.txt plugins/Vorace/conversion/rapport-*.txt; do [ -f "$f" ] && { echo "--- $f"; cat "$f"; }; done
ALERTES_CONVERSION=$(tail -n +"$AVANT_CONVERSION" journal.txt | grep -acE 'WARN\]: \[Vorace\]')
echo "alertes de Vorace après la conversion : $ALERTES_CONVERSION"
for s in Nexo ItemsAdder ModelEngine; do grep -aqE "$s : [0-9]+ objet" journal.txt || { echo "ECHEC : conversion de $s"; exit 1; }; done
if [ "$ALERTES_CONVERSION" -gt 0 ]; then echo "ECHEC : les fichiers convertis donnent des alertes au chargement"; exit 1; fi
echo "===== Menus"; cat menus.txt
grep -aq 'MENUS OK' menus.txt || { echo "ECHEC : menus"; exit 1; }
echo "===== Maillages"; grep -a 'golem_cape' journal.txt | head -5
grep -aq 'golem_cape : .*parties=[0-9]*/9 ' journal.txt || { echo "ECHEC : maillages (cape et voile du golem)"; exit 1; }
if grep -aq 'maillage « ' journal.txt; then echo "ECHEC : une face de maillage n'a pas été convertie"; exit 1; fi
echo "===== Mise à jour des objets"; grep -a 'ESSAI OBJETS' journal.txt
grep -aq 'ESSAI OBJETS echecs=0' journal.txt || { echo "ECHEC : mise à jour des objets (données de l'exemplaire)"; exit 1; }
ERREURS=$(grep -acE 'ERROR\]: \[Vorace\]|at fr\.vorace\.' journal.txt)
echo "erreurs de Vorace dans la console : $ERREURS"
if [ "$ERREURS" -gt 0 ]; then echo "ECHEC : erreurs de Vorace dans la console"; exit 1; fi
if [ "$EMPREINTES" -lt 3 ] || [ "${DISTINCTES:-0}" -lt 50 ]; then echo "ECHEC : les modèles ne s'animent pas"; exit 1; fi
grep -aq 'par-dessus' journal.txt || { echo "ECHEC : animation par-dessus"; exit 1; }
grep -aq 'taille=1.50 vue=proche' journal.txt || { echo "ECHEC : taille du modèle ou portée d'affichage"; exit 1; }
grep -aq 'ESSAI PAQUETS heures-modifiees=[1-9][0-9]* messages-bloques=[1-9][0-9]* paquets-recus=[1-9][0-9]* erreurs=0' journal.txt || { echo "ECHEC : couche paquets (côté serveur)"; exit 1; }
grep -aq 'VORACE-LIBRE' bot.txt || { echo "ECHEC : le message libre n'est pas arrivé au joueur"; exit 1; }
if grep -aq 'VORACE-BLOQUE' bot.txt; then echo "ECHEC : le message bloqué est arrivé au joueur"; exit 1; fi
grep -aqE 'BOT HEURES.* 6000( |$)|BOT HEURES 6000( |$)' bot.txt || { echo "ECHEC : l'heure reçue par le joueur n'a pas été remplacée par midi"; exit 1; }
echo "ESSAI REUSSI"
