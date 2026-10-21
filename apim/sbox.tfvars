api_mgmt_rg   = "rg-sps-platform-sbox"
api_mgmt_name = "sps-api-mgmt-sbox"

apim_product = {
  name = "try-it-now-cp-crime-schedulingandlisting"

  # The product requires a subscription so the Marketplace can still issue keys against it, but
  # the API below sets subscription_required = false and that wins for the API itself. This is the
  # standard way to keep an open API inside a managed product.
  subscription_required         = true
  subscriptions_limit           = 20
  approval_required             = false
  published                     = true
  product_access_control_groups = ["developers", "administrators", "guests"]
}

# Sandbox is the only ENTRA environment, so it is the only one that sets these. Both values must
# equal what the pod validates against - cnp-flux-config/apps/amp/amp-try-slc/sbox.yaml sets the
# same pair as AUTH_TENANT_ID and AUTH_AUDIENCE. If they drift, a token fails at one layer or the
# other and the two 401s look identical from outside.
#
# The audience is the shared hmcts-api-marketplace one for every API in the tenant, not a
# try-slc audience: a consumer's token carries whatever they requested with
# scope=<resource>/.default, and web-api-marketplace grants app.read on this GUID.
entra_tenant_id = "d44f885c-4fac-47bf-afde-d7d861ec4d7b"
entra_audience  = "30288840-e345-4543-99ee-f9253d789339"

apis = {
  public = {
    openapi_spec_path = "../contracts/try-it-now-public.openapi.yml"

    # Only /scenarios from the sandbox contract. The demo token endpoint and the demo JWKS are the
    # self-issued surface and do not exist where auth.key-source is ENTRA, which is what sandbox
    # runs - publishing them would document a 404.
    expose_paths = ["/scenarios"]

    # The API being demonstrated. Vendored from the pinned api-cp jar by ./gradlew syncSlcContract
    # and guarded by verifySlcContract, so what APIM publishes is the spec this service implements.
    merge_spec_path = "../contracts/slc-courtschedule.openapi.yml"

    display_name = "Try It Now — Crime Scheduling and Listing Court Schedule"
    path         = "amp/try/slc"

    # The CNP sandbox deployment, from apps/amp/amp-try-slc/sbox.yaml in cnp-flux-config.
    service_url = "https://amp-try-slc.sandbox.platform.hmcts.net"

    # No key to call it. GET /scenarios publishes the demo credentials, so it has to be reachable
    # before a consumer holds anything - a subscription key included. Rate and quota limits in
    # policies/api-policy.xml are what stands in for a key here. Flip this to true if the
    # Marketplace should gate discovery too; that is a product decision, not a technical one.
    subscription_required = false

    # Mirrors api-cp-crime-schedulingandlisting-courtschedule's sbox policy. Wider than this
    # service's KNOWN_ROLES of app.read alone, and that is the point: a caller holding only
    # slc.read must pass the gateway and be refused 403 by the service, exactly as against the
    # real API. See policies/api-policy.xml.
    required_roles = ["slc.read", "app.read"]
  }
}
