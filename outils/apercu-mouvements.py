# Fichier de relecture pour Blockbench : les mouvements du pantin avec une épée dans la main droite (celle du jeu : lame 10°
# sous l'horizontale bras pendant, pointe à 13,5 pixels de la poignée), pour juger le coup de bras avec ce qu'il tient.
#   python3 outils/apercu-mouvements.py src/main/resources/exemples/modeles/pantin_mouvements.bbmodel pantin_mouvements_epee.bbmodel
# Le fichier produit ne va pas dans le plugin.
import json, sys, uuid
src, dst = sys.argv[1], sys.argv[2]
d = json.load(open(src, encoding="utf-8"))
def cherche(n, nom):
    for c in n:
        if isinstance(c, dict):
            if c.get("name") == nom: return c
            r = cherche(c.get("children", []), nom)
            if r: return r
g = cherche(d["outliner"], "poing_droit")
def cube(nom, de, a, couleur):
    u = str(uuid.uuid4())
    faces = {f: {"uv": [0, 0, 1, 1], "texture": False} for f in ("north", "south", "east", "west", "up", "down")}
    d["elements"].append({"name": nom, "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
                          "allow_mirror_modeling": False, "from": de, "to": a, "autouv": 0, "color": couleur,
                          "origin": [6, 12, 0], "rotation": [-10, 0, 0], "faces": faces, "type": "cube", "uuid": u})
    g["children"].append(u)
cube("epee_lame", [5.75, 11.5, -13.5], [6.25, 12.9, -1.6], 6)
cube("epee_garde", [5.5, 10.6, -1.6], [6.5, 13.8, -1.0], 2)
cube("epee_poignee", [5.7, 11.7, -1.0], [6.3, 12.7, 1.6], 8)
d["name"] = "pantin_mouvements_epee"
json.dump(d, open(dst, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
