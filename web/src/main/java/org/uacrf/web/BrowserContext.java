package org.uacrf.web;

record BrowserContext(String tenantId, String tenantName, String environmentId, String environmentName) {
  static final String SESSION_KEY = BrowserContext.class.getName();
}
