package com.nexg.ide.testing

/**
 * The request path of the debug bridge, with no Android types in sight.
 *
 * [NexGTestReceiver.onReceive] does nothing but unwrap an `Intent` and call
 * [handle]; every rule that matters lives here so it can be tested on the JVM.
 * This project has no Robolectric, so a rule written directly inside
 * `onReceive` would be untestable — and "is the controller reached before the
 * token is checked?" is exactly the kind of question a test must be able to ask.
 */
object NexGTestBridge {

    /**
     * Handles one request.
     *
     * Order is the security-relevant part:
     *  1. authorize the token;
     *  2. *then* parse and apply the allowlist;
     *  3. *then* dispatch.
     *
     * Authorizing before parsing matters: otherwise an unauthenticated caller
     * could distinguish "unknown command" from "bad argument" from "unsafe
     * argument" and use the error codes to map the harness's command surface.
     * [dispatch] is therefore only ever reached with an authorized request.
     *
     * [expectedToken] and [providedToken] are passed in rather than read from
     * storage here, so a test can supply a wrong token without touching disk.
     */
    fun handle(
        rawRequest: String?,
        providedToken: String?,
        expectedToken: String?,
        dispatch: (TestRequest.Ok) -> TestResponse,
    ): TestResponse {
        when (val verdict = HarnessAuth.authorize(expectedToken, providedToken)) {
            is HarnessAuth.Verdict.Reject -> return HarnessAuth.rejectionResponse()
            HarnessAuth.Verdict.Allow -> Unit
        }
        return when (val parsed = TestRequestParser.parse(rawRequest)) {
            is TestRequest.Rejected -> TestResponse.rejected("?", parsed.code, parsed.message)
            is TestRequest.Ok -> dispatch(parsed)
        }
    }
}
