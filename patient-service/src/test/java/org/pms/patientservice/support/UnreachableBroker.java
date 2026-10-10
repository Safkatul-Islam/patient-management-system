package org.pms.patientservice.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/** A Kafka bootstrap address on a local port nothing listens on: every connection is refused. */
public final class UnreachableBroker {

  private UnreachableBroker() {}

  /** Binds a free loopback port once to pick it, then releases it. */
  public static String address() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return "127.0.0.1:" + socket.getLocalPort();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
