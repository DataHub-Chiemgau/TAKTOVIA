# Datensicherung (WP-M5-14): WAL-Archiv und Basissicherung (verschlüsselt, einmal schreibbar), versionierter Objektspeicher mit Replikation, Standby.
# Gerüst ohne anbieterspezifische Ressourcen (D-05 offen); NICHT angewendet, `terraform validate` nicht ausgeführt. Die Prüfungen unten sind Preconditions, die ein Plan erzwingt.
locals {
  prefix = "taktovia-${var.environment}"
  names = {
    backup_store   = "${local.prefix}-backup"           # Bucket/Volume mit Aufbewahrungssperre (Compliance-Modus), einmal schreibbar
    wal_archive    = "${local.prefix}-wal"
    object_store   = "${local.prefix}-objects"           # versioniert, mit Replikation
    object_replica = "${local.prefix}-objects-replica"
    standby        = "${local.prefix}-postgres-standby"
  }
}

resource "terraform_data" "datensicherung" {
  input = {
    environment            = var.environment
    names                  = local.names
    backup_domain          = var.backup_domain
    denied_identities      = var.application_identities
    versioning             = true
    object_lock_mode       = "compliance"
    retention_days         = var.wal_retention_days
    encryption             = "AES-256-GCM, Schluessel nur in der Betriebsdomaene"
    replica_failure_domain = var.replica_failure_domain
    standby_expected       = var.standby_expected
  }

  lifecycle {
    precondition {
      condition     = var.restore_target_environment == var.environment
      error_message = "Restore nur in die Quellumgebung (keine Produktionsdaten in Staging/Development, Kap. 01 1.7)."
    }
    precondition {
      condition     = var.replica_failure_domain != var.source_failure_domain
      error_message = "Die Replik des Objektspeichers liegt in einer anderen Ausfalldomaene als die Quelle."
    }
    precondition {
      condition     = var.wal_retention_days >= 35
      error_message = "Aufbewahrung unter dem Planungswert von 35 Tagen (OP-31)."
    }
    precondition {
      condition     = length(var.application_identities) >= 3 && !contains(var.application_identities, var.backup_domain)
      error_message = "Anwendungs-, Migrator- und Runtimeidentitaet muessen vom Backupspeicher ausgeschlossen sein und duerfen nicht die Backupdomaene sein."
    }
  }
}
