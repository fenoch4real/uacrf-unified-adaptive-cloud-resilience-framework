package org.uacrf.identity;

import java.util.Optional;

/** Server-held connection to a trusted provider's source directory. */
interface TrustedDirectory {
  /** Returns the verified directory entry, or empty when the source has no such subject. */
  Optional<DirectoryPrincipal> find(String issuerUri, String subjectRef);

  record DirectoryPrincipal(String subject, String displayName, String kind, boolean enabled) {}

  final class UnavailableException extends RuntimeException {
    UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
