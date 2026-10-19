locals {
  api_specs = {
    for api_key, api in var.apis :
    api_key => yamldecode(file(api.openapi_spec_path))
  }

  # The second spec, where one is configured - the SLC contract.
  # Keyed only where a second spec is configured. A null-valued entry would force every later
  # lookup through a conditional, and HCL will not unify {} with a populated object - which is
  # exactly how the first version of this failed.
  merge_specs = {
    for api_key, api in var.apis :
    api_key => yamldecode(file(api.merge_spec_path))
    if api.merge_spec_path != null
  }

  # What APIM actually publishes: the allow-listed paths from the sandbox contract, plus every
  # path from the merged one. Unset expose_paths means publish the lot.
  combined_paths = {
    for api_key, api in var.apis :
    api_key => merge(
      {
        for path, item in local.api_specs[api_key].paths : path => item
        if api.expose_paths == null || contains(coalesce(api.expose_paths, []), path)
      },
      try(local.merge_specs[api_key].paths, {})
    )
  }

  # Schemas from both specs. Verified non-overlapping - the sandbox contract names its types
  # ScenariosResponse/Authentication/..., the SLC contract CourtSchedule*/Hearing/ErrorResponse -
  # so a later collision is a real conflict to resolve, not something to silently let one side win.
  combined_schemas = {
    for api_key, api in var.apis :
    api_key => merge(
      try(local.api_specs[api_key].components.schemas, {}),
      try(local.merge_specs[api_key].components.schemas, {})
    )
  }

  # Declared only in the copy imported into APIM, so the portal's Try it shows them as
  # pre-filled boxes. The spec itself is untouched: header parameters there would change
  # the generated interface the service implements.
  try_it_headers = [
    {
      name        = "Authorization"
      in          = "header"
      required    = true
      description = "Bearer token. In the Entra realm, obtain one from the tenant named by GET /scenarios."
      schema      = { type = "string", default = "Bearer <access_token>" }
    },
    {
      name     = "Accept"
      in       = "header"
      required = false
      schema   = { type = "string", default = "application/json" }
    },
  ]

  http_methods = ["get", "put", "post", "delete", "patch", "head", "options"]

  # info and servers come from the sandbox contract; APIM overrides the backend with service_url
  # anyway, so the SLC spec's templated server block is deliberately dropped.
  combined_specs = {
    for api_key, api in var.apis :
    api_key => merge(local.api_specs[api_key], {
      paths = local.combined_paths[api_key]
      components = merge(
        try(local.api_specs[api_key].components, {}),
        { schemas = local.combined_schemas[api_key] }
      )
    })
  }

  # Always applied, never branched. An `api.try_it_auth_headers ? x : y` here would be the same
  # trap as above: the two sides are objects of different shape and HCL refuses to unify them.
  # Filtering the header list instead keeps one code path - with the flag off the comprehension
  # yields [], concat is a no-op, and the document is unchanged.
  import_specs = {
    for api_key, api in var.apis :
    api_key => yamlencode(merge(local.combined_specs[api_key], {
      paths = {
        for path, item in local.combined_paths[api_key] : path => merge(
          { for key, value in item : key => value if !contains(local.http_methods, key) },
          {
            for method, operation in item : method => merge(operation, {
              parameters = concat(
                try(operation.parameters, []),
                [for header in local.try_it_headers : header if api.try_it_auth_headers],
              )
            }) if contains(local.http_methods, method)
          }
        )
      }
    }))
  }
}

module "apis" {
  for_each = var.apis

  source = "git::https://github.com/hmcts/cnp-module-api-mgmt-api.git?ref=master"

  api_mgmt_rg   = var.api_mgmt_rg
  api_mgmt_name = var.api_mgmt_name
  product_id    = module.product.product_id

  revision = each.value.revision

  display_name = coalesce(
    try(each.value.display_name, null),
    try(local.api_specs[each.key].info.title, null),
    each.key
  )

  name = coalesce(
    try(each.value.name, null),
    lower(replace(
      coalesce(
        try(local.api_specs[each.key].info.title, null),
        each.key
      ),
      "/[^0-9A-Za-z-]/",
      "-"
    ))
  )

  path = coalesce(
    try(each.value.path, null),
    lower(replace(each.key, "/[^0-9A-Za-z-]/", "-"))
  )

  protocols             = each.value.protocols
  service_url           = each.value.service_url
  subscription_required = each.value.subscription_required
  api_type              = each.value.api_type

  swagger_url    = local.import_specs[each.key]
  content_format = "openapi"
}

# No validate-jwt, deliberately — see policies/api-policy.xml.
resource "azurerm_api_management_api_policy" "api_policy" {
  for_each = var.apis

  api_name            = module.apis[each.key].name
  api_management_name = var.api_mgmt_name
  resource_group_name = var.api_mgmt_rg

  xml_content = file("${path.module}/policies/api-policy.xml")

  depends_on = [module.apis]
}
