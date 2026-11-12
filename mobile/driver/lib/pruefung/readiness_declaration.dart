/// UI-09, WP-M3-19 / LH-6.21-CHK-010: Selbstauskunft „Einsatzbereitschaft“ als reine Ja/Nein-Bestaetigung.
/// Es gibt bewusst kein Textfeld, keinen Grund und keine Notiz; der Body enthaelt nur `step_code`, `answer` und `answered_at`.
/// Die Meldung bei fehlender Qualifikation ist neutral und nennt weder Person noch Qualifikation (der Server liefert nur den Code `QUALIFICATION_MISSING`).
enum ReadinessAnswer { ja, nein }

class ReadinessDeclaration {
  const ReadinessDeclaration({required this.stepCode, required this.answer, required this.answeredAt});

  final String stepCode;
  final ReadinessAnswer answer;
  final DateTime answeredAt;

  /// Antwortobjekt fuer `POST /v1/inspections/{id}/submit`: genau drei Felder, kein Freitext.
  Map<String, Object?> toJson() => <String, Object?>{
        'step_code': stepCode,
        'answer': answer == ReadinessAnswer.ja ? 'JA' : 'NEIN',
        'answered_at': answeredAt.toUtc().toIso8601String(),
      };
}

/// Neutraler Meldungsschluessel (siehe `Strings`) je Grund einer ausbleibenden Freigabe; unbekannte Codes fallen auf die neutrale Sperrmeldung zurueck.
String readinessMessageKey({required ReadinessAnswer? answer, required List<String> missingRequirementCodes}) {
  if (answer == ReadinessAnswer.nein) return 'inspection.readiness.notReady';
  return 'inspection.qualificationMissing';
}
