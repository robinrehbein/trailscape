/// Sportwissenschaftlicher Rechenkern für Trainingslast, Fitness/Form,
/// Erholung und Tour-Auswertung.
///
/// Die Datei ist bewusst **UI-frei und ohne Persistenz**: nur reine Funktionen
/// und Wertobjekte. Grundlage ist das Dokument „Trailscape — Sportwissen-
/// schaftliche Berechnungsbasis" (Stand 2026-08-08). Abschnittsnummern in den
/// Kommentaren (§2.1 usw.) verweisen darauf.
///
/// Leitplanken, die überall gelten:
///
///  * **Nie werfen.** Jede Funktion nimmt leere Serien, fehlende Herzfrequenz,
///    Lücken und Ausreißer entgegen und liefert einen „nicht berechenbar"-
///    Zustand (`available == false` plus deutschsprachige Begründung).
///  * **Jede abgeleitete Größe trägt eine [Confidence].** Geschätzte Werte
///    dürfen im UI nie wie Messwerte auftreten (§8.5).
///  * **Eine einzige Lastskala.** Alles mündet in `load` mit der Semantik
///    „1 h an der Schwelle = 100 Punkte" (§2.4), gedeckelt bei [maxLoad].
library;

import 'dart:math' as math;

import 'health_sync.dart' show DailyValue;
import 'models.dart';
import 'stats.dart' show haversineM;

// ---------------------------------------------------------------------------
// Konstanten
// ---------------------------------------------------------------------------

/// Erdbeschleunigung in m/s².
const double gravity = 9.80665;

/// Obergrenze für eine Tourlast (Plausibilität, §2.4).
const double maxLoad = 500;

/// Default-Setup-Masse (Rad + Kleidung + Flaschen + Tasche) in kg (§3.2).
const double defaultSetupMassKg = 12;

/// Default-CdA für Gravel in Hoods-Position in m² (§3.2).
const double defaultCda = 0.38;

/// Default-Rollwiderstandsbeiwert für Gravelreifen auf Mischuntergrund (§3.2).
const double defaultCrr = 0.008;

/// Antriebsstrang-Wirkungsgrad (§3.2).
const double defaultDriveEfficiency = 0.97;

/// Default-Ruhepuls für Freizeitsportler, wenn keine Serie vorliegt (§1.2).
const double defaultRestingHrBpm = 60;

/// LTHR-Default als Anteil der HFmax (§1.3).
const double defaultLthrFactor = 0.89;

/// Zulässiges Plausibilitätsfenster für LTHR relativ zur HFmax (§1.3).
const double lthrMinFactor = 0.80;
const double lthrMaxFactor = 0.95;

/// Grenzen für die geschätzte FTP in Watt (§3.3).
const double minEftpW = 100;
const double maxEftpW = 400;

/// Default-eFTP in W/kg Fahrergewicht, wenn keine harte Tour vorliegt (§3.3).
const double defaultEftpWPerKg = 2.4;

/// Mindestabdeckung der Bewegungszeit mit gültiger Herzfrequenz, damit der
/// HF-Pfad benutzt wird (§3.1, Stufe A).
const double minHrCoverage = 0.80;

/// Ab dieser Lücke gilt die Herzfrequenz eines Segments als unbekannt (§2.1).
const double maxHrGapS = 30;

/// Segmente, die länger dauern, gelten als Aufzeichnungslücke und zählen nicht
/// zur Bewegungszeit. Eigene Schutzregel (das Dokument nennt nur die
/// HF-Lückenregel), damit ein gestoppter Recorder keine Last erzeugt.
const double maxSegmentDtS = 120;

/// Bewegungsschwelle: Geschwindigkeit in m/s bzw. HF-Faktor über Ruhepuls
/// (§2.1, „moving time").
const double movingSpeedMs = 1.0;
const double movingHrRestFactor = 1.15;

/// EWMA-Zeitkonstanten der PMC (§4.2).
final double lambdaCtl = 1 - math.exp(-1 / 42);
final double lambdaAtl = 1 - math.exp(-1 / 7);

/// Antwort der CTL-EWMA über 7 Tage: `1 − (1 − λ_ctl)^7` (§6.3).
final double ctlWeeklyResponse = 1 - math.pow(1 - lambdaCtl, 7).toDouble();

/// Optimalband des Belastungsverhältnisses (Garmin-Konvention, §4.4).
const double loadRatioBandLow = 0.8;
const double loadRatioBandHigh = 1.5;

/// Unterhalb dieser chronischen Wochenlast ist das Verhältnis numerisch
/// instabil und wird unterdrückt (§4.4).
const double minChronicWeeklyLoad = 20;

/// Kalibrierungsfaktor HF↔Physik: gültiges Fenster, sonst auf 1,0 (§3.3).
const double alphaMin = 0.6;
const double alphaMax = 1.6;

/// Default-Faktor der sRPE-Kalibrierung (§3.4).
const double defaultRpeFactor = 1 / 6;

/// HRV (rMSSD): Baselinefenster in Tagen und Breite des Normalbands als
/// Vielfaches der Streuung von `ln(rMSSD)` (Plews & Laursen / HRV4Training).
const int hrvBaselineDays = 28;
const int hrvRollingDays = 7;
const double hrvBandFactor = 0.75;

/// Mindestzahl an HRV-Tagen im Baselinefenster für eine volle Wertung.
const int hrvMinBaselineDays = 14;

/// Mindestzahl an Messungen im 7-Tage-Rollfenster.
const int hrvMinRecentDays = 3;

/// Untergrenze der Streuung von `ln(rMSSD)`. Ohne sie würde eine zufällig sehr
/// ruhige Woche jede Alltagsschwankung zum Ausreißer machen (≈ ±3,8 % Band).
const double hrvMinSigmaLn = 0.05;

/// Plausibilitätsfenster einzelner rMSSD-Tageswerte in ms.
const double hrvMinMs = 5;
const double hrvMaxMs = 300;

/// Gewichte des Readiness-Scores, **sobald HRV vorliegt** (§5.3/§5.4).
///
/// Begründung der Reihenfolge: rMSSD misst den parasympathischen Zustand
/// direkt und ist das Signal, das Garmin, Polar und Whoop am höchsten
/// gewichten; der Ruhepuls beschreibt dieselbe Achse, reagiert aber träger und
/// gröber; Schlaf ist ein *Einflussfaktor* auf Erholung, keine Messung davon;
/// TSB ist ein Modellwert aus geschätzten Lasten und trägt entsprechend am
/// wenigsten. Ohne HRV bleibt die alte Formel aus §5.4 unverändert bestehen.
const double readinessWeightHrv = 0.40;
const double readinessWeightRhr = 0.25;
const double readinessWeightSleep = 0.20;
const double readinessWeightLoad = 0.15;

/// Obergrenzen der Einzel-Strafterme aus §5.4 — Normierungsanker, wenn die
/// Strafterme gewichtet zusammengeführt werden.
const double maxPenaltyRhr = 45;
const double maxPenaltySleep = 45;
const double maxPenaltyLoad = 30;

/// Umrechnung Wochenstunden → Wochenlast (§6.3, Sicherheitsdeckel).
///
/// Hergeleitet aus der eigenen Lastnormierung (`load = Dauer_h × IF² × 100`,
/// §3.3) und der pyramidalen Zielverteilung für Fahrer mit wenig Zeit
/// (75 : 15 : 10, §6.3): LIT ≈ IF 0,70 → 49 Last/h, MIT ≈ IF 0,85 → 72 Last/h,
/// HIT ≈ IF 1,00 → 100 Last/h. Gewichtet ergibt das
/// `0,75 × 49 + 0,15 × 72 + 0,10 × 100 ≈ 58` Last pro tatsächlich gefahrener
/// Stunde. Das Dokument nennt 75 Last/h — das ist das obere GA2-Mittel und
/// damit die optimistische Obergrenze, keine realistische Wochenmischung.
const double weeklyLoadPerHour = 58;

/// Unsicherheitsband der VO2max-Schätzung (§7.3). Für die Uth-Formel nennt das
/// Dokument ±15 %; für die Regression (Methode B) setzen wir ±10 % an — enger,
/// weil individuell gemessene Submaximalpunkte eingehen, aber weiterhin als
/// Band und nie als Einzelzahl.
const double vo2MaxBandRatio = 0.15;
const double vo2MaxBandRegression = 0.10;

// ---------------------------------------------------------------------------
// Kleine Helfer
// ---------------------------------------------------------------------------

double _clamp(double v, double lo, double hi) =>
    v.isNaN ? lo : (v < lo ? lo : (v > hi ? hi : v));

double _medianSorted(List<double> sorted) {
  final n = sorted.length;
  if (n == 0) {
    return double.nan;
  }
  if (n.isOdd) {
    return sorted[n ~/ 2];
  }
  return (sorted[n ~/ 2 - 1] + sorted[n ~/ 2]) / 2;
}

/// Median einer Liste, `null` bei leerer Liste.
double? median(Iterable<double> values) {
  final list = values.where((v) => v.isFinite).toList()..sort();
  if (list.isEmpty) {
    return null;
  }
  return _medianSorted(list);
}

/// Robuste Streuungsschätzung `1.4826 × MAD`, `null` bei leerer Liste (§5.1).
double? madSigma(Iterable<double> values, [double? center]) {
  final list = values.where((v) => v.isFinite).toList();
  if (list.isEmpty) {
    return null;
  }
  final med = center ?? median(list)!;
  final deviations = list.map((v) => (v - med).abs()).toList();
  return 1.4826 * median(deviations)!;
}

DateTime _atMidnight(DateTime d) => DateTime(d.year, d.month, d.day);

DateTime _addDays(DateTime d, int days) =>
    DateTime(d.year, d.month, d.day + days);

int _dayDifference(DateTime a, DateTime b) =>
    _atMidnight(a).difference(_atMidnight(b)).inDays;

/// Zeitgewichteter zentrierter gleitender Mittelwert.
List<double> _centeredMean(List<double> t, List<double> v, double windowS) {
  final n = v.length;
  if (n == 0) {
    return const [];
  }
  final half = windowS / 2;
  final out = List<double>.filled(n, 0);
  var lo = 0;
  var hi = 0;
  var sum = 0.0;
  for (var i = 0; i < n; i++) {
    while (hi < n && t[hi] <= t[i] + half) {
      sum += v[hi];
      hi++;
    }
    while (lo < n && t[lo] < t[i] - half) {
      sum -= v[lo];
      lo++;
    }
    final count = hi - lo;
    out[i] = count > 0 ? sum / count : v[i];
  }
  return out;
}

/// Zentrierter gleitender Median (robust gegen einzelne Höhen-Ausreißer).
List<double> _centeredMedian(List<double> t, List<double> v, double windowS) {
  final n = v.length;
  if (n == 0) {
    return const [];
  }
  final half = windowS / 2;
  final out = List<double>.filled(n, 0);
  var lo = 0;
  var hi = 0;
  for (var i = 0; i < n; i++) {
    while (hi < n && t[hi] <= t[i] + half) {
      hi++;
    }
    while (lo < n && t[lo] < t[i] - half) {
      lo++;
    }
    if (hi > lo) {
      final window = v.sublist(lo, hi)..sort();
      out[i] = _medianSorted(window);
    } else {
      out[i] = v[i];
    }
  }
  return out;
}

/// Nachlaufender, zeitgewichteter gleitender Mittelwert (für NP, §3.3).
List<double> _trailingWeightedMean(
  List<double> t,
  List<double> v,
  List<double> weight,
  double windowS,
) {
  final n = v.length;
  final out = List<double>.filled(n, 0);
  var lo = 0;
  var sumV = 0.0;
  var sumW = 0.0;
  for (var i = 0; i < n; i++) {
    sumV += v[i] * weight[i];
    sumW += weight[i];
    while (lo < i && t[lo] < t[i] - windowS) {
      sumV -= v[lo] * weight[lo];
      sumW -= weight[lo];
      lo++;
    }
    out[i] = sumW > 0 ? sumV / sumW : v[i];
  }
  return out;
}

// ---------------------------------------------------------------------------
// Basistypen
// ---------------------------------------------------------------------------

/// Geschlecht — beeinflusst nur die TRIMP-Koeffizienten (§2.1).
enum Sex { maennlich, weiblich, unbekannt }

/// Verlässlichkeit einer abgeleiteten Größe (§0).
enum Confidence { none, low, medium, high }

/// Woher ein HF-Grundwert stammt (§1.1/§1.3).
enum ValueSource { test, beobachtet, geschaetzt }

const Map<Confidence, String> confidenceLabels = {
  Confidence.none: 'nicht berechenbar',
  Confidence.low: 'grobe Schätzung',
  Confidence.medium: 'Schätzung',
  Confidence.high: 'belastbar',
};

Confidence _downgrade(Confidence c) => switch (c) {
      Confidence.high => Confidence.medium,
      Confidence.medium => Confidence.low,
      Confidence.low => Confidence.low,
      Confidence.none => Confidence.none,
    };

Confidence _minConfidence(Confidence a, Confidence b) =>
    a.index <= b.index ? a : b;

// ---------------------------------------------------------------------------
// 1. Nutzerprofil
// ---------------------------------------------------------------------------

/// Statisches Nutzerprofil für alle Berechnungen.
///
/// Alle abgeleiteten Größen (HFmax, LTHR, Ruhepuls, eFTP) haben einen Default
/// nach Dokument und lassen sich einzeln überschreiben. Ein Feldtestwert
/// gewinnt immer über die Schätzung.
class TrainingProfile {
  const TrainingProfile({
    required this.ageYears,
    this.sex = Sex.unbekannt,
    this.weightKg = 75,
    this.setupMassKg = defaultSetupMassKg,
    this.hrMaxOverride,
    this.lthrOverride,
    this.restingHrOverride,
    this.cda = defaultCda,
    this.crr = defaultCrr,
    this.driveEfficiency = defaultDriveEfficiency,
    this.eftpOverrideW,
    this.weeklyHours,
  });

  final int ageYears;
  final Sex sex;

  /// Fahrergewicht in kg (ohne Rad).
  final double weightKg;

  /// Setup-Masse in kg: Rad, Kleidung, Flaschen, Tasche.
  final double setupMassKg;

  /// Gemessene bzw. beobachtete HFmax in bpm; ohne Angabe gilt Tanaka.
  final double? hrMaxOverride;

  /// Gemessene LTHR in bpm; ohne Angabe [defaultLthrFactor] × HFmax.
  final double? lthrOverride;

  /// Ruhepuls-Baseline in bpm zum Zeitpunkt der Tour; ohne Angabe 60.
  final double? restingHrOverride;

  final double cda;
  final double crr;
  final double driveEfficiency;

  /// Geschätzte FTP in Watt; ohne Angabe [defaultEftpWPerKg] × Gewicht.
  final double? eftpOverrideW;

  /// Zeitbudget fürs Training in Stunden pro Woche; `null` = kein Budget
  /// hinterlegt (dann deckelt nur die Lasthistorie, §6.3).
  final double? weeklyHours;

  /// HFmax nach Tanaka: `208 − 0,7 × Alter` (§1.1). SEE ≈ ±10 bpm.
  double get tanakaHrMax => 208 - 0.7 * ageYears;

  /// Effektive HFmax in bpm.
  double get hrMax {
    final v = hrMaxOverride ?? tanakaHrMax;
    return _clamp(v, 120, 230);
  }

  ValueSource get hrMaxSource =>
      hrMaxOverride != null ? ValueSource.test : ValueSource.geschaetzt;

  /// Effektive Schwellen-HF in bpm, immer im Fenster 0,80–0,95 × HFmax (§1.3).
  double get lthr {
    final raw = lthrOverride ?? defaultLthrFactor * hrMax;
    return _clamp(raw, lthrMinFactor * hrMax, lthrMaxFactor * hrMax);
  }

  ValueSource get lthrSource =>
      lthrOverride != null ? ValueSource.test : ValueSource.geschaetzt;

  /// Effektiver Ruhepuls in bpm.
  double get restingHr {
    final v = restingHrOverride ?? defaultRestingHrBpm;
    return _clamp(v, 30, 100);
  }

  ValueSource get restingHrSource =>
      restingHrOverride != null ? ValueSource.beobachtet : ValueSource.geschaetzt;

  /// Herzfrequenzreserve (HFmax − HFruhe), nie kleiner als 1.
  double get hrReserve => math.max(hrMax - restingHr, 1.0);

  /// Gesamtmasse Fahrer + Setup in kg.
  double get totalMassKg => math.max(weightKg + setupMassKg, 1.0);

  /// Effektive FTP-Schätzung in Watt, geklemmt auf [minEftpW]…[maxEftpW].
  double get eftpW => _clamp(
        eftpOverrideW ?? defaultEftpWPerKg * weightKg,
        minEftpW,
        maxEftpW,
      );

  /// Banister-Koeffizient `a` (§2.1). Ohne Geschlechtsangabe der männliche
  /// Satz — bei hoher Intensität der konservativere.
  double get trimpA => sex == Sex.weiblich ? 0.86 : 0.64;

  /// Banister-Koeffizient `b` (§2.1).
  double get trimpB => sex == Sex.weiblich ? 1.67 : 1.92;

  /// Verlässlichkeit der HF-Ankerwerte. Feldtestwerte heben sie an.
  Confidence get anchorConfidence {
    if (hrMaxSource == ValueSource.test && lthrSource == ValueSource.test) {
      return Confidence.high;
    }
    if (hrMaxSource == ValueSource.test || lthrSource == ValueSource.test) {
      return Confidence.medium;
    }
    return Confidence.low;
  }

  /// Zonenmodell zu diesem Profil.
  TrainingZones get zones =>
      TrainingZones(hrMax: hrMax, lthr: lthr, restingHr: restingHr);

  TrainingProfile copyWith({
    int? ageYears,
    Sex? sex,
    double? weightKg,
    double? setupMassKg,
    double? hrMaxOverride,
    double? lthrOverride,
    double? restingHrOverride,
    double? cda,
    double? crr,
    double? driveEfficiency,
    double? eftpOverrideW,
    double? weeklyHours,
  }) =>
      TrainingProfile(
        ageYears: ageYears ?? this.ageYears,
        sex: sex ?? this.sex,
        weightKg: weightKg ?? this.weightKg,
        setupMassKg: setupMassKg ?? this.setupMassKg,
        hrMaxOverride: hrMaxOverride ?? this.hrMaxOverride,
        lthrOverride: lthrOverride ?? this.lthrOverride,
        restingHrOverride: restingHrOverride ?? this.restingHrOverride,
        cda: cda ?? this.cda,
        crr: crr ?? this.crr,
        driveEfficiency: driveEfficiency ?? this.driveEfficiency,
        eftpOverrideW: eftpOverrideW ?? this.eftpOverrideW,
        weeklyHours: weeklyHours ?? this.weeklyHours,
      );

  Map<String, dynamic> toJson() => {
        'ageYears': ageYears,
        'sex': sex.name,
        'weightKg': weightKg,
        'setupMassKg': setupMassKg,
        if (hrMaxOverride != null) 'hrMaxOverride': hrMaxOverride,
        if (lthrOverride != null) 'lthrOverride': lthrOverride,
        if (restingHrOverride != null) 'restingHrOverride': restingHrOverride,
        'cda': cda,
        'crr': crr,
        'driveEfficiency': driveEfficiency,
        if (eftpOverrideW != null) 'eftpOverrideW': eftpOverrideW,
        if (weeklyHours != null) 'weeklyHours': weeklyHours,
      };

  factory TrainingProfile.fromJson(Map<String, dynamic> json) {
    Sex parseSex(Object? raw) {
      if (raw is String) {
        for (final s in Sex.values) {
          if (s.name == raw) {
            return s;
          }
        }
      }
      return Sex.unbekannt;
    }

    double? optional(String key) => (json[key] as num?)?.toDouble();

    return TrainingProfile(
      ageYears: (json['ageYears'] as num?)?.round() ?? 40,
      sex: parseSex(json['sex']),
      weightKg: optional('weightKg') ?? 75,
      setupMassKg: optional('setupMassKg') ?? defaultSetupMassKg,
      hrMaxOverride: optional('hrMaxOverride'),
      lthrOverride: optional('lthrOverride'),
      restingHrOverride: optional('restingHrOverride'),
      cda: optional('cda') ?? defaultCda,
      crr: optional('crr') ?? defaultCrr,
      driveEfficiency: optional('driveEfficiency') ?? defaultDriveEfficiency,
      eftpOverrideW: optional('eftpOverrideW'),
      // Fehlt in älteren Profilen — dann gilt „kein Zeitbudget hinterlegt".
      weeklyHours: optional('weeklyHours'),
    );
  }
}

// ---------------------------------------------------------------------------
// Zonenmodelle (§1.4)
// ---------------------------------------------------------------------------

const List<String> frielZoneLabels = [
  'Z1 Regeneration',
  'Z2 Grundlage',
  'Z3 Tempo',
  'Z4 Schwelle',
  'Z5 VO2max+',
];

const List<String> luciaZoneLabels = ['LIT', 'MIT', 'HIT'];

const List<String> edwardsZoneLabels = [
  '50–60 % HFmax',
  '60–70 % HFmax',
  '70–80 % HFmax',
  '80–90 % HFmax',
  '90–100 % HFmax',
];

/// Zonengrenzen zu einem Satz HF-Ankerwerten.
///
/// Wird pro Tour als Snapshot mitgeführt (`zones_used`, §1.4), damit eine
/// spätere LTHR-Korrektur die historische Verteilung nicht verschiebt.
class TrainingZones {
  const TrainingZones({
    required this.hrMax,
    required this.lthr,
    required this.restingHr,
  });

  final double hrMax;
  final double lthr;
  final double restingHr;

  /// Untergrenzen der Friel-Zonen Z2…Z5 in bpm.
  List<double> get frielBoundsBpm =>
      [0.81 * lthr, 0.90 * lthr, 0.94 * lthr, 1.00 * lthr];

  /// Index 0…4 der Friel-Zone (§1.4 A).
  int frielZoneIndex(double hr) {
    final bounds = frielBoundsBpm;
    for (var i = 0; i < bounds.length; i++) {
      if (hr < bounds[i]) {
        return i;
      }
    }
    return 4;
  }

  /// Index 0…2 der Lucia-Domäne LIT/MIT/HIT (§1.4 B).
  int luciaZoneIndex(double hr) {
    if (hr < 0.85 * lthr) {
      return 0;
    }
    if (hr <= lthr) {
      return 1;
    }
    return 2;
  }

  /// Index 0…4 der Edwards-Zone (%HFmax) oder `null` unterhalb 50 % HFmax.
  int? edwardsZoneIndex(double hr) {
    final pct = hr / hrMax;
    if (pct < 0.5) {
      return null;
    }
    if (pct < 0.6) {
      return 0;
    }
    if (pct < 0.7) {
      return 1;
    }
    if (pct < 0.8) {
      return 2;
    }
    if (pct < 0.9) {
      return 3;
    }
    return 4;
  }

  /// Karvonen-Zielherzfrequenz für einen HRR-Anteil (§1.4 C, Fallback-Anker).
  double karvonenHr(double fraction) =>
      restingHr + _clamp(fraction, 0, 1) * (hrMax - restingHr);

  Map<String, dynamic> toJson() => {
        'hrMax': hrMax,
        'lthr': lthr,
        'restingHr': restingHr,
      };

  factory TrainingZones.fromJson(Map<String, dynamic> json) => TrainingZones(
        hrMax: (json['hrMax'] as num?)?.toDouble() ?? 185,
        lthr: (json['lthr'] as num?)?.toDouble() ?? 165,
        restingHr: (json['restingHr'] as num?)?.toDouble() ?? 60,
      );
}

/// Zeit je Zone, plus Anteile.
class ZoneDistribution {
  const ZoneDistribution({required this.labels, required this.seconds});

  ZoneDistribution.empty(this.labels)
      : seconds = List<double>.filled(labels.length, 0);

  final List<String> labels;
  final List<double> seconds;

  double get totalSeconds => seconds.fold(0.0, (a, b) => a + b);

  /// Anteile 0…1 je Zone; alles 0, wenn keine Zeit erfasst wurde.
  List<double> get fractions {
    final total = totalSeconds;
    if (total <= 0) {
      return List<double>.filled(seconds.length, 0);
    }
    return seconds.map((s) => s / total).toList();
  }

  double secondsOf(int index) =>
      index >= 0 && index < seconds.length ? seconds[index] : 0;

  /// Gewichtete Summation `Σ (i+1) × t_i` in Minuten — Edwards bzw. Lucia.
  double get weightedMinutes {
    var sum = 0.0;
    for (var i = 0; i < seconds.length; i++) {
      sum += (i + 1) * seconds[i] / 60;
    }
    return sum;
  }

  Map<String, dynamic> toJson() => {
        'labels': labels,
        'seconds': seconds,
      };
}

// ---------------------------------------------------------------------------
// GPS-Vorverarbeitung (§3.2)
// ---------------------------------------------------------------------------

/// Ein aufbereitetes Segment zwischen zwei Trackpunkten.
class RideSegment {
  const RideSegment({
    required this.timeS,
    required this.dtS,
    required this.distanceM,
    required this.speedMs,
    required this.accelMs2,
    required this.gradeTan,
    required this.elevationM,
    required this.deltaElevationM,
    required this.hr,
    required this.moving,
  });

  /// Sekunden seit Tourstart (Ende des Segments).
  final double timeS;
  final double dtS;
  final double distanceM;

  /// Geglättete Geschwindigkeit in m/s.
  final double speedMs;

  /// Beschleunigung in m/s² über ein 3-s-Fenster.
  final double accelMs2;

  /// Steigung als `tan θ = Δh / Δs` (§3.2: `θ = atan(Δh/Δs)`), berechnet über
  /// mindestens 20 m Wegstrecke und geklemmt auf ±25 %.
  final double gradeTan;

  /// Geglättete Höhe am Segmentende in m.
  final double elevationM;

  /// Geglättete Höhendifferenz des Segments in m.
  final double deltaElevationM;

  /// Herzfrequenz in bpm oder `null` (Lücke > [maxHrGapS], kein Sensor).
  final int? hr;

  /// Ob das Segment zur Bewegungszeit zählt (§2.1).
  final bool moving;
}

/// Aufbereitete Tour: geglättete Höhe, Geschwindigkeit, Steigung, HF-Zuordnung.
class RideSeries {
  const RideSeries({
    required this.segments,
    required this.movingTimeS,
    required this.totalTimeS,
    required this.movingTimeWithHrS,
    required this.distanceM,
    required this.ascentM,
    required this.hasElevation,
  });

  const RideSeries.empty()
      : segments = const [],
        movingTimeS = 0,
        totalTimeS = 0,
        movingTimeWithHrS = 0,
        distanceM = 0,
        ascentM = 0,
        hasElevation = false;

  final List<RideSegment> segments;
  final double movingTimeS;
  final double totalTimeS;
  final double movingTimeWithHrS;
  final double distanceM;
  final double ascentM;
  final bool hasElevation;

  bool get isEmpty => segments.isEmpty;

  /// Anteil der Bewegungszeit mit gültiger Herzfrequenz (0…1).
  double get hrCoverage =>
      movingTimeS > 0 ? _clamp(movingTimeWithHrS / movingTimeS, 0, 1) : 0;

  /// Über die Bewegungszeit gewichtete Ø-HF, `null` ohne HF-Daten.
  double? get avgHr {
    var sum = 0.0;
    var weight = 0.0;
    for (final s in segments) {
      if (s.moving && s.hr != null) {
        sum += s.hr! * s.dtS;
        weight += s.dtS;
      }
    }
    return weight > 0 ? sum / weight : null;
  }

  /// Höchste Herzfrequenz während der Bewegungszeit.
  int? get maxHr {
    int? best;
    for (final s in segments) {
      if (s.moving && s.hr != null && (best == null || s.hr! > best)) {
        best = s.hr;
      }
    }
    return best;
  }
}

/// Bereitet eine Trackpunktliste für alle weiteren Berechnungen auf.
///
/// Robust gegen: fehlende Zeitstempel, unsortierte Punkte, fehlende Höhe,
/// GPS-Sprünge (> 25 m/s), Höhenrauschen und Aufzeichnungslücken.
RideSeries buildRideSeries(List<TrackPoint> points, TrainingProfile profile) {
  final timed = points.where((p) => p.time != null).toList()
    ..sort((a, b) => a.time!.compareTo(b.time!));
  if (timed.length < 2) {
    return const RideSeries.empty();
  }

  final n = timed.length;
  final t0 = timed.first.time!;
  final times = List<double>.generate(n, (i) => (timed[i].time! - t0) / 1000);

  // --- Höhe: fehlende Werte linear füllen, dann Median 15 s + Mittel 15 s.
  final rawEle = List<double?>.generate(n, (i) => timed[i].ele);
  final knownCount = rawEle.where((e) => e != null).length;
  final hasElevation = knownCount >= 2;
  final filled = List<double>.filled(n, 0);
  if (hasElevation) {
    var lastIdx = -1;
    for (var i = 0; i < n; i++) {
      if (rawEle[i] != null) {
        if (lastIdx < 0) {
          for (var j = 0; j < i; j++) {
            filled[j] = rawEle[i]!;
          }
        } else {
          final span = times[i] - times[lastIdx];
          for (var j = lastIdx + 1; j < i; j++) {
            final f = span > 0 ? (times[j] - times[lastIdx]) / span : 0.0;
            filled[j] = rawEle[lastIdx]! + f * (rawEle[i]! - rawEle[lastIdx]!);
          }
        }
        filled[i] = rawEle[i]!;
        lastIdx = i;
      }
    }
    if (lastIdx >= 0) {
      for (var j = lastIdx + 1; j < n; j++) {
        filled[j] = rawEle[lastIdx]!;
      }
    }
  }
  final smoothEle = hasElevation
      ? _centeredMean(times, _centeredMedian(times, filled, 15), 15)
      : filled;

  // --- Distanz & Rohgeschwindigkeit, Ausreißer verwerfen.
  final segDist = List<double>.filled(n, 0);
  final segDt = List<double>.filled(n, 0);
  final rawSpeed = List<double>.filled(n, 0);
  for (var i = 1; i < n; i++) {
    final dt = times[i] - times[i - 1];
    final d = haversineM(timed[i - 1], timed[i]);
    segDt[i] = dt;
    segDist[i] = d;
    if (dt > 0) {
      final v = d / dt;
      rawSpeed[i] = v > 25 ? rawSpeed[i - 1] : v;
    } else {
      rawSpeed[i] = rawSpeed[i - 1];
    }
  }
  rawSpeed[0] = n > 1 ? rawSpeed[1] : 0;
  final speed = _centeredMean(times, rawSpeed, 5);

  // --- kumulierte Distanz für die Steigungsberechnung über ≥ 20 m.
  final cum = List<double>.filled(n, 0);
  for (var i = 1; i < n; i++) {
    cum[i] = cum[i - 1] + segDist[i];
  }

  // --- Beschleunigung über 3-s-Fenster.
  final accel = List<double>.filled(n, 0);
  for (var i = 1; i < n; i++) {
    var j = i;
    while (j > 0 && times[i] - times[j] < 3) {
      j--;
    }
    final dt = times[i] - times[j];
    if (dt > 0) {
      final a = (speed[i] - speed[j]) / dt;
      accel[i] = a.abs() > 3 ? 0 : a;
    }
  }

  final restingHr = profile.restingHr;
  final segments = <RideSegment>[];
  var movingTimeS = 0.0;
  var movingWithHrS = 0.0;
  var totalTimeS = 0.0;
  var ascentM = 0.0;
  var ascentReference = hasElevation ? smoothEle[0] : 0.0;

  for (var i = 1; i < n; i++) {
    final dt = segDt[i];
    if (dt <= 0) {
      continue;
    }
    totalTimeS += dt;

    // Steigung über mindestens 20 m Wegstrecke.
    var gradeTan = 0.0;
    if (hasElevation) {
      var j = i;
      while (j > 0 && cum[i] - cum[j] < 20) {
        j--;
      }
      final ds = cum[i] - cum[j];
      if (ds >= 5) {
        gradeTan = _clamp((smoothEle[i] - smoothEle[j]) / ds, -0.25, 0.25);
      }
    }

    // HF-Zuordnung: Lücken > 30 s nicht interpolieren.
    int? hr;
    if (dt <= maxHrGapS) {
      hr = timed[i].hr ?? timed[i - 1].hr;
    } else {
      hr = timed[i].hr;
      if (hr != null && dt > maxSegmentDtS) {
        hr = null;
      }
    }
    if (hr != null && (hr <= 20 || hr > 250)) {
      hr = null;
    }

    final isGap = dt > maxSegmentDtS;
    final moving = !isGap &&
        (speed[i] > movingSpeedMs ||
            (hr != null && hr > movingHrRestFactor * restingHr));

    if (moving) {
      movingTimeS += dt;
      if (hr != null) {
        movingWithHrS += dt;
      }
    }

    if (hasElevation) {
      final diff = smoothEle[i] - ascentReference;
      if (diff.abs() >= 3) {
        if (diff > 0) {
          ascentM += diff;
        }
        ascentReference = smoothEle[i];
      }
    }

    segments.add(RideSegment(
      timeS: times[i],
      dtS: dt,
      distanceM: segDist[i],
      speedMs: speed[i],
      accelMs2: accel[i],
      gradeTan: gradeTan,
      elevationM: hasElevation ? smoothEle[i] : 0,
      deltaElevationM: hasElevation ? smoothEle[i] - smoothEle[i - 1] : 0,
      hr: hr,
      moving: moving,
    ));
  }

  return RideSeries(
    segments: segments,
    movingTimeS: movingTimeS,
    totalTimeS: totalTimeS,
    movingTimeWithHrS: movingWithHrS,
    distanceM: cum[n - 1],
    ascentM: ascentM,
    hasElevation: hasElevation,
  );
}

// ---------------------------------------------------------------------------
// 2. Tourlast aus Herzfrequenz (§2)
// ---------------------------------------------------------------------------

/// Ergebnis der HF-basierten Lastberechnung einer Tour.
class HeartRateLoad {
  const HeartRateLoad({
    required this.available,
    required this.unavailableReason,
    required this.trimpBanister,
    required this.trimpEdwards,
    required this.load,
    required this.hrCoverage,
    required this.movingTimeS,
    required this.avgHr,
    required this.maxHr,
    required this.frielZones,
    required this.luciaZones,
    required this.edwardsZones,
    required this.zonesUsed,
    required this.confidence,
  });

  factory HeartRateLoad.unavailable(String reason, TrainingZones zones) =>
      HeartRateLoad(
        available: false,
        unavailableReason: reason,
        trimpBanister: 0,
        trimpEdwards: 0,
        load: 0,
        hrCoverage: 0,
        movingTimeS: 0,
        avgHr: null,
        maxHr: null,
        frielZones: ZoneDistribution.empty(frielZoneLabels),
        luciaZones: ZoneDistribution.empty(luciaZoneLabels),
        edwardsZones: ZoneDistribution.empty(edwardsZoneLabels),
        zonesUsed: zones,
        confidence: Confidence.none,
      );

  /// Ob die HF-Last als Primärlast taugt (Abdeckung ≥ 80 % der Bewegungszeit).
  final bool available;
  final String? unavailableReason;

  /// Sample-weiser Banister-TRIMP (§2.1).
  final double trimpBanister;

  /// Edwards-Zonen-TRIMP als Sekundärmetrik (§2.2).
  final double trimpEdwards;

  /// Normalisierte Last („hrTSS", 1 h an der Schwelle = 100, §2.4).
  final double load;

  final double hrCoverage;
  final double movingTimeS;
  final double? avgHr;
  final int? maxHr;

  /// Zeit in den 5 Friel-Zonen (LTHR-verankert).
  final ZoneDistribution frielZones;

  /// Zeit in den 3 Lucia-Domänen LIT/MIT/HIT.
  final ZoneDistribution luciaZones;

  /// Zeit in den 5 Edwards-Zonen (%HFmax).
  final ZoneDistribution edwardsZones;

  /// Snapshot der benutzten Zonengrenzen.
  final TrainingZones zonesUsed;

  final Confidence confidence;

  /// Lucia-TRIMP (`1×LIT + 2×MIT + 3×HIT`, nur informativ, §2.3).
  double get trimpLucia => luciaZones.weightedMinutes;

  /// Zeit über der Schwelle in Sekunden — Proxy für „harten Reiz gesetzt".
  double get secondsAboveLthr => luciaZones.secondsOf(2);
}

/// Banister-TRIMP-Beitrag eines einzelnen Samples (§2.1).
double trimpSampleContribution({
  required double hr,
  required double dtS,
  required TrainingProfile profile,
}) {
  if (dtS <= 0) {
    return 0;
  }
  final x = _clamp((hr - profile.restingHr) / profile.hrReserve, 0, 1.05);
  return (dtS / 60) * x * profile.trimpA * math.exp(profile.trimpB * x);
}

/// TRIMP einer Stunde exakt an der Schwelle — Normierungsanker (§2.4).
double trimpReference(TrainingProfile profile) {
  final xRef =
      _clamp((profile.lthr - profile.restingHr) / profile.hrReserve, 0.05, 1.0);
  return 60 * xRef * profile.trimpA * math.exp(profile.trimpB * xRef);
}

/// Rechnet einen Banister-TRIMP auf die 100er-Skala um (§2.4).
double normalizeTrimp(double trimp, TrainingProfile profile) {
  final ref = trimpReference(profile);
  if (ref <= 0) {
    return 0;
  }
  return math.min(100 * trimp / ref, maxLoad);
}

/// HF-basierte Tourlast inklusive Zonenverteilung.
HeartRateLoad computeHeartRateLoad(
  RideSeries series,
  TrainingProfile profile,
) {
  final zones = profile.zones;
  if (series.isEmpty) {
    return HeartRateLoad.unavailable(
      'Keine auswertbaren Trackpunkte mit Zeitstempel.',
      zones,
    );
  }
  if (series.movingTimeS <= 0) {
    return HeartRateLoad.unavailable('Keine Bewegungszeit erkannt.', zones);
  }

  var trimp = 0.0;
  final friel = List<double>.filled(5, 0);
  final lucia = List<double>.filled(3, 0);
  final edwards = List<double>.filled(5, 0);

  for (final s in series.segments) {
    if (!s.moving || s.hr == null) {
      continue;
    }
    final hr = s.hr!.toDouble();
    trimp += trimpSampleContribution(hr: hr, dtS: s.dtS, profile: profile);
    friel[zones.frielZoneIndex(hr)] += s.dtS;
    lucia[zones.luciaZoneIndex(hr)] += s.dtS;
    final e = zones.edwardsZoneIndex(hr);
    if (e != null) {
      edwards[e] += s.dtS;
    }
  }

  final frielZones = ZoneDistribution(labels: frielZoneLabels, seconds: friel);
  final luciaZones = ZoneDistribution(labels: luciaZoneLabels, seconds: lucia);
  final edwardsZones =
      ZoneDistribution(labels: edwardsZoneLabels, seconds: edwards);

  final coverage = series.hrCoverage;
  if (series.movingTimeWithHrS <= 0) {
    return HeartRateLoad.unavailable(
      'Für diese Tour liegt keine Herzfrequenz vor.',
      zones,
    );
  }

  var confidence = coverage >= 0.9 ? Confidence.high : Confidence.medium;
  if (profile.anchorConfidence == Confidence.low) {
    confidence = _downgrade(confidence);
  }
  if (profile.sex == Sex.unbekannt) {
    confidence = _downgrade(confidence);
  }

  final available = coverage >= minHrCoverage;

  return HeartRateLoad(
    available: available,
    unavailableReason: available
        ? null
        : 'Herzfrequenz deckt nur '
            '${(coverage * 100).round()} % der Bewegungszeit ab '
            '(mindestens ${(minHrCoverage * 100).round()} % nötig).',
    trimpBanister: trimp,
    trimpEdwards: edwardsZones.weightedMinutes,
    load: normalizeTrimp(trimp, profile),
    hrCoverage: coverage,
    movingTimeS: series.movingTimeS,
    avgHr: series.avgHr,
    maxHr: series.maxHr,
    frielZones: frielZones,
    luciaZones: luciaZones,
    edwardsZones: edwardsZones,
    zonesUsed: zones,
    confidence: available ? confidence : Confidence.none,
  );
}

// ---------------------------------------------------------------------------
// 3. Physik-Fallback (§3.2/§3.3)
// ---------------------------------------------------------------------------

/// Luftdichte in kg/m³ auf Höhe [elevationM] (§3.2).
double airDensity(double elevationM, {double temperatureK = 288.15}) {
  final t = temperatureK <= 0 ? 288.15 : temperatureK;
  return 1.225 * (288.15 / t) * math.exp(-_clamp(elevationM, -500, 6000) / 8435);
}

/// Geschätzte Fahrerleistung eines Samples in Watt (§3.2).
double estimateSamplePowerW({
  required double speedMs,
  required double accelMs2,
  required double gradeTan,
  required double elevationM,
  required TrainingProfile profile,
}) {
  if (speedMs <= 0) {
    return 0;
  }
  final m = profile.totalMassKg;
  final tan = _clamp(gradeTan, -0.25, 0.25);
  final norm = math.sqrt(1 + tan * tan);
  final sin = tan / norm;
  final cos = 1 / norm;
  final rho = airDensity(elevationM);

  final fGrav = m * gravity * sin;
  final fRoll = profile.crr * m * gravity * cos;
  final fAir = 0.5 * rho * profile.cda * speedMs * speedMs;
  final fAcc = m * accelMs2;

  final pWheel = (fGrav + fRoll + fAir + fAcc) * speedMs;
  final eta = profile.driveEfficiency <= 0 ? 1.0 : profile.driveEfficiency;
  return math.max(0.0, pWheel) / eta;
}

/// Ein Sample der geschätzten Leistungsreihe (nur Bewegungszeit).
class PowerSample {
  const PowerSample({
    required this.timeS,
    required this.dtS,
    required this.powerW,
    required this.speedMs,
    required this.elevationM,
    required this.deltaElevationM,
    required this.hr,
  });

  final double timeS;
  final double dtS;
  final double powerW;
  final double speedMs;
  final double elevationM;
  final double deltaElevationM;
  final int? hr;
}

/// Geschätzte Leistungsreihe einer Tour.
class PowerSeries {
  const PowerSeries({required this.samples, required this.rollingMean30sW});

  const PowerSeries.empty()
      : samples = const [],
        rollingMean30sW = const [];

  final List<PowerSample> samples;

  /// Nachlaufender 30-s-Mittelwert der Leistung — Basis für NP (§3.3).
  final List<double> rollingMean30sW;

  bool get isEmpty => samples.isEmpty;

  double get movingTimeS => samples.fold(0.0, (a, s) => a + s.dtS);

  double get avgPowerW {
    final t = movingTimeS;
    if (t <= 0) {
      return 0;
    }
    return samples.fold(0.0, (a, s) => a + s.powerW * s.dtS) / t;
  }

  /// Normalized Power nach Coggan (§3.3).
  double get normalizedPowerW {
    final t = movingTimeS;
    if (t <= 0) {
      return 0;
    }
    var sum = 0.0;
    for (var i = 0; i < samples.length; i++) {
      final p = rollingMean30sW[i];
      sum += math.pow(p, 4).toDouble() * samples[i].dtS;
    }
    return math.pow(sum / t, 0.25).toDouble();
  }

  double get hrCoverage {
    final t = movingTimeS;
    if (t <= 0) {
      return 0;
    }
    final withHr = samples
        .where((s) => s.hr != null)
        .fold(0.0, (double a, s) => a + s.dtS);
    return _clamp(withHr / t, 0, 1);
  }

  double? get avgHr {
    var sum = 0.0;
    var weight = 0.0;
    for (final s in samples) {
      if (s.hr != null) {
        sum += s.hr! * s.dtS;
        weight += s.dtS;
      }
    }
    return weight > 0 ? sum / weight : null;
  }

  /// Positive Höhendifferenz der Reihe in m.
  double get ascentM {
    var sum = 0.0;
    for (final s in samples) {
      if (s.deltaElevationM > 0) {
        sum += s.deltaElevationM;
      }
    }
    return sum;
  }

  /// Teilreihe über einen Indexbereich, inklusive neu berechnetem 30-s-Mittel.
  PowerSeries slice(int start, int end) {
    if (start < 0 || end > samples.length || start >= end) {
      return const PowerSeries.empty();
    }
    return PowerSeries(
      samples: samples.sublist(start, end),
      rollingMean30sW: rollingMean30sW.sublist(start, end),
    );
  }
}

/// Baut die geschätzte Leistungsreihe aus der aufbereiteten Tour (§3.2).
PowerSeries buildPowerSeries(RideSeries series, TrainingProfile profile) {
  if (series.isEmpty) {
    return const PowerSeries.empty();
  }
  final samples = <PowerSample>[];
  for (final s in series.segments) {
    if (!s.moving) {
      continue;
    }
    samples.add(PowerSample(
      timeS: s.timeS,
      dtS: s.dtS,
      powerW: estimateSamplePowerW(
        speedMs: s.speedMs,
        accelMs2: s.accelMs2,
        gradeTan: s.gradeTan,
        elevationM: s.elevationM,
        profile: profile,
      ),
      speedMs: s.speedMs,
      elevationM: s.elevationM,
      deltaElevationM: s.deltaElevationM,
      hr: s.hr,
    ));
  }
  if (samples.isEmpty) {
    return const PowerSeries.empty();
  }
  final t = samples.map((s) => s.timeS).toList();
  final p = samples.map((s) => s.powerW).toList();
  final w = samples.map((s) => s.dtS).toList();
  return PowerSeries(
    samples: samples,
    rollingMean30sW: _trailingWeightedMean(t, p, w, 30),
  );
}

/// Ergebnis des Physikmodells für eine Tour.
class PhysicsEstimate {
  const PhysicsEstimate({
    required this.available,
    required this.unavailableReason,
    required this.series,
    required this.movingTimeS,
    required this.avgPowerW,
    required this.normalizedPowerW,
    required this.variabilityIndex,
    required this.intensityFactor,
    required this.eTss,
    required this.eftpW,
    required this.kcal,
    required this.confidence,
  });

  factory PhysicsEstimate.unavailable(String reason) => PhysicsEstimate(
        available: false,
        unavailableReason: reason,
        series: const PowerSeries.empty(),
        movingTimeS: 0,
        avgPowerW: 0,
        normalizedPowerW: 0,
        variabilityIndex: 0,
        intensityFactor: 0,
        eTss: 0,
        eftpW: 0,
        kcal: 0,
        confidence: Confidence.none,
      );

  final bool available;
  final String? unavailableReason;
  final PowerSeries series;
  final double movingTimeS;

  /// Geschätzte Ø-Leistung in W. **Modelliert, nicht gemessen** (±15–25 %).
  final double avgPowerW;
  final double normalizedPowerW;

  /// `NP / Ø-Leistung` — > 1,25 sehr stochastisch, < 1,05 sehr gleichmäßig.
  final double variabilityIndex;
  final double intensityFactor;

  /// Last auf der 100er-Skala aus dem Physikmodell (vor α-Kalibrierung).
  final double eTss;
  final double eftpW;
  final double kcal;
  final Confidence confidence;

  /// Textbaustein ohne Overclaim (§8.5).
  String get powerText => available
      ? 'Geschätzte Leistung ≈ ${avgPowerW.round()} W '
          '(aus GPS & Profil, ±15–25 %)'
      : 'Leistung nicht schätzbar';
}

/// Physikbasierte Lastschätzung einer Tour ohne (oder mit) Herzfrequenz.
PhysicsEstimate computePhysicsEstimate(
  RideSeries series,
  TrainingProfile profile, {
  double? eftpW,
}) {
  if (series.isEmpty) {
    return PhysicsEstimate.unavailable(
      'Keine auswertbaren Trackpunkte mit Zeitstempel.',
    );
  }
  if (!series.hasElevation) {
    return PhysicsEstimate.unavailable(
      'Ohne Höhenprofil lässt sich die Leistung nicht schätzen.',
    );
  }
  if (profile.weightKg <= 0) {
    return PhysicsEstimate.unavailable(
      'Ohne Gewichtsangabe lässt sich die Leistung nicht schätzen.',
    );
  }
  final power = buildPowerSeries(series, profile);
  if (power.isEmpty || power.movingTimeS < 60) {
    return PhysicsEstimate.unavailable(
      'Zu wenig Bewegungszeit für eine Leistungsschätzung.',
    );
  }
  if (series.distanceM < 200) {
    return PhysicsEstimate.unavailable(
      'Zu kurze Strecke für eine Leistungsschätzung.',
    );
  }

  final avg = power.avgPowerW;
  final np = power.normalizedPowerW;
  final ftp = _clamp(eftpW ?? profile.eftpW, minEftpW, maxEftpW);
  final ifValue = ftp > 0 ? np / ftp : 0.0;
  final hours = power.movingTimeS / 3600;
  final eTss = math.min(hours * ifValue * ifValue * 100, maxLoad);
  final kcal = avg * power.movingTimeS / (1000 * 0.24);

  // Das Dokument stuft das Physikmodell grundsätzlich als „medium" ein (§3.1).
  // Sehr kurze oder sehr stochastische Fahrten stufen wir zusätzlich ab.
  var confidence = Confidence.medium;
  if (power.movingTimeS < 900 || (avg > 0 && np / avg > 1.3)) {
    confidence = Confidence.low;
  }

  return PhysicsEstimate(
    available: true,
    unavailableReason: null,
    series: power,
    movingTimeS: power.movingTimeS,
    avgPowerW: avg,
    normalizedPowerW: np,
    variabilityIndex: avg > 0 ? np / avg : 0,
    intensityFactor: ifValue,
    eTss: eTss,
    eftpW: ftp,
    kcal: kcal,
    confidence: confidence,
  );
}

/// Bestes nachlaufendes Leistungsmittel über [windowS] Sekunden.
double? bestRollingMeanPowerW(PowerSeries series, {double windowS = 1200}) {
  if (series.isEmpty || series.movingTimeS < windowS) {
    return null;
  }
  final t = series.samples.map((s) => s.timeS).toList();
  final p = series.samples.map((s) => s.powerW).toList();
  final w = series.samples.map((s) => s.dtS).toList();
  final rolling = _trailingWeightedMean(t, p, w, windowS);
  double? best;
  for (var i = 0; i < rolling.length; i++) {
    // Erst werten, wenn das Fenster tatsächlich voll ist.
    if (t[i] - t.first < windowS) {
      continue;
    }
    if (best == null || rolling[i] > best) {
      best = rolling[i];
    }
  }
  return best;
}

/// eFTP aus den Leistungsreihen der letzten 90 Tage (§3.3).
///
/// `0,95 × bestes 20-min-Mittel`, geklemmt auf 100…400 W. Ohne harte Tour
/// bleibt der Profil-Default (2,4 W/kg).
double estimateEftpW(Iterable<PowerSeries> recent, TrainingProfile profile) {
  double? best;
  for (final s in recent) {
    final v = bestRollingMeanPowerW(s);
    if (v != null && (best == null || v > best)) {
      best = v;
    }
  }
  if (best == null) {
    return profile.eftpW;
  }
  return _clamp(0.95 * best, minEftpW, maxEftpW);
}

// ---------------------------------------------------------------------------
// Kalibrierung HF ↔ Physik (§3.3)
// ---------------------------------------------------------------------------

/// Ein Tourenpaar, für das beide Lastpfade berechenbar waren.
class LoadCalibrationSample {
  const LoadCalibrationSample({
    required this.loadHr,
    required this.loadPhysics,
  });

  final double loadHr;
  final double loadPhysics;
}

/// Personenspezifischer Faktor `α = median(load_hr / load_phys)`.
class LoadCalibration {
  const LoadCalibration({
    required this.alpha,
    required this.sampleCount,
    required this.clamped,
    required this.confidence,
  });

  /// Neutraler Default: keine Korrektur.
  const LoadCalibration.neutral()
      : alpha = 1.0,
        sampleCount = 0,
        clamped = false,
        confidence = Confidence.low;

  final double alpha;
  final int sampleCount;

  /// Ob α außerhalb `[0.6, 1.6]` lag und deshalb auf 1,0 gesetzt wurde.
  final bool clamped;
  final Confidence confidence;

  Map<String, dynamic> toJson() => {
        'alpha': alpha,
        'sampleCount': sampleCount,
        'clamped': clamped,
        'confidence': confidence.name,
      };

  factory LoadCalibration.fromJson(Map<String, dynamic> json) {
    Confidence parse(Object? raw) {
      for (final c in Confidence.values) {
        if (c.name == raw) {
          return c;
        }
      }
      return Confidence.low;
    }

    return LoadCalibration(
      alpha: (json['alpha'] as num?)?.toDouble() ?? 1.0,
      sampleCount: (json['sampleCount'] as num?)?.toInt() ?? 0,
      clamped: json['clamped'] == true,
      confidence: parse(json['confidence']),
    );
  }
}

/// Bestimmt α aus Touren, für die HF- und Physiklast vorliegen (§3.3).
///
/// Es zählen die jüngsten [window] Paare (die Liste wird als chronologisch
/// aufsteigend erwartet). Unter [minSamples] Paaren bleibt α = 1,0.
LoadCalibration computeLoadCalibration(
  List<LoadCalibrationSample> samples, {
  int window = 20,
  int minSamples = 5,
}) {
  final usable = samples
      .where((s) =>
          s.loadHr.isFinite &&
          s.loadPhysics.isFinite &&
          s.loadHr > 0 &&
          s.loadPhysics > 0)
      .toList();
  final recent =
      usable.length > window ? usable.sublist(usable.length - window) : usable;
  if (recent.length < minSamples) {
    return LoadCalibration(
      alpha: 1.0,
      sampleCount: recent.length,
      clamped: false,
      confidence: Confidence.low,
    );
  }
  final ratio = median(recent.map((s) => s.loadHr / s.loadPhysics))!;
  if (ratio < alphaMin || ratio > alphaMax) {
    return LoadCalibration(
      alpha: 1.0,
      sampleCount: recent.length,
      clamped: true,
      confidence: Confidence.low,
    );
  }
  return LoadCalibration(
    alpha: ratio,
    sampleCount: recent.length,
    clamped: false,
    confidence: recent.length >= 10 ? Confidence.medium : Confidence.low,
  );
}

// ---------------------------------------------------------------------------
// Fallback-Kaskade zur einheitlichen Last (§3.1)
// ---------------------------------------------------------------------------

/// Woher die Tourlast stammt (§3.1).
enum LoadSource { herzfrequenz, physik, rpe, heuristik, keine }

const Map<LoadSource, String> loadSourceLabels = {
  LoadSource.herzfrequenz: 'aus Herzfrequenz',
  LoadSource.physik: 'aus GPS-Leistungsschätzung',
  LoadSource.rpe: 'aus Anstrengungsempfinden',
  LoadSource.heuristik: 'grob geschätzt aus Distanz und Höhenmetern',
  LoadSource.keine: 'nicht berechenbar',
};

/// Vollständige Lastauswertung einer Tour.
class RideLoad {
  const RideLoad({
    required this.load,
    required this.source,
    required this.confidence,
    required this.heartRate,
    required this.physics,
    required this.note,
  });

  /// Last auf der einheitlichen 100er-Skala (1 h an der Schwelle = 100).
  final double load;
  final LoadSource source;
  final Confidence confidence;
  final HeartRateLoad heartRate;
  final PhysicsEstimate physics;

  /// Deutschsprachiger Hinweis zur Herkunft bzw. zum Fehlen der Last.
  final String note;

  bool get available => source != LoadSource.keine;
}

/// Heuristische Last aus Distanz, Dauer und Höhenmetern (§3.5, letzte Instanz).
double heuristicLoad({
  required double distanceKm,
  required double durationH,
  required double ascentM,
}) {
  if (durationH <= 0) {
    return 0;
  }
  final equivKm = distanceKm + ascentM / 10;
  final base = durationH * 55;
  final factor = _clamp(equivKm / (durationH * 22), 0.7, 1.5);
  return math.min(base * factor, maxLoad);
}

/// Bestimmt die Tourlast über die Fallback-Kaskade A → B → C → D (§3.1).
RideLoad computeRideLoad({
  required List<TrackPoint> points,
  required TrainingProfile profile,
  RideStats? stats,
  LoadCalibration calibration = const LoadCalibration.neutral(),
  double? rpe,
  double rpeFactor = defaultRpeFactor,
  double? eftpW,
}) {
  final series = buildRideSeries(points, profile);
  final hr = computeHeartRateLoad(series, profile);
  final physics = computePhysicsEstimate(series, profile, eftpW: eftpW);

  // Stufe A — Herzfrequenz.
  if (hr.available && hr.load > 0) {
    return RideLoad(
      load: math.min(hr.load, maxLoad),
      source: LoadSource.herzfrequenz,
      confidence: hr.confidence,
      heartRate: hr,
      physics: physics,
      note: 'Last aus der Herzfrequenz berechnet '
          '(${(hr.hrCoverage * 100).round()} % Abdeckung).',
    );
  }

  // Stufe B — Physikmodell.
  if (physics.available && physics.eTss > 0) {
    final alpha = calibration.alpha;
    return RideLoad(
      load: math.min(alpha * physics.eTss, maxLoad),
      source: LoadSource.physik,
      confidence: calibration.clamped
          ? _downgrade(physics.confidence)
          : physics.confidence,
      heartRate: hr,
      physics: physics,
      note: 'Last aus der geschätzten Leistung berechnet '
          '(GPS & Profil, ±15–25 %).',
    );
  }

  // Stufe C — Anstrengungsempfinden.
  final movingMin = series.movingTimeS / 60;
  if (rpe != null && rpe > 0 && movingMin > 0) {
    return RideLoad(
      load: math.min(rpeFactor * movingMin * _clamp(rpe, 0, 10), maxLoad),
      source: LoadSource.rpe,
      confidence: Confidence.low,
      heartRate: hr,
      physics: physics,
      note: 'Last aus deinem Anstrengungsempfinden geschätzt.',
    );
  }

  // Stufe D — reine Distanz/Höhen-Heuristik.
  final distanceKm =
      stats?.distanceKm ?? (series.distanceM > 0 ? series.distanceM / 1000 : 0);
  final durationS = stats?.movingTimeS?.toDouble() ??
      stats?.durationS?.toDouble() ??
      (series.movingTimeS > 0 ? series.movingTimeS : series.totalTimeS);
  final ascentM = stats?.ascentM ?? series.ascentM;
  if (distanceKm > 0 && durationS > 0) {
    return RideLoad(
      load: heuristicLoad(
        distanceKm: distanceKm,
        durationH: durationS / 3600,
        ascentM: ascentM,
      ),
      source: LoadSource.heuristik,
      confidence: Confidence.low,
      heartRate: hr,
      physics: physics,
      note: 'Grobe Schätzung aus Distanz, Dauer und Höhenmetern — '
          'ohne Herzfrequenz oder Höhenprofil nur eine Näherung.',
    );
  }

  return RideLoad(
    load: 0,
    source: LoadSource.keine,
    confidence: Confidence.none,
    heartRate: hr,
    physics: physics,
    note: 'Für diese Tour liegen zu wenige Daten für eine Lastberechnung vor.',
  );
}

/// Bequemlichkeits-Variante von [computeRideLoad] für ein [Ride].
RideLoad computeRideLoadForRide(
  Ride ride,
  TrainingProfile profile, {
  LoadCalibration calibration = const LoadCalibration.neutral(),
  double? rpe,
  double? eftpW,
}) =>
    computeRideLoad(
      points: ride.points,
      profile: profile,
      stats: ride.stats,
      calibration: calibration,
      rpe: rpe,
      eftpW: eftpW,
    );

// ---------------------------------------------------------------------------
// 4. Fitness / Form: CTL, ATL, TSB (§4.2–§4.4)
// ---------------------------------------------------------------------------

/// Tagessumme aller Tourlasten eines Kalendertags.
class DailyLoad {
  DailyLoad({required DateTime day, required this.load})
      : day = _atMidnight(day);

  final DateTime day;
  final double load;
}

/// Ein Tag der Performance-Management-Kurve.
class FitnessPoint {
  const FitnessPoint({
    required this.day,
    required this.load,
    required this.ctl,
    required this.atl,
    required this.tsb,
    required this.rampRate7d,
    required this.loadRatio,
  });

  final DateTime day;
  final double load;

  /// Chronische Last (Fitness), EWMA τ = 42 d.
  final double ctl;

  /// Akute Last (Ermüdung), EWMA τ = 7 d.
  final double atl;

  /// Form: `CTL_{t−1} − ATL_{t−1}` (TrainingPeaks-Konvention).
  final double tsb;

  /// CTL-Punkte pro Woche, `null` in den ersten 7 Tagen.
  final double? rampRate7d;

  /// Entkoppeltes EWMA-Belastungsverhältnis, `null` bei zu kleiner
  /// chronischer Last (§4.4).
  final double? loadRatio;
}

/// Bänder der Form (§4.2).
enum TsbBand { sehrFrisch, formspitze, neutral, produktiv, ueberlastung }

const Map<TsbBand, String> tsbBandLabels = {
  TsbBand.sehrFrisch: 'Sehr ausgeruht',
  TsbBand.formspitze: 'Formspitze',
  TsbBand.neutral: 'Neutral',
  TsbBand.produktiv: 'Produktiver Bereich',
  TsbBand.ueberlastung: 'Sehr hohe Ermüdung',
};

const Map<TsbBand, String> tsbBandMessages = {
  TsbBand.sehrFrisch:
      'Sehr ausgeruht — typischerweise ein guter Zeitpunkt, wieder Reize zu setzen.',
  TsbBand.formspitze:
      'Dein Formwert liegt im Bereich, in dem viele Fahrer gute Leistungen zeigen.',
  TsbBand.neutral: 'Form und Ermüdung halten sich ungefähr die Waage.',
  TsbBand.produktiv:
      'Erwünschte Ermüdung beim Aufbau — viele Fahrer trainieren in diesem Bereich.',
  TsbBand.ueberlastung:
      'Deine Ermüdung ist deutlich höher als deine Fitness. Eine Entlastungswoche ist typischerweise sinnvoll.',
};

TsbBand classifyTsb(double tsb) {
  if (tsb > 25) {
    return TsbBand.sehrFrisch;
  }
  if (tsb >= 5) {
    return TsbBand.formspitze;
  }
  if (tsb >= -10) {
    return TsbBand.neutral;
  }
  if (tsb >= -30) {
    return TsbBand.produktiv;
  }
  return TsbBand.ueberlastung;
}

/// Bänder der CTL-Rampenrate (§4.3).
enum RampBand { formverlust, erhaltung, aufbau, aggressiv, zuSchnell }

const Map<RampBand, String> rampBandLabels = {
  RampBand.formverlust: 'Formverlust / Entlastung',
  RampBand.erhaltung: 'Erhaltung',
  RampBand.aufbau: 'Nachhaltiger Aufbau',
  RampBand.aggressiv: 'Aggressiver Aufbau',
  RampBand.zuSchnell: 'Sehr schneller Aufbau',
};

RampBand classifyRampRate(double ramp) {
  if (ramp < 0) {
    return RampBand.formverlust;
  }
  if (ramp < 3) {
    return RampBand.erhaltung;
  }
  if (ramp <= 6) {
    return RampBand.aufbau;
  }
  if (ramp <= 8) {
    return RampBand.aggressiv;
  }
  return RampBand.zuSchnell;
}

/// Bewertung des Belastungsverhältnisses — bewusst **nicht** als
/// „Verletzungsrisiko" benannt (§4.4).
enum LoadRatioBand { unbekannt, niedrig, imBand, belastungssprung }

const Map<LoadRatioBand, String> loadRatioLabels = {
  LoadRatioBand.unbekannt: 'noch keine Aussage möglich',
  LoadRatioBand.niedrig: 'Belastung zuletzt niedriger als gewohnt',
  LoadRatioBand.imBand: 'Belastung im gewohnten Rahmen',
  LoadRatioBand.belastungssprung: 'Belastungssprung',
};

LoadRatioBand classifyLoadRatio(double? ratio) {
  if (ratio == null || !ratio.isFinite) {
    return LoadRatioBand.unbekannt;
  }
  if (ratio < loadRatioBandLow) {
    return LoadRatioBand.niedrig;
  }
  if (ratio <= loadRatioBandHigh) {
    return LoadRatioBand.imBand;
  }
  return LoadRatioBand.belastungssprung;
}

/// Ergebnis der PMC-Berechnung über eine lückenlose Tagesserie.
class FitnessSeries {
  const FitnessSeries({
    required this.points,
    required this.historyDays,
    required this.seedLoad,
    required this.displayReady,
  });

  const FitnessSeries.empty()
      : points = const [],
        historyDays = 0,
        seedLoad = 0,
        displayReady = false;

  final List<FitnessPoint> points;

  /// Anzahl der abgedeckten Kalendertage.
  final int historyDays;

  /// Startwert für CTL und ATL (Seeding, §4.2).
  final double seedLoad;

  /// Ob die Kurve im UI gezeigt werden darf (≥ 28 Tage Historie).
  final bool displayReady;

  FitnessPoint? get latest => points.isEmpty ? null : points.last;

  /// Verbleibende Tage bis zur Anzeigereife.
  int get daysUntilDisplayReady => math.max(0, 28 - historyDays);

  FitnessPoint? at(DateTime day) {
    final target = _atMidnight(day);
    for (final p in points) {
      if (p.day == target) {
        return p;
      }
    }
    return null;
  }

  /// Die letzten [days] Punkte (höchstens so viele, wie vorhanden sind).
  List<FitnessPoint> lastDays(int days) {
    if (days <= 0 || points.isEmpty) {
      return const [];
    }
    return points.sublist(math.max(0, points.length - days));
  }
}

/// Berechnet CTL/ATL/TSB, Rampenrate und Belastungsverhältnis (§4.2–§4.4).
///
/// [loads] darf Lücken, Dubletten und unsortierte Tage enthalten; es wird auf
/// eine lückenlose Tagesserie normalisiert (Ruhetage = 0).
FitnessSeries computeFitnessSeries(
  List<DailyLoad> loads, {
  DateTime? until,
}) {
  final usable = loads.where((l) => l.load.isFinite && l.load >= 0).toList();
  if (usable.isEmpty) {
    return const FitnessSeries.empty();
  }

  final byDay = <DateTime, double>{};
  for (final l in usable) {
    byDay[l.day] = (byDay[l.day] ?? 0) + l.load;
  }
  final days = byDay.keys.toList()..sort();
  final first = days.first;
  var last = days.last;
  if (until != null) {
    final end = _atMidnight(until);
    if (end.isAfter(last)) {
      last = end;
    }
  }

  final dayList = <DateTime>[];
  var cursor = first;
  while (!cursor.isAfter(last)) {
    dayList.add(cursor);
    cursor = _addDays(cursor, 1);
  }
  final dailyLoads =
      dayList.map((d) => byDay[d] ?? 0.0).toList(growable: false);
  final historyDays = dayList.length;

  // Seeding (§4.2).
  double seed;
  if (historyDays >= 42) {
    seed = dailyLoads.take(42).fold(0.0, (a, b) => a + b) / 42;
  } else if (historyDays >= 14) {
    seed = dailyLoads.fold(0.0, (a, b) => a + b) / historyDays;
  } else {
    seed = 0;
  }

  final lambdaAcute = 2 / (7 + 1);
  final lambdaChronic = 2 / (28 + 1);

  var ctl = seed;
  var atl = seed;
  var ewmaAcute = seed;
  var ewmaChronic = seed;
  final ctlHistory = <double>[];
  final chronicHistory = <double>[];
  final points = <FitnessPoint>[];

  for (var i = 0; i < historyDays; i++) {
    final load = dailyLoads[i];
    final tsb = ctl - atl;
    ctl = ctl + lambdaCtl * (load - ctl);
    atl = atl + lambdaAtl * (load - atl);
    ewmaAcute = load * lambdaAcute + ewmaAcute * (1 - lambdaAcute);
    ewmaChronic = load * lambdaChronic + ewmaChronic * (1 - lambdaChronic);

    ctlHistory.add(ctl);
    chronicHistory.add(ewmaChronic);

    final ramp = i >= 7 ? ctl - ctlHistory[i - 7] : null;

    // Entkoppelt: der chronische Nenner stammt von vor 7 Tagen, damit die
    // akute Last nicht in beiden Termen steckt (§4.4).
    final chronicRef = i >= 7 ? chronicHistory[i - 7] : ewmaChronic;
    double? ratio;
    if (chronicRef * 7 >= minChronicWeeklyLoad && chronicRef > 0) {
      ratio = ewmaAcute / chronicRef;
    }

    points.add(FitnessPoint(
      day: dayList[i],
      load: load,
      ctl: ctl,
      atl: atl,
      tsb: tsb,
      rampRate7d: ramp,
      loadRatio: ratio,
    ));
  }

  return FitnessSeries(
    points: points,
    historyDays: historyDays,
    seedLoad: seed,
    displayReady: historyDays >= 28,
  );
}

/// Fasst Tourlasten zu Tagessummen zusammen.
List<DailyLoad> dailyLoadsFrom(Iterable<({DateTime at, double load})> entries) {
  final byDay = <DateTime, double>{};
  for (final e in entries) {
    if (!e.load.isFinite || e.load < 0) {
      continue;
    }
    final day = _atMidnight(e.at);
    byDay[day] = (byDay[day] ?? 0) + e.load;
  }
  final days = byDay.keys.toList()..sort();
  return days.map((d) => DailyLoad(day: d, load: byDay[d]!)).toList();
}

/// Empfohlene Wochenlast für eine Zielrampe (§6.3).
class WeeklyLoadTarget {
  const WeeklyLoadTarget({
    required this.targetRamp,
    required this.dailyLoad,
    required this.weeklyLoad,
    required this.caps,
    this.weeklyHours,
  });

  final double targetRamp;
  final double dailyLoad;
  final double weeklyLoad;

  /// Welche Sicherheitsdeckel gegriffen haben (deutschsprachig).
  final List<String> caps;

  /// Hinterlegtes Zeitbudget in Stunden pro Woche, falls vorhanden.
  final double? weeklyHours;

  /// Fahrzeit, die dieser Zielwert bei gemischter Woche ungefähr bedeutet
  /// ([weeklyLoadPerHour]).
  double get estimatedHours => weeklyLoad / weeklyLoadPerHour;
}

/// Empfohlene Wochenlast für eine Zielrampe (§6.3).
///
/// [weeklyHours] ist das Zeitbudget aus dem Profil: Mehr als
/// `weeklyHours × weeklyLoadPerHour` ist in der Woche schlicht nicht fahrbar,
/// deshalb deckelt es das Ziel zusätzlich zum 130-%-Deckel.
WeeklyLoadTarget weeklyLoadTarget({
  required double ctl,
  required double targetRamp,
  double? recentWeeklyMean,
  double? weeklyHours,
}) {
  final daily = math.max(ctl + targetRamp / ctlWeeklyResponse, 0.0);
  var weekly = 7 * daily;
  final caps = <String>[];

  if (recentWeeklyMean != null && recentWeeklyMean > 0) {
    final cap = 1.30 * recentWeeklyMean;
    if (weekly > cap) {
      weekly = cap;
      caps.add('Begrenzt auf 130 % deiner letzten vier Wochen.');
    }
  }
  if (weeklyHours != null && weeklyHours > 0) {
    final cap = weeklyHours * weeklyLoadPerHour;
    if (weekly > cap) {
      weekly = cap;
      caps.add(
        'Begrenzt auf dein Zeitbudget von ${formatHours(weeklyHours)} h '
        'pro Woche.',
      );
    }
  }

  return WeeklyLoadTarget(
    targetRamp: targetRamp,
    dailyLoad: weekly / 7,
    weeklyLoad: weekly,
    caps: caps,
    weeklyHours: weeklyHours,
  );
}

/// Stundenangabe im deutschen Format: ganze Zahlen ohne Nachkommastelle,
/// sonst eine Stelle mit Komma („4,5").
String formatHours(double hours) {
  final rounded = (hours * 10).round() / 10;
  if (rounded == rounded.roundToDouble()) {
    return rounded.round().toString();
  }
  return rounded.toStringAsFixed(1).replaceAll('.', ',');
}

/// Ziel-Intensitätsverteilung LIT : MIT : HIT in Prozent (§6.3).
List<double> intensityDistributionTarget({bool polarized = true}) =>
    polarized ? const [80, 5, 15] : const [75, 15, 10];

// ---------------------------------------------------------------------------
// 5. Erholung: Ruhepuls, Schlaf, Readiness (§5)
// ---------------------------------------------------------------------------

/// Ampelstufe eines Erholungssignals.
enum RecoveryFlag { unbekannt, gruen, gelb, orange, rot }

const Map<RecoveryFlag, String> recoveryFlagLabels = {
  RecoveryFlag.unbekannt: 'keine Aussage',
  RecoveryFlag.gruen: 'unauffällig',
  RecoveryFlag.gelb: 'leicht erhöht',
  RecoveryFlag.orange: 'deutlich auffällig',
  RecoveryFlag.rot: 'stark auffällig',
};

bool _atLeast(RecoveryFlag flag, RecoveryFlag min) => flag.index >= min.index;

/// Bewertung der Ruhepuls-Tagesserie (§5.1).
class RestingHrAssessment {
  const RestingHrAssessment({
    required this.available,
    required this.unavailableReason,
    required this.baseline,
    required this.sigma,
    required this.current,
    required this.deltaBpm,
    required this.z,
    required this.flag,
    required this.baselineDays,
    required this.streakDays,
    required this.message,
  });

  factory RestingHrAssessment.unavailable(String reason, int baselineDays) =>
      RestingHrAssessment(
        available: false,
        unavailableReason: reason,
        baseline: null,
        sigma: null,
        current: null,
        deltaBpm: null,
        z: null,
        flag: RecoveryFlag.unbekannt,
        baselineDays: baselineDays,
        streakDays: 0,
        message: reason,
      );

  final bool available;
  final String? unavailableReason;

  /// Median der Tage −60 … −8.
  final double? baseline;

  /// `1.4826 × MAD` derselben Tage.
  final double? sigma;

  /// Median der letzten 3 Tage.
  final double? current;
  final double? deltaBpm;
  final double? z;
  final RecoveryFlag flag;

  /// Anzahl gültiger Werte im Baseline-Fenster (Gate: ≥ 21).
  final int baselineDays;

  /// Wie viele aufeinanderfolgende Messungen bereits auffällig sind.
  final int streakDays;
  final String message;
}

class _RhrDay {
  const _RhrDay(this.day, this.value);
  final DateTime day;
  final double value;
}

List<_RhrDay> _normalizeDaily(List<DailyValue> series,
    {double min = 0, double max = double.infinity}) {
  final byDay = <DateTime, double>{};
  for (final v in series) {
    if (!v.value.isFinite || v.value < min || v.value > max) {
      continue;
    }
    byDay[_atMidnight(v.day)] = v.value;
  }
  final days = byDay.keys.toList()..sort();
  return days.map((d) => _RhrDay(d, byDay[d]!)).toList();
}

/// Bewertet den Ruhepuls gegen die eigene, rollierende Baseline (§5.1).
///
/// [afterHardDay] passt nur die Formulierung an (nach einer harten Tour sind
/// +3–5 bpm normal) — nicht die Stufe.
RestingHrAssessment assessRestingHeartRate(
  List<DailyValue> series, {
  DateTime? today,
  bool afterHardDay = false,
}) {
  final values = _normalizeDaily(series, min: 25, max: 130);
  if (values.isEmpty) {
    return RestingHrAssessment.unavailable(
      'Noch keine Ruhepuls-Werte vorhanden.',
      0,
    );
  }
  final ref = _atMidnight(today ?? values.last.day);

  final baselineValues = values
      .where((v) {
        final diff = _dayDifference(ref, v.day);
        return diff >= 8 && diff <= 60;
      })
      .map((v) => v.value)
      .toList();

  if (baselineValues.length < 21) {
    return RestingHrAssessment.unavailable(
      'Ruhepuls-Baseline wird aufgebaut (${baselineValues.length} von 21 Tagen).',
      baselineValues.length,
    );
  }

  final baseline = median(baselineValues)!;
  final sigma = math.max(madSigma(baselineValues, baseline) ?? 0, 1.5);

  final recent =
      values.where((v) => _dayDifference(ref, v.day) <= 2).toList();
  if (recent.isEmpty) {
    return RestingHrAssessment.unavailable(
      'Kein aktueller Ruhepuls-Wert (letzte 3 Tage).',
      baselineValues.length,
    );
  }
  final current = median(recent.map((v) => v.value))!;
  final delta = current - baseline;
  final z = delta / sigma;

  // Streaks über die tatsächlich vorhandenen Messungen, rückwärts ab heute.
  final descending = values.reversed
      .where((v) => _dayDifference(ref, v.day) >= 0)
      .toList();

  int streak(double minDelta, double? minZ, int maxSpanDays) {
    var count = 0;
    for (final v in descending) {
      if (_dayDifference(ref, v.day) > maxSpanDays) {
        break;
      }
      final d = v.value - baseline;
      final zz = d / sigma;
      if (d >= minDelta && (minZ == null || zz >= minZ)) {
        count++;
      } else {
        break;
      }
    }
    return count;
  }

  final yellowStreak = streak(3, 1.0, 3);
  final redStreak = streak(5, null, 5);

  var flag = RecoveryFlag.gruen;
  if (delta >= 3 && z >= 1.0 && yellowStreak >= 2) {
    flag = RecoveryFlag.gelb;
  }
  if (delta >= 5 && z >= 1.5) {
    flag = RecoveryFlag.orange;
  }
  if (delta >= 8 || redStreak >= 3) {
    flag = RecoveryFlag.rot;
  }

  final rounded = delta.abs() < 0.05 ? '0,0' : delta.abs().toStringAsFixed(1);
  final signed = delta >= 0.05 ? '+$rounded' : (delta <= -0.05 ? '−$rounded' : '±0,0');
  final message = switch (flag) {
    RecoveryFlag.gruen =>
      'Dein Ruhepuls liegt im gewohnten Bereich ($signed bpm gegenüber '
          'deinem Normalwert).',
    RecoveryFlag.gelb => afterHardDay
        ? 'Dein Ruhepuls liegt +$rounded bpm über deinem Normalwert — '
            'nach der gestrigen Belastung erwartbar.'
        : 'Dein Ruhepuls liegt seit mindestens zwei Messungen +$rounded bpm '
            'über deinem Normalwert. Das kann an Training, Schlaf, Stress, '
            'Alkohol, Hitze oder einem beginnenden Infekt liegen.',
    RecoveryFlag.orange =>
      'Dein Ruhepuls liegt deutlich über deinem Normalwert (+$rounded bpm). '
          'Das kann an Training, Schlaf, Stress, Alkohol, Hitze oder einem '
          'Infekt liegen.',
    RecoveryFlag.rot =>
      'Dein Ruhepuls liegt seit mehreren Tagen klar über deinem Normalwert '
          '(+$rounded bpm) — das kann an Training, Schlaf, Stress oder einem '
          'Infekt liegen.',
    RecoveryFlag.unbekannt => 'Keine Aussage möglich.',
  };

  return RestingHrAssessment(
    available: true,
    unavailableReason: null,
    baseline: baseline,
    sigma: sigma,
    current: current,
    deltaBpm: delta,
    z: z,
    flag: flag,
    baselineDays: baselineValues.length,
    streakDays: math.max(yellowStreak, redStreak),
    message: message,
  );
}

// ---------------------------------------------------------------------------
// Herzratenvariabilität (rMSSD)
// ---------------------------------------------------------------------------

/// Lage der HRV gegenüber dem persönlichen Normalband.
enum HrvStatus {
  unbekannt,

  /// Unter dem Band — typisch für Belastung, Stress, Schlafmangel, Infekt.
  niedrig,

  /// Innerhalb des Bands.
  imBand,

  /// Über dem Band, Ruhepuls unauffällig — gutes Zeichen.
  ueberBand,

  /// Über dem Band **bei gleichzeitig erhöhtem Ruhepuls**: mögliche
  /// parasympathische Sättigung, kein Freibrief für harte Reize.
  saettigung,
}

const Map<HrvStatus, String> hrvStatusLabels = {
  HrvStatus.unbekannt: 'keine Aussage',
  HrvStatus.niedrig: 'unter deinem Normalband',
  HrvStatus.imBand: 'im Normalband',
  HrvStatus.ueberBand: 'über deinem Normalband',
  HrvStatus.saettigung: 'über dem Band bei erhöhtem Ruhepuls',
};

/// Bewertung der nächtlichen HRV (rMSSD) gegen die persönliche Baseline.
///
/// Methodik nach Plews & Laursen bzw. HRV4Training: Einzelwerte sind
/// rechtsschief verteilt, deshalb wird `ln(rMSSD)` verwendet; verglichen wird
/// nicht der Tageswert, sondern das [hrvRollingDays]-Tage-Rollmittel gegen ein
/// [hrvBaselineDays]-Tage-Mittel plus Normalband
/// `Baseline ± hrvBandFactor × SD`.
class HrvAssessment {
  const HrvAssessment({
    required this.available,
    required this.unavailableReason,
    required this.baselineLn,
    required this.sigmaLn,
    required this.currentLn,
    required this.lastRmssd,
    required this.z,
    required this.status,
    required this.flag,
    required this.historyDays,
    required this.recentDays,
    required this.message,
  });

  /// Zustand „gar keine HRV übergeben" — Defaultwert von [computeReadiness].
  const HrvAssessment.missing()
      : available = false,
        unavailableReason = 'Noch keine HRV-Werte vorhanden.',
        baselineLn = null,
        sigmaLn = null,
        currentLn = null,
        lastRmssd = null,
        z = null,
        status = HrvStatus.unbekannt,
        flag = RecoveryFlag.unbekannt,
        historyDays = 0,
        recentDays = 0,
        message = 'Noch keine HRV-Werte vorhanden.';

  factory HrvAssessment.unavailable(String reason, int historyDays) =>
      HrvAssessment(
        available: false,
        unavailableReason: reason,
        baselineLn: null,
        sigmaLn: null,
        currentLn: null,
        lastRmssd: null,
        z: null,
        status: HrvStatus.unbekannt,
        flag: RecoveryFlag.unbekannt,
        historyDays: historyDays,
        recentDays: 0,
        message: reason,
      );

  final bool available;
  final String? unavailableReason;

  /// Mittelwert von `ln(rMSSD)` über [hrvBaselineDays] Tage.
  final double? baselineLn;

  /// Streuung derselben Tage, mindestens [hrvMinSigmaLn].
  final double? sigmaLn;

  /// [hrvRollingDays]-Tage-Rollmittel von `ln(rMSSD)`.
  final double? currentLn;

  /// Jüngster Tageswert in ms — nur zur Anzeige, nie zur Bewertung.
  final double? lastRmssd;

  /// `(currentLn − baselineLn) / sigmaLn`; das Normalband endet bei
  /// ±[hrvBandFactor].
  final double? z;
  final HrvStatus status;
  final RecoveryFlag flag;

  /// Gültige Tage im Baselinefenster (Gate: ≥ [hrvMinBaselineDays]).
  final int historyDays;

  /// Gültige Tage im Rollfenster (Gate: ≥ [hrvMinRecentDays]).
  final int recentDays;
  final String message;

  /// Rollmittel in ms (geometrisches Mittel der letzten Tage).
  double? get currentRmssd => currentLn == null ? null : math.exp(currentLn!);

  /// Baseline in ms.
  double? get baselineRmssd => baselineLn == null ? null : math.exp(baselineLn!);

  /// Untere Bandgrenze in ms.
  double? get bandLowRmssd => baselineLn == null
      ? null
      : math.exp(baselineLn! - hrvBandFactor * sigmaLn!);

  /// Obere Bandgrenze in ms.
  double? get bandHighRmssd => baselineLn == null
      ? null
      : math.exp(baselineLn! + hrvBandFactor * sigmaLn!);

  /// Abweichung des Rollmittels von der Baseline in Prozent.
  double? get deviationPercent => currentLn == null || baselineLn == null
      ? null
      : (math.exp(currentLn! - baselineLn!) - 1) * 100;
}

double _mean(Iterable<double> values) {
  var sum = 0.0;
  var n = 0;
  for (final v in values) {
    sum += v;
    n++;
  }
  return n == 0 ? double.nan : sum / n;
}

/// Stichproben-Standardabweichung (n − 1), 0 bei weniger als zwei Werten.
double _stdDev(List<double> values) {
  if (values.length < 2) {
    return 0;
  }
  final m = _mean(values);
  var sum = 0.0;
  for (final v in values) {
    sum += (v - m) * (v - m);
  }
  return math.sqrt(sum / (values.length - 1));
}

/// Bewertet die HRV-Tagesserie (rMSSD in ms) gegen die eigene Baseline.
///
/// [restingHrFlag] wird nur für den Sättigungsfall gebraucht: Ein Wert **über**
/// dem Band ist für sich genommen ein gutes Zeichen — zusammen mit einem
/// erhöhten Ruhepuls ist er aber ein bekanntes Muster bei starker Ermüdung
/// (parasympathische Sättigung) und wird dann als Warnzeichen geführt.
HrvAssessment assessHrv(
  List<DailyValue> series, {
  DateTime? today,
  RecoveryFlag restingHrFlag = RecoveryFlag.unbekannt,
}) {
  final values = _normalizeDaily(series, min: hrvMinMs, max: hrvMaxMs);
  if (values.isEmpty) {
    return HrvAssessment.unavailable('Noch keine HRV-Werte vorhanden.', 0);
  }
  final ref = _atMidnight(today ?? values.last.day);

  final window = values.where((v) {
    final diff = _dayDifference(ref, v.day);
    return diff >= 0 && diff < hrvBaselineDays;
  }).toList();

  if (window.length < hrvMinBaselineDays) {
    final missing = hrvMinBaselineDays - window.length;
    return HrvAssessment.unavailable(
      'Braucht noch $missing ${missing == 1 ? 'Tag' : 'Tage'} HRV-Daten '
      '(${window.length} von $hrvMinBaselineDays).',
      window.length,
    );
  }

  final baselineLn = _mean(window.map((v) => math.log(v.value)));
  final sigmaLn = math.max(
    _stdDev(window.map((v) => math.log(v.value)).toList()),
    hrvMinSigmaLn,
  );

  final recent = window
      .where((v) => _dayDifference(ref, v.day) < hrvRollingDays)
      .toList();
  if (recent.length < hrvMinRecentDays) {
    return HrvAssessment.unavailable(
      'Zu wenige HRV-Messungen in den letzten sieben Tagen '
      '(${recent.length} von $hrvMinRecentDays).',
      window.length,
    );
  }

  final currentLn = _mean(recent.map((v) => math.log(v.value)));
  final z = (currentLn - baselineLn) / sigmaLn;

  final HrvStatus status;
  var flag = RecoveryFlag.gruen;
  if (z <= -hrvBandFactor) {
    status = HrvStatus.niedrig;
    flag = RecoveryFlag.gelb;
    if (z <= -1.5) {
      flag = RecoveryFlag.orange;
    }
    if (z <= -2.5) {
      flag = RecoveryFlag.rot;
    }
  } else if (z >= hrvBandFactor) {
    if (_atLeast(restingHrFlag, RecoveryFlag.gelb)) {
      status = HrvStatus.saettigung;
      flag = RecoveryFlag.orange;
    } else {
      status = HrvStatus.ueberBand;
    }
  } else {
    status = HrvStatus.imBand;
  }

  final current = math.exp(currentLn).round();
  final low = math.exp(baselineLn - hrvBandFactor * sigmaLn).round();
  final high = math.exp(baselineLn + hrvBandFactor * sigmaLn).round();

  final message = switch (status) {
    HrvStatus.niedrig => flag == RecoveryFlag.gelb
        ? 'Deine HRV liegt mit $current ms knapp unter deinem Normalband '
            '($low–$high ms). Das kann an Training, Schlaf, Stress, Alkohol '
            'oder einem beginnenden Infekt liegen.'
        : 'Deine HRV liegt mit $current ms deutlich unter deinem Normalband '
            '($low–$high ms). Das kann an Training, Schlaf, Stress, Alkohol '
            'oder einem Infekt liegen.',
    HrvStatus.imBand =>
      'Deine HRV liegt mit $current ms in deinem Normalband ($low–$high ms).',
    HrvStatus.ueberBand =>
      'Deine HRV liegt mit $current ms über deinem Normalband '
          '($low–$high ms) — dein Nervensystem wirkt gut erholt.',
    HrvStatus.saettigung =>
      'Deine HRV liegt mit $current ms über deinem Normalband '
          '($low–$high ms), gleichzeitig ist dein Ruhepuls erhöht. Diese '
          'Kombination kommt auch bei starker Ermüdung vor — beobachte die '
          'nächsten Tage, bevor du hart trainierst.',
    HrvStatus.unbekannt => 'Keine Aussage möglich.',
  };

  return HrvAssessment(
    available: true,
    unavailableReason: null,
    baselineLn: baselineLn,
    sigmaLn: sigmaLn,
    currentLn: currentLn,
    lastRmssd: window.last.value,
    z: z,
    status: status,
    flag: flag,
    historyDays: window.length,
    recentDays: recent.length,
    message: message,
  );
}

/// Bewertung der Schlafserie gegen die persönliche Baseline (§5.2).
class SleepAssessment {
  const SleepAssessment({
    required this.available,
    required this.unavailableReason,
    required this.baselineH,
    required this.sigmaH,
    required this.lastNightH,
    required this.deviationH,
    required this.z,
    required this.debt7dH,
    required this.flag,
    required this.validNights,
    required this.shortSleeper,
    required this.message,
  });

  factory SleepAssessment.unavailable(String reason, int validNights) =>
      SleepAssessment(
        available: false,
        unavailableReason: reason,
        baselineH: null,
        sigmaH: null,
        lastNightH: null,
        deviationH: null,
        z: null,
        debt7dH: null,
        flag: RecoveryFlag.unbekannt,
        validNights: validNights,
        shortSleeper: false,
        message: reason,
      );

  final bool available;
  final String? unavailableReason;

  /// Persönlicher 28-Tage-Median, geklemmt auf 4,5–9,5 h.
  final double? baselineH;
  final double? sigmaH;
  final double? lastNightH;

  /// Abweichung der letzten Nacht vom eigenen Normalwert in Stunden.
  final double? deviationH;
  final double? z;

  /// Kumuliertes Defizit der letzten 7 Nächte in Stunden (≤ 0).
  final double? debt7dH;
  final RecoveryFlag flag;
  final int validNights;

  /// Baseline < 6,5 h — löst nur einen separaten Info-Hinweis aus, **nie**
  /// eine Drosselung der Tagesempfehlung (§8.3).
  final bool shortSleeper;
  final String message;
}

/// Nicht-blockierender Gesundheitshinweis für chronische Kurzschläfer (§5.2).
const String shortSleeperHint =
    'Dein üblicher Schlaf liegt seit Wochen unter 6,5 Stunden. Für Erwachsene '
    'werden 7–9 Stunden empfohlen, bei viel Training eher mehr — mehr Schlaf '
    'verbessert Regeneration und Leistung. Deine Tagesempfehlung ändert das '
    'nicht.';

/// Ob der Kurzschläfer-Hinweis gezeigt werden darf (höchstens 1×/Monat).
bool shouldShowShortSleeperHint(DateTime? lastShownAt, DateTime now) =>
    lastShownAt == null || _dayDifference(now, lastShownAt) >= 30;

/// Bewertet den Schlaf als Abweichung vom eigenen Normalwert (§5.2).
SleepAssessment assessSleep(
  List<DailyValue> series, {
  DateTime? today,
  RecoveryFlag restingHrFlag = RecoveryFlag.unbekannt,
}) {
  // Sensorartefakte ausschließen: < 2 h und > 14 h zählen nicht.
  final values = _normalizeDaily(series, min: 2, max: 14);
  if (values.isEmpty) {
    return SleepAssessment.unavailable('Noch keine Schlafdaten vorhanden.', 0);
  }
  final ref = _atMidnight(today ?? values.last.day);

  final window = values
      .where((v) {
        final diff = _dayDifference(ref, v.day);
        return diff >= 0 && diff < 28;
      })
      .toList();

  if (window.length < 14) {
    return SleepAssessment.unavailable(
      'Schlaf-Baseline wird aufgebaut (${window.length} von 14 Nächten).',
      window.length,
    );
  }

  final raw = median(window.map((v) => v.value))!;
  final baseline = _clamp(raw, 4.5, 9.5);
  final sigma = math.max(madSigma(window.map((v) => v.value), raw) ?? 0, 0.5);

  final recent = window.where((v) => _dayDifference(ref, v.day) <= 1).toList();
  if (recent.isEmpty) {
    return SleepAssessment.unavailable(
      'Keine aktuelle Schlafmessung vorhanden.',
      window.length,
    );
  }
  final lastNight = recent.last.value;
  final deviation = lastNight - baseline;
  final z = deviation / sigma;

  var debt = 0.0;
  for (final v in window) {
    if (_dayDifference(ref, v.day) < 7) {
      debt += math.min(0.0, v.value - baseline);
    }
  }

  // Die Gelb-Regel ist eine ODER-Bedingung und kann sich mit „grün"
  // (dev ≥ −0,5 h) überlappen. In dem Fall gewinnt die strengere Stufe — das
  // ist die in §8.3 gewollte Wirkung der MAD-Skalierung: ein sehr
  // regelmäßiger Schläfer (σ klein) reagiert empfindlicher als ein
  // schwankender.
  var flag = RecoveryFlag.gruen;
  if (deviation <= -1.0 || z <= -1.0) {
    flag = RecoveryFlag.gelb;
  }
  if (deviation <= -1.5 || debt <= -4) {
    flag = RecoveryFlag.orange;
  }
  if (deviation <= -2.5 && _atLeast(restingHrFlag, RecoveryFlag.gelb)) {
    flag = RecoveryFlag.rot;
  }

  final devText = deviation.abs().toStringAsFixed(1);
  final message = switch (flag) {
    RecoveryFlag.gruen =>
      'Dein Schlaf entspricht deinem Normalwert (${baseline.toStringAsFixed(1)} h).',
    RecoveryFlag.gelb => 'Du hast $devText h weniger geschlafen als sonst.',
    RecoveryFlag.orange =>
      'Dein Schlaf liegt deutlich unter deinem Normalwert '
          '(−$devText h; 7-Tage-Defizit ${debt.toStringAsFixed(1)} h).',
    RecoveryFlag.rot =>
      'Deutlich zu wenig Schlaf (−$devText h) bei gleichzeitig erhöhtem '
          'Ruhepuls.',
    RecoveryFlag.unbekannt => 'Keine Aussage möglich.',
  };

  return SleepAssessment(
    available: true,
    unavailableReason: null,
    baselineH: baseline,
    sigmaH: sigma,
    lastNightH: lastNight,
    deviationH: deviation,
    z: z,
    debt7dH: debt,
    flag: flag,
    validNights: window.length,
    shortSleeper: baseline < 6.5,
    message: message,
  );
}

/// Bänder des Readiness-Scores (§5.4).
enum ReadinessBand { hart, normal, locker, ruhe }

const Map<ReadinessBand, String> readinessBandLabels = {
  ReadinessBand.hart: 'bereit für eine harte Einheit',
  ReadinessBand.normal: 'normales Training',
  ReadinessBand.locker: 'locker / Z2',
  ReadinessBand.ruhe: 'Ruhe oder sehr locker',
};

ReadinessBand classifyReadiness(double score) {
  if (score >= 80) {
    return ReadinessBand.hart;
  }
  if (score >= 60) {
    return ReadinessBand.normal;
  }
  if (score >= 40) {
    return ReadinessBand.locker;
  }
  return ReadinessBand.ruhe;
}

/// Trailscape Readiness Score (§5.4).
class Readiness {
  const Readiness({
    required this.available,
    required this.unavailableReason,
    required this.score,
    required this.band,
    required this.penaltyRhr,
    required this.penaltySleep,
    required this.penaltyLoad,
    required this.restingHr,
    required this.sleep,
    required this.tsb,
    required this.confidence,
    required this.headline,
    required this.detail,
    this.hrv = const HrvAssessment.missing(),
    this.penaltyHrv = 0,
    this.usesHrv = false,
  });

  final bool available;
  final String? unavailableReason;

  /// 0…100. Nur bei [available] aussagekräftig.
  final double score;
  final ReadinessBand band;

  /// Strafterme nach §5.4 — unverändert die Rohwerte, auch wenn sie für den
  /// Score gewichtet zusammengeführt werden.
  final double penaltyRhr;
  final double penaltySleep;
  final double penaltyLoad;

  /// HRV-Strafterm auf der Skala 0…100 (nur gesetzt, wenn [usesHrv]).
  final double penaltyHrv;
  final RestingHrAssessment restingHr;
  final SleepAssessment sleep;
  final HrvAssessment hrv;
  final double? tsb;

  /// Ob HRV in den Score eingeflossen ist (dann gilt die Gewichtung aus
  /// [readinessWeightHrv] & Co., sonst die reine Summenformel aus §5.4).
  final bool usesHrv;
  final Confidence confidence;
  final String headline;
  final String detail;
}

/// Berechnet den Readiness-Score aus HRV, Ruhepuls, Schlaf und Form (§5.4).
///
/// Der Score erscheint nur, wenn alle drei Confidence-Gates halten: ≥ 21
/// Ruhepuls-Werte, ≥ 14 Schlafnächte, ≥ 28 Tage Trainingshistorie. HRV ist
/// **optional**: liegt sie vor, wird sie zum stärksten Einzelsignal
/// ([readinessWeightHrv]); fehlt sie, bleibt die Summenformel aus §5.4
/// unverändert.
Readiness computeReadiness({
  required RestingHrAssessment restingHr,
  required SleepAssessment sleep,
  HrvAssessment hrv = const HrvAssessment.missing(),
  double? tsb,
  int trainingHistoryDays = 0,
}) {
  final penaltyRhr = restingHr.available && restingHr.z != null
      ? _clamp((restingHr.z! - 0.5) * 18, 0, maxPenaltyRhr)
      : 0.0;

  var penaltySleep = 0.0;
  if (sleep.available) {
    if (sleep.z != null) {
      penaltySleep += _clamp((-sleep.z! - 0.5) * 12, 0, 30);
    }
    if (sleep.debt7dH != null) {
      penaltySleep += _clamp((-sleep.debt7dH! - 2) * 4, 0, 15);
    }
  }

  final penaltyLoad =
      tsb != null ? _clamp((-tsb - 20) * 1.2, 0, maxPenaltyLoad) : 0.0;

  // HRV-Strafterm auf der Skala 0…100: greift ab dem unteren Bandrand
  // (z = −0,75) und ist bei z ≈ −2,75 voll ausgereizt. Die parasympathische
  // Sättigung kostet die Hälfte — sie ist ein Warnzeichen, aber ein deutlich
  // unsichereres als ein echter Einbruch.
  final usesHrv = hrv.available && hrv.z != null;
  var penaltyHrv = 0.0;
  if (usesHrv) {
    penaltyHrv = _clamp((-hrv.z! - hrvBandFactor) * 50, 0, 100);
    if (hrv.status == HrvStatus.saettigung) {
      penaltyHrv = math.max(penaltyHrv, 50);
    }
  }

  final double score;
  if (usesHrv) {
    // Alle Strafterme auf 0…100 normieren und gewichtet zusammenführen.
    final weighted = readinessWeightHrv * penaltyHrv +
        readinessWeightRhr * (penaltyRhr / maxPenaltyRhr * 100) +
        readinessWeightSleep * (penaltySleep / maxPenaltySleep * 100) +
        readinessWeightLoad * (penaltyLoad / maxPenaltyLoad * 100);
    score = _clamp(100 - weighted, 0, 100);
  } else {
    score = _clamp(100 - penaltyRhr - penaltySleep - penaltyLoad, 0, 100);
  }
  final band = classifyReadiness(score);

  final missing = <String>[];
  if (!restingHr.available) {
    missing.add('Ruhepuls');
  }
  if (!sleep.available) {
    missing.add('Schlaf');
  }
  if (trainingHistoryDays < 28) {
    missing.add('Trainingshistorie');
  }
  final available = missing.isEmpty;

  return Readiness(
    available: available,
    unavailableReason: available
        ? null
        : 'Noch nicht genug Daten für einen Gesamtwert '
            '(${missing.join(', ')}). Die einzelnen Signale siehst du trotzdem.',
    score: score,
    band: band,
    penaltyRhr: penaltyRhr,
    penaltySleep: penaltySleep,
    penaltyLoad: penaltyLoad,
    penaltyHrv: penaltyHrv,
    restingHr: restingHr,
    sleep: sleep,
    hrv: hrv,
    tsb: tsb,
    usesHrv: usesHrv,
    // Mit HRV steht ein direkt gemessenes Signal des vegetativen Zustands im
    // Score — das trägt weiter als Ruhepuls und Schlaf allein.
    confidence: available
        ? (usesHrv ? Confidence.high : Confidence.medium)
        : Confidence.none,
    headline: available
        ? 'Erholung: ${score.round()} — ${readinessBandLabels[band]}'
        : 'Erholung noch nicht berechenbar',
    detail: available
        ? (usesHrv
            ? 'Basierend auf HRV, Ruhepuls, Schlaf und Trainingslast — '
                'ein Trendindikator, keine Messung.'
            : 'Basierend auf Ruhepuls, Schlaf und Trainingslast (ohne HRV) — '
                'ein Trendindikator, keine Messung.')
        : 'Sobald genug Tage vorliegen, fassen wir Ruhepuls, Schlaf und '
            'Trainingslast zu einem Wert zusammen.',
  );
}

/// Ein Tag der rückwirkend berechneten Readiness-Reihe.
class ReadinessPoint {
  const ReadinessPoint({required this.day, required this.readiness});

  final DateTime day;
  final Readiness readiness;
}

List<DailyValue> _upTo(List<DailyValue> series, DateTime day) {
  final ref = _atMidnight(day);
  return series
      .where((v) => !_atMidnight(v.day).isAfter(ref))
      .toList(growable: false);
}

/// Berechnet die Readiness der letzten [days] Tage rückwirkend.
///
/// Für jeden Tag zählt nur, was **bis dahin** vorlag: Vitalserien werden auf
/// den jeweiligen Stichtag beschnitten, TSB und Historienlänge kommen aus dem
/// passenden Punkt der Fitness-Kurve. Damit lässt sich der Deload-Trigger
/// „Readiness < 40 an ≥ 3 von 7 Tagen" (§6.2) ohne Persistenz auswerten.
///
/// Die Liste ist aufsteigend nach Datum und enthält auch Tage ohne
/// Gesamtscore (dann `readiness.available == false`).
List<ReadinessPoint> computeReadinessSeries({
  List<DailyValue> restingHrSeries = const [],
  List<DailyValue> sleepSeries = const [],
  List<DailyValue> hrvSeries = const [],
  FitnessSeries fitness = const FitnessSeries.empty(),
  DateTime? today,
  int days = 7,
}) {
  if (days <= 0) {
    return const [];
  }
  final ref = _atMidnight(today ?? DateTime.now());
  final points = <ReadinessPoint>[];

  for (var offset = days - 1; offset >= 0; offset--) {
    final day = _addDays(ref, -offset);

    // Stand der Fitness-Kurve an diesem Tag (letzter Punkt bis einschließlich
    // Stichtag) plus die bis dahin abgedeckten Historientage.
    FitnessPoint? point;
    var historyDays = 0;
    for (final p in fitness.points) {
      if (p.day.isAfter(day)) {
        break;
      }
      point = p;
      historyDays++;
    }

    final restingHr = assessRestingHeartRate(
      _upTo(restingHrSeries, day),
      today: day,
    );
    final hrv = assessHrv(
      _upTo(hrvSeries, day),
      today: day,
      restingHrFlag: restingHr.flag,
    );
    final sleep = assessSleep(
      _upTo(sleepSeries, day),
      today: day,
      restingHrFlag: restingHr.flag,
    );

    points.add(ReadinessPoint(
      day: day,
      readiness: computeReadiness(
        restingHr: restingHr,
        sleep: sleep,
        hrv: hrv,
        tsb: point?.tsb,
        trainingHistoryDays: historyDays,
      ),
    ));
  }

  return points;
}

/// Die Scores der Tage, an denen ein Gesamtwert berechenbar war — genau das,
/// was [assessDeload] als `readinessLast7` erwartet.
List<double> availableReadinessScores(Iterable<ReadinessPoint> points) => [
      for (final p in points)
        if (p.readiness.available) p.readiness.score,
    ];

// ---------------------------------------------------------------------------
// 6. Empfehlungen (§6.2/§6.3)
// ---------------------------------------------------------------------------

/// Art der Tagesempfehlung (§6.3).
enum DailyRecommendationKind {
  ruhetag,
  lockerZ2,
  recovery,
  harteEinheit,
  grundlage,
}

/// Konkrete Empfehlung für heute.
class DailyRecommendation {
  const DailyRecommendation({
    required this.kind,
    required this.title,
    required this.detail,
    required this.reasons,
  });

  final DailyRecommendationKind kind;
  final String title;
  final String detail;

  /// Warum diese Empfehlung — nur beschreibend, keine Diagnose.
  final List<String> reasons;
}

/// Tagesempfehlung aus Readiness, Ampeln und Form (§6.3).
DailyRecommendation recommendToday({
  required Readiness readiness,
  double? tsb,
  bool hitBudgetLeft = true,
}) {
  final rhr = readiness.restingHr.flag;
  final sleep = readiness.sleep.flag;
  final hrv = readiness.hrv.available
      ? readiness.hrv.flag
      : RecoveryFlag.unbekannt;
  final reasons = <String>[];
  if (readiness.hrv.available) {
    reasons.add(readiness.hrv.message);
  }
  if (readiness.restingHr.available) {
    reasons.add(readiness.restingHr.message);
  }
  if (readiness.sleep.available) {
    reasons.add(readiness.sleep.message);
  }
  if (tsb != null) {
    reasons.add(tsbBandMessages[classifyTsb(tsb)]!);
  }

  // Ohne Gesamtscore steuern nur die vorhandenen Einzelsignale.
  final score = readiness.available ? readiness.score : null;

  // Die Ampeln greifen zusätzlich zum Score: Ein einzelnes, klar auffälliges
  // Signal soll auch dann durchschlagen, wenn die Gewichtung es im
  // Gesamtwert abfedert.
  if ((score != null && score < 40) ||
      rhr == RecoveryFlag.rot ||
      hrv == RecoveryFlag.rot) {
    return DailyRecommendation(
      kind: DailyRecommendationKind.ruhetag,
      title: 'Heute besser Ruhetag',
      detail: 'Deine Erholungssignale sprechen für Pause statt Training.',
      reasons: reasons,
    );
  }
  if ((score != null && score < 60) ||
      sleep == RecoveryFlag.orange ||
      hrv == RecoveryFlag.orange) {
    return DailyRecommendation(
      kind: DailyRecommendationKind.lockerZ2,
      title: 'Locker in Z2, 60–90 min',
      detail: 'Keine Intervalle — halte die Intensität heute im '
          'Grundlagenbereich.',
      reasons: reasons,
    );
  }
  if (tsb != null && tsb < -25) {
    return DailyRecommendation(
      kind: DailyRecommendationKind.recovery,
      title: 'Regenerationsfahrt in Z1/Z2',
      detail: 'Deine Ermüdung ist gerade hoch — kurz und locker fahren.',
      reasons: reasons,
    );
  }
  if (score != null &&
      score >= 80 &&
      tsb != null &&
      tsb > -20 &&
      hitBudgetLeft &&
      // Eine HRV unter dem Normalband reicht, um den harten Reiz zu vertagen.
      !_atLeast(hrv, RecoveryFlag.gelb)) {
    return DailyRecommendation(
      kind: DailyRecommendationKind.harteEinheit,
      title: 'Harte Einheit möglich (Z4/Z5)',
      detail: 'Erholung und Form passen — heute darf ein harter Reiz rein.',
      reasons: reasons,
    );
  }
  return DailyRecommendation(
    kind: DailyRecommendationKind.grundlage,
    title: 'Grundlageneinheit',
    detail: 'Fahre nach dem Restbudget deiner Woche, überwiegend Z2.',
    reasons: reasons,
  );
}

/// Empfehlung für eine Entlastungswoche (§6.2).
class DeloadRecommendation {
  const DeloadRecommendation({
    required this.recommended,
    required this.triggers,
    required this.warnings,
    required this.title,
    required this.detail,
  });

  final bool recommended;

  /// Ausgelöste Deload-Trigger (deutschsprachig).
  final List<String> triggers;

  /// Weiche Hinweise (z. B. Wochenlastsprung), die keinen Deload auslösen.
  final List<String> warnings;
  final String title;
  final String detail;

  /// Empfohlene Volumenreduktion (Anteil), Intensität bleibt erhalten.
  double get volumeReductionLow => 0.40;
  double get volumeReductionHigh => 0.50;
}

/// Prüft die Deload-Trigger aus §6.2 / §8.2.
///
/// [readinessLast7] sind die Readiness-Scores der letzten sieben Tage
/// (Reihenfolge egal, nur vorhandene Werte übergeben).
DeloadRecommendation assessDeload(
  FitnessSeries series, {
  List<double> readinessLast7 = const [],
  double? weeklyLoad,
  double? fourWeekMeanWeeklyLoad,
}) {
  final triggers = <String>[];
  final warnings = <String>[];

  final tail = series.lastDays(3);
  if (tail.length == 3 && tail.every((p) => p.tsb < -30)) {
    triggers.add('Dein Formwert liegt seit drei Tagen sehr tief.');
  }

  final points = series.points;
  final rampDays = [points.length - 1, points.length - 8, points.length - 15];
  if (rampDays.every((i) => i >= 0)) {
    final ramps = rampDays.map((i) => points[i].rampRate7d).toList();
    if (ramps.every((r) => r != null && r > 8)) {
      triggers.add('Deine Fitness ist seit drei Wochen sehr schnell gestiegen.');
    }
  }

  final lowReadiness = readinessLast7.where((r) => r < 40).length;
  if (lowReadiness >= 3) {
    triggers.add(
      'Deine Erholung lag an $lowReadiness von sieben Tagen im unteren Bereich.',
    );
  }

  if (weeklyLoad != null &&
      fourWeekMeanWeeklyLoad != null &&
      fourWeekMeanWeeklyLoad > 0 &&
      weeklyLoad > 1.3 * fourWeekMeanWeeklyLoad) {
    warnings.add('Deine Belastung ist diese Woche deutlich gestiegen.');
  }

  final latest = series.latest;
  if (latest?.loadRatio != null &&
      classifyLoadRatio(latest!.loadRatio) == LoadRatioBand.belastungssprung) {
    warnings.add(
      'Deine akute Belastung liegt klar über deinem gewohnten Niveau.',
    );
  }

  final recommended = triggers.isNotEmpty;
  return DeloadRecommendation(
    recommended: recommended,
    triggers: triggers,
    warnings: warnings,
    title: recommended ? 'Entlastungswoche empfohlen' : 'Kein Deload nötig',
    detail: recommended
        ? 'Nimm das Wochenvolumen um 40–50 % zurück und behalte die Intensität '
            'bei — kurze harte Reize dürfen drinbleiben.'
        : 'Deine Belastung sieht aktuell tragfähig aus.',
  );
}

// ---------------------------------------------------------------------------
// 7. Tour-Auswertung: Entkopplung und VO2max (§7.2/§7.3)
// ---------------------------------------------------------------------------

/// Pe:Hr-Entkopplung einer Tour (§7.2).
class DecouplingResult {
  const DecouplingResult({
    required this.available,
    required this.unavailableReason,
    required this.efFirst,
    required this.efSecond,
    required this.decouplingPercent,
    required this.variabilityIndex,
    required this.rating,
    required this.confidence,
  });

  factory DecouplingResult.unavailable(String reason) => DecouplingResult(
        available: false,
        unavailableReason: reason,
        efFirst: null,
        efSecond: null,
        decouplingPercent: null,
        variabilityIndex: null,
        rating: null,
        confidence: Confidence.none,
      );

  final bool available;
  final String? unavailableReason;

  /// Efficiency Factor der ersten Hälfte (geschätzte NP pro bpm).
  final double? efFirst;
  final double? efSecond;

  /// `(EF1 − EF2) / EF1 × 100`.
  final double? decouplingPercent;
  final double? variabilityIndex;

  /// Einordnung nach Friel/TrainingPeaks.
  final String? rating;
  final Confidence confidence;
}

String _decouplingRating(double pct) {
  if (pct < 5) {
    return 'gute aerobe Ausdauer';
  }
  if (pct <= 10) {
    return 'aerobe Ausdauer im Aufbau';
  }
  return 'mehr Grundlagenarbeit sinnvoll';
}

/// Berechnet die Pe:Hr-Entkopplung — **nur**, wenn alle Gates halten (§7.2).
DecouplingResult computeDecoupling(
  PhysicsEstimate physics,
  TrainingProfile profile,
) {
  if (!physics.available) {
    return DecouplingResult.unavailable(
      physics.unavailableReason ?? 'Kein Leistungsmodell verfügbar.',
    );
  }
  final series = physics.series;
  if (series.movingTimeS < 3600) {
    return DecouplingResult.unavailable(
      'Für die Entkopplung braucht es mindestens 60 Minuten Bewegungszeit.',
    );
  }
  if (series.hrCoverage < 0.90) {
    return DecouplingResult.unavailable(
      'Für die Entkopplung braucht es Herzfrequenz auf mindestens 90 % der Fahrt.',
    );
  }
  final avgHr = series.avgHr;
  if (avgHr == null || avgHr <= 0) {
    return DecouplingResult.unavailable('Keine Herzfrequenz vorhanden.');
  }
  final relative = avgHr / profile.lthr;
  if (relative < 0.70 || relative > 0.95) {
    return DecouplingResult.unavailable(
      'Die Tour lag nicht im gleichmäßig-aeroben Bereich — '
      'die Entkopplung wäre nicht aussagekräftig.',
    );
  }
  if (physics.variabilityIndex > 1.15) {
    return DecouplingResult.unavailable(
      'Die Fahrt war zu ungleichmäßig für eine Entkopplungs-Analyse.',
    );
  }

  // Hälften nach Bewegungszeit teilen (Pausen sind bereits ausgeschlossen).
  final half = series.movingTimeS / 2;
  var acc = 0.0;
  var splitIndex = 0;
  for (var i = 0; i < series.samples.length; i++) {
    acc += series.samples[i].dtS;
    if (acc >= half) {
      splitIndex = i + 1;
      break;
    }
  }
  if (splitIndex <= 0 || splitIndex >= series.samples.length) {
    return DecouplingResult.unavailable(
      'Die Tour lässt sich nicht in zwei vergleichbare Hälften teilen.',
    );
  }

  final firstHalf = series.slice(0, splitIndex);
  final secondHalf = series.slice(splitIndex, series.samples.length);

  final gain1 = firstHalf.ascentM;
  final gain2 = secondHalf.ascentM;
  final maxGain = math.max(gain1, gain2);
  if (maxGain > 0 && (gain1 - gain2).abs() / maxGain > 0.35) {
    return DecouplingResult.unavailable(
      'Die beiden Tourhälften unterscheiden sich zu stark im Höhenprofil.',
    );
  }

  final hr1 = firstHalf.avgHr;
  final hr2 = secondHalf.avgHr;
  final np1 = firstHalf.normalizedPowerW;
  final np2 = secondHalf.normalizedPowerW;
  if (hr1 == null || hr2 == null || hr1 <= 0 || hr2 <= 0 || np1 <= 0) {
    return DecouplingResult.unavailable(
      'Für eine der Tourhälften fehlen auswertbare Werte.',
    );
  }

  final ef1 = np1 / hr1;
  final ef2 = np2 / hr2;
  final pct = (ef1 - ef2) / ef1 * 100;

  return DecouplingResult(
    available: true,
    unavailableReason: null,
    efFirst: ef1,
    efSecond: ef2,
    decouplingPercent: pct,
    variabilityIndex: physics.variabilityIndex,
    rating: _decouplingRating(pct),
    // Die Leistung ist geschätzt — mehr als „medium" ist nicht seriös.
    confidence: _minConfidence(Confidence.medium, physics.confidence),
  );
}

/// Rollierender Median der letzten fünf qualifizierenden Entkopplungswerte
/// — Einzelwerte schwanken zu stark (§7.2).
double? decouplingTrend(List<double> qualifyingValues) {
  if (qualifyingValues.isEmpty) {
    return null;
  }
  final recent = qualifyingValues.length > 5
      ? qualifyingValues.sublist(qualifyingValues.length - 5)
      : qualifyingValues;
  return median(recent);
}

/// Ein stabiles Submaximal-Segment für die VO2max-Regression (§7.3 B).
class SteadySegment {
  const SteadySegment({
    required this.avgPowerW,
    required this.avgHr,
    required this.durationS,
  });

  final double avgPowerW;
  final double avgHr;
  final double durationS;
}

/// Extrahiert stabile Segmente (≥ 5 min, HF-Drift < 1 bpm/min, VI ≤ 1,1,
/// HF zwischen 60 % und 90 % HFmax) aus einer Leistungsreihe (§7.3 B).
List<SteadySegment> extractSteadySegments(
  PowerSeries series,
  TrainingProfile profile, {
  double minDurationS = 300,
}) {
  final result = <SteadySegment>[];
  if (series.isEmpty) {
    return result;
  }
  final samples = series.samples;
  var start = 0;
  while (start < samples.length) {
    var end = start;
    var duration = 0.0;
    while (end < samples.length && duration < minDurationS) {
      if (samples[end].hr == null) {
        break;
      }
      duration += samples[end].dtS;
      end++;
    }
    if (duration < minDurationS || end <= start) {
      start = end + 1;
      continue;
    }

    final block = samples.sublist(start, end);
    final hrValues = block.map((s) => s.hr!.toDouble()).toList();
    final avgHr = hrValues.reduce((a, b) => a + b) / hrValues.length;
    final drift = (hrValues.last - hrValues.first).abs() / (duration / 60);
    final avgP =
        block.fold(0.0, (double a, s) => a + s.powerW * s.dtS) / duration;
    final slice = series.slice(start, end);
    final vi = avgP > 0 ? slice.normalizedPowerW / avgP : double.infinity;

    final inHrWindow =
        avgHr >= 0.60 * profile.hrMax && avgHr <= 0.90 * profile.hrMax;
    if (drift < 1.0 && vi <= 1.1 && inHrWindow && avgP > 0) {
      result.add(SteadySegment(
        avgPowerW: avgP,
        avgHr: avgHr,
        durationS: duration,
      ));
    }
    start = end;
  }
  return result;
}

/// Welche VO2max-Methode zum Ergebnis geführt hat (§7.3).
enum Vo2MaxMethod { keine, uthRatio, regression, plattform }

/// VO2max-Schätzung mit Unsicherheitsband (§7.3).
class Vo2MaxEstimate {
  const Vo2MaxEstimate({
    required this.available,
    required this.unavailableReason,
    required this.value,
    required this.lower,
    required this.upper,
    required this.method,
    required this.r2,
    required this.segmentCount,
    required this.hrSpanBpm,
    required this.confidence,
  });

  factory Vo2MaxEstimate.unavailable(String reason) => Vo2MaxEstimate(
        available: false,
        unavailableReason: reason,
        value: null,
        lower: null,
        upper: null,
        method: Vo2MaxMethod.keine,
        r2: null,
        segmentCount: 0,
        hrSpanBpm: null,
        confidence: Confidence.none,
      );

  final bool available;
  final String? unavailableReason;

  /// Punktschätzung in ml·kg⁻¹·min⁻¹ — im UI **nie allein** zeigen.
  final double? value;
  final double? lower;
  final double? upper;
  final Vo2MaxMethod method;
  final double? r2;
  final int segmentCount;
  final double? hrSpanBpm;
  final Confidence confidence;

  /// Formulierung gemäß §8.5: immer als Band.
  String get text => available
      ? 'VO2max geschätzt: ${lower!.round()}–${upper!.round()} ml/kg/min'
      : (unavailableReason ?? 'VO2max nicht schätzbar');
}

Vo2MaxEstimate _bandedEstimate(
  double value,
  double band,
  Vo2MaxMethod method,
  Confidence confidence, {
  double? r2,
  int segmentCount = 0,
  double? hrSpan,
}) {
  final v = _clamp(value, 15, 90);
  return Vo2MaxEstimate(
    available: true,
    unavailableReason: null,
    value: v,
    lower: v * (1 - band),
    upper: v * (1 + band),
    method: method,
    r2: r2,
    segmentCount: segmentCount,
    hrSpanBpm: hrSpan,
    confidence: confidence,
  );
}

/// VO2max nach Uth-Sørensen-Overgaard-Pedersen: `15,3 × HFmax / HFruhe` (§7.3 A).
Vo2MaxEstimate estimateVo2MaxFromHrRatio(TrainingProfile profile) {
  if (profile.restingHr <= 0) {
    return Vo2MaxEstimate.unavailable('Ohne Ruhepuls nicht schätzbar.');
  }
  return _bandedEstimate(
    15.3 * profile.hrMax / profile.restingHr,
    vo2MaxBandRatio,
    Vo2MaxMethod.uthRatio,
    Confidence.low,
  );
}

/// VO2max aus submaximalen Segmenten (ACSM-Regression, §7.3 B).
///
/// Gates: ≥ 6 Segmente, r² ≥ 0,80, HF-Spanne ≥ 25 bpm. Fällt eines davon,
/// liefert die Funktion einen „nicht berechenbar"-Zustand — der Aufrufer
/// weicht dann auf [estimateVo2MaxFromHrRatio] aus.
Vo2MaxEstimate estimateVo2MaxFromSegments(
  List<SteadySegment> segments,
  TrainingProfile profile,
) {
  if (profile.weightKg <= 0) {
    return Vo2MaxEstimate.unavailable('Ohne Gewichtsangabe nicht schätzbar.');
  }
  final usable = segments
      .where((s) =>
          s.avgHr > 0 &&
          s.avgPowerW >= 50 &&
          s.avgPowerW <= 200 &&
          s.avgPowerW.isFinite)
      .toList();
  if (usable.length < 6) {
    return Vo2MaxEstimate.unavailable(
      'Zu wenige gleichmäßige Abschnitte (${usable.length} von 6).',
    );
  }

  final hrs = usable.map((s) => s.avgHr).toList();
  final span = hrs.reduce(math.max) - hrs.reduce(math.min);
  if (span < 25) {
    return Vo2MaxEstimate.unavailable(
      'Die Herzfrequenz-Spanne der Abschnitte ist zu klein.',
    );
  }

  // ACSM-Beinergometrie: VO2 = (10,8 × W) / kg + 7.
  final vo2 =
      usable.map((s) => 10.8 * s.avgPowerW / profile.weightKg + 7).toList();

  final n = usable.length;
  final meanHr = hrs.reduce((a, b) => a + b) / n;
  final meanVo2 = vo2.reduce((a, b) => a + b) / n;
  var sxx = 0.0;
  var sxy = 0.0;
  var syy = 0.0;
  for (var i = 0; i < n; i++) {
    final dx = hrs[i] - meanHr;
    final dy = vo2[i] - meanVo2;
    sxx += dx * dx;
    sxy += dx * dy;
    syy += dy * dy;
  }
  if (sxx <= 0 || syy <= 0) {
    return Vo2MaxEstimate.unavailable('Regression nicht bestimmbar.');
  }
  final slope = sxy / sxx;
  final intercept = meanVo2 - slope * meanHr;
  final r2 = (sxy * sxy) / (sxx * syy);

  if (r2 < 0.80) {
    return Vo2MaxEstimate.unavailable(
      'Der Zusammenhang zwischen Herzfrequenz und Leistung ist zu unscharf '
      '(r² = ${r2.toStringAsFixed(2)}).',
    );
  }
  if (slope <= 0) {
    return Vo2MaxEstimate.unavailable('Regression nicht plausibel.');
  }

  return _bandedEstimate(
    slope * profile.hrMax + intercept,
    vo2MaxBandRegression,
    Vo2MaxMethod.regression,
    Confidence.medium,
    r2: r2,
    segmentCount: n,
    hrSpan: span,
  );
}

/// Wählt die beste verfügbare VO2max-Quelle: Plattform > Regression > Uth.
Vo2MaxEstimate estimateVo2Max({
  required TrainingProfile profile,
  List<SteadySegment> segments = const [],
  double? platformValue,
}) {
  if (platformValue != null && platformValue > 0) {
    return _bandedEstimate(
      platformValue,
      vo2MaxBandRegression,
      Vo2MaxMethod.plattform,
      Confidence.medium,
    );
  }
  final regression = estimateVo2MaxFromSegments(segments, profile);
  if (regression.available) {
    return regression;
  }
  return estimateVo2MaxFromHrRatio(profile);
}

/// Rollierender 28-Tage-Median der VO2max-Punktwerte (§7.3, Edge Case).
double? vo2MaxRollingMedian(List<DailyValue> values, {DateTime? today}) {
  if (values.isEmpty) {
    return null;
  }
  final ref = _atMidnight(today ?? values.last.day);
  final window = values
      .where((v) {
        final diff = _dayDifference(ref, v.day);
        return diff >= 0 && diff < 28 && v.value > 0;
      })
      .map((v) => v.value);
  return median(window);
}

/// Ob eine VO2max-Änderung kommuniziert werden sollte (≥ 2 ml/kg/min).
bool vo2MaxChangeWorthShowing(double? previous, double? current) {
  if (previous == null || current == null) {
    return current != null;
  }
  return (current - previous).abs() >= 2;
}
