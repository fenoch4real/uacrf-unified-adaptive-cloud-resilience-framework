package org.uacrf.advisory;

import java.net.URI;

final class AzureFoundryGateway implements AdvisoryGateway {
  private final OpenAiCompatibleGateway chat;

  AzureFoundryGateway(URI endpoint, String bearerToken) {
    chat = new OpenAiCompatibleGateway(endpoint, bearerToken, true);
  }

  @Override
  public Outcome invoke(Request request) {
    return chat.invoke(request);
  }
}
