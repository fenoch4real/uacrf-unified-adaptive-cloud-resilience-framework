package org.uacrf.edge;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class ObjectivesServiceRouteConfiguration {
  @Bean
  RestClient objectivesServiceRestClient(@Value("${uacrf.edge.objectives.base-url}") String baseUrl) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}
