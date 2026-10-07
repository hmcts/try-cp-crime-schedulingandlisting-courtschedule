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
}

data "azurerm_user_assigned_identity" "jenkins" {
  name                = "jenkins-${var.env == "sandbox" ? "sbox" : var.env}-mi"
  resource_group_name = "managed-identities-${var.env}-rg"
}

# This service is the FIRST component of the amp product, so it creates the product's shared
# resource group and Key Vault rather than consuming them. Under apim that job belonged to
# service-api-marketplace; when that service migrates to amp it must consume what is created here,
# not declare it again - two repositories declaring one vault fight over its state.
resource "azurerm_resource_group" "rg" {
  name     = "${var.product}-shared-${var.env}"
  location = var.location
  tags     = local.tags
}

module "vault" {
  source                               = "git@github.com:hmcts/cnp-module-key-vault?ref=master"
  name                                 = var.vault_name != "" ? var.vault_name : "${var.product}-${var.env}"
  product                              = var.product
  env                                  = var.env
  tenant_id                            = var.tenant_id
  object_id                            = var.jenkins_AAD_objectId
  resource_group_name                  = azurerm_resource_group.rg.name
  product_group_name                   = "DTS API Marketplace"
  common_tags                          = local.tags
  managed_identity_object_id           = var.managed_identity_object_id
  create_managed_identity              = true
  additional_managed_identities_access = var.additional_managed_identities_access
  jenkins_object_id                    = data.azurerm_user_assigned_identity.jenkins.principal_id
}

# A server of its own, not a second database on amp-flexible. Two repositories running
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
  name                      = "${var.product}-try-flexible"
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
  name         = "try-slc-POSTGRES-USER"
  value        = module.postgresql_flexible.username
  key_vault_id = module.vault.key_vault_id
  depends_on   = [module.vault]
}

resource "azurerm_key_vault_secret" "postgres_pass" {
  name         = "try-slc-POSTGRES-PASS"
  value        = module.postgresql_flexible.password
  key_vault_id = module.vault.key_vault_id
  depends_on   = [module.vault]
}

resource "azurerm_key_vault_secret" "postgres_host" {
  name         = "try-slc-POSTGRES-HOST"
  value        = module.postgresql_flexible.fqdn
  key_vault_id = module.vault.key_vault_id
  depends_on   = [module.vault]
}

resource "azurerm_key_vault_secret" "postgres_port" {
  name         = "try-slc-POSTGRES-PORT"
  value        = "5432"
  key_vault_id = module.vault.key_vault_id
  depends_on   = [module.vault]
}

resource "azurerm_key_vault_secret" "postgres_database" {
  name         = "try-slc-POSTGRES-DATABASE"
  value        = "tryitnow"
  key_vault_id = module.vault.key_vault_id
  depends_on   = [module.vault]
}
