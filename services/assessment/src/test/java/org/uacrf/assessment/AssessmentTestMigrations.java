package org.uacrf.assessment;

import java.net.URI;
import java.nio.file.Path;

final class AssessmentTestMigrations {
  private AssessmentTestMigrations() {}

  static String location() {
    // Isolate S07 migrations from those in the test-only advisory dependency.
    return "filesystem:" + Path.of(URI.create(AssessmentApplication.class.getProtectionDomain()
        .getCodeSource().getLocation().toString())).resolve("db/migration");
  }
}
