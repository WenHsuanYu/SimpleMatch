package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/** Verifies configured FIX identities fit the Gateway-owned JDBC component columns. */
class QuickFixAcceptorLifecycleTest {
  @TempDir Path directory;

  @Test
  void refusesPlaintextBeforeConstructingTheAcceptor() throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    assertThatThrownBy(lifecycle(settings("SocketUseSSL=N"), loaded)::start)
        .hasRootCauseMessage("FIX requires SocketUseSSL=Y and NeedClientAuth=Y");
    assertThat(loaded.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SocketKeyStore",
        "SocketKeyStorePassword",
        "SocketTrustStore",
        "SocketTrustStorePassword"
      })
  void refusesMissingExplicitTlsMaterialBeforeConstructingTheAcceptor(String setting)
      throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    assertThatThrownBy(lifecycle(settings(setting + "="), loaded)::start)
        .hasRootCauseMessage("FIX requires explicit " + setting);
    assertThat(loaded.get()).isNull();
  }

  @Test
  void refusesDisablingClientCertificateAuthenticationForAnIndividualSession() throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    assertThatThrownBy(lifecycle(settings("NeedClientAuth=N"), loaded)::start)
        .hasRootCauseMessage("FIX requires SocketUseSSL=Y and NeedClientAuth=Y");
    assertThat(loaded.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refusesRawUnknownSessionLoggingWhetherEnabledOrImplicit(boolean explicit) throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    final String config =
        settings("")
            .replace(
                "LogMessageWhenSessionNotFound=N\n",
                explicit ? "LogMessageWhenSessionNotFound=Y\n" : "");
    assertThatThrownBy(lifecycle(config, loaded)::start)
        .hasRootCauseMessage("FIX unknown-session raw logging must be explicitly disabled");
    assertThat(loaded.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"SocketKeyStore", "SocketTrustStore"})
  void nonexistentTlsStoresCannotFallBackToImplicitTrust(String setting) throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    assertThatThrownBy(
            lifecycle(settings(setting + "=" + directory.resolve("missing.p12")), loaded)::start)
        .hasRootCauseMessage("FIX requires readable file for " + setting);
    assertThat(loaded.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SenderCompID",
        "SenderSubID",
        "SenderLocationID",
        "TargetCompID",
        "TargetSubID",
        "TargetLocationID",
        "SessionQualifier"
      })
  void acceptsEachComponentAtItsDatabaseCapacity(String component) throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    final QuickFixAcceptorLifecycle lifecycle =
        lifecycle(settings(component + "=" + "S".repeat(64)), loaded);

    lifecycle.start();

    assertThat(lifecycle.isRunning()).isTrue();
    assertThat(loaded.get().getString(loaded.get().sectionIterator().next(), component))
        .hasSize(64);
    lifecycle.stop();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SenderCompID",
        "SenderSubID",
        "SenderLocationID",
        "TargetCompID",
        "TargetSubID",
        "TargetLocationID",
        "SessionQualifier"
      })
  void rejectsEachOversizedComponentBeforeCreatingAcceptor(String component) throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    final QuickFixAcceptorLifecycle lifecycle =
        lifecycle(settings(component + "=" + "S".repeat(65)), loaded);

    assertThatThrownBy(lifecycle::start)
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage(component + " must contain at most 64 characters");
    assertThat(loaded.get()).isNull();
    assertThat(lifecycle.isRunning()).isFalse();
  }

  @Test
  void beginStringUsesItsEightCharacterDatabaseCapacity() throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    final QuickFixAcceptorLifecycle valid = lifecycle(settings("BeginString=FIX.4.40"), loaded);
    valid.start();
    assertThat(valid.isRunning()).isTrue();
    valid.stop();

    final QuickFixAcceptorLifecycle invalid = lifecycle(settings("BeginString=FIX.4.400"), loaded);
    assertThatThrownBy(invalid::start)
        .hasRootCauseMessage("BeginString must contain at most 8 characters");
  }

  @Test
  void validatesAllSessionsIncludingInheritedDefaultComponents() throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    final String config =
        """
        [DEFAULT]
        BeginString=FIX.4.4
        SenderCompID=SIMPLEMATCH
        TargetCompID=CLIENT
        SenderLocationID=%s
        [SESSION]
        TargetCompID=CLIENT-A
        SenderLocationID=LOCAL
        [SESSION]
        TargetCompID=CLIENT-B
        """
            .formatted("L".repeat(65));

    assertThatThrownBy(lifecycle(config, loaded)::start)
        .hasRootCauseMessage("SenderLocationID must contain at most 64 characters");
    assertThat(loaded.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"BeginString", "SenderCompID", "TargetCompID"})
  void requiredIdentityComponentsCannotBeEmpty(String component) throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    assertThatThrownBy(lifecycle(settings(component + "="), loaded)::start)
        .hasRootCauseMessage(component + " must not be empty");
    assertThat(loaded.get()).isNull();
  }

  @Test
  void acceptsACompleteConfiguredIdentityLongerThan256Characters() throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    final String component = "S".repeat(64);
    final String overrides =
        """
        SenderCompID=%1$s
        SenderSubID=%1$s
        SenderLocationID=%1$s
        TargetCompID=%1$s
        TargetSubID=%1$s
        TargetLocationID=%1$s
        SessionQualifier=%1$s
        """
            .formatted(component);
    final QuickFixAcceptorLifecycle lifecycle = lifecycle(settings(overrides), loaded);
    lifecycle.start();
    assertThat(loaded.get().sectionIterator().next().toString().length()).isGreaterThan(256);
    lifecycle.stop();
  }

  @Test
  void shortCurrentSettingsAndEmptyOptionalComponentsRemainValid() throws Exception {
    final AtomicReference<SessionSettings> loaded = new AtomicReference<>();
    final QuickFixAcceptorLifecycle lifecycle = lifecycle(settings(""), loaded);
    lifecycle.start();
    assertThat(loaded.get().sectionIterator().next().getSenderSubID()).isEmpty();
    assertThat(loaded.get().sectionIterator().next().getSessionQualifier()).isEmpty();
    lifecycle.stop();
  }

  private QuickFixAcceptorLifecycle lifecycle(
      String config, AtomicReference<SessionSettings> loaded) throws Exception {
    final Path configPath = Files.createTempFile(directory, "quickfix-", ".cfg");
    Files.writeString(configPath, config);
    final QuickFixGatewayRuntime runtime =
        new QuickFixGatewayRuntime("test", configPath, directory.resolve("wal"), "gateway-test");
    return new QuickFixAcceptorLifecycle(
        null,
        runtime,
        (application, settings) -> {
          loaded.set(settings);
          return mock(SocketAcceptor.class);
        });
  }

  private String settings(String override) throws java.io.IOException {
    final Path store = directory.resolve("tls-store.p12");
    Files.write(store, new byte[0]);
    return """
    [DEFAULT]
    BeginString=FIX.4.4
    SenderCompID=SIMPLEMATCH
    TargetCompID=CLIENT
        LogMessageWhenSessionNotFound=N
    SocketUseSSL=Y
    NeedClientAuth=Y
        SocketKeyStore=%1$s
    SocketKeyStorePassword=test-password
        SocketTrustStore=%1$s
    SocketTrustStorePassword=test-password
    [SESSION]
        %2$s
    """
        .formatted(store, override);
  }
}
