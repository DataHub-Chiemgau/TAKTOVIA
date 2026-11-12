import 'package:flutter/material.dart';

import '../ui/accessibility.dart';
import 'defect_draft.dart';

/// Geführte Erfassung (UI-10 Fahrt/Schaden): ein Schritt je Pflichtangabe, Auswahlfelder statt Freitext, ein begrenztes Notizfeld, Foto, Senden.
/// Das Foto liefert die Kamera-Anbindung über [onTakePhoto] (liefert die Id des abgeschlossenen Uploadtickets); die Übertragung übernimmt [onSend].
/// NICHT AUSGEFÜHRT (kein Flutter in der Erstellungsumgebung).
class DefectCaptureScreen extends StatefulWidget {
  const DefectCaptureScreen({super.key, required this.draft, this.onTakePhoto, this.onSend});
  final DefectDraft draft;
  final Future<String?> Function()? onTakePhoto;
  final Future<void> Function(DefectDraft draft)? onSend;

  @override
  State<DefectCaptureScreen> createState() => _DefectCaptureScreenState();
}

class _DefectCaptureScreenState extends State<DefectCaptureScreen> {
  static const Map<String, String> _labels = <String, String>{
    'SCRATCH': 'Kratzer', 'DENT': 'Delle', 'CRACK_BREAK': 'Riss oder Bruch', 'MALFUNCTION': 'Funktionsstörung', 'MISSING_PART': 'Teil fehlt', 'LEAK': 'Undicht',
    'WEAR': 'Verschleiß', 'CONTAMINATION': 'Verschmutzung', 'OTHER': 'Sonstiges',
    'FRONT': 'Front', 'REAR': 'Heck', 'LEFT_SIDE': 'Linke Seite', 'RIGHT_SIDE': 'Rechte Seite', 'ROOF': 'Dach', 'UNDERBODY': 'Unterboden', 'WHEELS_TIRES': 'Räder und Reifen',
    'INTERIOR_PASSENGER': 'Fahrgastraum', 'INTERIOR_DRIVER': 'Fahrerplatz', 'LUGGAGE': 'Gepäckraum', 'WHEELCHAIR_AREA': 'Rollstuhlplatz', 'ENGINE_BATTERY': 'Antrieb und Batterie',
    'NONE_VISIBLE': 'Keine sichtbare Auswirkung', 'COSMETIC_ONLY': 'Nur optisch', 'FUNCTION_LIMITED': 'Funktion eingeschränkt', 'NOT_ROADWORTHY': 'Nicht fahrbereit',
    'FLUID_LEAK': 'Flüssigkeit tritt aus', 'WARNING_LIGHT': 'Warnleuchte', 'NOISE_VIBRATION': 'Geräusch oder Vibration',
  };

  CaptureStep get _step => widget.draft.nextStep;

  void _set(void Function() change) => setState(change);

  Widget _choices(List<String> codes, String? selected, void Function(String) pick) => Column(
        children: <Widget>[
          for (final String c in codes)
            AccessibleTap(
              label: _labels[c] ?? c,
              onTap: () => _set(() => pick(c)),
              child: ListTile(
                title: Text(_labels[c] ?? c),
                // Auswahl nie nur über Farbe: Symbol und Text
                trailing: selected == c ? const Icon(Icons.check_circle, semanticLabel: 'Ausgewählt') : null,
              ),
            ),
        ],
      );

  @override
  Widget build(BuildContext context) {
    final DefectDraft d = widget.draft;
    final String title = switch (_step) {
      CaptureStep.kind => 'Art des Schadens',
      CaptureStep.area => 'Fahrzeugbereich',
      CaptureStep.effect => 'Beobachtete Auswirkung',
      CaptureStep.photo => 'Foto aufnehmen',
      CaptureStep.send => 'Prüfen und senden',
    };
    final Widget body = switch (_step) {
      CaptureStep.kind => _choices(kDefectKinds, d.kind, (String c) => d.kind = c),
      CaptureStep.area => _choices(kDefectAreas, d.vehicleArea, (String c) => d.vehicleArea = c),
      CaptureStep.effect => _choices(kDefectEffects, d.observedEffect, (String c) => d.observedEffect = c),
      CaptureStep.photo => Column(children: <Widget>[
          AccessibleTap(
            label: 'Foto aufnehmen',
            onTap: widget.onTakePhoto == null
                ? null
                : () async {
                    final String? id = await widget.onTakePhoto!();
                    if (id != null) _set(() => d.photoTicketIds.add(id));
                  },
            child: const Text('Foto aufnehmen'),
          ),
          TextField(
            maxLength: kMaxDefectNoteLength,
            decoration: const InputDecoration(labelText: 'Notiz (nur notwendige Angaben, keine Namen oder Gesundheitsangaben)'),
            onChanged: (String v) => d.note = v,
          ),
        ]),
      CaptureStep.send => Column(children: <Widget>[
          Text('${_labels[d.kind]}, ${_labels[d.vehicleArea]}, ${_labels[d.observedEffect]}, ${d.photoTicketIds.length} Foto(s)'),
          AccessibleTap(
            label: 'Senden',
            onTap: widget.onSend == null ? null : () async { await widget.onSend!(d); _set(() => d.status = DraftStatus.synced); },
            child: const Text('Senden'),
          ),
        ]),
    };
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: ListView(children: <Widget>[
        Semantics(liveRegion: true, label: d.statusLabel(), child: Padding(padding: const EdgeInsets.all(16), child: Row(children: <Widget>[
          Icon(switch (d.status) { DraftStatus.draft => Icons.edit, DraftStatus.synced => Icons.cloud_done, DraftStatus.accepted => Icons.verified }),
          const SizedBox(width: 8),
          Expanded(child: Text(d.statusLabel())),
        ]))),
        body,
      ]),
    );
  }
}
