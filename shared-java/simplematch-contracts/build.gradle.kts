plugins {
  `java-library`
  alias(libs.plugins.protobuf)
}

java {
  withSourcesJar()
}

dependencies {
  api(libs.protobuf.java)
  api(libs.grpc.protobuf)
  api(libs.grpc.stub)
  compileOnly(libs.jakarta.annotation.api)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

sourceSets {
  main {
    proto {
      srcDir("../../proto")
      include("common.proto")
      include("orders.proto")
      include("matching.proto")
      include("marketdata.proto")
      include("account_service.proto")
      include("risk_service.proto")
      include("marketdata_service.proto")
    }
  }
}

protobuf {
  protoc {
    artifact = libs.protobuf.protoc.get().toString()
  }
  plugins {
    create("grpc") {
      artifact = libs.grpc.protoc.gen.java.get().toString()
    }
  }
  generateProtoTasks {
    all().configureEach {
      plugins {
        create("grpc")
      }
    }
  }
}
