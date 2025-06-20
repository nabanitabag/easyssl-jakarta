package com.github.dtreskunov.easyssl;

import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.SslStoreProvider;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.Filter;

/**
 * Defines Spring beans that are used for mutual SSL. They are:
 * <ol>
 * <li>{@link #easySslContext} - may be used to configure an SSL-using {@link RestTemplate}</li>
 * <li>{@link #easySslClientCertificateCheckingFilter} - checks that client's certificate has not been revoked</li>
 * <li>{@link #easySslServletContainerCustomizer} - used by Spring Boot to configure Jetty/Tomcat/Undertow to use SSL with client cert auth</li>
 * <li>{@code local.server.protocol} - environment property injectable into managed beans using {@code @Value}</li>
 * </ol>
 */
@Configuration
@Import(EasySslProperties.EasySslPropertiesConfiguration.class)
@ConditionalOnProperty(value = "easyssl.enabled", matchIfMissing = true)
public class EasySslBeans {

    private static final String PROTOCOL_PROPERTY = "local.server.protocol";

    @ConditionalOnProperty(value = "easyssl.enabled", matchIfMissing = true)
    public static @interface ConditionalOnEnabled {}

    @ConditionalOnEnabled
    @ConditionalOnWebApplication
    @ConditionalOnProperty(value = "easyssl.serverCustomizationEnabled", matchIfMissing = true)
    public static @interface ConditionalOnServerCustomizationEnabled {}

    /**
     * Only used by test code
     */
    public static SSLContext getSSLContext(EasySslProperties config) throws Exception {
        EasySslHelper helper = new EasySslHelper(config);
        return helper.getSSLContext();
    }

    @Bean
    @ConditionalOnEnabled
    public EasySslHelper easySslHelper(EasySslProperties config) throws Exception {
        return new EasySslHelper(config);
    }

    @Bean
    @ConditionalOnEnabled
    public SSLContext easySslContext(EasySslHelper helper) throws Exception {
        return helper.getSSLContext();
    }

    @Bean
    @ConditionalOnServerCustomizationEnabled
    public Filter easySslClientCertificateCheckingFilter(EasySslHelper helper) throws Exception {
        return new ClientCertificateCheckingFilter(helper.getTrustManager());
    }

    @Bean
    @ConditionalOnServerCustomizationEnabled
    public WebServerFactoryCustomizer<ConfigurableWebServerFactory> easySslServletContainerCustomizer(EasySslProperties config, EasySslHelper helper, @Autowired(required = false) ServerProperties serverProperties) throws Exception {
        final SslStoreProvider storeProvider = helper.getSslStoreProvider();
        final Ssl sslProperties = EasySslHelper.getSslProperties(config, serverProperties);

        return factory -> {
            factory.setSslStoreProvider(storeProvider);
            factory.setSsl(sslProperties);
        };
    }

    @Bean
    @ConditionalOnServerCustomizationEnabled
    @ConditionalOnClass(name = "io.undertow.Undertow")
    public EasySslUndertowCustomizer easySslUndertowCustomizer() throws Exception {
        return new EasySslUndertowCustomizer();
    }

    @Bean
    @ConditionalOnServerCustomizationEnabled
    @ConditionalOnClass(name = "org.apache.catalina.connector.Connector")
    public EasySslTomcatCustomizer easySslTomcatCustomizer() throws Exception {
        return new EasySslTomcatCustomizer();
    }

    /**
     * Jetty 9.4.15 started doing additional checks on client certificates. This bit of code reverts to the old behavior.
     *
     * See <a href="https://github.com/eclipse/jetty.project/issues/3454">eclipse/jetty.project#3454</a>
     */
    @Bean
    @ConditionalOnServerCustomizationEnabled
    @ConditionalOnClass(name = "org.eclipse.jetty.server.Server")
    public EasySslJettyCustomizer easySslJettyCustomizer() {
        return new EasySslJettyCustomizer();
    }

    @Autowired
    public void setProtocolEnvironmentProperty(ApplicationContext context, @Autowired(required = false) EasySslProperties config) {
        if (config != null && config.isEnabled() && config.isServerCustomizationEnabled()) {
            setEnvironmentProperty(context, PROTOCOL_PROPERTY, "https");
        } else {
            setEnvironmentProperty(context, PROTOCOL_PROPERTY, "http");
        }
    }

    @SuppressWarnings("unchecked")
    private void setEnvironmentProperty(ApplicationContext context, String propertyName, String propertyValue) {
        if (!(context instanceof ConfigurableApplicationContext)) {
            return;
        }
        ConfigurableEnvironment environment = ((ConfigurableApplicationContext) context).getEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> source = sources.get("easyssl");
        if (source == null) {
            source = new MapPropertySource("easyssl", new HashMap<String, Object>());
            sources.addFirst(source);
        }
        ((Map<String, Object>) source.getSource()).put(propertyName, propertyValue);
    }
}
