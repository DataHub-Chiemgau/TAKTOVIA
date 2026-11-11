output "names" {
  value = local.names
}

output "hosting_is_decided" {
  value = var.hosting_provider != "unset" && var.hosting_region != "unset"
}
