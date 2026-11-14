# Java implementation transition

Version 1.0 · Runtime revision 2

Java 25 and Spring Boot 4.1.2 replace the Python backend in the working release. All 23 routes retain their contract and SQL boundary. Sixteen Java tests have executed through real HTTP and PostgreSQL. The current evidence belongs to Java; it is not inherited from the old Python suite.

The old runtime files removed are: bootstrap.py, server.py, verify_package.py, fingerprint.py, test_runtime.py, install.py, requirements.txt, requirements.lock.txt. No Python runtime dependency is required to build or run this API. Project-level Python document and historical verification utilities remain outside this runtime; they are not the application implementation. Preserved historical ZIPs retain their original contents.

Use a fresh isolated database when reproducing this release. There is no automatic live database upgrade or deployment cutover in this package. Review the unchanged SQL with the Java JDBC transaction wrapper, build and run the tests, configure approved issuer/source keys, verify bootstrap, and then start the API with restricted credentials. Never run the serving process with DBA credentials.

Old continuation cursors are intentionally invalid after the Java cutover. Stored PostgreSQL canonical request digests remain unchanged. Java and Node verify the same database-produced fingerprint bytes. Request/response contracts and synchronous relationship receipts remain unchanged. The UI is still an illustrative prototype and does not call this server.

The remaining 64 canonical and 16 proposed operations, provider-native adapters, objective/import workers, connected UI and full-system acceptance remain open. No production-readiness or live-provider claim follows from the port.
