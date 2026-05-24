# Cicero V3 — Refondation : Win Equity Contribution (WEC)

## Philosophie : Pas d'oracle, que des données

V2 et V3-archetype étaient des **algorithmes prescriptifs** : "Kassadin scale, donc lane = moins importante. Tank = doit tanker." Toutes ces valeurs sont des verdicts humains imposés à l'algorithme.

V3-WEC est **descriptif et causal** : on observe ce qu'il s'est passé dans LA partie, on mesure l'impact causal de chaque joueur sur la probabilité de victoire de son équipe.

Le score est strictement défini :
- **0** : ce joueur a contribué massivement à la défaite de SON équipe (équivalent troll/AFK/inting)
- **50** : ce joueur a fait son travail. Sa contribution nette à l'équité de victoire est ≈ 0
- **100** : ce joueur a porté la victoire (1v9). Sa contribution nette représente +50% d'équité cumulée
- **>100** : performance exceptionnelle. Cumulé > +50% d'équité

Le score n'évalue plus "es-tu bon en CS pour ton rôle" — il évalue "à quel point as-tu fait basculer la partie en faveur de ton équipe". C'est exactement ce que les analystes esport mesurent intuitivement.

---

## 1. Les Trois Piliers Conceptuels de V3-WEC

### Pilier A : Spatial Truth Engine

L'API Timeline donne `position {x, y}` pour les 10 joueurs chaque minute (et pour chaque kill event). C'est la donnée que V1, V2, V3-archetype n'ont jamais utilisée. Avec elle :

- **Lane validation** : un support à 2000 unités de son ADC pendant 80% du laning phase = roamer ou abandoné
- **Teamfight detection** : kills agrégés dans un rayon de ~2000 unités et 15 secondes = teamfight
- **Participation réelle** : pour chaque event majeur, qui était dans la zone ? (pas qui figure dans les stats)
- **Map control** : quelle équipe occupe quelle moitié de carte minute par minute
- **Roam efficacité** : trajectoires détectables (mid descend bot → kill bot)
- **Validité d'une ward** : un ward placé dans un coin mort vaut 0, un ward sur drake spawn 30s avant le drake vaut tout

### Pilier B : Win Equity Timeline

À chaque instant, calculer la probabilité de victoire (WP) de chaque équipe à partir de l'état du jeu :

```
WP_blue(t) = sigmoid(equityScore_blue(t))

equityScore = w₁·goldDiff(t)/1000 
            + w₂·killDiff(t) 
            + w₃·turretDiff(t) 
            + w₄·dragonDiff(t) 
            + w₅·baronBuff(t) 
            + w₆·inhibDiff(t)
            + w₇·structureCorridorDepth(t)
```

Les coefficients `w₁..w₇` sont calibrés sur des données historiques (publiques : Oracle's Elixir, Riot E-sport data) ou à défaut, dérivés empiriquement. Pas hardcodés "à la main de Dieu" : régressés sur l'issue réelle de milliers de parties à chaque palier d'elo.

Le résultat : à chaque minute, on connaît WP_blue ∈ [0, 1]. Au début, WP=0.5. À la fin, WP=1 ou 0 selon le résultat.

**ΔWP entre deux events = équité créée/détruite par ce qui s'est passé entre eux.**

### Pilier C : Causal Attribution Engine

À chaque event détectable depuis l'API, on attribue le ΔWP à ses responsables :

| Event | Attribution |
|---|---|
| CHAMPION_KILL | killer reçoit +ΔWP, victim -ΔWP, chaque assist +ΔWP/3, tout joueur de l'équipe killer dans rayon 2500 reçoit ΔWP × 0.15 (présence) |
| ELITE_MONSTER_KILL (drake, baron, herald) | killer (smiter) reçoit 40% du ΔWP, joueurs de son équipe dans rayon 3000 partagent les 60% restants (présence pondérée) |
| BUILDING_KILL | distribué entre joueurs ayant infligé des dégâts à la structure (`damageStats.totalDamageDoneToBuildings` delta) |
| WARD_PLACED (high-value) | placeur reçoit ΔWP si la ward révèle un ennemi dans les 90s suivantes (=> kill ou évasion alliée) |
| Sauvetage (heal/shield event correlé à survival d'un allié en danger) | utilité support reçoit +ΔWP × 0.3 |
| Mort isolée sans cause valide (away from team, no objective trade) | joueur reçoit ΔWP entièrement négatif |

**Le score d'un joueur = Σ(ΔWP attribué)** sur toute la partie, transformé sur l'échelle [0, 100+].

---

## 2. Architecture du Pipeline

```
                      Raw Riot API
                ┌──────┴──────────┐
                ▼                 ▼
            Match JSON      Timeline JSON
                │                 │
                ▼                 ▼
   ┌─ PlayerStats (challenges) ─┐ ┌─ Frames (position, gold, dmg per min) ─┐
   │                            │ │                                         │
   │                            │ │  Events (kills, wards, objectives) ──┐  │
   └────────────┬───────────────┘ └────────────┬───────────────┬─────────┘  │
                │                              │               │             │
                ▼                              ▼               ▼             │
        EnrichedContext ◄─── Spatial Truth Engine ────► Event Stream         │
                │                                              │             │
                ▼                                              │             │
     Behavior Profiler                                         │             │
     (no priors !)                                             │             │
                │                                              │             │
                ▼                                              ▼             │
   ┌─────────────── Win Equity Timeline Computer ─────────────────┐         │
   │  Per minute : WP_blue(t)  →  ΔWP(t→t+1) caused by events      │         │
   └──────────────────────────────┬──────────────────────────────┘         │
                                  ▼                                         │
              Causal Attribution Engine ◄──────────────────────────────────┘
              (attributes ΔWP to involved players based on position+role+impact)
                                  │
                                  ▼
              Per-Player WEC Score = Σ ΔWP_attributed
                                  │
                                  ▼
                Normalization [0..100+] + Commentator Brief
```

---

## 3. Spatial Truth Engine — Détails Techniques

### 3.1 Extraction des Trajectoires

Pour chaque frame (1 par minute), on stocke `positions[participantId] = {x, y, gold, level, dmgDoneCumul, dmgTakenCumul}`.

Sur 35 minutes : 35 frames × 10 joueurs = 350 points de données spatiales. Ce qui permet :

```java
class PlayerTrajectory {
    int[] positionsX;       // [pos@min0, pos@min1, ...]
    int[] positionsY;
    int[] goldCumulative;   // courbe d'or
    int[] xpCumulative;     // courbe d'xp
    int[] dmgChampsCumul;   // dégâts aux champions cumulés
    int[] dmgTakenCumul;    // dégâts subis cumulés
    int[] levels;           // niveau au moment de chaque frame
}
```

### 3.2 Détection des Zones de Combat (Teamfight Clustering)

Sur la timeline, on récupère tous les `CHAMPION_KILL` events avec leurs positions et timestamps. On applique un clustering spatial-temporel simple :

```java
// Deux kills appartiennent au même teamfight si :
// - Distance ≤ 2500 unités
// - Délai ≤ 15 secondes entre deux kills consécutifs du cluster
List<Teamfight> teamfights = clusterKills(allKillEvents, 2500, 15000);

// Chaque teamfight a:
class Teamfight {
    long startTime, endTime;
    int centerX, centerY;
    List<Kill> kills;
    int killsBlue, killsRed;
    int participantsBlue, participantsRed;  // déduit des positions à start
    int objectiveAdjacent;  // si proche d'un objectif spawn dans les 60s
}
```

Les teamfights permettent ensuite l'attribution : **être dans le rayon d'un teamfight = participation**. Un Aurelion Sol qui clear sa lane pendant un teamfight bot ne participe PAS, même si Riot lui donne un assist générique.

### 3.3 Détection des Pickoffs Réels

Un pickoff = un kill où la victime était isolée (pas d'allié dans rayon 2000). On vérifie ça avec les positions :

```java
boolean isPickoff(Kill k, Frame contextFrame) {
    Position victimPos = k.position;
    Team victimTeam = k.victim.team;
    int alliesNearby = countAlliesWithin(victimPos, victimTeam, contextFrame, 2000);
    return alliesNearby == 0;
}
```

Plus besoin d'approximation par "écart temporel < 10s entre 2 kills". On a la vérité spatiale.

### 3.4 Roam Detection

Un roam = un mid/support qui quitte sa zone normale (corridor de lane) pendant une fenêtre temporelle, et un kill arrive dans sa nouvelle zone.

```java
boolean isRoam(PlayerContext player, long timestamp) {
    Position pos = positionAt(player, timestamp);
    Position expectedPos = expectedLanePosition(player.role, timestamp);
    double deviation = distance(pos, expectedPos);
    return deviation > 4000;  // hors de sa lane
}
```

Le `expectedLanePosition` est dérivé géométriquement : milieu de la lane attendue selon le rôle (TOP = corridor topside, MID = midline, BOT = botside).

---

## 4. Win Equity Timeline — Calcul de WP

### 4.1 Modèle

À chaque minute, on calcule un "équity score" pour l'équipe bleue :

```
S_blue(t) = α(t) · normGold(goldDiff(t))
          + β(t) · normKills(killDiff(t))
          + γ(t) · normTurrets(turretDiff(t))
          + δ(t) · normDragons(dragonStack(t)) 
          + ε(t) · activeBaronBuff(t)
          + ζ(t) · inhibDiff(t)

WP_blue(t) = 1 / (1 + exp(-S_blue(t)))
```

### 4.2 Coefficients dépendants du temps

Les coefficients α..ζ varient selon le moment de la partie :

| Phase | Gold weight | Kills weight | Turrets weight | Dragons weight | Baron weight |
|-------|-------------|--------------|----------------|----------------|--------------|
| 0-10m | 0.6 | 0.3 | 0.05 | 0.05 | 0 |
| 10-20m | 0.4 | 0.25 | 0.20 | 0.15 | 0 |
| 20-30m | 0.25 | 0.15 | 0.20 | 0.20 | 0.2 |
| 30m+ | 0.15 | 0.10 | 0.25 | 0.20 | 0.30 |

Ces coefficients sont calibrés sur des données historiques publiques (Oracle's Elixir, Riot's E-sport API). Initialement, on peut utiliser les valeurs ci-dessus dérivées empiriquement de la littérature LoL data science, puis raffiner.

### 4.3 ΔWP par Event

Pour un event à temps `t` :
```
ΔWP_event = WP_blue(post_event_state, t) - WP_blue(pre_event_state, t)
```

Exemple : kill du carry ennemi à 25 minutes alors que les équipes sont à 50/50.
- Pré-event : 5-5 kills, 30k-30k gold, équivalent → WP = 0.50
- Post-event : 6-5 kills, +300g shutdown → WP = 0.54
- ΔWP = +0.04

Si ce même kill se passe pendant une fight où l'équipe bleue prend ensuite un baron : la chaîne ΔWP cumulée peut atteindre +0.20.

### 4.4 Calibration Initiale

V3.0 utilise des coefficients dérivés du domaine (validés visuellement contre des matchs connus). V3.1 ajoutera une calibration par régression logistique sur un dataset Oracle's Elixir public (5000+ matchs Diamond+).

---

## 5. Causal Attribution Engine

### 5.1 Règles d'Attribution Détaillées

**Pour un CHAMPION_KILL (ΔWP = +X pour l'équipe qui tue)** :
```
killer.score += 0.60 × ΔWP
chaque assist.score += (0.30 / nbAssists) × ΔWP
chaque allié dans rayon 2500 (présence) .score += 0.10 × ΔWP / nbAlliésPrésents
victim.score -= ΔWP    // pleine pénalité pour la victime
```

**Pour un ELITE_MONSTER_KILL (drake, baron, herald)** :
```
smiter.score += 0.40 × ΔWP
chaque allié dans rayon 3000 .score += (0.60 / nbAlliésPrésents) × ΔWP
```

**Pour un BUILDING_KILL** :
On lit le `damageStats.totalDamageDoneToBuildings` de chaque joueur dans les 60s précédant le kill. On distribue ΔWP proportionnellement aux dégâts.

**Pour un WARD_PLACED stratégique** :
Une ward "stratégique" est définie par sa position (zone froide à fort impact : pit drake, pit baron, jungle entries, bushes lane). Si dans les 90s, un ennemi passe dans la vision et qu'un kill suit (par alliés) :
```
warder.score += 0.20 × ΔWP_du_kill_aval
```

**Pour un HEAL/SHIELD** (extrait via `saveAllyFromDeath` + correlation positionnelle) :
Si un allié reçoit un heal/shield juste avant des dégâts létaux et survit :
```
healer.score += 0.30 × ΔWP_évité  
// où ΔWP_évité = la perte d'équité que l'équipe aurait subie si l'allié était mort
```

**Pour un CC sur une cible threat** :
Si l'immobilisation (CC) est suivie d'un kill allié sur cette même cible dans les 3 secondes :
```
ccPlayer.score += 0.25 × ΔWP_du_kill
```

### 5.2 Kill Value Computation

The ΔWP of a CHAMPION_KILL is NOT simply `WP(post) - WP(pre)` from gold/kill counts. Kill value depends on four orthogonal factors:

#### 5.2.1 Target Threat Multiplier

The Riot API provides `bounty` on each CHAMPION_KILL event — the gold collected by the killer, which encodes the victim's fed/dangerous status via shutdown bounties.

```
targetThreat = bountyCollected / 300.0   // 300 = base kill gold
// Normal kill: 1.0 multiplier
// Shutdown on 600g bounty player: 2.0+ multiplier
// Killing the 15/2 carry with 1200g bounty: 4.0+ multiplier
```

Additionally, we compare victim's `totalGold@death` to their lane benchmark to classify threat level:
- `victimGold > 1.3 × avgTeamGold` → fed, high threat
- `victimGold < 0.7 × avgTeamGold` → irrelevant, low threat (0/10 player)

#### 5.2.2 Snowball Momentum Index

A kill's downstream value depends on its game phase and context:

```
// Early lane phase (< 10 min): a solo kill often means lane is over
snowballFactor(kill, phase, victimDeathTimer):
  if phase < 10min AND no allies near victim AND isLaneKill:
      return 1.5 + (gameMinute / 20.0)   // max 2.0 at 10min
  elif objectiveTakenWithin60s:
      return 1.3   // kill unlocked an objective
  else:
      return 1.0
```

A solo kill in the top lane at 5 minutes doesn't just give 300g — it means:
- Victim misses 2-3 waves while respawning (150-200g equivalent)
- Killer has tempo advantage for next wave + plate
- Victim can't trade back → psychological snowball
This is captured by `snowballFactor × ΔWP`, not just the raw gold delta.

#### 5.2.3 Resource Efficiency Factor

If a kill requires spending summoner spells / ultimate, the opportunity cost must be weighed:

The Riot timeline does NOT expose summoner spell usage directly, but we can approximate via:
- `killEvent.bounty / 300 < 0.8` → killing a weak target: spending resources was probably not efficient
- `killEvent happening while enemy takes objective within 60s` → negative efficiency (see 5.2.4)

```
resourceEfficiency(kill):
  if targetThreatMultiplier < 0.8:   // killed a non-threat player
      return 0.6   // 40% discount on this kill's value
  elif objectiveLostAfterKill:
      return -0.5   // killing an irrelevant target while giving baron is net negative
  else:
      return 1.0
```

#### 5.2.4 Opportunity Cost Adjustment

If within 60 seconds after a kill, the enemy team secures a major objective (dragon, baron, herald):

```
opportunityCostAdjustment(killEvent, objectiveEvents):
  for each obj in objectiveEvents where obj.timestamp in [kill.ts, kill.ts + 60s]:
      if obj.killerTeamId != kill.killerTeamId:   // enemy took it
          deltaWP_loss = WP_delta(objective_taken)
          opportunityCost += deltaWP_loss × 0.8   // 80% of the objective's value is blamed on the kill
  return -opportunityCost
```

#### 5.2.5 Composite Kill ΔWP Formula

```
killerΔWP = base_ΔWP
           × targetThreatMultiplier
           × snowballFactor
           × resourceEfficiency
           + opportunityCostAdjustment

victimΔWP = -base_ΔWP × targetThreatMultiplier
```

**Examples:**
- Solo kill top @5min, victim 0/0, no objective context:
  `base=0.03 × 1.0 × 1.75 × 1.0 + 0 = 0.052`
- Kill the 15/2 carry (1200g bounty) in 40min teamfight:
  `base=0.05 × 4.0 × 1.0 × 1.0 + 0 = 0.20`
- Kill 0/10 player burning flash+ult, enemy takes baron:
  `base=0.02 × 0.5 × 0.6 + (-0.12) = -0.09` (negative overall)

---

### 5.3 Errors / Throws

Une mort "non causée par un objectif" qui survient quand l'équipe est en avance :
```
if (death is isolated AND no objective trade AND WP_before > 0.55):
    victim.score -= ΔWP × 1.5  // pénalité augmentée pour throw
```

### 5.4 Indirect Contributions

Un splitpusher qui n'est pas dans un teamfight mais qui détourne 2 ennemis :
- Détectable : positions des 2 ennemis adjacentes au splitpusher pendant le fight
- Attribution : il reçoit `+0.20 × ΔWP_du_teamfight_gagné` même s'il n'y était pas

---

## 6. Behavior Profiling — Sans Aucun Prior

Le scaling factor manuel est éliminé. À la place : on observe l'évolution réelle du joueur dans CETTE partie.

```java
class ObservedBehavior {
    // Courbe de puissance économique
    double goldSlopeEarly;   // pente gold@10 / 10
    double goldSlopeMid;     // pente (gold@20 - gold@10) / 10
    double goldSlopeLate;    // pente (gold@30 - gold@20) / 10
    
    // Courbe de dommage
    double dpmEarly;         // dmg/min de 0 à 10
    double dpmMid;           // 10 à 20
    double dpmLate;          // 20 à 30+
    
    // Pattern spatial
    double avgDistanceFromTeam;  // moyenne distance au centroïde équipe
    double laneFidelity;         // % de temps dans le corridor de sa lane
    double mapMobility;          // somme des déplacements entre frames
    
    // Identité de joueur dérivée (calculée, pas déclarée)
    PlayerProfile profile;  // {EARLY_AGGRESSOR, SCALER, ROAMER, SPLITPUSHER, TEAMFIGHTER}
}
```

À partir de ces observations, on dérive le profil :
- `goldSlopeLate > goldSlopeEarly × 1.5` ET `dpmLate > dpmEarly × 1.4` → SCALER (s'est correctement déchaîné fin de partie)
- `goldSlopeEarly > 0.5 × max` ET `kills_avant_15m > 3` → EARLY_AGGRESSOR
- `avgDistanceFromTeam > 6000` ET `damageDealtToBuildings > 30%_total` → SPLITPUSHER
- `mapMobility > 1.5 × avgTeam` ET `earlyRoamTakedowns > 2` → ROAMER

Le profil sert à **valider la cohérence** de la performance, pas à pré-classer.

Exemple :
- Kassadin avec profil = SCALER + WEC élevé en late game → cohérent → score réel
- Kassadin avec profil = EARLY_AGGRESSOR + 10 kills à 12 min mais aucun impact late → score réduit car son profil suggère qu'il a stat-padded au mauvais moment

Ce n'est pas une punition arbitraire, c'est une mesure de **timing pertinent**.

---

## 7. Rework Complet des Supports

Le support est le rôle où V2 échoue le plus. V3 :

### 7.1 Vision contextualisée
```
ward_value = Σ (
   nb_ennemis_révélés × 1.0       // détection
 + nb_ganks_evités_dans_zone × 5.0  // évitement
 + nb_objectifs_sécurisés × 8.0    // contrôle objectif
)
```

Algorithme :
1. Pour chaque WARD_PLACED, noter position et timestamp.
2. Pour les 90s suivantes, regarder les positions ennemies. Combien passent dans rayon 1500 ?
3. Pour chaque passage, vérifier s'il y avait un kill allié évité (ennemi qui rebrousse), ou un objectif (drake/baron) capturé dans cette zone.
4. Score = somme pondérée.

Une ward placée à la base ennemie qui ne révèle personne pendant 90s = 0 valeur. Une ward placée pré-drake qui révèle un steal-attempt = 30+ valeur.

### 7.2 Heal/Shield contextualisés

L'API ne donne pas les events heal/shield individuels (sauf pour quelques champions). Mais on a :
- `effectiveHealAndShielding` (total)
- `saveAllyFromDeath` (count of "saves")
- `damageTaken` curves per ally (par minute)
- Positions

**Heuristique** : si `saveAllyFromDeath > 0`, on regarde les minutes où un allié a son HP critiquement bas (proxy via `championStats.currentHealth` dans participantFrames). On vérifie que le support était à proximité (< 1500). Chaque "save validée" reçoit `+0.30 × ΔWP_évité`.

Pour un Lulu qui shield un Vayne en mid-fight :
- HP Vayne va de 1200 → 200 (took 1000 damage) 
- Si pas de shield, Vayne meurt → équipe perd ce fight → ΔWP = -0.15
- Avec le shield, Vayne survit → ΔWP évité = +0.15
- Lulu reçoit +0.045

### 7.3 CC contextualisé

Les supports tank/engage sont jugés sur `immobilizeAndKillWithAlly`, **pas sur** `enemyChampionImmobilizations`.

Un Blitzcrank qui pull la lane creep 30 fois sans jamais convertir = 0 valeur.
Un Blitzcrank qui pull 5 fois et 4 de ces pulls finissent en kill = 4 × attribution causale.

### 7.4 Lane support spécifique

Le `goldDiff@14` du support est jugé contre la **différence ADC + Support** combinée (puisque les supports ne farm pas) :
```
laneDuoGoldDiff = (ctx_adc.gold@14 + ctx_sup.gold@14) - (oppCtx_adc.gold@14 + oppCtx_sup.gold@14)
```

Le support reçoit 40% du crédit/blame de cette différence.

---

## 8. Le JSON de Sortie

```json
{
  "math_score": 78,
  "wec_raw": 0.42,                     
  "wec_cumulative_delta_wp": 0.21,     
  "behavior_profile": {
    "identity": "SCALER",
    "gold_slope_late": 0.85,
    "gold_slope_early": 0.34,
    "lane_fidelity": 0.78,
    "map_mobility": 0.41,
    "avg_distance_from_team": 3200
  },
  "win_equity_contributions": [
    {
      "event": "CHAMPION_KILL",
      "timestamp_min": 22.3,
      "role_in_event": "killer",
      "delta_wp": 0.05,
      "attributed": 0.030,
      "context": "Pickoff isolé sur le ADC ennemi avant baron"
    },
    {
      "event": "DEATH",
      "timestamp_min": 28.1,
      "role_in_event": "victim",
      "delta_wp": -0.08,
      "attributed": -0.080,
      "context": "Mort isolée en split, équipe ahead à 60% WP : THROW"
    }
  ],
  "team_context": {
    "elo": "PLATINUM",
    "team_avg_wec": 0.08,
    "team_carry_player": "championX",
    "team_throw_player": null
  },
  "commentator_brief": {
    "tone": "impactful",
    "decisive_moments": [
      "+0.06 ΔWP au pickoff 22:30",
      "+0.04 ΔWP au teamfight 26:15",
      "-0.08 ΔWP throw 28:10"
    ],
    "narrative_hook": "Scaler qui s'est correctement déchaîné mid-game, malgré un throw coûteux à 28min."
  }
}
```

---

## 9. Plan de Construction

### Phase 1 — Extraction Positionnelle (foundation)
1. Étendre `PlayerContext` avec `PlayerTrajectory` (positions, gold, xp, dmg par frame)
2. Réécrire `MatchDataExtractor.extractAll()` pour lire TOUTES les frames (pas juste la 14)
3. Extraire les événements WARD_PLACED, WARD_KILL avec positions
4. Extraire les événements CHAMPION_KILL avec positions ET damage breakdown

### Phase 2 — Spatial Truth Engine
5. Créer `SpatialAnalyzer.java`
   - `clusterTeamfights(events)` → liste de Teamfight
   - `isPickoff(kill, frame)` → bool
   - `playersInRadius(position, radius, timestamp)` → liste
   - `expectedLanePosition(role, timestamp)` → Position

### Phase 3 — Win Equity Engine
6. Créer `WinEquityComputer.java`
   - `computeWP(gameState, time)` → double ∈ [0,1]
   - `wpDelta(event, preState, postState)` → double
   - Coefficients de calibration en JSON (`wp_coefficients.json` dans resources)

### Phase 4 — Causal Attribution Engine
7. Créer `CausalAttributor.java`
   - Règles d'attribution par type d'event
   - `attribute(event, deltaWP, allPlayers, positions)` → Map<player, attribution>

### Phase 5 — Behavior Profiler
8. Créer `BehaviorProfiler.java`
   - Calcule `ObservedBehavior` par joueur
   - Dérive `PlayerProfile` depuis observations

### Phase 6 — Support Rework
9. Créer `SupportImpactAnalyzer.java`
   - `wardValue(warder, gameState)` → double
   - `healShieldValue(supporter, alliesHpCurves)` → double
   - `ccChainValue(supporter, killEvents)` → double

### Phase 7 — Intégration
10. Réécrire `ScoreCalculator.analyzePlayer()` qui orchestre tout :
    ```
    1. trajectory = extractTrajectories(rawTimeline)
    2. teamfights = SpatialAnalyzer.clusterTeamfights(events, trajectory)
    3. wpTimeline = WinEquityComputer.compute(events, frames)
    4. attributions = CausalAttributor.attribute(events, wpTimeline, trajectory)
    5. behavior = BehaviorProfiler.profile(player, trajectory)
    6. supportBonus = (player.role == SUPPORT) ? SupportImpactAnalyzer.analyze() : 0
    7. WEC_raw = sum(attributions) + supportBonus
    8. score = normalize(WEC_raw, behavior, teamContext) → [0, 100+]
    ```
11. Mettre à jour ScoreCalculator.java avec la nouvelle pipeline
12. Supprimer ScoringConstants.java → tout est désormais dérivé (sauf coefficients WP en JSON)
13. Supprimer ChampionProfileLoader.java → plus de scaling factor manuel

### Phase 8 — Validation
14. Tester sur 30 parties variées (smurfs, throws, comebacks, stomps)
15. Comparer aux analyses_algo/ manuelles
16. Ajuster les coefficients de WP (pas les benchmarks champion — il n'y en a plus)

---

## 10. Ce Que V3-WEC N'a PLUS

- ❌ ChampionProfileLoader avec scaling_factor hardcodés
- ❌ Champion archetype matrix
- ❌ Pillars avec benchmarks role-spécifiques
- ❌ Bonus/Malus synergies (Glass Cannon, Hyperscaling, etc.)
- ❌ Constantes par classe (TANK_CC_EXPECTED, MAGE_DPM_WEIGHT, etc.)
- ❌ Mapping champion → classe statique

## Ce Que V3-WEC A Toujours

- ✅ `benchmarks.json` pour les valeurs d'attente de CS / Vision PAR ELO (utilisé en validation, pas en scoring direct)
- ✅ Soft-cap et floor protection (clamp final 0-100+)
- ✅ Le pipeline JSON output pour l'IA caster

---

## 11. Pourquoi C'est Fondamentalement Mieux

| Critère | V2 / V3-archetype | V3-WEC |
|---------|-------------------|--------|
| Source de vérité | Benchmarks/constantes "à la main de Dieu" | Données réelles de la partie |
| Champion knowledge | Mapping statique (champion → classe → SF) | Comportement observé en partie |
| Contexte partie | Modifie poids des piliers | Définit complètement WP timeline |
| Évaluation support | Vision/Heal/CC en valeur absolue | Vision/Heal/CC contextualisés + spatiaux |
| Pickoff detection | Approximation temporelle | Vérité spatiale |
| Throw detection | Heuristique post-hoc | Calculé via ΔWP réel |
| Scalability | Chaque patch = update manuel des SF | Indépendant des patches |
| Score signification | "Tu as bien fait ton CS, +10pts" | "Tu as fait gagner +21% WP à ton équipe" |

---

## 12. Sources

- [RiotWatcher MatchApiV5 documentation](https://riot-watcher.readthedocs.io/en/latest/riotwatcher/LeagueOfLegends/MatchApiV5.html)
- [Riot Developer Portal APIs](https://developer.riotgames.com/apis)
- Inspired by Win Probability Added (WPA) in baseball sabermetrics and Expected Goals (xG) in soccer analytics.
