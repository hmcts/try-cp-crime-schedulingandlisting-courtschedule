# Released, not destroyed. amp-try-it-now serves the slc sandbox now; destroy = false leaves every
# resource in Azure for PlatOps to delete by hand, past the CanNotDelete lock this pipeline cannot
# lift. `removed` rather than state surgery because the pipeline only offers plan and apply.
#
# Takes effect per environment as each pipeline runs: aat on master, sandbox and demo through the
# parameterised job.
#
# amp-${env}-mi is the one to watch. module.vault created it - product-wide, no component segment -
# and it is what the Flux workload-identity service account federates against. Everything else in
# amp only reads it. amp-try-it-now#21 adopts it.

# Kept while the removals are still pending in some environment: a plan refreshes state first, and
# the Postgres module was built with the aliased provider.
provider "azurerm" {
  features {}
}

provider "azurerm" {
  features {}
  skip_provider_registration = true
  alias                      = "postgres_network"
  subscription_id            = var.aks_subscription_id
}

removed {
  from = azurerm_resource_group.rg
  lifecycle { destroy = false }
}

removed {
  from = module.vault
  lifecycle { destroy = false }
}

removed {
  from = module.postgresql_flexible
  lifecycle { destroy = false }
}

removed {
  from = azurerm_key_vault_secret.postgres_user
  lifecycle { destroy = false }
}

removed {
  from = azurerm_key_vault_secret.postgres_pass
  lifecycle { destroy = false }
}

removed {
  from = azurerm_key_vault_secret.postgres_host
  lifecycle { destroy = false }
}

removed {
  from = azurerm_key_vault_secret.postgres_port
  lifecycle { destroy = false }
}

removed {
  from = azurerm_key_vault_secret.postgres_database
  lifecycle { destroy = false }
}

removed {
  from = tls_private_key.demo_signing_key
  lifecycle { destroy = false }
}

removed {
  from = azurerm_key_vault_secret.demo_signing_key
  lifecycle { destroy = false }
}
