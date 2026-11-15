package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InputPacketTests {
  @Test
  void retainsExactBytesDefensivelyAndHashesThem() {
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
    var packet = new InputPacket(bytes);
    bytes[0] = 0;
    packet.bytes()[1] = 0;
    assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), packet.bytes());
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        packet.bytesDigest());
  }

  @Test
  void enforcesBoundariesOnBytesNotCharacters() {
    assertEquals(InputPacket.MAX_BYTES, new InputPacket(new byte[InputPacket.MAX_BYTES]).bytes().length);
    assertThrows(IllegalArgumentException.class, () -> new InputPacket(new byte[InputPacket.MAX_BYTES + 1]));
    assertThrows(IllegalArgumentException.class, () -> new InputPacket(new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> new InputPacket(null));
  }
}
