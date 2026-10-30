# Handing the slc registration to amp-try-it-now.
#
# The product, the API and its policy are no longer managed here. They are NOT destroyed: they
# stay exactly as they are in APIM and amp-try-it-now adopts them with import blocks, which is
# what `destroy = false` below says.
#
# WHY NOT DESTROY AND LET THE OTHER REPOSITORY CREATE THEM AGAIN. The product carries 19 live
# subscriptions. Subscriptions belong to a product, so destroying it invalidates every key and
# every consumer has to re-subscribe through the Marketplace. Handing the resources over instead
# costs nobody anything - after both applies the only thing that has changed in Azure is the
# API's backend URL, which moves from this service to the engine.
#
# WHY A `removed` BLOCK AND NOT `terraform state rm`. Everything here runs through a pipeline, and
# nobody on this team can run state commands against the remote backend by hand. A `removed` block
# is the declarative form of the same operation: it applies in CI, is reviewable in the plan, and
# leaves a record in the repository of what was handed over and when.
#
# ORDER. This must apply BEFORE amp-try-it-now imports them. Two states managing one resource is
# only a problem when both apply, and once this has run nothing here names them any more.
#
# WHAT IS LEFT BEHIND. variables.tf and sbox.tfvars still describe the registration, and
# policies/api-policy.xml is still the policy that was applied. They are kept deliberately: the
# plan for this directory is now empty, and deleting it is the next PR rather than this one, so
# that the handover and the deletion are separately reviewable and separately revertible.
#
# TO REVERT, before amp-try-it-now has adopted them: restore products.tf, apis.tf and imports.tf
# from the commit that deleted them, and add an import block for each resource - the ids are in
# amp-try-it-now's apim/imports.tf. A revert of this commit alone would plan to CREATE them, and
# fail, because they already exist.

removed {
  from = module.product

  lifecycle {
    destroy = false
  }
}

removed {
  from = module.apis

  lifecycle {
    destroy = false
  }
}

removed {
  from = azurerm_api_management_api_policy.api_policy

  lifecycle {
    destroy = false
  }
}
