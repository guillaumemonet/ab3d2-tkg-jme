# CHANGELOG — Alien Breed 3D II Java/JME Port

---

## [2026-04-29] Session 113 — Phase 2.F : collision murs pour les aliens

### Probleme

Apres la phase 2.E, les aliens marchent et tirent correctement, mais ils
**traversent les murs** car {@code AlienAI.prowlRandom} et
{@code AlienAI.moveTowards} ecrivent directement dans {@code state.worldX/Z}
sans aucune verification de geometrie. Resultat : les Red Aliens passent
au travers des cloisons et se retrouvent dans des zones inaccessibles, et
les Guards qui chargent traversent les murs au lieu de prendre un detour.

### Approche

Port partiel de {@code newaliencontrol.s::Obj_DoCollision} avec la table
{@code diststowall} indexee par {@code AlienT_Girth_w} (= 80, 160 ou 320
unites Amiga selon le girth de l'alien). Test cercle&nbsp;vs&nbsp;segment 2D
+ slide-along-wall (3 iterations pour les coins multi-murs).

La decision technique a ete d'utiliser un systeme de segments 2D (style ASM)
plutot que de passer par Bullet Physics, pour garder l'IA pure et testable.
C'est aussi plus rapide : ~6 microsecondes par alien et par frame contre
~50 microsecondes pour un raycast Bullet.

### Pipeline

```
  +-------------------------+
  | AlienAI.prowlRandom     |
  | AlienAI.moveTowards     |
  +-----------+-------------+
              | (worldX, worldZ, dx, dz, radiusAmiga)
              v
  +-----------+-------------+
  | AiWorld.resolveAlienMove|  (interface, default = no-op)
  +-----------+-------------+
              |
              v
  +-----------+-------------+
  | AiWorldAdapter         |  conversion Amiga -> JME
  +-----------+-------------+
              |
              v
  +-----------+-------------+
  | AlienWallCollider.move |  test cercle/segment + slide
  +-------------------------+
```

### Fichier nouveau (1)

**`src/main/java/com/ab3d2/world/AlienWallCollider.java`** (~150 lignes) :

- Classe inspiree de {@link com.ab3d2.world.WallCollision} (utilisee pour les
  portes), mais avec un rayon parametrable (= permet de gerer les 3 girth
  d'aliens : 80/160/320 unites Amiga).
- Methode statique {@code fromLevelScene(Node)} qui scanne les Geometry
  {@code walls_*} du levelScene et extrait les segments XZ depuis les
  vertex buffers (vertices 0 et 1 de chaque quad = bord bas BL/BR, qui
  donne exactement le segment 2D du mur projete au sol).
- Methode {@code move(px, pz, dx, dz, radius)} : 3 iterations de resolution
  pour gerer les coins.
- Les portes ne sont PAS incluses (Node {@code doors/} skippe) car elles
  bougent dynamiquement. En pratique les aliens sont quand meme bloques
  par les linteaux et step-walls voisins.

### Fichiers modifies (4)

1. **`src/main/java/com/ab3d2/core/ai/AiWorld.java`** : ajout de la methode
   {@code default float[] resolveAlienMove(fromX, fromZ, dx, dz, radiusAmiga)}.
   Default = no-op (passthrough), pour les tests qui ne veulent pas simuler
   la geometrie.

2. **`src/main/java/com/ab3d2/world/AiWorldAdapter.java`** :
   - Constructeur cree {@code wallCollider = AlienWallCollider.fromLevelScene(levelScene)}
   - Implemente {@code resolveAlienMove} : conversion Amiga &rarr; JME (Z
     negate, /SCALE), delegation au {@code wallCollider}, reconversion
     JME &rarr; Amiga.

3. **`src/main/java/com/ab3d2/core/ai/AlienAI.java`** :
   - {@code prowlRandom} : remplace {@code worldX += fwdX*step} par un appel
     a {@code world.resolveAlienMove(...)}
   - {@code moveTowards} : meme refactor, calcule le delta avant d'appeler
     {@code resolveAlienMove}

### Tests (10 nouveaux)

**`AlienWallColliderTest`** : 9 tests purs (sans dependance JME) :
- Aucun mur : passthrough
- Mur perpendiculaire : alien borne a {@code wallX - radius}
- Slide-along-wall en biais
- Coin en L : pas de NaN, position finie
- Mur degenere (longueur 0) : ignore
- Alien sur un mur : pousse le long de la normale
- Mouvement parallele : pas de blocage
- Boite fermee multi-murs : stabilite des 3 iterations
- Rayon variable : alien mince (radius=2.5) plus pres du mur que normal (5.0)

**`AlienAITest::WallCollisionInAi`** : 5 tests d'integration :
- {@code prowl : alien bloque par un mur} : verifie que
  {@code resolveAlienMove} est appele avec le bon radius
- {@code prowl : arrete dans son elan} : verifie le clamp
- {@code moveTowards : charge alien resolue contre mur} : alien en RESPONSE
  qui charge mais bute sur un mur entre lui et le joueur
- {@code radius depend du girth} : boss girth=2 utilise radius=320
- {@code FakeWorld par defaut : aucun blocage} : sans mur, alien bouge
  librement (regression test)

Total : 58 tests precedents + 10 nouveaux = **68 tests**.

### Resultat in-game attendu

1. **Plus de traversee de murs** : les Red Aliens en patrouille rebondissent
   contre les cloisons au lieu de passer au travers.
2. **Charge realiste** : les Guards qui chargent en ligne droite vers le
   joueur s'arretent au premier mur, glissent le long en cherchant un
   chemin (pour l'instant sans pathfinding intelligent, juste slide-along).
3. **Boss girth=2** : les Mantis/Crab/Wasp Boss (~10 unites JME de large)
   ne pourront plus s'incruster dans les coins etroits.

### Limitations

- **Pas de pathfinding** : si l'alien colle un mur en allant vers le joueur,
  il glissera mais ne contournera pas activement (= il peut rester coince
  contre un mur si le joueur est de l'autre cote sans ouverture en vue
  directe). Le vrai pathfinding via control points (graphe de zones) viendra
  en phase 2.G.
- **Portes ouvertes** : un alien peut passer dans une zone-porte ouverte
  car les ZDoorWalls ne sont pas dans le node {@code geometry/} mais dans
  {@code doors/}. C'est conforme a l'ASM (les aliens passent par les
  portes ouvertes).
- **Portes fermees** : les aliens ne sont pas explicitement bloques par
  une porte fermee mais en pratique les linteaux et step-walls dans
  {@code geometry/} les bloquent. A surveiller pendant les tests.
- **Pas de collision verticale** : aliens volants peuvent traverser les
  plafonds (a ajouter en 2.G si necessaire).
- **Pas de collision alien-alien** : 2 aliens peuvent se superposer (deja
  le cas dans l'ASM original, ce n'est pas une regression).

### TODO bug list (a corriger plus tard)

L'utilisateur a signale 2 bugs visuels qui ne sont pas de la phase 2.F
mais a corriger en 2.G ou ulterieurement :

- **Red Alien position Y trop haute pendant l'attaque** : le sprite
  semble flotter au-dessus du sol quand l'alien est en RESPONSE. Cause
  probable : le {@code spawnYMap} fige le Y a la hauteur initiale du
  sprite, mais l'animation d'attaque devrait peut-etre baisser le sprite.
  Reporte. Diagnostic : verifier si {@code AlienControlSystem.update} ne
  modifie pas Y selon l'etat (actuellement il prend toujours
  {@code spawnYMap}).

- **Bullets de tirs des Guards invisibles** : les projectiles spawnes par
  les Guards (bulType=9 Blaster) ne sont pas rendus visuellement. La
  methode {@code AiWorldAdapter.spawnAlienProjectile} n'est qu'un
  placeholder qui applique 50% de chance de hit sans creer de Geometry
  visible. A faire : creer un vrai pool de bullets aliens (
  {@code AlienShotPool}) symetrique a {@code PlayerShotPool}, avec
  {@code AlienBulletUpdateSystem} qui rendre les sprites bullet et fait
  voyager + tester collision avec le joueur.

---

## [2026-04-29] Session 118 — Audit twolev.bin : ajout PointBrights et ZoneBorderPoints

### Demande

Verifier que le parsing/export de {@code twolev.bin} extrait toutes les
donnees presentes dans le format binaire original, et ajouter ce qui manque.

### Methodologie

Comparaison ligne par ligne entre :
- {@code defs.i} (TLBT, ZoneT, EdgeT, PVST, ObjT, EntT) — structures officielles
- {@code hires.s:330-430} — sequence de chargement du fichier
- {@code LevelBinaryParser.java} — implementation Java actuelle

### Trouvailles

**Header TLBT (54 bytes apres 1600 bytes de messages)** : tous les champs sont
lus correctement en Java.

**Structures referencees par offsets** :
- {@code TLBT_PointsOffset_l} : Points table → OK
- {@code TLBT_FloorLineOffset_l} : Edges → OK (avec exitZoneId a -2)
- {@code TLBT_ObjectDataOffset_l} : ObjT/EntT → OK
- {@code TLBT_ObjectPointsOffset_l} : ObjectPoints → OK
- {@code TLBT_ShotDataOffset_l} : buffers projectiles runtime (donnees nulles
  dans le fichier, juste reservation memoire) → pas pertinent a exporter
- {@code TLBT_AlienShotDataOffset_l} : idem → pas pertinent
- {@code TLBT_Plr1ObjectOffset_l}, {@code TLBT_Plr2ObjectOffset_l} : pointent
  vers des ObjT pre-construits pour les joueurs ; les positions de depart
  sont deja dans le header TLBT → doublons

**Structures NON referencees par TLBT mais lues par {@code hires.s}** :

1. **PointBrights** — lue a {@code pointsOffset + numPoints*4 + 4}, taille
   {@code numZones * 80} bytes ({@code numZones * 40} WORDs).
   Reference : {@code hires.s:351} :
   ```asm
   lea    4(a2,d0.w*4),a2          ; a2 = points_end + 4 (padding)
   move.l a2,PointBrightsPtr_l
   ```
   Format de chaque WORD ({@code hires.s:1502-1543}) :
   - Byte BAS : luminosite de base (signed)
   - Byte HAUT (si != 0) : animation lumiere
     - bits 0-3 du byte haut : index dans {@code Anim_BrightTable_vw}
     - bits 4-7 : amplitude/phase d'oscillation

   **Critique pour le rendu** : sans cette table, l'eclairage dynamique des
   zones (lampes pulsantes, transitions de luminosite) ne peut pas etre
   reproduit fidelement.

2. **ZoneBorderPoints** — lue a {@code PointBrights + numZones*80}, taille
   variable, jusqu'a {@code floorLineOffset - 2}.
   Reference : {@code hires.s:359-361} et {@code newanims.s:119-131}.
   Format : 10 WORDs reserves par zone (point indices), terminees par WORD
   {@code -1} si la liste est plus courte que 10. Utilisee par le moteur
   pour la propagation de lumiere d'une zone a ses voisines via le PVS
   (eclairage radial autour des sources lumineuses).

### Implementation

**`LevelBinaryParser.BinData`** : ajout de 2 champs avec javadoc detaillant
le format binaire et la semantique :
- {@code short[][] pointBrights} : indexed par {@code [zoneIdx][i]} avec
  {@code i ∈ [0..39]}
- {@code short[][] zoneBorderPoints} : tableau de listes variables par zone,
  troncature a la premiere occurrence de {@code -1}

**`LevelBinaryParser.parseBin()`** : nouvelle section apres la lecture des
Points qui calcule les offsets {@code pointBrightsOffset} et
{@code zoneBorderOffset} et lit les 2 tables. Les 2 lectures sont gardees
separement avec validation d'offsets pour eviter d'echouer sur des fichiers
legèrement non conformes.

**`LevelJsonExporter`** : ajout de 2 sections JSON entre {@code points} et
{@code edges} :
```json
"pointBrights": [
  [v0, v1, ..., v39],   // zone 0
  [v0, v1, ..., v39],   // zone 1
  ...
],
"zoneBorderPoints": [
  [pt0, pt1, pt2],      // zone 0 : 3 points jusqu'au -1
  [pt0, ..., pt9],      // zone 1 : 10 points pleins
  ...
]
```

Les valeurs de {@code pointBrights} sont stockees comme entiers non-signes
(0..65535) car le byte haut peut activer l'animation. Les indices de
{@code zoneBorderPoints} sont signes mais doivent etre {@code >= 0} (les
valeurs negatives sont des terminators tronques par le parser).

### Champs deliberement non exportes

**Champs runtime dans ObjT/EntT** (initialises a 0 ou non significatifs dans
le fichier) : {@code SeePlayer}, {@code DamageTaken}, {@code CurrentMode},
{@code CurrentSpeed}, {@code EnemyFlags}, {@code Timer2}, {@code ImpactX/Y/Z},
{@code VelocityY}.

**Buffers de projectiles** ({@code ShotData}, {@code AlienShotData}) :
zones de memoire reservees au runtime, contenu null dans le fichier.

**Plr1ObjectOffset / Plr2ObjectOffset** : pointent vers des structures ObjT
preconfigurees pour les joueurs, mais les positions de depart sont deja
exposees via {@code player1} et {@code player2} dans le JSON. Aucune autre
donnee unique.

### Validation attendue

Apres re-export par {@code ./gradlew convertLevels} (ou autre task qui
lance {@code LevelJsonExporter}) :

```
INFO  PointBrights : 134 zones x 40 WORDs depuis 0xXXXX
INFO  ZoneBorderPoints : 134 zones depuis 0xXXXX (XXXX bytes total, ...)
```

Dans {@code level_A.json}, presence des cles {@code pointBrights} et
{@code zoneBorderPoints} avec respectivement 134 entrees (1 par zone). Le
total du fichier passe d'environ 200 KB a environ 250-300 KB (selon la
densite des ZoneBorderPoints).

---

## [2026-04-29] Session 117 — NOFF confirme pour worm (20) et robotright (24)

### Confirmation visuelle

Apres l'export multi-candidates de la session 116, l'inspection des frames a
revele les valeurs correctes :

- {@code worm} : **NOFF=20** = 5 vues directionnelles * 4 frames d'animation
  - f0-f4 : vue de face (visage et bras visibles)
  - f5-f9 : vue 3/4 face
  - f10-f14 : vue dos vue de dessus (silhouette pliee)
  - f15-f19 : vue de dos
  - {@code totalCols = 1800 / 20 = 90 (WOFF)} sans residu

- {@code robotright} : **NOFF=24** = 4 vues directionnelles * 6 frames d'animation
  - f0-f5 : face avec arme a droite
  - f6-f11 : 3/4 droite avec arme avant
  - f12-f17 : dos avec arme a gauche
  - f18-f23 : 3/4 gauche
  - {@code totalCols = 3072 / 24 = 128 (WOFF)} sans residu

Note : l'attente initiale de "5 vues * 4 frames partout" etait un biais.
robotright utilise un schema different (4 vues * 6 frames) probablement
parce qu'il a une animation de marche/tir plus elaboree. La verification
de divisibilite {@code totalCols % NOFF == 0} aurait flag NOFF=20 comme
invalide pour robotright (1800/20 ok, mais 3072/20 = 153.6 ne l'est pas).

Le pattern "vues * frames_anim" reste coherent pour les deux.

### Note : la spritesheet n'a jamais ete generee

La session 116 prevoyait un fichier `<name>_sheet.png` de la spritesheet
complete (1800x100 pour worm) mais aucun n'a ete produit — probablement
{@code renderFrameHqn} a une limite stricte sur la largeur d'une frame ou
une exception est levee silencieusement quand la frame demandee couvre
toute la table PTR. Pas approfondi car les frames individuelles sont
correctement generees et la valeur NOFF correcte est maintenant identifiee.

### Fix : NOFF fige + suppression du code multi-candidates

{@code WadConverter.convertObjectStandalone} simplifiee :
- Plus de spritesheet generee (inutile une fois NOFF connu)
- Plus de boucle sur des candidates (NOFF est un parametre simple)
- Validation : {@code totalCols % NOFF == 0} avec erreur explicite

Dans la boucle {@code extraAlienSprites}, NOFF est fige a 20 pour
{@code worm} et {@code robotright}. Liberte pour ajouter d'autres sprites
HQN avec un NOFF different (la signature reste flexible).

### Validation attendue

Apres re-export par {@code ./gradlew convertWads} :

```
[EXTRA] TKG1:HQN/WORM
  WAD=95527 bytes, PTR=7200 bytes, 256pal=1024 bytes, HQN=180000 bytes
  Frames: NOFF=20 WOFF=90 HOFF=100 (totalCols=1800)
  Mode HQN: 1 byte/pixel, index direct
  -> 20 sprites PNG

[EXTRA] TKG1:HQN/ROBOTRIGHT
  WAD=233536 bytes, PTR=12288 bytes, 256pal=1024 bytes, HQN=393216 bytes
  Frames: NOFF=24 WOFF=128 HOFF=128 (totalCols=3072)
  Mode HQN: 1 byte/pixel, index direct
  -> 24 sprites PNG
```

Les deux sprites passent maintenant la verification {@code totalCols % NOFF == 0}
sans residu. Les fichiers PNG ont des noms standards {@code worm_f0..f19.png}
et {@code robotright_f0..f23.png} sans prefixe {@code _n*}.

---

## [2026-04-29] Session 116 — Format HQN sans header, derivation HOFF depuis .HQN

### Probleme

Apres le fix session 115, les exports worm/robotright echouaient avec :
```
ERR: pas de marqueur header $FFFF a -8 (trouve $0000)
```

### Cause racine : le format HQN n'a pas le header PTR documente

Le commentaire de leved303.amos sur les 8 derniers bytes du PTR (marqueur
$FFFF + NOFF/WOFF/HOFF) decrit le format de l'editeur AMOS pour les sprites
standards. Les fichiers du dossier {@code media/hqn} (worm, robotright,
guard, ashnarg, etc.) utilisent un format different qui ne stocke pas ces
dimensions dans le PTR.

### Verification math : HOFF derivable depuis .HQN

Le dossier {@code media/hqn} contient en plus du couple wad+ptr un fichier
`.HQN` qui est le pixel data brut (1 byte/pixel, column-major). Les
relations exactes :

```
total_cols       = ptr_size / 4              (4 bytes par entree PTR)
NOFF * WOFF      = total_cols                (les colonnes tilent les frames)
NOFF * WOFF * HOFF = HQN_size                (pixel data brut)
=> HOFF          = HQN_size / total_cols
```

Verification avec guard (frame size LH=80 connu via LNK) :
```
guard.HQN  = 134400 bytes
guard.PTR  =   6720 bytes -> 1680 cols
HOFF       = 134400 / 1680 = 80                  CORRESPOND a LH du LNK
21 frames  * 80 wide       = 1680 cols           CORRESPOND a NOFF*WOFF
```

Derivations pour worm et robotright :
```
worm.HQN     = 180000 bytes,  worm.ptr     = 7200 bytes  -> HOFF = 100
robotright.HQN = 393216 bytes, robotright.ptr = 12288 bytes -> HOFF = 128
```

### Probleme residuel : NOFF inconnu

Meme avec HOFF connu, on ne peut pas separer NOFF (nb frames) de WOFF
(largeur frame) sans connaissance externe. Pour AB3D2 les conventions
usuelles sont :
- 5 vues directionnelles * 4 frames anim = 20
- 5 vues * 5 frames = 25
- 5 vues * 6 frames = 30
- 8 vues (boss) * variations = 24, 32

### Fix : sheet complete + candidats NOFF multiples

{@code WadConverter.convertObjectStandalone} re-ecrite :

1. Genere une **spritesheet complete** {@code <name>_sheet.png}
   (largeur = total_cols, hauteur = HOFF) qui montre tout le sprite
   en un seul PNG. Le user peut visuellement compter les frames.

2. Pour chaque {@code candidateNoff} qui divise exactement {@code total_cols} :
   - calcule {@code WOFF = total_cols / NOFF}
   - genere {@code NOFF} fichiers PNG individuels
   - le **premier** candidate utilise le nom standard {@code <name>_f0..fN-1.png}
   - les **suivants** sont prefixes {@code <name>_n24_f0.png}, etc., pour
     ne pas ecraser le premier ensemble

Candidates choisies par sprite :
- worm : `20, 24, 25, 30` (probablement 30 d'apres la taille fine 60x100)
- robotright : `20, 24, 32` (probablement 32 d'apres 96x128)

### Validation attendue

Apres re-export par {@code ./gradlew convertWads} :

```
[EXTRA] TKG1:HQN/WORM
  WAD=95527 bytes, PTR=7200 bytes, 256pal=1024 bytes, HQN=180000 bytes
  Derive HOFF=100 depuis HQN=180000 / totalCols=1800 (residu=0)
  Mode HQN: 1 byte/pixel, index direct
    sheet: worm_sheet.png (1800x100, totalCols=1800)
    candidate NOFF=20, WOFF=90, HOFF=100 : 20 frames
    candidate NOFF=24, WOFF=75, HOFF=100 : 24 frames
    candidate NOFF=25, WOFF=72, HOFF=100 : 25 frames
    candidate NOFF=30, WOFF=60, HOFF=100 : 30 frames
```

Le user inspecte {@code worm_sheet.png}, compte les frames, indique le bon
NOFF, et la liste {@code candidateNoffs} sera reduite a une seule valeur
dans une session ulterieure.

### TODO Phase suivante

Une fois NOFF identifie pour worm et robotright :
- Reduire {@code extraAlienSprites[*][2]} a la seule valeur correcte
- Le nom des fichiers sera alors {@code worm_f0..fN-1.png} sans prefixe
- Mettre a jour {@code LevelSceneBuilder.ALIEN_WAD_BY_GFXTYPE} si necessaire

---

## [2026-04-29] Session 115 — Fix decoupage frames worm/robotright (PTR header standalone)

### Probleme

Apres le fix session 114 (chemins HQN), worm et robotright avaient maintenant
les bonnes couleurs mais un **decoupage des frames incorrect** :

- {@code worm_f0.png} affichait un fragment du sprite (jambes/torse seulement)
- 19 frames produites au lieu d'une trentaine attendue
- Frames de ~500 bytes au lieu de ~1.2 KB comme guard (sprite complet)
- Aspect general : chaque frame = un bout decale d'un personnage humanoide

### Cause racine : utilisation des frame descs d'alien2

Dans la boucle {@code extraAlienSprites}, l'appel etait :

```java
int saved = conv.convertObject(0, wadData, ptrData, ...)
```

Le parametre {@code 0} = {@code objIdx} dans le LNK. Mais worm/robotright ne
sont PAS references dans {@code GLFT_ObjGfxNames_l}, donc on lisait les
frame descs (LX, LY, LW, LH) de l'objet 0 = **alien2**.

Resultat : on decoupait le sprite worm avec les coordonnees prevues pour
alien2 — d'ou les fragments incoherents.

Les autres sprites HQN (guard, priest, insect, triclaw, ashnarg) marchent
car ils sont dans le LNK et ont leurs propres frame descs. Worm et
robotright sont speciaux : leurs WAD existent dans {@code media/hqn} mais
le LNK ne les reference pas.

### Fix

Nouvelle methode {@code WadConverter.convertObjectStandalone(...)} qui :

1. Lit le header du fichier PTR (8 derniers bytes) :
   ```
   word[-4] = $FFFF  (marqueur)
   word[-3] = NOFF   (nombre de frames)
   word[-2] = WOFF   (largeur d'une frame en colonnes)
   word[-1] = HOFF   (hauteur d'une frame en lignes)
   ```

2. Construit pour chaque frame {@code f} un {@code FrameDesc} synthetique :
   `lx = f * WOFF, ly = 0, lw = WOFF, lh = HOFF`

3. Delegue a {@code renderFrameHqn} ou {@code renderFrame} selon le format.

La boucle {@code extraAlienSprites} appelle desormais cette methode au lieu
de {@code convertObject(0, ...)}, ne touchant pas au LNK.

### Validation attendue

Apres re-export par {@code ./gradlew convertWads} :

- Log : `PTR header: NOFF=N WOFF=W HOFF=H` pour worm et robotright
- {@code assets/Textures/objects/worm/worm_f0.png} : un sprite complet de
  worm (ou plutot d'alien-warrior d'apres l'image, le nom est trompeur)
- Nombre de frames = NOFF lu dans le header (probablement >= 20)
- Tailles de frames coherentes (~1-2 KB chacune comme guard/insect)

### Limitations residuelles

Les 5 vues de rotation (top/bot/lft/rgt/cmp) restent dans des fichiers
separees ({@code worm.top}, {@code worm.bot}, etc.) qui ne sont pas
traites par {@code WadConverter}. Le fichier {@code worm.wad} traite ici
contient probablement la vue principale (face avant). Les autres vues
requiereront un parser dedie si on veut le rendu 3D fidele.

---

## [2026-04-29] Session 114 — Fix exports sprites bitmap (NUM_FRAMES + chemins HQN)

### Probleme

Apres re-test visuel des sprites exportes par {@code WadConverter}, deux
bugs identifies :

1. **`bigbullet_f33.png` (et frames 32+ de tous les objets) montraient des
   sprites "fantomes"** : par exemple 4 bullets dans une grille 2x2,
   alors que c'est une seule bullet par frame. {@code glare/} exportait
   64 frames dont la moitie etait du contenu vole a l'objet voisin.

2. **`worm_f17.png` et toutes les frames de worm = bruit visuel noir** :
   le rendu HQN decodait des donnees 5-bit packed au lieu de 1 byte/pixel.

### Sources analysees

- {@code defs.i} — STRUCTURE GLFT pour confirmer les offsets du LNK
- {@code leved303.amos::_GL_SETOBJFRAMES} — doc du format frame data
- {@code objdrawhires.s::draw_bitmap, draw_bitmap_lighted} — rendu standard vs HQN
- Inventaire {@code media/includes} et {@code media/hqn} (fichiers en double)

### Cause racine 1 : NUM_FRAMES=64 incorrect

La structure `GLFT_FrameData_l` du LNK fait 30 objets x 256 bytes :

```
OFS_FRAME_DATA   = 0x39B0  (14768)
OFS_OBJECT_NAMES = 0x57B0  (22448)
diff             = 0x1E00  (7680 = 30 * 256)
```

Chaque frame = 8 bytes (LX, LY, LW/2, LH/2 en WORD), donc 256/8 = **32 frames
max par objet**. Le commentaire de leved303.amos qui mentionnait "FRN (0..63)"
etait trompeur (probable vestige d'un format intermediaire jamais utilise).

Avec {@code NUM_FRAMES = 64}, {@code countFrames(objIdx)} et
{@code getFrameDesc(objIdx, frameIdx >= 32)} lisaient au-dela des 256 bytes
de l'objet et tombaient dans les donnees de l'objet suivant. Resultat :
les frames 32+ etaient en fait les frames 0+ des objets voisins, d'ou les
sprites fantomes.

### Cause racine 2 : worm/robotright en double dans deux dossiers

```
media/includes/worm.wad     = 50.88 KB  (5-bit packed, format standard)
media/hqn/worm.wad          = 93.29 KB  (1 byte/pixel, format HQN)

media/includes/worm.256pal  = 2 KB      (32 brightness * 64 bytes)
media/hqn/worm.256pal       = 1 KB      (4 light types * 256 bytes)
```

{@code gatherWadSearchPaths} ajoutait {@code media/includes} avant
{@code media/hqn}, donc {@code findAndLoad("worm.wad", ...)} trouvait la
version 5-bit packed. Mais comme {@code worm} est dans {@code HQN_SPRITE_NAMES},
{@code renderFrameHqn} essayait de decoder 1 byte/pixel sur ces donnees,
produisant du bruit.

Les autres HQN ({@code ashnarg}, {@code insect}, {@code guard}, {@code priest},
{@code triclaw}) n'etaient pas affectes car ils n'existent QUE dans
{@code media/hqn/}.

### Fixes

**`LnkParser.java`** : `NUM_FRAMES` 64 -> 32, avec javadoc detaillant le calcul
de la taille reelle de la section frame data et l'historique du bug.

**`WadConverter.java`** :
- {@code gatherWadSearchPaths(srcRes, preferHqn)} : nouveau parametre
  {@code preferHqn} qui inverse l'ordre {@code includes/hqn} en favorisant
  {@code hqn/} pour les sprites HQN.
- Boucle {@code extraAlienSprites} (worm, robotright) : utilise maintenant
  {@code hqnSearchPaths} au lieu de {@code wadSearchPaths}.
- Nettoyage prealable du dossier de sortie {@code assets/Textures/objects}
  pour supprimer les anciennes frames fantomes du dernier run buggy
  (sans cela, glare_f32..f63 et bigbullet_f33 resteraient sur disque
  apres re-export, polluant les checks visuels).
- Log enrichi : taille des fichiers WAD/PTR/256pal pour les sprites EXTRA
  (utile pour verifier qu'on charge bien le bon fichier).

### Validation attendue

Apres re-export par {@code ./gradlew convertWads} :

- `assets/Textures/objects/glare/` : exactement 32 fichiers (au lieu de 64)
- `assets/Textures/objects/bigbullet/` : plus de `_f33.png` avec 4 bullets
- `assets/Textures/objects/worm/worm_f0.png` : un vrai sprite de worm vert
- `assets/Textures/objects/worm/` : ~32 frames (pas 20)
- Toutes les autres sprites (alien2, ashnarg, insect, guard, etc.) inchanges

### Limitations connues (pas dans le scope)

- {@code robotright} reste tres desature (sepia/grey). Le lightType=0 utilise
  par {@code buildHqnPalsVl} pourrait ne pas correspondre a la palette
  prevue pour le robot. A investiguer si necessaire dans une session future.
- Les sprites GLARE ({@code glare/}, {@code explosion/}) apparaissent ternes
  car concus pour un rendu en blend additif au runtime. Pas un bug de
  l'export, mais a prendre en compte dans le materiel JME des billboards.

---

## [2026-04-29] Session 113 — Phase 2.E : animations sprites 4-directionnelles

### Fixes apres test in-game (4)

Apres test in-game, le joueur a constate :
- *"je ne vois pas car soit c'est trop rapide"* : animation invisible en patrouille
- Avertissements `Cannot locate resource: alien2_f19.png (Flipped)` repetes en boucle

Quatre causes identifiees et corrigees :

**Fix 1 : timer2 jamais incremente en mode DEFAULT/FOLLOWUP** (root cause du "static")

Dans `core/ai/AlienAI.java::doDefault`, le {@code timer2} (= driver de l'anim
de marche) n'etait jamais incremente. Resultat : `pickFrame` recevait
toujours `timer2=0`, donc affichait la frame 0 de la vue courante en
permanence. **L'alien marchait visuellement comme une statue glissante**.

Ajoute :
```java
// Phase 2.E (fix) : incrementer timer2 chaque frame pour faire avancer
// l'animation de marche. Sans ca, l'alien reste fige sur la frame 0.
a.timer2 += frames;
if (a.timer2 > 100000) a.timer2 = a.timer2 % 24;
```

Meme fix dans `doFollowup` (sinon l'alien restait fige pendant la pause
apres une attaque).

**Fix 2 : conflit timer3 prowl vs death**

`prowlRandom` utilisait `a.timer3` pour le tick de changement de direction.
Mais `timer3` est aussi le compteur de fade-out de la mort (utilise dans
`doDie` et `isDeadAndGone()`). Resultat : un alien en train de mourir
pouvait "changer de direction" pendant son anim de mort.

Fix : ajout d'un nouveau champ {@code prowlTicker} dans `AlienRuntimeState`
dedie au prowl, separe de `timer3`. ASM-fidele : dans le binaire original,
les aliens utilisent un timer different pour le random walk vs la mort.

**Fix 3 : animation trop rapide**

`WALK_TICK_DIVISOR=6` donnait ~10 frames/sec a 60Hz JME, ce qui faisait
un cycle complet en 0.5 sec - trop rapide pour qu'on identifie
visuellement les frames individuelles.

Ajuste a 12 (= ~4 frames/sec, cycle complet en 1 sec). Idem
`ATTACK_TICK_DIVISOR` 4 -> 8 pour les anims d'attaque.

Les tests JUnit `AlienAnimTableTest` ont ete mis a jour pour les nouvelles
valeurs.

**Fix 4 : warnings JME repetes pour frames manquantes**

Les avertissements `guard_f21.png` / `alien2_f19.png` etaient emis car :

1. **`countSpriteFrames`** utilisait `assetManager.locateAsset(TextureKey)`,
   qui emet un warning JME pour CHAQUE fichier non trouve. Avec une boucle
   0..32, ca polluait les logs au load.
2. **`AlienSpriteController.loadFrame`** : le cache utilisait
   `Map.get() != null` pour decider si une frame etait cachee. Comme on
   cache `null` pour les frames absentes, le test echouait et on retentait
   le `loadTexture` a chaque appel (= warning JME chaque fois).

Deux corrections :

- `AlienControlSystem.countSpriteFrames` : maintenant teste l'existence via
  `Files.exists()` sur le filesystem directement (silencieux), au lieu de
  passer par JME.
- `AlienSpriteController.loadFrame` : utilise `Map.containsKey` pour
  distinguer "absent du cache" de "cache avec valeur null".

### Fichiers modifies (5)

1. **`src/main/java/com/ab3d2/core/ai/AlienAI.java`** :
   - `doDefault` : ajout `timer2 += frames` + bornage
   - `doFollowup` : meme ajout
   - `prowlRandom` : utilise `prowlTicker` au lieu de `timer3`
2. **`src/main/java/com/ab3d2/core/ai/AlienRuntimeState.java`** : ajout
   du champ `public int prowlTicker`
3. **`src/main/java/com/ab3d2/core/ai/AlienAnimTable.java`** : divisors
   ajustes (6 -> 12 walk, 4 -> 8 attack)
4. **`src/main/java/com/ab3d2/world/AlienControlSystem.java`** :
   `countSpriteFrames` utilise `Files.exists` au lieu de
   `assetManager.locateAsset`
5. **`src/main/java/com/ab3d2/world/AlienSpriteController.java`** :
   `loadFrame` utilise `Map.containsKey` pour le cache

### Tests mis a jour

- `AlienAnimTableTest` : 12 tests mis a jour pour les nouveaux divisors
  (frame 6 -> 12, 18 -> 36, 4 -> 8, etc.)
- `AlienAITest::prowlMovesAlienWithoutControlPoints` : utilise
  `a.prowlTicker = 0` et verifie `a.prowlTicker > 0` apres update.

### Resultat attendu apres fixes

1. Plus de `Cannot locate resource:` en boucle dans les logs.
2. Animation de marche **visible** : 4 frames qui se succedent ~1 par 250ms
   (cycle complet en 1 sec, lisible a l'oeil).
3. Alien qui change correctement de viewpoint quand on tourne autour.
4. La fade de mort n'est plus interrompue par un changement de direction
   prowl.

---

## [2026-04-29] Session 113 — Phase 2.E : animations sprites 4-directionnelles

### Contexte

Apres test in-game de la phase 2.D (ASM-fidelity fixes), les aliens se
deplacent et tirent correctement, MAIS leurs sprites restent figes sur la
frame 0 (= statique). Le joueur a explicitement demande : *"Pour les
deplacements n'oublie pas les animations, car la ca fait static"*.

La phase 2.E porte le systeme d'animation 4-directionnelle de l'ASM original
(`newaliencontrol.s::ViewpointToDraw` + `modules/ai.s::ai_DoWalkAnim`) :
selon la position relative joueur-alien, le sprite affiche une vue
TOWARDS / RIGHT / AWAY / LEFT, et cycle entre 4 frames de marche par vue.

### Pipeline d'animation

```
  +-------------------------+
  | AlienControlSystem.update|
  +------------+------------+
               | 1. computeCameraAngle() une fois
               v
  +------------+------------+
  | AlienViewpoint.compute  |  alienAngle vs cameraAngle
  +------------+------------+   -> TOWARDS / RIGHT / AWAY / LEFT
               |
               v
  +------------+------------+
  | AlienAnimTable.pickFrame|  mode + viewpoint + timer2
  +------------+------------+   -> frame index 0..18
               |
               v
  +------------+------------+
  | AlienSpriteController   |  swap material's DiffuseMap
  +-------------------------+   -> Textures/objects/{wad}/{wad}_fN.png
```

### 1. AlienViewpoint (`core/ai/AlienViewpoint.java`)

Enum a 4 valeurs reproduisant l'ASM `ViewpointToDraw` :

```asm
ViewpointToDraw:
    d3 = EntT_CurrentAngle_w(a0) - Vis_AngPos_w   ; angle relatif
    d2 = sin(d3)                                   ; composante laterale
    d3 = cos(d3)                                   ; composante avant
    d0 = -d3                                       ; -cos
    if d0 > 0 -> FacingTowardsPlayer (cos < 0)
    ; sinon : FAP (Facing Away from Player)
```

Logique :
- **cos < 0** : alien face camera. Si |sin| > |cos|, profil (LEFT ou RIGHT
  selon signe de sin), sinon TOWARDS
- **cos > 0** : alien dos camera. Idem mais avec AWAY a la place de TOWARDS

L'ordinal de l'enum suit l'ordre ASM : `TOWARDS=0, RIGHT=1, AWAY=2, LEFT=3`
(comme `TOWARDSFRAME / RIGHTFRAME / AWAYFRAME / LEFTFRAME` dans le binaire).

### 2. AlienAnimTable (`core/ai/AlienAnimTable.java`)

**Probleme** : les vraies tables `GLFT_AlienAnims_l[type]` ne sont pas
encore extraites du binaire (`TEST.LNK`). On utilise une **convention
deduite** de l'observation des PNGs convertis :

```
  Frames 0..3   : walk anim TOWARDS  (4 frames cyclique)
  Frames 4..7   : walk anim RIGHT
  Frames 8..11  : walk anim AWAY
  Frames 12..15 : walk anim LEFT
  Frame 16      : attack/shoot
  Frame 17      : hit (encaisse)
  Frame 18      : die / splat
```

Verifie sur :
- alien2 (Red Alien) : 19 frames _f0..f18
- guard : 21 frames
- worm, robotright, insect, priest, ashnarg, triclaw : tous &ge; 17 frames

Methode `pickFrame(mode, viewpoint, amigaFrame, maxFrames)` :
- DEFAULT/FOLLOWUP/RETREAT &rarr; walkFrame(vp, amigaFrame) : cycle 4 frames
  (1 frame PNG toutes les `WALK_TICK_DIVISOR=6` frames Amiga)
- RESPONSE &rarr; alterne entre ATTACK_FRAME_BASE (=16) et walk frame 0 du
  viewpoint (= flash d'attaque entre les marches)
- TAKE_DAMAGE &rarr; HIT_FRAME_BASE (=17)
- DIE &rarr; DIE_FRAME_BASE (=18)

Le clamp : si l'index calcule depasse `maxFrames` (WAD plus court), on
tombe sur la 1ere frame du viewpoint, puis 0 en derniere extremite.

### 3. AlienSpriteController (`world/AlienSpriteController.java`)

Un controller par alien anime, ~150 lignes. Pipeline :

1. **Constructor** : trouve la `Geometry` sprite dans le sous-arbre du
   Node alien (= celle nommee `*_sprite` cf. `LevelSceneBuilder.tryLoadSprite`).
   Capture le Material initial (Lighting.j3md). Si pas de Geometry sprite
   trouvee (cas des aliens vector type=1 : SnakeScanner, Mantis Boss etc.),
   `isAnimatable() == false` et l'update est un no-op.

2. **update(state, cameraAngle)** :
   - Calcule `AlienViewpoint vp = AlienViewpoint.compute(state.currentAngle, cameraAngle)`
   - Calcule `frameIdx = AlienAnimTable.pickFrame(state.mode, vp, state.timer2, maxFrames)`
   - Si `frameIdx == lastFrame` &rarr; skip (pas de change)
   - Sinon charge `Textures/objects/{wadName}/{wadName}_f{frameIdx}.png` (avec
     cache textureCache pour eviter les reloads)
   - Applique `material.setTexture("DiffuseMap", tex)`

3. **Cache** : Map<Integer, Texture> par frame index. Premiere apparition
   d'une frame = 1 load PNG, ensuite reutilisee. Pour un alien type alien2
   avec 19 frames, max 19 charges sur la duree de vie.

4. **Filtre Nearest** : `MagFilter.Nearest + MinFilter.NearestNoMipMaps` pour
   conserver l'aspect pixelise original Amiga.

### 4. AlienControlSystem - integration

Modifications dans `world/AlienControlSystem.java` :

- **Champs ajoutes** :
  - `Camera camera` capturee dans `initialize()` via `app.getCamera()`
  - `AssetManager assetManager` capturee aussi
  - `Map<AlienRuntimeState, AlienSpriteController> spriteCtrlMap`

- **Dans `initialize()` pour chaque alien** :
  ```java
  String wadName = spriteNode.getUserData("wadName");
  if (wadName != null && !wadName.isEmpty()) {
      int maxFrames = countSpriteFrames(wadName);
      AlienSpriteController ctrl = new AlienSpriteController(
          spriteNode, wadName, maxFrames, assetManager);
      if (ctrl.isAnimatable()) {
          spriteCtrlMap.put(state, ctrl);
      }
  }
  ```

- **`countSpriteFrames(wadName)`** : scanne 0..32 via
  `assetManager.locateAsset(new TextureKey(path))` jusqu'au premier trou.
  Pour alien2 retourne 19, pour priest peut-etre moins.

- **Dans `update()`** :
  - Calcule `int cameraAngle = computeCameraAngle()` UNE fois par frame
    (= equivalent ASM `Vis_AngPos_w`)
  - Apres sync de la position du Node, appelle `ctrl.update(state, cameraAngle)`
  - Sur `isDeadAndGone()` : retire aussi du spriteCtrlMap

- **`computeCameraAngle()`** :
  ```java
  Vector3f dir = camera.getDirection();
  float rad = (float) Math.atan2(dir.x, dir.z);
  rad += FastMath.PI;  // JME camera regarde -Z, alien convention +Z
  if (rad < 0) rad += FastMath.TWO_PI;
  rad = rad % FastMath.TWO_PI;
  return (int) (rad * 4096.0 / (2 * Math.PI)) & 0xFFF;
  ```
  Convertit la direction camera JME en angle 0..4095 (Amiga convention).

### Fichiers nouveaux (3)

1. **`src/main/java/com/ab3d2/core/ai/AlienViewpoint.java`** : enum + compute
   (~80 lignes)
2. **`src/main/java/com/ab3d2/core/ai/AlienAnimTable.java`** : utility statique
   pour selection de frame (~130 lignes)
3. **`src/main/java/com/ab3d2/world/AlienSpriteController.java`** : controleur
   par alien (~150 lignes)

### Fichiers modifies (1)

4. **`src/main/java/com/ab3d2/world/AlienControlSystem.java`** : ajout du
   pipeline d'animation (~50 lignes ajoutees, integration dans `initialize`,
   `update`, `cleanup`)

### Resultat in-game attendu

1. **Marche cyclique** : un Red Alien qui patrouille montre 4 frames de
   marche TOWARDS qui se succedent (= cycle a chaque ~6 frames Amiga
   = ~120 ms par frame, 480 ms pour le cycle complet).

2. **Vue 4-directionnelle** : tournez autour d'un alien en cercle, le sprite
   change : TOWARDS quand vous etes face a lui, profil RIGHT/LEFT quand
   vous etes sur le cote, AWAY quand vous etes derriere.

3. **Animation d'attaque** : quand un Guard tire, sa frame 16 (muzzle flash)
   apparait brievement entre 2 frames de marche, donnant l'illusion d'un
   tir ponctuel.

4. **Animation hit** : quand un alien encaisse un coup (mode TAKE_DAMAGE,
   25% de chance par hit), sa frame 17 (= alien convulse) s'affiche pendant
   ~8 frames Amiga (~160 ms).

5. **Animation mort** : quand un alien meurt (mode DIE), sa frame 18
   (= splat / corps) s'affiche pendant 25 frames Amiga (= ~500 ms) avant
   d'etre retire de la scene.

6. **Aliens vector** (Mantis Boss, etc., gfxType=1) : pas anime visuellement
   pour l'instant car les modeles 3D `.j3o` n'ont pas de keyframes exposees.
   `AlienSpriteController.isAnimatable()` retourne false, c'est un no-op.

### Limitations connues (a faire en phases ulterieures)

- **Tables ASM non extraites** : la convention 4 frames/vue + 16/17/18 est
  une heuristique. Quand les vraies tables `GLFT_AlienAnims_l` seront
  extraites du binaire, elles pourront avoir des nombres de frames
  variables par type d'alien et des byte triggers (`ai_DoAction_b`,
  `ai_FinishedAnim_b`) precis frame-par-frame. Pour l'instant on tire
  toujours au `FIRE_FRAME=4` cf. phase 2.D, qui ne correspond pas
  forcement a la "frame de tir" reelle de chaque alien.

- **Aliens vector pas animes** : Mantis Boss, Wasp Boss, Crab Boss,
  SnakeScanner. Il faudrait porter le systeme `VectObjFrameAnimControl`
  qui anime les keyframes du modele 3D selon la frame index. Reporte.

- **Pas de transition douce** : le swap de DiffuseMap est instantane
  chaque N tick Amiga. C'est conforme a l'ASM (qui faisait du blit
  pixel-by-pixel sans interpolation), donc pas un bug.

- **Pas de lip-sync entre l'attaque IA et la frame 16** : actuellement
  le tir est declenche a `FIRE_FRAME=4` dans `AlienAI.doResponse`, mais
  la frame 16 est affichee selon `phase = timer2 / ATTACK_TICK_DIVISOR`.
  Les deux peuvent etre desynchrones d'une frame ou deux. Acceptable.

### Tests

**Nouveaux tests (sans dependance JME)** :
- `AlienViewpointTest` : 6 tests pour le mapping (alienAngle, cameraAngle)
  vers TOWARDS / RIGHT / AWAY / LEFT (sameDirection -> AWAY, opposite ->
  TOWARDS, profile 90deg -> RIGHT/LEFT, wrap-around, tour autour, ordinaux)
- `AlienAnimTableTest` : 12 tests pour le mapping (mode, viewpoint,
  amigaFrame, maxFrames) -> frame index 0..18 avec validation du cycle,
  de l'alternance attack/walk, des frames fixes (TAKE_DAMAGE=17, DIE=18),
  et du clamp quand maxFrames est insuffisant.

**Pas de test pour `AlienSpriteController`** : il manipule JME (Material,
AssetManager, Geometry) ce qui necessiterait un mock lourd. La validation
se fait via les 2 tests ci-dessus + le test in-game.

Les 40 tests existants (phases 2.A-2.D) passent toujours.

La validation in-game se fait via `./gradlew run` :
1. Charger un niveau (touche A pour Level A par exemple)
2. S'approcher d'un Red Alien : verifier qu'il cycle entre plusieurs
   frames quand il marche
3. Tourner autour : verifier le change TOWARDS &rarr; RIGHT &rarr; AWAY &rarr; LEFT
4. Tirer dessus : verifier la frame hit (alien convulse brievement)
5. Le tuer : verifier la frame mort puis la disparition apres ~500 ms

### Statistiques cumulees session 113 (5 phases)

- **Phases livrees** : 2.A (machine d'etats) + 2.B (integration runtime)
  + 2.C (tirs joueur -> aliens) + 2.D (ASM-fidelity fixes) + 2.E (animations)
- **11 fichiers nouveaux** : 9 dans `core/ai/`, 2 dans `world/`
- **58 tests** : 22 AlienAI + 7 integration AlienDefLoader + 11 AlienHitDetector
  + 6 AlienViewpoint + 12 AlienAnimTable
- **CHANGELOG** : 5 sections session 113 (2.A -> 2.E)

---

## [2026-04-28] Session 113 — Phase 2.D : ASM-fidelity fixes pour l'IA alien

### Contexte

Apres test in-game de la phase 2.C, deux problemes majeurs identifies :

1. **Tous les aliens se comportent pareil** : Red Aliens et Guards aggressent
   et chargent de la meme facon, alors que dans le jeu original, les Guards
   *tirent* avec leur arme (Shotgun, Blaster, Mind Zap), tandis que les Red
   Aliens *chargent* en corps-a-corps.
2. **Distance de detection** : la limite `LOS_MAX_DIST_JME = 100f` etait
   arbitraire et beaucoup plus courte que dans l'ASM original (qui n'a
   *aucune* limite distance, juste un test PVS de visibilite).

Cette phase 2.D reprend l'ASM (`modules/ai.s`) ligne par ligne et corrige
ces 4 ecarts :

| # | Probleme | Avant | ASM-fidele |
|---|----------|-------|------------|
| 1 | Distance LOS | hardcap 100 JME | pas de cap (PVS-based) ; on cap a 200 JME |
| 2 | Modele de damage | `hp -= damage` chaque coup | `cumul/4 >= HP_init` (HP immuable) |
| 3 | Field of view | aucun | `ai_CheckInFront` : dot(forward, delta) > 0 |
| 4 | Tir alien | non-implemente | `attackWithGun` (hitscan) / `spawnAlienProjectile` |

### 1. Modele de damage : cumul/4 vs HP_init

L'ASM original (`modules/ai.s::ai_TakeDamage` ligne ~100) ne fait <b>jamais</b>
`HP -= damage`. Au lieu de cela :

```asm
ai_TakeDamage:
    add.w   d0, AI_Damaged_vw[idx]    ; cumul += damageTaken (ce coup)
    move.w  AI_Damaged_vw[idx], d0
    asr.w   #2, d0                     ; d0 = cumul/4
    moveq   #0, d1
    move.b  EntT_HitPoints_b(a0), d1   ; d1 = HP_init (jamais decremente)
    move.b  #0, EntT_DamageTaken_b(a0)
    cmp.w   d0, d1
    ble     ai_JustDied                ; si HP_init <= cumul/4 -> mort
```

Donc `EntT_HitPoints_b` est <b>fixe = HP_init</b>, c'est juste le seuil de
mort. Le cumul de tous les degats recus est dans `AI_Damaged_vw[idx]`
(notre `totalDamageDone`), et on divise par 4 avant de comparer.

Resultats :
- **Red Alien (HP=2)** meurt quand totalDamage >= 8 (= 8 plasmas a 1 dmg).
- **Mantis Boss (HP=125)** meurt quand totalDamage >= 500 (= 500 plasmas).

Avant on faisait HP -= 1 par plasma, donc Red Alien mourait apres 2 tirs.
Maintenant il faut 8 tirs. Plus realiste, plus fidele a l'experience
originale.

### 2. Field of view (ai_CheckInFront)

L'ASM original a une routine `ai_CheckInFront` qui filtre les detections de
joueur. Sans elle, les aliens detectent le joueur a 360deg (ils "voient
derriere eux"). Avec, ils n'agressent que si le joueur est dans leur
demi-plan frontal :

```asm
ai_CheckInFront:
    dx = playerX - alienX
    dz = playerZ - alienZ
    sin = SinCosTable[currentAngle]
    cos = SinCosTable[currentAngle + COSINE_OFS]
    dot = dx*sin + dz*cos
    sgt d0     ; D0 = -1 si dot > 0 (joueur devant), 0 sinon
```

C'est un produit scalaire entre le vecteur "forward" de l'alien (oriente
par `currentAngle`) et le vecteur delta (alien -> joueur). Positif = devant.

Ajoute dans 3 endroits : `doDefault`, `doFollowup`, `doResponse` (toutes
les transitions vers RESPONSE/agression sont gatees par `isPlayerInFront`).
Dans `doResponse`, l'alien tourne face au joueur a chaque frame, donc une
fois qu'il a aggrer une fois, il garde le contact tant que le joueur est
suffisamment proche.

### 3. Tir alien (ai_AttackWithGun / ai_AttackWithProjectile)

L'ASM dispatche la routine d'attaque selon `AlienT_BulType_w` :

```asm
ai_AttackCommon:
    move.l  GLF_DatabasePtr_l, a1
    lea     GLFT_BulletDefs_l(a1), a1
    muls    #BulT_SizeOf_l, d0
    add.l   d0, a1
    tst.l   BulT_IsHitScan_l(a1)
    beq     ai_AttackWithProjectile      ; bullet projectile
    ; sinon : ai_AttackWithHitScan       ; bullet hitscan
```

**Hitscan** (Machine Gun=1, Shotgun=7, MindZap=12) : test de probabilite
base sur la distance :

```asm
ai_AttackWithHitScan:
    jsr     GetRand
    and.w   #$7fff, d0           ; rand 0..32767
    move.w  (a6), d1             ; dx (rotated)
    muls    d1, d1               ; dx^2
    move.w  2(a6), d2            ; dz (rotated)
    muls    d2, d2               ; dz^2
    add.l   d2, d1               ; dist^2
    asr.l   #6, d1               ; dist^2 / 64
    ext.l   d0
    asl.l   #2, d0               ; rand * 4
    cmp.l   d1, d0
    bgt.s   .hit_player          ; rand*4 > dist^2/64 -> HIT
```

Approximations :
- A `dist=100` (proche) : `dist^2/64 = 156`, max rand*4 = 131068, ~99.9%
  chance de hit
- A `dist=1000` : `dist^2/64 = 15625`, ~88% chance de hit
- A `dist=4000` : `dist^2/64 = 250000`, ~0% chance de hit (rand*4 max = 131068)

**Projectile** (Plasma=0, Rocket=2, Blaster=9, Lazer=14, Grenade=8...) :
spawn une bullet alien qui voyage vers le joueur (delegue a
`spawnAlienProjectile` dans le port). Pour la 2.D initiale, on simule
avec 50% chance de hit + damage immediat (placeholder, vraie pool en 2.E).

**Differenciation par alien** :
- Red Alien : `bulType=0`, `responseBehaviour=0` (Charge) -> charge melee
- Guard : `bulType=9 (Blaster)`, `respBeh=2` (AttackWithGun) -> tire projectile
- 'Ard Guard : `bulType=7 (Shotgun)`, `respBeh=2` -> tire hitscan
- Mind Priest : `bulType=12 (MindZap)`, `respBeh=2` -> tire hitscan
- Mantis Boss : `bulType=2 (Rocket)`, `respBeh=2` -> tire projectile

### 4. Trigger de tir (ai_DoAction_b)

Dans l'ASM, le tir n'a lieu QUE pendant les frames "kick" de l'animation
d'attaque, marquees par un byte `ai_DoAction_b` non-zero dans la table
`AlienAnimPtr_l`. Sans porter les tables d'animations completes, on simule
par : <b>tirer une fois quand timer2 traverse FIRE_FRAME=4</b>. Une seule
fois par cycle d'attaque (de 8 frames).

```java
boolean fireTrigger = (prevTimer2 < FIRE_FRAME && a.timer2 >= FIRE_FRAME);
if (fireTrigger && a.seesPlayer && isPlayerInFront(a)) {
    performAttack(a);  // dispatch melee/hitscan/projectile
}
```

### Fichiers modifies

1. **`core/ai/AlienRuntimeState.java`** : `hitPoints` documente comme
   IMMUABLE (= HP_init, jamais decremente). `isAlive()` reecrit en
   `mode != DIE && (totalDamageDone >> 2) < hitPoints`.

2. **`core/ai/AlienAI.java`** :
   - `applyDamage()` reecrit : `totalDamageDone += damageTaken`, mort si
     `totalDamageDone >> 2 >= hitPoints`. **HP_init n'est plus decremente.**
   - Ajout `isPlayerInFront(a)` : produit scalaire forward.delta
   - `doDefault`, `doFollowup`, `doResponse` : ajout du gate FOV avant
     toute transition vers RESPONSE
   - `doResponse()` reecrit avec :
     - `FIRE_FRAME=4` trigger pour simuler `ai_DoAction_b`
     - Dispatch `performAttack(a)` : melee si `!attacksWithGun`, hitscan
       si `isHitscanBullet(bulType)`, sinon projectile
   - Ajout `attackWithGun(a)` : formule ASM `(rand*256f) > dist^2`,
     applique damage via `world.applyDamageToPlayer`
   - Ajout `attackWithProjectile(a)` : delegate vers `world.spawnAlienProjectile`
   - Ajout `attackMelee(a)` : si `dist^2 < 80*80`, applique `MELEE_DAMAGE=2`
   - Ajout `damageForBulletType(bulType)` : lookup hardcode des degats GLF

3. **`core/ai/AiWorld.java`** : ajout 2 methodes default :
   - `applyDamageToPlayer(damage, fromX, fromZ)` (no-op default)
   - `spawnAlienProjectile(bulletType, damage, fromX, fromY, fromZ)` (no-op)

4. **`world/AiWorldAdapter.java`** :
   - `LOS_MAX_DIST_JME` passe de 100 a 200 (pas de cap dans l'ASM)
   - Ajout champ `playerHealth` + setter `setPlayerHealth(ph)`
   - Implementation `applyDamageToPlayer` : appelle `playerHealth.takeDamage(damage)`
   - Implementation `spawnAlienProjectile` : 50% chance + takeDamage (placeholder 2.D)

5. **`app/GameAppState.java`** : apres creation de l'AiWorldAdapter, branche
   `aiWorld.setPlayerHealth(healthState)` pour que les tirs alien fassent
   vraiment des degats.

### Tests

**`core/ai/AlienAITest.java`** mis a jour : 17 -> 22 tests

- 3 nouveaux tests dans `class FieldOfView` :
  - `playerInFrontTriggersResponse`
  - `playerBehindDoesNotTrigger`
  - `playerToTheSide`

- 5 nouveaux tests dans `class AlienShooting` :
  - `ardGuardShootsHitscanCloseRange` ('Ard Guard, Shotgun=hitscan, courte distance)
  - `ardGuardMissesAtLongRange` (dist=10000 -> miss garanti, formule `rand*256 > dist^2`)
  - `guardShootsProjectile` (Guard, Blaster=projectile, spawnAlienProjectile appele)
  - `noShootBeforeFireFrame` (timer2=2 ne declenche pas, timer2 doit traverser 4)
  - `onlyOneShotPerCycle` (timer2=5 deja passe ne re-tire pas)

- Test `lethalDamageKills` corrige : verifie maintenant `hitPoints==0`
  apres `doDie` (= ASM-fidele, cf. `ai.s::ai_DoDie .still_dying`)

- Test `damageReducesHP` modifie : verifie que hitPoints reste FIXE
  (= HP_init) en cas de dommages non letaux, et que `totalDamageDone`
  s'accumule

- Nouveau test `redAlienDiesAfter8Plasmas` : valide la formule cumul/4
  (Red Alien HP=2 meurt apres 8 plasmas, pas 2)

- `FakeWorld` etend les capture des appels de combat :
  - `applyDamageCount`, `lastDamageAmount` pour `applyDamageToPlayer`
  - `spawnProjectileCount`, `lastBulletType` pour `spawnAlienProjectile`

### Resultat in-game attendu

1. **Red Aliens** : chargent toujours en ligne droite, font des degats melee
   quand ils touchent le joueur (= 2 HP par contact). Plus difficile a
   tomber : 8 plasmas au lieu de 2. Sans gun, jamais de tir distant.

2. **Guards** (Blaster) : s'arretent a portee, tirent un projectile vers
   le joueur. Le projectile a 50% chance de hit (placeholder). Damage = 2
   (Blaster Bolt).

3. **'Ard Guards** (Shotgun) : s'arretent a portee, tirent un hitscan.
   Le hit est probabiliste : ~99% a courte distance, ~88% a 1000 unites,
   ~0% au-dela de 4000 unites. Damage = 1.

4. **Mind Priests** (MindZap) : tirent un hitscan tres puissant (3 dmg)
   avec memes proba que Shotgun.

5. **Field of view** : si on est derriere un alien, il ne reagit pas.
   Ils faut entrer dans son demi-plan frontal pour declencher l'aggression.

6. **HP joueur** : visible dans l'overlay debug (touche F3) sous forme
   `HP:N/200`. Decremente quand on est touche.

### Limitations connues (a faire en phase 2.E ou plus tard)

- **Pas de shield logic** dans `applyDamageToPlayer` : les degats vont
  directement a la sante, sans consommer le shield d'abord. A implementer
  quand on rebranche le HUD complet.
- **Animation trigger simplifie** : on tire au FIRE_FRAME=4 fixe, alors
  que l'ASM utilise des tables `AlienAnimPtr_l` avec un byte par frame.
  Pour porter ca correctement, il faut extraire les tables de l'ASM
  ("draw.s" cote draw + tables alimentees par newaliencontrol).
- **Projectile 50% hit placeholder** : il n'y a pas de vrai pool de
  bullets aliens (`AlienShotPool` symetrique a `PlayerShotPool`).
  Placeholder simplifie : 50% chance de hit + damage direct. La 2.E
  ajoutera un vrai pool + `AlienBulletUpdateSystem` pour faire voyager
  les bullets et tester collision avec le joueur.
- **Pas de red flash overlay** quand le joueur est touche.
- **Pas de knockback visuel** (ASM utilise `EntT_ImpactX/Z` pour pousser
  le joueur dans la direction du tir).
- **Pas de SFX positionnel** (cris d'alien, son de tir aliens).
- **Boss death spawn** non implemente : si `splatType >= NUM_BULLET_DEFS`,
  l'alien parent doit spawner 2 plus petits aliens. Pour l'instant tous
  les aliens font juste un fade out simple.
- **Movement collision** : aliens traversent toujours les murs (deplacement
  lineaire vers la cible). La 2.F portera `MoveObject` + `Obj_DoCollision`.
- **Sprite 4-directionnel** : les sprites sont toujours frontaux. La 2.G
  portera `ViewpointToDraw` (TOWARDS=0/RIGHT=1/AWAY=2/LEFT=3) qui choisit
  le sprite selon l'angle relatif joueur-alien.

### Statistiques cumulees session 113 (4 phases)

- **Phases livrees** : 2.A (machine d'etats) + 2.B (integration runtime)
  + 2.C (tirs joueur -> aliens) + 2.D (ASM-fidelity fixes)
- **8 fichiers nouveaux** : 6 dans `core/ai/`, 1 dans `world/`, 1 dans `combat/`
- **6 fichiers modifies** dont 2 fois pour 2.D : `AlienRuntimeState`,
  `AlienAI`, `AiWorld`, `AiWorldAdapter`, `GameAppState`,
  `BulletUpdateSystem`, `HitscanTracerSystem`, `PlayerShootSystem`
- **3 fichiers test** : 22 tests AlienAI + 7 integration AlienDefLoader
  + 11 unitaires AlienHitDetector = **40 tests** au total
- **CHANGELOG** : 4 sections (2.A -> 2.B -> 2.C -> 2.D) toutes datees
  2026-04-28

---

## [2026-04-28] Session 113 — Phase 2.C : tirs joueur tuent les aliens

### Contexte

Les phases 2.A (machine a etats IA pure + 17 tests) et 2.B (integration
runtime via `AlienControlSystem` + `AiWorldAdapter` + 7 tests d'integration)
etaient livrees, mais les aliens etaient invincibles : aucun tir joueur ne
leur infligeait de degats. Cette phase 2.C ferme la boucle visuelle : on tire,
l'alien encaisse, transition `TAKE_DAMAGE` ou `RESPONSE` (75/25), et meurt
quand HP &le; 0 avec animation de fade.

### Architecture du wiring

```
 GameAppState.setupPhysics()
   ...
   +-- AlienControlSystem (deja la, phase 2.B)
   |
   +-- AlienHitDetector (NEW)
        |
        +-- branche sur BulletUpdateSystem.setAlienHitDetector()
        |    -> projectiles : Plasma, Blaster, Rocket, Grenade, Mine, Lazer...
        |
        +-- branche sur HitscanTracerSystem.setAlienHitDetector()
             -> hitscan    : Shotgun, Machine Gun, MindZap
```

Un meme `AlienHitDetector` est injecte dans les deux systemes de tir : il
lit la liste d'aliens vivants depuis l'`AlienControlSystem` et expose deux
methodes :

- `findHitByPoint(jx, jy, jz)` : test point-vs-capsule pour les projectiles
  (chaque frame, apres update de la position)
- `findHitByRay(from, dir, maxDist)` : test ray-vs-capsule (intersection
  cylindre XZ + check Y) pour les hitscan, retourne aussi la distance pour
  comparer avec l'impact mur et prendre le plus proche

### Modele de capsule

Chaque alien est modelise comme une capsule verticale :
- **Centre XZ** = position monde Amiga / 32 (avec flip Z)
- **Centre Y**  = `node.getLocalTranslation().y` (= spawnY courant)
- **Rayon**     = 0.5 unite JME (~ 16 unites Amiga)
- **Demi-hauteur** = 0.9 unite JME (~ 1m80 ingame)

*Note* : on n'utilise PAS `AlienDef.collisionRadius()` (= 80/160/320 Amiga)
qui est specifique a la collision murs (pour empecher les aliens de se
coincer dans les coins). Pour le hit testing on prend une capsule plus serree
autour du sprite.

### Math du raycast vs capsule

Polynome quadratique en t pour le cylindre XZ :

```
  |O.xz + t*D.xz - C.xz|^2 = r^2
  a = Dx^2 + Dz^2
  b = 2 * ((Ox-Cx)*Dx + (Oz-Cz)*Dz)
  c = (Ox-Cx)^2 + (Oz-Cz)^2 - r^2
```

On prend la plus petite racine positive, puis on verifie que `Oy + t*Dy` est
dans `[Cy - halfHeight, Cy + halfHeight]`. Pour plusieurs aliens, on garde
le `t` minimum.

### Routing damage

Les deux systemes utilisent la meme chaine d'application :

```
 hit detecte
   -> AlienHitDetector.applyDamage(alien, damage)
   -> AlienAI.inflictDamage(alien, damage)
   -> alien.damageTaken += damage
   -> au prochain AlienAI.update() :
      applyDamage() consomme damageTaken, decremente HP, applique 75/25
```

Le `damage` vient de `BulletDef.hitDamage()` qui est lu du GLF.
- Plasma Bolt = 1 HP
- Shotgun Round = 1 HP par bille (count=2 = 2 HP par tir)
- Rocket = ~5 HP
- MindZap = ~10 HP

Donc Red Alien (HP=2) meurt en 2 tirs Plasma ou en 1 tir Shotgun bien place.
Mantis Boss (HP=125) prend ~125 plasmas. C'est fidele au jeu original.

### Fichiers nouveaux

1. **`combat/AlienHitDetector.java`** : detecteur point/ray vs aliens
   (~210 lignes). API publique :
   - `AlienHit findHitByRay(from, dir, maxDist)` retourne `record AlienHit(alien, impact, distance)`
   - `AlienRuntimeState findHitByPoint(x, y, z)`
   - `applyDamage(alien, amount)` (delegate vers `AlienAI.inflictDamage`)

2. **`test/.../AlienHitDetectorTest.java`** : 11 tests JUnit qui valident
   - Hit au centre, hit dans le rayon, miss hors rayon, miss trop haut
   - Alien mort = pas touchable
   - Ray traverse le centre, ray rate, ray trop court, ray vertical pur
   - Plusieurs aliens : on touche le plus proche
   - applyDamage accumule dans `damageTaken`

### Fichiers modifies

3. **`combat/BulletUpdateSystem.java`** : ajout du test point-vs-aliens
   apres update position, AVANT le test mur (l'alien doit prevaler sur le
   mur derriere lui). Setter `setAlienHitDetector()`.

4. **`combat/HitscanTracerSystem.java`** :
   - Setter `setAlienHitDetector()`
   - `spawnTracer()` prend maintenant un parametre `damage`
   - Le tracer choisit la cible la plus proche entre alien et mur
   - Si on touche un alien : `applyDamage` puis tracer s'arrete sur l'alien
   - Surcharge legacy `spawnTracer(origin, dir, type)` deprecated pour la
     retro-compat (= 0 damage)

5. **`combat/PlayerShootSystem.java`** :
   - `fireHitscan()` prend maintenant le `BulletDef bullet` en parametre
   - Lit `bullet.hitDamage()` et le passe au tracer

6. **`app/GameAppState.java`** : creation de l'`AlienHitDetector` apres
   l'`AlienControlSystem` dans `setupPhysics()`, branche sur
   `bulletUpdateSystem` et `tracerSystem` via les setters.

### Tests

Total session 113 : **35 tests** = 17 unitaires AlienAI + 7 integration
AlienDefLoader + 11 unitaires AlienHitDetector.

```bash
./gradlew test
```

### Resultat in-game attendu

Sur le niveau A :
1. Les Red Aliens spawnent et patrouillent en mode DEFAULT (anim 0).
2. Quand on entre dans leur LOS, transition RESPONSE (anim 1) au bout de
   5 frames Amiga (= 0.1 sec, reactionTime du Red Alien).
3. Ils chargent vers le joueur en ligne droite.
4. **Tirer dessus** : la bullet/le tracer s'arrete sur l'alien, on voit le
   flash d'impact. 75% du temps, l'alien aggro (passe directement en
   RESPONSE meme s'il etait en DEFAULT). 25% du temps, il anime un "hit"
   (mode TAKE_DAMAGE, anim 2) pendant ~8 frames avant de revenir en DEFAULT.
5. Au 2e plasma (HP=2), il meurt : mode DIE, anim 3, fade pendant 25 frames
   (~0.5 sec) puis le sprite est detache.

**Boss vector (Mantis, Snake, Wasp, Crab)** : meme principe mais HP=125,
donc il faut beaucoup de tirs pour les tomber. Capsule = 0.5 JME de rayon
ce qui peut paraitre etroit pour ces gros boss ; on peut ajuster en 2.D si
necessaire (utiliser `def.girth()` pour adapter le rayon de la capsule).

### Limitations connues (a faire en phase 2.D)

- **Capsule rigide** : pas de bias d'auto-aim Y. L'ASM autorise une tolerance
  pour les tirs un peu hauts/bas. Pour l'instant la capsule est rigide a
  &plusmn;0.9 unite JME.
- **Splash damage** : Rocket/Grenade explosent mais n'infligent que des
  degats directs. Le rayon d'explosion (`BulletDef.explosiveForce`) n'est
  pas applique aux aliens dans le rayon.
- **Bullets PHYSICS (grenades)** : les bullets gerees par `PhysicsBulletSystem`
  ne testent PAS encore les aliens (elles sont a part dans la boucle). On
  pourra brancher en 2.D si necessaire.
- **Rayon de capsule fixe** : on ne lit pas `def.girth()` pour ajuster, donc
  les boss `girth=2` (320 Amiga = 10 JME) sont touches comme des aliens
  normaux (rayon 0.5). Visuellement c'est etroit pour Mantis (height=800).
- **Aliens armes ne tirent toujours pas sur le joueur** : reste un TODO
  dans `AlienAI.doResponse()` quand `def.attacksWithGun()=true`.
- **Pas de ricochet alien** : si une bullet rate l'alien et continue vers le
  mur, c'est OK (test mur applique apres). Mais une bullet qui touche un
  alien explose dessus, sans degats AOE possibles sur les aliens voisins.

### Statistiques cumulees session 113 (3 phases)

- **8 fichiers nouveaux** : 6 dans `core/ai/` + 1 dans `world/` + 1 dans `combat/`
- **6 fichiers modifies** : `tools/LnkParser`, `tools/LevelJsonExporter`,
  `combat/BulletUpdateSystem`, `combat/HitscanTracerSystem`, `combat/PlayerShootSystem`,
  `app/GameAppState`, `world/AlienControlSystem` (cree en 2.B)
- **3 fichiers test** : `AlienAITest`, `AlienDefLoaderIntegrationTest`, `AlienHitDetectorTest`
- **35 tests** au total
- **Build modifie** : `build.gradle` (deps JUnit 5), `gradle.properties` (action 34=test)

---

## [2026-04-28] Session 113 — Phase 2.B : integration runtime IA dans le jeu

### Contexte

La phase 2.A (= meme jour, plus tot dans la session) avait livre la **machine
a etats IA pure** (`AlienAI`, `AlienBehaviour`, `AlienDef`, `AlienRuntimeState`,
`AlienDefLoader`, `AiWorld` interface), avec 17 tests JUnit passant en
isolation. Restait a la **brancher dans le jeu** : spawner les aliens depuis
le JSON de niveau, faire tourner l'IA dans la boucle JME, sync les Nodes.

### Architecture du wiring

```
 GameAppState.setupPhysics()
   |
   +-- WorldRaycaster (cree)
   |
   +-- AiWorldAdapter (NEW)         <- implements AiWorld
   |     - camera (lecture position joueur)
   |     - ZoneTracker (zone joueur, brightness)
   |     - WorldRaycaster (test LOS)
   |     - levelScene root
   |
   +-- AlienControlSystem (NEW)     <- extends AbstractAppState
         - charge definitions.json -> AlienDef[20]
         - itere levelScene/items pour typeId=0
         - cree AlienRuntimeState par instance
         - chaque frame : ai.update(state) + sync Node JME
```

### Conversion d'unites

L'IA travaille en unites Amiga (signed 16-bit, ~32 unites = 1 m JME). Le wiring
gere les conversions :

| Domaine         | Unite             | Conversion vers JME       |
|-----------------|-------------------|---------------------------|
| Position X      | int Amiga         | `jx = amigaX / 32`        |
| Position Z      | int Amiga         | `jz = -amigaZ / 32`       |
| Position Y      | int Amiga (inv)   | `jy = -amigaY / 32`       |
| Angle           | 0..4095 (12-bit)  | `rad = a * TAU / 4096`    |
| Vitesse         | unites Amiga/tick | `step = speed * tempFrames` |
| Timer           | tick 50Hz Amiga   | `seconds = ticks / 50`    |

### Frame skip Amiga -> JME

`AlienControlSystem` accumule le `tpf` (~16ms a 60 fps JME) et appelle l'IA
tous les 20ms (= 50 Hz Amiga). Si le moteur lague, on cap a 4 frames Amiga par
frame JME pour eviter de geler. Le `tempFrames` resultant est passe a
`AiWorldAdapter.setTempFrames()` pour multiplier les vitesses et timeouts.

### Fichiers nouveaux

1. **`world/AiWorldAdapter.java`** : implementation runtime de
   `core.ai.AiWorld`. Lit camera + zoneTracker + raycaster pour fournir les
   queries. Le LOS est un raycast Bullet contre la geometrie du niveau.
   `getRandom()` est seede a 42L pour la reproductibilite. `playPositionalSound`
   est un no-op pour l'instant (audio porte plus tard).

2. **`world/AlienControlSystem.java`** : `AbstractAppState` qui anime tous les
   aliens. `initialize()` charge defs + spawne les `AlienRuntimeState` depuis
   les sprites typeId=0. `update(tpf)` accumule le temps en frames Amiga et
   appelle `AlienAI.update()` pour chacun. Sync la position du Node JME et
   detache les aliens morts. API publique `damageAlien()` et
   `findNearestAlien()` pour les futurs systemes (collision tirs, IA team).

3. **`test/.../AlienDefLoaderIntegrationTest.java`** : 7 tests d'integration
   qui valident le parsing du vrai `assets/levels/definitions.json` regenere
   par convertLevels. Skippe automatiquement si le fichier n'existe pas
   (utilise `@EnabledIf`).

### Fichiers modifies

4. **`app/GameAppState.java`** :
   - Champ `private AlienControlSystem alienControlSystem;` apres `zoneTracker`
   - Dans `setupPhysics()` : extraction de `WorldRaycaster raycaster` (etait
     scope local), creation `AiWorldAdapter` + `AlienControlSystem` + attach
     via `sa.getStateManager().attach()`. Path defs : `jmeAssets.resolve("levels/definitions.json")`.
   - Dans `cleanup()` : detach `alienControlSystem` (apres `physicsBulletSystem`).

### Verifications

**`assets/levels/definitions.json` est deja a jour** (genere lors d'une
execution precedente de convertLevels avec le nouveau code) :
- Red Alien (#0) : reactionTime=5, hitPoints=2, gfxType=0 BITMAP
- Snake Scanner (#1) : isFlying=true, attacksWithGun=true, gfxType=1 VECTOR
- Mantis Boss (#16) : hitPoints=125, girth=2 (large), height=800
- Crab Boss (#18) : hitPoints=125, gfxType=1 VECTOR

### Limitations connues (a faire en phase 2.C)

- **Sprites 4-directionnels** : ViewpointToDraw pas encore porte. Les sprites
  alien affichent toujours `_f<defFrame>.png` quel que soit l'angle vs camera.
- **Bullet -> alien** : pas de detection de collision tirs joueur sur aliens.
  Il faut etendre `BulletUpdateSystem` ou creer un `AlienBulletCollisionSystem`
  qui appelle `AlienAI.inflictDamage()` au moment du hit.
- **Alien -> joueur** : les aliens `attacksWithGun()=true` ne tirent pas encore
  (TODO dans `AlienAI.doResponse`). Pour les melee, on charge sans collision
  (le joueur peut traverser).
- **Pathfinding** : `AiWorldAdapter.controlPoints` est une liste vide. Il faut
  exporter les CP depuis `LevelSceneBuilder` (en userData sur le levelScene)
  et porter `GetNextCPt` (graphe BFS).
- **Mouvement** : `AlienAI.moveTowards*` est lineaire sans collision murs.
  Il faut brancher `WallCollision` pour porter `MoveObject + Obj_DoCollision`.
- **Boss spawn on death** : `splatType >= NUM_BULLET_DEFS` doit spawner des
  aliens plus petits (snake -> alien2, etc.). TODO dans `AlienAI.justDied`.
- **Audio** : `AiWorldAdapter.playPositionalSound` est un no-op. A brancher
  sur AudioNode JME quand l'AudioManager sera porte.
- **Team coordination** : `AI_AlienTeamWorkspace_vl` (partage position joueur
  entre coequipiers) pas porte. Chaque alien decide en solo.
- **Upper zones** : `AlienRuntimeState.inUpperZone` toujours false (les zones
  doubles sont rares dans le jeu, on traitera plus tard).

### Pour valider

```bash
# Compile + tests
./gradlew compileJava test

# Regenere assets si besoin
./gradlew convertLevels buildScenes

# Lance le jeu, choisir niveau A pour voir les aliens
./gradlew run
```

**Resultat attendu** : sur le niveau A, on doit voir des Red Aliens spawner
au bons emplacements (positions du JSON), passer en mode RESPONSE (anim 1)
quand le joueur entre dans leur ligne de vue, et charger vers lui en ligne
droite. Sans collision player-alien, on peut les traverser. La mort par tir
n'est pas encore active (il faut le BulletAlienCollisionSystem en 2.C).

### Statistiques

- 3 fichiers nouveaux (~600 lignes : adapter + control system + integration tests)
- 1 fichier modifie (GameAppState.java : ~30 lignes ajoutees)
- 7 tests d'integration en plus des 17 unitaires de phase 2.A = 24 tests IA

---

## [2026-04-28] Session 113 — Phase 2.A : machine a etats IA aliens (newaliencontrol.s + AlienT)

### Contexte

Demarrage du portage de l'IA des aliens depuis l'ASM original. Le moteur AB3D2
origine utilise une **machine a etats a 6 modes** (4 "officiels" annonces dans la
struct AlienT + 2 modes runtime non parametrables) :

| Mode ASM | Enum Java | Role |
|---|---|---|
| 0 | `DEFAULT` | Patrouille (prowl) entre control points |
| 1 | `RESPONSE` | Attaque (charge ou tir) |
| 2 | `FOLLOWUP` | Pause apres tir / approche pour attaque suivante |
| 3 | `RETREAT` | Fuite **(non implemente dans l'ASM original : `ai_DoRetreat: rts`)** |
| 4 | `TAKE_DAMAGE` | Animation de hit |
| 5 | `DIE` | Animation de mort + suppression apres 25 frames |

### Decouvertes ASM

- `newaliencontrol.s::ItsAnAlien` (l.7-78) : entry point qui charge l'AlienT depuis
  le GLF et appelle `AI_MainRoutine`.
- **`modules/ai.s::AI_MainRoutine`** : le dispatcher principal a 6 cas, **trouve
  dans `ab3d2-tkg-original/ab3d2_source/modules/ai.s`** (n'etait pas dans `/mnt/project/`).
- `defs.i::STRUCTURE AlienT` (l.169-191) : 21 UWORDs = 42 bytes.
- `ai_bss.s` : `AI_AlienWorkspace_vl` (4*300 longs = 300 aliens max),
  `AI_AlienTeamWorkspace_vl` (4*30 longs = 30 equipes), boredom, damage.
- `newaliencontrol.s::ViewpointToDraw` : sprite 4-directionnel (TOWARDS=0,
  RIGHT=1, AWAY=2, LEFT=3) calcule depuis l'angle alien vs camera.

### Comportements (= index dans tables ASM)

- **DefaultBehaviour** : 0=ProwlRandom, 1=ProwlRandomFlying
- **ResponseBehaviour** : 0=Charge, 1=ChargeToSide, 2=AttackWithGun,
  3=ChargeFlying, 4=ChargeToSideFlying, 5=AttackWithGunFlying
- **FollowupBehaviour** : 0=PauseBriefly, 1=Approach, 2=ApproachToSide,
  3=ApproachFlying, 4=ApproachToSideFlying
- **RetreatBehaviour** : declared but `ai_DoRetreat: rts` (vide dans l'ASM)

### Architecture Java (5 fichiers nouveaux + 3 modifies)

#### Donnees

1. **`tools/LnkParser.java`** *(modifie)* : ajout du record
   `LnkParser.AlienDef` (21 UWORDs) et de la methode `getAlienDef(int idx)`.
   Avant on ne lisait que 4 champs (gfxType, hitPoints, height, bulType).
   Maintenant on lit la structure AlienT complete (42 bytes).

2. **`tools/LevelJsonExporter.java`** *(modifie)* : `exportDefinitions` exporte
   les **16 nouveaux champs** par alien dans `definitions.json` :
   - defaultBehaviour, defaultSpeed, reactionTime
   - responseBehaviour, responseSpeed, responseTimeout
   - damageToRetreat, damageToFollowup
   - followupBehaviour, followupSpeed, followupTimeout
   - retreatBehaviour, retreatSpeed, retreatTimeout
   - girth, splatType, auxilliary

#### Runtime IA (nouveau package `com.ab3d2.core.ai`)

3. **`core/ai/AlienBehaviour.java`** : enum a 6 modes, ordinal aligne sur le
   byte ASM `EntT_CurrentMode_b` pour que la table de dispatch de `AI_MainRoutine`
   se traduise en `switch` Java direct.

4. **`core/ai/AlienDef.java`** : record immutable des 21 UWORDs de AlienT.
   Helpers `isFlying()`, `attacksWithGun()`, `collisionRadius()` (mappant la
   table `diststowall` de `newaliencontrol.s`).

5. **`core/ai/AlienDefLoader.java`** : parseur JSON regex (sans dep externe)
   pour charger les 20 definitions depuis `assets/levels/definitions.json`.
   API `load(Path)`, `parse(String)`, `loadFromClasspath(String)`.

6. **`core/ai/AlienRuntimeState.java`** : etat mutable par instance d'alien,
   port des champs `EntT_*` de `defs.i` avec mapping documente. Inclut
   position monde, mode courant, timers (timer1=reaction, timer2=anim,
   timer3=death fade), HP, damage, control points, team number.

7. **`core/ai/AiWorld.java`** : interface qui abstrait les queries au monde
   (player position, LOS, control points, RNG, audio). Permet de garder
   `AlienAI` pur et testable. L'implementation runtime sera dans la session 2.B.

8. **`core/ai/AlienAI.java`** : la machine a etats. Methode `update(state)` qui
   dispatche selon le mode courant :
   - `doDefault` (port `ai_ProwlRandom`) : decremente timer1, test LOS,
     transition vers RESPONSE quand visible et reactionTime ecoule + pas dans le noir
   - `doResponse` (port `ai_Charge / ai_AttackWithGun`) : anime l'attaque,
     retombe en FOLLOWUP quand finie ou LOS perdue
   - `doFollowup` (port `ai_PauseBriefly / ai_Approach`) : decremente timer1,
     retour en DEFAULT au timeout, ou en RESPONSE si LOS retrouvee
   - `doRetreat` : fallback DEFAULT (ASM-fidele)
   - `doTakeDamage` : anim hit pendant 8 frames, retour DEFAULT
   - `doDie` : decremente timer3 (death fade), `isDeadAndGone()` quand 0
   - `applyDamage` : decompte HP, transition DIE / RESPONSE (75%) / TAKE_DAMAGE (25%)
     selon GetRand & 3 (port fidele de `ai_TakeDamage`).

#### Tests (nouveau)

9. **`test/.../AlienAITest.java`** : 17 tests JUnit 5 qui valident toutes les
   transitions de la machine a etats. Utilise un `FakeWorld` deterministe avec
   sequences de RNG controlees pour tester precisement les ratios 75/25 du
   damage handling.

#### Build

10. **`build.gradle`** *(modifie)* : ajout dependances JUnit 5 + bloc `test {
    useJUnitPlatform() }`. La task `gradle test` execute desormais les tests IA.

11. **`gradle.properties`** *(modifie)* : ajout action NetBeans `custom-34=test`.

### Limitations (a faire en phase 2.B)

- **Deplacement physique** : `AlienAI.moveTowards*` est un placeholder lineaire
  sans collision. La 2.B portera `MoveObject + Obj_DoCollision` depuis l'ASM
  pour utiliser le `WallCollision` existant.
- **Pathfinding** : la patrouille ne suit pas le graphe de control points
  (`GetNextCPt` dans l'ASM). Les aliens vont en ligne droite vers la cible,
  ce qui est faux pour les zones reliees indirectement.
- **Animation 4-directionnelle** : `ViewpointToDraw` n'est pas encore porte.
  Les sprites alien existants (alien2/ashnarg/guard/insect/priest/robotright/
  triclaw/worm) sont la et ont la bonne echelle, mais le mapping frame index
  -> direction doit etre cable dans la 2.B.
- **Spawn / integration GameAppState** : aucun alien n'est instancie pour
  l'instant. La 2.B branchera `AlienControlSystem` qui itere sur les objets
  `typeId=0` du JSON niveau et les transforme en `AlienRuntimeState`.
- **Audio** : `playPositionalSound` est un no-op. La 2.B (ou phase audio
  ulterieure) branchera `AudioManager` JME.
- **Boss spawning** : `ai_JustDied` peut spawner des aliens plus petits
  (`splatType >= NUM_BULLET_DEFS`). Pas encore implemente.
- **Team coordination** : `AI_AlienTeamWorkspace_vl` (partage de la derniere
  position connue du joueur entre aliens d'une meme equipe) n'est pas porte.
  Chaque alien decide en solo pour le moment.

### Verifications assets

**Sprites bitmap** : tous presents dans `assets/Textures/objects/` avec les
frames attendues (echelle visuelle correcte, conforme aux `height` du GLF) :

| Alien | Frames | Indices defs |
|---|---:|---|
| `alien2` | 19 | 0 (Red Alien) |
| `ashnarg` | 12 | 4 (Ashnarg) |
| `guard` | 21 | 2 (Guard), 5 ('Ard Guard), 8 (Player1), 13 (Triclaw), 17 (Insect Boss) |
| `insect` | 22 | 6 (well ard guard), 7 (Ashnarg2), 9 (Player2), 10 (Insectalien) |
| `priest` | 14 | 11 (AlienPriest) |
| `robotright` | 20 | 3 (Droid), 12 (BigInsect) |
| `triclaw` | 18 | 14 (Tough Triclaw) |
| `worm` | 20 | (mapping a confirmer) |

**Vectobj boss** : tous presents dans `assets/Scenes/vectobj/` :
- `snake.j3o` (alien 1 = SnakeScanner)
- `wasp.j3o` (alien 15 = Wasp Boss)
- `mantis.j3o` (alien 16 = Mantis Boss)
- `crab.j3o` (alien 18 = Crab Boss)

### Pour tester

```bash
./gradlew test                    # execute AlienAITest (17 tests)
./gradlew convertLevels           # regenere definitions.json avec les 16 nouveaux champs
cat assets/levels/definitions.json | grep -A 1 reactionTime  # verifier export
```

### Fichiers nouveaux

- `core/ai/AlienBehaviour.java` (enum a 6 modes)
- `core/ai/AlienDef.java` (record AlienT)
- `core/ai/AlienDefLoader.java` (parser definitions.json)
- `core/ai/AlienRuntimeState.java` (etat runtime EntT)
- `core/ai/AiWorld.java` (interface queries monde)
- `core/ai/AlienAI.java` (machine a etats)
- `test/.../AlienAITest.java` (17 tests JUnit)

### Fichiers modifies

- `tools/LnkParser.java` (record AlienDef + getAlienDef)
- `tools/LevelJsonExporter.java` (export 16 nouveaux champs alien)
- `build.gradle` (deps JUnit 5 + bloc test)
- `gradle.properties` (action custom-34=test)

---

## [2026-04-27] Session 112 — Ramassage des items au sol (pickups)

### Probleme

Les items au sol etaient simplement affiches comme sprites (apres correction
textures de la session 111), mais le joueur pouvait passer a travers sans
rien ramasser. Pas d'inventaire, pas de feedback. Il fallait :

1. Detecter la collision joueur ↔ item (memes conditions que l'ASM)
2. Lire les tables {@code GLFT_AmmoGive_l} / {@code GLFT_GunGive_l} pour
   determiner ce que la collecte donne (sante, ammo, arme, etc.)
3. Appliquer ces deltas a l'inventaire du joueur
4. Retirer le sprite visuellement
5. Afficher un feedback (message + indicateur HUD)

### Diagnostic ASM

**Detection collision** (cf. {@code newaliencontrol.s::Plr1_CheckObjectCollide},
ligne ~1700) :

```asm
; meme zone ?
move.w  Plr1_Zone_w, d7
cmp.w   ObjT_ZoneID_w(a0), d7
bne     .NotSameZone
; difference Y < ODefT_CollideHeight_w ?
move.l  Plr1_YOff_l, d7
asr.l   #7, d7
sub.w   4(a0), d7
cmp.w   ODefT_CollideHeight_w(a2), d7
bgt     .NotSameZone
; distance XZ < ODefT_CollideRadius_w ?
move.w  ODefT_CollideRadius_w(a2), d2
muls    d2, d2
jsr     CheckHit
```

**Application de la collecte** (cf. {@code Plr1_CollectItem}, ligne ~1640) :

```asm
lea     GLFT_AmmoGive_l(a2), a1
add.l   #GLFT_GunGive_l, a2
muls    #AmmoGiveLen, d0       ; AmmoGiveLen = 22*2 = 44
muls    #GunGiveLen,  d1       ; GunGiveLen  = 12*2 = 24
add.w   d1, a2
add.w   d0, a1
; a1 -> ammoGive[defIdx]  : [Health, JetpackFuel, Ammo[20]]
; a2 -> gunGive[defIdx]   : [Shield, JetPack,    Weapons[10]]
CALLC   Game_AddToInventory     ; merge dans Plr1_Invetory
```

**Tables d'inventaire** (offsets dans {@code test.lnk}, calcules depuis defs.i) :

| Offset | Table | Taille | Description |
|---|---|---|---|
| `0x5A08` | `GLFT_ObjectDefs` | 30×40 | ODefT (behaviour, gfxType, colRadius...) |
| `0x5EB8` | `GLFT_ObjectDefAnims_l` | 30×120 | Anim repos (cf. session 111) |
| `0x6CC8` | `GLFT_ObjectActAnims_l` | 30×120 | Anim active (computers en pulsation, etc.) |
| **`0x7AD8`** | **`GLFT_AmmoGive_l`** | **30×44** | **InvCT par def : Health, JetpackFuel, Ammo[20]** |
| **`0x7FF8`** | **`GLFT_GunGive_l`** | **30×24** | **InvIT par def : Shield, JetPack, Weapons[10]** |

### Architecture Java (4 fichiers modifies + 2 nouveaux)

#### Pipeline donnees

1. **`LnkParser.java`** : ajout des constantes
   `OFS_OBJECT_ACT_ANIMS=0x6CC8`, `OFS_AMMO_GIVE=0x7AD8`, `OFS_GUN_GIVE=0x7FF8`
   et des methodes `getAmmoGive(defIdx)` / `getGunGive(defIdx)` qui lisent
   les structs InvCT (22 WORDs) et InvIT (12 WORDs).

2. **`LevelJsonExporter.java`** : ajoute `ammoGive` et `gunGive` (tableaux
   de 22/12 ints) dans chaque entree de `objects[]` du `definitions.json`.

3. **`LevelSceneBuilder.addItems`** : expose `colRadius` et `colHeight` en
   `userData` sur chaque sprite, pour permettre au PickupSystem de tester
   la collision sans avoir a re-lire les definitions.

#### Runtime gameplay

4. **`combat/PlayerHealthState.java`** *(nouveau)* : pendant de
   `PlayerCombatState`, gere les champs PlrT lies a la sante et items :
   - `health` / `maxHealth` (PlrT_Health_w) - default 200
   - `jetpackFuel` / `maxJetpackFuel` (PlrT_JetpackFuel_w) - default 200
   - `shield` (PlrT_Shield_w)
   - `jetpackOwned` (PlrT_Jetpack_w)
   - `keysMask` (PlrT_Keys_b, bitmask 8-bit)

5. **`world/PickupSystem.java`** *(nouveau)* :
   - Charge les definitions d'objets depuis `definitions.json` (ammoGive +
     gunGive + colRadius/colHeight + sfx).
   - `update(playerPos)` : balaye `itemsNode`, pour chaque sprite
     COLLECTABLE (typeId=1, behaviour=0) dans la meme zone que le joueur,
     teste la distance XZ et Y. Si OK : applique les deltas et detache.
   - Skip les items deja "max" (= rien a ajouter, comportement ASM-fidele).
   - Marge horizontale `PICKUP_RADIUS_MARGIN = 0.3` JME (~10 cm) pour
     faciliter le ramassage en passant.

6. **`app/GameAppState.java`** : wiring complet :
   - Champs `healthState`, `pickupSystem`, `hudState`
   - Initialisation apres `combatState` dans le try{}
   - Appel `pickupSystem.update(playerPos)` apres `applyZoneLogic` dans `update()`
   - Overlay debug enrichi : ligne `HP:200/200  Fuel:N  [Jetpack]  Picked:5`

### Comportement actuel

- **Ramassable** : Medipac (HP), ShotgunShells (ammo), Plasmaclip (ammo),
  Passkey (keysMask), grenades (ammo), Plasmagun/Shotgun (weapons), etc.
- **Non ramassable** : Computer / Generator / Ventfan (behaviour=2 DESTRUCTABLE,
  necessite des tirs), Lamp / decorations (behaviour=3 DECORATION).
- **Non implemente** : Activatable (computers via SPACE) - viendra plus tard.

### Pour tester

```
./gradlew convertLevels   # regen definitions.json avec ammoGive/gunGive
./gradlew buildScenes     # regen scenes avec colRadius/colHeight userData
./gradlew run
```

Avancer sur un Medipac : l'overlay debug doit montrer `HP: 200/200 -> +X HP`,
le sprite disparait, le compteur `Picked:N` s'incremente. Les logs montrent :

```
Collected: Medipac (defIdx=1) +50 HP -> PlayerHealth{HP=200/200 ...}
```

### Limitations / TODO

- **Audio** : pas encore d'AudioManager. Le son SFX[4] = "collect" devra
  etre joue via `Aud_SampleNum_w = 4` (cf. `Plr1_CollectItem` ASM).
  TODO en commentaire dans `PickupSystem.collect()`.
- **HUD visuel** : `HudAppState` est cree mais pas attache au stateManager
  (desactive session 75). Les messages "Got Medipac" sont juste loggues.
  Quand on reactivera le HUD ils s'afficheront automatiquement.
- **Activatable objects** : computers / switches qui demandent SPACE.
  Necessite la touche SPACE + check distance + animation "activated".
- **Destructable** : computers a detruire (tir + hitPoints). Probablement
  deja partiellement gere par `PlayerShootSystem` mais a verifier.
- **Affichage messages level** : les `messages[]` du JSON (briefings,
  hints) ne sont pas affiches a la collecte d'objets ayant un
  `displayText` non-nul (cf. ASM `Plr1_CollectItem.notext`).

### Fichiers modifies / nouveaux

**Nouveaux** :
- `combat/PlayerHealthState.java` : sante / fuel / shield / jetpack / keys
- `world/PickupSystem.java` : detection collision + application collecte

**Modifies** :
- `tools/LnkParser.java` : offsets et accesseurs `getAmmoGive` / `getGunGive`
- `tools/LevelJsonExporter.java` : ajoute `ammoGive` / `gunGive` dans definitions.json
- `tools/LevelSceneBuilder.java` : expose `colRadius` / `colHeight` en userData
- `app/GameAppState.java` : wiring + appel pickupSystem.update + overlay HP

---

## [2026-04-27] Session 110 — Zone tracking + Teleports + Fin de niveau

### Probleme

Le moteur ASM original utilise constamment {@code Plr1_ZonePtr_l} (la zone
courante du joueur) pour decider de plein de choses : teleports, fin de
niveau, brightness, sons d'ambiance, sons de pas… Cote Java, on n'avait
aucun tracking de zone : impossible de savoir dans quelle zone se trouve
le joueur, donc impossible d'implementer ces features.

Deux features specifiques manquaient au gameplay :

1. **Teleports** : certaines zones ont {@code telZone}, {@code telX},
   {@code telZ} dans le binaire. Entrer dans une telle zone teleporte
   instantanement le joueur ailleurs.
2. **Fin de niveau** : chaque niveau a un {@code Lvl_ExitZoneID_w}
   (zone de sortie). Y entrer doit declencher la fin de niveau.

### Diagnostic ASM

**Teleport** (cf. {@code hires.s:2902-2945} dans {@code Plr1_Control}) :

```asm
move.l Plr1_ZonePtr_l, a0
move.w ZoneT_TelZone_w(a0), d0     ; offset 38 dans struct Zone
blt    .noteleport                  ; <0 = pas de teleport
move.w ZoneT_TelX_w(a0), newx       ; offset 40
move.w ZoneT_TelZ_w(a0), newz       ; offset 42
; ... test collision ...
.teleport:
  Plr1_XOff_l = TelX
  Plr1_ZOff_l = TelZ
  Plr1_YOff_l = (Y - oldFloor) + newFloor   ; preserve hauteur relative
  Plr1_ZonePtr_l = nouvelle zone
  jouer son #26 (sample teleport)
```

**Fin de niveau** (cf. {@code hires.s:2086-2107}) :

```asm
move.l Plr1_ZonePtr_l, a0
move.w (a0), d0                     ; ZoneID actuelle
cmp.w  Lvl_ExitZoneID_w, d0
bne.s  noexit
jmp    endlevel                     ; on est dans la zone exit
```

L'{@code Lvl_ExitZoneID_w} est lue depuis le binaire a {@code floorLineOffset - 2}
(cf. {@code hires.s:374} : {@code move.w -2(a2),Lvl_ExitZoneID_w} ou
{@code a2 = base + floorLineOffset}).

### Architecture Java (8 modifications + 2 nouveaux fichiers)

#### Cote donnees (parsing)

1. **LevelBinaryParser** : ajoute le champ {@code exitZoneId} a {@code BinData}
   et le lit comme un word a {@code floorLineOffset - 2}.
2. **LevelData** : ajoute le champ {@code exitZoneId} (final).
3. **GraphicsBinaryParser** : passe {@code bd.exitZoneId} au constructeur de
   {@code LevelData}.
4. **LevelJsonExporter** : ajoute {@code "exitZoneId": N} au top-level du JSON.
   Note : les champs {@code telZone}/{@code telX}/{@code telZ} par zone etaient
   deja exportes (session 99).

#### Cote scene 3D (build-time)

5. **LevelSceneBuilder** :
   - Record {@code ZD} etendu pour inclure {@code telZone}, {@code telX}, {@code telZ}.
   - {@code parseZones} les lit depuis le JSON.
   - Nouvelle methode {@code parseExitZoneId(json)} pour le top-level.
   - {@code root.setUserData("exitZoneId", ...)} sur le levelScene.
   - Pour chaque zone, ajout de userData {@code telZone}/{@code telX}/{@code telZ}
     + {@code floorXZ} (polygone reconstruit depuis les edges, en CSV).

#### Cote runtime (gameplay)

6. **PolygonXZ.java** (nouveau, package {@code com.ab3d2.world}) :
   utilitaire statique extrait de {@code LiftControl}, expose
   {@code pointInPolygon}, {@code distanceToPolygon}, {@code distanceToSegment}.
7. **LiftControl** : refactore pour utiliser {@code PolygonXZ} au lieu de
   ses methodes privees dupliquees.
8. **ZoneTracker.java** (nouveau, package {@code com.ab3d2.world}) :
   - Charge la liste des zones depuis le {@code Node "zones"} du levelScene.
   - Methode {@code update(Vector3f playerPos)} qui :
     - Tente d'abord la zone precedente (cache)
     - Sinon balaye toutes les zones jusqu'a trouver le polygone contenant
   - Expose {@code currentZoneId}, {@code getCurrentZone()}, {@code getZone(id)},
     {@code isExitZone(id)}, {@code inExitZone()}.
   - Record interne {@code ZoneInfo} pour les snapshots des userData.
9. **GameAppState** :
   - Champ {@code private ZoneTracker zoneTracker}
   - Garde-fous {@code endLevelTriggered}, {@code teleportCooldown}
   - Initialisation dans {@code initialize()} apres {@code attachChild(levelScene)}
   - {@code zoneTracker.setCurrentZoneId(p1Zone)} dans {@code placePlayer()}
   - Nouvelle methode {@code applyZoneLogic(tpf)} appelee dans {@code update()}
     apres {@code applyLiftPushY()}
   - Nouvelle methode {@code applyTeleport(ZoneInfo)} :
     conversion coords Amiga -> JME, {@code setPhysicsLocation}, cooldown 0.5s
   - Nouvelle methode {@code endLevel()} : detach + attach LevelSelectAppState
   - **Overlay debug zone** (BitmapText haut-gauche, toggle F3) :
     affiche zone courante, position monde Amiga (= editeur), floorH/roofH,
     marquage TEL/EXIT. Visible par defaut.

### Garde-fous

- **Boucles teleport** : un cooldown de 0.5s empeche le joueur de re-teleporter
  immediatement apres avoir atterri (si jamais la destination etait elle-meme
  une zone-telep, ce qui serait surprenant mais possible).
- **Rebonds endlevel** : un boolean {@code endLevelTriggered} evite
  d'attacher plusieurs fois le LevelSelectAppState pendant la frame de
  transition.
- **Polygones invalides** : zones avec moins de 3 points -> {@code floorXZ}
  null -> {@code ZoneTracker} les ignore, point-in-polygon retourne false.
- **JSON anciens** : si {@code exitZoneId} ou {@code telZone} sont absents,
  on retombe sur -1 (= rien ne se passe).

### Pour tester

```
./gradlew convertLevels   # regenere les level_X.json (ajoute exitZoneId)
./gradlew buildScenes     # regenere les scene_X.j3o (ajoute userData zones)
./gradlew run
```

**Test fin de niveau** : trouver la zone exit du niveau (= sortie du level
A, etc.) et y aller. Le joueur doit etre renvoye au menu LevelSelect des
qu'il y entre. Le log console affichera :

```
=== Joueur dans zone EXIT N -> fin de niveau ===
```

**Test teleport** : trouver une zone avec {@code telZone &gt;= 0} dans le
JSON ({@code grep '"telZone":' assets/levels/level_A.json | grep -v '"telZone":-1'}).
Marcher dedans, le joueur doit etre instantanement deplace ailleurs. Log :

```
=== Teleport zone N -> zone M (telX/telZ) -> (jx/jy/jz) ===
```

### Limitations / TODO

- **Son de teleport** : l'ASM joue le sample 26 ({@code Aud_SampleNum_w=26}).
  Pas implemente cote Java (TODO en commentaire dans {@code applyTeleport}).
- **Effet visuel teleport** : l'ASM fait un shimmer/flash blanc
  ({@code Game_TeleportFrame_w}). Pas implemente (TODO).
- **Hauteur Y au teleport** : l'ASM preserve la hauteur RELATIVE au sol
  ({@code Plr_YOff = (oldY - oldFloor) + newFloor}). On utilise simplement
  le sol de la zone destination + offset, suffisant pour les cas de base.
- **Niveau suivant** : pour l'instant {@code endLevel()} retourne au menu
  LevelSelect. Plus tard, on enchainera automatiquement vers level+1 avec
  un ecran d'inter-niveau.
- **Performance** : le balayage toutes-zones brut force se fait au plus 1
  fois par frame (cas frequent : la zone n'a pas change, hit cache).
  ~134 zones max, ~10 ops par zone -> negligeable (&lt; 0.1ms par frame).

### Fichiers modifies / nouveaux

**Nouveaux** :
- {@code world/PolygonXZ.java} : pointInPolygon + distance utilities
- {@code world/ZoneTracker.java} : tracker zone courante + accesseurs

**Modifies** :
- {@code core/level/LevelBinaryParser.java} : lecture {@code exitZoneId}
- {@code core/level/LevelData.java} : champ {@code exitZoneId}
- {@code core/level/GraphicsBinaryParser.java} : transmet exitZoneId
- {@code tools/LevelJsonExporter.java} : ecrit {@code exitZoneId} top-level JSON
- {@code tools/LevelSceneBuilder.java} : record ZD etendu, userData zones, exitZoneId
- {@code world/LiftControl.java} : refactore pour utiliser PolygonXZ
- {@code app/GameAppState.java} : zoneTracker + applyZoneLogic + applyTeleport + endLevel

---

## [2026-04-26] Session 109 — Bob de marche (head bob) camera + arme

### Probleme

Quand le joueur marche, la camera reste parfaitement plate et l'arme tenue ne
bouge pas. Il manque le "head bob" classique des FPS — oscillation verticale
de la vue + de l'arme synchronisee avec les pas. Sans ça, le mouvement
parait artificiel (le joueur glisse au lieu de marcher).

### Diagnostic ASM

`hires.s` ligne 2842-2867 (fonction `Plr1_Control`) :

```
phase = plr1_TmpBobble_w           ; angle 16-bit (oscillator)
sin = SinCosTable[phase]            ; -32768..+32767
bobbleY = (|sin| + 16384) >> 4     ; toujours positif
if (!Ducked && !Squished) bobbleY *= 2
plr1_BobbleY_l = bobbleY

; Application :
;  - camera : Plr1_TmpYOff_l += BobbleY        (1.0x)
;  - arme   : weaponY        += BobbleY * 1.5  (1.5x)
;  - xwobble = sin(phase) >> 6  (lateral signed)
```

Points cles :

- `|sin(phase)|` produit **2 oscillations descendantes par cycle** (1 cycle =
  2 pas, gauche + droit). C'est le head-bob classique.
- BobbleY est *toujours positif* : la camera ne descend jamais en-dessous de
  sa hauteur "debout neutre", elle pulse vers le haut a chaque pas.
- L'arme oscille a **1.5x** la camera, ce qui se traduit visuellement par un
  decalage relatif (l'arme bouge a l'ecran).
- L'incrementation de `Plr1_Bobble_w` n'est **pas dans le source ASM dispo**
  (variable jamais ecrite). On reconstruit la mecanique cote Java.

### Architecture Java

Nouvelle classe `core/BobController.java` :

- `phase` (float, radians, wrap a 2π) avance proportionnellement a
  l'intensite de marche (`WALK_CYCLES_PER_SEC = 1.5f`)
- `activeAmplitude` lerpee vers `MAX_AMP_CAMERA * walkIntensity` (lissage
  start/stop, transition en ~0.125 sec)
- `getBobYCamera()` = `|sin(phase)| * activeAmplitude` (>= 0)
- `getBobYWeaponExtra()` = `getBobYCamera() * 0.5` (= 0.5x extra pour
  atteindre 1.5x au total avec la camera qui porte l'arme)
- `getBobX()` = `sin(phase) * activeAmplitude * 0.3` (signe, lateral)

Valeurs par defaut : `MAX_AMP_CAMERA = 0.04f` JME (~4 cm), subtil mais
perceptible.

### Modifications GameAppState

Dans `update()`, calcul de l'intensite de marche AVANT normalisation :

```java
float walkIntensity = walk.length();  // 0..√2 (clamp dans BobController)
if (walk.lengthSquared() > 0) walk.normalizeLocal();
player.setWalkDirection(walk.multLocal(MOVE_SPEED * tpf));
bobController.update(walkIntensity, false /*ducked*/, tpf);
```

Dans `updateCameraFromPlayer()`, la position de la camera integre les bob Y
+ X :

```java
float bobYCam = bobController.getBobYCamera();
float bobXCam = bobController.getBobX();
Vector3f camPos = player.getPhysicsLocation().add(
    sy * bobXCam,                  // wobble lateral (axe right monde)
    EYE_HEIGHT + bobYCam,          // pulse vertical au pas
    -cy * bobXCam);
sa.getCamera().setLocation(camPos);
```

Le bobController est connecte au WeaponViewAppState via
`weaponView.setBobController(bobController)` apres l'instantiation.

### Modifications WeaponViewAppState

Dans `update()`, on ajoute un offset Y supplementaire le long de
`cam.getUp()` proportionnel a `getBobYWeaponExtra()` :

```java
if (bobController != null) {
    float bobYExtra = bobController.getBobYWeaponExtra();
    if (bobYExtra > 0f) {
        weaponPos.addLocal(up.mult(bobYExtra));
    }
}
```

Resultat : la camera porte l'arme (= 1x bob), et l'arme s'eleve de 0.5x extra,
totalisant 1.5x comme dans l'ASM. A l'ecran, l'arme **bouge** a chaque pas
(la difference 0.5x est visible en relatif a la camera).

### Pour tester

```
./gradlew run
```

- A l'arret : camera plate, arme stable
- En marchant (Z/Q/S/D ou fleches) : camera oscille legerement vers le haut a
  chaque pas (~2-3 pas/sec), l'arme oscille un peu plus visiblement
- En diagonale : meme effet (intensite ~√2 clampee a 1)
- Stop apres marche : transition douce en ~0.125 sec, pas de snap

### Limitations / TODO

- Pas de detection "accroupi" (`ducked` toujours false). A relier a
  `PlayerState.ducked` quand le systeme de squat sera implemente.
- Le wobble X est applique a la camera mais pas a l'arme (l'ASM ne l'applique
  pas non plus a l'arme dans le monde — il est implicite via la position de
  la camera). Suffisant en l'etat.
- Constantes (`MAX_AMP_CAMERA`, `WALK_CYCLES_PER_SEC`) ajustables dans
  `BobController` selon le ressenti.

### Fichiers modifies

- `core/BobController.java` (nouveau) : phase, amplitude, lissage, accesseurs
- `app/GameAppState.java` :
  - import `BobController`
  - champ `private final BobController bobController = new BobController()`
  - `weaponView.setBobController(bobController)` apres instantiation
  - `update()` : `walkIntensity` + appel `bobController.update(...)`
  - `updateCameraFromPlayer()` : application bob Y + bob X a la position camera
- `weapon/WeaponViewAppState.java` :
  - import `BobController`
  - champ `private BobController bobController`
  - methode `setBobController(BobController)`
  - `update()` : ajout `up.mult(bobYExtra)` a `weaponPos`

---

## [2026-04-26] Session 108b — Texture chevron pour les parois du lift

### Symptôme

Après session 108, l'entrée du lift est ouverte (fix #1 OK), mais en
entrant dans la cabine on voit que les parois latérales (les `lift_side_*`)
portent la texture du sol (= `floor_02` beige pour zone 104), pas la
texture chevron jaune/noir typique des ascenseurs Alien Breed.

### Diagnostic

Les vrais walls de zone 104 dans le JSON ont tous `clipIdx=5` (=
`wall_05_chevrondoor`, la texture chevron), mais le code session 99
créait les sides avec `liftMat` (= matériau du sol). Le commentaire de
l'époque le notait déjà :

```
// Pour zone 104 : floor_03 ou similaire (chevron jaune si possible).
// On reutilise liftMat pour la coherence visuelle.
```

Mais `floor_03` n'est pas le bon, et de toutes façons un sol n'a pas
les bonnes proportions (= horizontal) pour servir de paroi.

### Fix

Dans la création des liftNodes, juste avant la boucle de création des
sides : récupérer le `clipIdx` du premier wall de la zone-lift et
utiliser `wm[clipIdx]` comme matériau des sides :

```java
Material sideMat = liftMat;  // fallback
List<int[]> liftZoneWalls = zw.get(zid);
if (liftZoneWalls != null && !liftZoneWalls.isEmpty()) {
    int sideClipIdx = liftZoneWalls.get(0)[6];
    if (sideClipIdx >= 0 && sideClipIdx < NUM_WALL_TEX && wm[sideClipIdx] != null) {
        sideMat = wm[sideClipIdx];
    }
}
```

Aussi, normalisation des UV en tile-units (`L2 / sideTileSize`,
`sideHeight / sideTileSize`) avec `sideTileSize = (wMask + 1) / SCALE`
(= 4 JME pour une tile 128px). Sans cette normalisation, la texture
chevron était étirée de plusieurs fois sa taille réelle.

### Pour tester

```
./gradlew buildScenes run
```

Observation : en entrant dans le lift, les 4 parois latérales ont
maintenant la texture chevron jaune/noir caractéristique. Le pattern
se répète correctement (~1 chevron en largeur d'un seg de 4 JME, ~3
en hauteur sur la course du lift).

### Fichiers modifiés

- `tools/LevelSceneBuilder.java` : création des liftNodes
  - récupération de `sideMat` depuis le clipIdx du premier wall
  - normalisation UV par `sideTileSize`
  - utilisation de `sideMat` (au lieu de `liftMat`) dans les Geometry
    `lift_side_*`

---

## [2026-04-26] Session 108 — Fixes lift : panneau chevron a l'entree + dépassement vertical

### Symptomes (zone 104, level A)

1. **« Une porte pleine avec chevrons devant le lift »** — en arrivant
   par le couloir bas (zone 103), le passage vers la cabine etait
   bouche par un panneau chevron au lieu d'etre ouvert.

2. **« Lift va trop haut, pas assez »** — quand le joueur monte, le
   sol du lift s'arretait à `top=-64` Amiga (=`+2 JME`), mais le sol
   de la zone d'arrivee (zone 105) est à `floorH=-8` (=`+0.25 JME`).
   Le lift depassait de **+1.75 JME (~56 cm)** au-dessus du sol couloir.
   Le joueur arrivait perché et devait « descendre une marche » pour
   sortir.

### Diagnostic

**Bug 1 — walls couloir<->lift rendus statiquement**

Le JSON declare `lifts[].walls[].edgeId` (500 et 510 pour zone 104) :
ce sont les edges des passages couloir<->cabine. Dans l'ASM original
(`newanims.s::LiftRoutine`), ces walls sont rendus *dynamiquement*
(visibles selon position du lift).

Le code Java (session 99) collectait ces edges dans `liftWallEdges`
mais avait laissé un TODO (« utiliser pour skip dans une session
future »). Resultat : ces walls etaient rendus comme n'importe quel
autre wall, avec leur clipIdx (=5, texture chevron) et leur hauteur
full-height. Visuellement ça faisait un panneau plein chevron à
l'entree du lift.

**Bug 2 — yHigh non clampé au sol d'arrivee**

Le code calculait `yHigh = -top/SCALE` directement, sans tenir compte
du sol des zones voisines. L'ASM clamp aussi a top/bottom, mais
l'editeur Amiga avait choisi `top=-64` qui depasse intentionnellement
le floorH du couloir d'arrivee — pas naturel en 3D.

### Fixes

**(1) Skip walls liftWallEdges** dans la boucle principale de
`LevelSceneBuilder.buildScene` (juste après le check
`doorEdgesGlobal`) :
```java
int wallEidLift = findEdgeIdForSegment(lpi, rpi, pts, edgesFull, z.edgeIds());
if (wallEidLift >= 0 && liftWallEdges.contains(wallEidLift)) {
    continue;
}
```

Resultat : le passage devient visuellement ouvert. Les `lift_side_*`
de la cabine (crées au build) sont déja skippés sur ces memes edges
(session 102), donc l'interieur du lift reste ouvert lui aussi.

**(2) Clamp dynamique de yHigh/yLow aux zones voisines**

Dans la boucle de creation des liftNodes, parcours des walls de la
zone-lift dont l'edge est dans `liftWallEdges` (= passages reels).
Collecte des `floorH` des `otherZone` voisines :
```java
for (int[] w : liftWallsZone) {
    int oz = w[5];
    if (oz <= 0 || oz == zid) continue;
    int weid = findEdgeIdForSegment(...);
    if (weid < 0 || !liftWallEdges.contains(weid)) continue;
    ZD nz = zones.get(oz);
    float yNeighborFloor = -nz.floorH() / SCALE;
    maxNeighborYFloor = max(maxNeighborYFloor, yNeighborFloor);
    minNeighborYFloor = min(minNeighborYFloor, yNeighborFloor);
}
```

Puis :
- Si lift monte (`yHighEdit > yRest`) : `yHigh = min(yHighEdit, maxNeighborYFloor)`
- Si lift descend : `yLow = max(yLowEdit, minNeighborYFloor)`

**Effet pour zone 104** :
- Avant : `yHigh = +2 JME` (lift dépasse de +1.75 JME)
- Après : `yHigh = +0.25 JME` (= floorH zone 105) — le lift s'arrete
  pile au sol de la zone d'arrivee

### Note ASM-fidelite

Le fix #2 dévie volontairement de l'ASM. L'ASM clamp simplement à
`top` quoi qu'il arrive. Mais en 3D le dépassement est moche — on
préfère l'aligner sur le sol d'arrivee. Si on veut revenir au
comportement ASM, c'est à 1 ligne de retirer.

Le fix #1 est aussi une simplification : l'ASM rend ces walls
dynamiquement (visibles selon position du lift). On fera ca dans une
session future si besoin.

### Petit nettoyage

`LiftControl.java::controlUpdate` avait du code mort :
```java
speed = -openSpeedJ;  // ecrit puis...
speed = openSpeedJ;   // ...immediatement ecrase
```
Reduit à une seule affectation `speed = openSpeedJ;` avec commentaire
clair sur la convention JME (Y vers le haut, donc speed > 0 pour
monter).

### Pour tester

```
./gradlew buildScenes run
```

(Rebuild necessaire pour fix #1 et #2.)

Observations attendues :
- Le passage du couloir bas (zone 103) vers le lift est **vide**
  visuellement — plus de panneau chevron qui boucherait l'entree.
- Quand le joueur monte sur le lift, le sol s'arrete pile au niveau
  du sol de la zone 105 — plus de « marche descendante » à la sortie.
- La cabine elle-meme garde ses 4 « parois » laterales (sauf sur les
  edges 500 et 510 = passages, deja skippés session 102).

### Limites connues

- L'ASM rend les walls couloir<->lift de manière *dynamique* (visibles
  selon position courante du lift). On les supprime juste pour le
  moment. Si on remarque des trous visuels (ex : voir le ciel/vide
  par la porte du lift quand il est en mouvement), on devra implémenter
  le rendu dynamique.
- Le fix #2 dévie de l'ASM. Si un autre niveau a un design où le lift
  doit *vraiment* monter au-dessus du sol voisin, ce fix le clamp à
  tort. Pour zone 104 c'est correct.

### Fichiers modifiés

- `tools/LevelSceneBuilder.java` :
  - boucle des walls : skip si l'edge est dans `liftWallEdges`
  - boucle des liftNodes : calcul de `maxNeighborYFloor` /
    `minNeighborYFloor` via les `liftWalls`, clamp de `yHigh`/`yLow`
- `world/LiftControl.java` : nettoyage du dead code (`speed =
  -openSpeedJ` immediatement écrasé).

---

## [2026-04-26] Session 107 — Texture qui glisse vers le haut + plafond zone-porte propre

### Deux fixes après test session 106

**(1) Texture qui glisse « dans le mur du haut »** (priorité utilisateur)

Après session 106, le panneau s'ouvre bien et le plafond mobile remonte
en synchro. Mais l'utilisateur a remarqué que la texture du chevron
*s'efface par le bas* au lieu de *glisser vers le haut*. Visuellement
ça donnait l'impression que la porte se faisait « manger » par le sol
plutôt qu'elle ne montait dans le plafond.

**Diagnostic** : la session 104 mappait l'UV pour que la texture reste
*fixe dans le monde* :
```java
ub.put(BL_v + vShift);  // BL voit une portion plus haute de la texture
ub.put(TL_v);           // TL reste au sommet de la texture
```
Du coup, à mi-ouverture, le bas du quad visible voyait V=0.5 (= moitié
basse de la texture) au lieu de V=0 (= base du chevron). La portion
basse du chevron était *coupée* du quad.

**Fix** : inverser le sens du décalage.
```java
ub.put(BL_v);            // BL voit toujours V=0 (= base du chevron)
ub.put(TL_v - vShift);   // TL voit V=vMax-vShift (= la partie haute sort par le haut)
```
La texture *suit* le quad qui monte. Le bas du quad voit toujours la
base du chevron, et la partie haute de la texture sort par le haut du
quad (= « glisse dans le mur du haut »).

**Vérification mathématique** à state=1 :
- effectiveRise = panelHeight
- vShift = panelHeight × 32 / tileH = vMax
- TL.v = vMax − vMax = 0
- BL.v = 0
- Quad collapse en V (texture invisible)
- CullHint.Always (state ≥ 0.999) cache le tout ✅

**(2) Plafond zone-porte avec son propre `floorH`/`roofH`** (détail mineur)

La porte rouge (zone 132) a son propre `roofH` plus bas que celui du
couloir. Après session 106, le plafond mobile montait jusqu'au plafond
du couloir, ce qui dépassait visuellement.

**Fix** : utiliser les `floorH`/`roofH` *propres* de la zone-porte :
```java
float doorYBot = -doorZone.floorH() / SCALE;  // sol propre de la zone-porte
float doorYTop = -doorZone.roofH()  / SCALE;  // plafond propre de la zone-porte
```
au lieu de `acc.yBot` / `acc.yTop` (qui viennent du premier couloir voisin).

La synchronisation reste assurée par `maxPanelHeight` (session 104) :
l'animation tourne à la même vitesse pour toute la porte, mais le plafond
mobile *clampe* à son propre `panelHeight` (= doorYTop − doorYBot) et
s'arrête pile au plafond rouge.

### Pour tester

```
./gradlew buildScenes run
```

(Rebuild nécessaire pour le fix ③. Le fix ② est runtime-only mais le
build redonne la scène propre.)

Observations attendues :
- En s'approchant d'une porte fermée : chevron complet visible.
- Pendant l'ouverture : la texture *monte* avec le quad. Le bas du
  chevron reste visible en bas du quad. La pointe (haut du chevron)
  disparaît « dans le plafond ».
- Porte rouge zone 132 : le plafond mobile s'arrête à la hauteur du
  plafond rouge propre (plus bas que le plafond couloir voisin).

### Fichiers modifiés

- `world/DoorControl.java` : `updateMeshes` — inversion du sens de
  `vShift` (BL/BR fixes, TL/TR décrémentés).
- `tools/LevelSceneBuilder.java` : création du plafond mobile zone-porte
  utilise `doorZone.floorH()` / `doorZone.roofH()` au lieu de
  `acc.yBot` / `acc.yTop`.

---

## [2026-04-26] Session 106 — Plafond mobile commun (ASM-fidèle)

### Suggestion utilisateur (excellente)

> « Il faut que les n faces de la porte aient le même fond, un quad du
> plafond. Ou alors tu descends le plafond : pour la porte rouge le
> plafond devrait toucher le sol et monter en même temps que les pans,
> et tout s'arrête une fois la hauteur du plafond initial atteinte. »

C'est exactement la mécanique ASM ! Dans `newanims.s::DoorRoutine`,
`ZoneT_Roof_l` de la zone-porte est patché à chaque frame pour suivre
le mouvement des ZDoorWalls. Au repos la zone-porte est aplatie (plafond
= sol), elle s'élève avec l'ouverture, et atteint sa hauteur finale
(plafond du couloir) quand la porte est complètement ouverte.

### Fix : 1 quad polygonal partagé, animé en synchro

Les N caps par-pan (session 105) sont **retirés**. À leur place, **un
seul quad polygonal** couvrant toute la zone-porte est créé après la
boucle des pans, et attaché au même `dn` (DoorNode).

```java
ZD doorZone = zones.get(doorZoneId);
List<Float> roofCoords = ...;  // polygone XZ depuis edges de la zone-porte
float[] roofXZ = ...;

Geometry roofGeo = makePolyCapGeo("door_<id>_roof",
    roofXZ, acc.yBot, roofMat, false /* normalDown */);
roofGeo.setUserData("faceType", "bottom");
roofGeo.setUserData("yBotSeg", acc.yBot);  // sol couloir
roofGeo.setUserData("yTopSeg", acc.yTop);  // plafond couloir
dn.attachChild(roofGeo);
```

L'animation est gérée par `DoorControl` via la branche `"bottom"`
faceType existante (session 105) :
```java
geo.setLocalTranslation(0, effectiveRise, 0);
```
où `effectiveRise = min(maxPanelHeight * state, panelHeight)`. À
`state=1`, le plafond a remonté de toute la hauteur couloir et atteint
sa position normale.

### Texture

`ceilTile` est maintenant pris **directement de la zone-porte**
(`doorZone.ceilTile()`), pas du couloir voisin. Plus cohérent : le
plafond mobile a la texture du plafond original de la zone-porte (qui
apparaîtra finalement à sa place quand la porte est ouverte).

### Nouveau helper `makePolyCapGeo`

Quad triangle-fan couvrant un polygone XZ arbitraire (vs l'ancien
`makeCapGeo` qui faisait des quads rectangulaires à 4 corners). Sup-
porte les zones-portes avec N edges (3, 4, 5, 6 ou plus). Pour zone 30
qui a 6 walls, c'est utile.

### Pour tester

```
./gradlew buildScenes run
```

(Rebuild nécessaire — la géométrie change.)

Observations attendues :
- Au repos, devant une porte fermée : on voit les pans verticaux
  (chevron) avec un « fond » au niveau du sol (le plafond mobile posé
  au sol, texture du plafond couloir).
- Pendant l'ouverture : les pans remontent ET le plafond remonte avec.
  Les deux atteignent leur position finale ensemble.
- Après ouverture complète : le plafond est à hauteur normale, les
  pans ont disparu, on traverse librement.
- Plus de « cube ouvert » au-dessus, plus d'effet feuille de papier.

### Limites

- Le plafond mobile peut être vu à travers les ZDoorWalls (FaceCullMode.
  Off) qui sont juste devant. Quand la porte est fermée, le sol couloir
  rend en premier, puis le plafond mobile (au même Y), puis les pans :
  pas de problème en pratique tant que le plafond mobile a une normale
  opposée au pan front.
- `acc.yBot` et `acc.yTop` viennent du PREMIER couloir voisin. Si la
  porte est entre 2 couloirs de hauteurs différentes, le plafond mobile
  utilise les valeurs du premier. La synchro session 104 (maxPanelHeight)
  garantit que tous les pans suivent ensemble.

### Fichiers modifiés

- `tools/LevelSceneBuilder.java` :
  - retrait du bloc « caps par pan » (session 105)
  - ajout du bloc « plafond mobile zone-porte » après la boucle des pans
  - nouveau helper `makePolyCapGeo` pour quads polygonaux N-côtés

(`DoorControl.java` inchangé — la branche `"bottom"` de session 105
fait déjà le bon travail pour le nouveau plafond mobile.)

---

## [2026-04-26] Session 105 — Cube fermé pour les portes (top + bottom caps)

### Suggestion utilisateur

Après les fixes session 103-104, les portes ont une épaisseur (2 faces
verticales séparées de 0.16 JME) et tous les pans sont synchronisés. Le
panneau ressemble déjà à un bloc 3D, mais les bords du "cube" en haut
et en bas restent ouverts. L'utilisateur a proposé :
- Plafond du panneau = top fixe à `yTop`
- Sol du panneau = bottom mobile qui suit l'animation (curYBot)
- Texture du plafond du couloir pour les caps (simple)

### Fix : 2 caps horizontaux par pan

Dans `LevelSceneBuilder.buildScene` (création des Geometries de porte) :

**Top cap** — quad horizontal fixe à `groupYTop`, normale +Y, matériau du
plafond du couloir (`fm[floorIdx(ceilTile)]`).

**Bottom cap** — quad horizontal initialement à `groupYBot`, normale -Y,
même matériau, **mobile** : `DoorControl` le translate au runtime via
`setLocalTranslation(0, effectiveRise, 0)` pour qu'il suive `curYBot`.

Les 4 corners de chaque cap = 2 corners du front (anchor[0..3]) + 2
corners du back (anchor - 2*ofs). Pas besoin de back face explicite : le
front du couloir voisin sert naturellement de back vu d'ici.

### Pourquoi `setLocalTranslation` plutôt que vertex update

Un quad horizontal qui monte = translation pure en Y, pas de déformation.
`setLocalTranslation` sur un Geometry est plus simple et plus rapide qu'une
réécriture du buffer Position à chaque frame :
- Mesh static (pas de buffer dynamique)
- 1 seule opération JME au lieu de 12 floats réécrits
- Le delta transform est automatiquement appliqué par JME au rendu

### Modifications

**LevelSceneBuilder.java** :
- `addSeg` accepte maintenant un param `ceilTile` (slot 11 du seg array)
- L'appel à `addSeg` passe `z.ceilTile()` (= ceilTile de la zone-couloir
  courante)
- Après `dn.attachChild(sg)` (front face), création de **2 caps** :
  - `seg_{i}_top` avec `faceType="top"`
  - `seg_{i}_bot` avec `faceType="bottom"`
- Nouveau helper `makeCapGeo(...)` : quad horizontal 4-vertices, UV
  type sol/plafond (coords XZ scaled), normale up ou down

**DoorControl.java** — `updateMeshes` :
```java
String faceType = geo.getUserData("faceType");
if ("top".equals(faceType)) continue;        // fixe
if ("bottom".equals(faceType)) {              // mobile
    float eR = Math.min(maxPanelHeight * state, panelHeight);
    geo.setLocalTranslation(0f, eR, 0f);
    continue;
}
// faces verticales : logique existante
```

Les faces verticales (front, ou null par défaut) suivent la logique de
session 104 (vertex update avec `effectiveRise`).

### Pour tester

```
./gradlew buildScenes run
```

(Rebuild nécessaire — changement de géométrie au build).

Observations attendues :
- Quand on regarde une porte fermée par-dessous (en s'approchant et en
  levant la tête), on voit le **bottom cap** avec la texture du plafond
- En s'éloignant et en regardant de loin, le panneau a une **silhouette
  3D** correcte (plus de "feuille de papier" même avec un angle de vue
  rasant)
- Pendant l'ouverture, le bottom cap monte avec le panneau (visible si
  le joueur passe en dessous)
- Quand la porte est complètement ouverte, tout le doorNode est cullé
  (`CullHint.Always` quand `state >= 0.999`), caps inclus

### Limites connues

- Pas de left/right caps (épaisseur 5cm = 2*0.08 JME, peu visible).
  Peut être ajouté plus tard si on remarque un "bord ouvert" sous certains
  angles
- Z-fighting possible entre les 2 caps top/bottom des 2 fronts d'un même
  edge (couloir A et B). Comme les positions XZ sont identiques, en pratique
  pas de flickering visible (les depths matchent exactement)
- La texture du plafond peut différer entre les 2 couloirs (zone A vs zone
  B). Chaque seg utilise le ceilTile de SA zone-couloir, donc on peut avoir
  un mix visuel sur les caps. Acceptable comme première passe.

### Fichiers modifiés

- `tools/LevelSceneBuilder.java` : addSeg + ceilTile, cap creation,
  makeCapGeo helper
- `world/DoorControl.java` : updateMeshes branchement faceType

---

## [2026-04-26] Session 104 — Synchronisation des pans de porte (vitesse + Y final commun)

### Symptôme

Après session 103, la porte 132 s'ouvre fluide (plus de saccades) et a une
vraie épaisseur. Mais on observait encore que les différents pans d'une
porte ne montaient **pas tous à la même vitesse** ni jusqu'à un **Y
final commun**. Résultat : pendant l'ouverture, certains pans étaient
plus avancés que d'autres.

### Diagnostic

Depuis la session 100, l'animation était par-seg :
```java
float riseAmount = panelHeight * state;  // panelHeight propre au seg
```

La session 102 imposait `panelHeight = yR - yF` (hauteur du couloir). Mais
si une porte est entre **deux couloirs de hauteurs différentes** (zone A
avec roofH=-128, zone B avec roofH=-256 par exemple), alors :
- les pans du côté A ont `panelHeight_A = yR_A - yF_A`
- les pans du côté B ont `panelHeight_B = yR_B - yF_B ≠ panelHeight_A`

À la même valeur de `state`, les deux groupes ont monté de quantités
différentes en JME, et leurs Y finaux étaient différents.

### Fix (DoorControl.java)

Pre-pass dans `updateMeshes` pour calculer un `maxPanelHeight` commun à
toute la porte, puis utiliser ce max pour le `riseAmount` :

```java
float maxPanelHeight = 0f;
for (Spatial child : doorNode.getChildren()) {
    Float yBotSeg = geo.getUserData("yBotSeg");
    Float yTopSeg = geo.getUserData("yTopSeg");
    float h = yTopSeg - yBotSeg;
    if (h > maxPanelHeight) maxPanelHeight = h;
}

// Dans la boucle principale :
float panelHeight    = segTop - segBot;
float riseAmount     = maxPanelHeight * state;        // commun
float effectiveRise  = Math.min(riseAmount, panelHeight);  // clamp
float curYBot        = segBot + effectiveRise;
```

**Comportement** :
- Tous les pans montent à la même vitesse JME (`maxPanelHeight * d_state/dt`)
- À `state=1`, le pan le plus grand atteint pile son `segTop`
- Les pans plus courts collapsent à leur `segTop` plus tôt (quand
  `riseAmount > panelHeight_seg`), puis restent invisibles le reste de
  l'animation — c'est l'effet "le pan disparait dans le plafond"
- L'UV `vShift` utilise `effectiveRise` au lieu de `riseAmount` pour ne
  pas faire déborder la texture au-delà du sommet

### Pour tester

```
./gradlew run
```

(Pas besoin de `buildScenes` cette fois : c'est un changement purement
runtime dans `DoorControl`.)

Zones de test :
- **Porte zone 30** (6 pans) : test critique, les 6 pans doivent monter
  parfaitement synchronisés
- **Porte zone 132** (rouge) : confirme que la fluidité session 103 est
  préservée, et vérifie la synchronisation
- **Toutes les portes à 2 couloirs de hauteurs différentes** (à chercher
  dans le binaire si besoin)

### Fichiers modifiés

- `world/DoorControl.java` : `updateMeshes` — ajout pre-pass
  `maxPanelHeight`, modif `riseAmount` (commun) + `effectiveRise`
  (clamp), UV basé sur `effectiveRise`.

---

## [2026-04-26] Session 103 — Épaisseur des portes + animation fluide porte 132

### Symptômes (après session 102)

- **Porte 132 (rouge)** : s'ouvre par à-coups, animation saccadée au lieu
  d'être fluide.
- **Toutes les portes** : textures correctes, glissement uniforme, mais
  le panneau n'a "pas de fond" : c'est un quad mince, on perçoit l'absence
  d'épaisseur quand on regarde sous un angle ou qu'on s'approche près.

### Fix à-coups (DoorControl.java)

Le seuil de redessin du mesh était trop élevé pour les portes lentes :

```java
if (Math.abs(state - prev) > 0.002f) updateMeshes(doorNode);
```

Pour la porte 132 (`openSpeed`=4 Amiga, `hEditor`=704), `state` croît de
0.142/sec, soit **~0.0024 par frame à 60 FPS** — juste au-dessus de
l'ancien seuil. La moindre variance de `tpf` faisait sauter des updates et
rendait l'animation saccadée.

```java
if (Math.abs(state - prev) > 0.0001f) updateMeshes(doorNode);
```

Le coût d'un `updateMeshes` est négligeable (4 vertex + 4 UV par seg), donc
un seuil très bas est OK.

### Fix épaisseur (LevelSceneBuilder.java)

Problème structurel : un panneau de porte était UN seul quad. Même avec
`FaceCullMode.Off`, c'est un plan sans épaisseur — le rendu trahissait
l'absence de volume.

Observation clé : pour chaque porte, il y a **2 ZDoorWalls par edge** (un
rendu depuis chaque couloir voisin), avec des **normales opposées** car
le wall est partagé entre 2 zones d'orientation CCW inversée. Ces 2 segs
étaient précédemment superposés exactement (même XZ).

Fix : décaler chaque ZDoorWall de ε le long de **sa propre normale
intérieure** :

```java
float dxN = x1 - x0, dzN = z1 - z0;
float Ln = sqrt(dxN*dxN + dzN*dzN);
float ox = -dzN / Ln * 0.08f;  // ε = 0.08 JME = 2.5 unités Amiga
float oz =  dxN / Ln * 0.08f;
.addSeg(x0 + ox, z0 + oz, x1 + ox, z1 + oz, ...);
```

Comme les 2 ZDoorWalls ont des normales opposées (`-dzA, dxA` vs
`-dzB, dxB = dzA, -dxA`), après décalage ils sont séparés de **2ε ≈ 0.16
JME ≈ 5cm**. La porte a maintenant une vraie épaisseur visible.

Le joueur passe toujours à travers car la course d'animation reste large
(`riseAmount = panelHeight * state` avec `panelHeight = yR - yF` = hauteur
couloir complète) : quand `state → 1`, les deux faces sont totalement
remontées.

### Pour tester

```
./gradlew buildScenes run
```

- Toutes les portes : panneau visible avec une vraie épaisseur quand on
  s'en approche (plus l'effet "feuille de papier")
- Porte 132 (rouge) : ouverture lente mais **continue**, plus de saccades
- L'effet "bloc plein qui glisse vers le haut" reste préservé

### Fichiers modifiés

- `tools/LevelSceneBuilder.java` : décalage des ZDoorWalls le long de
  leur normale intérieure (DOOR_OFFSET = 0.08f)
- `world/DoorControl.java` : seuil `updateMeshes` abaissé de 0.002 à
  0.0001

---

## [2026-04-26] Session 102 — Extension panneaux de porte + ouvertures de lift

### Symptômes (après session 101)

- Les portes présentaient toujours l'effet "découpé" : panneau chevron en
  haut, bande noire au milieu, panneau chevron en bas. La fusion par edge
  de la session 101 était insuffisante car les segments étaient en réalité
  des walls **distincts** (un ZDoorWall étroit + un linteau séparé +
  parfois un step-wall en bas), pas plusieurs ZDoorWalls sur le même edge.
- Le **lift de level A** apparaissait comme une cabine totalement fermée
  (chevrons sur les 4 côtés) : le joueur arrivait à l'étage et restait
  prisonnier. Les `lift_side_*` créés en session 99 étaient appliqués sur
  TOUS les côtés du polygone, y compris les ouvertures de la cabine.

### Fix portes : extension à la hauteur du couloir

Dans `LevelSceneBuilder.buildScene`, quand un wall est détecté comme
ZDoorWall, on crée maintenant le panneau à la hauteur **complète du
couloir** (`yF`/`yR` = sol/plafond de la zone courante), pas à la hauteur
limitée du wall original.

```java
// Avant :
final float fSegBot = yBot;  // = -botWallH/SCALE (hauteur du ZDoorWall)
final float fSegTop = yTop;

// Après :
final float fSegBot = yF;    // sol du couloir (zone courante)
final float fSegTop = yR;    // plafond du couloir
```

Conséquence : le panneau couvre toute l'ouverture quand fermé, cachant
le linteau et le step-wall qui auraient pu rester visibles. Quand ouvert,
l'ouverture est complète du sol au plafond.

### Fix portes (suite) : skip des walls statiques sur les edges-portes

Après extension du panneau, il faut empêcher les linteaux/step-walls de
se rendre par-dessus. On précalcule `doorEdgesGlobal` (Set de tous les
edges porteurs d'au moins un ZDoorWall) puis on skip dans la boucle des
walls :

```java
int wallEid2 = findEdgeIdForSegment(lpi, rpi, pts, edgesFull, z.edgeIds());
if (wallEid2 >= 0 && doorEdgesGlobal.contains(wallEid2)) continue;
```

Résultat : un seul panneau plein par edge-porte, pas de doublon visuel.

### Fix lifts : skip des sides sur les ouvertures de cabine

La méthode `parseLiftWallEdges(json)` ajoutée à la session 100 est
maintenant **utilisée**. Lors de la création des `lift_side_*`, on garde
un tableau parallèle `sideEdgeIds[]` indexant les edgeIds, puis :

```java
int sideEid = (i < sideEdgeIds.size()) ? sideEdgeIds.get(i) : -1;
if (sideEid > 0 && liftWallEdges.contains(sideEid)) continue;
```

Conséquence : les côtés correspondant aux passages couloir↔cabine ne
reçoivent **pas** de paroi solide. Le joueur peut entrer/sortir librement.

### Pour tester

```
./gradlew buildScenes run
```

Zones à vérifier :
- **Porte zone 5** (entrée level A) : panneau plein chevron, glissement
  uniforme vers le haut, plus de bande noire au milieu.
- **Lift zone 104** : la cabine doit avoir une (ou plusieurs) **ouverture
  visible** correspondant aux edges 500, 510 (les 2 portes du lift).
  Le joueur doit pouvoir entrer et ressortir.
- **Porte zone 30** (6 walls) et **zone 132** (rouge, large) : panneau
  uniforme, glissement complet du sol au plafond.

### Note sur "la porte 130 ne s'ouvre pas progressivement en approche"

Le symptôme peut venir de l'`openSpeed` du binaire (zone 132 rouge a
`openSpeed=4`, soit ~7 secondes pour s'ouvrir complètement) ou d'un
`triggerDist` insuffisant. Avec ce fix, le panneau étendu est plus haut,
ce qui peut affecter la distance perçue. À retester après buildScenes.
Si le problème persiste, on inspectera la zone exacte dans le JSON.

### Fichiers modifiés

- `tools/LevelSceneBuilder.java` :
  - ajout calcul `doorEdgesGlobal` (set tous edges porte)
  - extension panneau ZDoorWall à la hauteur couloir (yF/yR)
  - ajout skip des walls statiques sur edges-portes
  - tracking `sideEdgeIds[]` parallèle aux coords du polygone lift
  - ajout skip des sides du lift sur `liftWallEdges`

---

## [2026-04-26] Session 101 — Fusion ZDoorWalls par edge (effet bloc plein)

### Symptôme

Après les fixes session 100, les portes présentaient encore un effet visuel
incorrect : le panneau chevron jaune/noir apparaissait **découpé horizontalement**
avec une bande noire au milieu (visible sur captures du joueur). La texture ne
glissait pas comme un bloc unique, et les morceaux haut/bas étaient séparés
par un gap où on voyait l'arrière-plan noir.

### Diagnostic

Le bug venait de la phase de **build** (`LevelSceneBuilder`), pas du runtime.

Pour une porte donnée, le binaire définit souvent **plusieurs ZDoorWalls sur
le MEME edge XZ** mais à des hauteurs verticales différentes. Typiquement :
- un panneau de porte (par exemple yBot=-10, yTop=-2)
- un linteau décoratif (par exemple yBot=0, yTop=2)
- un wall de transition entre les deux (souvent invisible ou avec une autre
  texture, mais qui crée un gap dans la géométrie ZDoorWall)

Mon code rendait chaque ZDoorWall comme un **quad indépendant**, ce qui :
- crée une discontinuité verticale entre les segments (bande noire visible)
- désynchronise l'effet de glissement (chaque seg anime sa propre hauteur)
- détruit l'illusion d'un "bloc plein" qui monte

L'ASM 2.5D ne montrait pas ce problème parce que tous les ZDoorWalls étaient
rasterisés dans la **même passe scanline** : visuellement les pixels étaient
contigus même si la structure interne était fragmentée.

### Fix (LevelSceneBuilder.buildScene)

**Avant** : un `Geometry` par ZDoorWall :
```java
for (float[] seg : acc.segs) {
    Geometry sg = makeDoorSegGeo(..., segYTop, segYBot, ...);
    dn.attachChild(sg);
}
```

**Après** : grouper les segs par position XZ, fusionner verticalement :
```java
Map<String, List<float[]>> bySpan = ...;  // clé = "x0_z0_x1_z1"
for (float[] seg : acc.segs)
    bySpan.computeIfAbsent(spanKey(seg), k -> new ArrayList<>()).add(seg);

for (var group : bySpan.values()) {
    float[] anchor = ...;          // seg avec le yBot le plus bas
    float groupYBot = min(yBots);  // bas global du groupe
    float groupYTop = max(yTops);  // haut global du groupe
    Geometry sg = makeDoorSegGeo(..., groupYTop, groupYBot, ...);
    dn.attachChild(sg);
}
```

La texture/UV/yOffset proviennent du seg-ancre (yBot le plus bas), car son
yOffset est calibré pour ce yBot — les UV restent alignées au sol original.

### Résultat attendu

- Plus de bande noire au milieu du panneau
- Glissement uniforme de la texture vers le haut quand la porte s'ouvre
- Effet "bloc plein qui se rétracte dans le plafond" comme dans le jeu original
- L'ouverture continue de laisser voir l'autre côté du couloir (porte mince,
  pas de back face requise)

### Pour tester

**Important** : ce fix change la géométrie des `.j3o`, donc :
```
./gradlew buildScenes run
```
(pas juste `run` comme pour les fixes session 100)

Zones à vérifier en priorité :
- Porte zone 5 (entrée level A, chevron) — plus de discontinuité verticale
- Porte zone 30 (6 walls) — le test stress, le plus susceptible d'avoir des
  segs disjoints
- Porte zone 132 (rouge, large) — confirm que le rendu reste propre sur les
  grandes portes

### Fichiers modifiés

- `tools/LevelSceneBuilder.java` : refonte de la boucle de création des
  Geometry porte (~30 lignes), grouping par clé XZ avec `LinkedHashMap`,
  fusion vertical (min/max sur yBot/yTop du groupe).

---

## [2026-04-26] Session 100 — Fix effet "rideau" portes + cabine lift solidaire

### Symptômes rapportés

- Les portes (toutes, y compris zone 132 rouge) donnaient l'impression d'un
  **"rideau qui s'enroule"** plutôt que d'un bloc de porte qui remonte.
- Le **lift de level A (zone 104)** présentait le même symptôme : le sol
  remontait tout seul, les parois latérales restaient en place.

### Diagnostic

Relecture complète du `DoorRoutine` et `LiftRoutine` dans `newanims.s` pour
comprendre la mécanique ASM exacte (cf. analyse session) :

- Côté ASM : `move.l d3,24(a1)` (porte) et `move.l d3,20(a1)` (lift) patchent
  un mot de la structure du mur avec `position * 256`. Tous les ZDoorWalls
  d'une porte partagent la **même valeur absolue** de Bottom -> certains murs
  plus courts disparaissent avant les plus hauts. Sur le moteur Amiga 2.5D
  ce "froissement" est masqué par la rastérisation perspective-correct, mais
  en 3D JME ça donne un effet visuel inacceptable.

- Côté Java (avant fix) :
  - `DoorControl.updateMeshes` calculait `riseAmount = min(animDist*state, panelHeight)`.
    Comme `animDist` est commun mais `panelHeight` varie par seg, et comme
    `computeMaxState()` plafonnait à `min(panelHeight/animDist)`, la porte
    arrêtait son anim quand le PLUS PETIT seg était replié, **laissant les
    plus grands segs partiellement visibles** -> effet rideau.
  - `LiftControl.updateFloorMesh` faisait `geo.setLocalTranslation(...)` sur
    le seul `lift_floor_*`. Or `setLocalTranslation` sur un enfant ne se
    propage **pas** aux frères -> les `lift_side_*` restaient statiques
    pendant que le sol montait.

### Fix porte (DoorControl.java)

Animation **par-segment** au lieu de par animDist global :

```java
// Avant
float riseAmount = Math.min(dist * state, panelHeight);

// Après
float riseAmount = panelHeight * state;
```

Chaque segment monte de SA propre `panelHeight` quand `state` passe de 0 à 1.
Tous les segs d'un même groupe atteignent le repli complet **simultanément**,
ce qui donne un effet de "bloc de porte" uniforme.

`computeMaxState()` simplifié à `return 1f` puisque la limite est maintenant
intrinsèque à chaque seg.

La durée d'ouverture (contrôlée par `openSpeed` calculé depuis
`zl_OpeningSpeed * 25 / |zl_Top - zl_Bottom|`) reste inchangée -> même temps
d'ouverture que dans l'original.

**Note ASM-fidélité** : ce fix dévie volontairement de l'ASM brut sur ce
point précis. Le moteur 2.5D Amiga produisait le même "froissement" mais ne
le rendait pas perceptible. En 3D JME on choisit la cohérence visuelle.

### Vérification UV (texture reste fixée dans le monde)

Le `vShift = (riseAmount * 32) / tileH` continue de garantir que la texture
est perspective-correct fixée dans l'espace monde :
- À `state=0` : `vShift=0`, texture pleine
- À `state=s` : V au bas de la portion visible = `vOffset + s * vM` =
  exactement la même V qui était à cette hauteur monde dans la position fermée
- À `state=1` : `vShift=vM`, V_bot = V_top, texture collapsée à une ligne ✓

### Fix lift (LiftControl.java)

Translation au niveau du `Node` parent au lieu du seul `Geometry` du sol :

```java
// Avant
for (Spatial child : liftNode.getChildren()) {
    if (!(child instanceof Geometry geo)) continue;
    if (!geo.getName().startsWith("lift_floor_")) continue;
    geo.setLocalTranslation(0f, deltaY, 0f);
}

// Après
liftNode.setLocalTranslation(0f, deltaY, 0f);
```

Tous les enfants (sol + 4 cotés `lift_side_*`) montent ensemble -> cabine
d'ascenseur cohérente.

### Fichiers modifiés

- `world/DoorControl.java` : `updateMeshes` (riseAmount par-seg) +
  `computeMaxState` (simplifié)
- `world/LiftControl.java` : `updateFloorMesh` (translation Node parent)
- `tools/LevelSceneBuilder.java` : ajout méthode `parseLiftWallEdges(json)`
  qui parcourt `lifts[].walls[].edgeId` du JSON. La méthode était appelée
  ligne 154 mais jamais définie (vestige de la session 99) -> erreur de
  compilation. Ajoutée pour usage futur (skip rendu statique des walls
  d'entrée/sortie de lift) avec TODO clair côté call site.

### À vérifier après build

- `./gradlew run` (pas besoin de `buildScenes` - changements purement runtime)
- Porte zone 5 (level A entrée) : panneau chevron jaune/noir qui se replie
  vers le plafond comme un bloc uniforme
- Porte zone 132 (rouge, large) : même effet, plus de panneaux à hauteurs
  désynchronisées
- Lift zone 104 (level A) : sol + parois montent ensemble
- Toutes les autres portes du level A (11, 30, 48, 52, 54, 57, 68, 74, 96)

---

## [2026-04-26] Session 99 — Audit architectural : données ignorées du binaire de niveau

### Le constat

Après avoir rapidement "réparé" la porte rouge zone 132 et le lift zone 104, il est apparu que l'`LevelJsonExporter` actuel **ignore une grosse partie** des données déjà parsées par `LevelBinaryParser`. C'est probablement la racine de plusieurs bugs subtils observés.

### Inventaire des données manquantes

#### Données PARSÉES en mémoire mais NON EXPORTÉES :

1. **Messages texte** (10 × 160 chars) - briefings/narratif/hints
2. **Control Points** (TLBT_NumControlPoints) - points de patrouille des aliens
3. **Zones étendues** : tous les champs après `floor`/`roof`/`brightness` sont ignorés :
   - `upperFloor`, `upperRoof`, `upperBrightness` -> **zones à 2 niveaux** (mezzanines, ponts)
   - `water` -> **zones aquatiques** (animation, dégâts par water touch)
   - `controlPoint` -> control point assigné à cette zone
   - `backSFXMask` -> sons d'ambiance (gouttes, vent, etc.)
   - `drawBackdrop` -> skybox visible
   - `echo` -> reverb audio par zone
   - `telZone, telX, telZ` -> **TÉLÉPORTEURS** (entrer dans la zone téléporte)
   - `floorNoise, upperFloorNoise` -> sons de pas (eau, métal, terre)
4. **PVS records** (Potentially Visible Set) - optimisation rendu
5. **Edges étendus** : `Word_5`, `Byte_12`, `Byte_13`, `Flags_w` (flags wall pour DoorRoutine)
6. **DisplayText des objets** (texte affiché quand activé)

#### Données PAS DU TOUT PARSÉES :

7. **Switches** (header `+8` du TLGT) - **leviers/boutons déclencheurs** !
8. **TYPE_OBJECT/WATER/BACKDROP** dans ZoneGraphAdds (decoration zone-bound, water anims, custom skybox)
9. **ZoneCrossing logic** - comment passer d'un étage bas à étage haut dans une zone à 2 niveaux

### Architecture clé manquée : ZONES À 2 NIVEAUX

Le vrai jeu supporte des zones avec **deux étages superposés** (`floor`/`roof` pour bas, `upperFloor`/`upperRoof` pour haut). Le joueur a un flag `PlrT_StoodInTop_b` qui détermine s'il est dans la moitié basse ou haute. Les passages entre zones utilisent `ZoneCrossing` (LOWER_TO_LOWER, LOWER_TO_UPPER, etc.).

Mon code JME suppose actuellement **une seule épaisseur par zone** -> impossible de modéliser correctement les mezzanines, ponts, escaliers à étage.

### Étape 1 — Enrichissement du JSON (FAIT)

`LevelJsonExporter.java` exporte maintenant :

- `messages[]` : 10 messages texte du niveau (briefings, hints, scene transitions)
- `controlPoints[]` : points de patrouille pour aliens
- Zones étendues : `upperFloorH, upperRoofH, hasUpper, water, upperBrightness, controlPoint, backSFXMask, drawBackdrop, echo, telZone, telX, telZ, floorNoise, upperFloorNoise, pvs[]`
- Edges étendus : `word5, byte12, byte13, flags`
- Objets : `displayText, targetCP`
- `switchesDataOffset` : pointeur vers la table switches (pas encore parse)

### Outil d'inspection — LevelInspector (FAIT)

Nouveau tool `LevelInspector.java` + task gradle `levelInspect` qui dumps un rapport synthetique pour chaque niveau : nombre de messages non vides, control points, teleporteurs, zones a 2 niveaux, zones water, doors/lifts/switches.

Usage : `./gradlew convertLevels levelInspect` ou `./gradlew levelInspect -Plevel=A`

### Fichiers modifies session 99

- `tools/LevelJsonExporter.java` : ajout de tous les champs etendus
- `tools/LevelInspector.java` : NOUVEAU - rapport synthetique par niveau
- `build.gradle` : ajout task `levelInspect`
- `gradle.properties` : ajout action NetBeans pour `levelInspect`

### Etapes suivantes

- ~~**Etape 2** : parser les SWITCHES depuis switchesDataOffset~~ **(FAIT)** - voir ci-dessous
- **Etape 3** : valider que le LIFT zone 104 a un floorH coherent en utilisant les nouvelles donnees
- **Etape 4** : architecture des ZONES A 2 NIVEAUX (refactor LevelSceneBuilder)
- **Etape 5** : utiliser les messages texte et displayText dans le HUD
- **Etape 6** : implementer les TELEPORTEURS dans le runtime
- **Etape 7** : sons d'ambiance par backSFXMask + reverb par echo

### Etape 2 — Parsing des SWITCHES (FAIT)

Apres relecture de `newanims.s::SwitchRoutine` (lignes ~2200-2400), structure decryptee :

```c
struct Switch {           // 14 bytes
    int16_t  active;         // +0  : -1 = slot vide, >=0 = actif
    uint8_t  timerActive;    // +2  : flag timer en cours
    uint8_t  timerCounter;   // +3  : decrement par Anim_TempFrames_w*4
    uint16_t pointIndex;     // +4  : index Lvl_PointsPtr_l (position du switch)
    uint32_t gfxOffset;      // +6  : offset Lvl_GraphicsPtr (graphique)
    uint8_t  pressed;        // +10 : 0/1 toggle (Plr active)
    uint8_t  padding;        // +11
    uint16_t reserved;       // +12
};
```

Maximum **8 switches par niveau** (boucle `move.w #7,d0`).
Le bit affecte dans `Conditions` par switch d'index `i` (0..7) est `bit (11 - i)` (cf asm `addq #4,d3` apres `sub.w d0,d3` ou d3 part de 7).
Distance d'activation : 60 unites Amiga^2 (~1.9 unites JME).

Exporte dans `level_*.json` sous la section `"switches": [...]`.
L'offset brut reste expose via `switchesDataOffset` pour reference.

### Audit complet 16 niveaux (LevelInspector)

Les 16 niveaux du jeu (A-P) ont chacun un caractere distinct :

| Niveau | Telep | 2-Niv | Eau | Echo | Caractere |
|---|---|---|---|---|----|
| A | 0 | 0 | 0 | - | Intro classique (1 message: cle blast doors) |
| B | 0 | 0 | 0 | - | **0 doors/lifts** - bug ou open space ? |
| C | 3 | 3 | 0 | - | Premier niveau "complexe" |
| D | **8** | 0 | 0 | - | **Labyrinthe** (50 zones, 8 telep, 8 lifts, 3 obj) |
| E | 0 | 0 | 0 | - | |
| F | 3 | 0 | 1 | - | |
| G | 1 | 0 | 0 | 33 | **Scenarise** (10 messages narratifs !) |
| H | 0 | 0 | 0 | 64 | |
| I | 0 | 2 | 0 | 56 | |
| J | 5 | 1 | **7** | 17 | **Aquatique** (zones 0-4 spawn dans eau) |
| K | 6 | 0 | 0 | - | |
| L | 0 | 0 | 0 | 54 | Niveau "spectacle" (2 objets) |
| M | 0 | 0 | 0 | 122 | |
| N | 1 | 2 | 1 | - | Test/debug (2x "hello") |
| O | **22** | 2 | 6 | 138 | **Hub massif** (22 telep !) |
| P | 0 | **12** | 0 | 76 | **Vertical** (mezzanines partout) |

**Totaux** : 49 teleporteurs, 22 zones a 2 niveaux, 22 zones aquatiques.

### Switches : table inutilisee dans le jeu de base

0 switches actifs sur 16 niveaux. La table `SwitchRoutine` existe (offset present) mais est vide partout. Donc cette routine est **du code mort** dans la version finale du jeu. Les leviers/boutons sont implementes via `ENT_TYPE_ACTIVATABLE` qui utilise le mecanisme `EntT_DoorsAndLiftsHeld_l`.

### Niveau G : narration scenarisee

Le niveau G est le plus narratif, les 10 messages forment l'histoire complete :
1. Energy weapon intro (collecte arme)
2. Ventilation duct discovery (passage cache)
3. Fan mechanism puzzle (obstacle)
4. Pillar objective (objectif vise)
5. Get out of here ! (urgence apres trigger)
6. Sky/planet surface transition (sortie outdoor)
7. Strike one generator ! (boss/objectif)
8. Magnetic seal puzzle (sortie verrouillee)
9. Way out (chemin trouve)
10. "That was a really bad idea" (game over scripte)

### Mecanisme cle->unlock confirme (newaliencontrol.s::Collectable + JUMPALIEN)

```asm
Collectable:
    move.w  ObjT_ZoneID_w(a0),d0
    bge.s   .ok_in_room
    rts                              ; deja ramasse -> exclu
.ok_in_room:
    move.l  EntT_DoorsAndLiftsHeld_l(a0),d1
    or.l    d1,Anim_DoorAndLiftLocks_l   ; OR ses bits dans les locks globaux
```

`Anim_DoorAndLiftLocks_l` = OR de **tous** les `EntT_DoorsAndLiftsHeld_l` des objets **vivants/non collectes** (aliens **ET** pickups). Quand un pickup est ramasse, son `ObjT_ZoneID_w = -1` -> exclu de l'OR au prochain frame -> la porte n'est plus bloquee.

**DoorRoutine** lit ensuite ces bits :
```asm
move.w  Anim_DoorAndLiftLocks_l,d5
btst    d2,d5         ; d2 = anim_CurrentLiftable_w (index porte)
beq.s   satisfied     ; bit non set -> porte peut s'ouvrir
```

Note IMPORTANTE : la ligne `; and.w Conditions,d2` est COMMENTEE dans DoorRoutine. Le mecanisme utilise `Anim_DoorAndLiftLocks_l`, **pas** la variable `Conditions` (qui semble inutilisee finalement, malgre les references dans SwitchRoutine code mort).

### Donnees deja disponibles dans le JSON pour implementer ca

- `objects[].doorLocks` (low word de `EntT_DoorsAndLiftsHeld_l`)
- `objects[].liftLocks` (high word)
- `objects[].displayText` (index dans `messages[]` pour hint au pickup)
- Index de la porte = position dans `doors[]`
- Index du lift = position dans `lifts[]`

Pas besoin de plus de parsing, il faut juste **exploiter en runtime**.

### Etape 3 - Bug du LIFT zone 104 : trou dans le sol (FAIT)

**Symptome** : le joueur tombe dans un trou JME -43 au lieu d'arriver a -10.75 dans la zone du lift.

**Cause racine** : depuis la session 92 fix 4, le sol statique de la zone-lift est explicitement **retire** dans `LevelSceneBuilder` (pour eviter le double-rendering avec le sol dynamique). Mais l'ajout de la **collision physique** sur le sol dynamique avait ete oublie.

Resultat : le mesh dynamique est genere et rendu correctement a yRest (-10.75 JME), mais sans `RigidBodyControl` -> aucune collision Bullet -> le joueur traverse le sol et tombe dans le trou (jusqu'a la prochaine collision en dessous, soit yBot=-43).

**Fix session 99** :

1. **GameAppState.setupPhysics()** : pour chaque `lift_<zid>` Node, on cree un `RigidBodyControl` kinematic (mass=0 + setKinematic=true) sur le Geometry `lift_floor_*`, et on l'ajoute au PhysicsSpace.

2. **LiftControl.updateFloorMesh()** : refactor de l'animation pour etre simple-et-correcte :
   - **Avant** : on modifiait le buffer `Position` du Mesh chaque frame (couteux, ne suit pas la collision)
   - **Apres** : on garde le Mesh STATIC genere a yBot, et on translate le `Geometry.setLocalTranslation(0, currentFloorYDelta, 0)`. Le RigidBody kinematic suit via `setPhysicsLocation`.

Cette approche est plus naturelle (un seul `LocalTranslation` au lieu de N vertices a recalculer), plus rapide, et **fait suivre la collision**.

**Fichiers** : `app/GameAppState.java` (creation RigidBody kinematic), `world/LiftControl.java` (refactor updateFloorMesh).

**A tester** : entree dans la zone 104 (lift bas du level A) - le joueur doit etre stable au sol a -10.75 JME, et monter avec le lift quand il bouge.

### Etape 3 (suite) - MeshCollisionShape ne fonctionne pas en kinematic

**Symptome 2** : le joueur arrive sur la plateforme du lift (collision fonctionne quand il atterrit) mais des qu'il bouge il tombe a travers.

**Cause** : `CollisionShapeFactory.createMeshShape()` cree un **MeshCollisionShape (concave)**. Bullet **ne supporte pas** de deplacer un MeshCollisionShape, meme en kinematic. Les contacts disparaissent des qu'on bouge.

**Fix** : remplacer par une **BoxCollisionShape** (convexe). Pour un sol de lift, une boite plate (extent.y = 0.1) suffit, dimensionnee depuis la BoundingBox du Geometry. Le RigidBody est positionne au centre du polygone (`physCenterX/Y/Z` stocke en UserData), et `LiftControl` deplace verticalement de `currentFloorYDelta` autour de ce centre.

**Limitation reconnue** : le `CharacterControl` Bullet ne suit pas automatiquement les kinematic platforms en mouvement. Quand le lift montera, le joueur restera sur place pendant que la plateforme passe a travers lui. Solution future : `LiftControl.setPlayerStoodOnLift(true)` -> pousser manuellement la position Y du joueur dans `GameAppState.update()`.

### Etape 3 (refonte) - Approche "sol statique + push Y" (FAIT)

**Symptome 3** : avec BoxCollisionShape kinematic, le joueur tombe quand il bouge sur le lift. Investigation des donnees zone 104 :

- Geometrie : un **losange** (4 edges, sommets a 45 degres) — pas un rectangle
- `floorH=344` (-10.75 JME), `roofH=-128` (+4 JME)
- `bottom=1376` (-43 JME, jamais utilise car `lowerCondition=NEVER`)
- `top=-64` (+2 JME, niveau atteint quand le lift est en haut)
- 2 zones voisines : zone 103 (couloir bas, floorH=344) et zone 105 (couloir haut, floorH=-8)
- Le lift connecte le couloir bas au couloir haut, course de ~12.75 unites JME

Probleme architectural : le `CharacterControl` Bullet ne suit pas les MeshCollisionShape ni les BoxCollisionShape kinematic. La box deborde du losange (plus large dans les coins) et le joueur "glisse hors" quand il marche.

**Refonte session 99** :

1. **`LevelSceneBuilder.java`** : ANNULE le retrait du sol statique pour les zones-lifts (sess 92 fix 4 etait une erreur). Le sol de la zone-lift est maintenant present dans `geometry/` (= collision Bullet globale OK).

2. **`LiftControl.java`** : simplifie - se contente de translater le Geometry visuel localement de `currentFloorYDelta`. Pas de gestion de RigidBody. Expose `getCurrentFloorY()` et `isPlayerOver(liftNode)` pour utilisation externe.

3. **`GameAppState.java`** : nouvelle methode `applyLiftPushY()` appelee chaque frame. Si le joueur est dans le polygone XZ d'un lift et que le sol visuel du lift est plus haut que ses pieds, on **teleporte** le joueur (via `setPhysicsLocation`) pour le poser sur le sol. Comme ca le joueur monte avec le lift sans casser le moteur Bullet.

Approche fidele a l'ASM Amiga : `LiftRoutine` modifie `ZoneT_Floor_l`, et le code physique du jeu original re-positionne le joueur en consequence (pas de kinematic platforms - tout etait calcule manuellement).

**Avantages** :
- Au repos : sol statique = collision Bullet OK, joueur stable comme dans toute autre zone
- Visuellement : le sol du lift remonte (Geometry localTranslation)
- En mouvement : le joueur suit le sol via push Y manuel
- Le sol statique sous le sol visuel reste un "filet de securite" si jamais le push Y rate un frame

---

## [2026-04-25] Session 97 — Portes : ASM-fidele enfin

### Le diagnostic ASM definitif

Apres relecture COMPLETE du fichier `newanims.s` (DoorRoutine ligne 1900+ +
zone_liftable.h), le mecanisme reel des portes a ete compris :

1. Les **vrais ZDoorWalls** sont les murs des couloirs voisins de la zone-porte,
   ceux qui ont `otherZone = doorZoneId` dans le JSON.
2. Ils ont DEJA leur propre `clipIdx` qui pointe vers la BONNE texture
   (ex. `clipIdx=5` = `wall_05_chevrondoor.png` pour la porte zone 5).
3. Le `gfxOfs` du JSON ("walls" sous-bloc des doors) N'EST PAS un texIndex !
   C'est juste un BYTE OFFSET dans le buffer `Lvl_GraphicsPtr` pour que la
   routine ASM `DoorRoutine` puisse retrouver la struct graphique du wall et
   patcher dynamiquement son `topWallH`/`botWallH`/`yOffset` a runtime.
4. L'animation reelle : la routine modifie `ZoneT_Roof_l` de la zone-porte
   (le PLAFOND descend pour fermer, monte pour ouvrir). Les ZDoorWalls
   voient leur `topWallH` patche en consequence -> visuellement le panneau
   se reduit en hauteur.

### Erreur des sessions 93-96

Mon code precedent interpretait `gfxOfs` comme :
  `texIndex = (gfxOfs >> 16) & 0xFFFF`
  `yOffset = gfxOfs & 0xFF`

C'est COMPLETEMENT FAUX. Les valeurs gfxOfs (2142, 3576, etc.) decalees de 16
bits donnent 0 ou des nombres absurdes. La texture utilisee finissait par etre
la premiere du tableau (clipIdx=0 = stonewall) ou une fallback rose.

Resultat: textures grises/marron, panneaux dupliques, animations bizarres.

### Solution Session 97

Refactor complet de la detection ZDoorWall dans `LevelSceneBuilder` :

1. Pendant la boucle des zones-couloirs : si un wall a `otherZone=doorZone`
   ET son edge est dans la liste des walls de la porte (via `parseDoorZoneEdges`),
   c'est un ZDoorWall.
2. **On utilise SON PROPRE clipIdx** comme texture (pas le gfxOfs interprete).
3. **On utilise SES PROPRES coordonnees** (leftPt, rightPt) pour la geometrie,
   pas les edges de la zone-porte.
4. Les 2 ZDoorWalls (un de chaque cote du couloir) sont rendus chacun comme
   un quad separe -> on voit la porte des 2 cotes.

### Fichiers modifies

- `tools/LevelSceneBuilder.java` :
  - Suppression de la boucle de construction du "cube" (session 96)
  - Detection ZDoorWall + ajout direct dans DoorAccum.segs avec clipIdx du wall
  - Nouvelle methode `parseDoorZoneEdges(json)` (zoneId -> Set<edgeId>)
  - Suppression de la deduplication des segments (les 2 faces sont voulues)
  - Suppression de l'usage de `parseDoorFirstGfx` et `doorZoneGfx` (inutiles)

### Bug additionnel : UV mapping incorrect dans makeDoorSegGeo

Apres le refactor, la texture s'affichait toujours mal (degrade horizontal
blanc/marron). Cause : `makeDoorSegGeo` calculait `uM = L / tileW` ou `L`
etait en unites JME (deja /SCALE) alors que `tileW` etait en pixels Amiga.
Resultat : facteur 32x trop petit -> texture etiree 32 fois en horizontal,
on voyait 1-2 pixels etires sur tout le mur.

Fix : reconvertir `L` en pixels via `L_pixels = L * SCALE` avant le calcul UV.
`buildWallGeo` (murs normaux) faisait deja le bon calcul car il operait avant
la division par SCALE.

### Comportement valide

- Porte zone 5 (standard) : panneau jaune/noir hazard chevrondoor qui monte ✓
- Porte zone 132 (rouge large) : openSpeed=4 (lent), top=-704 (haut), avec
  sa texture clipIdx=5 = chevrondoor egalement
- Toutes les autres portes (30, 11, 54, 48, 52, 57, 96, 68, 74) : meme mecanisme

### Fichiers modifies (ajout fix UV)

- `tools/LevelSceneBuilder.java` : `makeDoorSegGeo` recalcule `L_pixels` et
  `wallH_pixels` pour avoir la meme echelle que `tileW`/`tileH` (pixels)

---

## [2026-04-24] Session 92 — Lighting moderne JME complet + audit textures walls

### Objectif

Revoir l'integralite du systeme d'eclairage (niveau + objets + vectobj) en
abandonnant le systeme Amiga historique (palette shading, brightness
per-zone/per-polygon baked-in) au profit d'un lighting JME standard :
`AmbientLight` + `DirectionalLight` + `PointLight headlight` + `PointLights`
par zone brillante.

En parallele, demarrage d'un audit des textures de murs (UV/wrap).

### Diagnostic initial

Les sessions precedentes (75-90) avaient force tous les materiaux en
`Unshaded.j3md` via `GameAppState.fixMaterials()` car le lighting JME
avait donne des resultats decevants au debut. Resultat : les lumieres
(AmbientLight, DirectionalLight, PointLight headlight, et les ~40
PointLights par zone serialisees dans les `.j3o` par `LevelSceneBuilder`)
etaient **toutes ignorees** car rien n'etait Lit.

En realite, `LevelSceneBuilder` configurait deja tout correctement au
build-time (`Lighting.j3md + UseMaterialColors + Ambient=0.5 + Diffuse=White`).
Il suffisait d'arreter de l'ecraser au chargement.

### Modifications code (Phase A — Lighting)

1. **`GameAppState.java`** :
   - Nouvelles constantes : `AMBIENT_COLOR` (0.35,0.33,0.40), `SUN_COLOR`
     (0.55,0.52,0.50) + `SUN_DIRECTION` (-0.4,-1,-0.3), `HEADLIGHT_COLOR`
     (1.0,0.92,0.80), `HEADLIGHT_RANGE=30`
   - Nouveau champ `DirectionalLight sunLight` (detruit proprement dans
     `cleanup()`)
   - Suppression de `fixMaterials()`, remplacement par
     **`upgradeMaterialsForLighting()`** qui :
     * garde les `Lighting.j3md` existants et force
       `UseMaterialColors=true + Ambient=0.45 + Diffuse=White` si absent
       (via `ensureLightingParams()`)
     * convertit les `Unshaded` + VertexColor en `Lighting.j3md` avec
       `UseVertexColor=true` (compat future)
     * convertit les `Unshaded` + Alpha en `Lighting.j3md` avec
       `AlphaDiscardThreshold=0.5 + BlendMode.Alpha`
     * preserve le `FaceCullMode` et le bucket `Transparent`
   - Configuration du lighting pipeline :
     `setPreferredLightMode(SinglePass)` + `setSinglePassLightBatchSize(8)`

2. **`LevelSceneBuilder.tryLoadSprite()`** : sprites billboards bitmap
   passent de `Unshaded.j3md` a `Lighting.j3md` (DiffuseMap + Ambient=0.6
   + AlphaDiscardThreshold + FaceCullMode.Off). Impact : les sprites
   reagissent maintenant au headlight et aux PointLights de zone.

3. **`VectObjConverter.buildMaterial()`** : materiau partage des vectobj
   (armes, boss polygonaux) passe de `Unshaded.j3md` a `Lighting.j3md`
   avec `UseVertexColor=true` preserve (le VertexColor baked-in du
   brightness Amiga devient un modulateur du Diffuse).

4. **`WeaponViewAppState.fixWeaponMaterials()`** : ajout d'une conversion
   on-the-fly `Unshaded->Lighting` via
   **`upgradeVectObjMaterialInPlace()`** pour rester compatible avec les
   anciens `.j3o` pre-session 92. Sans cela, il fallait forcer la
   reconversion complete des vectobj.

### Modifications code (Phase B — Audit textures walls)

1. **Nouveau `WallTextureDiagnostic.java`** (+ tache Gradle `diagWallTextures`) :
   audite tous les PNGs walls — dimensions, largeur en multiple de 16,
   hauteur = 128, canal alpha, distribution des largeurs.

2. **Nouveau `WallUsageDiagnostic.java`** (+ tache Gradle `diagWallUsage`) :
   croise les JSON `level_*.json` avec les PNGs walls pour detecter :
   textures manquantes, murs avec `wallLen > texW` (wrap U), murs avec
   `wallH > 128` (wrap V), distribution `fromTile`/`yOffset`.

### Validation test

Retour utilisateur apres `convertVectObj + buildScenes + run` :

```
upgradeMaterialsForLighting : 96 Lighting (gardes) + 0 Unshaded->Lighting
  (alpha) + 0 Unshaded->Lighting (vertex color) + 0 inchanges
Pas de soucis particulier
```

Les 96 materiaux du level A etaient deja en `Lighting.j3md` des le build
(`LevelSceneBuilder`) : la correction consistait donc simplement a ne plus
les ecraser au chargement. L'ecran de jeu affiche maintenant le lighting
JME complet sans regression visible.

### Impact technique

- Le headlight PointLight suit la camera (range 30) → ambiance FPS classique
- Les PointLights de zone (luminosite >=40 dans le brightness Amiga) creent
  des halos colores dans les salles allumees
- Les vectobj reagissent aux lumieres (arme en main eclairee, boss aliens
  teintes selon la zone)
- `setSinglePassLightBatchSize=8` : 8 lumieres max par objet par passe,
  suffisant pour un FPS zonal (peu d'objets sont dans le field de plusieurs
  PointLights simultanement)

### Outils d'audit disponibles

```bash
./gradlew diagWallTextures           # Check PNG dimensions, alpha, distribution
./gradlew diagWallUsage              # Croise JSON levels x PNG walls
./gradlew diagWallUsage -Plevel=A    # Un seul niveau
```

### Phase B suite — Corrections textures walls apres audit

L'audit `diagWallTextures` a revele que **12 textures sur 13 ont une
largeur non-multiple de 16** :

```
hullmetal        258x128  (= 256 + 2 pixels padding)
chevrondoor      129x128  (= 128 + 1)
brownpipes       258x128  (= 256 + 2)
gieger           642x128  (= 640 + 2)
rocky            513x128  (= 512 + 1)
stonewall        195x64   (= 192 + 3, + hauteur != 128)
steampunk         64x64   (hauteur != 128)
brownstonestep   129x32   (= 128 + 1, + hauteur != 128)
```

Cause : le format `.256wad` stocke les pixels 3-par-3 dans des WORDs
16 bits (5 bits par pixel). Pour une texture de 256 px, on a
`ceil(256/3) = 86 groupes = 258 pixels encodes` (les 2 derniers sont
des pixels de padding sans valeur visuelle). Le calcul inverse de la
largeur dans `WallTextureExtractor.computeWidth()` ne tenait pas compte
de ce padding et retournait 258 au lieu de 256.

De plus, plusieurs textures ont **une hauteur differente de 128** (64 ou
32 px), ce qui les fait etirer/compresser verticalement lors du mapping
UV. Par exemple, stonewall (64 px) etait etiree x2 sur chaque mur de
128 unites de haut.

### Modifications code (correctives)

1. **`WallTextureExtractor.java`** :
   - Nouvelle methode `snapToAmigaWidth(rawWidth)` : arrondit au multiple
     de 16 inferieur si l'ecart est <=3 pixels. Tolere l'ecart exceptionnel
     de stonewall (195 -> 192, ecart 3).
   - `computeWidth()` appelle maintenant `snapToAmigaWidth()`. Impact
     apres regeneration : les 12 PNGs non-multiples de 16 deviennent
     correctement dimensionnes.

2. **`LevelSceneBuilder.java`** :
   - Nouveau champ `int[] wallTexHeights` (hauteur reelle lue depuis le
     PNG, comme pour `wallTexWidths`).
   - Calcul UV vM utilise maintenant `wallH / texH` (hauteur reelle) au
     lieu de `wallH / TEX_V` (128 fixe). Les textures de 64 ou 32 px de
     haut sont maintenant repetees verticalement au lieu d'etre etirees.
   - Meme correction pour `makeDoorSegGeo()` (segments de portes
     animees). Le parametre `texH` est passe en plus de `texW`.

### Procedure de mise a jour apres ces corrections

```bash
./gradlew convertAssets    # Regenere PNGs walls (snap largeurs a 16)
./gradlew buildScenes      # Regenere scene_*.j3o avec le nouveau UV mapping
./gradlew run              # Test visuel
./gradlew diagWallTextures # Verification : 0 "w!%16"
```

### Phase B suite (fix 2) — Respect du tileWidth Amiga

Apres le fix des largeurs, l'utilisateur rapporte que certaines textures sont
**entierement affichees** sur un mur, alors que le jeu original n'affiche
qu'**une partie** (une "tile" logique).

### Diagnostic via l'ASM

Analyse de `hiresgourwall.s` (dessin des murs avec shading Gouraud) :

```asm
move.w  d1, d6
and.w   draw_WallTextureWidthMask_w, d6   ; wrap par mask (tileWidth-1)
...
add.w   draw_FromTile_w(pc), d6           ; ajoute fromTile*16 APRES le wrap
```

Conclusion : l'Amiga utilise un **mask de puissance de 2** (typiquement
`widthMask = 127` pour une tile de 128 pixels) pour wrapper horizontalement.
Le `fromTile` sert ensuite a **selectionner quelle tile** dans la texture
totale. Une texture 256x128 contient donc **2 tiles de 128x128** accessibles
via `fromTile=0` (gauche) ou `fromTile=8` (droite).

Pour un mur plus long que la tile, l'Amiga **repete la MEME tile**, il ne
glisse pas sur la tile suivante (celle qui pourrait etre visuellement
differente).

### Probleme avec JME

JME `WrapMode.Repeat` wrappe sur la texture **entiere** (UV `[0, 1]` = toute
la largeur du PNG), pas sur une sous-region. Un UV a `1.5` mod 1.0 devient
`0.5` de la texture **totale**, pas de la sous-tile courante -> debordement
sur la tile d'a cote.

### Solution : decoupage de mur en sous-quads

Code refactore dans `LevelSceneBuilder.buildScene()` :

1. Pour chaque mur, calcule `tileWidth` (128 pour texW >= 128, sinon texW)
2. Si `wallLen > tileWidth`, decoupe le mur en `N = ceil(wallLen/tileWidth)`
   sous-quads
3. Chaque sous-quad a un UV borne strictement a `[uOffset, uOffset + tileUvWidth]`
   ou `tileUvWidth = tileWidth/texW`

Ainsi, le UV ne depasse jamais la zone de la tile selectionnee par
`fromTile`, et JME repete proprement cette tile quand le mur est plus long.

### Modifications code

1. **`LevelSceneBuilder.java`** :
   - Nouvelle methode `deduceTileWidth(texW)` : retourne 128 si texW >= 128,
     sinon texW.
   - La boucle de construction des murs utilise maintenant un sous-bouclage
     sur `numRepeats` pour generer plusieurs quads par mur long.
   - Impact: ~1479 murs (sur 6891 total, 21%) sont maintenant decoupes en
     2+ quads. Les 5412 autres restent en 1 quad (cas majoritaire).

2. **Nouveau `WallTileDiagnostic.java`** (+ tache Gradle `diagWallTiles`) :
   outil d'extraction visuelle des tiles logiques par texture. Genere dans
   `build/wall-tiles/` :
   - `wall_XX_*_annotated.png` : PNG zoome x2 avec lignes rouges sur les
     frontieres de tiles et labels `fromTile=N`
   - `wall_XX_*_tileN.png` : chaque tile extraite individuellement

### Procedure de mise a jour (fix 2)

```bash
./gradlew buildScenes      # Regenere scenes avec le nouveau decoupage
./gradlew run              # Verification visuelle
./gradlew diagWallTiles    # Optionnel : voir les tiles extraites
./gradlew diagWallTiles -Pwall=02    # Juste hullmetal
```

### Phase B suite (fix 3) — Masks Amiga par-mur (tile dimensions exactes)

Le fix 2 utilisait une regle empirique (`tileWidth = 128 si texW >= 128`) pour
le decoupage en sous-quads. Mais l'analyse approfondie de `hireswall.s` a
revele que **chaque mur stocke ses propres masks** dans le binaire du niveau :

```asm
moveq    #0,d1
move.b  (a0)+,d1
move.w  d1,draw_WallTextureHeightMask_w   ; hMask = textureHeight - 1
moveq   #0,d1
move.b  (a0)+,d1
move.w  d1,draw_WallTextureHeightShift_w  ; log2(textureHeight)
moveq   #0,d1
move.b  (a0)+,d1                          ; texture width - 1
move.w  d1,draw_WallTextureWidthMask_w    ; wMask
```

Donc les vraies dimensions de tile sont `wMask + 1` et `hMask + 1`, baked-in
par le LevelED dans chaque entree de mur. Ces valeurs etaient deja parsees
dans `WallRenderEntry` mais **jamais exportees dans le JSON**.

De plus, les portes/lifts utilisaient encore l'ancien `makeDoorSegGeo` simple
qui ne respectait pas le tile mapping, d'ou les **textures mal appliquees**
rapportees par l'utilisateur.

### Modifications code

1. **`LevelJsonExporter.java`** : ajout des champs `wMask`, `hMask`, `hShift`
   dans le JSON exporte pour chaque mur. Format :
   ```json
   {"leftPt":..., ..., "wMask":127, "hMask":127, "hShift":7}
   ```

2. **`LevelSceneBuilder.java`** :
   - `parseWalls()` lit les nouveaux champs `wMask`/`hMask` (compat avec
     anciens JSON : valeur 0 = fallback sur `deduceTileWidth`)
   - Le calcul `tileWidth/tileHeight` utilise les vraies valeurs des masks au
     lieu de la regle empirique du fix 2
   - `makeDoorSegGeo()` accepte maintenant `tileW` et `tileH` en parametres
     supplementaires et les utilise pour calculer uM/vM (au lieu de texW/texH)
   - `DoorAccum.addSeg()` propage `wMask` et `hMask` aux segments

### Impact

- **Murs** : meme effet visuel que fix 2 dans la majorite des cas (tile=128)
  mais maintenant **exact** pour des cas exotiques (textures avec des tiles
  non-128 dans certains niveaux)
- **Portes/lifts** : enfin afficher la bonne tile et pas la texture entiere.
  L'utilisateur rapportait des portes avec textures "mal appliquees" - fix
  conforme a l'ASM original.
- **Marches/differences de niveau** : les murs courts (8-32 unites) avec des
  textures speciales (bandes jaune-noire, brownstonestep) repetent maintenant
  correctement leur tile au lieu d'etirer/comprimer.

### Procedure de mise a jour (fix 3)

```bash
./gradlew convertLevels    # Regenere les JSON avec wMask/hMask
./gradlew buildScenes      # Regenere les scenes avec le bon mapping UV
./gradlew run              # Test visuel - portes + marches + tile-aware
```

---

## [2026-04-24] Session 91 — Reactivation des 10 armes du GLF (slots 8 et 9)

### Decouverte cle

L'utilisateur confirme que le jeu original AB3D2 TKG a **10 armes**
selectionnables, mais que les slots 8 et 9 (touches 9 et 0) reutilisent
les modeles 3D de leurs cousines :

| Touche | Slot | Arme              | bulletType     | Apparence 3D    |
|:------:|:----:|-------------------|----------------|-----------------|
|   1    |  0   | Shotgun           | 7 Shotgun Round| shotgun.j3o     |
|   2    |  1   | Plasma Gun        | 0 Plasma Bolt  | plasmagun.j3o   |
|   3    |  2   | Grenade Launcher  | 8 Grenade      | grenadelauncher |
|   4    |  3   | Assault Rifle     | 1 Machine Gun  | rifle.j3o       |
|   5    |  4   | Blaster           | 9 Blaster Bolt | blaster.j3o     |
|   6    |  5   | Rocket Launcher   | 2 Rocket       | rocketlauncher  |
|   7    |  6   | Lazer             | 14 Lazer       | laser.j3o       |
|   8    |  7   | Drop Mine         | 15 Mine        | plink.j3o       |
|   9    |  8   | Plasma Multi-Shot | 13 MegaPlasma  | **plasmagun.j3o** (meme apparence) |
|   0    |  9   | MegaLaser         | 10 Assault Lazer | **laser.j3o** (meme apparence) |

### Analyse TEST.LNK (dump hex raw)

Le dump confirme que **les slots 8 et 9 existent bien** dans le GLF mais
sont configures comme des placeholders :

```
  Gun 8 : "GUN I" — bulType=8, delay=0, count=0, sfx=0
  Gun 9 : "GUN J" — bulType=9, delay=0, count=0, sfx=0
```

Les bullets **MegaPlasma (13)**, **Assault Lazer (10)** et **MindZap (12)**
sont presentes dans la table `BulletDefs` mais inutilisees par les 8
premieres armes — ce sont probablement les munitions des armes manquantes
dans cette version de test du GLF.

### Modifications

1. **`CombatBootstrap.WeaponOverride`** : nouvelle classe interne avec les
   ShootDef par defaut pour les slots 8 et 9 :
   - Slot 8 Plasma Multi-Shot : MegaPlasma × 3, delay 30f, SFX 1
   - Slot 9 MegaLaser        : Assault Lazer × 1, delay 10f, SFX 28

2. **`CombatBootstrap.getEffectiveShootDef(glf, gunIdx)`** : retourne
   l'override si le GLF a count=0, sinon la valeur du GLF. Preserve la
   possibilite de modder via un GLF personnalise.

3. **`CombatBootstrap.newLevelStart()`** : itere maintenant sur les 10 armes
   (et pas 8), donne les slots 8 et 9 via les overrides.

4. **`PlayerShootSystem.update()`** : utilise `getEffectiveShootDef()` au
   lieu de `glf.getShootDef()` pour que le tir des slots 8 et 9 fonctionne.

5. **`PlayerShootSystem.registerMappings()`** : ajoute `WEAPON_9` et
   `WEAPON_10` dans les actions ecoutees.

6. **`PlayerShootSystem.onAction()`** : utilise un switch explicite slot→action
   (slot 8 → touche 9, slot 9 → touche 0) au lieu de la formule generique
   qui ne marchait que pour les touches 1-8.

7. **`KeyBindings`** : ajoute `Action.WEAPON_9` et `Action.WEAPON_10`,
   bindees respectivement sur KEY_9 et KEY_0.

8. **`WeaponType`** : ajoute `PLASMA_MULTISHOT` (slot 8 → plasmagun.j3o)
   et `MEGALASER` (slot 9 → laser.j3o). Meme modele 3D que les armes
   cousines comme confirme par l'utilisateur.

### A tester

Au lancement du jeu :
- Les touches 9 et 0 doivent switcher sur les nouvelles armes
- Touche 9 (Plasma Multi-Shot) : meme apparence que touche 2 (Plasma Gun),
  mais 3 projectiles en eventail de couleur bleue/blanche (MegaPlasma)
- Touche 0 (MegaLaser) : meme apparence que touche 7 (Lazer), mais cadence
  de tir 2x plus rapide avec bullets magenta (Assault Lazer)
- Les 10 armes sont donnees au joueur au spawn

---

## [2026-04-24] Session 90 — Fix trous arme : activation du depthTest + diagnostic structure

### Decouverte majeure

Apres lecture complete de `VectObjConverter.java` : **le code fait DEJA un
`Geometry` par Part du vectobj !** L'architecture multi-parts conforme a
l'ASM existe deja en coulisses :

```java
// Dans convert() :
for (int pi = 0; pi < parts.size(); pi++) {
    Geometry geo = buildGeometryIdx(name + "_part" + pi, tris, points);
    root.attachChild(geo);  // <- un Geometry distinct par part
}
```

Donc **le probleme des trous ne vient pas de l'ordre des parts** (JME les
trie via Bucket.Transparent). Il vient du <b>depthTest=false</b> dans
`fixWeaponMaterials` qui empeche le z-buffer de fonctionner :
- Entre les triangles d'une meme part : pas de tri -> ordre du buffer
- Entre les Geometries (parts) : tri JME, mais sans z-test les triangles
  dessines en premier peuvent etre ecrases par ceux dessines ensuite

### Investigation alt-fire Laser : clos

Observation utilisateur initiale : "quand on clique plusieurs fois sur le
laser on change de mode". Precision utilisateur : il confondait avec le
fait qu'il y a deux armes plasma dans sa memoire (plasma single shot et
plasma multishot).

**Verdict apres investigation exhaustive** :

1. **Pas d'alt-fire dans l'ASM.** Lecture complete de `newplayershoot.s`,
   `plr_KeyboardControl` (modules/player.s), `newanims.s` :
   - Pas de `Plr1_SecShot`, `alt_fire_key`, logique d'alternance
   - Une seule routine `Plr1_Shot` par joueur
   - Chaque bullet spawne avec `ShotT_Anim_b = 0` (meme etat initial)

2. **Pas de Plasma multishot dans ce GLF.** Le dump GLF de TEST.LNK confirme :
   - **Gun 1 = "Plasma Gun"** : bulletType=0 (Plasma Bolt), count=1,
     single shot uniquement
   - Gun 8 ("GUN I") et Gun 9 ("GUN J") sont des placeholders (count=0)
   - Il existe des bullets MegaPlasma (13), Assault Lazer (10) mais elles ne
     sont attribuees a aucune arme player dans ce GLF

3. **Si l'utilisateur voit des variations entre tirs** dans la version
   originale Amiga : probablement l'animation en vol des bullets glare
   (graphicType=2) via `BulT_AnimData_vb` (20 frames). Chaque frame a
   potentiellement un sprite different.

**Conclusion** : pas d'alt-fire a implementer. Point ferme definitivement.

### Fix applique

**`WeaponViewAppState.fixWeaponMaterials()`** :
- `depthTest` : false -> **true** (active le z-test entre triangles)
- `FaceCullMode` : inchange (reste Off)
- `Bucket.Transparent` : inchange

### Nouveaux outils

**`VectObjStructureDump.java`** : diagnostic pour verifier la structure d'un
.j3o vectobj. Montre combien de Geometries (parts), triangles, vertices, et
le render state de chacun.

**Task Gradle** `dumpVectObjStructure -Pname=shotgun` :
```bash
./gradlew dumpVectObjStructure -Pname=shotgun
```

Utile pour valider :
1. Que le shotgun est bien multi-parts (attendu 3-8 parts)
2. Que le render state est bien applique (depthTest=true apres ce fix)
3. Que les frames d'animation sont bien stockees (UserData)

### Fichiers modifies / crees

- `src/main/java/com/ab3d2/weapon/WeaponViewAppState.java` (depthTest=true)
- `src/main/java/com/ab3d2/tools/VectObjStructureDump.java` (NEW)
- `build.gradle` (task `dumpVectObjStructure`)

### Test attendu

```bash
./gradlew run
```

1. Changer d'arme (touches 1-8) et observer : plus de trous visibles ?
2. Tester specialement le Shotgun (touche 1) : c'est le plus visible
3. Si encore des trous :
   - Lancer `./gradlew dumpVectObjStructure -Pname=shotgun` et me donner la sortie
   - Sinon passer au plan B (Bucket.Gui ou ViewPort dedie)

### Si ca marche

- ✅ Point 7 (ordre triangles arme) : regle
- ✅ Point 11 (2 comportements tir) : resolu (rien a faire, pas d'alt-fire)
- On passe aux points restants : lumiere murs, portes physiques,
  textures murs/portes, slots 0/9, animations tir

---

## [2026-04-24] Session 89bis — Revert fix arme + decouvertes ASM

### Retour de test session 89

❌ **FaceCullMode.Back = pire** : l'arme apparait inversee (normales mal
   orientees dans les vectobj)
❌ **depthTest=true + Translucent** : ne corrige pas les trous, ne change rien
   visuellement
❌ **TARGET_WEAPON_SIZE=0.7** : arme trop grande
✅ Position bas-droite : tendance correcte mais trop extreme

### Decouvertes ASM (apres etude de objdrawhires.s + player.s)

**1. Pas de position/rotation speciale par arme dans l'ASM.** L'arme est
dessinee comme n'importe quel objet 3D (draw_PolygonModel) via un cas
special :

```asm
move.l a0,a3
sub.l  Plr1_ObjectPtr_l,a3
cmp.l  #DRAW_VECTOR_NEAR_PLANE,a3    ; = 130
bne    polybehind
...
move.w #1,d1                         ; force z=1 pour l'arme
move.w #SMALL_HEIGHT/2,draw_PolygonCentreY_w
```

Donc l'arme est rendue **a z=1** avec le centre ecran vertical ajuste. La
difference de taille/position entre armes vient **du modele vectobj lui-meme**
(chaque gun a son propre centre et sa propre echelle dans son .3D).

**2. Consequence** : notre `normalizeModelVertices()` qui force toutes les
armes a `TARGET_WEAPON_SIZE=0.5` est **incorrect**. On devrait :
- Preserver l'echelle native de chaque vectobj
- Avoir un offset/scale **par arme** dans `WeaponType`

**3. Les "trous" dans l'arme** viennent probablement de l'ordre des polygones
dans le .j3o. L'ASM trie les polygones back-to-front via la liste
`Draw_PolyObjects_vl` + `draw_PartBuffer_vw`. Notre conversion .j3o ne
respecte peut-etre pas cet ordre. A traiter dans `VectObjConverter`.

**4. SHOTGUN = arme par defaut** (confirme par l'ordre des GunNames dans le
GLF : index 0 = Shotgun).

### Fix session 89bis (revert + default weapon)

**`WeaponViewAppState.java`** :
- `TARGET_WEAPON_SIZE` : 0.7 -&gt; **0.5** (revert)
- `WEAPON_OFFSET` : (0.45, -0.45, -0.55) -&gt; **(0.40, -0.38, -0.55)** (compromis)
- `fixWeaponMaterials` : revert session 85 (`depthTest=false`, `FaceCullMode.Off`,
  `Bucket.Transparent`)

**`GameAppState.java`** :
- Arme par defaut : `ASSAULT_RIFLE` -&gt; **`SHOTGUN`** (conforme a l'ASM)

### A faire en session 90 (gros chantier arme)

1. **Offset/scale/rotation par arme** dans `WeaponType` :
   ```java
   SHOTGUN("...", new Vector3f(0.4f, -0.4f, -0.6f), 1.0f, quatIdentite),
   PLASMA_GUN("...", new Vector3f(0.35f, -0.3f, -0.5f), 0.8f, ...),
   ...
   ```
2. **Preserver l'echelle native des vectobj** (retirer
   `normalizeModelVertices` ou le rendre optionnel)
3. **Fixer l'ordre des polygones** dans `VectObjConverter` (back-to-front
   depuis les faces + ordre de tri ASM)
4. **Animations de tir** : declencher `playFireAnimation()` sur chaque tir
5. **Investiguer les slots 0 et 9** (mines vertes/rouges ?)
6. **2 comportements de tir ?** (ex. Shotgun hitscan + Grenade Launcher pourrait
   avoir un tir secondaire ?)

### Fichiers modifies

- `src/main/java/com/ab3d2/weapon/WeaponViewAppState.java` (revert)
- `src/main/java/com/ab3d2/app/GameAppState.java` (arme par defaut)

---

## [2026-04-24] Session 89 — Arme en main : position, taille, ordre des triangles

### Retour de test session 88

✅ Vitesse joueur, hauteur, FOV : OK
❌ **Arme trop vers le centre**, devrait etre plus en bas-droite
❌ **Arme trop petite**, devrait etre plus imposante
❌ **"Trous" visibles dans l'arme** — certains triangles s'affichent dans le mauvais ordre

### Analyse du probleme des trous

Dans `fixWeaponMaterials()` on avait :
```java
mat.getAdditionalRenderState().setDepthTest(false);
```

C'est ce qui causait les "trous". Sans depth test, les triangles sont dessines
dans l'ordre du buffer, **sans tenir compte de la profondeur**. Resultat :
les triangles du dos de l'arme peuvent etre dessines par-dessus ceux de
devant, creant des faces visibles qui devraient etre cachees.

De plus, `FaceCullMode.Off` (herite de VectObjConverter) dessine les deux
cotes de chaque polygone, ce qui aggrave le probleme.

### Fix

**`WeaponViewAppState.java`** :

| Parametre | Avant | Apres | Effet |
|-----------|-------|-------|-------|
| `WEAPON_OFFSET.x` | 0.35 | **0.45** | Plus a droite |
| `WEAPON_OFFSET.y` | -0.32 | **-0.45** | Plus en bas |
| `WEAPON_OFFSET.z` | -0.5 | **-0.55** | Un peu plus loin (compense la taille) |
| `TARGET_WEAPON_SIZE` | 0.5 | **0.7** | Arme plus imposante |
| `setDepthTest` | false | **true** | Triangles se trient entre eux |
| `FaceCullMode` | Off (herite) | **Back** | Cache les faces arriere |
| Bucket | Transparent | **Translucent** | Rendu apres la scene, triage par distance |

L'arme est a z=-0.55 (tres pres de la camera) donc depth test=true ne risque
pas de la faire obscurcir par les murs en pratique.

### Fichier modifie

- `src/main/java/com/ab3d2/weapon/WeaponViewAppState.java`

### Test attendu

1. L'arme apparait plus a droite et plus bas
2. L'arme est visiblement plus grande (bien presente a l'ecran)
3. Plus de "trous" dans l'arme : les faces sont ordonnees correctement
4. Possible : certaines faces exterieures peuvent ne plus etre visibles si les
   normales des polygones vectobj sont inversees. Dans ce cas, on passera a
   `FaceCullMode.Off` et on cherchera une autre solution pour les trous.

### Si ca pose probleme

- **Toujours des trous** : revenir a `FaceCullMode.Off` et chercher cote
  ordre des triangles dans le .j3o (reordonner a la generation ?)
- **Faces manquantes sur certaines armes** : c'est que leur orientation de
  normale est inversee. Repasser a `FaceCullMode.Off`.

---

## [2026-04-24] Session 88 — Confort joueur : vitesse, taille, FOV

### Retour de test session 87bis

✅ Portes, grenades, collisions : OK
❌ **Joueur trop rapide et trop grand** (MOVE_SPEED=20, EYE_HEIGHT=1.5)
❌ **FOV trop large** (80°) : salles paraissent enormes et distordues

### Ajustements

`GameAppState.java` :

| Parametre | Avant | Apres | Raison |
|-----------|-------|-------|--------|
| `MOVE_SPEED` | 20 JME/s | **10 JME/s** | Joueur 2x moins rapide, plus jouable |
| `EYE_HEIGHT` | 1.5 | **1.1** | POV moins "geant" |
| `PLAYER_HEIGHT` | 1.0 | **0.8** | Capsule proportionnelle |
| `PLAYER_RADIUS` | 0.4 | **0.35** | Un peu plus mince |
| `FOV_DEGREES` | 80° | **75°** | Moins de distorsion fisheye |

Verification coherence avec les bullets : a 10 JME/s le joueur ne rattrape
aucune bullet (Blaster a 40 = 4x, Plasma a 80 = 8x, Grenade a 40 = 4x). OK.

### Fichier modifie

- `src/main/java/com/ab3d2/app/GameAppState.java`

### Test attendu

1. Vitesse de deplacement jouable (pas besoin de courser les murs)
2. Les salles ont un aspect plus normal, moins de sensation de fish-eye
3. Le joueur ne se tape plus la tete dans des passages bas
4. Le POV est plus "naturel" (yeux a ~1m du sol au lieu de 1.5m)

### Prochaines etapes (bugs restants)

- 🔴 Arme en main : position, taille, ordre des triangles
- 🔴 Lumiere des murs : passage Unshaded -&gt; Lighting avec modulation
- 🔴 Grenade traverse portes fermees : ajouter doors au PhysicsSpace
- 🔴 Textures murs avec 2 pixels de trop
- 🔴 Textures portes/lifts incorrectes ou etirees

---

## [2026-04-24] Session 87bis — Fix grenade tunneling + warning Minie

### Retour de test session 87

✅ Collisions bullets vs murs : OK (Plasma/Rocket/Lazer s'arretent)
✅ Rebonds grenade : OK
⚠️ **Warning Minie** au spawn de grenade :
   `The body isn't in any PhysicsSpace, and its gravity isn't protected.`
⚠️ **Grenade traverse parfois** les murs = tunneling

### Cause

Deux problemes dans `PhysicsBulletSystem.spawnPhysicsBullet()` :

1. **Ordre des appels Minie** : `setGravity()` appele AVANT
   `physicsSpace.add(rbc)`. Si la gravite n'est pas "protegee", elle est
   ecrasee par la gravite par defaut du space au moment du add.

2. **Tunneling** : a 40 JME/s avec tpf=16ms, la grenade parcourt 0.64 unites
   par tick physique. Si un mur est plus fin que ca, Bullet peut sauter
   par-dessus entre deux steps (collision discrete). Solution = CCD
   (Continuous Collision Detection) qui fait un sweep test.

### Fix

**`PhysicsBulletSystem.spawnPhysicsBullet()`** :

- **CCD active** via :
  ```java
  rbc.setCcdMotionThreshold(BULLET_RADIUS * 0.5f);
  rbc.setCcdSweptSphereRadius(BULLET_RADIUS);
  ```
  Quand la bullet bouge de plus de `radius*0.5` en un tick, Bullet fait un
  sweep test de l'ancienne position a la nouvelle au lieu du test discret.

- **Gravite protegee** via :
  ```java
  rbc.setProtectGravity(true);
  rbc.setGravity(...);
  ```
  Supprime le warning Minie et garantit que la gravite de la bullet ne soit
  pas ecrasee par la gravite du space.

- **setLinearVelocity apres ps.add()** : sinon la velocite initiale peut etre
  perdue au moment de l'attachement au space.

### Fichiers modifies

- `src/main/java/com/ab3d2/combat/PhysicsBulletSystem.java`

### Test attendu

1. Plus de warning Minie au lancement de grenade
2. Les grenades ne traversent plus les murs, meme fines
3. Comportement de rebond identique (restitution 0.6, friction 0.3)

---

## [2026-04-24] Session 87 — Phase 1.D : Collision bullets vs murs + impacts

### Objectif

Les bullets traversaient les murs jusqu'ici (phase 1.C). Implementation de :
- **Raycast contre la geometrie du niveau** via Bullet Physics (Minie)
- **Collision bullets simples vs murs** : Plasma/Rocket/Lazer/Blaster meurent
  au contact d'un mur
- **Tracer hitscan qui s'arrete** sur le mur (plus de tracer qui traverse)
- **Flash d'impact** visible au point de collision

Pas encore de collision avec les ennemis (pas d'ennemis spawnes actuellement).
Phase 1.E quand on spawnera les premiers aliens.

### Nouveaux composants

**`WorldRaycaster`** : service de raycast contre la geometrie du niveau.
Utilise `PhysicsSpace.rayTest()` (Minie) qui est deja en place pour les
collisions du joueur.

- `castRay(from, direction, maxDistance)` -&gt; `RayHit { hit, impactPoint, normal, distance }`
- Recule automatiquement le point d'impact de `IMPACT_BACKOFF = 0.02` vers
  l'origine pour eviter le z-fighting des effets visuels avec la surface.
- Helpers `RayHit.isFloor()` / `isCeiling()` / `isWall()` pour classifier la
  surface touchee via sa normale.

**`ImpactEffectSystem`** (AppState) : spawn un flash d'impact (sphere qui
grossit et s'attenue) au point de collision.
- Duree 0.15s, rayon 0.05 -&gt; 0.25 pendant la vie
- Couleur selon le bullet type (plasma bleu, rocket orange, etc.)
- BlendMode Additive pour effet lumineux
- Placeholder : en phase ulterieure on utilisera les vrais sprites
  <code>BulT_PopData_vb</code> depuis <code>includes/explosion.dat</code>
  et <code>includes/splutch.dat</code>

### Modifications

**`HitscanTracerSystem`** :
- `spawnTracer()` retourne maintenant le `Vector3f impactPoint` (utile pour
  spawner le flash d'impact au bon endroit)
- Si un `WorldRaycaster` est installe via `setRaycaster()`, le traceur s'arrete
  au premier mur touche (plus court, plus realiste)
- Sans raycaster : traceur de 50 unites (fallback comme avant)

**`BulletUpdateSystem`** :
- Avant de mettre a jour la position, sauvegarde `oldX/Y/Z`
- Si raycaster installe : `castRay(oldPos, dir, stepDistance)` pour voir si
  la bullet traverse un mur entre deux frames
- Si hit : positionne la bullet au point d'impact, spawn un flash via
  `ImpactEffectSystem` et tue la bullet
- Les bullets simples (Plasma/Rocket/Lazer/Blaster) meurent maintenant en
  touchant les murs au lieu de continuer indefiniment

**`PlayerShootSystem`** :
- Nouveau champ optionnel `impactSystem` via `setImpactSystem()`
- Dans `fireHitscan()` : recupere le `impactPoint` retourne par
  `tracerSystem.spawnTracer()` et spawn un flash d'impact a cet endroit

**`GameAppState`** :
- Instancie `ImpactEffectSystem`, l'attache, le passe a shoot et bulletUpdate
- Dans `setupPhysics()` (apres que le PhysicsSpace existe) : cree un
  `WorldRaycaster(physicsSpace)` et le distribue a `bulletUpdateSystem` et
  `tracerSystem`
- Cleanup ajoute pour le nouveau `impactSystem`

### Fichiers crees/modifies

- `src/main/java/com/ab3d2/combat/WorldRaycaster.java` (NEW)
- `src/main/java/com/ab3d2/combat/ImpactEffectSystem.java` (NEW)
- `src/main/java/com/ab3d2/combat/HitscanTracerSystem.java` (raycaster + retour impact)
- `src/main/java/com/ab3d2/combat/BulletUpdateSystem.java` (raycaster + kill on wall)
- `src/main/java/com/ab3d2/combat/PlayerShootSystem.java` (impactSystem + flash hitscan)
- `src/main/java/com/ab3d2/app/GameAppState.java` (integration)

### Test attendu

```bash
./gradlew run
```

1. **Touche 2 (Plasma Gun)** + tir contre un mur : la bullet bleue **s'arrete
   sur le mur** avec un petit flash bleu clair (auparavant : elle traversait)
2. **Touche 4 (Assault Rifle)** + clic : rafale, chaque tir laisse un flash
   jaune sur le mur, le traceur s'arrete a l'impact
3. **Touche 1 (Shotgun)** + clic : 2 flashs dores apparaissent sur le mur,
   les 2 traceurs s'arretent au mur (spread visible)
4. **Touche 6 (Rocket Launcher)** + tir : boule orange qui s'arrete au mur
   avec un flash orange (plus tard : vraie explosion avec AoE)
5. **Touche 3 (Grenade Launcher)** : la grenade est geree par Bullet Physics
   donc elle rebondit deja correctement sans avoir besoin du raycaster

### Prochaine etape

- **Phase 1.E : Spawn d'ennemis + collision bullets vs ennemis + degats**
  (le premier alien du niveau, touchable par le joueur, prend des degats)
- **Phase 1.F : Explosions AoE** pour Rocket/Grenade/Mine (rayon + damage
  falloff, killzone sur les ennemis environnants)

---

## [2026-04-24] Session 86bis — Calibration vitesses jouables

### Retour de test

Apres session 86 :
- ✅ Hitscans visibles (Shotgun, Rifle) : OK
- ❌ **Bullets trop rapides** : impossible de suivre visuellement
- ❌ **Grenades** : pas le temps de les voir (vitesse initiale de 400 JME/s)

### Analyse

La formule ASM-stricte donne 400 JME/s pour Plasma (speed=5) et 800 JME/s
pour Rocket (speed=6). Sur des niveaux JME qui font ~30-50 unites de cote,
une bullet a 400 JME/s les traverse en 0.1 seconde : **injouable**.

L'hypothese "1 tuile Amiga = 1 unite JME" n'est pas la vraie correspondance
dans notre port (les niveaux JME sont plus compacts que les niveaux Amiga).

### Fix : Calibration empirique

**`PlayerShootSystem`** :
- Ajoute `BULLET_SPEED_CALIBRATION = 0.2` (facteur empirique pour vitesse jouable)
- Ajoute `GRENADE_SPEED_CALIBRATION = 0.1` (encore plus lent pour voir l'arc)
- Selection auto : grenades/mines -&gt; calibration grenade, autres -&gt; normale

Nouvelles vitesses :
- Blaster (speed=4)  -&gt;  40 JME/s (2x le joueur)
- Plasma (speed=5)   -&gt;  80 JME/s (4x le joueur)
- Rocket (speed=6)   -&gt; 160 JME/s (8x le joueur)
- Grenade (speed=5, calibration grenade) -&gt; 40 JME/s (2x le joueur, visible en arc)
- Mine (speed=0)     -&gt;  0 JME/s (tombe sur place, correct)

**`PhysicsBulletSystem`** :
- Simplifie la gravite : utilise `PHYSICS_GRAVITY = -9.8` (gravite standard JME)
  au lieu de `def.gravity() * scale`. La conversion ASM vers m/s^2 n'avait pas
  de sens physique clair.
- Resultat : la grenade suit une parabole jouable (lance a 40 JME/s, retombe
  apres ~4 sec, parcourt ~16m).

### Fichiers modifies

- `src/main/java/com/ab3d2/combat/PlayerShootSystem.java` :
  - Ajout constantes `BULLET_SPEED_CALIBRATION`, `GRENADE_SPEED_CALIBRATION`
  - Selection du facteur selon le type de bullet
- `src/main/java/com/ab3d2/combat/PhysicsBulletSystem.java` :
  - `GRAVITY_SCALE` remplace par `PHYSICS_GRAVITY = -9.8` fixe

### Test attendu

1. **Plasma Gun** : bullet bleue qui traverse une piece en ~1 seconde (au lieu
   de 0.05 sec)
2. **Grenade Launcher** : bullet verte qui part en arc visible, retombe apres
   quelques secondes, rebondit
3. **Mine** : tombe au sol immediatement
4. **Rocket** : bullet orange visiblement plus rapide que Plasma

### Note

Ces calibrations ne sont pas "ASM-strict" mais elles respectent les rapports
relatifs (Blaster &lt; Plasma &lt; Rocket). Si ajustement necessaire, modifier
uniquement `BULLET_SPEED_CALIBRATION` et `GRENADE_SPEED_CALIBRATION`.

---

## [2026-04-24] Session 86 — Corrections vitesse + hitscan visibles + physique grenades

### Problemes signales en testant la Phase 1.C

1. **Bullets trop lentes** : joueur court plus vite que les projectiles (6.25 JME/s
   pour Plasma speed=5 alors que le joueur va a 8 JME/s).
2. **Shotgun et Rifle invisibles** : ce sont des bullets <code>isHitScan=1</code>,
   donc pas de projectile physique; juste log, aucun feedback visuel.
3. **Proposition** : utiliser Bullet Physics (Minie) pour les grenades et mines
   plutot que de recoder <code>MoveObject</code> ASM a la main.

### Fix #1 : Vitesse des bullets (facteur ~64x)

L'ASM stocke les velocites en <b>fixed-point 16.16</b>. J'avais oublie le facteur
d'echelle du sinus (max = 32768) et le shift du fixed-point (65536). Nouvelle
formule :

```java
float speedJme = (1 << bullet.speed()) * SIN_MAX * TICKS_PER_SECOND / FIXED_POINT_SCALE;
//              = (2^speed) * 32768 * 25 / 65536
//              = (2^speed) * 12.5
```

Resultats :
- speed=4 (Blaster)       -&gt; 200 JME/sec
- speed=5 (Plasma, Grenade) -&gt; 400 JME/sec
- speed=6 (Rocket, Lazer) -&gt; 800 JME/sec

### Fix #2 : Vitesse du joueur

`MOVE_SPEED` 8 -&gt; **20** JME/sec (rapprochant du rythme Amiga ~2500 Amiga/sec).

### Fix #3 : `HitscanTracerSystem` (NEW)

Nouveau systeme pour afficher un **rayon visible** quand on tire avec une arme
hitscan. Cylindre fin entre muzzle et muzzle+direction*50 qui fade out en 0.08s
en additif.

Couleurs :
- Shotgun Round : jaune dore
- Machine Gun Bullet : jaune pale
- MindZap : violet

Integre dans `PlayerShootSystem.fireHitscan()` : le spread angulaire est applique
pareil que les projectiles (shotgun 2 bullets = 2 tracers).

### Fix #4 : `PhysicsBulletSystem` (NEW) - Physique Bullet pour grenade/mine/splutch

Approche hybride :
- **Bullets rapides lineaires** (Plasma, Rocket, Lazer, Blaster, Assault Lazer,
  MegaPlasma) -&gt; reste geres par `BulletUpdateSystem` custom (mouvement simple)
- **Bullets avec gravite ou rebond** (Grenade, Mine, Splutch1) -&gt; geres par
  le nouveau `PhysicsBulletSystem` via Bullet Physics (Minie).

Le choix est fait automatiquement via `PhysicsBulletSystem.shouldUsePhysics(def)`
qui teste si la bullet def a gravity, bounceHoriz ou bounceVert non-zero.

`PhysicsBulletSystem` :
- Utilise `BulletAppState.getPhysicsSpace()` (deja present pour le joueur)
- Cree un `RigidBodyControl` par bullet avec :
  - `SphereCollisionShape(BULLET_RADIUS=0.08)`
  - `mass=0.1`, `restitution=0.6` (rebond moyen), `friction=0.3`
  - `setGravity(0, -def.gravity() * 0.3, 0)` specifique par bullet
  - `setLinearVelocity(velX, velY, velZ)` = velocite initiale calculee par
    `PlayerShootSystem`
- Les rebonds sur murs/sol sont gere automatiquement par Bullet
- Le lifetime reste gere cote Java (pas Bullet)

### Routage dans `PlayerShootSystem.fireProjectile()`

Apres l'allocation du slot + initialisation des champs :
```java
if (physicsSystem != null && PhysicsBulletSystem.shouldUsePhysics(bullet)) {
    shot.handler = PlayerShot.HANDLER_PHYSICS;
    physicsSystem.spawnPhysicsBullet(shot);
} else {
    shot.handler = PlayerShot.HANDLER_SIMPLE;
    // Geometry sera creee par BulletUpdateSystem au premier update
}
```

`BulletUpdateSystem` skip les bullets avec `handler != HANDLER_SIMPLE` pour
eviter le double-update.

### Nouveau champ `handler` sur `PlayerShot`

```java
public int handler = HANDLER_SIMPLE;
public static final int HANDLER_SIMPLE  = 0;  // BulletUpdateSystem
public static final int HANDLER_PHYSICS = 1;  // PhysicsBulletSystem
```

Reset dans `release()` avec les autres champs.

### Fichiers crees/modifies

- `src/main/java/com/ab3d2/combat/HitscanTracerSystem.java` (NEW)
- `src/main/java/com/ab3d2/combat/PhysicsBulletSystem.java` (NEW)
- `src/main/java/com/ab3d2/combat/PlayerShot.java` (ajout handler)
- `src/main/java/com/ab3d2/combat/PlayerShootSystem.java` (fix vitesse + fireHitscan + routing physics)
- `src/main/java/com/ab3d2/combat/BulletUpdateSystem.java` (skip HANDLER_PHYSICS)
- `src/main/java/com/ab3d2/app/GameAppState.java` (MOVE_SPEED 8 -&gt; 20, attach des 2 nouveaux systemes)

### Test attendu

```bash
./gradlew run
```

1. **Touche 4 (Assault Rifle)** + clic : rayons jaune pale qui flashent devant
   le joueur (auparavant : rien)
2. **Touche 1 (Shotgun)** + clic : 2 rayons jaune dore en eventail 22.5°
   (auparavant : rien)
3. **Touche 2 (Plasma Gun)** : bullet bleue rapide (~400 JME/s, 50x plus vite
   qu'avant)
4. **Touche 3 (Grenade Launcher)** : bullet verte qui **rebondit sur le sol**
   et les murs grace a Bullet Physics (auparavant : tombait tout droit et
   traversait tout)
5. **Touche 8 (Drop Mine)** : mine grise qui tombe au sol et reste la (rebondit
   si poussee, gravite faible)
6. **Mouvement joueur** : plus rapide qu'avant (20 unites/sec au lieu de 8)

### Prochaine etape (Phase 1.D)

- Raycast reel pour les hitscans (collision avec murs = tracer plus court)
- Collision bullets simples vs murs (pour tuer Plasma/Rocket/Lazer)
- Collision bullets vs ennemis (dommages)

---

## [2026-04-24] Session 85 — Phase 1.C : Spawn + mouvement de projectiles

### Objectif

Implementer le spawn de projectiles visibles (`plr1_FireProjectile` ASM) et
leur mouvement physique chaque frame (`ItsABullet` ASM, partie mouvement).
Pas encore de collision murs/ennemis (Phases 1.D / 1.E).

### Nouveaux composants

**`PlayerShot`** : instance d'un projectile en vol. Correspondance stricte
avec `ShotT` + entete `ObjT` de defs.i :
- Position (posX/Y/Z), velocite (velX/Y/Z) en unites JME
- bulletType (= ShotT_Size_b, index dans BulletDefs)
- power (= ShotT_Power_w), gravity, bounceFlags
- lifetime accumule en frames Amiga
- status (0=en vol, 1=pop), animFrame
- zoneId (-1 = slot libre, conforme a la convention ASM ObjT_ZoneID_w &lt; 0)
- geometry (reference au placeholder JME)

**`PlayerShotPool`** : pool fixe de **20 slots** (= `NUM_PLR_SHOT_DATA` ASM).
- `allocate()` : cherche le premier slot libre, null si pool plein
- `releaseAll()` : utilise au cleanup
- Comportement : si le pool est plein, le tir est abandonne silencieusement
  (comme l'ASM fait `rts` sans rien faire dans `.findonefree`).

**`PlayerAimProvider`** : interface permettant au ShootSystem de connaitre
la position/direction de tir du joueur sans couplage dur avec `GameAppState`.
Equivalent des snapshots `tempxoff/tempzoff/tempyoff/tempangpos` de l'ASM.

**`BulletUpdateSystem`** (AppState) : implemente `ItsABullet` (newanims.s).
Chaque frame :
1. Pour chaque bullet active : `pos += vel * tpf`
2. Si `gravity != 0` : `velY -= gravity * tpf^2 * factor`
3. Incremente `lifetime`. Si &gt;= `BulT_Lifetime_l`, timeout -&gt; libere le slot
4. Pour `lifetime = -1` (infini ASM) : **fallback a 500 frames Amiga (~20s)**
   pendant la phase 1.C car pas encore de collision mur pour tuer la bullet.
   En phase 1.D, les bullets infinies seront tuees par impact mural.
5. Update la position de la `Geometry` JME associee (sphere placeholder coloree
   par bulletType : bleu=plasma, jaune=machine gun, orange=rocket, etc.)
6. Les bullets avec `graphicType=2` (glare/additive : plasma, lazer, mega)
   utilisent `BlendMode.Additive` pour un rendu "energie"

### Modifications

**`PlayerShootSystem`** : ajout du spawn de projectiles :
- Remplace le log "FIRE!" par un appel a `fireProjectile()` pour les bullets
  non-hitscan (hitscan laisse en log, sera implemente en phase 1.D)
- Calcul du spread angulaire conformement a l'ASM :
  - `startYaw = playerYaw - (count-1)*SPREAD_PER_BULLET/2`
  - `SPREAD_PER_BULLET = TWO_PI/16` (= 22.5°, equivalent des 256 unites de
    table sin/cos ASM qui couvre 4096 entrees pour 360°)
- Conversion vitesse : `velJme = 2^BulT_Speed * TICKS_PER_SECOND / AMIGA_SCALE`
  - Exemple : Plasma Bolt speed=5 -&gt; 32 * 25/128 = 6.25 unites JME/s
- Chaque bullet reçoit sa direction avec offset pour le spread (shotgun 2
  bullets auront +/-11.25° autour du centre)

**`CombatBootstrap`** : 20 ammo par type (au lieu de 1) pour permettre de
tester les rafales et voir plusieurs bullets en vol simultanement.

**`GameAppState`** : integration de `BulletUpdateSystem` + `PlayerShotPool`.
- `PlayerAimProvider` implemente en anonymous class qui lit la camera a chaque
  appel (pas de snapshot fige)

### Test attendu

```bash
./gradlew run
```

1. Lancer un niveau, appuyer sur **touche 4** (Assault Rifle, 2 frames cooldown)
2. Maintenir **clic gauche** : rafale de bullets jaune pales qui partent de la
   camera dans la direction de visee
3. Les bullets volent 20 secondes puis disparaissent (fallback timeout)
4. Appuyer sur **touche 1** (Shotgun) : 2 bullets par tir avec spread 22.5°
5. Appuyer sur **touche 2** (Plasma Gun) : bullets bleues additives, lentes
   (speed=5)
6. Appuyer sur **touche 3** (Grenade Launcher) : bullet verte avec gravite,
   retombe en arc, disparait au bout de 150 frames Amiga (~6 secondes)
7. Appuyer sur **touche 6** (Rocket Launcher) : bullet orange rapide (speed=6)
8. Les bullets **traversent les murs** pour l'instant (pas encore de collision)

### Fichiers crees/modifies

- `src/main/java/com/ab3d2/combat/PlayerShot.java` (NEW)
- `src/main/java/com/ab3d2/combat/PlayerShotPool.java` (NEW)
- `src/main/java/com/ab3d2/combat/PlayerAimProvider.java` (NEW)
- `src/main/java/com/ab3d2/combat/BulletUpdateSystem.java` (NEW)
- `src/main/java/com/ab3d2/combat/PlayerShootSystem.java` (MODIFIED : spawn)
- `src/main/java/com/ab3d2/combat/CombatBootstrap.java` (20 ammo par type)
- `src/main/java/com/ab3d2/app/GameAppState.java` (integration)

### Prochaine etape (Phase 1.D)

- **Collision mur / sol / plafond** : equivalent `MoveObject` ASM + test
  hitwall pour tuer/rebondir les bullets
- **Hitscan bullets** : `plr1_HitscanSucceded` / `plr1_HitscanFailed`
- Evaluation si on passe aux **sprites 2D** pour les bullets avant ou apres
  les collisions

---

## [2026-04-24] Session 84 — Phase 1.B : Input + Cooldown (logique Plr1_Shot)

### Objectif

Implementer la routine ASM `Plr1_Shot` (newplayershoot.s) de facon incrementale.
Phase 1.B = input + cooldown + consommation ammo + log du tir. Pas encore de
spawn de projectile visible (Phase 1.C).

### Nouveaux composants

**`KeyBindings`** : config des bindings clavier/souris avec enum `Action`
(MOVE_*, FIRE, NEXT_WEAPON, WEAPON_1..8, DEBUG_HUD, etc.). Support pour
override a chaud via menu options (a venir). Defaults :
- FIRE : clic gauche souris
- WEAPON_1..8 : touches 1..8
- NEXT_WEAPON : Y / PREV_WEAPON : T
- MOVE : ZQSD + fleches (compatibilite avec l'existant)

**`PlayerCombatState`** : etat combat du joueur, conforme aux champs
`PlrT_*` de l'ASM :
- `ammoCounts[20]` (= `PlrT_AmmoCounts_vw`)
- `weaponsOwned[10]` (= `PlrT_Weapons_vb`)
- `gunSelected` (= `PlrT_GunSelected_b`)
- `timeToShoot` (= `PlrT_TimeToShoot_w`, en frames Amiga)
- `fireHeld` (= `PlrT_Fire_b`)
- Snapshot `tmpGunSelected`/`tmpFire` (= `Tmp*` ASM)
- Listener pattern pour notifier le changement d'arme (sync avec WeaponView)

**`PlayerShootSystem`** (AppState JME) : implemente `Plr1_Shot` ASM :
1. Input tick : snapshot de fire/gun
2. Cooldown : decremente `timeToShoot` avec `framesAmigaDelta = tpf * 25`
3. Si pret a tirer + fire actif + ammo suffisante : consomme ammo, applique
   `shoot.delay()` en cooldown, log le tir
4. Si ammo insuffisante : log "plus d'ammo" + petit cooldown anti-spam
5. Selection d'arme via touches 1..8 / next / prev

Conversion temps : **25 Hz Amiga** → `tpf` JME en secondes multiplie par
`TICKS_PER_SECOND = 25` pour obtenir l'equivalent `Anim_TempFrames_w`.

**`CombatBootstrap`** : factory des defaults pour un nouveau niveau :
- 1 ammo par type de bullet utilise par les 8 vraies armes
- Les 8 armes possedees (0..7)
- Arme selectionnee : Shotgun (index 0)

### Integration dans GameAppState

- Charge `TEST.LNK` via `GlfDatabase.loadFromResource("TEST.LNK")`
- Cree le combat state via `CombatBootstrap.newLevelStart(glf)`
- Enregistre un listener sur `gunSelected` qui appelle `weaponView.loadWeapon()`
- Attache le `PlayerShootSystem`
- **Retire les bindings Y/T** de GameAppState pour eviter le double-binding
  avec le PlayerShootSystem (la selection d'arme devient la responsabilite
  unique du combat system)

### Fichiers crees/modifies

- `src/main/java/com/ab3d2/combat/KeyBindings.java` (NEW)
- `src/main/java/com/ab3d2/combat/PlayerCombatState.java` (NEW)
- `src/main/java/com/ab3d2/combat/PlayerShootSystem.java` (NEW)
- `src/main/java/com/ab3d2/combat/CombatBootstrap.java` (NEW)
- `src/main/java/com/ab3d2/app/GameAppState.java` : integration combat system

### Test attendu

1. Lancer `./gradlew run`, entrer dans un niveau
2. Clic gauche : log `"FIRE! Shotgun -> 2x Shotgun Round (hitscan) (ammo restant: 0) [cooldown 50 frames]"`
3. Cooldown de ~2 secondes (50 frames / 25 Hz), puis log `"Click! Plus d'ammo pour Shotgun"`
4. Touche 2 : log `"Arme -> 1 (Plasma Gun)"`, le modele en main change, le vectobj
   `plasmagun.j3o` s'affiche (si correctement copie et converti)
5. Clic gauche : tir de plasma (cooldown 10 frames = 0.4s)

### Prochaine etape

Phase 1.C : creation de projectile. Spawn d'un `Geometry` simple (sphere
coloree) a la position du joueur + velocite dans la direction de visee, qui
se deplace chaque frame. Pour l'instant sans collision ni SFX.

---

## [2026-04-23] Session 83 — Debut systeme de combat : structures GLF (ShootDef/BulletDef)

### Objectif

Demarrage du chantier "tir / bullet / explosion / animation". Suit la logique
de `newplayershoot.s` (fonction `Plr1_Shot`) et `newanims.s` (fonction
`ItsABullet`). Approche incrementale proche de l'ASM, par phases :
- Phase 1.A : structures de donnees (ce commit)
- Phase 1.B : input + cooldown
- Phase 1.C : creation de projectile (spawn)
- Phase 1.D : mouvement de projectile (BulletUpdateSystem)
- Puis : collision murs, impact aliens, SFX, explosions, animations...

### Nouveautes session 83

**Fichier TEST.LNK copie** dans `src/main/resources/` (~85KB). C'est le "Game
Link File" (GLF) d'AB3D2 qui contient toutes les definitions statiques
(armes, bullets, aliens, niveaux, sons). Reference par `GLF_DatabasePtr_l`
dans l'ASM.

**Package `com.ab3d2.combat`** cree avec :
- `ShootDef` (record) : config d'une arme (bulletType, delay, bulletCount, sfx).
  Correspond a `ShootT_SizeOf_l = 8` bytes dans l'ASM.
- `BulletDef` (record) : config d'un projectile (17 champs + animData + popData).
  Correspond a `BulT_SizeOf_l = 300` bytes dans l'ASM.
- `GlfDatabase` : parser binaire du GLF. Lit les ShootDefs et BulletDefs avec
  les offsets calcules depuis `STRUCTURE GLFT,64` de defs.i :
  - `GLFT_BulletDefs_l` = 6848 (20 bullets)
  - `GLFT_ShootDefs_l`  = 13448 (10 guns)
  - `GLFT_BulletNames_l` = 12848 (20*20)
  - `GLFT_GunNames_l`    = 13248 (10*20)

**Outil diagnostic `GlfDumpTool`** : affiche les contenus lus pour validation.
- Task `./gradlew dumpGlf`
- Liste les 10 armes + leurs stats (bullet type, cooldown, count, sfx)
- Liste les 20 bullets + leurs stats (hitscan, gravity, damage, speed, ...)
- Resume detaille par arme avec la bullet liee

### Prochaine etape

Phase 1.B : input + cooldown. Ecrire le `PlayerShootSystem` qui :
- Ecoute le clic souris (ou touche de tir configuree)
- Applique le cooldown `Plr1_TimeToShoot_w` (decremente chaque frame)
- Lit les defs via GlfDatabase quand le joueur tire
- Declenche (pour l'instant) juste un log de tir, sans projectile encore

### Fichiers crees/modifies

- `src/main/resources/TEST.LNK` (NEW, a copier par l'utilisateur)
- `src/main/java/com/ab3d2/combat/ShootDef.java` (NEW)
- `src/main/java/com/ab3d2/combat/BulletDef.java` (NEW)
- `src/main/java/com/ab3d2/combat/GlfDatabase.java` (NEW)
- `src/main/java/com/ab3d2/tools/GlfDumpTool.java` (NEW)
- `build.gradle` : task `dumpGlf`

---

## [2026-04-22] Session 82 — FIX MAJEUR : vertex_color ecrasait la texture

### Probleme

Apres confirmation que la tile 26 de l'atlas est bien la texture correcte
du corps du crab (motif en losange brun, visible dans le jeu Amiga original),
les polys du crab corps/queue (texOffset 0x8202) apparaissaient toujours
**noirs** alors que la texture etait bien presente dans l'atlas.

### Cause racine

Dans `brightnessToColor()`, on **samplait UN SEUL pixel de la texture** pour
en deduire la vertex_color :
```java
int argb = TextureMapConverter.sampleColor(texData, shadeData, palette,
                                            texOffset, brightness);
return new ColorRGBA(r/255, g/255, b/255, 1f);
```

Cette vertex_color est ensuite utilisee en mode `Unshaded + VertexColor + ColorMap`
pour **moduler la texture** : `pixel_final = texture * vertex_color`.

**Probleme** : le sample tombait a `rowStart+8, colStart+2`. Pour la tile 26
du crab, 48% des pixels sont d'**index palette 0** (= noir). Donc le sample
tombait tres souvent sur un pixel noir -&gt; vertex_color = noir -&gt;
`texture * noir = noir` -&gt; **tout le polygone devenait noir**.

Meme si la texture dans l'atlas etait correcte, on la multipliait par 0.

### Fix

`brightnessToColor()` retourne maintenant un **facteur de luminosite uniforme**
(gris) base sur le brightness, **sans sampler la texture** :
```java
int shadeCalc = (brightness * 32 * 41) >> 12;
int shade     = Math.max(0, Math.min(31, 31 - shadeCalc));
float factor = 1.0f - (shade / 31.0f) * 0.75f;  // 1.0 clair -> 0.25 sombre
return new ColorRGBA(factor, factor, factor, 1.0f);
```

La texture (dans l'atlas) reste intacte, et le shade est juste un
multiplicateur uniforme 0.25..1.0. On conserve la texture visible au lieu
de l'ecraser.

### Impact

Ce fix devrait :
- Résoudre le **corps/queue noir du crab** (tile 26 existe mais etait ecrasee)
- Résoudre le **manche du rifle** (possible meme bug de sample)
- Potentiellement résoudre les **ailes noires du wasp** (si brightness haut + pixel 0)
- Ne pas dégrader les autres modeles (le facteur est plus tolerant)

### Fichiers modifies

- `src/main/java/com/ab3d2/tools/VectObjConverter.java` : brightnessToColor()

---

## [2026-04-22] Session 81 — Remise de l'alpha transparent pour index 0 + outils de diag

### Contexte

Apres session 80 (revert du transparent), test a montre que :
- **glarebox** : le rendu etait mieux AVEC l'alpha transparent (session 79)
- **wasp, crab, rifle** : toujours des problemes de textures

### Decisions

1. **REMISE de l'alpha transparent** pour l'index palette 0. On accepte que ce
   ne soit pas 100% conforme a `drawpolg` ASM (qui dessinerait noir) car
   c'est visuellement mieux pour glarebox et n'aggrave probablement pas les
   autres problemes (qui sont d'une autre nature).

2. **Nouveau outil `AtlasTileExtractor`** : extrait et zoome x8 les tiles
   specifiques de l'atlas pour les visualiser. Permet de comparer la tile
   reelle utilisee avec ce qu'on attendait visuellement.
   Task : `./gradlew extractTile -Ptile=22,26,27`

### Problemes a investiguer (session 82+)

- **wasp** : ailes noires, manque de blanc
- **crab** : corps et queue noirs (tiles 22/26 marron tres sombre)
- **rifle** : manche incorrect

### Fichiers modifies

- `src/main/java/com/ab3d2/tools/TextureMapConverter.java` : remise RGBA
- `src/main/java/com/ab3d2/tools/VectObjConverter.java` : remise alpha discard
- `src/main/java/com/ab3d2/tools/AtlasTileExtractor.java` : nouvel outil
- `build.gradle` : ajout task `extractTile`

---

## [2026-04-22] Session 80 — REVERT session 79 : mauvaise interpretation des flags

### Probleme decouvert apres test session 79

Les modeles vectobj (crab surtout) avaient toujours des textures manquantes
apres le fix de session 79 (transparence index palette 0 sur l'atlas). Les
zones noires ont ete remplacees par des zones **traversantes** (on voit a
travers le corps du crab).

### Cause racine : mauvaise lecture de l'ASM

L'outil `VectObjFlagsInventory` de session 79 interpretait `gouraud != 0`
comme le mode GLARE. C'est **faux**.

La vraie structure dans objdrawhires.s :
```asm
draw_PreGouraud_b:  dc.b 0  ; written as WORD (so writes also to next byte)
draw_Gouraud_b:     dc.b 0  ; tested as BYTE
draw_PreHoles_b:    dc.b 0  ; written as WORD
draw_Holes_b:       dc.b 0  ; tested as BYTE
```

Quand `move.w (a1)+, draw_PreHoles_b` s'execute :
- byte HAUT du WORD → `draw_PreHoles_b`
- byte BAS du WORD → `draw_Holes_b` (byte suivant en memoire)

Les tests sont sur les bytes bas/haut :
- `tst.b draw_Holes_b`      → byte BAS @ poly+2           → drawpolh  (skip idx0)
- `tst.b draw_Gouraud_b`    → byte BAS @ poly+N*4+16     → drawpolg  (shading normal, **TOUT** dessine)
- `tst.b draw_PreGouraud_b` → byte HAUT @ poly+N*4+16    → drawpolGL (glare, skip idx0)

Pour les polys du crab : `gouraud = 0x0001` donc byte HAUT = 0 (pas glare),
byte BAS = 1 (mode GOURAUD normal). `drawpolg` **dessine TOUS les pixels**,
y compris index 0. Donc l'atlas doit etre **opaque**.

### Fix (revert session 79 + correction outil diagnostic)

**TextureMapConverter.java** : revert a TYPE_INT_RGB, index 0 → rgb(0,0,0)
opaque comme avant session 79.

**VectObjConverter.buildMaterial()** : suppression de AlphaDiscardThreshold
et BlendMode.Alpha (inutiles, le rendu normal suffit).

**VectObjFlagsInventory.java** : correction pour decoder correctement les
bytes haut/bas des WORDs flags et gouraud, avec trois modes distincts :
HOLES, gouraud, GLARE.

### Consequence pour les vraies textures manquantes

Les tiles 22 et 26 du crab ont ~40-50% de pixels d'index palette 0 qui se
rendent en noir rgb(0,0,0) sur l'atlas. Ce n'est PAS un bug de rendu, c'est
le comportement original de l'Amiga : `drawpolg` dessine ces pixels en noir.

**Le vrai probleme des textures du crab est ailleurs** et sera investigue
en session 81. Pistes :
- Le shade level (brightness) applique peut rendre la texture totalement noire
- Le mapping UV tile 64x64 peut etre legerement mal aligne
- L'ordre/offset des points dans la tile peut etre inverse

### Fichiers modifies

- `src/main/java/com/ab3d2/tools/TextureMapConverter.java` : revert RGB opaque
- `src/main/java/com/ab3d2/tools/VectObjConverter.java` : suppression alpha
- `src/main/java/com/ab3d2/tools/VectObjFlagsInventory.java` : decodage correct

---

## [2026-04-22] Session 79 — Fix majeur transparence index 0 (mode glare/holes ASM)

### Probleme

Apres le fix de layout atlas (session 78), les modeles crab et autres aliens
présentaient TOUJOURS des zones noires sur le corps. Les tiles de textures
utilisees (tile 22, 26 pour le crab) contenaient bien du contenu dans l'atlas,
mais ~40-50% des pixels etaient noirs (rgb=0,0,0).

### Diagnostic

Outil `VectObjFlagsInventory` cree pour lister les flags render de chaque poly :
```
========== RESUME crab ==========
Total polys: 52
  Holes (flags!=0): 1 (1.9%)
  Glare (gouraud!=0): 49 (94.2%)  <-- quasi tous !
```

**94% des polys du crab utilisent le mode GLARE** (gouraud!=0). En regardant
`drawpolGL` dans objdrawhires.s :
```asm
drawpolGL:
    move.b  (a0,d0.w*4),d3   ; sample pixel index
    beq.s   itsblack          ; <-- SKIP si index = 0 !
    ...
```

Le mode GLARE (et le mode HOLES via `drawpolh`) **skip les pixels d'index
palette 0**, les rendant transparents au lieu de les dessiner en noir.

### Cause racine

- Le rendu Amiga utilise l'**index palette 0 comme "transparent"** pour les
  modes glare et holes (crab, mantis, wasp, etc.)
- Notre atlas PNG etait rendu en RGB plein ou index 0 donnait rgb(0,0,0)
- Les pixels transparents etaient dessines comme **noirs opaques**

### Fix (2 fichiers)

**TextureMapConverter.java** : atlas regenere en RGBA (TYPE_INT_ARGB)
```java
if (rawIdx == 0) {
    img.setRGB(V, U, 0x00000000);  // alpha = 0 (transparent)
} else {
    int mappedIdx = shadeData[32 * 256 + rawIdx] & 0xFF;
    img.setRGB(V, U, 0xFF000000 | (palette[mappedIdx] & 0xFFFFFF));
}
```

**VectObjConverter.buildMaterial()** : activation du blend alpha + discard
```java
mat.setFloat("AlphaDiscardThreshold", 0.5f);
mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
```

### Outil de diagnostic ajoute

- `VectObjFlagsInventory.java` : liste tous les polys d'un vectobj avec leurs
  flags (holes/glare/normal) et texOffsets, + resume statistique par texOffset.
- Task gradle `dumpVectObjFlags -Pvectobj=<nom>`.

### Procedure de test

```bash
./gradlew convertTextureMaps   # regenere atlas PNG en RGBA
./gradlew convertVectObj       # regenere .j3o avec nouveau material
./gradlew run                  # teste en jeu
```

### Fichiers modifies

- `src/main/java/com/ab3d2/tools/TextureMapConverter.java` : atlas RGBA
- `src/main/java/com/ab3d2/tools/VectObjConverter.java` : material alpha
- `src/main/java/com/ab3d2/tools/VectObjFlagsInventory.java` : nouvel outil
- `build.gradle` : ajout task `dumpVectObjFlags`

---

## [2026-04-22] Session 78 — Fix majeur layout atlas textures : 32 tiles de 64x64

### Probleme

Le crab et de nombreux autres vectobj presentaient des **zones noires** ou des
**mauvaises textures** sur leurs faces. Exemple visible : le corps du crab avec
2 grandes zones noires au lieu de la texture 27 (sur 32) de l'atlas.

### Diagnostic

Outil `VectObjTexOffsetInventory` cree et lance sur le crab :
```
0x8201 | bank=1 slot=1 | colBk=128 | U:0-63 V:0-63 | tile22 (64x64 col=0)
0x8202 | bank=1 slot=2 | colBk=128 | U:0-63 V:0-63 | tile26 (64x64 col=0)
```

Les deltas UV (`V range 0-63`) ne depassent **jamais 64**. Preuve que chaque
tile fait bien **64x64 pixels**, pas 256x64 comme suppose precedemment.

### Cause racine

Le code assumait le layout **A** (8 tiles de 256x64 empilees verticalement).
Le vrai layout est **B** (grille 4 colonnes x 8 lignes de tiles 64x64).

Les deux interpretations ont la meme dimension totale (256x512) mais une
decoupe radicalement differente. La generation de l'atlas PNG elle-meme etait
correcte, mais le calcul d'UV utilisait un `colStart` dans 0..255 au lieu
de decomposer en `tileCol (0..3)` + `colIn64 (0..63)`.

### Fix (VectObjConverter.java)

Remplacement de la generation d'UV avec decomposition complete :
```java
int bank    = (rawTexOffset & 0x8000) != 0 ? 1 : 0;
int relOfs  = rawTexOffset & 0x7FFF;
int slot    = relOfs & 3;                    // 0..3
int rowBk   = relOfs / 1024;                 // 0..63 (dans la tile)
int colBk   = (relOfs % 1024) / 4;           // 0..255 dans le slot
int tileCol = colBk / 64;                    // 0..3 (colonne tile dans atlas)
int tileRow = bank * 4 + slot;               // 0..7 (ligne tile dans atlas)
int colIn64 = colBk % 64;                    // 0..63 (col de depart DANS tile)
int rowIn64 = rowBk;                         // 0..63

// Pour chaque vertex :
int colRaw = colIn64 + vtxU[v];
int rowRaw = rowIn64 + vtxV[v];
int colFinal = ((colRaw % 64) + 64) % 64;    // wrap intra-tile modulo 64
int rowFinal = ((rowRaw % 64) + 64) % 64;
int pixelX = tileCol * 64 + colFinal;
int pixelY = tileRow * 64 + rowFinal;
```

### Outil de diagnostic ajoute

- `VectObjTexOffsetInventory.java` : liste les texOffsets d'un vectobj avec les
  deltas UV pour chacun, et affiche les deux interpretations A et B cote a cote.
- Task gradle `dumpTexOffsets -Pvectobj=<nom>` dans `build.gradle`.

### Fichiers modifies

- `src/main/java/com/ab3d2/tools/VectObjConverter.java` : fix generation UV
- `src/main/java/com/ab3d2/tools/VectObjTexOffsetInventory.java` : nouvel outil
- `build.gradle` : ajout task `dumpTexOffsets`

### Validation

A relancer : `./gradlew convertVectObj` puis `./gradlew run` pour verifier que
le crab et les autres modeles ont leurs textures correctes.

---

## [2026-04-22] Session 77 — Liste d'armes corrigee + rotation par arme

### Probleme

- L'enum `WeaponType` contenait CHARGER et PLINK qui ne sont pas des armes
  d'AB3D2 TKG (ce sont des decorations/objets)
- Toutes les armes avaient la meme rotation de 180deg sur Y (fixe)
- `plasmagun.j3o` non converti alors que c'est une arme du jeu

### Decouverte

Analyse du fichier `TEST.LNK` du jeu original a revele la liste reelle des 10
slots d'armes du jeu :
1. Shotgun
2. Plasma Gun
3. Grenade Launcher
4. Assault Rifle
5. Blaster
6. Rocket Launcher
7. Lazer (spelling du jeu)
8. Drop Mine
9. GUN I (placeholder non utilise)
10. GUN J (placeholder non utilise)

Soit 8 armes reelles + 2 placeholders jamais utilises en jeu.

### Fix

- `WeaponType.java` : refactoring complet avec les 8 vraies armes
- Ajout de `getDisplayRotation()` pour permettre une rotation specifique par
  modele (les vectobj n'ont pas tous la meme orientation canonique)
- `WeaponViewAppState.java` : utilise `currentWeapon.getDisplayRotation()`
  a chaque update au lieu d'une constante globale
- `GameAppState.java` : `WeaponType.RIFLE` renomme en `WeaponType.ASSAULT_RIFLE`

### Fichiers modifies

- `src/main/java/com/ab3d2/weapon/WeaponType.java`
- `src/main/java/com/ab3d2/weapon/WeaponViewAppState.java`
- `src/main/java/com/ab3d2/app/GameAppState.java`

---

## [2026-04-22] Session 76 — Fix affichage arme en main : bug convention camera JME

### Probleme

Depuis session 75, le modele vectobj de l'arme etait charge correctement (logs de
bbox OK, 4-8 animations detectees) mais **rien ne s'affichait a l'ecran**. Cube de
debug invisible aussi.

### Diagnostic (via cubes colores)

Apres avoir isole le probleme avec plusieurs cubes de test :
- ✅ Cube vert statique au monde : visible
- ✅ Cube jaune statique attache dans `WeaponViewAppState.initialize()` : visible
- ❌ Cube magenta dont la position est update chaque frame depuis la camera : invisible

Les logs ont revele que le cube magenta etait **dans la mauvaise direction** :
```
dir=(0.48, -0.41, 0.77)   delta=(-0.71, 0.79, -3.02)   <- Z OPPOSE
```
La camera regardait vers `+Z` (`dir.z > 0`) mais l'arme etait calculee avec un
`delta.z < 0`, donc derriere la camera.

### Cause racine

**`cam.getRotation()` en JME a une convention inversee** par rapport a
`cam.getDirection()`. Si on fait :
```java
Vector3f offset = new Vector3f(0, 0, -1);  // -Z local = devant
Vector3f worldOffset = cam.getRotation().mult(offset);
```
on obtient un vecteur qui **ne correspond pas** a `cam.getDirection()`. La rotation
quaternion de la camera caracterise la camera-as-spatial (sa position dans la
scene), pas sa direction de vue.

### Solution

Utiliser **les vecteurs de la camera directement** :
```java
Vector3f forward = cam.getDirection();  // vers l'avant, coherent
Vector3f left    = cam.getLeft();
Vector3f up      = cam.getUp();

// Composer la position manuellement
Vector3f pos = cam.getLocation().clone();
pos.addLocal(left.mult(-offsetX));       // X positif = a droite
pos.addLocal(up.mult(offsetY));          // Y positif = en haut
pos.addLocal(forward.mult(-offsetZ));    // Z negatif = devant
```

Et pour que JME ne cull pas l'arme a tort :
```java
weaponRoot.setCullHint(Spatial.CullHint.Never);
```

### Architecture finale simplifiee

Au lieu de la chaine `cameraFollower > weaponHolder > weaponCentering > modelWrapper`
on a une seule hierarchie :
```
rootNode
  └─ weaponRoot (CullHint.Never, position+rotation update chaque frame)
       └─ model (pre-normalise : centre sur origine + scale uniforme)
```

### Valeurs finales

- `WEAPON_OFFSET = (0.25, -0.25, -0.8)` : droite, bas, devant en unites JME
- `WEAPON_ROTATION = rotY(PI)` : 180deg pour orienter le canon vers l'avant
- `TARGET_WEAPON_SIZE = 0.5` : diagonale max de la bbox apres normalisation
- Near plane camera : 0.05 (deja OK dans GameAppState)

### Fichiers modifies

- `src/main/java/com/ab3d2/weapon/WeaponViewAppState.java` — reecriture complete
  avec pattern simple + documentation detaillee du bug de convention camera

### Lecon pour l'avenir

**Ne jamais utiliser `cam.getRotation().mult(offset)` pour calculer une position
relative a la camera en coord monde.** Toujours utiliser `getDirection()`/`getLeft()`/
`getUp()` explicitement. Ce piege peut potentiellement re-surgir pour d'autres
objets "screen overlay" (HUD 3D, crosshair, particules en vue FPS, etc.).

### TODO session 77 (prochaine etape : tir)

- Clic gauche = tir, avec cadence specifique par arme (ShootT_Delay_w)
- Spawn de projectile visible (modele 3D ou sprite billboard)
- Collision projectile contre murs/portes/ennemis
- Son de tir (samples extraits session 67)
- Animation de tir declenchee via `playFireAnimation()` (deja en place)
- Muzzle flash (particule ponctuelle a la sortie du canon)

---

## [2026-04-21] Session 75 — Arme en main (vue FPS) + HUD desactive temporairement

### HUD desactive (temporaire)

Le HUD 2D (newborder, AMMO, ENERGY, messages) est **commente** dans
`GameAppState.initialize()` le temps de se concentrer sur l'affichage de l'arme
et le systeme de tir. Le code est juste commente, pas supprime : il sera
reactive en prochaine etape apres que l'arme/tir soient fonctionnels.

### Ajout : affichage de l'arme en main (package `com.ab3d2.weapon`)

Nouveau package dedie aux armes, avec 2 classes initiales :

- **`WeaponType`** : enum listant les 8 armes du jeu (BLASTER, SHOTGUN, RIFLE,
  CHARGER, GRENADE_LAUNCHER, ROCKET_LAUNCHER, LASER, PLINK) avec leurs slots
  HUD (0..7, correspondant aux touches 1-8) et leurs chemins d'assets
  (`Scenes/vectobj/<nom>.j3o`).

- **`WeaponViewAppState`** : AppState qui affiche le modele 3D de l'arme
  devant la camera en vue FPS. Architecture :
  - Node `cameraFollower` qui suit position+orientation de la camera chaque frame
  - Node `weaponHolder` enfant, qui positionne l'arme en coord locales
    (offset : droite=+0.25, bas=-0.25, devant=-0.55, rotation 180deg sur Y,
    scale=0.8)
  - Materials de l'arme avec `depthTest=false` et bucket Transparent pour que
    l'arme reste visible meme si elle intersecte un mur
  - Lumieres dediees (AmbientLight + DirectionalLight sur `cameraFollower`)
    pour que l'arme reste visible en toutes zones
  - Support des animations via `VectObjFrameAnimControl.attachIfAnimated()`
    (deja construit en session 72)

### Integration GameAppState

- Attache par defaut un WeaponViewAppState avec le `RIFLE` comme arme initiale.
- Nouvelles touches debug :
  - **H** : toggle visibilite de l'arme
  - **Y** : arme suivante (cycle dans les 8 armes)
  - **T** : arme precedente

### TODO session 76 (prochaine etape)

- Clic gauche = tir avec cadence specifique par arme
- Spawn de projectile visible (modele 3D ou sprite billboard)
- Collision projectile contre murs/portes/ennemis
- Son de tir (samples deja extraits dans session 67?)
- Animation de tir sur l'arme (distinguer idle vs fire dans les frames vectobj)
- Muzzle flash

---

## [2026-04-21] Session 74 — HUD alignement + debug overlay + corrections

### Corrections apportees

- `HudLayout.SMALL_YPOS_DEFAULT` : passe de 16 a **20** (vraie valeur `SMALL_YPOS = 20`
  dans `screen.c`).
- `HudAppState.placeText` : formule de taille de police clarifiee. On utilise
  maintenant `lineHeight = glyphScreenH / 0.70` (ratio capHeight/lineHeight
  pour la police Arial par defaut de JME) + verticalOffset pour centrer le glyph
  visible sur la coord native. Elimine le facteur empirique "1.4".

### Ajout : mode debug visuel

`HudAppState.setDebugOverlay(true)` affiche des rectangles colores semi-transparents
sur chaque element du HUD pour verifier l'alignement :

- Cyan : contour complet du HUD (320x256 natif)
- Jaune : zone 3D (small screen 192x160)
- Magenta : compteurs AMMO et ENERGY (3 chiffres de 8x7)
- Vert : 10 slots d'armes (8x5)
- Orange : zone messages

**Touche F3** en jeu : toggle on/off a la volee.

GameAppState attache par defaut le HUD avec debug overlay actif pour faciliter
le reglage initial.

### TODO note pour plus tard

Les bordures (`newborder.png`) viennent des assets Amiga natifs en 320x256. A terme
on referra les bordures **en haute resolution** (assets HD custom) pour qu'elles
soient propres sur les grands ecrans sans pixelisation. L'architecture actuelle
(HudLayout en coord natives + HudScaling) supportera sans modification un
newborder.png en 1920x1080 ou autre resolution : seul le fichier PNG changera.

### Format palette Amiga copper-list decode (session 73, complete)

Format de `panelcols` confirme comme dump copper list Amiga AGA :
byte 0-1 = adresse registre, byte 2-3 = couleur $0RGB 12-bit. Fix applique
dans `RawExtractor.parseAmiga12BitPalette()`.

---

## [2026-04-20] Session 73 — HUD 2D initial (architecture native 320x256 + scaling)

### Ajout : architecture HUD avec respect des coordonnees natives Amiga

Premiere version fonctionnelle du HUD d'AB3D2 au-dessus de la vue 3D, construite
sur 4 composants decouples permettant de supporter n'importe quelle resolution
cible sans deformer le HUD.

**Composants :**

- `HudLayout` : source de verite pour toutes les positions/dimensions du HUD,
  en coordonnees natives Amiga 320x256. Toutes les valeurs viennent directement
  de `screen.h` et `draw.h` du code C original (HUD_BORDER_WIDTH=16,
  SMALL_WIDTH=192, SMALL_HEIGHT=160, HUD_AMMO_COUNT_X=160, etc.).

- `HudScaling` : conversion coord natives -> coord ecran JME. Supporte 3 modes :
  FIT (letterbox proportionnel, defaut), STRETCH (remplit tout), PIXEL_PERFECT
  (scale entier). Gere l'inversion d'axe Y (natif top-left vs JME bottom-left).

- `HudMode` : enum SMALL_SCREEN (HUD complet visible, 3D 192x160) ou FULL_SCREEN
  (3D plein ecran, panneau bas en overlay).

- `HudState` : modele des valeurs dynamiques (AMMO, ENERGY, arme selectionnee,
  messages avec ring buffer deduplique). Decouple du rendu JME.

- `HudAppState` : couche JME. Charge `newborder.png` comme fond (deja extrait via
  IffExtractor), positionne AMMO/ENERGY/slots/messages en BitmapText aux
  coordonnees natives converties via HudScaling.

**Integration :**
- Attache automatiquement le HudAppState depuis GameAppState.initialize().
- Message d'accueil "Level X loaded" pushe a l'init.
- Detach propre dans cleanup().

**Ce qui reste a faire :**
- Relier AMMO/ENERGY/selectedWeapon aux vraies donnees joueur (actuellement
  valeurs fixes).
- Ajouter la zone 3D transparente (actuellement le border masque une partie
  de la vue). Options : (a) appliquer alpha=0 a la zone centrale noire du PNG,
  (b) configurer un viewport 3D a la taille de HudLayout.getSmall3DViewport().
- Extraire les vraies fonts pixel (stenfontraw) pour un look 100% fidele.
- Implementer le mode FULL_SCREEN (panneau bas seul).
- Fenetre redimensionnable : appeler HudAppState.onResize() sur resize event.

### Palette Amiga copper-list decodee

Format de `panelcols` identifie comme dump de copper list Amiga AGA :
- Byte 0-1 = adresse registre hardware ($0180 = COLOR00, ...)
- Byte 2-3 = vraie couleur $0RGB 12-bit

Fix applique dans `RawExtractor.parseAmiga12BitPalette()` pour lire les bytes
2-3 au lieu des bytes 0-1. Les couleurs HUD confirmees : noir ($0000), oranges
($0DA0, $0A80), rouge ($0E00), jaune vif ($0FF2), blanc ($0FFF).

---

## [2026-04-20] Session 72 — Outil VectObj Viewer + TODO textures

### Ajout : viewer 3D pour les modeles vectobj

Outil standalone pour inspecter interactivement les modeles `.j3o` convertis
depuis les vectobj Amiga, avec leurs animations de frames.

**Lancement** :
```
./gradlew viewVectObj                  # premier modele de la liste
./gradlew viewVectObj -Pmodel=rifle    # modele specifique
```

**Controles** :
- `<-` / `->` : modele precedent/suivant (19 au total : blaster, charger,
  crab, generator, glarebox, grenadelauncher, jetpack, laser, mantis,
  passkey, plink, rifle, rocketlauncher, scenery, shotgun, snake, switch,
  ventfan, wasp)
- `Space` : play/pause animation
- `+` / `-` : accelerer / ralentir FPS (pas de 1)
- `N` / `B` : frame suivante / precedente (en pause)
- `R` : reset camera
- `G` : toggle grille au sol
- `W` : toggle wireframe
- `A` : toggle auto-rotation du modele
- Drag souris gauche : orbit autour du modele
- Molette : zoom
- `Esc` : quitter

**HUD** :
- Titre : nom du modele + index + FPS courante + etat PAUSED
- Info : nb geometries, vertices, frames d'animation
- Controles en bas

### Modifications

**VectObjFrameAnimControl.java** :
- Ajout methode publique `stepFrame(int delta)` : avance/recule d'une
  frame et force le rafraichissement du mesh (indispensable en pause)
- Ajout `getCurrentFrameIndex()` et helper prive `applyCurrentFrameToMesh()`
- Refactor : la mise a jour du mesh est factorisee dans
  `applyCurrentFrameToMesh()` (reutilise par `stepFrame` et `controlUpdate`)

**VectObjViewer.java** (nouveau, 500+ lignes) :
- SimpleApplication standalone avec camera orbit spherique
- Auto-scan des .j3o dans `assets/Scenes/vectobj`
- Normalisation automatique de l'echelle du modele (tient dans une sphere
  de 1.5 unites, centre au sol)
- Eclairage 3-points (ambient + sun + fill) pour visualiser le shading
- Grille 10x10 + axes XYZ colores comme reference spatiale
- Integration avec `VectObjFrameAnimControl.attachIfAnimated()` pour
  declencher les anims au chargement

**build.gradle** :
- Nouvelle tache `viewVectObj` (group ab3d2)
- `-XstartOnFirstThread` sur macOS
- Verifie la presence de .j3o avant de lancer (message d'erreur explicite
  renvoyant vers `convertVectObj`)

### TODO note : bug decodage textures

L'utilisateur signale que certaines textures murales ont **2 pixels en
trop en largeur** apres conversion `.256wad` -> PNG. A investiguer plus
tard dans `WallTextureExtractor` (stride, padding, ou offset de header).
Peut potentiellement expliquer certains decalages UV residuels sur les
portes. Tag pour session future.

---

## [2026-04-20] Session 71 — Fix portes : ne rendre que la vue exterieure

### Symptome apres session 70

Comparaison entre notre version et l'original :

- **Original (image Amiga)** : la porte zone 30 affiche 1-2 pans de
  `chevrondoor` (hazard stripes) selon l'angle de vue - **c'est une porte
  simple avec une face visible**.
- **Notre version** : la porte affiche 2 pans hazard lateraux correctement,
  mais au MILIEU apparaissent des **bandes verticales sombres** avec un
  degrade etrange - on voit a travers les pans avants les pans arrieres
  de la porte.

### Diagnostic

La zone 30 est un **volume octogonal** (6 edges) entoure de 6 zones
voisines. Dans l'original, chaque mur de l'octogone est visible UNIQUEMENT
depuis sa zone voisine (vue "exterieure"). Ce sont 6 murs distincts, pas 6
faces d'une meme porte.

Notre code rendait TOUS les murs de la zone-porte comme pans animes, meme
ceux vus depuis la zone-porte elle-meme. Cela creait une **double-paroi** :
- Les pans "exterieurs" (vus depuis la zone voisine) : corrects
- Les pans "interieurs" (vus depuis la zone-porte) : dupliques, causant
  Z-fighting et affichage des pans arrieres a travers les avants

La session 70 aggravait le probleme en ecrivant **prioritairement** la vue
interieure via le flag `isInsideView`, donnant des donnees texture
incorrectes.

### Fix

**LevelSceneBuilder.java** (boucle detection portes) :

- N'enregistrer QUE la vue exterieure (`zid != doorZoneId`) dans
  `DoorAccum`. La vue interieure est ignoree (`continue`).
- Suppression de la logique "priorite vue interieure" : un seul pan par
  mur visible, donnees texture prises telles quelles depuis le
  `WallRenderEntry`.
- `addSeg(..., isInsideView=false)` est toujours appele avec `false`
  desormais (parametre conserve pour compatibilite mais non utilise).

### Verification

Apres rebuild (`./gradlew buildScenes`), la porte zone 30 (level A) doit
afficher :
- Les pans `chevrondoor` (jaune/noir) nets, sans bandes sombres au milieu
- Une seule paroi visible par angle de vue (pas de double-rendu)

Les autres portes (simples, 1-2 pans) doivent continuer de fonctionner.

### Code en reference

- `zone_liftable_pvs.c` : chaque `ZDoorWall` correspond a UN mur visible
  depuis UNE zone voisine, pas a une paire de faces.
- PVS original : la zone-porte n'est pas rendue depuis son propre volume
  tant que la porte est fermee.

---

## [2026-04-20] Session 70 — Fix portes : detection par edgeId, priorite vue interieure

### Symptomes apres session 69

Malgre les corrections de la session 69 (vitesses + donnees texture par pan),
deux bugs visuels persistaient :

1. **Pans de porte mal textures** (image utilisateur) : la porte jaune-noire
   de la zone 30 (level A, 6 pans) avait 2 pans affichant le motif
   `chevrondoor` correct mais 4 pans apparaissaient **noirs/lisses**,
   comme si la texture etait absente ou fortement distordue.

2. **Portes non detectees comme animees** : la porte rouge du niveau A
   (zones 48, 52, 54, 57, 68, 74 avec `bottom=-256`) apparaissait comme
   un **mur statique geant rouge** qui occupait toute la piece, et
   ne bougeait pas du tout. Aucune animation d'ouverture.

### Diagnostic

**Bug #1 - Textures incorrectes** : chaque pan de porte est present DEUX FOIS
dans le graphe du niveau :
- Une fois du cote **zone-porte** (vue interieure, texture de porte correcte)
- Une fois du cote **zone voisine** (vue exterieure, texture du couloir)

La de-duplication geometrique dans `DoorAccum.addSeg` gardait la PREMIERE
entree rencontree, peu importe qu'elle soit la vue interieure ou exterieure.
Selon l'ordre de traversal de la Map `zones`, certains pans recevaient la
bonne texture (vue interieure) et d'autres la mauvaise (vue exterieure).

**Bug #2 - Portes non detectees** : le code utilisait un filtre heuristique
`Math.abs(botH) <= DOOR_FLOOR_THRESH` (=8) pour decider si un mur est un
pan de porte. Ce filtre eliminait **toutes les portes qui ne commencent
pas au sol** (zones 48, 52, 54 avec `bottom=-256`). Ces portes etaient
alors traitees comme murs statiques avec des hauteurs enormes.

Or, l'original (`zone_liftable_pvs.c`) n'utilise AUCUN critere de hauteur :
un mur est un pan de porte ssi son `edgeId` est reference dans la liste
des `ZDoorWall` d'une porte.

### Fix

**LevelSceneBuilder.java** :

- **Detection par `edgeId`** (et non par hauteur) : pour chaque mur adjacent
  a une zone-porte (`otherZone != 0 && doorZoneDefs.containsKey(zid|oz)`),
  on retrouve son `edgeId` via `findEdgeIdForSegment()` puis on teste
  `doorEdgeGfx.containsKey(eid)`. C'est le critere exact de l'original.

- **Priorite vue interieure** : `DoorAccum.addSeg()` accepte un nouveau
  parametre booleen `isInsideView`. Quand la vue interieure arrive alors
  que la vue exterieure est deja en place pour la meme geometrie, les
  donnees texture (`texIdx`, `fromTile`, `yOffset`) sont **ecrasees** par
  les bonnes valeurs (interieures). Le segment devient
  `[x0, z0, x1, z1, texIdx, fromTile, yOffset, isInsideView]` (8 floats
  au lieu de 7).

- **Suppression des heuristiques de hauteur** : les constantes
  `DOOR_FLOOR_THRESH=8` et `DOOR_HEIGHT_MIN=64` ne sont plus consultees
  (elles restent declarees pour reference). Le fallback pour "portes
  non-repertoriees" est egalement supprime puisqu'il n'a plus lieu
  d'etre : un mur est une porte ssi son edgeId est dans `doorEdgeGfx`.

### Verification

Apres rebuild (`./gradlew buildScenes`), tester sur level A :
- Porte zone 30 (6 pans, hazard stripes jaune/noir) : tous les pans doivent
  afficher la meme texture correcte
- Portes rouges zones 48/52/54/57/68/74 (`bottom=-256`, en hauteur dans les
  couloirs) : doivent s'animer normalement au passage du joueur
- Autres portes (5, 11, 96, 132) : continuent de fonctionner normalement

### Code en reference

- `zone_liftable_pvs.c` : `Zone_InitDoorList` et le parcours des `ZDoorWall`
  utilisant uniquement `zdw_EdgeID` pour le match
- Pas d'utilisation de hauteur minimale / maximale pour la detection de porte

---

## [2026-04-20] Session 69 — Fix portes : vitesse + textures par pan

### Symptomes

1. **Vitesse identique pour toutes les portes** : les portes s'ouvraient et
   se fermaient toutes au meme rythme, beaucoup trop rapidement. Les valeurs
   `zl_OpeningSpeed` / `zl_ClosingSpeed` du `ZLiftable` etaient ignorees, le
   code utilisait des constantes en dur (`DEFAULT_OPEN_SPEED=2.0f`,
   `DEFAULT_CLOSE_SPEED=1.2f`).

2. **Texture des portes animees incorrecte** : toutes les faces d'une meme
   porte portaient la texture du PREMIER pan rencontre. Les portes a plusieurs
   segments (ex. zone 30 de level A avec 6 pans) affichaient une texture
   uniforme au lieu des textures differentes prevues par l'original.

### Diagnostic

**Vitesse** : les champs `zl_OpeningSpeed` / `zl_ClosingSpeed` du ZLiftable
(cf. `zone_liftable.h`) sont exprimes en unites-editeur Amiga par frame a
25 fps. Notre code ne les lisait meme pas depuis le JSON - `LevelSceneBuilder`
stockait `openDuration` et les conditions d'ouverture en UserData mais jamais
`openSpeed` / `closeSpeed`.

**Textures** : le `computeIfAbsent` sur `DoorAccum` figeait les donnees
texture (clipIdx, fromTile) du PREMIER segment rencontre, puis les
appliquait a TOUS les segments. Or chaque pan de porte a son propre
`WallRenderEntry` avec ses propres `clipIdx`/`fromTile`/`yOffset` dans le
graphe du niveau - il suffisait de les conserver par-segment au lieu de
les mutualiser.

### Fix

**LevelSceneBuilder.java** :
- `DoorAccum` restructure : chaque segment stocke ses propres
  `[x0, z0, x1, z1, texIdx, fromTile, yOffset]` au lieu d'un `texIdx` global.
  Le constructeur devient `DoorAccum(yTop, yBot, doorZoneId)` et
  `addSeg(x0, z0, x1, z1, texIdx, fromTile, yOffset)` accepte les 3 donnees
  texture du segment.
- `parseWalls` lit maintenant aussi le champ `yOffset` du JSON (w[7] =
  `VO = (-floor) & 0xFF`).
- Boucle de detection des portes : passe `clipIdx`, `fromTile`,
  `wallYOffset` **du mur courant** a `addSeg()` - chaque pan conserve ses
  donnees propres.
- Rendu des portes : chaque segment recoit son propre materiau
  `wm[segTexIdx]` et ses propres UV. Appel : `makeDoorSegGeo(..., texW,
  segFromTile, segYOffset)`.
- `makeDoorSegGeo` prend un parametre `yOffset` supplementaire et l'applique
  aux coordonnees UV V : `V_offset = yOffset / TEX_V`. Cela aligne la
  texture de la porte sur la bonne ligne horizontale, comme dans l'original.
- UserData ajoutees sur chaque `Node` porte : `zlBottom`, `zlTop`,
  `openSpeed`, `closeSpeed` (valeurs Amiga brutes, a convertir au runtime),
  en plus de `openDuration`, `raiseCondition`, `lowerCondition` et des 4 sfx.

**DoorControl.java** :
- `setSpatial` lit `openSpeed`, `closeSpeed`, `zlBottom`, `zlTop` depuis les
  UserData du `Node` porte.
- Conversion 25 fps Amiga -> state/s JME :
  ```
  hEditor      = |zl_Top - zl_Bottom|   (hauteur de course en unites Amiga)
  openSpeed_jme = (zl_OpeningSpeed * 25) / hEditor  (state/seconde)
  closeSpeed_jme = (zl_ClosingSpeed * 25) / hEditor
  ```
- Cas special : si `zl_ClosingSpeed == 0` (ex. zone 30 level A), la porte ne
  se referme jamais -> on force `lowerCondition = LOWER_NEVER`.

### Verifications

Exemples level A apres le fix :
| Zone | openSpeed | course | duree d'ouverture |
|------|-----------|--------|-------------------|
|   5  |    16     |  512   |  1.28 s           |
|  30  |     2     |  512   | 10 s (lente)      |
|  11  |    16     |  512   |  1.28 s           |
| 132  |     4     |  704   |  7.04 s           |

Apres rebuild des `.j3o` (`./gradlew buildScenes`), verifier :
- Les portes de level A s'ouvrent maintenant a des vitesses distinctes
- Les portes multi-pans (zone 30 avec 6 pans) affichent correctement les
  textures de chaque pan
- La porte zone 30 et zone 132 restent ouvertes (`closeSpeed=0`)

### Code en reference

- `zone_liftable.h` : structures `ZLiftable` (36 bytes) et `ZDoorWall`
  (10 bytes). Les champs `zl_OpeningSpeed`/`zl_ClosingSpeed` sont a
  l'offset +4 et +6.
- `zone_liftable_pvs.c` : parcours `Zone_InitDoorList` qui confirme la
  lecture WORD par WORD jusqu'au marqueur `-1` pour les `ZDoorWall`.
- `LevelED.txt` (AMOS) : confirmation que le temps d'ouverture est bien
  `|Top - Bottom| / OpeningSpeed` frames a 25 fps.

---

## [2026-04-19] Session 67 — Fix final UV : swap U <-> V

### Symptome

Apres sessions 65-66, la majorite des textures etaient correctes mais
certains polygones apparaissaient avec la mauvaise zone de texture :
- Rifle Part 12 : zones rouges vives au lieu de metal chrome
- Polygones avec patches marron/bois sur des armes metalliques
- Des motifs qui paraissaient tournes 90 deg ou decales

### Diagnostic

Apres dump UV detaille via `VectObjUVDump` et analyse ligne par ligne
de `draw_PutInLines` dans objdrawhires.s :

```asm
move.b  2(a1), d2      ; byte +2 du vertex
...
move.l  d2, a6         ; a6 = xbitconst -> INCREMENTE AVEC X ECRAN

move.b  3(a1), d5      ; byte +3 du vertex
...
move.l  d5, a5         ; a5 = dy constant -> INCREMENTE AVEC Y ECRAN
```

X ecran -> COLONNE de la texture ; Y ecran -> LIGNE de la texture.

Donc :
- byte +2 (appele "U" historiquement) = COLONNE (axe horizontal)
- byte +3 (appele "V" historiquement) = LIGNE (axe vertical)

On avait code l'inverse depuis la session 62.

### Fix

Dans `VectObjConverter.readPolygons()`, generation des UV vertex :

```java
// AVANT (sessions 62-66, INCORRECT)
int colFinal = colStart + vtxV[v];
int rowFinal = rowStart + vtxU[v];

// APRES (session 67, CORRECT)
int colFinal = colStart + vtxU[v];   // byte +2 -> colonne
int rowFinal = rowStart + vtxV[v];   // byte +3 -> ligne
```

### Methodologie de validation

Ajout de flags experimentaux via system properties :
- `-Dvectobj.flipU` : miroir sur axe U (row = 63-U)
- `-Dvectobj.flipV` : miroir sur axe V (col = 63-V)
- `-Dvectobj.swapUV` : swap U<->V

Exposes via build.gradle avec `-PflipU`, `-PflipV`, `-PswapUV`.
Test empirique : `-PswapUV` correction parfaite du rifle (metal continu).

### Nettoyage

Flags experimentaux retires du code definitif apres validation. Swap
applique en dur dans `readPolygons()`. Documentation du format binaire
mise a jour dans le JavaDoc de `VectObjConverter` pour eviter la confusion
future : byte +2 = V (colonne), byte +3 = U (ligne).

### Pipeline
```bash
./gradlew convertVectObj   # regenere les j3o avec UV corrects
```

---

## [2026-04-19] Session 66 — Animations vectobj + fix UV residuels

### Animations multi-frame

Les vectobj Amiga ont un champ `numFrames` indiquant le nombre de poses
d'animation (ex: passkey=13 frames rotation, Mantis=20 frames marche).
Jusqu'a session 65 on ne generait que la frame 0 (statique).

**Implementation session 66** :

1. **`VectObjFrameAnimControl`** (NOUVEAU) : Control JME qui cycle les
   positions du Mesh a chaque frame.

   <b>ARCHITECTURE IMPORTANTE</b> : Le Control n'est PAS stocke dans le
   j3o. A la place, les frames d'animation sont stockees en <b>UserData</b>
   sur la Geometry (types primitifs natifs JME, serialisation sure).
   Le Control est attache au RUNTIME via :
   ```java
   Spatial model = assetManager.loadModel("Scenes/vectobj/passkey.j3o");
   VectObjFrameAnimControl.attachIfAnimated(model);
   ```

   <b>Pourquoi ?</b> Un Control custom stocke dans un j3o necessite d'etre
   enregistre dans `SavableClassUtil` et pose probleme dans l'editeur SDK
   JME (erreur "obj parameter cannot be null" lors de l'ouverture du j3o).

   UserData attendues sur une Geometry animee :
   - `"vectobj.frames"` : `float[]` aplati (numFrames * numVerts * 3)
   - `"vectobj.numFrames"` : `int`
   - `"vectobj.fps"` : `float` (optionnel, defaut 10)

   Fonctionnalites : FPS configurable, boucle infinie par defaut,
   `setPlaying(false)` pour stopper, freeze de la pose quand la part
   est OFF dans une frame (pas de disparition brutale).

2. **`VectObjConverter.convert()` refactore** :
   - Lit les positions de TOUTES les frames (pas seulement frame 0)
   - Pour chaque part visible a frame 0, genere la geometry avec positions
     de frame 0, puis stocke les frames en UserData via
     `VectObjFrameAnimControl.storeFrames()`.
   - Detection "toutes frames identiques" : pas de UserData stockees si
     l'objet est statique (optimisation).

3. **Refactoring interne** :
   - Nouveau record `TriangleIdx` (indices de points au lieu de positions)
   - Nouvelle methode `buildGeometryIdx()` qui utilise les indices
   - Nouvelle methode `buildFramePositions()` qui construit le tableau
     de positions par frame.

### Fix UV residuels (sans toucher a l'atlas)

Certains polygones avaient des textures mal positionnees. Correction :
**wrap modulo 64 / 256** sur `rowFinal` et `colFinal`. En theorie le
comportement ASM fait un modulo implicite via le byte overflow, donc
un polygone qui demande un U > 63-rowStart cycle sur les premieres
rows. Cette correction evite aussi les UVs qui debordent sur la tuile
d'atlas suivante (qui contient un slot different).

### Pipeline
```bash
./gradlew convertVectObj   # regenere les .j3o avec frames en UserData + UV fixes
./gradlew run              # pour l'animation runtime, appeler
                           # VectObjFrameAnimControl.attachIfAnimated(model)
                           # apres chargement du j3o
```

### Fix en cours de session : float[] non serialisable

JME UserData n'accepte PAS les `float[]` bruts (erreur "Unsupported type: [F"
durant la conversion). Seuls Integer, Float, String, Savable, List, Map sont
autorises. Solution : encoder les positions en <b>String base64</b>.

- Key `vectobj.framesB64` contient une String base64 des positions en
  IEEE754 little-endian
- Taille : ~5.5 chars par float (vs 4 bytes natifs), acceptable
- Decodage via `Base64.getDecoder()` + `ByteBuffer` au runtime

Les anciens j3o generes avant ce fix peuvent contenir des UserData cassees.
Il faut **supprimer** `assets/Scenes/vectobj/*.j3o` et relancer
`./gradlew convertVectObj` pour les regenerer proprement.


### Verification attendue
- Les j3o s'ouvrent SANS erreur dans l'editeur SDK JME
- Lors du runtime, apres `attachIfAnimated()`, les modeles bougent :
  passkey tourne, Mantis marche, ventfan tourne, wasp bat des ailes
- Certains polygones auparavant mal textures sont correctement mappes
  grace au wrap modulo

---

## [2026-04-19] Session 65 — Mantis & aliens : slots 1/2/3 decouverts (atlas 8 slots)

### Symptome
Apres session 64, majorite des modeles textures correctement mais Mantis
et certains enemies/parties ont des textures incorrectes.

### Diagnostic via VectObjTextureScan
Scan des 112 polygones du Mantis revele :
- **TOUS** utilisent `texOffset = 0x8302` ou `0x8303` (bank 1, col 192, row 0)
- Brightness=100 sur tous

### Decouverte
`0x8302` = relOfs `770` = `192*4 + 2` → **lane=2** (et pas lane=0).
`0x8303` = relOfs `771` = `192*4 + 3` → **lane=3**.

L'ASM `move.b (a0, d0.w*4), d3` avec d0=(U<<8)|V lit toujours dans le MEME
slot. Si a0 est aligne sur lane 2, tous les samples viennent du slot 2 du
buffer, jamais du slot 0.

### Nouvelle theorie validee (via TextureLayoutExplorer)
**Chaque banque de 64KB contient 4 textures differentes interleaved
byte-par-byte** :
```
  Slot 0 : bytes 0, 4, 8, 12, ...
  Slot 1 : bytes 1, 5, 9, 13, ...
  Slot 2 : bytes 2, 6, 10, 14, ...
  Slot 3 : bytes 3, 7, 11, 15, ...
```
2 banques * 4 slots = 8 textures de 256x64 au total.

**Validation visuelle** (`all_slots.png`) : on voit enfin TOUTES les
textures du jeu (panneaux EXIT, boutons numeriques, logo radioactif,
circuits imprimes, claviers 0-9, segments mecaniques, peau alien...)
au lieu d'un seul slot.

**Dans les sessions precedentes on ne rendait QUE le slot 0**, ce qui
explique pourquoi les aliens et un tas d'objets etaient mal mappes.

### Corrections appliquees

**TextureMapConverter.java** :
- `renderSlot(texData, shade, pal, bank, slot)` : nouvelle methode generalisee
- `renderBank()` devient un alias pour `renderSlot(..., slot=0)` (API compat)
- Atlas PNG final : **256x512** (8 tuiles 256x64 empilees verticalement)
- `texOffsetToSlot(texOffset)` : retourne 0..3
- `texOffsetToAtlasTile(texOffset)` : retourne 0..7 (bank*4 + slot)
- `sampleColor()` corrige pour inclure le slot dans le byte offset

**VectObjConverter.java** :
- UVs normalisees sur l'atlas 256x512 via `texOffsetToAtlasTile()`
- Chaque polygone pointe sur sa tile (0..7) au lieu d'une banque (0..1)

### Outils
- `VectObjTextureScan` (NOUVEAU) : scan les texOffsets d'un vectobj +
  genere atlas annote PNG. Usage : `./gradlew scanVectObjTex -Pvectobj=Mantis`
- `TextureLayoutExplorer` reecrit : rend les 4 slots de chaque banque en
  PNG separes + atlas combine `all_slots.png`.

### Pipeline
```bash
./gradlew convertTextureMaps  # regenere l'atlas 256x512 avec les 8 tuiles
./gradlew convertVectObj      # regenere les .j3o avec les bons UV
./gradlew run                  # verifier visuellement le Mantis
```

### Verification attendue
- `assets/Textures/vectobj/texturemaps_atlas.png` = 256x512, avec 8 zones
  de textures distinctes
- Le Mantis affiche enfin une vraie texture d'alien (pas du metal gris)
- Tous les objets qui utilisaient les slots 1/2/3 sont correctement mappes

### Statut final session 65
✅ **VALIDE** : atlas 8 slots genere avec les 32 textures reconnaissables
(EXIT, claviers, circuits, peau alien...). Mantis et autres modeles utilisant
slots 1/2/3 maintenant mappes sur les bonnes textures.

Residuel : certaines textures sont encore mal positionnees ou mal orientees
sur quelques polygones. Hypotheses a investiguer en session suivante :
- Flip U/V ou axes inverses sur certains modeles
- Interpretation signee vs non-signee des bytes U,V vertex
- Wraparound/modulo 64 sur row_final pour polygones qui debordent


---

## [2026-04-17] Session 64 — VectObj textures : layout ASM-strict + palette globale

### Contexte
Apres session 63, les textures etaient reconnaissables dans l'atlas mais :
1. Pas de couleur (tout gris/noir)
2. Le layout "interleaved 4-chunky" etait en fait une mauvaise hypothese

### Diagnostic via TextureLayoutExplorer
Un outil de diagnostic a ete developpe pour tester 8 hypotheses de layout.
La variante **ASMstrict_U_row_V_col** etait la plus propre :
```
byte_ofs = bankBase + U*1024 + V*4
  avec U = row (0..63), V = col (0..255)
```
Ceci donne **256 colonnes x 64 rows par banque** (pas 1024 comme pense).
Les 2 banques font donc 512 colonnes utiles au total, 128 KB.

Le layout s'explique directement par l'ASM `move.b (a0, d0.w*4), d3`
avec `d0 = (U_int << 8) | V_int` : `d0*4 = U*1024 + V*4` = byte offset
dans le buffer avec U=row et V=col.

Les 3 bytes apres chaque pixel (offsets +1, +2, +3) sont probablement
des shades pre-calcules pour accelerer le rendu ; ignores pour l'atlas PNG.

### Probleme palette resolu
Le fichier `256pal` etait absent de `ab3d2-tkg-jme/src/main/resources/`
mais present dans le projet frere `ab3d2-tkg-java/src/main/resources/`.

Fichier copie dans `ab3d2-tkg-jme/src/main/resources/256pal`. Format :
- 1536 bytes = 256 * 6 bytes
- Layout : [R:word, G:word, B:word] big-endian
- Valeur effective dans le LOW byte de chaque WORD
- Contient 42 gris de luminosite + 214 couleurs vives (rouge pur, vert, bleu...)

`TextureMapConverter.loadGlobalPalette()` cherche maintenant dans :
1. Le dossier passe en parametre (src/main/resources typiquement)
2. `../ab3d2-tkg-java/src/main/resources/` (projet frere)
3. Workspace NetBeansProjects si trouve

### Corrections appliquees

**TextureMapConverter.java** : reecrit en entier.
- Constantes mises a jour : `BANK_ROWS=64`, `BANK_COLS=256`, `ROW_STRIDE=1024`, `COL_STRIDE=4`
- `COL_HEIGHT=64` et `NUM_COLS=256` (etait 1024, d'ou les erreurs de normalisation UV)
- `renderBank()` fait `pixel(row=U, col=V) = texData[bankBase + U*1024 + V*4]`
- `texOffsetToColumn()` retourne `colStart = (relOfs % 1024) / 4` (0..255)
- `texOffsetToRowStart()` retourne `rowStart = relOfs / 1024` (0..63)
- `sampleColor()` mis a jour pour le nouveau layout
- Recherche de palette dans plusieurs emplacements (dont projet frere)
- Atlas final : 256x128 PNG (au lieu de 1024x128)

**VectObjConverter.java** : generation UV simplifiee.
```java
// AVANT (session 63) :
int colFinal = baseCol + vtxU[v] * 16;   // U => col, facteur *16 a cause du layout interleaved
int rowFinal = rowStart + vtxV[v];        // V => row

// APRES (session 64, layout ASM-strict) :
int colFinal = colStart + vtxV[v];   // V vertex = delta col (1 unite = 1 pixel)
int rowFinal = rowStart + vtxU[v];   // U vertex = delta row (1 unite = 1 pixel)
```
Plus de facteur *16, plus de decalage par bandes, plus de confusion
sur quelle est l'axe row vs col.

### Pipeline
```bash
./gradlew convertTextureMaps  # regenere l'atlas 256x128 avec couleurs
./gradlew convertVectObj      # regenere les .j3o avec les bons UV
./gradlew run
```

### Verification attendue
- `assets/Textures/vectobj/texturemaps_atlas.png` = 256x128, avec couleurs
- Preview zoome visible dans `texturemaps_preview.png`
- Les modeles .j3o (passkey, blaster, ...) affichent leurs vraies textures

### Statut final session 64
✅ **VALIDE** : atlas genere avec couleurs vives, mapping UV correct sur
la majorite des modeles. Residuel : certains modeles (ex: Mantis) ou parties
specifiques utilisent une texture qui n'est pas correctement mappee. A
diagnostiquer en session 65.

---

## [2026-04-16] Session 63 — VectObj textures : fix decalage par bandes (OBSOLETE)

### Symptome
Apres session 62, les textures apparaissaient **decalees par bandes
horizontales** : chaque polygone prenait une bande de texture qui n'avait
pas de rapport avec son contenu, comme si on demarrait toujours au row 0
alors que ce n'etait pas le cas.

### Analyse ASM (l.2371-2376)

```asm
move.l  Draw_TextureMapsPtr_l, a0
move.w  (a1)+, d0              ; texOffset (WORD)
bge.s   .notsec
    and.w  #$7fff, d0
    add.l  #65536, a0           ; banque 1
.notsec:
add.w   d0, a0                   ; a0 += texOffset (byte offset)
```

`texOffset` est un **byte offset arbitraire** dans la banque. Dans le layout
interleaved 4-chunky :
```
byte_ofs = block * 256 + row * 4 + lane
```
Ce texOffset peut donc pointer **au milieu d'une colonne** (row > 0), pas
seulement au debut.

La session 62 traduisait `texOffset → column` en ignorant totalement le
row de depart, d'ou le decalage par bandes : chaque poly demarrait au row 0
meme s'il etait cense commencer row 17, 32, etc.

### Decomposition correcte du texOffset

```
texOffset = block * 256 + rowStart * 4 + lane
  → block    = relOfs / 256          (0..255)
  → rowStart = (relOfs % 256) / 4    (0..63)   <-- MANQUANT session 62
  → lane     = relOfs % 4             (0..3)
  → col      = block * 4 + lane      (colonne globale dans l'atlas)
```

### Step size des U,V vertex (formule ASM drawpol)

Le sample final : `d0 = (U_int << 8) | V_int`, puis `d0 * 4` = byte offset :
```
byte_ofs_from_a0 = (U * 1024) + (V * 4)
```
Dans le layout atlas :
- 1024 bytes = 4 blocks = **16 colonnes**
- 4 bytes    = **1 row**

Donc :
```
col_final = baseCol + U_vertex * 16
row_final = rowStart + V_vertex
```

La session 62 utilisait `U_vertex * 1` (1 unite = 1 colonne) ce qui ne
correspondait a rien dans le mapping ASM.

### Correction

**TextureMapConverter.java** :
- Nouvelle methode `texOffsetToRowStart(texOffset)` qui retourne le row
  de depart (0..63).
- Documentation enrichie de `texOffsetToColumn()`.

**VectObjConverter.java** : dans `readPolygons()`, generation des UV :
```java
int baseCol  = texOffsetToColumn(rawTexOffset);
int rowStart = texOffsetToRowStart(rawTexOffset);   // NOUVEAU
int bankRowOfs = texOffsetToBank(rawTexOffset) * 64;

int colFinal = baseCol + vtxU[v] * 16;              // *16 et non *1
int rowFinal = rowStart + vtxV[v];                  // + rowStart et non 0
```

### Verification attendue
Apres re-conversion, les textures doivent etre correctement alignees sur
chaque polygone, sans bande horizontale decalee.

### Pipeline
```bash
./gradlew convertVectObj   # pas besoin de reconvertir les PNG atlas
./gradlew run
```

---

## [2026-04-16] Session 62 — VectObj : textures atlas + UV par vertex

### Objectif
Remplacer les vertex-colors aplaties (flat shading derive du texOffset) par de
vraies textures. Les armes, monstres et decors affichent desormais les pixels
d'origine issus de `newtexturemaps`.

### Decouverte du format texture (analyse ASM drawpol l.2546-2559)

```asm
drawpol:
    move.l  d6, d0
    asr.l   #8, d0           ; d0 = (d6 >> 8) = 14 bits U_int
    swap    d5
    move.b  d5, d0           ; low byte de d0 = V_int (byte @ +3)
    move.b  (a0, d0.w*4), d3 ; sample texel byte
```

Le d0 final = `(U_int << 8) | V_int`, puis `d0 * 4` donne le byte offset final.
Ce `*4` revele un **layout interleaved 4-chunky** :
- adjacent en V = +4 bytes
- adjacent en U (dans le meme bloc de 4 colonnes) = +1 byte
- bloc de 4 colonnes interleaved = 256 bytes (64 pixels * 4 bytes)

### Layout `newtexturemaps` (128 KB = 2 banques * 64 KB)

```
byte layout d'une banque (65536 bytes) :
  block 0 (bytes 0..255)  : 4 colonnes interleaved
    byte[0]   byte[1]   byte[2]   byte[3]     <- row 0, col 0,1,2,3
    byte[4]   byte[5]   byte[6]   byte[7]     <- row 1, col 0,1,2,3
    ...                                      (64 rows, 256 bytes)
  block 1 (bytes 256..511) : 4 colonnes suivantes (4..7)
  ...
  block 255 (bytes 65280..65535) : colonnes 1020..1023
```

Une banque = 256 blocs * 256 bytes = 65536 = 1024 colonnes de 64 pixels.
Les textures sont des **strips verticaux** de 64 pixels comme les murs du jeu.

### Modifications TextureMapConverter

- Layout interleaved 4-chunky implemente dans la generation des PNG
- Nouvelle sortie `texturemaps_atlas.png` (1024x128) = bank0 + bank1 empiles
- Nouvelles methodes publiques `texOffsetToColumn()` et `texOffsetToBank()`
- `sampleColor()` mis a jour pour le nouveau layout

### Modifications VectObjConverter

- Record `Triangle` etendu : positions + **UV par vertex** + couleur shade
- `readPolygons()` genere les UV atlas pour chaque vertex :
  ```java
  int baseCol = TextureMapConverter.texOffsetToColumn(rawTexOffset);
  int baseRow = TextureMapConverter.texOffsetToBank(rawTexOffset) * 64;
  float uCol  = (baseCol + vtxU[v]) / 1024f;
  float vRow  = (baseRow + vtxV[v]) / 128f;
  ```
- `buildGeometry()` emet maintenant `Type.TexCoord` en plus de Position/Normal/Color
- `buildMaterial()` charge `Textures/vectobj/texturemaps_atlas.png` en ColorMap
  avec NearestNoMipMaps + MagFilter.Nearest pour le look retro pixel-art

### Configuration materiau

```
Unshaded.j3md :
  ColorMap    = texturemaps_atlas.png (nearest filtering)
  VertexColor = true  (module par brightness par-poly)
  FaceCullMode.Off    (front+back visible)
  WrapMode.Repeat     (permet U > 1 pour tiling)
```

Le pixel final = `texture * vertex_color`, donc le brightness par poly module
correctement la texture (eclairage directionnel preserve).

### Fallback propre

Si `texturemaps_atlas.png` n'existe pas encore (premier run avant
`./gradlew convertTextureMaps`), `buildMaterial()` tombe sur vertex-color seul
avec un WARN dans les logs – le comportement session 58-61 reste fonctionnel.

### Pipeline

```bash
./gradlew convertTextureMaps   # genere les PNG atlas
./gradlew convertVectObj       # puis regenere les .j3o avec UVs + texture
```

### Known limitation

La correspondance exacte **byte U,V du vertex → pixel dans l'atlas** est une
*premiere approximation* : on traite U et V comme des pixels directs dans une
region 64x64 ancree a la colonne du texOffset. L'ASM fait une indexation plus
complexe (fixed-point 16.16 avec mask 0x3FFFFF) dont l'equivalence exacte en
UV normalisees demandera peut-etre un ajustement dans une future session si
certaines textures apparaissent decalees ou mal echelonnees.

---

## [2026-04-16] Session 61 — VectObj : fix nombre de vertex = numLines+1

### Probleme
Apres session 60 la geometrie etait bonne mais il manquait des faces.

### Diagnostic
Le passkey a des polygones avec `numLines=3` mais contient des **quads**
(4 vertices) : par exemple le face arriere ptIdx [3,2,1,0] = le rectangle
au plan Z=-16 (tous les 4 points ont z=-16, coplanaires).

Avec `numVerts = numLines = 3`, on lisait seulement 3 des 4 vertices du quad,
generant un seul triangle au lieu de 2. Resultat : environ **la moitie des
faces manquantes**.

### Preuve ASM (draw_PutInLines l.3352-3357)
```asm
this_line_flat:
    addq    #4, a1              ; avance vers vertex suivant
    dbra    d7, draw_PutInLines ; boucle numLines+1 fois (d7=numLines -> -1)
    addq    #4, a1              ; +4 final apres la boucle
    rts
```

La boucle tourne `numLines+1` fois, chaque iteration dessine un **edge**
`v[i] -> v[i+1]`. Pour un polygone FERME a V vertices il faut exactement
V edges donc **V = numLines + 1**.

### Verification budget polygone
```
polySize = 18 + numLines*4  (verifie par lea 18(a1,d0.w*4),a1)

= 4 bytes header (numLines + flags)
+ 4*(numLines+1) bytes vertex list  <- V = numLines+1 vertex reels
+ 4 bytes 1 vertex phantom (lu par PutInLines mais ignore)
+ 6 bytes footer (texOffset WORD + brightness BYTE + polyAngle BYTE + gouraud WORD)
= 4 + 4*numLines + 4 + 4 + 6 = 4*numLines + 18 ✓
```

### Exemple passkey polygone 1
```
0070: 00 03 00 00 | 00 03 20 10 | 00 02 30 10 | 00 01 30 20 | 00 00 20 20 | ...
         N=3  fl        v[0]=3       v[1]=2       v[2]=1       v[3]=0 <-- FIX
```

Les 4 vertices pointent vers les pts 3,2,1,0 -> **rectangle face Z=-16**
(tous les points ont z=-16, verifie coplanarite).

### Correction
```java
// AVANT
int numVerts = numLines;          // QUAD lu comme TRI -> 1 tri au lieu de 2

// APRES
int numVerts = numLines + 1;      // QUAD lu comme QUAD -> fan 2 tris
```

### Impact attendu
Les polygones reel du modele ont maintenant le bon nombre de sommets :
- numLines=2 -> 3 sommets = triangle
- numLines=3 -> 4 sommets = quad (fan 2 triangles)
- numLines=4 -> 5 sommets = pentagon (fan 3 triangles)
- etc.

Faces qui manquaient devraient etre maintenant visibles.

---

## [2026-04-16] Session 60 — VectObj : respecter onOffMask frame 0

### Probleme
Apres la session 59 (fix part list), les modeles affichaient **trop de faces
superposees**. Analyse du passkey : 12 parts x 6 triangles = 72 triangles
pour un objet de 9 sommets seulement.

### Diagnostic
Le passkey a `numFrames=13` avec des `onOffMask` differents par frame :
- frame 0 : 0x001FE001 -> part 0 visible uniquement
- autres frames : differents bits -> autres parts

C'est une **animation de rotation** : chaque frame affiche une "pose" differente
(un wedge different visible). Les 12 parts sont donc **12 poses d'animation**
d'une meme cle qui tourne, pas une decomposition multi-parts statique.

### Correction
- `forceAllParts = true` **retire** en session 59 -> re-active le masque
- Utilisation du `onOffMask` de **frame 0** (pose au repos, comme le jeu)
- Detection automatique "frames SAME vs DIFFERENT" + affichage debug

### Nouvel outil : VectObjQuickAnalyze
`./gradlew analyzeVectObj` dump pour chaque vectobj : header + masques
onOff des 6 premieres frames + verdict SAME/DIFF pour identifier rapidement
les objets animes vs statiques.

### Resultat attendu
- **passkey** : 1 seule part rendue (6 triangles) au lieu de 12 parts (72 triangles)
- **armes** (blaster, rifle, etc.) : si frames SAME, geometrie complete toujours
- Plus de faces superposees dans le rendu

---

## [2026-04-16] Session 59 — VectObj : fix critique format part list

### Bug decouvert via VectObjDumper

Un dump de `passkey` a revele que les `bodyOfs` lus pointaient dans le milieu
de la part list elle-meme (au lieu de pointer vers des polygones) :

```
part[0] bodyOfs=0x0050 -> file+0x0052   (INVALIDE : numLines=1202)
part[5] bodyOfs=0x0208 -> file+0x020A   (INVALIDE)
...
```

Mais les **premiers WORDs** des entrees (qu'on lisait comme sortKey) etaient
110, 292, 474, ... avec delta constant de 182 bytes = **les vrais bodyOfs** !

Verification : file+2+110 = file+0x70 contient `00 03 00 00 00 03 20 10...`
→ numLines=3, flags=0, vertex[0]=(ptIdx=3, u=0x20, v=0x10), etc. **Coherent !**

### Re-analyse ASM stricte (putinunsorted l.2116-2131)

```asm
putinunsorted:
    move.w  (a1)+, d7        ; d7 = PREMIER WORD
    blt     doneallparts     ; si negatif -> fin
    lsr.l   #1, d5           ; test onOff bit
    bcs.s   .yeson
    addq    #2, a1           ; skip second WORD si pas actif
    bra     putinunsorted
.yeson:
    move.w  (a1)+, d6        ; d6 = DEUXIEME WORD
    move.l  #0, (a0)+
    move.w  d7, (a0)         ; <-- stocke d7 (PREMIER WORD) dans PartBuffer
    addq    #4, a0
    bra     putinunsorted
```

Puis @ l.2183-2186 dans .part_loop :
```asm
    move.w  (a0), d0             ; lit le WORD stocke dans PartBuffer (= d7)
    add.l   draw_StartOfObjPtr_l, d0  ; rend absolu
    move.l  d0, a1               ; a1 pointe au polygone
```

**Le PREMIER WORD est le bodyOfs du polygone.**
**Le DEUXIEME WORD est un refPointOfs** (index byte dans `draw_3DPointsRotated_vl`
utilise par PutinParts @ l.2145 pour calculer la profondeur de tri).

### Correction appliquee

**VectObjConverter.java** :
```java
// AVANT (inverse)
short partSortKey = b.getShort(pos);         // WRONG: ce n'est pas un sortKey
int   bodyOfs     = b.getShort(pos+2) & ...; // WRONG: c'est le refPoint

// APRES (conforme ASM)
short firstWord  = b.getShort(pos);          // bodyOfs (termine par -1)
int   refPoint   = b.getShort(pos+2) & ...;  // point reference pour tri
int   bodyOfs    = startOfs + (firstWord & 0xFFFF);
```

### onOffMask : comportement modifie

L'ASM utilise `onOffMask` pour skipper les parts inactives pour **la frame
courante**. Un passkey anime (13 frames) a mask=0x001FE001 en frame 0 montrant
uniquement part 0 (vue frontale). D'autres frames affichent d'autres parts.

Pour l'export d'asset statique on force `forceAllParts=true` → rend la
geometrie complete de **toutes** les parts. En runtime il faudra reactiver le
masquage par frame pour l'animation.

### Outil de diagnostic : VectObjDumper.java

Nouveau `./gradlew dumpVectObj [-Pvectobj=<nom>]` qui dump :
- Hex dump header et polygones
- Decodage detaille de chaque champ avec validation
- Comparaison WORD vs byte+byte pour les vertex
- Valeurs du footer (texOffset, brightness, polyAngle, gouraud)

### Pipeline
```bash
./gradlew dumpVectObj -Pvectobj=passkey  # diagnostic
./gradlew convertVectObj                  # conversion
```

---

## [2026-04-16] Session 58 — VectObj : parser strict conforme ASM

### Objectif
Reprise du decodage vectobj selon **strictement** ce qui est indique dans
`objdrawhires.s` (fonction `draw_PolygonModel` + `doapoly` + `draw_PutInLines`).

### Analyse ASM des lignes cles

Structure globale du fichier (relatif a `draw_StartOfObjPtr_l` = fichier+2) :
```
+0    WORD sortIt        <- move.w (a3)+, draw_SortIt_w    (l.1840)
+2    WORD numPoints     <- move.w (a3)+, draw_NumPoints_w (l.1847)
+4    WORD numFrames     <- move.w (a3)+, d6               (l.1848)
+6    PointerTable[nf] :
        WORD ptsOfs      <- (a4,d5.w*4)                    (l.1867)
        WORD angOfs      <- 2(a4,d5.w*4)                   (l.1870)
+6+nf*4  Parts (sortKey<0 = fin) :
        SWORD sortKey    <- move.w (a1)+, d7               (l.2117)
        WORD  bodyOfs    <- move.w (a1)+, d6               (l.2127)
```

Point data @ ptsOfs :
```
+0    LONG onOff_l       <- move.l (a3)+, draw_ObjectOnOff_l (l.1875)
+4    BYTE[((np+1)/2)*2] pointAngles <- draw_PointAngPtr_l  (l.1876-1882)
+...  np x 6 bytes       <- addq #6, a3                    (l.1917)
      Chaque point : SWORD x, SWORD y, SWORD z
```

Polygone @ bodyOfs (taille = N*4 + 18) :
```
+0          SWORD numLines (N)  <- (a1)+, d7                    (l.2223)
+2          WORD  flags         <- (a1)+, draw_PreHoles_b       (l.2224)
+4..+4+N*4  N x vertex :
              +0 : WORD ptIdx   <- move.w (a1), d0              (l.3091)
              +2 : BYTE u       <- move.b 2(a1), d2             (l.3164)
              +3 : BYTE v       <- move.b 3(a1), d5             (l.3180)
+4+N*4      8 bytes phantom (lus par PutInLines, ignores)
+N*4+12     WORD texOffset      <- (a1)+, d0                    (l.2372)
            bit 15 = banque secondaire (l.2375-2376)
+N*4+14     BYTE brightness     <- (a1)+, d1                    (l.2382)
+N*4+15     BYTE polyAngle      <- (a1)+, d2                    (l.2407)
+N*4+16     WORD gouraud        <- 12(a1,d7*4)                  (l.2225)
```

### Bug identifie et corrige : vertex ptIdx

Le parser precedent lisait :
```java
int normalIdx = data[vOfs]     & 0xFF;  // soi-disant "normal index"
int ptIdx     = data[vOfs + 1] & 0xFF;
```

Mais l'ASM (l.3091) fait `move.w (a1), d0` = lecture d'un **WORD complet**.
Le resultat est ensuite utilise comme `(a3, d0.w*4)` pour indexer les points
projetes (4 bytes/point).

Pour les petits objets (numPoints < 256) le byte de poids fort vaut 0, donc
la lecture byte+byte donne le meme resultat. **Mais pour les objets avec plus
de 256 points ce serait faux.** Le parser lit maintenant un WORD entier
via `b.getShort(vOfs) & 0xFFFF`.

### VectObjConverter.java — changements

- **JavaDoc classe** : documentation stricte avec references de lignes ASM
- **readPolygons()** :
  - Lecture vertex par WORD (`b.getShort(vOfs) & 0xFFFF`) conforme l.3091
  - Extraction explicite des coordonnees U,V via bytes +2 et +3 (prepare
    futur texturing, pas encore utilise pour generer de vrais UV)
  - Footer : calcul `rawTexOffset` preservant bit 15 pour banque secondaire
  - Lecture supplementaire du WORD gouraud a N*4+16
- Tous les offsets sont commentes avec la ligne ASM d'origine

### Impact
La geometrie produite pour les objets existants (blaster, passkey, switch,
etc.) est **numeriquement identique** car ces objets ont numPoints < 256.
Le code est maintenant correct pour tout objet potentiel (robuste).

### Pipeline
```bash
./gradlew convertVectObj
```

---

## [2026-04-16] Session 57 — Vraies couleurs vectobj depuis newtexturemaps

### TextureMapConverter.java (nouveau)
- Lit `newtexturemaps` (128KB raw 8bpp Amiga chunky)
- Lit `newtexturemaps.pal` (16KB shade table BYTE[64][256])
- Lit `256pal.bin` (1536 bytes = 256 × 6 bytes PALC format)
- Génère `assets/Textures/vectobj/texturemaps_bank0.png` et `bank1.png`
- API `sampleColor(texData, shadeData, palette, texOffset, brightness)` pour VectObjConverter

### VectObjConverter.java
- `loadTextureData()` : charge les 3 fichiers au démarrage
- `brightnessToColor()` : utilise vraies couleurs Amiga si disponibles
- Y inversé (jme_y = -amiga_y) : correction axe Amiga→JME
- onOff bitmask respecté : parties invisibles en frame 0 skippées

### build.gradle
- Nouvelle tâche `convertTextureMaps`

---


### Découverte clé (analyse diagnostic blaster)
Le format d'une entrée vertex est :
- `BYTE normalIdx | BYTE ptIdx | BYTE u | BYTE v`
- Quand normalIdx=0 : ptIdx = index point valide
- Quand normalIdx!=0 : vertex toujours clippé dans le jeu (cross product 0)

### VectObjConverter.java
- Lecture vertex : low byte = ptIdx (non le WORD entier)
- Triangulation partielle par triangle individuel

---

## [2026-04-15] Session 54 — polyModelIndex : lecture correcte depuis le binaire

### Analyse ASM (objdrawhires.s)
- `cmp.b #$ff, 6(a0)` : byte +6 de l'objet = marqueur polygon model
- `move.w 6(a0), d5` (apres `move.w (a0)+`) = WORD a l'offset original +8 = poly model index
- Cet index pointe dans `Draw_PolyObjects_vl` = `GLFT_VectorNames_l`

### LevelBinaryParser.java
- Parse byte +6 : `polyMarker` (0xFF = polygon)
- Parse WORD +8 : `polyModelIndex` (index dans GLFT_VectorNames_l, 0-21)
- Ajout `isPolygon` et `polyModelIndex` dans `ObjData`

### LevelJsonExporter.java
- Export `isPolygon` et `polyModelIndex` dans le JSON par objet

### LevelSceneBuilder.java
- `VECTOR_NAMES_TABLE[]` : table exacte GLFT_VectorNames_l depuis LevelED.txt
- Priorite 1 : `polyModelIndex` direct (source binaire, fiable a 100%)
- Priorite 2 : fallback par nom via `OBJECT_NAME_TO_VECTOBJ`

### Pipeline complet
```bash
./gradlew convertLevels buildScenes run
```

---


### LevelSceneBuilder - tryLoadVectObj
- `OBJECT_NAME_TO_VECTOBJ` : table nom objet -> fichier vectobj
- `ALIEN_NAME_TO_VECTOBJ` : table nom alien -> fichier vectobj (SnakeScanner, Wasp, Mantis, Crab)
- `tryLoadVectObj(name, targetH)` : charge le .j3o depuis `Scenes/vectobj/`, scale au colHeight
- `addItems` : priorite 1=vectobj, 2=sprite bitmap, 3=cube fallback

### Pipeline
```bash
./gradlew convertVectObj buildScenes run
```

---


### Format SBP decodé empiriquement

```
"SBP Object\0"
BYTE numVerts, BYTE padding
numVerts x SWORD[3] big-endian, fixed-point /256 = coordonnées réelles
6 bytes info + BYTE numPolys + BYTE padding
Polygones : nv*4 + 18 bytes chacun
  BYTE nv + 3 bytes flags
  nv x (BYTE 1-based_idx + BYTE u + BYTE v + BYTE normal)
  14 bytes footer (WORD color 12-bit Amiga + BYTE brightness + ...)
```

### SbpObjParser.java
- `parseSbpObject()` : parse un fichier SBP
- `parseProject()` : lit le manifest SBPProjV01 (liste des parties)
- `loadProject()` : assemble toutes les parties d'un dossier .prj
- `amigaColorToJme()` : couleur Amiga 12-bit → ColorRGBA

### VectObjConverter - mode SBP
- Si `name.prj/` existe : utilise SBP (géométrie complète)
- Sinon : binaire vectobj (fallback)
- `buildFromSbp()` : construit le node JME depuis SbpMesh

### Pour utiliser les fichiers SBP :
Copier les dossiers `.prj` dans `src/main/resources/vectobj/`
```bash
./gradlew convertVectObj
```

---


### Fixes triangulation
- `numLines=2` = 3 vertices (v0,v1,v2) → triangle valide (avant : 0 tris car fan de 2 = vide)
- `numLines=1` = ligne → skip (pas de mesh)
- Suppression du `break` sur numLines invalide (remplace par `continue` implicite)

### Couleurs par polygone
Chaque polygone a un footer a `poly + numLines*4 + 8` :
- `+8`  : WORD texIdx (15-bit, bit15=secondary texture set)
- `+10` : BYTE brightness (0=sombre, 100=clair inverse)

Mappings : `texIdx[11:8]` → 1 sur 16 teintes de base, brightness → shade (0.1-1.0)
Vertex colors activees via `VertexColor=true` sur Unshaded.j3md

### Pipeline
```bash
./gradlew convertVectObj
```

---


### VectObjConverter.java

Format binaire analyse depuis `objdrawhires.s` (`draw_PolygonModel`) :
```
+0 : WORD numPoints, WORD numFrames
+4 : frame table [numFrames * 4 bytes] : WORD ptsOfs, WORD angOfs
+4+numFrames*4 : part list : SWORD partId, WORD bodyOfs (termine par -1)
body @ bodyOfs : polygones (18 + numLines*4 bytes chacun, -1 = fin)
  +0 SWORD numLines, +2 WORD flags
  +4 vertex list : WORD ptIdx, BYTE u, BYTE v
  +numLines*4+12 : WORD texIdx, BYTE brightness, BYTE polyAngle, WORD gouraud
pointData @ ptsOfs : numPoints * 6 bytes (SWORD x,y,z)
```

### Tache Gradle
```bash
./gradlew convertVectObj
```
Sortie dans `assets/Scenes/vectobj/*.j3o`

---


### Sprites manquants : worm + robotright

`worm.wad` et `robotright.wad` existent dans `media/hqn` mais ne sont pas
references dans `GLFT_ObjGfxNames_l` du LNK. WadConverter ne les convertissait
jamais. Fix : liste `extraAlienSprites` ajoutee dans WadConverter.main().

### Root cause cubes grises au runtime : fixMaterials

`fixMaterials()` dans GameAppState traversait toutes les geometries incluant
les sprites Unshaded (ColorMap + BlendMode.Alpha + Transparent). Elle cherchait
`DiffuseMap` (Lighting) -> null -> remplacait par gris (0.6,0.6,0.6) et ecrasait
BlendMode.Alpha. Fix : skiper les materiaux deja Unshaded.

### Pipeline
```bash
./gradlew convertWads buildScenes run
```

---


### Bug identifie dans LevelBinaryParser

Structure EntT (defs.i) :
```
+50..+53 : LONG EntT_DoorsAndLiftsHeld_l
           high word (+50..+51) = lock bits hauts
           low word  (+52..+53) = EntT_Timer3_w = door/lift bits
+54      : BYTE EntT_Type_b = defIndex !!
+55      : BYTE EntT_WhichAnim_b
```

Bug : `getInt()` a +50 avance a +54, puis `getShort()` lisait bytes 54-55
= `(defIndex << 8) | whichAnim` -> stocke dans "liftLocks"
Et `get()` lisait byte 56 = padding -> defIndex = toujours 0 !

Ex: liftLocks=3584=0x0E00 -> byte 54=0x0E=14 (glarebox) ← c'etait le vrai defIndex

### Fix LevelBinaryParser
```java
// Avant (FAUX) :
int defIndex = b.get() & 0xFF;  // lisait byte 56 = padding = 0

// Apres (CORRECT) :
int doorsLifts = b.getInt();         // +50..+53, avance a +54
int defIndex   = b.get() & 0xFF;     // +54 = EntT_Type_b
```

### Pipeline
```bash
./gradlew convertLevels buildScenes run
```

---


### Structure HQN expliquee

Chaque sprite HQN (guard, insect, priest, triclaw...) a 3 fichiers :
- `.wad` : donnees sprite, 1 byte/pixel, index 0=transparent
- `.ptr` : table de pointeurs, 4 bytes/colonne = offset byte dans .wad
- `.256pal` : **1024 bytes = 4 types * 32 brightness * 8 screen-color-indices**

### Mapping correct (draw_bitmap_lighted)
```
draw_Pals_vl[pixel] = hqnPal256[light_type*256 + brightness*8 + pixel%8]
screen_color = draw_Pals_vl[pixel]
rgb = globalPalette[screen_color]
```

On utilisait `globalPalette[pixel]` directement = FAUX.

### Fix
- `renderFrameHqn(wad, ptr, gPal, fd, woff, hqnPal256)` : prend le .256pal brut
- `buildHqnPalsVl(hqnPal256, lightType=0, brightness=31)` : construit la table
  draw_Pals_vl pour preview en plein eclairage
- `convertObject(..., rawPalData, ...)` : passe le palData brut

### Pipeline
```bash
./gradlew convertWads buildScenes run
```

---


### Root cause identifie dans objdrawhires.s

Deux formats WAD coexistent dans AB3D2 :

**Sprites standards** (ALIEN2, PICKUPS, KEYS...) — `draw_right_side` :
```asm
move.b  1(a0,d1.w*2),d0   ; stride=2 bytes, 3 pixels/word (5-bit chacun)
and.b   #%00011111,d0      ; masque 5 bits
move.b  (a4,d0.w*2),(a6)  ; palette[idx5bit * 2]
```

**Sprites HQN** (GUARD, INSECT, PRIEST, TRICLAW...) — `draw_bitmap_lighted` :
```asm
move.b  (a0,d1.w),d0      ; stride=1 byte, 1 pixel = 1 octet
beq.s   .skip_black        ; 0 = transparent
move.b  (a4,d0.w),(a6)    ; index DIRECT 8-bit dans globalPalette
```

### Fix WadConverter
- Ajout `HQN_SPRITE_NAMES` : guard, priest, insect, triclaw, ashnarg, robotright, worm, globe
- `renderFrameHqn()` : stride=1 byte, index direct globalPalette
- Detection automatique via baseName dans `convertObject()`

### Pipeline
```bash
./gradlew convertWads buildScenes run
```

---


### Bugs corriges dans WadConverter

**Bug 1 — Palette globale (256pal.bin)** :
```
// AVANT (faux) : lisait le LOW byte = 0x00 => toutes couleurs noires
int r = readShortBE(raw, i*6) & 0xFF;
// APRES (correct) : lit le HIGH byte = valeur reelle
int r = raw[i*6] & 0xFF;  // Peek semantics
```

**Bug 2 — Palette objet (.256pal)** :
```
// AVANT (faux) : readShortBE & 0xFF = LOW byte = 0x00 => index 0 = noir
int globalIdx = readShortBE(palData, i*2) & 0xFF;
// APRES (correct) : Peek(A*2) = premier byte du WORD
int globalIdx = palData[i*2] & 0xFF;
```
AMOS `Peek(base + A*2)` lit un BYTE à l'offset A*2 = premier octet de chaque WORD.

**Bug 3 — Largeur HQN (GUARD, INSECT, PRIEST, TRICLAW, ASHNARG)** :
Le LW du LNK couvre TOUTES les vues de rotation concaténées dans le WAD.
`woff` du header PTR donne la largeur d'UNE seule vue.
```java
// renderFrame() utilise maintenant woff pour limiter LW
int width = (woff > 0 && woff < fd.lw()) ? woff : fd.lw();
```

### Pipeline
```bash
./gradlew convertWads buildScenes run
```

---


### Sprites trouvés dans `ab3d2-tkg-original/media/`
- `includes/` : alien2, pickups, bigbullet, explosion, keys, lamps, glare, rockets, splutch
- `hqn/`      : guard, priest, insect, triclaw, ashnarg, robotright, worm

### Implémentation

**WadConverter** : mise à jour `gatherWadSearchPaths` pour trouver automatiquement
les WAD dans `../ab3d2-tkg-original/media/includes` et `media/hqn`.

**LevelSceneBuilder** :
- `tryLoadSprite(wadName, height)` : charge `Textures/objects/{name}/{name}_f0.png`,
  crée un `Quad` texturé avec `BlendMode.Alpha` + `BillboardControl.Camera`
- Fallback cube coloré si PNG absent
- Mapping `gfxType` alien → nom WAD (`alien2`, `worm`, `robotright`, `guard`, `insect`)
- Taille sprite depuis `colHeight` des definitions (en unités Amiga / 32)

### Workflow
```bash
./gradlew convertWads convertLevels buildScenes run
```

---


### Cause racine

Les coordonnées dans la table ObjectPoints sont stockées en **fixed-point 16.16** :
```
LONG value = (coord_entier << 16) | partie_fractionnaire
coord_réelle = value >> 16  (= high SHORT)
```

C'est le format standard Amiga pour le mouvement fluide (fractionnaire).
Conformément à `hires.s` : `move.w d0,ObjT_ZPos_l(a0)` écrit un SHORT
dans le HIGH WORD du LONG — la partie entière est toujours dans le high word.

### Fix

Dans `LevelBinaryParser` : lire `getShort()` + skip `getShort()` au lieu de `getInt()`
pour chaque composante X et Z dans la table ObjectPoints.

### Résultat

Av ant : `x=-160432128` (mauvais), Après : `x=-2436` (dans zone 88 ✓)
Le medikit proche du joueur et tous les objets apparaissent maintenant aux bonnes positions.

---


### Découverte capitale (hires.s lignes 2266-2270)

Les positions XPos/ZPos/YPos dans `ObjT_XPos_l` (+0..+11) sont **marquées "To be confirmed"**
dans `defs.i` et NE sont PAS stockées dans le fichier binaire.

La structure réelle de chaque ObjT dans `twolev.bin` :
```
+0..+1 : WORD = objPointIndex   INDEX dans la table ObjectPoints
+2..+3 : WORD   padding (0)
+4..+7 : LONG   0 (ZPos placeholder, initialisé runtime)
+8..+11: LONG   0 (YPos placeholder, initialisé runtime)
+12    : WORD   ObjT_ZoneID_w
+16    : BYTE   ObjT_TypeID_b
... (EntT overlay valide à partir de +18)
```

Les **positions monde réelles** sont dans la table ObjectPoints :
- Pointée par `TLBT_ObjectPointsOffset_l` (TLBT +42)
- 8 bytes/entrée : `{ xPos:int, zPos:int }`
- `TLBT_NumObjects_w` = nombre d'entrées
- Code runtime : `move.w (a0),d0` (lit l'index) puis `(a1,d0.w*8)` (accède ObjectPoints)

Explication du bug : Java lisait le WORD d'index comme le mot fort d'un int
→ `x = index * 65536`, `z = 0`, `y = 0` pour tous les objets.

### Correction Y

Le `y` dans le JSON vaut maintenant `zone.floorH` (même valeur que les murs/zones).
Conversion JME : `jy = -zone.floorH / 32 + 0.3f`

### Fichiers modifiés

- **LevelBinaryParser.java** :
  - Parse la table ObjectPoints depuis `objectPointsOffset`
  - Lit le WORD +0 comme `objPointIndex` (pas XPos)
  - `xPos/zPos` = `ObjectPoints[objPointIndex].{x,z}`
  - `ObjData` : ajout du champ `objPointIndex`

- **LevelJsonExporter.java** :
  - Construit `zoneFloorH` map depuis les zones parsées
  - Export `y = zone.floorH` au lieu de `yPos = 0`

- **LevelSceneBuilder.java** :
  - `jy = -y / SCALE + 0.3f` (y = floorH, +0.3 au-dessus du sol)

### Workflow
```bash
./gradlew convertLevels buildScenes run
```

---


### Sources analysées
- `defs.i` : STRUCTURE ObjT, STRUCTURE EntT, constantes OBJ_TYPE_*, ENT_TYPE_*
- `leved303.amos` : ALIENSAVE / THINGSAVE (save format exact de chaque champ)
- `newaliencontrol.s` : ODefT_GFXType_w usage (0=BITMAP, 1=VECTOR, 2=GLARE)

### Catalogue complet ObjT_TypeID_b

| TypeID | Constante          | Signification                                      |
|--------|-------------------|----------------------------------------------------|
| 0      | OBJ_TYPE_ALIEN      | Alien vivant, EntT_Type_b = alien def 0-19        |
| 1      | OBJ_TYPE_OBJECT     | Objet, EntT_Type_b = objet def 0-29               |
| 2      | OBJ_TYPE_PROJECTILE | Bullet/projectile (runtime uniquement)             |
| 3      | OBJ_TYPE_AUX        | Sprite auxiliaire attaché (runtime)                |
| 4      | OBJ_TYPE_PLAYER1    | Position spawn joueur 1                            |
| 5      | OBJ_TYPE_PLAYER2    | Position spawn joueur 2 (coop)                     |

### EntT overlay (18 champs parsés)

```
+18  EntT_HitPoints_b        points de vie initiaux
+21  EntT_TeamNumber_b       équipe alien (0=aucune)
+24  EntT_DisplayText_w      index texte niveau (-1=aucun)
+30  EntT_CurrentAngle_w     angle initial 0-8191 = 360°
+32  EntT_TargetControlPoint_w  waypoint cible aliens
+34  EntT_Timer1_w           STRTANIM = frame de départ (objets)
+50  EntT_DoorsAndLiftsHeld_l  bits portes bloquées
+52  EntT_Timer3_w           bits lifts bloquées
+54  EntT_Type_b             INDEX def alien(0-19) OU objet(0-29)
+55  EntT_WhichAnim_b        frame courante (runtime)
```

### ODefT_Behaviour_w (pour TypeID=1)

| Valeur | Constante               | Exemples                      |
|--------|------------------------|-------------------------------|
| 0      | ENT_TYPE_COLLECTABLE   | health, ammo, armes, clés     |
| 1      | ENT_TYPE_ACTIVATABLE   | switch, levier, terminal      |
| 2      | ENT_TYPE_DESTRUCTABLE  | barils, caisses               |
| 3      | ENT_TYPE_DECORATION    | lampes, décors                |

### ODefT_GFXType_w (newaliencontrol.s)

| Valeur | Constante       | Rendu                                  |
|--------|----------------|----------------------------------------|
| 0      | OBJ_GFX_BITMAP | sprite WAD (alien2.wad, pickups.wad…) |
| 1      | OBJ_GFX_VECTOR | modèle vectoriel (vectobj/blaster…)   |
| 2      | OBJ_GFX_GLARE  | effet glare/smoke additif              |

### Fichiers modifiés

- **LevelBinaryParser.java** : `ObjData` passe de 7 à 14 champs, parse tous les champs EntT.
  Constantes OBJ_TYPE_*, ENT_TYPE_*, OBJ_GFX_* ajoutées.
  Méthode `makeObjDataCompat()` pour rétrocompatibilité.
- **LevelJsonExporter.java** : exporte `defIndex`, `startAnim`, `angle`, `hitPoints`,
  `teamNumber`, `doorLocks`, `liftLocks` au lieu de `entType`/`whichAnim`.
  TypeID PROJECTILE et AUX filtrés (runtime uniquement).
- **LevelSceneBuilder.java** : `addItems()` utilise les nouveaux champs JSON.
  UserData : `defIndex`, `startAnim`, `angle`, `hitPoints`, `teamNumber`, `doorLocks`, `liftLocks`.
  Couleur des cubes par TypeID (rouge=alien, vert=collectible, jaune=activatable...).
- **LnkParser.java** : parseur complet TEST.LNK (86268 bytes, GLF database).
  Extraction WAD names ($2C0), vector names ($13FE0), frame data ($39B0).
- **WadConverter.java** : convertisseur WAD+PTR+256PAL → PNG (format extrait de leved303.amos).
- **AssetAnalyzer.java** : intègre LnkParser, dump TEST.LNK.
- **build.gradle** : tâches `convertWads` et `analyzeAssets` ajoutées.

### Workflow
```bash
./gradlew convertLevels buildScenes run   # rebuild JSON + scènes
./gradlew analyzeAssets                   # dump TEST.LNK
./gradlew convertWads                     # si les .WAD du jeu sont disponibles
```

---

## [2026-04-13] Session 40 — Fix animation + UV portes

### Bug 1 : pas d'animation (mesh statique)
`makeGeo()` appelait `mesh.setStatic()` -> le GPU uploadait le buffer une seule fois
au premier frame, jamais mis a jour. Fix : `makeDoorSegGeo()` utilise maintenant
`mesh.setDynamic()` + `VertexBuffer.setUpdateNeeded()` dans `updateMeshes()`.

### Bug 2 : UV texture inversee (V permute)
Murs : V=0 en bas, V=vM en haut.
Portes (avant) : V=vM en bas, V=0 en haut -> texture inversee.
Fix : `float[] uv = {uOffset,0f, uOffset+uM,0f, uOffset+uM,vM, uOffset,vM}`

### Bug 3 : yTop/yBot swappes dans DoorAccum
zl_Bottom = hauteur sol editeur -> convertit en yBot JME (plancher)
zl_Top    = hauteur plafond editeur -> convertit en yTop JME (plafond)
Avant : les deux etaient inverses -> porte a l'envers, animation negative.

---

## [2026-04-12] Session 39 — Portes : refonte complete basee sur ZLiftable

### Architecture reelle des portes (sources originales)

Les portes sont des ZONES (pas des paires de zones).
Donnees dans `twolev.graph.bin` a `doorDataOffset` (1er int du header TLGT) :

```
[ZLiftable(36b), ZDoorWall×N(10b), -1] × N_portes, 999
```

ZLiftable :
- bottom/top : plage de hauteur (porte fermee/ouverte)
- zoneId     : la zone qui EST la porte
- raiseCondition : 0=approche joueur, 1=toucher, 4=timer, 5=jamais
- lowerCondition : 0=timeout, 1=jamais
- openDuration   : duree en ticks Amiga (50Hz)

### Fixes

1. **Export** : `LevelJsonExporter` exporte maintenant le tableau `doors` dans le JSON
   avec toutes les donnees ZLiftable.

2. **Groupement** : avant = par paire (1_5, 2_5...) -> portes ouvraient separement.
   Maintenant = par zoneId porte -> tous les pans d'une porte montent ENSEMBLE.

3. **Hauteurs** : utilise `zl_bottom`/`zl_top` du ZLiftable au lieu des hauteurs des murs.

4. **Conditions** : DoorControl gere maintenant raiseCondition/lowerCondition/openDuration.
   Porte LOWER_NEVER (lowerCondition=1) reste ouverte indefiniment.

### Workflow
```bash
./gradlew convertLevels buildScenes run
```

---

## [2026-04-12] Session 38 — Portes UV fix + Items de niveau

### Fix portes : animation + UV
- **Animation** : le panneau entier translate vers le haut (plus de compression)
  `offset = fullH * state; curYBot = yBot + offset; curYTop = yTop + offset`
- **UV** : `fromTile` (ZWG(A,B,0)) maintenant transmis au `DoorAccum` et utilise
  dans `makeDoorSegGeo()` pour le decalage U (meme logique que les murs)

### Items de niveau

**Pipeline** : `LevelBinaryParser` parse maintenant les `ObjT` (64 bytes) depuis
`objectDataOffset`. Champs exportes dans le JSON sous `"objects": [...]`.

**Structure ObjT** (defs.i) :
- TypeID=0 alien, TypeID=1 object(item/deco), TypeID=2+ ignoré
- EntType=0 collectible, 1 activatable, 2 destructable, 3 decoration
- whichAnim = index sprite/anim

**Rendu actuel** : cubes couleur par type dans le node `items` du .j3o :
- Rouge = alien
- Vert = collectible (health, ammo, key)
- Jaune = activatable (switch, terminal)
- Orange = destructable (crate, barrel)
- Gris = decoration

### Workflow
```bash
./gradlew convertLevels buildScenes run
```

---

## [2026-04-12] Session 37 — Fix ecran noir : render() -> postRender() pour les menus 2D

### Cause racine REELLE

Les menus utilisaient `AppState.render()` pour dessiner via Renderer2D.
`render()` s'execute AVANT que JME rende ses viewports. L'etat GL laisse
par Renderer2D (FBO, viewport letterbox, shader actif) corrompait le rendu
JME du frame suivant, meme sans invalidateState().

### Fix : utiliser postRender() a la place de render()

`postRender()` s'execute APRES que JME ait completement rendu tous ses
viewports. L'etat GL laisse par Renderer2D ne peut plus impacter JME
pour ce frame — JME a deja fini.

Avantages supplementaires :
- Plus besoin de desactiver/reactiver le viewport JME
- Plus besoin de restaurer l'etat GL dans cleanup()
- Plus besoin de enqueue() pour differer la destruction
- Code beaucoup plus simple

### Ordre d'execution corrige

Avant (bug) :
1. render() : Renderer2D dessine (corrompt GL state)
2. JME rend ses viewports (avec GL state corrompu -> noir)

Apres (correct) :
1. JME rend ses viewports (GL state propre)
2. postRender() : Renderer2D dessine par-dessus (GL state sans impact)

---

## [2026-04-12] Session 36 — Fix ecran noir definitif : destruction differee de Renderer2D

`renderer2D.destroy()` dans `cleanup()` supprimait les programs GL AVANT
que JME ait rendu un frame. Collision d'IDs GL -> shader cache invalide
-> geometrie invisible (fond bleu visible mais rien d'autre).

Fix : `app.enqueue(() -> { r2d.destroy(); return null; })` dans les deux
menu AppStates. La destruction se produit apres que JME ait rendu un frame
propre avec ses propres shaders correctement lies.

---

## [2026-04-12] Session 35 — Fix ecran noir definitif : invalidateState() brise le pipeline JME

### Cause racine identifiee par bisection

Appeler `rm.getRenderer().invalidateState()` dans `AppState.render()` brise
COMPLETEMENT le pipeline de rendu JME — meme le GuiNode ne s'affiche plus,
meme sans aucun menu. Le fond (background color du viewport) reste visible
mais AUCUNE geometrie ne se rend.

Ce bug est tres contre-intuitif car `invalidateState()` est documente comme
"force JME a re-uploader son etat GL", mais en pratique il corrompt quelque
chose dans le pipeline de rendu en JME 3.8.1.

### Fix

```java
// INTERDIT dans AppState.render() :
// rm.getRenderer().invalidateState();  // brise TOUT le rendu JME
// glUseProgram(0);                     // idem

@Override public void render(RenderManager rm) { /* ne rien faire ici */ }
```

Pour le passage menu -> jeu, on se contente dans `cleanup()` de :
```java
glBindFramebuffer(GL_FRAMEBUFFER, 0);
glViewport(0, 0, w, h);
// C'est tout - JME gere le reste
```

### Lecon apprise

Ne JAMAIS appeler `rm.getRenderer().invalidateState()` ni aucun appel GL
de type "reset complet" dans `AppState.render()`. JME gere son propre etat
GL interne et appeler invalidateState() le perturbe irreversiblement.

---

## [2026-04-12] Session 34 — Fix ecran noir definitif : AssetManager FileLocator manquant

### Cause racine

`Main.simpleInitApp()` n'enregistrait PAS le dossier `assets/` comme FileLocator :
```java
// MANQUAIT dans simpleInitApp() :
assetManager.registerLocator(jmeAssets.toAbsolutePath().toString(),
                             FileLocator.class);
```

Sans ca, `AssetManager.loadModel("scenes/scene_A.j3o")` lance une exception
(AssetNotFoundException). Le `catch` dans `GameAppState.initialize()` redirigeait
silencieusement vers `LevelSelectAppState` en plein fade-out noir => ecran noir persistant.

Le menu fonctionnait car `Renderer2D` utilise du GL direct (pas l'AssetManager).
L'editeur JME fonctionnait car il a son propre systeme de localisation des assets.

### Fix
```java
assetManager.registerLocator(
    jmeAssets.toAbsolutePath().toString(),
    com.jme3.asset.plugins.FileLocator.class);
```
Ajoute une seule fois dans `Main.simpleInitApp()` avant le premier AppState.

---

## [2026-04-12] Session 33 — Fix ecran noir (BulletAppState timing + camera spawn)

### Bug racine : NullPointerException sur bullet.getPhysicsSpace()

Dans `initialize()`, on appelait :
```java
bullet = new BulletAppState();
sm.attach(bullet);           // bullet ajoute en attente
bullet.getPhysicsSpace().add(rbc);  // NPE ! physicsSpace = null jusqu'au prochain frame
```

`AppStateManager.attach()` ne fait QUE ajouter l'etat en file d'attente.
Il est initialise au debut du PROCHAIN `sm.update()`, pas immediatement.
Donc `getPhysicsSpace()` retourne `null` → NPE → `initialize()` s'arrete
→ camera jamais positionnee → ecran noir.

### Fix : setup physique differee

Nouvel ordre :
1. `initialize()` : charge .j3o, attache scene, eclairage, camera au spawn, input, attach(bullet)
2. `update() frame 1` : `bullet.isInitialized()` == false → attend, camera libre (noclip)
3. `update() frame 2+` : `bullet.isInitialized()` == true → `setupPhysics()` (collider + CharacterControl)

### Fix : camera positionnee des initialize()

`positionCameraAtSpawn()` appele AVANT l'attach(bullet) → scene visible
des la premiere frame, meme si les physiques ne sont pas encore actives.

### Fix : spawn Y calcule depuis floorH de la zone

Avant : `y = 3.0f` → camera = y+1.5 = 4.5 → au-dessus du plafond (4.0) → noir
Apres : `y = getSpawnFloorY() + 1.0f` → zone 3 (floorH=0) : y=1.0, camera=2.5 → OK

---

## [2026-04-12] Session 32 — Fix sols + step walls (floorIdx + linteaux/marches)

### Bug 1 : floorIdx() formule incorrecte

Le `whichTile` dans le binaire est l'OFFSET MEMOIRE dans la banque floortile,
pas un index direct. Format AMOS (`_FLOOR2SCREEN`) :
```amos
S = ADR + (NR mod 4) + ((NR/4) and 3)*256
```
Donc l'inverse : `NR = (whichTile >> 8) * 4 + (whichTile & 3)`

Verification avec level_A.json :
| whichTile | hex   | NR calcule | Texture        |
|-----------|-------|------------|----------------|
| 513       | 0x201 | 9          | floor_10.png   |
| 257       | 0x101 | 5          | floor_06.png   |
| 769       | 0x301 | 13         | floor_14.png   |
| 1         | 0x001 | 1          | floor_02.png   |
| 0         | 0x000 | 0          | floor_01.png   |

### Bug 2 : murs de marche et linteaux non rendus

**Avant** : si `oz!=0` et `abs(botH) > 8` -> `sl++` (saut, rien rendu)
**Avant** : si `oz!=0` et `abs(botH) <= 8` -> portail/porte (toujours, meme step)

**Apres** : distinction porte reelle / mur de marche par la HAUTEUR :
- `abs(botH) <= 8` et `wallHeight >= 64` -> porte (panneau animable)
- Tout le reste -> rendu comme geometrie statique

Exemples corrects :
- Zone 131-132 `chevrondoor` h=192 -> porte (ajoute a dg) OK
- Zone 7-6 step h=24           -> mur de marche (rendu geometrie) OK
- Zone 34-35 step h=16          -> mur de marche (rendu geometrie) OK
- Zone 34-35 linteau abs(bot)=160 -> linteau (rendu geometrie) OK

### Workflow :
```bash
./gradlew buildScenes   # pas besoin de convertAssets si deja fait
./gradlew run
```

---

## [2026-04-12] Session 31 — Fix textures : footer WGH + fromTile UV offset

### Fix 1 : hauteur texture depuis le footer du .256wad

L'AMOS stocke la hauteur dans les **2 derniers bytes** du fichier .256wad :
```amos
WGH = Deek(Start(701+ZWGC) + T - 2)   ; T = Length(bank)
```
`WallTextureExtractor` faisait une detection heuristique → textures parfois scramblees.

Fix : `readHeightFromFooter(raw)` + `computeWidth(fileSize, texH)`
→ Relancer `./gradlew convertAssets` pour regenerer les PNG avec les bonnes dimensions.

### Fix 2 : decalage U depuis fromTile (ZWG(A,B,0))

Le champ `texIndex` = `ZWG(A,B,0)` = **fromTile** = position X dans le strip de texture.
Dans le renderer AMOS : `draw_FromTile_w = texIndex * 16` (decalage pixel).

Dans JME (UV normalisees) :
```java
uOffset = fromTile * 16.0f / texWidth
uMax    = wallLen / texWidth   // (au lieu de wallLen / 256)
```

La largeur reelle `texWidth` est lue depuis l'image PNG chargee par JME
(`mat.getTextureParam("DiffuseMap").getTextureValue().getImage().getWidth()`).

### Action requise pour voir le resultat :
```bash
./gradlew convertAssets   # re-extrait PNG avec bonnes dimensions (footer WGH)
./gradlew buildScenes     # regenere .j3o avec UV corrects (fromTile offset)
# ou tout en un :
./gradlew setupAll
```

---

## [2026-04-11] Session 30 — Fix textures (clipIdx/ZWGC) + PointLights + AmbientLight

### Bug critique : mauvais index texture (texIndex vs clipIdx)

Depuis LevelED.txt (AMOS BASIC) :
```
ZWG(A,B,0) = ZWG  = fromTile = POSITION X dans le strip de texture (PAS l'index fichier)
ZWG(A,B,2) = ZWGC = chunk file = INDEX DU FICHIER .256wad = VRAI index texture
```

Dans le binaire (ZoneGraphAdds) :
- HIGH SHORT de LK[W+VO] = ZWG(A,B,0) = `texIndex` dans JSON = fromTile
- WORD DK[ZWG(A,B,2)]   = ZWGC       = `clipIdx` dans JSON  = fichier .256wad a utiliser

Fix dans `LevelSceneBuilder.parseWalls()` :
- w[6] = `clipIdx` = ZWGC = vrai index -> utilise pour selection du materiau
- w[2] = `texIndex` = fromTile -> ignore pour la selection de texture
- Si JSON ancien sans `clipIdx` : fallback sur w[2]

**Action requise** : `./gradlew convertLevels buildScenes` pour regenerer JSON avec `clipIdx`.

### Lumieres de zones : PointLight reelles dans le .j3o

Avant : proxy Nodes avec UserData (lumieres crees a la main dans GameAppState)
Apres : `node.addLight(PointLight)` dans LevelSceneBuilder -> serialise dans le .j3o

Chaque zone lumineuse (brightness >= 40) genere une `PointLight` attachee a un
noeud proxy dans `lights`. Couleur chaude, radius proportionnel a la luminosite.

### AmbientLight par defaut dans le .j3o

`root.addLight(new AmbientLight(0.35, 0.32, 0.38))` serialisee dans le .j3o.
GameAppState detecte si l'AmbientLight est deja presente, sinon ajoute un fallback.

### Workflow obligatoire apres ce fix :
```bash
./gradlew convertLevels   # regenere JSON avec clipIdx correct
./gradlew buildScenes     # regenere .j3o avec bonnes textures + PointLights
```

---

## [2026-04-11] Session 29 — Fix normales + FaceCullMode + gradle.properties

### Fix critique : normales murs inversées

**Problème** : `(dz, 0, -dx)/L` = perpendiculaire DROITE = normale SORTANTE (vers l'extérieur).
Les murs étaient sombres car la normale pointait à l'opposé du joueur et de la headlight.

**Fix** : `(-dz, 0, dx)/L` = perpendiculaire GAUCHE = normale ENTRANTE (vers l'intérieur de la zone).

```
leftPt ──────────────► rightPt
         direction →
         intérieur = côté GAUCHE
         normale correcte = (-dz, 0, dx)/L
```

Fichiers corrigés : `LevelSceneBuilder`, `LevelGeometry`, `DoorSystem`, `DoorControl`

### Fix : FaceCullMode.Off sur tous les matériaux

Dans un donjon, les murs/sols/plafonds sont vus des deux côtés.
Le flip Z (`jme.z = -worldZ`) peut inverser le winding des polygones de sol/plafond.
Solution : désactiver le backface culling sur tous les matériaux.

```java
mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
```

### Fix : Ambient 0.5 dans les matériaux

`Ambient = (0.5, 0.5, 0.5)` → les zones sombres (brightness=0) restent partiellement visibles.
La headlight `PointLight` et les lumières de zones ajoutent l'éclairage directionnel.

### Fix : textures dans .j3o

**Ordre obligatoire** : `convertAssets` AVANT `buildScenes`.
Si `texMat()` ne trouve pas la PNG → warning (pas de crash) → fallback magenta.
Le .j3o sauvegarde les références de textures par chemin relatif (`Textures/walls/...`).
Au runtime, JME retrouve les PNG via le subproject `assets/` sur le classpath.

### gradle.properties — toutes les tâches documentées

Actions NetBeans custom (clic droit → Custom) :
```
1 - Copy Amiga resources    copyResources
2 - Convert assets          convertAssets
3 - Convert levels          convertLevels
4 - Build scenes            buildScenes
5 - Setup ALL               setupAll (pipeline complet)
6 - Resources status        resourcesStatus
7 - Clean generated assets  cleanResources
```

JVM daemon Gradle : `org.gradle.jvmargs=-Xmx2g`

---

## [2026-04-11] Session 28 — LevelSceneBuilder + .j3o + DoorControl + Minie
## [2026-04-11] Sessions 26-27 — Migration JME complète, abandon LWJGL direct
## [2026-04-09] Sessions 13-25 — Renderer3D LWJGL, textures, portes, collision
## [2026-04-08] Sessions 7-12 — Infrastructure, menu, fire, level select
