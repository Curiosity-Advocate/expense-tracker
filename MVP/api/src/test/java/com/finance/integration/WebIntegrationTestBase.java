package com.finance.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

// Variant of IntegrationTestBase that boots the full embedded servlet
// container on a random port so tests can exercise the actual HTTP filter
// chain (TraceIdFilter → JwtAuthenticationFilter → AsUserIdFilter → ...).
//
// Use this base when a test needs to verify HTTP-level behaviour — request
// parsing, header handling, status codes, response envelopes. Service-level
// integration tests (most existing ones) extend IntegrationTestBase instead,
// since spinning up the servlet is unnecessary cost for them.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class WebIntegrationTestBase extends IntegrationTestBase {
}
