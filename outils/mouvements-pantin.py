# Génère le modèle des mouvements du pantin des joueurs (projet Blockbench) :
#   python3 outils/mouvements-pantin.py src/main/resources/exemples/modeles
# pantin_mouvements.bbmodel : le squelette du joueur (copie de joueur.bbmodel, mêmes os) avec tout ce que le pantin permanent
# joue de lui-même. Tout modèle au même squelette (les modèles des classes) emprunte ces animations par le nom des os.
#
# Chaque animation reprend celle du jeu (formules de HumanoidModel et AnimationUtils de Minecraft 1.21.4 : amplitude des
# jambes et des bras, poses des bras selon l'objet utilisé, coup de bras, nage, assis...) avec ce que le jeu n'a pas : des
# genoux et des coudes, un buste qui tourne, les pieds posés au sol.
#
#   mouvements (corps entier, en boucle) : repos, marche, course, recul, pas_droite, pas_gauche, accroupi, accroupi_marche,
#       saut, chute, nage, ramper, echelle, assis, dormir, vol, vrille
#   gestes (par-dessus les mouvements : seuls leurs os bougent) : main droite, et « _gauche » pour la main gauche
#       tenir, tenir_deux, geste_main, manger, boire, viser_arc, viser_arc_tenu, tir_arc, charger_arbalete, viser_arbalete, garde_bouclier,
#       viser_trident, longue_vue, cor, brosse ; et degat (coup reçu)
#
# Repère de Blockbench (x à droite du personnage, y en haut, il regarde vers -z) ; une clé de rotation (x, y, z) vaut la
# rotation Rz(z) · Ry(-y) · Rx(-x) : x négatif = bras ou jambe vers l'avant, genou plié = x positif sur le bas de la jambe,
# coude plié = x négatif sur le bas du bras, buste penché en avant = x positif, bras droit écarté = z positif.
# Les mains sont posées par cinématique inverse (bras de 4 + 6 pixels) : on donne où va la main, le script trouve les angles.
import copy, json, math, os, random, sys, uuid

random.seed(23)
DOSSIER = sys.argv[1]
PAS = 0.05          # une clé par tic du jeu : ce que le serveur envoie


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


def os_(nom):
    for n in (nom, "peau_" + nom):
        if n in OS:
            return n
    raise KeyError(nom)


# ------------------------------------------------------------------ petite algèbre (matrices 3 × 3, vecteurs)

def rx(a):
    c, s = math.cos(a), math.sin(a)
    return [[1, 0, 0], [0, c, -s], [0, s, c]]


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, 0, s], [0, 1, 0], [-s, 0, c]]


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def mm(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def mv(a, v):
    return tuple(sum(a[i][k] * v[k] for k in range(3)) for i in range(3))


def tr(a):
    return [[a[j][i] for j in range(3)] for i in range(3)]


def add(a, b): return tuple(x + y for x, y in zip(a, b))
def sub(a, b): return tuple(x - y for x, y in zip(a, b))
def mul(a, k): return tuple(x * k for x in a)
def dot(a, b): return sum(x * y for x, y in zip(a, b))
def norme(a): return math.sqrt(dot(a, a))
def unite(a):
    n = norme(a)
    return mul(a, 1 / n) if n > 1e-9 else (0.0, -1.0, 0.0)
def croix(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def mix(a, b, u): return tuple(x + (y - x) * u for x, y in zip(a, b))


def mat(e):
    """Matrice d'une clé de rotation de Blockbench (degrés)."""
    x, y, z = (math.radians(v) for v in e)
    return mm(rz(z), mm(ry(-y), rx(-x)))


def euler(m):
    """Clé de rotation de Blockbench (degrés) d'une matrice."""
    b = math.asin(max(-1.0, min(1.0, -m[2][0])))
    a = math.atan2(m[2][1], m[2][2])
    c = math.atan2(m[1][0], m[0][0])
    return (-math.degrees(a), -math.degrees(b), math.degrees(c))


def proche(e, avant):
    """La même rotation, écrite au plus près de la clé d'avant (sinon le bras prend le long chemin entre deux clés)."""
    if avant is None:
        return e
    autre = (e[0] + 180, 180 - e[1], e[2] + 180)
    mieux = None
    for cand in (e, autre):
        c = tuple(v + 360 * round((p - v) / 360) for v, p in zip(cand, avant))
        d = sum(abs(v - p) for v, p in zip(c, avant))
        if mieux is None or d < mieux[0]:
            mieux = (d, c)
    return mieux[1]


def doux(u):
    u = max(0.0, min(1.0, u))
    return u * u * (3 - 2 * u)


def courbe(points, t):
    """
    Courbe lisse (Catmull-Rom) par des points datés [(t, valeur), ...] : la main suit un arc au lieu d'aller en ligne droite
    d'une clé à l'autre. Valeur : nombre ou tuple.
    """
    if t <= points[0][0]:
        return points[0][1]
    if t >= points[-1][0]:
        return points[-1][1]
    i = max(k for k in range(len(points) - 1) if points[k][0] <= t)
    p0, p1, p2, p3 = (points[max(0, min(len(points) - 1, k))][1] for k in (i - 1, i, i + 1, i + 2))
    u = (t - points[i][0]) / (points[i + 1][0] - points[i][0])

    def un(a, b, c, e):
        return 0.5 * (2 * b + (c - a) * u + (2 * a - 5 * b + 4 * c - e) * u * u + (3 * b - a - 3 * c + e) * u ** 3)
    if isinstance(p1, tuple):
        return tuple(un(a, b, c, e) for a, b, c, e in zip(p0, p1, p2, p3))
    return un(p0, p1, p2, p3)


def entre(t, t0, t1):
    return doux((t - t0) / (t1 - t0)) if t1 > t0 else (1.0 if t >= t1 else 0.0)


# ------------------------------------------------------------------ squelette (pivots de joueur.bbmodel)

BASSIN, BUSTE, COU = (0, 12, 0), (0, 18, 0), (0, 24, 0)
EPAULE = {"droit": (6, 22, 0), "gauche": (-6, 22, 0)}
BRAS_HAUT, BRAS_BAS = 4.0, 6.0      # épaule → coude → poing
CUISSE, TIBIA = 6.0, 6.0


def bras(cote, main, coude, bas=(0, 0, 0), haut=(0, 0, 0)):
    """
    Clés du bras (haut, bas) pour que le poing soit en `main` (repère du modèle, racine immobile), le coude tiré vers
    `coude`. `bas` et `haut` : rotations de corps_bas et corps_haut au même instant (le bras en est l'enfant).
    """
    rb, rh = mat(bas), mat(haut)
    parent = mm(rb, rh)
    s0 = sub(EPAULE[cote], BUSTE)
    epaule = add(BASSIN, mv(rb, add(sub(BUSTE, BASSIN), mv(rh, s0))))
    v = sub(main, epaule)
    d = max(abs(BRAS_HAUT - BRAS_BAS) + 0.05, min(BRAS_HAUT + BRAS_BAS - 0.02, norme(v)))
    u = unite(v)
    cosa = (BRAS_HAUT ** 2 + d * d - BRAS_BAS ** 2) / (2 * BRAS_HAUT * d)
    sina = math.sqrt(max(0.0, 1 - cosa * cosa))
    w = sub(coude, mul(u, dot(coude, u)))
    w = unite(w) if norme(w) > 1e-6 else unite(croix(u, (1, 0, 0)))
    a = unite(add(mul(u, cosa), mul(w, sina)))                    # épaule → coude
    cible = add(epaule, mul(u, d))
    e = add(epaule, mul(a, BRAS_HAUT))
    f0 = unite(sub(cible, e))                                     # coude → poing
    f = sub(f0, mul(a, dot(f0, a)))
    f = unite(f) if norme(f) > 1e-6 else unite(croix(a, (1, 0, 0)))
    ey, ez = mul(a, -1), mul(f, -1)
    ex = croix(ey, ez)
    monde = [[ex[i], ey[i], ez[i]] for i in range(3)]
    pli = math.degrees(math.acos(max(-1.0, min(1.0, dot(a, f0)))))
    return euler(mm(tr(parent), monde)), (-pli, 0, 0)


def pied(hanche, genou):
    """Hauteur du bassin au-dessus du pied (jambe dans le plan de la marche), avancée du pied."""
    a, k = math.radians(-hanche), math.radians(genou)
    return CUISSE * math.cos(a) + TIBIA * math.cos(a - k), CUISSE * math.sin(a) + TIBIA * math.sin(a - k)


# ------------------------------------------------------------------ écriture des animations

class Lin(list):
    """Piste échantillonnée (une clé par tic) : interpolation linéaire."""


def ech(duree, f, boucle=True):
    """Échantillonne f(t) → (x, y, z) à chaque tic."""
    n = max(1, round(duree / (PAS if duree <= 1.5 else 0.2)))    # mouvements lents : une clé tous les 4 tics suffit
    return Lin((round(i * duree / n, 4), f(i * duree / n)) for i in range(n + 1))


def cle(canal, t, v, interp):
    x, y, z = (round(c, 3) + 0.0 for c in v)
    return {"channel": canal, "data_points": [{"x": x, "y": y, "z": z}], "uuid": uid(), "time": round(t, 4), "color": -1,
            "interpolation": interp}


def animation(nom, duree, boucle, pistes):
    """pistes : {os: {"rotation": ..., "position": ...}} ; une valeur seule = constante ; Lin = linéaire ; liste = courbe."""
    anim = {}
    for o, canaux in pistes.items():
        n = os_(o)
        cles = []
        for canal, valeurs in canaux.items():
            interp = "linear" if isinstance(valeurs, Lin) else "catmullrom"
            if isinstance(valeurs, tuple):
                valeurs = [(0, valeurs), (duree, valeurs)] if boucle == "loop" else [(0, valeurs)]
            avant = None
            for t, v in valeurs:
                if canal == "rotation":
                    v = avant = proche(v, avant)
                cles.append(cle(canal, t, v, interp))
        anim[OS[n]] = {"name": n, "type": "bone", "keyframes": cles}
    return {"uuid": uid(), "name": nom, "loop": boucle, "override": False, "length": duree, "snapping": 20, "selected": False,
            "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": anim}


def miroir(pistes):
    """Les mêmes pistes pour l'autre main : os droits et gauches échangés, y et z des rotations (x des positions) opposés."""
    def autre(o):
        return o.replace("droite", "\0").replace("gauche", "droite").replace("\0", "gauche") if "jambe" in o else \
            o.replace("droit", "\0").replace("gauche", "droit").replace("\0", "gauche")

    def retourne(canal, v):
        return (v[0], -v[1], -v[2]) if canal == "rotation" else (-v[0], v[1], v[2])

    res = {}
    for o, canaux in pistes.items():
        res[autre(o)] = {}
        for canal, valeurs in canaux.items():
            if isinstance(valeurs, tuple):
                res[autre(o)][canal] = retourne(canal, valeurs)
            else:
                res[autre(o)][canal] = type(valeurs)((t, retourne(canal, v)) for t, v in valeurs)
    return res


A = []
PHI = lambda t, T: 2 * math.pi * t / T
pos = lambda x: max(0.0, x)


def sol(T, hd, kd, hg, kg, leve=lambda t: 0.0):
    """Hauteur de la racine pour que le pied le plus bas touche le sol (jambes hd/kd et hg/kg : hanche et genou en degrés)."""
    brut = ech(T, lambda t: (0, max(pied(hd(t), kd(t))[0], pied(hg(t), kg(t))[0]) - (CUISSE + TIBIA) + leve(t), 0))
    # le changement de pied d'appui fait un angle dans la courbe : adouci sur trois tics (la boucle se referme)
    n = len(brut) - 1
    ys = [brut[i][1][1] for i in range(n)]
    lisse = [0.25 * ys[(i - 1) % n] + 0.5 * ys[i] + 0.25 * ys[(i + 1) % n] for i in range(n)]
    monte = max(0.0, max(b - l for b, l in zip(ys, lisse)))       # jamais plus bas que le sol
    lisse = [l + monte for l in lisse]
    return Lin((brut[i][0], (0, lisse[i % n], 0)) for i in range(n + 1))


# ------------------------------------------------------------------ mouvements (corps entier)

# repos : le balancement des bras du jeu (bobModelPart : 0 à 5,7° d'écart, ±2,9° d'avant en arrière), une respiration
# (4 s) et le poids du corps qui passe lentement d'une jambe à l'autre (8 s) : le buste et la tête penchent à peine
T = 8.0
SOUFFLE = lambda t: math.sin(PHI(t, 4.0))
POIDS = lambda t: math.sin(PHI(t, 8.0))
A.append(animation("repos", T, "loop", {
    "corps_bas": {"rotation": ech(T, lambda t: (0, 1.2 * POIDS(t), 0.9 * POIDS(t)))},
    "corps_haut": {"rotation": ech(T, lambda t: (0.9 + 0.9 * SOUFFLE(t), 0, -0.5 * POIDS(t)))},
    "tete": {"rotation": ech(T, lambda t: (-0.7 - 0.7 * SOUFFLE(t), -1.2 * POIDS(t) + 1.5 * math.sin(PHI(t, 8.0) + 1.1), -0.4 * POIDS(t)))},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (2.9 * SOUFFLE(t), 0, 2.9 + 2.9 * math.cos(PHI(t, 4.0)) - 0.4 * POIDS(t)))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (-2.9 * SOUFFLE(t), 0, -2.9 - 2.9 * math.cos(PHI(t, 4.0)) - 0.4 * POIDS(t)))},
    "bras_droit_bas": {"rotation": ech(T, lambda t: (-7 - 1.5 * math.sin(PHI(t, 4.0) - 0.9), 0, 0))},
    "bras_gauche_bas": {"rotation": ech(T, lambda t: (-7 + 1.5 * math.sin(PHI(t, 4.0) - 0.9), 0, 0))},
    "jambe_droite_bas": {"rotation": ech(T, lambda t: (1.5 + 1.5 * POIDS(t), 0, 0))},
    "jambe_gauche_bas": {"rotation": ech(T, lambda t: (1.5 - 1.5 * POIDS(t), 0, 0))},
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (-0.75 - 0.75 * POIDS(t), 0, 0))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (-0.75 + 0.75 * POIDS(t), 0, 0))},
}))


def pas(nom, T, amp, genou0, genou1, bras_amp, coude0, coude1, penche, tete, vrille, vol=0.0, biais=0.0, ecart=3.0, roulis=0.0):
    """
    Cycle de marche ou de course vers l'avant. Jambes en opposition (hanche = biais ∓ amp · cos φ, comme le cos du jeu), le
    genou se plie quand la jambe revient vers l'avant ; bras opposés aux jambes, coude plus plié quand le bras est devant ;
    les épaules tournent à l'inverse du bassin (vrille ; Mo' Bends : ±20° en marche, ±37° en course) et le buste penche du
    côté de la jambe d'appui (roulis) ; la racine descend pour que le pied d'appui touche le sol (+ vol : la phase en l'air
    de la course).
    """
    hd = lambda t: biais - amp * math.cos(PHI(t, T))
    hg = lambda t: biais + amp * math.cos(PHI(t, T))
    kd = lambda t: genou0 + genou1 * pos(-math.sin(PHI(t, T))) ** 1.2
    kg = lambda t: genou0 + genou1 * pos(math.sin(PHI(t, T))) ** 1.2
    A.append(animation(nom, T, "loop", {
        "jambe_droite_haut": {"rotation": ech(T, lambda t: (hd(t), 0, 0))},
        "jambe_gauche_haut": {"rotation": ech(T, lambda t: (hg(t), 0, 0))},
        "jambe_droite_bas": {"rotation": ech(T, lambda t: (kd(t), 0, 0))},
        "jambe_gauche_bas": {"rotation": ech(T, lambda t: (kg(t), 0, 0))},
        "bras_droit_haut": {"rotation": ech(T, lambda t: (bras_amp * math.cos(PHI(t, T)) - penche * 0.5, 0, ecart))},
        "bras_gauche_haut": {"rotation": ech(T, lambda t: (-bras_amp * math.cos(PHI(t, T)) - penche * 0.5, 0, -ecart))},
        # l'avant-bras suit le bras avec un peu de retard (0,5 rad) : le coude se plie quand le bras arrive devant
        "bras_droit_bas": {"rotation": ech(T, lambda t: (-coude0 - coude1 * (1 - math.cos(PHI(t, T) - 0.5)) / 2, 0, 0))},
        "bras_gauche_bas": {"rotation": ech(T, lambda t: (-coude0 - coude1 * (1 + math.cos(PHI(t, T) - 0.5)) / 2, 0, 0))},
        "corps_bas": {"rotation": ech(T, lambda t: (penche, -vrille * 0.5 * math.cos(PHI(t, T)), -roulis * math.sin(PHI(t, T))))},
        "corps_haut": {"rotation": ech(T, lambda t: (0, vrille * 1.5 * math.cos(PHI(t, T)), 0))},
        "tete": {"rotation": ech(T, lambda t: (tete, -vrille * math.cos(PHI(t, T)), roulis * math.sin(PHI(t, T))))},
        "racine": {"position": sol(T, hd, kd, hg, kg, lambda t: vol * (0.5 + 0.5 * math.cos(2 * PHI(t, T))))},
    }))


# marche : le jeu balance les jambes de ±69° à 4,3 blocs par seconde (cos · 1,4 · 0,86) sans plier le genou ; avec un genou,
# ±40° couvrent presque la même foulée. Un cycle en 0,5 s à cette vitesse (Vorace règle la cadence sur la vitesse réelle).
pas("marche", 0.5, 40, 8, 52, 30, 14, 22, 4, -4, 11, roulis=1.5)
# course : 5,6 blocs par seconde, ±80° dans le jeu ; buste penché, coudes pliés, un temps en l'air entre deux appuis
pas("course", 0.42, 50, 14, 88, 46, 72, 16, 17, -15, 20, vol=1.6, biais=-8, ecart=5.0, roulis=4)

# recul : la jambe part vers l'arrière, genou plié quand elle recule
T = 0.5
hd = lambda t: 4 + 26 * math.cos(PHI(t, T))
hg = lambda t: 4 - 26 * math.cos(PHI(t, T))
kd = lambda t: 8 + 40 * pos(-math.sin(PHI(t, T))) ** 1.2
kg = lambda t: 8 + 40 * pos(math.sin(PHI(t, T))) ** 1.2
A.append(animation("recul", T, "loop", {
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (hd(t), 0, 0))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (hg(t), 0, 0))},
    "jambe_droite_bas": {"rotation": ech(T, lambda t: (kd(t), 0, 0))},
    "jambe_gauche_bas": {"rotation": ech(T, lambda t: (kg(t), 0, 0))},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (-14 * math.cos(PHI(t, T)) + 2, 0, 4))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (14 * math.cos(PHI(t, T)) + 2, 0, -4))},
    "bras_droit_bas": {"rotation": (-18, 0, 0)},
    "bras_gauche_bas": {"rotation": (-18, 0, 0)},
    "corps_bas": {"rotation": ech(T, lambda t: (-3, 2 * math.cos(PHI(t, T)), 0))},
    "corps_haut": {"rotation": ech(T, lambda t: (0, -4 * math.cos(PHI(t, T)), 0))},
    "tete": {"rotation": ech(T, lambda t: (3, 2 * math.cos(PHI(t, T)), 0))},
    "racine": {"position": sol(T, hd, kd, hg, kg)},
}))

# pas de côté vers la droite : la jambe droite s'écarte en se levant, se pose, la gauche la rejoint en se levant
T = 0.5
ouvre = lambda t: (1 - math.cos(PHI(t, T))) / 2
PAS_DROITE = {
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (-12 * pos(math.sin(PHI(t, T))), 0, 15 * ouvre(t)))},
    "jambe_droite_bas": {"rotation": ech(T, lambda t: (4 + 30 * pos(math.sin(PHI(t, T))), 0, 0))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (-12 * pos(-math.sin(PHI(t, T))), 0, -15 * ouvre(t)))},
    "jambe_gauche_bas": {"rotation": ech(T, lambda t: (4 + 30 * pos(-math.sin(PHI(t, T))), 0, 0))},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (-4, 0, 6 + 8 * ouvre(t)))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (-4, 0, -6 - 8 * ouvre(t)))},
    "bras_droit_bas": {"rotation": (-16, 0, 0)},
    "bras_gauche_bas": {"rotation": (-16, 0, 0)},
    "corps_bas": {"rotation": ech(T, lambda t: (2, 0, 2.5 * math.sin(PHI(t, T))))},
    "tete": {"rotation": ech(T, lambda t: (-2, 0, -2.5 * math.sin(PHI(t, T))))},
    "racine": {"position": ech(T, lambda t: (0, -(CUISSE + TIBIA) * (1 - math.cos(math.radians(15 * ouvre(t)))) - 0.15, 0))},
}
A.append(animation("pas_droite", T, "loop", PAS_DROITE))
A.append(animation("pas_gauche", T, "loop", miroir(PAS_DROITE)))

# accroupi : le jeu penche le buste de 28,6° (0,5 rad) et tient en 1,5 bloc ; ici les genoux plient pour la même hauteur
CUI, GEN, BUS = -50, 95, 30
H_ACC = pied(CUI, GEN)[0] - (CUISSE + TIBIA)
T = 3.0
ACCROUPI = {
    "racine": {"position": (0, H_ACC, 1.2)},
    "corps_bas": {"rotation": (BUS, 0, 0)},
    "corps_haut": {"rotation": ech(T, lambda t: (5 + 0.8 * math.sin(PHI(t, T)), 0, 0))},
    "tete": {"rotation": (-BUS - 5, 0, 0)},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (-22 + 1.5 * math.sin(PHI(t, T)), 0, 5))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (-22 - 1.5 * math.sin(PHI(t, T)), 0, -5))},
    "bras_droit_bas": {"rotation": (-28, 0, 0)},
    "bras_gauche_bas": {"rotation": (-28, 0, 0)},
    "jambe_droite_haut": {"rotation": (CUI, 0, 3)},
    "jambe_gauche_haut": {"rotation": (CUI, 0, -3)},
    "jambe_droite_bas": {"rotation": (GEN, 0, 0)},
    "jambe_gauche_bas": {"rotation": (GEN, 0, 0)},
}
A.append(animation("accroupi", T, "loop", ACCROUPI))

T = 0.8
hd = lambda t: CUI - 13 * math.cos(PHI(t, T))
hg = lambda t: CUI + 13 * math.cos(PHI(t, T))
kd = lambda t: GEN - 8 * math.cos(PHI(t, T)) + 16 * pos(-math.sin(PHI(t, T)))
kg = lambda t: GEN + 8 * math.cos(PHI(t, T)) + 16 * pos(math.sin(PHI(t, T)))
p = copy.deepcopy(ACCROUPI)
p.update({
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (hd(t), 0, 3))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (hg(t), 0, -3))},
    "jambe_droite_bas": {"rotation": ech(T, lambda t: (kd(t), 0, 0))},
    "jambe_gauche_bas": {"rotation": ech(T, lambda t: (kg(t), 0, 0))},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (-22 + 9 * math.cos(PHI(t, T)), 0, 5))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (-22 - 9 * math.cos(PHI(t, T)), 0, -5))},
    "corps_haut": {"rotation": ech(T, lambda t: (5, 4 * math.cos(PHI(t, T)), 0))},
    "tete": {"rotation": ech(T, lambda t: (-BUS - 5, -4 * math.cos(PHI(t, T)), 0))},
})
ys = sol(T, hd, kd, hg, kg)
p["racine"] = {"position": Lin((t, (0, v[1], 1.2)) for t, v in ys)}
A.append(animation("accroupi_marche", T, "loop", p))

# saut : la pose en l'air (tenue), une jambe remontée, les bras ouverts
A.append(animation("saut", 0.2, "hold", {
    "jambe_droite_haut": {"rotation": [(0, (-8, 0, 0)), (0.2, (-38, 0, 2))]},
    "jambe_droite_bas": {"rotation": [(0, (14, 0, 0)), (0.2, (58, 0, 0))]},
    "jambe_gauche_haut": {"rotation": [(0, (4, 0, 0)), (0.2, (10, 0, -2))]},
    "jambe_gauche_bas": {"rotation": [(0, (10, 0, 0)), (0.2, (26, 0, 0))]},
    "bras_droit_haut": {"rotation": [(0, (6, 0, 6)), (0.2, (22, 0, 20))]},
    "bras_gauche_haut": {"rotation": [(0, (-6, 0, -6)), (0.2, (-26, 0, -18))]},
    "bras_droit_bas": {"rotation": [(0, (-14, 0, 0)), (0.2, (-34, 0, 0))]},
    "bras_gauche_bas": {"rotation": [(0, (-14, 0, 0)), (0.2, (-40, 0, 0))]},
    "corps_bas": {"rotation": [(0, (2, 0, 0)), (0.2, (5, 0, 0))]},
    "tete": {"rotation": [(0, (-2, 0, 0)), (0.2, (-5, 0, 0))]},
}))

# chute : bras levés sur les côtés, jambes écartées qui battent un peu, le corps un peu basculé vers l'avant (Mo' Bends)
T = 0.6
A.append(animation("chute", T, "loop", {
    "racine": {"rotation": (10, 0, 0)},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (-12, 0, 68 + 7 * math.sin(PHI(t, T))))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (-12, 0, -68 + 7 * math.sin(PHI(t, T))))},
    "bras_droit_bas": {"rotation": ech(T, lambda t: (-26 - 6 * math.cos(PHI(t, T)), 0, 0))},
    "bras_gauche_bas": {"rotation": ech(T, lambda t: (-26 + 6 * math.cos(PHI(t, T)), 0, 0))},
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (-14 + 9 * math.sin(PHI(t, T)), 0, 11))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (-6 - 9 * math.sin(PHI(t, T)), 0, -11))},
    "jambe_droite_bas": {"rotation": ech(T, lambda t: (30 - 10 * math.sin(PHI(t, T)), 0, 0))},
    "jambe_gauche_bas": {"rotation": ech(T, lambda t: (22 + 10 * math.sin(PHI(t, T)), 0, 0))},
    "corps_bas": {"rotation": (4, 0, 0)},
    "tete": {"rotation": (-10, 0, 0)},
}))

# À plat ventre (nage, ramper, vol, vrille) : la racine bascule de 90° vers l'avant et recule de 28 pixels, pour que la
# tête soit à la place du joueur (ses yeux sont dans la tête du pantin, comme debout) ; Vorace penche ensuite le corps
# entier vers où il regarde.
A_PLAT = (90, 0, 0)


def brasse(t, T, signe):
    """Bras de la brasse du jeu : tendus devant, ils s'ouvrent et reviennent le long du corps, puis repassent sous le buste."""
    u = (t / T) % 1.0
    if u < 0.46:                                   # traction : de devant (172°) au flanc (24°), par le côté
        z, x = 172 - 148 * doux(u / 0.46), 0.0
    else:                                          # retour : le coude plie, la main repasse sous le buste
        v = (u - 0.46) / 0.54
        z, x = 24 + 148 * doux(v), -42 * math.sin(math.pi * v)
    return (x, 0, signe * z)


def coude_brasse(t, T):
    u = (t / T) % 1.0
    return (-6 - 20 * math.sin(math.pi * u / 0.46), 0, 0) if u < 0.46 else (-6 - 100 * math.sin(math.pi * (u - 0.46) / 0.54) ** 1.5, 0, 0)


T = 1.3   # le cycle de 26 du jeu
A.append(animation("nage", T, "loop", {
    "racine": {"rotation": A_PLAT, "position": (0, 5, 28)},
    "tete": {"rotation": (-52, 0, 0)},
    "corps_haut": {"rotation": ech(T, lambda t: (-4 + 3 * math.sin(PHI(t, T)), 0, 0))},
    "bras_droit_haut": {"rotation": ech(T, lambda t: brasse(t, T, 1))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: brasse(t, T, -1))},
    "bras_droit_bas": {"rotation": ech(T, lambda t: coude_brasse(t, T))},
    "bras_gauche_bas": {"rotation": ech(T, lambda t: coude_brasse(t, T))},
    # battement des jambes du jeu : ±17° (0,3 rad), plus rapide que les bras
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (4 + 17 * math.cos(2 * PHI(t, T)), 0, 3))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (4 - 17 * math.cos(2 * PHI(t, T)), 0, -3))},
    "jambe_droite_bas": {"rotation": ech(T, lambda t: (14 + 10 * math.sin(2 * PHI(t, T)), 0, 0))},
    "jambe_gauche_bas": {"rotation": ech(T, lambda t: (14 - 10 * math.sin(2 * PHI(t, T)), 0, 0))},
}))

# ramper (pose de nage hors de l'eau, sous un bloc) : sur les coudes, un bras puis l'autre, les genoux poussent
T = 1.0
A.append(animation("ramper", T, "loop", {
    "racine": {"rotation": A_PLAT, "position": (0, 2.6, 28)},
    "tete": {"rotation": (-58, 0, 0)},
    "corps_bas": {"rotation": ech(T, lambda t: (0, 0, 4 * math.sin(PHI(t, T))))},
    "corps_haut": {"rotation": ech(T, lambda t: (-8, 0, -6 * math.sin(PHI(t, T))))},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (-18, 0, 122 + 30 * math.cos(PHI(t, T))))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (-18, 0, -122 + 30 * math.cos(PHI(t, T))))},
    "bras_droit_bas": {"rotation": ech(T, lambda t: (-78 + 26 * math.cos(PHI(t, T)), 0, 0))},
    "bras_gauche_bas": {"rotation": ech(T, lambda t: (-78 - 26 * math.cos(PHI(t, T)), 0, 0))},
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (2, 0, 10 + 14 * pos(-math.cos(PHI(t, T)))))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (2, 0, -10 - 14 * pos(math.cos(PHI(t, T)))))},
    "jambe_droite_bas": {"rotation": ech(T, lambda t: (10 + 34 * pos(-math.cos(PHI(t, T))), 0, 0))},
    "jambe_gauche_bas": {"rotation": ech(T, lambda t: (10 + 34 * pos(math.cos(PHI(t, T))), 0, 0))},
}))

# échelle : une main monte quand le pied opposé monte ; Vorace règle la cadence sur la vitesse de montée (à l'arrêt, figé)
T = 0.7
A.append(animation("echelle", T, "loop", {
    "racine": {"position": (0, 0, -1.2)},
    "corps_bas": {"rotation": (5, 0, 0)},
    "tete": {"rotation": (-12, 0, 0)},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (-132 - 26 * math.cos(PHI(t, T)), 0, 6))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (-132 + 26 * math.cos(PHI(t, T)), 0, -6))},
    "bras_droit_bas": {"rotation": ech(T, lambda t: (-40 + 24 * math.cos(PHI(t, T)), 0, 0))},
    "bras_gauche_bas": {"rotation": ech(T, lambda t: (-40 - 24 * math.cos(PHI(t, T)), 0, 0))},
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (-36 + 28 * math.cos(PHI(t, T)), 0, 0))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (-36 - 28 * math.cos(PHI(t, T)), 0, 0))},
    "jambe_droite_bas": {"rotation": ech(T, lambda t: (52 - 38 * math.cos(PHI(t, T)), 0, 0))},
    "jambe_gauche_bas": {"rotation": ech(T, lambda t: (52 + 38 * math.cos(PHI(t, T)), 0, 0))},
}))

# assis (monture, bateau, wagonnet) : les jambes du jeu (-81°, écartées de 18°), les bras à -36°
T = 3.0
A.append(animation("assis", T, "loop", {
    "jambe_droite_haut": {"rotation": (-81, 18, 4.5)},
    "jambe_gauche_haut": {"rotation": (-81, -18, -4.5)},
    "jambe_droite_bas": {"rotation": (14, 0, 0)},
    "jambe_gauche_bas": {"rotation": (14, 0, 0)},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (-34 + 2 * math.sin(PHI(t, T)), 0, 4))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (-34 - 2 * math.sin(PHI(t, T)), 0, -4))},
    "bras_droit_bas": {"rotation": (-22, 0, 0)},
    "bras_gauche_bas": {"rotation": (-22, 0, 0)},
    "corps_haut": {"rotation": ech(T, lambda t: (1 + 0.8 * math.sin(PHI(t, T)), 0, 0))},
}))

# dormir : sur le dos, la tête à la place du joueur (l'oreiller), les pieds vers le pied du lit ; Vorace tourne le pantin
# dans le sens du lit
T = 4.0
A.append(animation("dormir", T, "loop", {
    "racine": {"rotation": (-90, 0, 0), "position": (0, 0, -26)},
    "corps_haut": {"rotation": ech(T, lambda t: (-1 - 1 * math.sin(PHI(t, T)), 0, 0))},
    "tete": {"rotation": (6, 0, 0)},
    "bras_droit_haut": {"rotation": (2, 0, 5)},
    "bras_gauche_haut": {"rotation": (2, 0, -5)},
    "bras_droit_bas": {"rotation": (-8, 0, 0)},
    "bras_gauche_bas": {"rotation": (-8, 0, 0)},
    "jambe_droite_haut": {"rotation": (0, 0, 3)},
    "jambe_gauche_haut": {"rotation": (0, 0, -3)},
}))

# vol (élytres) : à plat, bras le long du corps un peu ouverts, jambes serrées ; Vorace penche le corps vers où il regarde
T = 1.2
A.append(animation("vol", T, "loop", {
    "racine": {"rotation": A_PLAT, "position": (0, 5, 28)},
    "tete": {"rotation": (-48, 0, 0)},
    "corps_haut": {"rotation": (-5, 0, 0)},
    "bras_droit_haut": {"rotation": ech(T, lambda t: (8, 0, 16 + 3 * math.sin(PHI(t, T))))},
    "bras_gauche_haut": {"rotation": ech(T, lambda t: (8, 0, -16 - 3 * math.sin(PHI(t, T))))},
    "bras_droit_bas": {"rotation": (-10, 0, 0)},
    "bras_gauche_bas": {"rotation": (-10, 0, 0)},
    "jambe_droite_haut": {"rotation": ech(T, lambda t: (3 + 1.5 * math.sin(PHI(t, T)), 0, 4))},
    "jambe_gauche_haut": {"rotation": ech(T, lambda t: (3 - 1.5 * math.sin(PHI(t, T)), 0, -4))},
    "jambe_droite_bas": {"rotation": (6, 0, 0)},
    "jambe_gauche_bas": {"rotation": (6, 0, 0)},
}))

# vrille (trident avec Impulsion) : à plat, bras tendus devant, le corps tourne sur lui-même (75° par tic dans le jeu)
T = 0.25
A.append(animation("vrille", T, "loop", {
    "racine": {"rotation": Lin([(0, (90, 0, 0)), (T, (90, 0, 360))]), "position": (0, 5, 28)},
    "tete": {"rotation": (-20, 0, 0)},
    "bras_droit_haut": {"rotation": (0, 0, 174)},
    "bras_gauche_haut": {"rotation": (0, 0, -174)},
    "bras_droit_bas": {"rotation": (-4, 0, 0)},
    "bras_gauche_bas": {"rotation": (-4, 0, 0)},
    "jambe_droite_haut": {"rotation": (2, 0, 1)},
    "jambe_gauche_haut": {"rotation": (2, 0, -1)},
}))

# ------------------------------------------------------------------ gestes (par-dessus les mouvements : seuls ces os bougent)
# Écrits pour la main droite ; « _gauche » est leur miroir. Vorace ajoute le regard vers le haut ou le bas aux bras des
# gestes de visée (arc, arbalète, bouclier, longue-vue, cor), comme le jeu ajoute head.xRot aux bras.

COUDE_BAS = (0.5, -1, 0.35)        # coude tiré vers le bas, un peu dehors et derrière (bras droit)


def geste(duree, f, boucle):
    """
    f(t) → {"buste": (x, y, z) de corps_haut, "tete": (x, y, z) ajouté à la tête, "droit": (main, coude), "gauche": (main, coude)}.
    La tête annule la rotation du buste (elle continue de regarder devant ; le regard du joueur s'ajoute en jeu).
    """
    pistes = {}
    e0 = f(0)

    def piste(os, valeur):
        pistes.setdefault(os, {})["rotation"] = ech(duree, valeur, boucle == "loop")

    if "buste" in e0:
        piste("corps_haut", lambda t: f(t)["buste"])
    if "buste" in e0 or "tete" in e0:
        def tete(t):
            e = f(t)
            b, h = e.get("buste", (0, 0, 0)), e.get("tete", (0, 0, 0))
            return (h[0] - b[0], h[1] - b[1], h[2] - b[2])
        piste("tete", tete)
    for cote in ("droit", "gauche"):
        if cote in e0:
            def haut(t, cote=cote):
                e = f(t)
                return bras(cote, e[cote][0], e[cote][1], haut=e.get("buste", (0, 0, 0)))[0]

            def bas(t, cote=cote):
                e = f(t)
                return bras(cote, e[cote][0], e[cote][1], haut=e.get("buste", (0, 0, 0)))[1]
            piste("bras_%s_haut" % cote, haut)
            piste("bras_%s_bas" % cote, bas)
    return pistes


def deux(nom, duree, boucle, pistes):
    A.append(animation(nom, duree, boucle, pistes))
    A.append(animation(nom + "_gauche", duree, boucle, miroir(pistes)))


# tenir : la pose « objet en main » du jeu (bras levé de 18°, π/10), coude un peu plié, qui respire
T = 4.0
TENIR = geste(T, lambda t: {"droit": ((5.6 + 0.15 * math.sin(PHI(t, T)), 13.6 + 0.25 * math.sin(PHI(t, T)), -4.2), COUDE_BAS)}, "loop")
deux("tenir", T, "loop", TENIR)
p = copy.deepcopy(TENIR)
p.update(miroir(TENIR))
A.append(animation("tenir_deux", T, "loop", p))


# coup de bras (9 tics ; 6 dans le jeu, mais un bras qui tourne de 100° en un tic saute à l'écran) : la main part sur le côté à
# hauteur d'épaule, coude et épaule en arrière, puis fend en diagonale vers le bas
# et le milieu, le buste qui tourne avec le coup ; le jeu ne fait que lever et baisser le bras. La main ne passe jamais
# devant le visage ni au-dessus de la tête.
MAIN_REPOS = (5.6, 13.6, -4.2)
# (temps, main, rotation du buste) : armé en 2 à 3 tics, coup en 4, retour en 3 ; la main suit une courbe par ces points
COUP = [(0.0, MAIN_REPOS, 0.0), (0.12, (9.6, 22.6, -2.2), 13.0), (0.24, (5.4, 21.8, -9.0), -8.0), (0.32, (0.6, 16.0, -8.6), -17.0),
        (0.45, MAIN_REPOS, 0.0)]


def coup(t):
    # le temps ralentit au départ et à l'armé (un bref arrêt en haut : l'élan), puis le coup accélère jusqu'à l'impact
    t = 0.12 * doux(t / 0.12) if t < 0.12 else 0.12 + 0.33 * doux((t - 0.12) / 0.33)
    main = courbe([(a, m) for a, m, _ in COUP], t)
    vr = courbe([(a, v) for a, _, v in COUP], t)
    return {"buste": (3 * math.sin(math.pi * t / 0.45), vr, 0), "droit": (main, (1, -0.55, 0.45))}


deux("geste_main", 0.45, "once", geste(0.45, coup, "once"))

# manger : la main à la bouche, de petits va-et-vient (une bouchée tous les 4 tics dans le jeu) ; la tête pique un peu
T = 0.4
BOUCHE = (0.9, 24.8, -6.7)
deux("manger", T, "loop", geste(T, lambda t: {
    "tete": (5 + 3 * math.sin(PHI(t, T)), 0, 0),
    "droit": (add(BOUCHE, (0, 0.5 * math.sin(PHI(t, T)), 0.5 * math.cos(PHI(t, T)))), (0.9, -1, 0.1)),
}, "loop"))

# boire : la main plus haut, la tête en arrière, lent
T = 1.6
deux("boire", T, "loop", geste(T, lambda t: {
    "tete": (-14 - 3 * math.sin(PHI(t, T)), 0, 0),
    "droit": (add((0.9, 26.4, -6.0), (0, 0.3 * math.sin(PHI(t, T)), 0)), (0.9, -0.8, 0.1)),
}, "loop"))

# viser à l'arc : l'arc dans la main droite, bras tendu vers la cible ; la main gauche prend la corde et la tire jusqu'à la
# joue en 1 s (la tension complète de l'arc du jeu), puis tient. Le buste s'ouvre pendant la traction, épaule de l'arc devant :
# la main de l'arc avance, celle de la corde recule de 10 pixels (dans le jeu, les deux bras restent tendus devant).
ARC_0, ARC_1 = (3.4, 23.2, -9.6), (3.0, 23.4, -12.6)
CORDE_0, CORDE_1 = (1.4, 23.0, -8.4), (-5.4, 22.8, -3.0)     # sous la mâchoire, hors de la tête
COUDE_CORDE = (-0.7, 0.25, 1.0)


def arc(t):
    leve, tire = entre(t, 0.0, 0.18), entre(t, 0.15, 1.0)
    return {
        "buste": (0, -8 * leve - 24 * tire, 0),
        "droit": (mix(mix(MAIN_REPOS, ARC_0, leve), ARC_1, tire), (0.6, -1, 0.2)),
        "gauche": (mix(mix((-5.0, 14.5, -4.0), CORDE_0, leve), CORDE_1, tire), mix((-0.6, -1, 0.3), COUDE_CORDE, tire)),
    }


deux("viser_arc", 1.0, "hold", geste(1.0, arc, "hold"))

# arc bandé (Vorace l'enchaîne après la traction) : la même pose, qui respire ; l'arc bouge à peine
T = 3.0
deux("viser_arc_tenu", T, "loop", geste(T, lambda t: {
    "buste": (0.5 * math.sin(PHI(t, T)), -32, 0),
    "droit": (add(ARC_1, (0, 0.12 * math.sin(PHI(t, T)), 0)), (0.6, -1, 0.2)),
    "gauche": (add(CORDE_1, (0, 0.1 * math.sin(PHI(t, T)), 0.12 * math.sin(PHI(t, T) + 1.0))), COUDE_CORDE),
}, "loop"))


# tir à l'arc (au lâcher de la flèche) : la main de corde part en arrière, l'arc tient un instant puis redescend
def tir(t):
    part, baisse = entre(t, 0.0, 0.12), entre(t, 0.18, 0.5)
    return {
        "buste": (0, -32 * (1 - baisse), 0),
        "droit": (mix(add(ARC_1, (0, 0.4 * part, 0.5 * part)), MAIN_REPOS, baisse), (0.6, -1, 0.2)),
        "gauche": (mix(add(CORDE_1, (-1.2 * part, 0.2 * part, 2.4 * part)), (-5.6, 13.4, -2.0), baisse), mix(COUDE_CORDE, (-0.5, -1, 0.35), baisse)),
    }


deux("tir_arc", 0.5, "once", geste(0.5, tir, "once"))


# charger l'arbalète : celle du jeu (bras de l'arbalète à -56°, tourné de 46° vers le milieu ; l'autre main remonte la corde
# en 1,25 s). L'arbalète pointe vers le sol devant lui, il se penche dessus.
def charge(t):
    u = entre(t, 0.1, 1.25)
    return {
        "buste": (9, -6, 0),
        "tete": (8, 0, 0),
        "droit": ((1.4, 15.2, -6.2), (0.9, -0.6, 0.5)),
        "gauche": (mix((-0.4, 12.8, -7.6), (-0.2, 18.6, -5.4), u), (-0.9, -0.5, 0.5)),
    }


deux("charger_arbalete", 1.25, "hold", geste(1.25, charge, "hold"))

# viser à l'arbalète (arbalète chargée en main) : crosse à l'épaule, l'autre main sous le fût
T = 3.0
deux("viser_arbalete", T, "loop", geste(T, lambda t: {
    "buste": (0, -10, 0),
    "droit": ((3.2, 22.4 + 0.12 * math.sin(PHI(t, T)), -8.6), (0.8, -0.8, 0.3)),
    "gauche": ((-0.6, 21.6 + 0.12 * math.sin(PHI(t, T)), -7.6), (-0.7, -1, 0.2)),
}, "loop"))

# garde au bouclier : le bras du jeu (-54°, tourné de 30° vers le milieu), l'avant-bras en travers ; il se ramasse un peu
T = 3.0
deux("garde_bouclier", T, "loop", geste(T, lambda t: {
    "buste": (5 + 0.5 * math.sin(PHI(t, T)), 8, 0),
    "droit": ((2.2, 19.6 + 0.15 * math.sin(PHI(t, T)), -6.6), (0.9, -0.5, 0.2)),
}, "loop"))

# viser au trident : le bras du jeu (levé derrière la tête), le buste ouvert, l'autre bras pointe vers la cible
T = 3.0
deux("viser_trident", T, "loop", geste(T, lambda t: {
    "buste": (-4, 22, 0),
    "droit": ((8.4, 29.2 + 0.15 * math.sin(PHI(t, T)), 3.4), (1, -0.1, 0.6)),
    "gauche": ((-3.6, 21.0, -8.4), (-0.6, -1, 0.2)),
}, "loop"))

# longue-vue : la main devant l'œil droit, coude vers le bas
T = 3.0
deux("longue_vue", T, "loop", geste(T, lambda t: {
    "droit": ((2.6, 27.2 + 0.1 * math.sin(PHI(t, T)), -7.2), (0.7, -1, 0.1)),
}, "loop"))

# cor : la main à la bouche, coude levé sur le côté
T = 3.0
deux("cor", T, "loop", geste(T, lambda t: {
    "tete": (-6, 0, 0),
    "droit": ((2.0, 25.6 + 0.1 * math.sin(PHI(t, T)), -6.4), (1, 0.35, 0.1)),
}, "loop"))

# brosse : le bras du jeu (-36°) qui balaie devant lui
T = 0.5
deux("brosse", T, "loop", geste(T, lambda t: {
    "buste": (6, 0, 0),
    "droit": ((3.5 + 1.8 * math.sin(PHI(t, T)), 14.4, -7.2 + 0.4 * math.cos(2 * PHI(t, T))), COUDE_BAS),
}, "loop"))

# coup reçu : le bassin, le buste et la tête partent en arrière l'un après l'autre (2 tics), puis reviennent doucement. Pas de
# clé sur les bras : ils suivent le buste et gardent ce qu'ils faisaient (la main qui tient l'épée ne lâche pas sa pose).
T = 0.35
recul_ = lambda t: math.sin(math.pi * min(1.0, t / 0.1) / 2) if t < 0.1 else 1 - entre(t, 0.1, T)
A.append(animation("degat", T, "once", {
    "corps_bas": {"rotation": ech(T, lambda t: (-4 * recul_(t), 0, 0), False)},
    "corps_haut": {"rotation": ech(T, lambda t: (-9 * recul_(max(0.0, t - 0.025) * T / (T - 0.025)), 0, 0), False)},
    "tete": {"rotation": ech(T, lambda t: (-10 * recul_(max(0.0, t - 0.05) * T / (T - 0.05)), 0, 0), False)},
}))

projet = copy.deepcopy(SOURCE)
projet["name"] = "pantin_mouvements"
projet["animations"] = A
with open(os.path.join(DOSSIER, "pantin_mouvements.bbmodel"), "w", encoding="utf-8") as f:
    json.dump(projet, f, ensure_ascii=False, separators=(",", ":"))
print(len(A), "animations :", ", ".join(a["name"] for a in A))
