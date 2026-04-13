# CHANGELOG — Alien Breed 3D II Java/JME Port

---

## [2026-04-13] Session 41 — Structure ObjT/EntT complète + catalogue TypeID

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
