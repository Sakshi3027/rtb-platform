package com.sakshichavan.rtb.common.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps keys (e.g. user ids) to nodes (e.g. bidder instances) so the same key always reaches the same node,
 * and adding a node moves only about 1/(N+1) of keys, all of them onto the new node.
 * Each node is placed at many points ("virtual nodes") so load spreads evenly.
 * Immutable and thread-safe: lookups are a binary search over sorted arrays.
 */
public final class ConsistentHashRing {

    private final Set<String> nodes;
    private final int virtualNodesPerNode;
    private final long[] points;
    private final String[] owners;

    public ConsistentHashRing(Collection<String> nodes, int virtualNodesPerNode) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("ring needs at least one node");
        }
        if (virtualNodesPerNode <= 0) {
            throw new IllegalArgumentException("virtual nodes per node must be positive: " + virtualNodesPerNode);
        }
        this.nodes = Set.copyOf(new LinkedHashSet<>(nodes));
        this.virtualNodesPerNode = virtualNodesPerNode;

        List<Point> placed = new ArrayList<>(this.nodes.size() * virtualNodesPerNode);
        for (String node : this.nodes) {
            for (int i = 0; i < virtualNodesPerNode; i++) {
                placed.add(new Point(hash(node + "#" + i), node));
            }
        }
        placed.sort(Comparator.comparingLong(Point::hash).thenComparing(Point::node));

        this.points = new long[placed.size()];
        this.owners = new String[placed.size()];
        for (int i = 0; i < placed.size(); i++) {
            points[i] = placed.get(i).hash();
            owners[i] = placed.get(i).node();
        }
    }

    /** The node that owns this key: the first ring point clockwise from the key's hash. */
    public String nodeFor(String key) {
        int index = Arrays.binarySearch(points, hash(key));
        if (index < 0) {
            index = -index - 1;
        }
        if (index == points.length) {
            index = 0;
        }
        return owners[index];
    }

    /** A new ring with one more node. This ring is unchanged. */
    public ConsistentHashRing withNode(String node) {
        List<String> grown = new ArrayList<>(nodes);
        grown.add(node);
        return new ConsistentHashRing(grown, virtualNodesPerNode);
    }

    public Set<String> nodes() {
        return nodes;
    }

    /** 64-bit FNV-1a over the key's characters, then a MurmurHash3 finalizer to spread similar keys apart. */
    static long hash(String key) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x100000001b3L;
        }
        return mix(h);
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    private record Point(long hash, String node) {
    }
}
