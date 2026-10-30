# NO LONGER APPLIED. amp-try-it-now owns this registration; see removed.tf.
#
# Kept so the plan that handed it over is reviewable against the values that were live at the
# time, and so the engine's tfvars can be diffed against them. Nothing reads these any more.

api_mgmt_rg   = "rg-sps-platform-sbox"
api_mgmt_name = "sps-api-mgmt-sbox"

apim_product = {
  name = "try-it-now-cp-crime-schedulingandlisting"

  # The product requires a subscription, so the Marketplace issues keys against it, and the API
  # below requires one too. Both are true, so there is no open path: every operation needs a key.
  # The API-level flag is the one that governs the API, which is why it is set explicitly rather
  # than left to inherit.
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
# try-slc's own resource app, amp-api-try-slc, added by external-entra-id#33. It replaces the
# shared hmcts-api-marketplace audience 30288840-..., which every API in this tenant pinned: a
# token minted for any one of them was accepted by all of them, and nothing in the token said
# which API it was for.
entra_tenant_id = "d44f885c-4fac-47bf-afde-d7d861ec4d7b"
entra_audience  = "c4c8974c-2800-4fe6-964f-dcd4c0820507"

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

    # A key is required, so the Marketplace gates discovery as well as the data. This was false
    # on the reasoning that GET /scenarios has to be readable before a consumer holds anything -
    # that is a product decision, and it has been taken the other way: a sandbox consumer arrives
    # through the Marketplace and already holds a key by the time they get here.
    #
    # It applies to /scenarios too. That path stays exempt from validate-jwt in
    # policies/api-policy.xml, so discovery needs a key but no Entra token - gated to subscribers
    # without a round trip to the tenant.
    #
    # Sandbox only. An aat or demo tfvars added later makes its own choice; demo is the public
    # catalogue's target and a browser there holds no key, so do not copy this to it by default.
    subscription_required = true

    # app.read is the only role amp-api-try-slc defines, so it is the only one a token for this
    # audience can carry. The previous list also named slc.read to mirror api-cp's policy - that
    # was possible only while both pinned the same shared audience, and is meaningless now.
    required_roles = ["app.read"]
  }
}
