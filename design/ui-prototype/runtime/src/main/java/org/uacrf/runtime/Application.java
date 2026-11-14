package org.uacrf.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("verify")) {
      PackageCheck.verify(java.nio.file.Path.of(args[1]));
      return;
    }
    if (args.length == 1 && args[0].equals("fingerprint")) {
      PackageCheck.fingerprint();
      return;
    }
    if (args.length > 0 && args[0].equals("admin")) {
      Admin.main(java.util.Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    if (args.length > 0 && args[0].equals("graph-worker")) {
      GraphWorker.main(java.util.Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    SpringApplication.run(Application.class, args);
  }

  @Bean
  Config config() throws Exception {
    return Config.load();
  }

  @Bean
  Database database(Config cfg) {
    var db = new Database(cfg);
    db.checkRole();
    return db;
  }

  @Bean
  Contracts contracts(Config cfg) throws Exception {
    var c = new Contracts(cfg.releaseRoot());
    c.verifyAll();
    return c;
  }

  @Bean(destroyMethod = "close")
  GraphService graphs(Config cfg, Database db) {
    var store = new GraphStore(GraphConfig.load(false), cfg.releaseRoot());
    try {
      store.driver.verifyConnectivity();
      store.assertConstraints();
      return new GraphService(cfg, db, store);
    } catch (Exception ex) {
      store.close();
      throw new IllegalStateException("Configured Neo4j unavailable");
    }
  }

  @Bean
  Identity.Source source(Config cfg) {
    return new Identity.Directory(cfg);
  }
}
