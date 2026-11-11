# Pflichtparameter ohne Default; Werte stehen in terraform.tfvars (nicht eingecheckt) bzw. terraform.tfvars.example.
variable "hosting_provider" { type = string }
variable "hosting_region" { type = string }
variable "secret_domain" { type = string }
variable "identity_domain" { type = string }
variable "psp_domain" { type = string }
variable "db_migration_access" { type = string }
variable "db_runtime_access" { type = string }
variable "account_domain" { type = string }
variable "network_domain" { type = string }
variable "db_ingress_network_domain" { type = string }
