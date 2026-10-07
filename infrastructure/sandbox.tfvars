# The file must be named sandbox.tfvars, not sbox.tfvars: the sbox cluster sets
# ENVIRONMENT=sbox for flux paths but KEYVAULT_ENVIRONMENT=sandbox for resource names, and
# the pipeline passes env=sandbox. The sbox vnet has no postgresql subnet, so the server
# runs with public access and the subnet data source is bypassed.
aks_subscription_id = "bf308a5c-0624-4334-8ff8-8dca9fd43783"
pgsql_sku           = "B_Standard_B1ms"
pgsql_public_access = true
vault_name          = "amp-try-slc-sbox"
