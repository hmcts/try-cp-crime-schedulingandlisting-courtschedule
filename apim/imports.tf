# APIM adds the administrators group to a product the moment it is created, before Terraform gets
# to manage that association. So the first apply creates the product, then fails on the group it
# did not create:
#
#   Error: a resource with the ID ".../products/try-it-now-cp-crime-schedulingandlisting/groups/
#   administrators" already exists - to be managed via Terraform this resource needs to be
#   imported into the State
#
# Importing is the fix rather than dropping "administrators" from product_access_control_groups:
# the group genuinely should be on the product, and leaving it out of the config would mean
# Terraform never managed something it is responsible for.
#
# Same approach as api-cp-crime-schedulingandlisting-courtschedule's infrastructure/imports.tf.
# The difference is which environments need it: api-cp needs it everywhere except sbox, because
# its sbox product predates the behaviour. try-slc's product is new in sbox, so sbox is exactly
# where it is needed. Add an entry per environment as each one is first applied.
locals {
  administrators_product_group_ids = {
    "sps-api-mgmt-sbox" = "/subscriptions/bd2864ed-4f3e-45ed-9c6a-8d179674bab1/resourceGroups/rg-sps-platform-sbox/providers/Microsoft.ApiManagement/service/sps-api-mgmt-sbox/products/try-it-now-cp-crime-schedulingandlisting/groups/administrators"
  }
}

import {
  for_each = contains(keys(local.administrators_product_group_ids), var.api_mgmt_name) ? toset(["administrators"]) : toset([])
  to       = module.product.azurerm_api_management_product_group.access_control_groups[each.value]
  id       = local.administrators_product_group_ids[var.api_mgmt_name]
}
