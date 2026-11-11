module "environment" {
  source = "../../modules/environment"

  environment         = "production"
  hosting_provider    = var.hosting_provider
  hosting_region      = var.hosting_region
  secret_domain       = var.secret_domain
  identity_domain     = var.identity_domain
  psp_domain          = var.psp_domain
  db_migration_access = var.db_migration_access
  db_runtime_access   = var.db_runtime_access
  data_class          = "production"
}
