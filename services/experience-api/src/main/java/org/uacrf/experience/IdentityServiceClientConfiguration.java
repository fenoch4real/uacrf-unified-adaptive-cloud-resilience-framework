package org.uacrf.experience;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class IdentityServiceClientConfiguration {
  @Bean
  RestClient identityServiceRestClient(
      @Value("${uacrf.experience.identity.base-url}") String baseUrl) {
    return restClient(baseUrl);
  }

  @Bean
  RestClient identityDelegationRestClient(
      @Value("${uacrf.experience.identity.internal-base-url}") String baseUrl) {
    return restClient(baseUrl);
  }

  private static RestClient restClient(String baseUrl) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}
