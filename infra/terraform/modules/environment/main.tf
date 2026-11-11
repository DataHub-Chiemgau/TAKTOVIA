# Gerüst ohne anbieterspezifische Ressourcen (D-05 offen). Anbieterbausteine werden nach D-05 hier ergaenzt,
# jeweils hinter hosting_provider. Namenskonvention: taktovia-<umgebung>-<komponente>.
locals {
  prefix = "taktovia-${var.environment}"
  names = {
    postgres      = "${local.prefix}-postgres"
    objectstore   = "${local.prefix}-objects"
    app           = "${local.prefix}-app"
    migration_job = "${local.prefix}-migrate"
  }
}

resource "terraform_data" "environment_manifest" {
  input = {
    environment         = var.environment
    hosting_provider    = var.hosting_provider
    hosting_region      = var.hosting_region
    secret_domain       = var.secret_domain
    identity_domain     = var.identity_domain
    psp_domain          = var.psp_domain
    db_migration_access = var.db_migration_access
    db_runtime_access   = var.db_runtime_access
    data_class          = var.data_class
    names               = local.names
  }

  lifecycle {
    precondition {
      condition     = var.db_migration_access != var.db_runtime_access
      error_message = "Migrations- und Laufzeitzugang muessen getrennt sein (Kap. 01 1.7)."
    }
    precondition {
      condition     = var.environment == "production" || var.data_class != "production"
      error_message = "Produktionsdaten nur in production."
    }
  }
}
