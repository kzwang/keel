plugins {
  `java-library`
  id("kotlin-spring")
}

dependencies {
  api(project(":keel-api"))
  api(project(":keel-ec2-api"))
  implementation(project(":keel-core")) // TODO: ideally not
  implementation(project(":keel-clouddriver"))
  implementation(project(":keel-orca"))
  implementation(project(":keel-retrofit"))
  implementation(project(":keel-artifact"))
  implementation("com.netflix.spinnaker.kork:kork-core")
  implementation("com.netflix.spinnaker.kork:kork-web")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
  implementation("org.springframework:spring-context")
  implementation("org.springframework.boot:spring-boot-autoconfigure")
  implementation("com.netflix.frigga:frigga")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
  implementation("io.swagger.core.v3:swagger-annotations:2.1.2")

  testImplementation(project(":keel-test"))
  testImplementation(project(":keel-retrofit-test-support"))
  testImplementation("io.strikt:strikt-jackson")
  testImplementation("dev.minutest:minutest")
  testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
  testImplementation("org.funktionale:funktionale-partials")
  testImplementation("org.apache.commons:commons-lang3")
}
