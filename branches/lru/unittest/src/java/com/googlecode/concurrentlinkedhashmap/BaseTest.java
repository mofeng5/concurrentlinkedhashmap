package com.googlecode.concurrentlinkedhashmap;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap.Builder;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap.Node;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;

import java.io.Serializable;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Base utilities for testing purposes. This is ugly, but it makes the tests
 * easier to read by moving the junk utilities here.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class BaseTest extends Assert {
  protected final EvictionMonitor<Integer, Integer> guard = EvictionMonitor.newGuard();
  protected Validator validator;
  protected final int capacity;
  protected boolean debug;

  protected BaseTest() {
    this(intProperty("singleThreaded.maximumCapacity"));
  }

  protected BaseTest(int capacity) {
    this.capacity = capacity;
  }

  /**
   * Initializes the test with runtime properties.
   */
  @BeforeClass(alwaysRun = true)
  public void before() {
    validator = new Validator(booleanProperty("test.exhaustive"));
    debug = booleanProperty("test.debugMode");
    info("\nRunning %s...\n", getClass().getSimpleName());
  }

  protected static int intProperty(String property) {
    return Integer.valueOf(System.getProperty(property));
  }

  protected static boolean booleanProperty(String property) {
    return Boolean.valueOf(System.getProperty(property));
  }

  /* ---------------- Logging methods -------------- */

  protected void info(String message, Object... args) {
    System.out.printf(message, args);
    System.out.println();
  }

  protected void debug(String message, Object... args) {
    if (debug) {
      info(message, args);
    }
  }

  /* ---------------- Factory methods -------------- */

  <K, V> Builder<K, V> builder() {
    return new Builder<K, V>().maximumWeightedCapacity(capacity);
  }

  protected <K, V> ConcurrentLinkedHashMap<K, V> create() {
    return create(capacity);
  }

  protected <K, V> ConcurrentLinkedHashMap<K, V> create(int size) {
    return new Builder<K, V>()
        .maximumWeightedCapacity(size)
        .build();
  }

  protected <K, V> ConcurrentLinkedHashMap<K, V> createGuarded() {
    return create(EvictionMonitor.<K, V>newGuard());
  }

  protected <K, V> ConcurrentLinkedHashMap<K, V> create(EvictionListener<K, V> listener) {
    return create(capacity, listener);
  }

  protected <K, V> ConcurrentLinkedHashMap<K, V> create(int size, EvictionListener<K, V> listener) {
    return new Builder<K, V>()
        .maximumWeightedCapacity(size)
        .listener(listener)
        .build();
  }

  /* ---------------- Warming methods -------------- */

  protected ConcurrentLinkedHashMap<Integer, Integer> createWarmedMap() {
    return createWarmedMap(capacity);
  }

  protected ConcurrentLinkedHashMap<Integer, Integer> createWarmedMap(
      EvictionListener<Integer, Integer> listener) {
    return createWarmedMap(capacity, listener);
  }

  protected ConcurrentLinkedHashMap<Integer, Integer> createWarmedMap(
      int size, EvictionListener<Integer, Integer> listener) {
    ConcurrentLinkedHashMap<Integer, Integer> map = create(size, listener);
    warmUp(map, 0, size);
    return map;
  }

  protected ConcurrentLinkedHashMap<Integer, Integer> createWarmedMap(int size) {
    ConcurrentLinkedHashMap<Integer, Integer> map = create(size);
    warmUp(map, 0, size);
    return map;
  }

  protected void warmUp(Map<Integer, Integer> map, int start, int end) {
    for (Integer i = start; i < end; i++) {
      assertNull(map.put(i, -i));
    }
  }

  /* ---------------- Listener support -------------- */

  /**
   * A listener that either aggregates the results or fails if invoked.
   */
  protected static final class EvictionMonitor<K, V>
      implements EvictionListener<K, V>, Serializable {

    private static final long serialVersionUID = 1L;
    final Collection<Entry<K, V>> evicted;
    final boolean isAllowed;

    private EvictionMonitor(boolean isAllowed) {
      this.isAllowed = isAllowed;
      this.evicted = new ConcurrentLinkedQueue<Entry<K, V>>();
    }

    public static <K, V> EvictionMonitor<K, V> newMonitor() {
      return new EvictionMonitor<K, V>(true);
    }

    public static <K, V> EvictionMonitor<K, V> newGuard() {
      return new EvictionMonitor<K, V>(false);
    }

    public void onEviction(K key, V value) {
      if (!isAllowed) {
        throw new IllegalStateException("Eviction should not have occured");
      }
      evicted.add(new SimpleImmutableEntry<K, V>(key, value));
    }
  }

  /* ---------------- Node List support -------------- */

  protected static String listForwardToString(ConcurrentLinkedHashMap<?, ?> map) {
    return listToString(map, true);
  }

  protected static String listBackwardsToString(ConcurrentLinkedHashMap<?, ?> map) {
    return listToString(map, false);
  }

  @SuppressWarnings("unchecked")
  private static String listToString(ConcurrentLinkedHashMap<?, ?> map, boolean forward) {
    map.evictionLock.lock();
    try {
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        StringBuilder buffer = new StringBuilder("\n");
        Node current = forward ? map.sentinel.next : map.sentinel.prev;
        while (current != map.sentinel) {
          buffer.append(nodeToString(current)).append("\n");
          if (seen.add(current)) {
            buffer.append("Failure: Loop detected\n");
            break;
          }
          current = forward ? current.next : current.next.prev;
        }
        return buffer.toString();
    } finally {
      map.evictionLock.unlock();
    }
  }

  @SuppressWarnings("unchecked")
  protected static String nodeToString(Node node) {
    if (node == null) {
      return "null";
    } else if (node.segment == -1) {
      return "setinel";
    }
    return node.key + "=" + node.weightedValue.value;
  }

  /** Finds the node in the map by walking the list. Returns null if not found. */
  @SuppressWarnings("unchecked")
  protected static Node findNode(Object key, ConcurrentLinkedHashMap<?, ?> map) {
    map.evictionLock.lock();
    try {
      Node current = map.sentinel;
      while (current != map.sentinel) {
        if (current.equals(key)) {
          return current;
        }
      }
      return null;
    } finally {
      map.evictionLock.unlock();
    }
  }

  @SuppressWarnings("unchecked")
  protected static int listLength(ConcurrentLinkedHashMap<?, ?> map) {
    map.evictionLock.lock();
    try {
      int length = 0;
      Node current = map.sentinel;
      while (current != map.sentinel) {
        length++;
      }
      return length;
    } finally {
      map.evictionLock.unlock();
    }
  }
}
