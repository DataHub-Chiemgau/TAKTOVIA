# Pflichtparameter ohne Default (WP-M5-14). Anbieterneutral (D-05 offen); anbieterspezifische Ressourcen kommen erst nach D-05 hinter hosting_provider.
variable "environment" {
  type        = string
  description = "Umgebungskennung der Quelle (development, staging, performance, production)"
}
variable "hosting_provider" {
  type        = string
  description = "Hostinganbieter (D-05); unset bis zur Entscheidung"
}
variable "backup_domain" {
  type        = string
  description = "Eigene Berechtigungsdomäne der Betriebsrollen ops-backup, ops-restore, ops-retention (nicht die Anwendungs-, Migrator- oder Runtimeidentität)"
}
variable "application_identities" {
  type        = list(string)
  description = "Anwendungsidentitäten, denen der Zugriff auf Backupspeicher und Schlüssel ausdrücklich verweigert wird"
}
variable "restore_target_environment" {
  type        = string
  description = "Umgebung, in die wiederhergestellt wird; muss die Quellumgebung sein"
}
variable "wal_retention_days" {
  type        = number
  description = "Aufbewahrung von Basissicherung und WAL (Planungswert 35, OP-31)"
}
variable "replica_failure_domain" {
  type        = string
  description = "Ausfalldomäne der Replik des versionierten Objektspeichers; muss sich von der Quelle unterscheiden"
}
variable "source_failure_domain" {
  type        = string
  description = "Ausfalldomäne der Quelle"
}
variable "standby_expected" {
  type        = bool
  description = "ob ein Standby gemäß Betriebsmodell betrieben wird (Metrik taktovia_standby_connected)"
}
