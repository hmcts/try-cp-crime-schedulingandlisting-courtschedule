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

    display_name      = "Try It Now — Crime Scheduling and Listing Court Schedule"
    path              = "amp/try/slc"

    # The CNP sandbox deployment, from apps/amp/amp-try-slc/sbox.yaml in cnp-flux-config.
    service_url = "https://amp-try-slc.sandbox.platform.hmcts.net"

    # No key to call it. GET /scenarios publishes the demo credentials, so it has to be reachable
    # before a consumer holds anything - a subscription key included. Rate and quota limits in
    # policies/api-policy.xml are what stands in for a key here. Flip this to true if the
    # Marketplace should gate discovery too; that is a product decision, not a technical one.
    subscription_required = false
  }
}
