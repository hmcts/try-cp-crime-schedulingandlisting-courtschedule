locals {
  api_specs = {
    for api_key, api in var.apis :
    api_key => yamldecode(file(api.openapi_spec_path))
  }

  # Declared only in the copy imported into APIM, so the portal's Try it shows them as
  # pre-filled boxes. The spec itself is untouched: header parameters there would change
  # the generated interface the service implements.
  try_it_headers = [
    {
      name        = "Authorization"
      in          = "header"
      required    = true
      description = "Bearer token from POST /oauth2/v2.0/token on this sandbox."
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

  import_specs = {
    for api_key, api in var.apis :
    api_key => api.try_it_auth_headers ? yamlencode(merge(local.api_specs[api_key], {
      paths = {
        for path, item in local.api_specs[api_key].paths : path => merge(
          { for key, value in item : key => value if !contains(local.http_methods, key) },
          {
            for method, operation in item : method => merge(operation, {
              parameters = concat(try(operation.parameters, []), local.try_it_headers)
            }) if contains(local.http_methods, method)
          }
        )
      }
    })) : file(api.openapi_spec_path)
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
