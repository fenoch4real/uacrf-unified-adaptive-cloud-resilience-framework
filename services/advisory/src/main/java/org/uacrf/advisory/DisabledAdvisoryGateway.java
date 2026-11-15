package org.uacrf.advisory;

final class DisabledAdvisoryGateway implements AdvisoryGateway {
  @Override
  public Outcome invoke(Request request) {
    throw new IllegalStateException("Advisory model invocation is disabled.");
  }
}
