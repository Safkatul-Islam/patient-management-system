package org.pms.apigateway.support;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A size-bounded dump of the threads that matter when a reactive request stalls: the Reactor Netty
 * event loops ({@code reactor-http-nio-*} on Windows, {@code reactor-http-epoll-*} on Linux), the
 * {@code boundedElastic} workers, and any thread currently inside the JWKS fetch or key lookup.
 * Each thread shows its state, the lock it waits on (and its owner) and its top frames; an idle
 * event loop is one line.
 */
public final class ThreadDumps {

  private static final String EVENT_LOOP_PREFIX = "reactor-http-";
  private static final String BOUNDED_ELASTIC = "boundedElastic";
  private static final String JWKS_PACKAGE = "org.pms.apigateway.security.jwks.";
  private static final int MAX_THREADS = 40;
  private static final int MAX_FRAMES = 20;
  private static final int MAX_CHARS = 16_000;

  /** How deep below the top of the stack the selector wait may sit. */
  private static final int IDLE_SELECTOR_DEPTH = 5;

  private ThreadDumps() {}

  /** The filtered dump, cut at {@value #MAX_CHARS} characters. */
  public static String eventLoopsAndJwks() {
    ThreadInfo[] all = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
    List<ThreadInfo> relevant =
        Arrays.stream(all)
            .filter(ThreadDumps::isRelevant)
            .sorted(Comparator.comparing(ThreadInfo::getThreadName))
            .toList();
    StringBuilder out = new StringBuilder();
    out.append(relevant.size()).append(" relevant of ").append(all.length).append(" threads\n");
    relevant.stream().limit(MAX_THREADS).forEach(info -> append(out, info));
    if (relevant.size() > MAX_THREADS) {
      out.append("... ").append(relevant.size() - MAX_THREADS).append(" more threads omitted\n");
    }
    return out.length() <= MAX_CHARS
        ? out.toString()
        : out.substring(0, MAX_CHARS) + "\n... dump cut at " + MAX_CHARS + " characters";
  }

  private static boolean isRelevant(ThreadInfo info) {
    String name = info.getThreadName();
    return name.startsWith(EVENT_LOOP_PREFIX)
        || name.contains(BOUNDED_ELASTIC)
        || Arrays.stream(info.getStackTrace())
            .anyMatch(frame -> frame.getClassName().startsWith(JWKS_PACKAGE));
  }

  /**
   * An event loop parked in NIO select (any OS) or native epoll, with nothing of its own on the
   * stack: printed as one line so the size bound is spent on threads doing work.
   */
  private static boolean isIdleSelector(StackTraceElement[] frames) {
    return Arrays.stream(frames)
        .limit(IDLE_SELECTOR_DEPTH)
        .anyMatch(
            frame ->
                (frame.getClassName().endsWith("SelectorImpl")
                        && frame.getMethodName().equals("select"))
                    || frame.getMethodName().startsWith("epollWait"));
  }

  private static void append(StringBuilder out, ThreadInfo info) {
    out.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
    if (info.getLockName() != null) {
      out.append(" on ").append(info.getLockName());
      if (info.getLockOwnerName() != null) {
        out.append(" owned by \"").append(info.getLockOwnerName()).append('"');
      }
    }
    StackTraceElement[] frames = info.getStackTrace();
    if (isIdleSelector(frames)) {
      out.append(" (idle, waiting in its selector)\n");
      return;
    }
    out.append('\n');
    for (int i = 0; i < Math.min(MAX_FRAMES, frames.length); i++) {
      out.append("    at ").append(frames[i]).append('\n');
    }
    if (frames.length > MAX_FRAMES) {
      out.append("    ... ").append(frames.length - MAX_FRAMES).append(" more frames\n");
    }
  }
}
