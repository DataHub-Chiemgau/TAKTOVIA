# Anbieterneutrales Umgebungsmodul (WP-M0-11). Pflichtparameter haben bewusst KEINEN Default:
# fehlt einer, bricht `terraform validate/plan` ab. Die Hostingentscheidung (D-05) ist ausschliesslich ueber
# hosting_provider / hosting_region gekapselt.
variable "environment" {
  type        = string
  description = "development | staging | performance | production"
  validation {
    condition     = contains(["development", "staging", "performance", "production"], var.environment)
    error_message = "environment muss development, staging, performance oder production sein."
  }
}

variable "hosting_provider" {
  type        = string
  description = "Hostinganbieter (D-05). 'unset' = noch nicht entschieden; dann ist keine Anwendung auf eine reale Umgebung moeglich."
}

variable "hosting_region" {
  type        = string
  description = "EU-Hostingregion (Kap. 01 1.1); 'unset' bis D-05 entschieden."
}

variable "secret_domain" {
  type        = string
  description = "Eigene Geheimnisdomaene je Umgebung (Name des Secret-Stores/Namespace); nie umgebungsuebergreifend geteilt."
}

variable "identity_domain" {
  type        = string
  description = "Eigene Identitaetsdomaene je Umgebung (Keycloak-Realm/Issuer-Namespace)."
}

variable "psp_domain" {
  type        = string
  description = "Eigene PSP-Domaene je Umgebung (Sandbox/Test vs. live)."
}

variable "db_migration_access" {
  type        = string
  description = "Name des kurzlebigen Migrationszugangs (nur Migrationsschritt, Kap. 01 1.7)."
}

variable "db_runtime_access" {
  type        = string
  description = "Name des Laufzeitzugangs (Runtime-Rolle; darf nicht gleich dem Migrationszugang sein)."
  validation {
    condition     = var.db_runtime_access != ""
    error_message = "db_runtime_access darf nicht leer sein."
  }
}

variable "data_class" {
  type        = string
  description = "synthetic | production-like-synthetic | production"
  validation {
    condition     = contains(["synthetic", "production-like-synthetic", "production"], var.data_class)
    error_message = "data_class ungueltig."
  }
}

variable "account_domain" {
  type        = string
  description = "Eigenes Konto/Projekt je Umgebung (Kap. 01 1.8, WP-M1-40); nie umgebungsuebergreifend geteilt."
}

variable "network_domain" {
  type        = string
  description = "Eigenes Netz je Umgebung (WP-M1-40); es gibt keinen Netzpfad zwischen Netzen verschiedener Umgebungen."
}

variable "db_ingress_network_domain" {
  type        = string
  description = "Netz, aus dem die Datenbank erreichbar ist; muss das eigene Netz der Umgebung sein (kein Netzpfad von Entwicklung zur Produktionsdatenbank)."
  validation {
    condition     = var.db_ingress_network_domain != ""
    error_message = "db_ingress_network_domain darf nicht leer sein."
  }
}
