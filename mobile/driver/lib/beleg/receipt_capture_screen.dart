import 'package:flutter/material.dart';

import '../ui/accessibility.dart';
import '../ui/strings.dart';
import 'receipt_capture_flow.dart';
import 'receipt_draft.dart';

/// Geführte Belegerfassung (UI-10, Fahrer-App): Foto, Zuschnitt/Drehung, Vorgang, Speichern in höchstens fünf Eingabeschritten. Alle Texte stehen in [Strings]; Auswahl und Status nie nur über
/// Farbe; Touchflächen mindestens 44 x 44 (AccessibleTap); größere Schrift folgt der Geräteeinstellung (keine festen Höhen). Während der Fahrbewegung öffnet sich kein Erfassungsdialog.
/// Widget-Tests mit `flutter test` ausgeführt (`test/beleg_erfassung_test.dart`); nicht auf einem Gerät.
class ReceiptCaptureScreen extends StatefulWidget {
  const ReceiptCaptureScreen({super.key, required this.flow, this.strings = Strings.de, this.onSaved});
  final ReceiptCaptureFlow flow;
  final Strings strings;
  final void Function(ReceiptDraft draft)? onSaved;

  @override
  State<ReceiptCaptureScreen> createState() => _ReceiptCaptureScreenState();
}

class _ReceiptCaptureScreenState extends State<ReceiptCaptureScreen> {
  String? _hint;
  List<String> _cases = <String>[];

  ReceiptCaptureFlow get _f => widget.flow;
  Strings get _s => widget.strings;

  String? _hintFor(CaptureOutcome o) => switch (o) {
        CaptureOutcome.accepted => null,
        CaptureOutcome.tooSmall => _s.of('receipt.quality.small'),
        CaptureOutcome.blurry => _s.of('receipt.quality.blurry'),
        CaptureOutcome.tooSmallAndBlurry => _s.of('receipt.quality.both'),
        CaptureOutcome.cameraDenied => _s.of('receipt.camera.denied'),
        CaptureOutcome.cameraDeniedPermanently => _s.of('receipt.camera.deniedPermanent'),
      };

  Future<void> _take() async {
    final CaptureOutcome o = await _f.takePhoto();
    setState(() => _hint = _hintFor(o));
  }

  @override
  Widget build(BuildContext context) {
    if (_f.start() == StartResult.blockedWhileMoving) {
      return Scaffold(appBar: AppBar(title: Text(_s.of('receipt.title'))), body: Center(child: Text(_s.of('receipt.moving'))));
    }
    final ReceiptStep step = _f.step;
    final String title = switch (step) {
      ReceiptStep.photo => _s.of('receipt.step.photo'),
      ReceiptStep.edit => _s.of('receipt.step.edit'),
      ReceiptStep.chooseCase => _s.of('receipt.step.case'),
      ReceiptStep.save => _s.of('receipt.step.save'),
      ReceiptStep.done => _s.of('receipt.saved'),
    };
    final List<Widget> body = <Widget>[
      // Hinweis zur Bildqualität bzw. Kamera: als Text mit Symbol, vom Screenreader sofort vorgelesen (liveRegion)
      if (_hint != null) Semantics(liveRegion: true, child: Row(children: <Widget>[const Icon(Icons.warning_amber, semanticLabel: 'Hinweis'), const SizedBox(width: 8), Expanded(child: Text(_hint!))])),
      switch (step) {
        ReceiptStep.photo => AccessibleTap(label: _s.of(_hint == null ? 'receipt.take' : 'receipt.retake'), onTap: _take, child: Text(_s.of(_hint == null ? 'receipt.take' : 'receipt.retake'))),
        ReceiptStep.edit => AccessibleTap(
            label: _s.of('receipt.confirmEdit'),
            onTap: () async {
              await _f.confirmEdit(crop: null, quarterTurns: 0);
              final List<String> labels = (await _f.cases.assignedCases()).map((c) => c.label).toList();
              setState(() => _cases = labels);
            },
            child: Text(_s.of('receipt.confirmEdit')),
          ),
        ReceiptStep.chooseCase => Column(children: <Widget>[
            for (final String label in _cases)
              AccessibleTap(
                label: label,
                onTap: () async {
                  final ref = (await _f.cases.assignedCases()).firstWhere((c) => c.label == label);
                  try {
                    await _f.chooseCase(ref);
                    setState(() => _hint = null);
                  } on CaseNotAssigned {
                    setState(() => _hint = _s.of('receipt.case.notAssigned'));
                  }
                },
                child: ListTile(title: Text(label)),
              ),
          ]),
        ReceiptStep.save => AccessibleTap(
            label: _s.of('receipt.save'),
            onTap: () async {
              final ReceiptDraft d = await _f.save();
              setState(() {});
              widget.onSaved?.call(d);
            },
            child: Text(_s.of('receipt.save')),
          ),
        ReceiptStep.done => const SizedBox.shrink(),
      },
      if (step != ReceiptStep.done)
        AccessibleTap(label: _s.of('receipt.cancel'), onTap: () => setState(_f.cancel), child: Text(_s.of('receipt.cancel'))),
    ];
    return Scaffold(appBar: AppBar(title: Text(title)), body: ListView(padding: const EdgeInsets.all(16), children: body));
  }
}

/// Getrennte Anzeige von „lokal“, „synchronisiert“ und „freigegeben“ (Kap. 06 §6.7): zwei Zeilen mit Symbol und Text, dazu ein ggf. vorhandener Synchronisationshinweis.
class ReceiptStatusView extends StatelessWidget {
  const ReceiptStatusView({super.key, required this.draft});
  final ReceiptDraft draft;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
      StatusChip(
        icon: draft.status == ReceiptSyncStatus.synced ? Icons.cloud_done : (draft.status == ReceiptSyncStatus.conflict ? Icons.error_outline : Icons.phone_android),
        text: draft.syncLabel,
        color: Theme.of(context).colorScheme.primary,
      ),
      StatusChip(icon: draft.approvalLabel == 'Freigegeben' ? Icons.verified : Icons.hourglass_empty, text: draft.approvalLabel, color: Theme.of(context).colorScheme.primary),
      Text('Erfasst: ${draft.capturedAtUtc.toLocal().toIso8601String()}'),
      if (draft.issueLabel.isNotEmpty) Semantics(liveRegion: true, child: Text(draft.issueLabel)),
    ]);
  }
}
