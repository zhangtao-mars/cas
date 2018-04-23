package org.apereo.cas.web.flow;

import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.CasProtocolConstants;
import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.audit.AuditableExecution;
import org.apereo.cas.audit.AuditableExecutionResult;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationManager;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationResultBuilder;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.AuthenticationTransaction;
import org.apereo.cas.authentication.AuthenticationTransactionManager;
import org.apereo.cas.authentication.CoreAuthenticationTestUtils;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.DefaultAuthenticationServiceSelectionPlan;
import org.apereo.cas.authentication.DefaultAuthenticationServiceSelectionStrategy;
import org.apereo.cas.authentication.adaptive.AdaptiveAuthenticationPolicy;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.authentication.principal.WebApplicationServiceFactory;
import org.apereo.cas.services.AbstractRegisteredService;
import org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy;
import org.apereo.cas.services.DefaultRegisteredServiceDelegatedAuthenticationPolicy;
import org.apereo.cas.services.RegisteredServiceTestUtils;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.ticket.ExpirationPolicy;
import org.apereo.cas.ticket.Ticket;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.TicketGrantingTicketImpl;
import org.apereo.cas.ticket.factory.DefaultTransientSessionTicketFactory;
import org.apereo.cas.ticket.registry.DefaultTicketRegistry;
import org.apereo.cas.ticket.support.HardTimeoutExpirationPolicy;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.Pac4jUtils;
import org.apereo.cas.web.DelegatedClientWebflowManager;
import org.apereo.cas.web.flow.resolver.CasDelegatingWebflowEventResolver;
import org.apereo.cas.web.flow.resolver.CasWebflowEventResolver;
import org.apereo.cas.web.pac4j.DelegatedSessionCookieManager;
import org.apereo.cas.web.support.CookieRetrievingCookieGenerator;
import org.junit.Test;
import org.pac4j.core.client.BaseClient;
import org.pac4j.core.client.Clients;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.oauth.client.FacebookClient;
import org.pac4j.oauth.client.TwitterClient;
import org.pac4j.oauth.credentials.OAuth20Credentials;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.theme.ThemeChangeInterceptor;
import org.springframework.webflow.action.AbstractAction;
import org.springframework.webflow.context.servlet.ServletExternalContext;
import org.springframework.webflow.core.collection.MutableAttributeMap;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.test.MockRequestContext;

import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * This class tests the {@link DelegatedClientAuthenticationAction} class.
 *
 * @author Jerome Leleu
 * @since 3.5.2
 */
@Slf4j
public class DelegatedClientAuthenticationActionTests {

    private static final String TGT_ID = "TGT-00-xxxxxxxxxxxxxxxxxxxxxxxxxx.cas0";

    private static final String MY_KEY = "my_key";

    private static final String MY_SECRET = "my_secret";

    private static final String MY_LOGIN_URL = "http://casserver/login";

    private static final String MY_SERVICE = "http://myservice";

    private static final String MY_THEME = "my_theme";

    @Test
    public void verifyStartAuthentication() throws Exception {
        final var mockResponse = new MockHttpServletResponse();
        final var mockRequest = new MockHttpServletRequest();
        mockRequest.setParameter(ThemeChangeInterceptor.DEFAULT_PARAM_NAME, MY_THEME);
        mockRequest.setParameter(LocaleChangeInterceptor.DEFAULT_PARAM_NAME, Locale.getDefault().getCountry());
        mockRequest.setParameter(CasProtocolConstants.PARAMETER_METHOD, HttpMethod.POST.name());

        final var servletExternalContext = mock(ServletExternalContext.class);
        when(servletExternalContext.getNativeRequest()).thenReturn(mockRequest);
        when(servletExternalContext.getNativeResponse()).thenReturn(mockResponse);

        final var mockRequestContext = new MockRequestContext();
        mockRequestContext.setExternalContext(servletExternalContext);

        final Service service = RegisteredServiceTestUtils.getService(MY_SERVICE);
        mockRequestContext.getFlowScope().put(CasProtocolConstants.PARAMETER_SERVICE, service);

        final var facebookClient = new FacebookClient(MY_KEY, MY_SECRET);
        final var twitterClient = new TwitterClient("3nJPbVTVRZWAyUgoUKQ8UA", "h6LZyZJmcW46Vu8R47MYfeXTSYGI30EqnWaSwVhFkbA");
        final var clients = new Clients(MY_LOGIN_URL, facebookClient, twitterClient);
        final var enforcer = mock(AuditableExecution.class);
        when(enforcer.execute(any())).thenReturn(new AuditableExecutionResult());

        final var ticketRegistry = new DefaultTicketRegistry();
        final var manager = new DelegatedClientWebflowManager(ticketRegistry,
            new DefaultTransientSessionTicketFactory(new HardTimeoutExpirationPolicy(60)),
            ThemeChangeInterceptor.DEFAULT_PARAM_NAME, LocaleChangeInterceptor.DEFAULT_PARAM_NAME,
            new WebApplicationServiceFactory(), "https://cas.example.org",
            new DefaultAuthenticationServiceSelectionPlan(new DefaultAuthenticationServiceSelectionStrategy()));
        final var ticket = manager.store(Pac4jUtils.getPac4jJ2EContext(mockRequest, new MockHttpServletResponse()), facebookClient);

        mockRequest.addParameter(DelegatedClientWebflowManager.PARAMETER_CLIENT_ID, ticket.getId());

        final var event = getDelegatedClientAction(facebookClient, service, clients, mockRequest).execute(mockRequestContext);
        assertEquals("error", event.getId());

        manager.retrieve(mockRequestContext, Pac4jUtils.getPac4jJ2EContext(mockRequest, new MockHttpServletResponse()), facebookClient);

        assertEquals(MY_THEME, mockRequest.getAttribute(ThemeChangeInterceptor.DEFAULT_PARAM_NAME));
        assertEquals(Locale.getDefault().getCountry(), mockRequest.getAttribute(LocaleChangeInterceptor.DEFAULT_PARAM_NAME));
        assertEquals(HttpMethod.POST.name(), mockRequest.getAttribute(CasProtocolConstants.PARAMETER_METHOD));
        final MutableAttributeMap flowScope = mockRequestContext.getFlowScope();
        final var urls =
            (Set<DelegatedClientAuthenticationAction.ProviderLoginPageConfiguration>)
                flowScope.get(DelegatedClientAuthenticationAction.PAC4J_URLS);

        assertFalse(urls.isEmpty());
        assertSame(2, urls.size());
    }

    @Test
    public void verifyFinishAuthentication() throws Exception {
        final var mockRequest = new MockHttpServletRequest();
        mockRequest.setParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER, "FacebookClient");

        mockRequest.addParameter(ThemeChangeInterceptor.DEFAULT_PARAM_NAME, MY_THEME);
        mockRequest.addParameter(LocaleChangeInterceptor.DEFAULT_PARAM_NAME, Locale.getDefault().getCountry());
        mockRequest.addParameter(CasProtocolConstants.PARAMETER_METHOD, HttpMethod.POST.name());
        final var service = CoreAuthenticationTestUtils.getService(MY_SERVICE);
        mockRequest.addParameter(CasProtocolConstants.PARAMETER_SERVICE, service.getId());

        final var servletExternalContext = mock(ServletExternalContext.class);
        when(servletExternalContext.getNativeRequest()).thenReturn(mockRequest);
        when(servletExternalContext.getNativeResponse()).thenReturn(new MockHttpServletResponse());

        final var mockRequestContext = new MockRequestContext();
        mockRequestContext.setExternalContext(servletExternalContext);

        final FacebookClient facebookClient = new FacebookClient() {
            @Override
            protected OAuth20Credentials retrieveCredentials(final WebContext context) {
                return new OAuth20Credentials("fakeVerifier");
            }
        };
        facebookClient.setName(FacebookClient.class.getSimpleName());
        final var clients = new Clients(MY_LOGIN_URL, facebookClient);

        final var event = getDelegatedClientAction(facebookClient, service, clients, mockRequest).execute(mockRequestContext);
        assertEquals("success", event.getId());
        assertEquals(MY_THEME, mockRequest.getAttribute(ThemeChangeInterceptor.DEFAULT_PARAM_NAME));
        assertEquals(Locale.getDefault().getCountry(), mockRequest.getAttribute(LocaleChangeInterceptor.DEFAULT_PARAM_NAME));
        assertEquals(HttpMethod.POST.name(), mockRequest.getAttribute(CasProtocolConstants.PARAMETER_METHOD));
        assertEquals(MY_SERVICE, mockRequest.getAttribute(CasProtocolConstants.PARAMETER_SERVICE));
        final MutableAttributeMap flowScope = mockRequestContext.getFlowScope();
        assertEquals(service.getId(), ((Service) flowScope.get(CasProtocolConstants.PARAMETER_SERVICE)).getId());
    }

    private ServicesManager getServicesManagerWith(final Service service, final BaseClient client) {
        final var mgr = mock(ServicesManager.class);
        final var regSvc = RegisteredServiceTestUtils.getRegisteredService(service.getId());

        final var strategy = new DefaultRegisteredServiceAccessStrategy();
        strategy.setDelegatedAuthenticationPolicy(new DefaultRegisteredServiceDelegatedAuthenticationPolicy(CollectionUtils.wrapList(client.getName())));
        regSvc.setAccessStrategy(strategy);
        when(mgr.findServiceBy(any(Service.class))).thenReturn(regSvc);

        return mgr;
    }

    private AbstractAction getDelegatedClientAction(final BaseClient client, final Service service, final Clients clients,
                                                    final MockHttpServletRequest mockRequest) {
        final TicketGrantingTicket tgt = new TicketGrantingTicketImpl(TGT_ID, mock(Authentication.class), mock(ExpirationPolicy.class));
        final var casImpl = mock(CentralAuthenticationService.class);
        when(casImpl.createTicketGrantingTicket(any())).thenReturn(tgt);

        final var transManager = mock(AuthenticationTransactionManager.class);
        final var authNManager = mock(AuthenticationManager.class);
        when(authNManager.authenticate(any(AuthenticationTransaction.class))).thenReturn(CoreAuthenticationTestUtils.getAuthentication());

        when(transManager.getAuthenticationManager()).thenReturn(authNManager);
        when(transManager.handle(any(AuthenticationTransaction.class), any(AuthenticationResultBuilder.class))).thenReturn(transManager);

        final var authnResult = mock(AuthenticationResult.class);
        when(authnResult.getAuthentication()).thenReturn(CoreAuthenticationTestUtils.getAuthentication());
        when(authnResult.getService()).thenReturn(service);

        when(support.getAuthenticationTransactionManager()).thenReturn(transManager);
        when(support.handleAndFinalizeSingleAuthenticationTransaction(any(), (Credential[]) any())).thenReturn(authnResult);

        final var enforcer = mock(AuditableExecution.class);
        when(enforcer.execute(any())).thenReturn(new AuditableExecutionResult());
        final var ticketRegistry = new DefaultTicketRegistry();
        final var manager = new DelegatedClientWebflowManager(ticketRegistry,
            new DefaultTransientSessionTicketFactory(new HardTimeoutExpirationPolicy(60)),
            ThemeChangeInterceptor.DEFAULT_PARAM_NAME, LocaleChangeInterceptor.DEFAULT_PARAM_NAME,
            new WebApplicationServiceFactory(),
            "https://cas.example.org",
            new DefaultAuthenticationServiceSelectionPlan(new DefaultAuthenticationServiceSelectionStrategy()));
        final var ticket = manager.store(Pac4jUtils.getPac4jJ2EContext(mockRequest, new MockHttpServletResponse()), client);

        mockRequest.addParameter(DelegatedClientWebflowManager.PARAMETER_CLIENT_ID, ticket.getId());
        final var initialResolver = mock(CasDelegatingWebflowEventResolver.class);
        when(initialResolver.resolveSingle(any())).thenReturn(new Event(this, "success"));

        return new DelegatedClientAuthenticationAction(
            initialResolver,
            mock(CasWebflowEventResolver.class),
            mock(AdaptiveAuthenticationPolicy.class),
            clients,
            getServicesManagerWith(service, client),
            enforcer,
            manager,
            new DelegatedSessionCookieManager(mock(CookieRetrievingCookieGenerator.class)),
            support);

    }
}
