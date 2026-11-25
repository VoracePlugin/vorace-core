# Génère le modèle des mouvements du pantin permanent (projet Blockbench) :
#   python3 outils/mouvements-pantin.py src/main/resources/exemples/modeles
# pantin_mouvements.bbmodel : le squelette du joueur (copie de joueur.bbmodel, mêmes os) avec les mouvements que le pantin
# permanent joue de lui-même : repos, marche, course, recul, pas de côté, accroupi, saut, chute, nage, échelle, assis,
# dormir, vol ; et les gestes joués par-dessus (bras qui frappe, manger, viser à l'arc ou à l'arbalète, bouclier, trident,
# longue-vue, coup reçu). Tout modèle au même squelette (les modèles des classes) les emprunte par le nom des os.
#
# Conventions de Blockbench (vérifiées sur le code de Vorace, Animateur) : le modèle regarde vers -z ; rotation x négative =
# bras ou jambe vers l'avant ; genou plié = x positif sur le bas de la jambe ; coude plié = x négatif sur le bas du bras ;
# torse penché en avant = x positif ; bras droit écarté du corps = z positif (gauche : z négatif) ; position y vers le haut.
# Ce sont des bases à retoucher dans Blockbench : les valeurs viennent du modèle du joueur du jeu, à l'œil.
import copy, json, os, random, sys, uuid

random.seed(23)
DOSSIER = sys.argv[1]


def uid():
    return str(uuid.UUID(int=random.getrandbits(128)))


SOURCE = json.load(open(os.path.join(DOSSIER, "joueur.bbmodel"), encoding="utf-8"))
OS = {}


def lister(o):
    for x in o:
        if isinstance(x, dict):
            OS[x["name"]] = x["uuid"]
            lister(x.get("children", []))


lister(SOURCE["outliner"])

D, G = "droit", "gauche"


def os_(nom):
    for n in (nom, "peau_" + nom):
        if n in OS:
            return n
    raise KeyError(nom)


def cle(canal, t, v, interp):
    x, y, z = v
    return {"channel": canal, "data_points": [{"x": x, "y": y, "z": z}], "uuid": uid(), "time": round(t, 4), "color": -1,
            "interpolation": interp}


def animation(nom, duree, boucle, pistes, interp="catmullrom"):
    """pistes : {os: {"rotation": [(t, (x, y, z)), ...], "position": [...]}} ; une valeur seule = constante."""
    anim = {}
    for o, canaux in pistes.items():
        n = os_(o)
        cles = []
        for canal, valeurs in canaux.items():
            if isinstance(valeurs, tuple):
                valeurs = [(0, valeurs), (duree, valeurs)] if boucle == "loop" else [(0, valeurs)]
            for t, v in valeurs:
                cles.append(cle(canal, t, v, interp))
        anim[OS[n]] = {"name": n, "type": "bone", "keyframes": cles}
    return {"uuid": uid(), "name": nom, "loop": boucle, "override": False, "length": duree, "snapping": 20, "selected": False,
            "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": anim}


def rx(*cles):
    """Rotation autour de x seulement : rx((t, x), ...)."""
    return [(t, (x, 0, 0)) for t, x in cles]


def miroir(cles):
    """Même piste pour l'autre côté : y et z changent de signe."""
    return [(t, (x, -y, -z)) for t, (x, y, z) in cles]


A = []

# ------------------------------------------------------------------ mouvements (complets)

A.append(animation("repos", 3.2, "loop", {
    "corps_haut": {"rotation": rx((0, 0), (1.6, 1.5), (3.2, 0))},
    "bras_droit_haut": {"rotation": [(0, (0, 0, 3)), (1.6, (1, 0, 5)), (3.2, (0, 0, 3))]},
    "bras_gauche_haut": {"rotation": [(0, (0, 0, -3)), (1.6, (1, 0, -5)), (3.2, (0, 0, -3))]},
    "bras_droit_bas": {"rotation": (-4, 0, 0)},
    "bras_gauche_bas": {"rotation": (-4, 0, 0)},
    "tete": {"rotation": rx((0, 0), (1.6, -1), (3.2, 0))},
}))

A.append(animation("marche", 1.0, "loop", {
    "jambe_droite_haut": {"rotation": rx((0, -28), (0.25, 0), (0.5, 28), (0.75, 0), (1.0, -28))},
    "jambe_gauche_haut": {"rotation": rx((0, 28), (0.25, 0), (0.5, -28), (0.75, 0), (1.0, 28))},
    "jambe_droite_bas": {"rotation": rx((0, 4), (0.25, 4), (0.5, 8), (0.75, 38), (1.0, 4))},
    "jambe_gauche_bas": {"rotation": rx((0, 8), (0.25, 38), (0.5, 4), (0.75, 4), (1.0, 8))},
    "bras_droit_haut": {"rotation": [(0, (22, 0, 3)), (0.5, (-22, 0, 3)), (1.0, (22, 0, 3))]},
    "bras_gauche_haut": {"rotation": [(0, (-22, 0, -3)), (0.5, (22, 0, -3)), (1.0, (-22, 0, -3))]},
    "bras_droit_bas": {"rotation": rx((0, -6), (0.5, -20), (1.0, -6))},
    "bras_gauche_bas": {"rotation": rx((0, -20), (0.5, -6), (1.0, -20))},
    "corps_bas": {"rotation": [(0, (0, 4, 0)), (0.5, (0, -4, 0)), (1.0, (0, 4, 0))]},
    "racine": {"position": [(0, (0, -0.4, 0)), (0.25, (0, 0.3, 0)), (0.5, (0, -0.4, 0)), (0.75, (0, 0.3, 0)), (1.0, (0, -0.4, 0))]},
}))

A.append(animation("course", 0.6, "loop", {
    "jambe_droite_haut": {"rotation": rx((0, -45), (0.15, 0), (0.3, 40), (0.45, 0), (0.6, -45))},
    "jambe_gauche_haut": {"rotation": rx((0, 40), (0.15, 0), (0.3, -45), (0.45, 0), (0.6, 40))},
    "jambe_droite_bas": {"rotation": rx((0, 10), (0.15, 15), (0.3, 25), (0.45, 80), (0.6, 10))},
    "jambe_gauche_bas": {"rotation": rx((0, 25), (0.15, 80), (0.3, 10), (0.45, 15), (0.6, 25))},
    "bras_droit_haut": {"rotation": [(0, (40, 0, 5)), (0.3, (-45, 0, 5)), (0.6, (40, 0, 5))]},
    "bras_gauche_haut": {"rotation": [(0, (-45, 0, -5)), (0.3, (40, 0, -5)), (0.6, (-45, 0, -5))]},
    "bras_droit_bas": {"rotation": rx((0, -60), (0.3, -85), (0.6, -60))},
    "bras_gauche_bas": {"rotation": rx((0, -85), (0.3, -60), (0.6, -85))},
    "corps_bas": {"rotation": (10, 0, 0)},
    "tete": {"rotation": (-10, 0, 0)},
    "racine": {"position": [(0, (0, -0.6, 0)), (0.15, (0, 0.6, 0)), (0.3, (0, -0.6, 0)), (0.45, (0, 0.6, 0)), (0.6, (0, -0.6, 0))]},
}))

A.append(animation("recul", 1.2, "loop", {
    "jambe_droite_haut": {"rotation": rx((0, 18), (0.3, 0), (0.6, -18), (0.9, 0), (1.2, 18))},
    "jambe_gauche_haut": {"rotation": rx((0, -18), (0.3, 0), (0.6, 18), (0.9, 0), (1.2, -18))},
    "jambe_droite_bas": {"rotation": rx((0, 4), (0.3, 28), (0.6, 4), (0.9, 4), (1.2, 4))},
    "jambe_gauche_bas": {"rotation": rx((0, 4), (0.3, 4), (0.6, 4), (0.9, 28), (1.2, 4))},
    "bras_droit_haut": {"rotation": [(0, (-12, 0, 4)), (0.6, (12, 0, 4)), (1.2, (-12, 0, 4))]},
    "bras_gauche_haut": {"rotation": [(0, (12, 0, -4)), (0.6, (-12, 0, -4)), (1.2, (12, 0, -4))]},
    "corps_bas": {"rotation": (-3, 0, 0)},
}))

A.append(animation("pas_droite", 0.8, "loop", {
    "jambe_droite_haut": {"rotation": [(0, (0, 0, 0)), (0.2, (-6, 0, 14)), (0.4, (0, 0, 0)), (0.8, (0, 0, 0))]},
    "jambe_droite_bas": {"rotation": rx((0, 0), (0.2, 20), (0.4, 0), (0.8, 0))},
    "jambe_gauche_haut": {"rotation": [(0, (0, 0, 0)), (0.4, (0, 0, 0)), (0.6, (-6, 0, 10)), (0.8, (0, 0, 0))]},
    "jambe_gauche_bas": {"rotation": rx((0, 0), (0.4, 0), (0.6, 20), (0.8, 0))},
    "bras_droit_haut": {"rotation": [(0, (0, 0, 4)), (0.2, (0, 0, 9)), (0.4, (0, 0, 4)), (0.8, (0, 0, 4))]},
    "bras_gauche_haut": {"rotation": [(0, (0, 0, -4)), (0.4, (0, 0, -4)), (0.6, (0, 0, -8)), (0.8, (0, 0, -4))]},
}))

A.append(animation("pas_gauche", 0.8, "loop", {
    "jambe_gauche_haut": {"rotation": [(0, (0, 0, 0)), (0.2, (-6, 0, -14)), (0.4, (0, 0, 0)), (0.8, (0, 0, 0))]},
    "jambe_gauche_bas": {"rotation": rx((0, 0), (0.2, 20), (0.4, 0), (0.8, 0))},
    "jambe_droite_haut": {"rotation": [(0, (0, 0, 0)), (0.4, (0, 0, 0)), (0.6, (-6, 0, -10)), (0.8, (0, 0, 0))]},
    "jambe_droite_bas": {"rotation": rx((0, 0), (0.4, 0), (0.6, 20), (0.8, 0))},
    "bras_gauche_haut": {"rotation": [(0, (0, 0, -4)), (0.2, (0, 0, -9)), (0.4, (0, 0, -4)), (0.8, (0, 0, -4))]},
    "bras_droit_haut": {"rotation": [(0, (0, 0, 4)), (0.4, (0, 0, 4)), (0.6, (0, 0, 8)), (0.8, (0, 0, 4))]},
}))

ACCROUPI = {
    "corps_bas": {"rotation": (28, 0, 0)},
    "tete": {"rotation": (-22, 0, 0)},
    "bras_droit_haut": {"rotation": (15, 0, 4)},
    "bras_gauche_haut": {"rotation": (15, 0, -4)},
    "bras_droit_bas": {"rotation": (-10, 0, 0)},
    "bras_gauche_bas": {"rotation": (-10, 0, 0)},
    "jambe_droite_haut": {"rotation": (-35, 0, 0)},
    "jambe_gauche_haut": {"rotation": (-35, 0, 0)},
    "jambe_droite_bas": {"rotation": (60, 0, 0)},
    "jambe_gauche_bas": {"rotation": (60, 0, 0)},
    "racine": {"position": (0, -1.7, 0)},
}
p = copy.deepcopy(ACCROUPI)
p["corps_haut"] = {"rotation": rx((0, 0), (1.5, 2), (3.0, 0))}
A.append(animation("accroupi", 3.0, "loop", p))

p = copy.deepcopy(ACCROUPI)
p["jambe_droite_haut"] = {"rotation": rx((0, -45), (0.6, -25), (1.2, -45))}
p["jambe_gauche_haut"] = {"rotation": rx((0, -25), (0.6, -45), (1.2, -25))}
p["jambe_droite_bas"] = {"rotation": rx((0, 60), (0.3, 75), (0.6, 60), (1.2, 60))}
p["jambe_gauche_bas"] = {"rotation": rx((0, 60), (0.6, 60), (0.9, 75), (1.2, 60))}
p["bras_droit_haut"] = {"rotation": [(0, (23, 0, 4)), (0.6, (7, 0, 4)), (1.2, (23, 0, 4))]}
p["bras_gauche_haut"] = {"rotation": [(0, (7, 0, -4)), (0.6, (23, 0, -4)), (1.2, (7, 0, -4))]}
A.append(animation("accroupi_marche", 1.2, "loop", p))

A.append(animation("saut", 0.25, "hold", {
    "jambe_droite_haut": {"rotation": rx((0, 0), (0.25, -30))},
    "jambe_droite_bas": {"rotation": rx((0, 0), (0.25, 45))},
    "jambe_gauche_haut": {"rotation": rx((0, 0), (0.25, 10))},
    "jambe_gauche_bas": {"rotation": rx((0, 0), (0.25, 20))},
    "bras_droit_haut": {"rotation": [(0, (0, 0, 0)), (0.25, (-25, 0, 25))]},
    "bras_gauche_haut": {"rotation": [(0, (0, 0, 0)), (0.25, (-25, 0, -25))]},
    "bras_droit_bas": {"rotation": rx((0, 0), (0.25, -20))},
    "bras_gauche_bas": {"rotation": rx((0, 0), (0.25, -20))},
    "corps_bas": {"rotation": rx((0, 0), (0.25, 5))},
}))

A.append(animation("chute", 0.8, "loop", {
    "bras_droit_haut": {"rotation": [(0, (-10, 0, 45)), (0.4, (-10, 0, 55)), (0.8, (-10, 0, 45))]},
    "bras_gauche_haut": {"rotation": [(0, (-10, 0, -45)), (0.4, (-10, 0, -55)), (0.8, (-10, 0, -45))]},
    "jambe_droite_haut": {"rotation": rx((0, -10), (0.4, 5), (0.8, -10))},
    "jambe_gauche_haut": {"rotation": rx((0, 5), (0.4, -10), (0.8, 5))},
    "jambe_droite_bas": {"rotation": rx((0, 15), (0.4, 8), (0.8, 15))},
    "jambe_gauche_bas": {"rotation": rx((0, 8), (0.4, 15), (0.8, 8))},
}))

A.append(animation("nage", 1.4, "loop", {
    "racine": {"rotation": (90, 0, 0), "position": (0, 3, 12)},
    "tete": {"rotation": (-60, 0, 0)},
    "bras_droit_haut": {"rotation": [(0, (-170, 0, 5)), (0.5, (-110, 0, 60)), (0.9, (-70, 0, 20)), (1.4, (-170, 0, 5))]},
    "bras_gauche_haut": {"rotation": [(0, (-170, 0, -5)), (0.5, (-110, 0, -60)), (0.9, (-70, 0, -20)), (1.4, (-170, 0, -5))]},
    "jambe_droite_haut": {"rotation": rx((0, -12), (0.35, 12), (0.7, -12), (1.05, 12), (1.4, -12))},
    "jambe_gauche_haut": {"rotation": rx((0, 12), (0.35, -12), (0.7, 12), (1.05, -12), (1.4, 12))},
    "jambe_droite_bas": {"rotation": (10, 0, 0)},
    "jambe_gauche_bas": {"rotation": (10, 0, 0)},
}))

A.append(animation("echelle", 0.8, "loop", {
    "bras_droit_haut": {"rotation": rx((0, -150), (0.4, -110), (0.8, -150))},
    "bras_gauche_haut": {"rotation": rx((0, -110), (0.4, -150), (0.8, -110))},
    "bras_droit_bas": {"rotation": (-20, 0, 0)},
    "bras_gauche_bas": {"rotation": (-20, 0, 0)},
    "jambe_droite_haut": {"rotation": rx((0, -50), (0.4, -10), (0.8, -50))},
    "jambe_gauche_haut": {"rotation": rx((0, -10), (0.4, -50), (0.8, -10))},
    "jambe_droite_bas": {"rotation": rx((0, 70), (0.4, 20), (0.8, 70))},
    "jambe_gauche_bas": {"rotation": rx((0, 20), (0.4, 70), (0.8, 20))},
}))

A.append(animation("assis", 3.0, "loop", {
    "jambe_droite_haut": {"rotation": (-80, 15, 0)},
    "jambe_gauche_haut": {"rotation": (-80, -15, 0)},
    "bras_droit_haut": {"rotation": (-35, 0, 3)},
    "bras_gauche_haut": {"rotation": (-35, 0, -3)},
    "corps_haut": {"rotation": rx((0, 0), (1.5, 1.5), (3.0, 0))},
}))

A.append(animation("dormir", 4.0, "loop", {
    "racine": {"rotation": (-90, 0, 0), "position": (0, 2, -12)},
    "corps_haut": {"rotation": rx((0, 0), (2.0, 1), (4.0, 0))},
}))

A.append(animation("vol", 1.0, "loop", {
    "racine": {"rotation": (80, 0, 0), "position": (0, 3, 12)},
    "tete": {"rotation": (-60, 0, 0)},
    "bras_droit_haut": {"rotation": [(0, (20, 0, 10)), (0.5, (20, 0, 14)), (1.0, (20, 0, 10))]},
    "bras_gauche_haut": {"rotation": [(0, (20, 0, -10)), (0.5, (20, 0, -14)), (1.0, (20, 0, -10))]},
}))

# ------------------------------------------------------------------ gestes (par-dessus les mouvements : seuls ces os bougent)

GESTE_MAIN = {
    "bras_droit_haut": {"rotation": [(0, (-10, 0, 0)), (0.1, (-110, 0, 12)), (0.3, (0, 0, 0))]},
    "bras_droit_bas": {"rotation": rx((0, -10), (0.1, -30), (0.3, 0))},
    "corps_haut": {"rotation": [(0, (0, 0, 0)), (0.1, (0, -8, 0)), (0.3, (0, 0, 0))]},
}
A.append(animation("geste_main", 0.3, "once", GESTE_MAIN))
A.append(animation("geste_main_gauche", 0.3, "once", {
    "bras_gauche_haut": {"rotation": miroir(GESTE_MAIN["bras_droit_haut"]["rotation"])},
    "bras_gauche_bas": {"rotation": GESTE_MAIN["bras_droit_bas"]["rotation"]},
    "corps_haut": {"rotation": miroir(GESTE_MAIN["corps_haut"]["rotation"])},
}))

A.append(animation("manger", 0.5, "loop", {
    "bras_droit_haut": {"rotation": [(0, (-70, -25, 0)), (0.25, (-62, -25, 0)), (0.5, (-70, -25, 0))]},
    "bras_droit_bas": {"rotation": (-40, 0, 0)},
    "tete": {"rotation": rx((0, 8), (0.25, 12), (0.5, 8))},
}))

A.append(animation("viser_arc", 1.0, "loop", {
    "bras_gauche_haut": {"rotation": (-90, 10, 0)},
    "bras_gauche_bas": {"rotation": (0, 0, 0)},
    "bras_droit_haut": {"rotation": (-90, -30, 0)},
    "bras_droit_bas": {"rotation": (-40, 0, 0)},
}))

A.append(animation("viser_arbalete", 1.0, "loop", {
    "bras_droit_haut": {"rotation": (-85, -20, 0)},
    "bras_droit_bas": {"rotation": (-10, 0, 0)},
    "bras_gauche_haut": {"rotation": (-80, 35, 0)},
    "bras_gauche_bas": {"rotation": (-25, 0, 0)},
}))

A.append(animation("garde_bouclier", 1.0, "loop", {
    "bras_gauche_haut": {"rotation": (-50, 30, 0)},
    "bras_gauche_bas": {"rotation": (-40, 0, 0)},
}))

A.append(animation("viser_trident", 1.0, "loop", {
    "bras_droit_haut": {"rotation": (-160, 0, 10)},
    "bras_droit_bas": {"rotation": (0, 0, 0)},
}))

A.append(animation("longue_vue", 1.0, "loop", {
    "bras_droit_haut": {"rotation": (-75, -30, 0)},
    "bras_droit_bas": {"rotation": (-60, 0, 0)},
}))

A.append(animation("degat", 0.35, "once", {
    "corps_haut": {"rotation": rx((0, 0), (0.08, -10), (0.35, 0))},
    "tete": {"rotation": rx((0, 0), (0.08, -12), (0.35, 0))},
    "bras_droit_haut": {"rotation": [(0, (0, 0, 0)), (0.08, (0, 0, 10)), (0.35, (0, 0, 0))]},
    "bras_gauche_haut": {"rotation": [(0, (0, 0, 0)), (0.08, (0, 0, -10)), (0.35, (0, 0, 0))]},
}))

projet = copy.deepcopy(SOURCE)
projet["name"] = "pantin_mouvements"
projet["animations"] = A
with open(os.path.join(DOSSIER, "pantin_mouvements.bbmodel"), "w", encoding="utf-8") as f:
    json.dump(projet, f, ensure_ascii=False, indent=1)
print(len(A), "animations :", ", ".join(a["name"] for a in A))
