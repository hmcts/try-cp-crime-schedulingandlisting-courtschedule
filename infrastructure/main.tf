provider "azurerm" {
  features {}
}

provider "azurerm" {
  features {}
  skip_provider_registration = true
  alias                      = "postgres_network"
  subscription_id            = var.aks_subscription_id
}

locals {
  tags = merge(
    var.common_tags,
    tomap({ "Team Contact" = var.team_contact })
  )

  # The vault is created by service-api-marketplace, which owns the apim product's shared
  # infrastructure. This repo consumes it, exactly as web-api-marketplace does for Redis -
  # two repos must never both declare the same vault, or they fight over its state.
  vault_name           = var.vault_name != "" ? var.vault_name : "${var.product}-${var.env}"
  vault_resource_group = "${var.product}-shared-${var.env}"
}

data "azurerm_key_vault" "vault" {
  name                = local.vault_name
  resource_group_name = local.vault_resource_group
}

# A server of its own, not a second database on apim-flexible. Two repositories running
# terraform against one server is a state conflict waiting to happen: whichever applies
# second sees the other's databases as drift. The name must differ from the backend's
# "${var.product}-flexible" or the two collide outright.
module "postgresql_flexible" {
  providers = {
    azurerm.postgres_network = azurerm.postgres_network
  }

  source                    = "git@github.com:hmcts/terraform-module-postgresql-flexible?ref=master"
  env                       = var.env
  product                   = var.product
  name                      = "${var.product}-tryitnow-flexible"
  component                 = var.component
  business_area             = "CFT"
  location                  = var.location
  subnet_suffix             = var.pgsql_subnet_suffix
  public_access             = var.pgsql_public_access
  pgsql_delegated_subnet_id = var.pgsql_public_access ? "bypass" : ""

  common_tags          = local.tags
  admin_user_object_id = var.jenkins_AAD_objectId

  pgsql_databases = [
    { name : "tryitnow" }
  ]

  pgsql_version = "16"
  pgsql_sku     = var.pgsql_sku
}

# Secret names are prefixed with the component, so they sit alongside the backend's
# marketplace-POSTGRES-* in the same vault without colliding. The chart maps them onto the
# POSTGRES_* aliases application.yaml reads.
resource "azurerm_key_vault_secret" "postgres_user" {
  name         = "tryitnow-slc-POSTGRES-USER"
  value        = module.postgresql_flexible.username
  key_vault_id = data.azurerm_key_vault.vault.id
}

resource "azurerm_key_vault_secret" "postgres_pass" {
  name         = "tryitnow-slc-POSTGRES-PASS"
  value        = module.postgresql_flexible.password
  key_vault_id = data.azurerm_key_vault.vault.id
}

resource "azurerm_key_vault_secret" "postgres_host" {
  name         = "tryitnow-slc-POSTGRES-HOST"
  value        = module.postgresql_flexible.fqdn
  key_vault_id = data.azurerm_key_vault.vault.id
}

resource "azurerm_key_vault_secret" "postgres_port" {
  name         = "tryitnow-slc-POSTGRES-PORT"
  value        = "5432"
  key_vault_id = data.azurerm_key_vault.vault.id
}

resource "azurerm_key_vault_secret" "postgres_database" {
  name         = "tryitnow-slc-POSTGRES-DATABASE"
  value        = "tryitnow"
  key_vault_id = data.azurerm_key_vault.vault.id
}
