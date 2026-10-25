package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 把后端分层作为可执行约束，防止后续接口重新将 SQL 或持久实体暴露到控制器。 */
class ArchitectureTests {
  private List<Class<?>> classes(String relativePackage) throws Exception {
    Path root = Path.of("src/main/java/cn/huimeng", relativePackage.replace('.', '/'));
    try (var files = Files.list(root)) {
      return files
          .filter(file -> file.toString().endsWith(".java"))
          .map(
              file ->
                  "cn.huimeng."
                      + relativePackage
                      + "."
                      + file.getFileName().toString().replace(".java", ""))
          .<Class<?>>map(
              name -> {
                try {
                  return Class.forName(name);
                } catch (ClassNotFoundException ex) {
                  throw new IllegalStateException(ex);
                }
              })
          .toList();
    }
  }

  @Test
  void controllersDependOnServiceContractsAndReturnExplicitHttpModels() throws Exception {
    for (Class<?> type : classes("controller")) {
      for (var field : type.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) continue;
        assertThat(field.getType().isInterface())
            .as(type.getName() + "." + field.getName())
            .isTrue();
        assertThat(field.getType().getPackageName()).isEqualTo("cn.huimeng.service");
      }
      for (var method : type.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers())) continue;
        assertThat(method.getReturnType())
            .as(type.getName() + "." + method.getName())
            .isNotEqualTo(Object.class)
            .isNotEqualTo(Map.class);
        assertThat(method.getGenericReturnType().getTypeName()).doesNotContain("cn.huimeng.dao.");
      }
    }
  }

  @Test
  void jdbcAccessStaysInMappersAndServiceImplementationsUseContracts() throws Exception {
    for (String layer : List.of("controller", "service", "security")) {
      try (var files = Files.walk(Path.of("src/main/java/cn/huimeng", layer))) {
        for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          assertThat(Files.readString(file))
              .as(file.toString())
              .doesNotContain("org.springframework.jdbc", "java.sql.");
        }
      }
    }
    for (Class<?> contract : classes("service")) {
      assertThat(contract.isInterface()).as(contract.getName()).isTrue();
      Class<?> implementation =
          Class.forName("cn.huimeng.service.impl." + contract.getSimpleName() + "Impl");
      assertThat(contract.isAssignableFrom(implementation)).isTrue();
    }
  }
}
