# Contrôle les animations d'un modèle au squelette du joueur (pantin_mouvements.bbmodel, ou un modèle retouché à la main) :
#   python3 outils/verifier-mouvements.py src/main/resources/exemples/modeles/pantin_mouvements.bbmodel [animation ...]
# Rejoue chaque animation tic par tic (même calcul que Blockbench et que Vorace) et signale :
#   - une main, un avant-bras ou un coude qui entre dans la tête ou dans le buste ;
#   - un pied sous le sol (mouvements debout) ;
#   - un os qui tourne de plus de 70° en un tic, 90° pour un coude ou un genou (saut de bras, angle d'Euler qui se retourne) ;
#   - un tronc qui se tord ou roule (il doit rester d'un bloc) ;
#   - une boucle qui ne se referme pas (la dernière image diffère de la première).
# Code de sortie 1 s'il y a un défaut.
import json, math, sys

d = json.load(open(sys.argv[1], encoding="utf-8"))
SEULES = set(sys.argv[2:])
PAS = 0.05


def rx(a):
    c, s = math.cos(a), math.sin(a)
    return [[1, 0, 0], [0, c, -s], [0, s, c]]


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, 0, s], [0, 1, 0], [-s, 0, c]]


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def mm(a, b): return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
def mv(a, v): return tuple(sum(a[i][k] * v[k] for k in range(3)) for i in range(3))
def tr(a): return [[a[j][i] for j in range(3)] for i in range(3)]
def add(a, b): return tuple(x + y for x, y in zip(a, b))
def sub(a, b): return tuple(x - y for x, y in zip(a, b))
def mix(a, b, u): return tuple(x + (y - x) * u for x, y in zip(a, b))


def mat(e):
    x, y, z = (math.radians(v) for v in e)
    return mm(rz(z), mm(ry(-y), rx(-x)))


def angle(a, b):
    m = mm(tr(a), b)
    return math.degrees(math.acos(max(-1.0, min(1.0, (m[0][0] + m[1][1] + m[2][2] - 1) / 2))))


OS = {}      # nom → (parent, pivot, uuid)


def lister(o, parent):
    for x in o:
        if isinstance(x, dict):
            OS[x["name"]] = (parent, tuple(x.get("origin", (0, 0, 0))), x["uuid"])
            lister(x.get("children", []), x["name"])


lister(d["outliner"], None)
COURT = {n[5:] if n.startswith("peau_") else n: n for n in OS}


def valeur(cles, t, defaut):
    if not cles:
        return defaut
    if t <= cles[0][0]:
        return cles[0][1]
    for (t0, v0), (t1, v1) in zip(cles, cles[1:]):
        if t <= t1:
            return mix(v0, v1, (t - t0) / (t1 - t0) if t1 > t0 else 1.0)
    return cles[-1][1]


def pistes(anim):
    p = {}
    for uuid, a in anim["animators"].items():
        for canal in ("rotation", "position"):
            cles = sorted((k["time"], tuple(float(k["data_points"][0][c]) for c in "xyz")) for k in a["keyframes"] if k["channel"] == canal)
            if cles:
                p[(a["name"], canal)] = cles
    return p


def pose(p, t):
    """nom d'os → (matrice monde, position monde du pivot, matrice locale)."""
    res = {}

    def faire(n):
        if n in res:
            return res[n]
        parent, pivot, _ = OS[n]
        loc = mat(valeur(p.get((n, "rotation")), t, (0, 0, 0)))
        dp = valeur(p.get((n, "position")), t, (0, 0, 0))
        if parent is None:
            res[n] = (loc, (pivot[0] - dp[0], pivot[1] + dp[1], pivot[2] + dp[2]), loc)
        else:
            pm, pp, _ = faire(parent)
            ppiv = OS[parent][1]
            local = (pivot[0] - ppiv[0] - dp[0], pivot[1] - ppiv[1] + dp[1], pivot[2] - ppiv[2] + dp[2])
            res[n] = (mm(pm, loc), add(pp, mv(pm, local)), loc)
        return res[n]

    for n in OS:
        faire(n)
    return res


def dans(po, os_, point, boite, marge):
    """Le point (monde) est-il dans la boîte de l'os (repère du modèle au repos), agrandie de marge ? Renvoie la profondeur."""
    m, pp, _ = po[COURT[os_]]
    piv = OS[COURT[os_]][1]
    loc = add(mv(tr(m), sub(point, pp)), piv)
    prof = min(min(loc[i] - (boite[0][i] - marge), (boite[1][i] + marge) - loc[i]) for i in range(3))
    return prof if prof > 0 else 0.0


TETE = ((-4, 24, -4), (4, 32, 4))
BUSTE = ((-4, 18, -2), (4, 24, 2))
VENTRE = ((-4, 12, -2), (4, 18, 2))
A_PLAT = ("nage", "ramper", "vol", "vrille", "dormir", "planer")     # couchés ou en l'air : pas de sol sous les pieds
defauts = 0
for anim in d["animations"]:
    nom = anim["name"]
    if SEULES and nom not in SEULES:
        continue
    p = pistes(anim)
    T = anim["length"]
    n = max(1, round(T / PAS))
    pb = {}

    def note(cle, v):
        pb[cle] = max(pb.get(cle, 0), v)

    # tronc rigide : ni torsion ni roulis du haut du tronc sur le bas, ni du bassin sur les jambes (le pantin « se
    # désassemble » à la taille et aux hanches, surtout en armure) ; le haut ne se penche pas de plus de 6° sur le bas
    for (os_, canal), cles in p.items():
        if canal != "rotation" or not (os_.endswith("corps_haut") or os_.endswith("corps_bas")):
            continue
        for _, (x, y, z) in cles:
            if abs(y) > 0.01 or abs(z) > 0.01:
                note("%s se tord ou roule (tronc rigide)" % os_, max(abs(y), abs(z)))
            if os_.endswith("corps_haut") and abs(x) > 6.01:
                note("%s penché de plus de 6° sur le bassin" % os_, abs(x))

    avant = None
    for i in range(n + 1):
        t = T * i / n
        po = pose(p, t)
        for cote in ("droit", "gauche"):
            coude, main = po[COURT["bras_%s_bas" % cote]][1], po["poing_" + cote][1]
            epaule = po[COURT["bras_%s_haut" % cote]][1]
            pts = [("main", main), ("avant-bras", mix(coude, main, 0.6)), ("avant-bras", mix(coude, main, 0.3)), ("coude", coude)]
            for quoi, pt in pts:
                v = dans(po, "tete", pt, TETE, 1.0)
                if v > 0.3:
                    note("%s %s dans la tête" % (quoi, cote), v)
                for os_, boite in (("corps_haut", BUSTE), ("corps_bas", VENTRE)):
                    v = dans(po, os_, pt, boite, 0.6)
                    if v > 0.3:
                        note("%s %s dans le buste" % (quoi, cote), v)
        if not nom.startswith(A_PLAT):
            for cote in ("droit", "gauche"):
                y = po["pied_" + cote][1][1]
                if y < -0.5:
                    note("pied %s sous le sol" % cote, -y)
        if avant is not None:
            for o in OS:
                a = angle(avant[o][2], po[o][2])
                # un coude ou un genou (charnière) peut fouetter jusqu'à 90° par tic ; les autres os, 70°
                limite = 90 if o.endswith("_bas") else 70
                if a > limite and not (nom.startswith("vrille") and o == "racine"):
                    note("%s tourne de plus de %d° en un tic" % (o, limite), a)
        avant = po
    if anim["loop"] == "loop":
        a, b = pose(p, 0), pose(p, T)
        for o in OS:
            v = angle(a[o][2], b[o][2])
            if v > 1.0:
                note("boucle ouverte sur " + o, v)
    if pb:
        defauts += len(pb)
        print("%-24s %s" % (nom, " ; ".join("%s (%.1f)" % (k, v) for k, v in sorted(pb.items()))))
print("%d animation(s) contrôlée(s), %d défaut(s)" % (len([a for a in d["animations"] if not SEULES or a["name"] in SEULES]), defauts))
sys.exit(1 if defauts else 0)
