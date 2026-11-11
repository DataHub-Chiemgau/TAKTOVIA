import 'package:flutter/material.dart';

/// Mindestgroesse von Touchflaechen (Kap. 06 §6.1): 44 x 44 logische Pixel.
const double kMinTouchTarget = 44;

/// Stellt die Mindestgroesse sicher und setzt eine Screenreader-Beschriftung.
class AccessibleTap extends StatelessWidget {
  const AccessibleTap({super.key, required this.label, required this.onTap, required this.child});
  final String label;
  final VoidCallback? onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      enabled: onTap != null,
      label: label,
      child: InkWell(
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minWidth: kMinTouchTarget, minHeight: kMinTouchTarget),
          child: Center(child: child),
        ),
      ),
    );
  }
}

/// Status nie nur ueber Farbe: Symbol und Text gehoeren dazu.
class StatusChip extends StatelessWidget {
  const StatusChip({super.key, required this.icon, required this.text, required this.color});
  final IconData icon;
  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: text,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          ExcludeSemantics(child: Icon(icon, color: color)),
          const SizedBox(width: 8),
          ExcludeSemantics(child: Text(text)),
        ],
      ),
    );
  }
}
