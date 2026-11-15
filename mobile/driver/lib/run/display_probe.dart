/// Messpunkt „Revision tatsaechlich dargestellt“ (WP-M5-13, LH-6.19-NFR-004; Kap. 08 §8.6 „Commit bis tatsaechlich dargestellte Clientrevision“).
/// NICHT AUSGEFUEHRT (Flutter war in der Erstellungsumgebung nicht verfuegbar). Nur im Testmodus aktiv (`enabled`): in der Produktion ist [DisplayProbe.disabled] eingesetzt und tut nichts.
///
/// Gemeldet wird nicht der Netzwerkempfang, sondern der Zeitpunkt nach dem Frame, der die Stoppliste mit genau dieser Planrevision gezeichnet hat (`RunListScreen` ruft [onFrameShown]
/// in einem Post-Frame-Callback). Eine aeltere Revision ueberschreibt nie eine neuere: [onFrameShown] meldet nur, wenn die Revision hoeher ist als die zuletzt gemeldete.
class DisplayProbe {
  DisplayProbe({required this.enabled, required this.now, required this.sink});

  /// Ohne Wirkung (Produktion).
  DisplayProbe.disabled()
      : enabled = false,
        now = DateTime.now,
        sink = _ignore;

  final bool enabled;
  final DateTime Function() now;

  /// Senke des Testmodus: Revision und Zeitpunkt der Darstellung (UTC). Sie traegt keine Fahrgast- oder Ortsdaten.
  final void Function(int revision, DateTime shownAtUtc) sink;

  int _lastReported = 0;

  /// Letzte gemeldete Revision (0: noch keine).
  int get lastReportedRevision => _lastReported;

  static void _ignore(int revision, DateTime shownAtUtc) {}

  /// Vom Frame-Callback gerufen, nachdem die Stoppliste der Revision [revision] gezeichnet wurde. [revision] null (kein Plan) meldet nichts.
  void onFrameShown(int? revision) {
    if (!enabled || revision == null || revision <= _lastReported) return;
    _lastReported = revision;
    sink(revision, now().toUtc());
  }
}
