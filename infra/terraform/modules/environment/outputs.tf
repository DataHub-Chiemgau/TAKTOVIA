output "names" {
  value = local.names
}

output "hosting_is_decided" {
  value = var.hosting_provider != "unset" && var.hosting_region != "unset"
}

# Wird als TAKTOVIA_ENVIRONMENT an Migrationsschritt und Anwendung gegeben (Umgebungskennung der Datenbank, WP-M1-40).
output "environment_marker" {
  value = var.environment
}
