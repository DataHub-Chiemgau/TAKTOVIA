/// UI-09, WP-M3-09 / LH-6.21-CHK-001: „Fahrzeugfreigabe: Prüfung erforderlich“ und WP-M3-16 / LH-6.21-CHK-004: Sperranzeige.
/// Die App zeigt „freigegeben“ erst nach der Serverantwort; Freigabe und Prüfergebnis entscheidet allein der Server (der Client liefert beides nie).
/// Fehlende Voraussetzungen kommen als Codes (Vertrag `MissingRequirement`, `GET /v1/driver/shifts/{id}/run`); der Text nennt weder Person noch Qualifikation.
class MissingRequirement {
  const MissingRequirement({required this.code, this.inspectionId, this.stepCodes = const <String>[]});

  factory MissingRequirement.fromJson(Map<String, Object?> j) => MissingRequirement(
        code: j['code']! as String,
        inspectionId: j['inspection_id'] as String?,
        stepCodes: ((j['step_codes'] as List<Object?>?) ?? const <Object?>[]).cast<String>(),
      );

  final String code;
  final String? inspectionId;

  /// Offene Pflichtschritte der Prüfung (nur bei `INSPECTION_OPEN`).
  final List<String> stepCodes;
}

/// Anzeigetext je Code; unbekannte Codes fallen auf die neutrale Meldung zurück (kein Rateversuch über Diagnose).
String requirementText(MissingRequirement m) {
  switch (m.code) {
    case 'NO_INSPECTION':
      return 'Die Vor-Schicht-Prüfung fehlt.';
    case 'INSPECTION_OPEN':
      return m.stepCodes.isEmpty ? 'Die Prüfung ist nicht abgeschlossen.' : 'Die Prüfung ist nicht abgeschlossen; offen: ${m.stepCodes.join(', ')}.';
    case 'INSPECTION_EXPIRED':
      return 'Die Freigabe ist abgelaufen; eine neue Prüfung ist nötig.';
    case 'SAFETY_REVISION_CHANGED':
      return 'Der Sicherheitsstand des Fahrzeugs hat sich geändert; eine neue Prüfung ist nötig.';
    case 'RELEASE_REVOKED':
      return 'Die Freigabe wurde widerrufen.';
    case 'DEFECT_BLOCK':
    case 'OPERATIONAL_BLOCK':
      return 'Das Fahrzeug ist gesperrt; bitte die Leitstelle kontaktieren.';
    case 'VEHICLE_CHANGED':
      return 'Die Schicht passt nicht zum Fahrzeug.';
    case 'QUALIFICATION_MISSING':
    default:
      return 'Die Einsatzfreigabe ist nicht möglich; bitte die Leitstelle kontaktieren.';
  }
}

/// Freigabestand der Schicht aus `GET /v1/driver/shifts/{id}/run` (`release_state`, `missing_requirements`).
class ReleaseGateView {
  const ReleaseGateView({required this.releaseState, required this.missing});

  factory ReleaseGateView.fromRunJson(Map<String, Object?> j) => ReleaseGateView(
        releaseState: j['release_state']! as String,
        missing: ((j['missing_requirements'] as List<Object?>?) ?? const <Object?>[]).map((Object? e) => MissingRequirement.fromJson(e! as Map<String, Object?>)).toList(),
      );

  final String releaseState;
  final List<MissingRequirement> missing;

  /// Aufträge sind nur nach der Serverfreigabe ausführbar; vorher zeigt die App Dienst, Fahrzeug und diese fehlenden Voraussetzungen.
  bool get ordersExecutable => releaseState == 'RELEASED' && missing.isEmpty;

  /// „Verfügbar schalten“ ist nur mit Serverfreigabe angeboten; der Server weist eine Bereitmeldung ohne Freigabe trotzdem ab.
  bool get mayReportReady => ordersExecutable;

  List<String> get texts => missing.map(requirementText).toList();
}

/// Sperre nach kritischem Mangel (Antwort von `POST /v1/inspections/{id}/submit` mit Ergebnis `BLOCKED`): Sperrart, Vorgangsnummer und Kontakt zur Leitstelle.
/// Der Fahrer hat keine Aktion zum Aufheben (Aufheben: berechtigte Fachrolle, WP-M3-51).
class BlockNotice {
  const BlockNotice({required this.kind, required this.caseNumber, this.dispatchContact});

  factory BlockNotice.fromJson(Map<String, Object?> j) => BlockNotice(
        kind: j['block_kind']! as String,
        caseNumber: j['case_number']! as String,
        dispatchContact: j['dispatch_contact'] as String?,
      );

  /// `VEHICLE` (Fahrzeugsperre) oder `SERVICE` (nur diese Schicht).
  final String kind;
  final String caseNumber;
  final String? dispatchContact;

  /// Text mit Vorgangsnummer und Kontakt; die Kennzeichnung steht im Text, nie nur in der Farbe (Screenreader).
  String get text => (kind == 'SERVICE' ? 'Dienst gesperrt' : 'Fahrzeug gesperrt') +
      ' · kritischer Mangel · Vorgang $caseNumber' +
      (dispatchContact == null ? '. Bitte die Leitstelle kontaktieren.' : '. Leitstelle: $dispatchContact.');
}

/// Kennzeichnung eines sicherheitskritischen Prüfelements für Screenreader und ohne Farbe.
String criticalLabel(String title, {required bool safetyCritical}) => safetyCritical ? 'Sicherheitskritisch: $title' : title;

/// UI-09, WP-M3-12 / LH-6.21-CHK-007: Antwortmöglichkeiten bei Ausstattungsprüfpunkten (z. B. „Rollstuhlrampe funktionsfähig?“): Ja / Nein – Mangel melden / Nicht prüfbar.
/// „Nicht prüfbar“ gilt nie als Ja; der Server leitet daraus das Ergebnis „eingeschränkt“ ab (Fahrten ohne Rollstuhl bleiben möglich), „Nicht anwendbar“ gibt es bei diesen Punkten nicht.
class EquipmentAnswerOption {
  const EquipmentAnswerOption(this.label, this.answer, {this.requiresNote = false});

  final String label;

  /// Antwortwert für `POST /v1/inspections/{id}/submit` (`OK`, `DEFECT`, `NOT_CHECKABLE`).
  final String answer;
  final bool requiresNote;
}

const List<EquipmentAnswerOption> kEquipmentAnswerOptions = <EquipmentAnswerOption>[
  EquipmentAnswerOption('Ja', 'OK'),
  EquipmentAnswerOption('Nein – Mangel melden', 'DEFECT', requiresNote: true),
  EquipmentAnswerOption('Nicht prüfbar', 'NOT_CHECKABLE'),
];
