package com.simplematch.tools.riskmatchinge2e;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * An immutable wrapper around a byte array.
 *
 * <p>This class provides value-based equality for byte sequences. The underlying byte array is
 * defensively copied when constructing an instance and when retrieving its contents, preventing
 * external modification of the internal state.
 */
public final class Bytes {
  private final byte[] value;

  /**
   * Creates a new {@code Bytes} instance containing a copy of the given byte array.
   *
   * @param value the byte array to wrap
   */
  public Bytes(byte[] value) {
    this.value = Objects.requireNonNull(value, "value is required").clone();
  }

  /**
   * Returns a copy of the underlying byte array.
   *
   * <p>Modifying the returned array does not affect this instance.
   *
   * @return a copy of the underlying byte array
   */
  public byte[] toByteArray() {
    return value.clone();
  }

  /**
   * Returns the byte sequence encoded as a Base64 string.
   *
   * @return the Base64-encoded representation of the underlying byte sequence
   */
  public String toBase64() {
    return Base64.getEncoder().encodeToString(value);
  }

  /**
   * Returns the byte sequence encoded as a Base64 string.
   *
   * @return the Base64-encoded representation of the underlying byte sequence
   */
  @Override
  public String toString() {
    return toBase64();
  }

  /**
   * Compares this instance with another object for value equality.
   *
   * <p>Two {@code Bytes} instances are equal if their underlying byte arrays contain the same bytes
   * in the same order.
   *
   * @param obj the object to compare with
   * @return {@code true} if the objects contain the same byte sequence; otherwise {@code false}
   */
  @Override
  public boolean equals(Object obj) {
    return obj instanceof Bytes other
        && Arrays.equals(value, other.value);
  }

  /**
   * Returns a hash code based on the contents of the underlying byte array.
   *
   * @return the hash code for this byte sequence
   */
  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  /**
   * Returns the length of the underlying byte array.
   *
   * @return the number of bytes in the underlying byte array
   */
  public int length() {
    return value.length;
  }
}