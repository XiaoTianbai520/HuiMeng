package cn.huimeng.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Runtime validates migration checksums using DML credentials; DDL runs in a separate job. */
@Configuration
public class SchemaConfiguration {
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  ApplicationRunner validateSchema(DataSource dataSource) {
    return args -> Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().validate();
  }
}
