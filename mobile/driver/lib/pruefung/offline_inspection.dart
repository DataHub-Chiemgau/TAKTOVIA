import '../offline/models.dart';
import '../offline/offline_queue.dart';
import '../offline/ports.dart';
import 'inspection_start.dart';
import 'known_defects.dart';
import 'release_gate.dart';

/// Offline-Pruefung (WP-M3-33, Kap. 01 §1.5): Pruefabschluss und Schadenmeldung werden lokal erfasst und spaeter ueber die Outbox uebertragen.
/// Eine Einsatzfreigabe oder Sperraufhebung entsteht dabei **nie** ohne Serverbestaetigung (PH-AT-020); die App zeigt „freigegeben“ erst nach der Serverantwort.
/// Nichts in dieser Datei wurde ausgefuehrt (kein Flutter in der Umgebung).

/// Outbox-Typ des lokal abgeschlossenen Pruefablaufs.
const String kInspectionSubmitEventType = 'inspectionSubmit';

/// Die fuer eine Offline-Pruefung noetigen Daten, nach dem letzten Online-Kontakt lokal abgelegt: Vorlagenrevision und Liste der Altschaeden (AK 5).
class OfflineInspectionBundle {
  const OfflineInspectionBundle({required this.vehicleId, required this.templateRevisionId, required this.steps, required this.knownDefects, required this.cachedAtUtc});

  final String vehicleId;
  final String templateRevisionId;
  final List<InspectionStep> steps;

  /// `null` = Altschadenliste nie geladen (etwas anderes als die ausdrueckliche leere Liste).
  final KnownDefectList? knownDefects;
  final DateTime cachedAtUtc;
}

abstract interface class OfflineInspectionBundleStore {
  Future<void> save(OfflineInspectionBundle bundle);
  Future<OfflineInspectionBundle?> load(String vehicleId);
}

class InMemoryOfflineInspectionBundleStore implements OfflineInspectionBundleStore {
  final Map<String, OfflineInspectionBundle> _m = <String, OfflineInspectionBundle>{};
  @override
  Future<void> save(OfflineInspectionBundle bundle) async => _m[bundle.vehicleId] = bundle;
  @override
  Future<OfflineInspectionBundle?> load(String vehicleId) async => _m[vehicleId];
}

/// Warum offline keine Pruefung begonnen werden kann; die Texte stehen in `Strings` (nie Freitext).
enum OfflineStartBlock { noTemplate, noKnownDefects }

class OfflineStartDecision {
  const OfflineStartDecision.allowed(this.bundle) : block = null;
  const OfflineStartDecision.blocked(this.block) : bundle = null;
  final OfflineInspectionBundle? bundle;
  final OfflineStartBlock? block;
  bool get allowed => bundle != null;

  /// Schluessel in `Strings`; die App sagt offen, warum die Pruefung nicht beginnt (AK 5).
  String? get textKey => switch (block) {
        OfflineStartBlock.noTemplate => 'inspection.offline.noTemplate',
        OfflineStartBlock.noKnownDefects => 'inspection.offline.noKnownDefects',
        null => null,
      };
}

class OfflineInspectionGate {
  const OfflineInspectionGate(this.store);
  final OfflineInspectionBundleStore store;

  /// Offline beginnt eine Pruefung nur mit lokal vorhandener Vorlagenrevision **und** geladener Altschadenliste des Fahrzeugs; sonst bleibt der Start gesperrt.
  Future<OfflineStartDecision> evaluate(String vehicleId) async {
    final OfflineInspectionBundle? b = await store.load(vehicleId);
    if (b == null || b.steps.isEmpty) return const OfflineStartDecision.blocked(OfflineStartBlock.noTemplate);
    if (b.knownDefects == null) return const OfflineStartDecision.blocked(OfflineStartBlock.noKnownDefects);
    return OfflineStartDecision.allowed(b);
  }
}

/// Anzeigestand einer Pruefung mit drei getrennten Zustaenden (UI-09, AK 1): „lokal abgeschlossen“, „synchronisiert“, „freigegeben“.
enum OfflineInspectionStatus {
  /// Lokal gespeichert, noch nicht (bestaetigt) am Server. Es besteht **keine** Freigabe: „Freigabe ausstehend“.
  localCompleted,

  /// Der Server hat den Abschluss angenommen (APPLIED/DUPLICATE); eine Freigabe ist damit nicht gesagt.
  synced,

  /// Nur mit Serverstand `RELEASED` ohne fehlende Voraussetzung.
  released,

  /// Der Server hat den Abschluss nicht uebernommen (Klaerfall, z. B. andere Vorlagenrevision) oder die lokale Integritaetspruefung schlug fehl.
  needsReview,
}

OfflineInspectionStatus offlineInspectionStatus(QueuedEvent e, {ReleaseGateView? serverGate}) {
  switch (e.state) {
    case EventState.pending:
    case EventState.inFlight:
      return OfflineInspectionStatus.localCompleted;
    case EventState.synced:
      return serverGate != null && serverGate.ordersExecutable ? OfflineInspectionStatus.released : OfflineInspectionStatus.synced;
    case EventState.reviewRequired:
    case EventState.quarantined:
      return OfflineInspectionStatus.needsReview;
  }
}

/// Schluessel in `Strings` je Zustand; Text und Symbol, nie nur Farbe.
String offlineInspectionStatusKey(OfflineInspectionStatus s) => switch (s) {
      OfflineInspectionStatus.localCompleted => 'inspection.status.local',
      OfflineInspectionStatus.synced => 'inspection.status.synced',
      OfflineInspectionStatus.released => 'inspection.status.released',
      OfflineInspectionStatus.needsReview => 'inspection.status.review',
    };

/// Ergebnis des lokalen Abschlusses: bei einem kritischen „Nein“ bekommt der Fahrer sofort die Weisung, nicht zu fahren und die Leitstelle zu kontaktieren (AK 6).
/// Die lokale Bewertung ist nur eine Anzeige; die serverseitige Sperre entsteht beim Sync.
class OfflineCompletion {
  const OfflineCompletion({required this.event, required this.criticalDefectStepCodes});
  final QueuedEvent event;
  final List<String> criticalDefectStepCodes;
  bool get doNotDrive => criticalDefectStepCodes.isNotEmpty;
  String get instructionKey => doNotDrive ? 'inspection.offline.criticalNo' : 'inspection.offline.releasePending';
}

class OfflineInspectionAnswer {
  const OfflineInspectionAnswer({required this.stepCode, required this.answer, this.note, required this.answeredAtUtc});
  final String stepCode;

  /// `OK`, `DEFECT`, `NA` (wie beim Server).
  final String answer;
  final String? note;
  final DateTime answeredAtUtc;

  Map<String, Object?> toJson() => <String, Object?>{
        'step_code': stepCode,
        'answer': answer,
        if (note != null) 'note': note,
        'answered_at': answeredAtUtc.toUtc().toIso8601String(),
      };
}

class OfflineCompletionRejected implements Exception {
  const OfflineCompletionRejected(this.reason);
  final String reason;
  @override
  String toString() => 'OfflineCompletionRejected: $reason';
}

class OfflineInspectionRecorder {
  const OfflineInspectionRecorder({required this.queue, required this.gate});
  final OfflineQueue queue;
  final OfflineInspectionGate gate;

  /// Schliesst die Pruefung lokal ab und reiht sie in die Outbox ein. Pflichtschritte muessen beantwortet sein; Freigabe oder Sperre entstehen hier nie.
  Future<OfflineCompletion> complete({
    required String shiftId,
    required String vehicleId,
    String kind = 'PRE_SHIFT',
    required DateTime startedAtUtc,
    required List<OfflineInspectionAnswer> answers,
  }) async {
    final OfflineStartDecision d = await gate.evaluate(vehicleId);
    if (!d.allowed) throw OfflineCompletionRejected(d.textKey!);
    final OfflineInspectionBundle b = d.bundle!;
    final Set<String> answered = answers.map((OfflineInspectionAnswer a) => a.stepCode).toSet();
    final List<InspectionStep> missing = b.steps.where((InspectionStep s) => s.mandatory && !answered.contains(s.code)).toList();
    if (missing.isNotEmpty) throw OfflineCompletionRejected('missing:${missing.map((InspectionStep s) => s.code).join(',')}');
    final Set<String> known = b.steps.map((InspectionStep s) => s.code).toSet();
    for (final OfflineInspectionAnswer a in answers) {
      if (!known.contains(a.stepCode)) throw OfflineCompletionRejected('unknown:${a.stepCode}');
      if (a.answer == 'DEFECT' && (a.note == null || a.note!.trim().isEmpty)) throw OfflineCompletionRejected('note:${a.stepCode}');
    }
    final Set<String> critical = b.steps.where((InspectionStep s) => s.safetyCritical).map((InspectionStep s) => s.code).toSet();
    final List<String> criticalNo = answers.where((OfflineInspectionAnswer a) => a.answer == 'DEFECT' && critical.contains(a.stepCode)).map((OfflineInspectionAnswer a) => a.stepCode).toList();
    final QueuedEvent e = await queue.record(type: kInspectionSubmitEventType, payload: <String, Object?>{
      'shift_id': shiftId,
      'vehicle_id': vehicleId,
      'kind': kind,
      'template_revision_id': b.templateRevisionId,
      'client_started_at': startedAtUtc.toUtc().toIso8601String(),
      'answers': answers.map((OfflineInspectionAnswer a) => a.toJson()).toList(),
    });
    return OfflineCompletion(event: e, criticalDefectStepCodes: criticalNo);
  }
}

// ------------------------------------------------------------------------------------------------ Sync

class InspectionRemoteRun {
  const InspectionRemoteRun({required this.id, required this.revision});
  final String id;
  final int revision;
}

/// Ergebnis des Abschlusses am Server: [released] nur, wenn der Server eine Freigabe erteilt hat; [missing] sind die Codes fehlender Voraussetzungen.
class InspectionRemoteResult {
  const InspectionRemoteResult({required this.status, this.released = false, this.missing = const <String>[], required this.receivedAtUtc, required this.revision});
  final int status;
  final bool released;
  final List<String> missing;
  final DateTime receivedAtUtc;
  final int revision;
}

/// Gegenstelle der Pruefung (`POST /v1/inspections`, `POST /v1/inspections/{id}/submit`). Wirft [NetworkUnavailableException] bei Verbindungsfehlern.
abstract interface class InspectionRemote {
  /// Startet (idempotent ueber [idempotencyKey]) den Lauf nachtraeglich; `client_started_at` ist die Geraetezeit.
  Future<InspectionRemoteRun> start({required String shiftId, required String vehicleId, required String kind, required DateTime clientStartedAtUtc, required String idempotencyKey});

  /// Schliesst ab mit Geraetezeit `client_submitted_at` und `device_event_id`; 409 `IMMUTABLE_RECORD` bedeutet: schon verarbeitet.
  Future<InspectionRemoteResult> submit({required String inspectionId, required int revision, required List<Map<String, Object?>> answers, required DateTime clientSubmittedAtUtc, required String deviceEventId});
}

/// Leitet `inspectionSubmit` an die Pruefungs-Gegenstelle und alle anderen Ereignisse an den Fahrerereignis-Eingang. Der Abschluss wird ueber Start-Schluessel und Geraeteereignis-ID genau einmal wirksam:
/// eine Wiederholung nach verlorener Antwort liefert `DUPLICATE`, eine Ablehnung (422, 404, 403: andere Vorlagenrevision, Schicht beendet, fremd) bleibt als Klaerfall `REVIEW_REQUIRED` stehen.
class InspectionAwareEventApi implements DriverEventApi {
  InspectionAwareEventApi({required this.inspections, required this.delegate});
  final InspectionRemote inspections;
  final DriverEventApi delegate;

  /// Schluessel des Starts je lokalem Ereignis: stabil, damit eine Wiederholung denselben Lauf trifft.
  static String startKey(String deviceEventId) => 'inspection-start-$deviceEventId';

  @override
  Future<ApiResponse> recordDriverEvent(QueuedEvent event) async {
    if (event.type != kInspectionSubmitEventType) return delegate.recordDriverEvent(event);
    final Map<String, Object?> p = event.payload;
    try {
      final InspectionRemoteRun run = await inspections.start(
        shiftId: p['shift_id']! as String,
        vehicleId: p['vehicle_id']! as String,
        kind: p['kind']! as String,
        clientStartedAtUtc: DateTime.parse(p['client_started_at']! as String).toUtc(),
        idempotencyKey: startKey(event.deviceEventId),
      );
      final InspectionRemoteResult r = await inspections.submit(
        inspectionId: run.id,
        revision: run.revision,
        answers: (p['answers']! as List<Object?>).map((Object? a) => (a! as Map<Object?, Object?>).cast<String, Object?>()).toList(),
        clientSubmittedAtUtc: event.capturedAtUtc,
        deviceEventId: event.deviceEventId,
      );
      if (r.status == 409) {
        // IMMUTABLE_RECORD: der Abschluss wurde bereits verarbeitet (Antwort ging verloren); die erste Wirkung gilt
        return ApiResponse(outcome: ServerOutcome.duplicate, serverReceivedAtUtc: r.receivedAtUtc, serverRevision: r.revision, originalOutcome: ServerOutcome.applied);
      }
      if (r.status == 200) return ApiResponse(outcome: ServerOutcome.applied, serverReceivedAtUtc: r.receivedAtUtc, serverRevision: r.revision);
      return ApiResponse(outcome: ServerOutcome.reviewRequired, serverReceivedAtUtc: r.receivedAtUtc, serverRevision: r.revision, reviewReason: ReviewReason.other);
    } on InspectionStartException catch (e) {
      if (e.failure == InspectionStartFailure.other && e.status >= 500) throw const NetworkUnavailableException();
      // Schicht nicht angetreten/beendet, keine Vorlage oder fremde Schicht: der Server nimmt die Pruefung nicht an
      return ApiResponse(outcome: ServerOutcome.reviewRequired, serverReceivedAtUtc: DateTime.now().toUtc(), serverRevision: 0, reviewReason: ReviewReason.other);
    }
  }
}
