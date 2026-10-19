# ─── Variables injected automatically by the HMCTS GitHub Actions reusable workflow ───
#
# terraform-deploy-openid passes these with -var regardless of whether the configuration uses
# them, so they must be declared or every plan fails on an undeclared variable.

variable "env" {
  type        = string
  description = "Environment name, injected by CI (e.g. sbox, aat)."
  default     = ""
}

variable "product" {
  type        = string
  description = "Product name, injected by CI."
  default     = ""
}

variable "subscription" {
  type        = string
  description = "Azure subscription ID, injected by CI."
  default     = ""
}

variable "aks_subscription_id" {
  type        = string
  description = "AKS subscription ID, injected by CI."
  default     = ""
}

variable "tenant_id" {
  type        = string
  description = "Azure tenant ID, injected by CI."
  default     = ""
}

variable "builtFrom" {
  type        = string
  description = "Repository reference, injected by CI."
  default     = ""
}

variable "ci_service_principal_object_id" {
  type        = string
  description = "CI service principal object ID, injected by CI."
  default     = ""
}

# ─── Module-specific variables ───

variable "api_mgmt_rg" {
  type        = string
  description = "Resource group name of the target APIM instance."
}

variable "api_mgmt_name" {
  type        = string
  description = "Name of the target APIM instance."
}

variable "apim_product" {
  description = "Product configuration to create in APIM."
  type = object({
    name                          = string
    description                   = optional(string)
    subscription_required         = optional(bool, true)
    subscriptions_limit           = optional(number, 20)
    approval_required             = optional(bool, true)
    published                     = optional(bool, true)
    product_access_control_groups = optional(list(string), [])
  })
}

variable "apis" {
  description = "Map of APIs to register in APIM. Details are sourced from the referenced OpenAPI spec file."
  type = map(object({
    openapi_spec_path = string

    # The full backend URL, not a host fragment.
    #
    # api-cp-crime-schedulingandlisting-courtschedule composes this as
    # "https://${service_host}.org.uk${service_path}" because every backend it registers is a
    # Common Platform app gateway (devamp01-appgw.dev.nl.cjscp). This sandbox runs in CNP, where
    # hosts end .platform.hmcts.net, so that expression would produce a .org.uk suffix on a host
    # that does not have one. Taking the whole URL avoids encoding any one estate's naming.
    service_url = string

    name         = optional(string)
    display_name = optional(string)
    path         = optional(string)
    revision     = optional(string, "1")
    protocols    = optional(list(string), ["https"])
    api_type     = optional(string, "http")

    # Whether APIM demands an Ocp-Apim-Subscription-Key. See the note in sbox.tfvars: the
    # discovery endpoint has to be readable before a consumer holds anything at all.
    subscription_required = optional(bool, false)

    # Adds Authorization and Accept header boxes to the portal's Try it (APIM import only).
    try_it_auth_headers = optional(bool, false)

    # Paths from openapi_spec_path to publish. Unset publishes all of them.
    #
    # The sandbox contract describes three endpoints, but only one belongs in APIM. The demo token
    # endpoint and the demo JWKS are the self-issued surface - they do not exist at all where
    # auth.key-source is ENTRA, which is what sandbox runs, so publishing them would document a
    # 404. A consumer reaching this through APIM gets its token from Entra.
    expose_paths = optional(list(string))

    # A second spec whose paths and schemas are merged into the imported document.
    #
    # This is how the API being demonstrated reaches APIM. /scenarios describes the sandbox;
    # /case/{case_urn}/courtschedule comes from the SLC contract, so a consumer sees one API with
    # the discovery endpoint and the real shape together rather than two half-APIs.
    merge_spec_path = optional(string)
  }))
}
