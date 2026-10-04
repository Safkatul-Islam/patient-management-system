package org.pms.apigateway.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends one HTTP/1.1 request over a plain socket with the request line exactly as given, so no
 * client normalizes or re-encodes the path before the gateway sees it.
 */
public final class RawHttp {

  /** Status, header names lower-cased (last value wins), and the body as ISO-8859-1 text. */
  public record Response(int status, Map<String, String> headers, String body) {}

  private RawHttp() {}

  public static Response send(int port, String method, String rawPath, Map<String, String> headers)
      throws IOException {
    StringBuilder request = new StringBuilder();
    request.append(method).append(' ').append(rawPath).append(" HTTP/1.1\r\n");
    request.append("Host: 127.0.0.1:").append(port).append("\r\n");
    request.append("Connection: close\r\n");
    headers.forEach(
        (name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
    if ("POST".equals(method)) {
      request.append("Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
    } else {
      request.append("\r\n");
    }
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      OutputStream out = socket.getOutputStream();
      out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return parse(readAll(socket.getInputStream()));
    }
  }

  private static Response parse(String raw) {
    int headerEnd = raw.indexOf("\r\n\r\n");
    String head = headerEnd < 0 ? raw : raw.substring(0, headerEnd);
    String body = headerEnd < 0 ? "" : raw.substring(headerEnd + 4);
    String[] lines = head.split("\r\n");
    int status = Integer.parseInt(lines[0].split(" ")[1]);
    Map<String, String> parsed = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon > 0) {
        parsed.put(
            lines[i].substring(0, colon).trim().toLowerCase(),
            lines[i].substring(colon + 1).trim());
      }
    }
    return new Response(status, parsed, body);
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = in.read(buffer)) != -1) {
      bytes.write(buffer, 0, read);
    }
    return bytes.toString(StandardCharsets.ISO_8859_1);
  }
}
