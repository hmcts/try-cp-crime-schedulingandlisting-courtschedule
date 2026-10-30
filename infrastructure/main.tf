# The provider configuration stays. A plan still refreshes what is in state before the blocks below
# take effect, and the postgres module was built with the aliased provider, so both have to remain
# resolvable until every environment has applied this.
provider "azurerm" {
  features {}
}

provider "azurerm" {
  features {}
  skip_provider_registration = true
  alias                      = "postgres_network"
  subscription_id            = var.aks_subscription_id
}

# Releasing this component's infrastructure.
#
# amp-try-it-now serves the slc sandbox now. The APIM product and API moved to its Terraform state
# on 30 October, keeping all 19 subscription keys, and cnp-flux-config#48741 stops this deployment
# running. The vault, the Postgres server and the resource group have nothing left to serve.
#
# NOTHING IS DESTROYED HERE. Every block below carries destroy = false, so Terraform forgets these
# resources and leaves them exactly as they are in Azure. Deleting them is a separate, manual step
# with PlatOps: the resource group carries a CanNotDelete lock that has to be lifted and re-applied,
# which this pipeline cannot do. Same shape as the amp-aat vault orphaning.
#
# WHY NOT `terraform destroy`. The Jenkins pipeline offers plan and apply, nothing else, and nobody
# on this team can run state commands against the remote backend by hand. A `removed` block is the
# declarative form: it applies in the pipeline, it is visible in the plan, and the repository keeps
# a record of what was released and when.
#
# ONE PER ENVIRONMENT. The state is per environment, so this takes effect in each one only when
# that environment's pipeline runs - aat on the master build, sandbox and demo through the
# parameterised job. Until then that environment's state still holds the resources.
#
# ─── THE MANAGED IDENTITY IS THE ONE TO BE CAREFUL WITH ──────────────────────────────────────
#
# module.vault created amp-${env}-mi, with create_managed_identity = true, because this repository
# was amp's first component. It is PRODUCT-wide, not component-scoped - cnp-module-key-vault names
# it "${var.product}-${local.env}-mi" with no component segment - and it is the identity the Flux
# workload-identity service account federates against (${WI_NAME}-${WI_ENVIRONMENT}-mi in
# apps/amp/base). amp-try-it-now and both marketplace apps read it as a DATA SOURCE and create
# nothing.
#
# So after this applies, every amp component still depends on an identity that no repository
# creates. destroy = false means it survives, and it lives in managed-identities-${env}-rg rather
# than in this component's resource group, so deleting this resource group by hand will not take it
# with them. But amp-try-it-now should adopt it - create_managed_identity = true plus an import -
# before this repository is archived, or it is permanently unowned.

removed {
  from = azurerm_resource_group.rg

  lifecycle {
    destroy = false
  }
}

removed {
  from = module.vault

  lifecycle {
    destroy = false
  }
}

removed {
  from = module.postgresql_flexible

  lifecycle {
    destroy = false
  }
}

removed {
  from = azurerm_key_vault_secret.postgres_user

  lifecycle {
    destroy = false
  }
}

removed {
  from = azurerm_key_vault_secret.postgres_pass

  lifecycle {
    destroy = false
  }
}

removed {
  from = azurerm_key_vault_secret.postgres_host

  lifecycle {
    destroy = false
  }
}

removed {
  from = azurerm_key_vault_secret.postgres_port

  lifecycle {
    destroy = false
  }
}

removed {
  from = azurerm_key_vault_secret.postgres_database

  lifecycle {
    destroy = false
  }
}

# The demo signing key is the SELF_ISSUED realm's private key. Releasing it rather than destroying
# it keeps the pair intact for as long as the vault exists; it is deleted with everything else.
removed {
  from = tls_private_key.demo_signing_key

  lifecycle {
    destroy = false
  }
}

removed {
  from = azurerm_key_vault_secret.demo_signing_key

  lifecycle {
    destroy = false
  }
}
