package com.simplematch.queryservice.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisQueryReadCacheTest {
  @Mock private RedisTemplate<String, byte[]> template;
  @Mock private Cursor<String> cursor;
  @Mock private ValueOperations<String, byte[]> values;
  private RedisQueryReadCache cache;

  @BeforeEach
  void createCache() {
    cache = new RedisQueryReadCache(template, new ObjectMapper(), "query:v1");
  }

  @Test
  void clearingScansOnlyItsLiteralNamespaceAndClosesCursor() {
    when(template.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(cursor.hasNext()).thenReturn(true, true, false);
    when(cursor.next()).thenReturn("query:v1:order:1", "query:v1:order:2");

    cache.clear();

    final org.mockito.ArgumentCaptor<ScanOptions> options =
        org.mockito.ArgumentCaptor.forClass(ScanOptions.class);
    verify(template).scan(options.capture());
    assertThat(options.getValue().getPattern()).isEqualTo("query:v1:*");
    verify(template).delete(List.of("query:v1:order:1", "query:v1:order:2"));
    verify(template, never()).keys(any());
    verify(cursor).close();
  }

  @Test
  void clearingRejectsAnOutOfNamespaceResultWithoutDeletingIt() {
    when(template.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(cursor.hasNext()).thenReturn(true);
    when(cursor.next()).thenReturn("other-service:secret");

    assertThatThrownBy(cache::clear).isInstanceOf(IllegalArgumentException.class);

    verify(template, never()).delete(anyCollection());
    verify(cursor).close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"other-service:secret", "query:v10:order:1", "query:v1"})
  void rejectsKeysOutsideItsNamespaceBeforeReadingOrWriting(String key) {
    assertThatThrownBy(() -> cache.get(key)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cache.put(key, "value")).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(template);
  }

  @ParameterizedTest
  @ValueSource(strings = {"query:*", "query:?", "query:[a-z]", "query:\\", " "})
  void rejectsPrefixesWithRedisPatternSyntax(String prefix) {
    assertThatThrownBy(() -> new RedisQueryReadCache(template, new ObjectMapper(), prefix))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedJsonIsEvictedAndTreatedAsAMiss() {
    when(template.opsForValue()).thenReturn(values);
    when(values.get("query:v1:order:1"))
        .thenReturn("invalid JSON".getBytes(StandardCharsets.UTF_8));

    assertThat(cache.get("query:v1:order:1")).isEmpty();

    verify(template).delete("query:v1:order:1");
  }
}
