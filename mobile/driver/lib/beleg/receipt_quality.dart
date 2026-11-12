/// Bildqualitätsprüfung der Belegerfassung (WP-M4-25, LH-I.1-DOC-001): Mindestauflösung und Schärfe vor dem Speichern.
/// Die Schwellen stehen NICHT im Pflichtenheft (WP-M4 Offener Punkt 13): es sind Planungsdefaults als Konfigurationswerte, vom Auftraggeber zu bestätigen.
/// Reines Dart, ohne Flutter-Abhängigkeit; mit `flutter test` ausgeführt.
library;

/// Schwellen der Qualitätsprüfung. Planungsdefaults, nicht freigegeben.
class ReceiptQualityPolicy {
  const ReceiptQualityPolicy({this.minWidth = 1000, this.minHeight = 1000, this.minSharpness = 60.0});

  /// Mindestauflösung der kürzeren und der längeren Bildkante in Pixeln (Beleg im Hoch- oder Querformat): beide Kanten zusammen mit [minWidth]/[minHeight] verglichen,
  /// die Ausrichtung spielt keine Rolle.
  final int minWidth;
  final int minHeight;

  /// Mindestschärfe als Varianz des Laplace-Filters der Helligkeitswerte (0..255). Ein unscharfes Foto liegt weit darunter.
  final double minSharpness;
}

/// Graustufenbild (Helligkeit 0..255, zeilenweise) für die Prüfung; die Kamera-Anbindung liefert es (verkleinert ist zulässig, dann Auflösung separat angeben).
class GrayImage {
  GrayImage({required this.width, required this.height, required this.luma, int? sourceWidth, int? sourceHeight})
      : sourceWidth = sourceWidth ?? width,
        sourceHeight = sourceHeight ?? height {
    if (luma.length != width * height) throw ArgumentError('luma passt nicht zu Breite x Höhe');
  }

  final int width;
  final int height;
  final List<int> luma;

  /// Auflösung des Originalfotos (die Schärfeprüfung darf auf einer verkleinerten Kopie laufen).
  final int sourceWidth;
  final int sourceHeight;

  int at(int x, int y) => luma[y * width + x];
}

/// Hinweise an den Fahrer; die Oberfläche übersetzt sie in verständliche Texte und bietet die Neuaufnahme an.
enum QualityIssue { tooSmall, blurry }

class QualityReport {
  const QualityReport(this.issues, this.sharpness);
  final List<QualityIssue> issues;
  final double sharpness;
  bool get ok => issues.isEmpty;
}

/// Schärfemaß: Varianz des diskreten Laplace-Operators (4er-Nachbarschaft) über die Bildmitte; je schärfer die Kanten, desto größer.
double laplacianVariance(GrayImage img) {
  if (img.width < 3 || img.height < 3) return 0;
  double sum = 0, sumSq = 0;
  int n = 0;
  for (int y = 1; y < img.height - 1; y++) {
    for (int x = 1; x < img.width - 1; x++) {
      final int l = 4 * img.at(x, y) - img.at(x - 1, y) - img.at(x + 1, y) - img.at(x, y - 1) - img.at(x, y + 1);
      sum += l;
      sumSq += l * l;
      n++;
    }
  }
  final double mean = sum / n;
  return sumSq / n - mean * mean;
}

QualityReport assessReceiptQuality(GrayImage img, {ReceiptQualityPolicy policy = const ReceiptQualityPolicy()}) {
  final int shortSide = img.sourceWidth < img.sourceHeight ? img.sourceWidth : img.sourceHeight;
  final int longSide = img.sourceWidth < img.sourceHeight ? img.sourceHeight : img.sourceWidth;
  final int needShort = policy.minWidth < policy.minHeight ? policy.minWidth : policy.minHeight;
  final int needLong = policy.minWidth < policy.minHeight ? policy.minHeight : policy.minWidth;
  final double sharpness = laplacianVariance(img);
  return QualityReport(<QualityIssue>[
    if (shortSide < needShort || longSide < needLong) QualityIssue.tooSmall,
    if (sharpness < policy.minSharpness) QualityIssue.blurry,
  ], sharpness);
}
