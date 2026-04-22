plugins {
  `java-library`
  id("com.google.protobuf")
}

dependencies {
  api("com.google.protobuf:protobuf-java:4.31.1")
  api("io.grpc:grpc-protobuf:1.80.0")
  api("io.grpc:grpc-stub:1.80.0")
  compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")
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
    artifact = "com.google.protobuf:protoc:4.31.1"
  }
  plugins {
    create("grpc") {
      artifact = "io.grpc:protoc-gen-grpc-java:1.80.0"
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