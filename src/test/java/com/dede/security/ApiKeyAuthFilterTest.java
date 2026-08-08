package com.dede.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Before this filter existed, every REST/GraphQL endpoint -- including
 * POST /api/graph/scan -- was reachable by any caller who could reach the port.
 * Confirmed directly against a running server earlier this session, not assumed.
 */
class ApiKeyAuthFilterTest {

    private static final String HEADER = "X-Dede-Api-Key";
    private static final String REAL_KEY = "test-key-abc123";

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter();
        filter.setEffectiveKeyForTest(REAL_KEY);
    }

    @Test
    void rejectsRequestWithNoKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/graph/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing or invalid API key");
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsRequestWithWrongKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/graph/stats");
        request.addHeader(HEADER, "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsRequestWithCorrectKeyViaCustomHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/graph/stats");
        request.addHeader(HEADER, REAL_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void acceptsRequestWithCorrectKeyViaBearerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/graph/scan");
        request.addHeader("Authorization", "Bearer " + REAL_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void healthEndpointIsExemptFromAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void otherActuatorEndpointsAreNotExempt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void graphqlEndpointRequiresKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/graphql");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }
}
