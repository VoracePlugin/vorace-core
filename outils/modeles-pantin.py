# Génère le squelette du pantin des joueurs et le mannequin des réactions (projets Blockbench) :
#   python3 outils/modeles-pantin.py src/main/resources/exemples/modeles
# joueur.bbmodel : os « peau_... » (un cube chacun), repères poing_droit, poing_gauche, pied_droit, pied_gauche (sans cube,
# pour placer les particules des animations : « flame*20@poing_droit ») que le pantin remplace par des têtes portant le vrai skin ;
# un modèle de classe (animations de compétences) reprend ce squelette tel quel et ajoute ses animations.
# mannequin_reactions.bbmodel : mannequin d'essai qui a les réactions aux compétences (saisi, plaque, souleve...).
import base64, io, json, os, random, sys, uuid
from PIL import Image

random.seed(11)
DOSSIER = sys.argv[1]

def uid():
    return str(uuid.UUID(int=random.getrandbits(128)))

def source(img):
    b = io.BytesIO(); img.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()

def texture(nom, img):
    return {"path": "", "name": nom + ".png", "folder": "", "namespace": "", "id": "0", "width": 64, "height": 64,
            "uv_width": 64, "uv_height": 64, "particle": False, "use_as_default": False, "layers_enabled": False, "sync_to_project": "",
            "render_mode": "default", "render_sides": "auto", "frame_time": 1, "frame_order_type": "loop", "frame_order": "",
            "frame_interpolate": False, "visible": True, "internal": True, "saved": False, "uuid": uid(), "source": source(img)}

class Projet:
    def __init__(self):
        self.elements = []

    def cube(self, nom, de, a, zones):
        """zones : dict face -> (u0, v0, u1, v1) ; 'tout' pour une valeur par défaut."""
        faces = {}
        for f in ("north", "south", "east", "west", "up", "down"):
            faces[f] = {"uv": list(zones.get(f, zones["tout"])), "texture": 0}
        e = {"name": nom, "box_uv": False, "rescale": False, "locked": False, "render_order": "default", "allow_mirror_modeling": True,
             "from": de, "to": a, "autouv": 0, "color": 0, "origin": [(de[i] + a[i]) / 2 for i in range(3)],
             "faces": faces, "type": "cube", "uuid": uid()}
        self.elements.append(e)
        return e["uuid"]

def groupe(nom, origine, enfants):
    return {"name": nom, "origin": origine, "color": 0, "uuid": uid(), "export": True, "mirror_uv": False,
            "isOpen": True, "locked": False, "visibility": True, "autouv": 0, "children": enfants}

def cle(canal, t, x, y, z, interp="linear"):
    return {"channel": canal, "data_points": [{"x": x, "y": y, "z": z}], "uuid": uid(), "time": t, "color": -1, "interpolation": interp}

def anim(nom, duree, boucle, pistes):
    animateurs = {}
    for g, cles in pistes:
        animateurs[g["uuid"]] = {"name": g["name"], "type": "bone", "keyframes": cles}
    return {"uuid": uid(), "name": nom, "loop": boucle, "override": False, "length": duree, "snapping": 20,
            "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animateurs}

def ecrire(nom, projet, outliner, img, animations):
    p = {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
         "name": nom, "model_identifier": "", "visible_box": [1, 2, 0], "variable_placeholders": "", "variable_placeholder_buttons": [],
         "timeline_setups": [], "unhandled_root_fields": {}, "resolution": {"width": 64, "height": 64},
         "elements": projet.elements, "outliner": outliner, "textures": [texture(nom, img)], "animations": animations}
    chemin = os.path.join(DOSSIER, nom + ".bbmodel")
    with open(chemin, "w") as f:
        json.dump(p, f, indent=1)
    print("ok", chemin)

# ================================================================ joueur (squelette du pantin)
T = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
def zone(x0, y0, l, h, c, bruit=6):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + l):
            T.putpixel((x, y), tuple(max(0, min(255, v + random.randint(-bruit, bruit))) for v in c) + (255,))
zone(0, 0, 8, 8, (198, 150, 118))      # peau
zone(8, 0, 8, 8, (58, 96, 160))        # chemise
zone(16, 0, 8, 8, (64, 58, 92))        # pantalon
zone(24, 0, 8, 8, (198, 150, 118), 0)  # visage
for (x, y) in [(25, 4), (26, 4), (29, 4), (30, 4)]:
    T.putpixel((x, y), (40, 40, 60, 255))
for x in range(26, 30):
    T.putpixel((x, 6), (150, 96, 80, 255))
zone(32, 0, 8, 8, (70, 50, 34))        # cheveux
PEAU, CHEMISE, PANTALON, VISAGE, CHEVEUX = (0, 0, 8, 8), (8, 0, 16, 8), (16, 0, 24, 8), (24, 0, 32, 8), (32, 0, 40, 8)

pj = Projet()
tete = groupe("peau_tete", [0, 24, 0], [pj.cube("tete", [-4, 24, -4], [4, 32, 4], {"tout": PEAU, "north": VISAGE, "up": CHEVEUX})])
bras_d_bas = groupe("peau_bras_droit_bas", [6, 18, 0], [pj.cube("bras_droit_bas", [4, 12, -2], [8, 18, 2], {"tout": PEAU}), groupe("poing_droit", [6, 12, 0], [])])
bras_d_haut = groupe("peau_bras_droit_haut", [6, 22, 0], [pj.cube("bras_droit_haut", [4, 18, -2], [8, 24, 2], {"tout": CHEMISE}), bras_d_bas])
bras_g_bas = groupe("peau_bras_gauche_bas", [-6, 18, 0], [pj.cube("bras_gauche_bas", [-8, 12, -2], [-4, 18, 2], {"tout": PEAU}), groupe("poing_gauche", [-6, 12, 0], [])])
bras_g_haut = groupe("peau_bras_gauche_haut", [-6, 22, 0], [pj.cube("bras_gauche_haut", [-8, 18, -2], [-4, 24, 2], {"tout": CHEMISE}), bras_g_bas])
corps_haut = groupe("peau_corps_haut", [0, 18, 0], [pj.cube("corps_haut", [-4, 18, -2], [4, 24, 2], {"tout": CHEMISE}), tete, bras_d_haut, bras_g_haut])
corps_bas = groupe("peau_corps_bas", [0, 12, 0], [pj.cube("corps_bas", [-4, 12, -2], [4, 18, 2], {"tout": CHEMISE}), corps_haut])
jambe_d_bas = groupe("peau_jambe_droite_bas", [2, 6, 0], [pj.cube("jambe_droite_bas", [0, 0, -2], [4, 6, 2], {"tout": PANTALON}), groupe("pied_droit", [2, 0, 0], [])])
jambe_d_haut = groupe("peau_jambe_droite_haut", [2, 12, 0], [pj.cube("jambe_droite_haut", [0, 6, -2], [4, 12, 2], {"tout": PANTALON}), jambe_d_bas])
jambe_g_bas = groupe("peau_jambe_gauche_bas", [-2, 6, 0], [pj.cube("jambe_gauche_bas", [-4, 0, -2], [0, 6, 2], {"tout": PANTALON}), groupe("pied_gauche", [-2, 0, 0], [])])
jambe_g_haut = groupe("peau_jambe_gauche_haut", [-2, 12, 0], [pj.cube("jambe_gauche_haut", [-4, 6, -2], [0, 12, 2], {"tout": PANTALON}), jambe_g_bas])
racine = groupe("racine", [0, 0, 0], [corps_bas, jambe_d_haut, jambe_g_haut])

anims_joueur = [
    # essai : un direct du droit, garde du gauche, buste qui tourne (x négatif = bras vers l'avant)
    anim("essai", 0.5, "once", [
        (bras_d_haut, [cle("rotation", 0, -30, 0, -10), cle("rotation", 0.12, -90, 0, 0), cle("rotation", 0.25, -90, 0, 0), cle("rotation", 0.5, 0, 0, 0)]),
        (bras_d_bas, [cle("rotation", 0, -60, 0, 0), cle("rotation", 0.12, 0, 0, 0), cle("rotation", 0.25, 0, 0, 0), cle("rotation", 0.5, 0, 0, 0)]),
        (bras_g_haut, [cle("rotation", 0, -40, 0, 15), cle("rotation", 0.5, 0, 0, 0)]),
        (bras_g_bas, [cle("rotation", 0, -80, 0, 0), cle("rotation", 0.5, 0, 0, 0)]),
        (corps_haut, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.12, 0, -25, 0), cle("rotation", 0.25, 0, -25, 0), cle("rotation", 0.5, 0, 0, 0)]),
        (jambe_d_haut, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.12, 15, 0, 0), cle("rotation", 0.5, 0, 0, 0)]),
        (jambe_g_haut, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.12, -20, 0, 0), cle("rotation", 0.5, 0, 0, 0)]),
    ]),
    anim("salut", 1.4, "once", [
        (bras_d_haut, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.25, 0, 0, 160), cle("rotation", 1.15, 0, 0, 160), cle("rotation", 1.4, 0, 0, 0)]),
        (bras_d_bas, [cle("rotation", 0.25, 0, 0, 0), cle("rotation", 0.45, 0, 0, 30), cle("rotation", 0.65, 0, 0, -30), cle("rotation", 0.85, 0, 0, 30),
                      cle("rotation", 1.05, 0, 0, -30), cle("rotation", 1.15, 0, 0, 0)]),
        (tete, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.3, 0, 0, -10), cle("rotation", 1.1, 0, 0, -10), cle("rotation", 1.4, 0, 0, 0)]),
    ]),
    # pose de calage : bras en croix, à garder 3 s pour vérifier le skin
    anim("croix", 3.0, "once", [
        (bras_d_haut, [cle("rotation", 0, 0, 0, 90), cle("rotation", 3, 0, 0, 90)]),
        (bras_g_haut, [cle("rotation", 0, 0, 0, -90), cle("rotation", 3, 0, 0, -90)]),
    ]),
]
ecrire("joueur", pj, [racine], T, anims_joueur)

# ================================================================ mannequin des réactions
T = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
zone(0, 0, 16, 16, (164, 120, 74), 10)   # bois clair
zone(16, 0, 16, 16, (120, 84, 50), 10)   # bois sombre
zone(32, 0, 8, 8, (200, 60, 50), 4)      # cible rouge
BOIS, SOMBRE, CIBLE = (0, 0, 16, 16), (16, 0, 32, 16), (32, 0, 40, 8)
pm = Projet()
m_tete = groupe("tete", [0, 24, 0], [pm.cube("tete", [-4, 24, -4], [4, 32, 4], {"tout": BOIS, "north": CIBLE})])
m_bras_d = groupe("bras_droit", [6, 22, 0], [pm.cube("bras_droit", [4, 12, -2], [8, 24, 2], {"tout": SOMBRE})])
m_bras_g = groupe("bras_gauche", [-6, 22, 0], [pm.cube("bras_gauche", [-8, 12, -2], [-4, 24, 2], {"tout": SOMBRE})])
m_corps = groupe("corps", [0, 12, 0], [pm.cube("corps", [-4, 12, -2], [4, 24, 2], {"tout": BOIS, "north": CIBLE}), m_tete, m_bras_d, m_bras_g])
m_jambe_d = groupe("jambe_droite", [2, 12, 0], [pm.cube("jambe_droite", [0, 0, -2], [4, 12, 2], {"tout": SOMBRE})])
m_jambe_g = groupe("jambe_gauche", [-2, 12, 0], [pm.cube("jambe_gauche", [-4, 0, -2], [0, 12, 2], {"tout": SOMBRE})])
m_racine = groupe("racine", [0, 0, 0], [m_corps, m_jambe_d, m_jambe_g])

anims_mannequin = [
    anim("repos", 2.0, "loop", [
        (m_corps, [cle("rotation", 0, 0, 0, 0, "catmullrom"), cle("rotation", 1, 2, 0, 0, "catmullrom"), cle("rotation", 2, 0, 0, 0, "catmullrom")]),
    ]),
    anim("marche", 1.0, "loop", [
        (m_jambe_d, [cle("rotation", 0, 25, 0, 0), cle("rotation", 0.5, -25, 0, 0), cle("rotation", 1, 25, 0, 0)]),
        (m_jambe_g, [cle("rotation", 0, -25, 0, 0), cle("rotation", 0.5, 25, 0, 0), cle("rotation", 1, -25, 0, 0)]),
    ]),
    # saisi : soulevé, bras et jambes qui pendent et se débattent
    anim("saisi", 0.5, "once", [
        (m_racine, [cle("position", 0, 0, 0, 0), cle("position", 0.15, 0, 6, -2), cle("position", 0.5, 0, 6, -2)]),
        (m_bras_d, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.15, 0, 0, 40), cle("rotation", 0.3, 0, 0, 20), cle("rotation", 0.5, 0, 0, 40)]),
        (m_bras_g, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.15, 0, 0, -40), cle("rotation", 0.3, 0, 0, -20), cle("rotation", 0.5, 0, 0, -40)]),
        (m_jambe_d, [cle("rotation", 0.15, 20, 0, 0), cle("rotation", 0.3, -20, 0, 0), cle("rotation", 0.5, 20, 0, 0)]),
        (m_jambe_g, [cle("rotation", 0.15, -20, 0, 0), cle("rotation", 0.3, 20, 0, 0), cle("rotation", 0.5, -20, 0, 0)]),
    ]),
    # plaque : écrasé à plat sur le dos, puis se relève
    anim("plaque", 1.2, "once", [
        (m_racine, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.15, -90, 0, 0), cle("rotation", 0.8, -90, 0, 0), cle("rotation", 1.2, 0, 0, 0, "catmullrom"),
                    ]),
        (m_tete, [cle("rotation", 0.15, 0, 0, 0), cle("rotation", 0.25, -20, 0, 0), cle("rotation", 0.4, 0, 0, 0)]),
    ]),
    # souleve : décolle, bras en l'air
    anim("souleve", 0.8, "once", [
        (m_racine, [cle("position", 0, 0, 0, 0), cle("position", 0.3, 0, 10, 0, "catmullrom"), cle("position", 0.8, 0, 0, 0, "catmullrom")]),
        (m_bras_d, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.3, 0, 0, 150), cle("rotation", 0.8, 0, 0, 0)]),
        (m_bras_g, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.3, 0, 0, -150), cle("rotation", 0.8, 0, 0, 0)]),
    ]),
    # projete : part en arrière en tournant, retombe
    anim("projete", 1.0, "once", [
        (m_racine, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.5, 300, 0, 0), cle("rotation", 0.7, 360, 0, 0), cle("rotation", 1.0, 360, 0, 0)]),
        (m_corps, [cle("position", 0, 0, 0, 0), cle("position", 0.4, 0, 8, 0, "catmullrom"), cle("position", 0.7, 0, 0, 0)]),
    ]),
    # repousse : recule, buste en arrière
    anim("repousse", 0.5, "once", [
        (m_corps, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.1, 25, 0, 0), cle("rotation", 0.5, 0, 0, 0)]),
        (m_racine, [cle("position", 0, 0, 0, 0), cle("position", 0.1, 0, 0, 3), cle("position", 0.5, 0, 0, 0)]),
    ]),
    # etourdi : la tête tourne, le buste vacille
    anim("etourdi", 1.5, "once", [
        (m_tete, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.375, 0, 0, 20, "catmullrom"), cle("rotation", 0.75, 0, 0, -20, "catmullrom"),
                  cle("rotation", 1.125, 0, 0, 20, "catmullrom"), cle("rotation", 1.5, 0, 0, 0, "catmullrom")]),
        (m_corps, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.5, 0, 0, 8, "catmullrom"), cle("rotation", 1.0, 0, 0, -8, "catmullrom"), cle("rotation", 1.5, 0, 0, 0)]),
    ]),
    # resiste : trop lourd pour être attrapé, il se cale et tremble
    anim("resiste", 0.6, "once", [
        (m_corps, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.1, -10, 0, 3), cle("rotation", 0.2, -10, 0, -3), cle("rotation", 0.3, -10, 0, 3),
                   cle("rotation", 0.4, -10, 0, -3), cle("rotation", 0.6, 0, 0, 0)]),
        (m_jambe_d, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.1, 0, 0, 10), cle("rotation", 0.6, 0, 0, 0)]),
        (m_jambe_g, [cle("rotation", 0, 0, 0, 0), cle("rotation", 0.1, 0, 0, -10), cle("rotation", 0.6, 0, 0, 0)]),
    ]),
]
ecrire("mannequin_reactions", pm, [m_racine], T, anims_mannequin)
