package de.palsoftware.yvoke.shared.config;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Value("${rest-client.connect-timeout-ms}")
    private int connectTimeout;

    @Value("${rest-client.read-timeout-ms}")
    private int readTimeout;

    /** Default pooled client for trusted internal integrations (LLM providers, Voyage, …). */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestFactory(pooledFactory(true));
    }

    /**
     * Confluence-only client with automatic redirect following DISABLED (SEC-11). The SSRF guard in
     * {@code ConfluenceClientService} validates each target host's resolved IP before the request,
     * but an HTTP 3xx would let the remote server bounce the connection to an internal / cloud-
     * metadata address without that host ever being re-checked. Refusing to follow redirects closes
     * that bypass; the Confluence REST endpoints we call return their payloads directly and
     * paginate via {@code _links.next}, so they never need transparent redirects.
     */
    @Bean
    public RestClient.Builder confluenceRestClientBuilder() {
        return RestClient.builder().requestFactory(pooledFactory(false));
    }

    /**
     * Dedicated pools for the two retrieval-path clients (PRF-05). Embedding and reranking both hit
     * the same Voyage host, so on the shared pool a burst of embedding calls could exhaust the
     * per-route connections and starve reranking. Giving each its own connection manager keeps the
     * two independent — reranking never waits behind embeddings.
     */
    @Bean
    public RestClient.Builder embeddingRestClientBuilder() {
        return RestClient.builder().requestFactory(pooledFactory(true));
    }

    @Bean
    public RestClient.Builder rerankRestClientBuilder() {
        return RestClient.builder().requestFactory(pooledFactory(true));
    }

    private HttpComponentsClientHttpRequestFactory pooledFactory(boolean followRedirects) {
        ConnectionConfig connectionConfig =
            ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setSocketTimeout(Timeout.ofMilliseconds(readTimeout)).build();

        HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder
            .create().setDefaultConnectionConfig(connectionConfig).setMaxConnTotal(20)
            .setMaxConnPerRoute(10).build();

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout)).build();

        HttpClientBuilder httpClientBuilder = HttpClients.custom()
            .setConnectionManager(connectionManager).setDefaultRequestConfig(requestConfig);
        if (!followRedirects) {
            httpClientBuilder.disableRedirectHandling();
        }
        CloseableHttpClient httpClient = httpClientBuilder.build();

        HttpComponentsClientHttpRequestFactory factory =
            new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
