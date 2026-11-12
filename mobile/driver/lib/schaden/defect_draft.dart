/// Geführte Schadenerfassung der Fahrer-App (WP-M3-44, LH-6.22-DMG-004, UI-10): Entwurfsmodell, Pflichtangaben, Schrittfolge und Anzeigezustände.
/// NICHT AUSGEFÜHRT: Flutter ist in der Erstellungsumgebung nicht verfügbar (siehe README); die serverseitige Gegenprobe der Pflichtangaben und des Musterfalls
/// laufen im Backend (`DefectEvidenceUploadTest`, `DefectEvidenceApiTest`).
library;

/// Kataloge wie im Detailvertrag `docs/umsetzungsplan/vertraege/m3-schadenakte.md` (Planungsannahmen).
const List<String> kDefectKinds = <String>['SCRATCH', 'DENT', 'CRACK_BREAK', 'MALFUNCTION', 'MISSING_PART', 'LEAK', 'WEAR', 'CONTAMINATION', 'OTHER'];
const List<String> kDefectAreas = <String>[
  'FRONT', 'REAR', 'LEFT_SIDE', 'RIGHT_SIDE', 'ROOF', 'UNDERBODY', 'WHEELS_TIRES', 'INTERIOR_PASSENGER', 'INTERIOR_DRIVER', 'LUGGAGE', 'WHEELCHAIR_AREA', 'ENGINE_BATTERY', 'OTHER',
];
const List<String> kDefectEffects = <String>['NONE_VISIBLE', 'COSMETIC_ONLY', 'FUNCTION_LIMITED', 'NOT_ROADWORTHY', 'FLUID_LEAK', 'WARNING_LIGHT', 'NOISE_VIBRATION', 'OTHER'];

/// Obergrenze der Schritte für den Musterfall (Art, Bereich, Auswirkung, ein Foto; Senden ist der letzte Schritt). Vorschlag des Vertrags DMG-004, nicht aus dem Pflichtenheft.
const int kMaxDefectCaptureSteps = 5;

/// Begrenzter Freitext (nur notwendige Angaben); der Server erlaubt 2000 Zeichen, die App weniger.
const int kMaxDefectNoteLength = 300;

/// Schritte der geführten Erfassung in fester Reihenfolge.
enum CaptureStep { kind, area, effect, photo, send }

/// Anzeige des Entwurfs: lokal (Entwurf), an den Server übertragen (synchronisiert), vom Server angenommen (Fallnummer vergeben). Drei getrennte Anzeigen (Kap. 06 §6.7),
/// nie nur über Farbe: Symbol und Text gehören dazu.
enum DraftStatus { draft, synced, accepted }

/// Kategorie der Akte aus dem Fahrzeugbereich (Planungsannahme des Detailvertrags).
String categoryForArea(String area) => switch (area) {
      'WHEELS_TIRES' => 'TIRES_WHEELS',
      'WHEELCHAIR_AREA' => 'ACCESSIBILITY_EQUIPMENT',
      'ENGINE_BATTERY' => 'ELECTRICAL_HV',
      'UNDERBODY' => 'DRIVETRAIN',
      'INTERIOR_PASSENGER' || 'INTERIOR_DRIVER' || 'LUGGAGE' => 'INTERIOR',
      'OTHER' => 'OTHER',
      _ => 'BODY',
    };

class DefectDraft {
  DefectDraft({required this.vehicleId, required this.deviceEventId, DateTime? occurredAtUtc, this.kind, this.vehicleArea, this.observedEffect, this.note = '', List<String>? photoTicketIds})
      : occurredAtUtc = occurredAtUtc ?? DateTime.now().toUtc(),
        photoTicketIds = photoTicketIds ?? <String>[];

  final String vehicleId;

  /// Stabil je Meldung: Wiederholung nach Zeitüberschreitung oder Offline-Synchronisation erzeugt keine zweite Akte (Server: `device_event_id`).
  final String deviceEventId;
  final DateTime occurredAtUtc;
  String? kind;
  String? vehicleArea;
  String? observedEffect;
  String note;

  /// Ids der abgeschlossenen Uploadtickets (Fotos); ein Foto in Prüfung zählt, der Server entscheidet über die Freigabe.
  final List<String> photoTicketIds;
  DraftStatus status = DraftStatus.draft;
  String? caseNumber;

  /// Fehlende Pflichtangaben als JSON-Pointer wie in der Serverantwort 422 (`violations[].pointer`).
  List<String> get missingPointers => <String>[
        if (kind == null) '/kind',
        if (vehicleArea == null) '/vehicle_area',
        if (observedEffect == null) '/observed_effect',
        if (photoTicketIds.isEmpty) '/evidence_ids',
      ];

  bool get isComplete => missingPointers.isEmpty && note.length <= kMaxDefectNoteLength;

  /// Nächster offener Schritt; `send`, wenn alles vorliegt.
  CaptureStep get nextStep {
    if (kind == null) return CaptureStep.kind;
    if (vehicleArea == null) return CaptureStep.area;
    if (observedEffect == null) return CaptureStep.effect;
    if (photoTicketIds.isEmpty) return CaptureStep.photo;
    return CaptureStep.send;
  }

  /// Anzahl Schritte, die der Fahrer bis zum Senden durchläuft (ein Schritt je Pflichtangabe plus Senden).
  static int get musterfallSteps => CaptureStep.values.length;

  /// Rumpf von `POST /v1/defects` (geführte Erfassung). Mandant, Meldender, Zeit und Fallnummer setzt der Server. Ohne Freitext fasst die App die Auswahl zusammen.
  Map<String, Object?> toReportBody() {
    if (!isComplete) throw StateError('Pflichtangaben fehlen: ${missingPointers.join(', ')}');
    final String text = note.trim().isEmpty ? 'Schaden: $kind, $vehicleArea, $observedEffect' : note.trim();
    return <String, Object?>{
      'vehicle_id': vehicleId,
      'occurred_at': occurredAtUtc.toIso8601String(),
      'category_code': categoryForArea(vehicleArea!),
      'description': text,
      'kind': kind,
      'vehicle_area': vehicleArea,
      'observed_effect': observedEffect,
      'evidence_ids': List<String>.unmodifiable(photoTicketIds),
      'device_event_id': deviceEventId,
      'capture_mode': 'GUIDED',
    };
  }

  /// Anzeigetext des Zustands (Screenreader und ohne Farbe bedienbar).
  String statusLabel() => switch (status) {
        DraftStatus.draft => 'Entwurf (nur auf diesem Gerät)',
        DraftStatus.synced => 'Synchronisiert (an den Server übertragen)',
        DraftStatus.accepted => 'Angenommen${caseNumber == null ? '' : ' (Fallnummer $caseNumber)'}',
      };
}
