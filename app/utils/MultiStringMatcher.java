package utils;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Finds many fixed strings in one pass over a text (Aho-Corasick), and replaces each leftmost, longest match. Built
 * once and shared: the cost of a pass is the text's length, whatever the number of strings, which a loop of
 * {@link String#replace} or a regex alternation of every string is not.
 *
 * <p>Only the root and the states one character deep carry a full row of transitions: a scan spends nearly all its
 * time there, and a full row for every state would cost the states times the alphabet, megabytes for a cookie jar's
 * tokens. Deeper states keep their few children in sorted lists and fall back along failure links, so the memory
 * stays proportional to the strings' total length. A character no string holds sends the scan to the root at once.
 *
 * @param <L> the label each string carries, handed to the replacement
 */
public final class MultiStringMatcher<L> {

    private static final int DENSE_DEPTH = 1;

    /** Each ASCII character's class: 0 for one no string holds, else 1 to {@code width - 1}. */
    private final int[] asciiClass = new int[128];
    /** The other characters the strings hold, sorted, and each one's class. */
    private final char[] wideChars;
    private final int[] wideClass;
    private final int width;
    /** Where each state's full transition row starts in {@link #dense}, up to {@link #DENSE_DEPTH} deep; -1 for none. */
    private final int[] denseRow;
    private final int[] dense;
    /** Every state's children, by class: {@code childClass[childStart[s] .. childStart[s + 1])}, sorted. */
    private final int[] childStart;
    private final int[] childClass;
    private final int[] childTarget;
    private final int[] fail;
    /** The length of the longest string that ends at each state, 0 when none does, and that string's label. */
    private final int[] longest;
    private final Object[] label;
    private final boolean empty;

    /** A matcher for each key of {@code strings}; an empty key is ignored. */
    public MultiStringMatcher(Map<String, L> strings) {
        var wide = new TreeSet<Character>();
        int asciiClasses = classifyAscii(strings.keySet(), wide);
        wideChars = new char[wide.size()];
        wideClass = new int[wide.size()];
        int w = 0;
        for (char c : wide) {
            wideChars[w] = c;
            wideClass[w] = asciiClasses + w + 1;
            w++;
        }
        width = asciiClasses + wide.size() + 1;

        var trie = new LinkedTrie(strings.keySet().stream().mapToInt(String::length).sum() + 1);
        for (var entry : strings.entrySet()) insert(trie, entry.getKey(), entry.getValue());
        int states = trie.states;
        empty = states == 1;

        childStart = new int[states + 1];
        childClass = new int[states - 1];
        childTarget = new int[states - 1];
        flattenChildren(trie);

        fail = new int[states];
        longest = Arrays.copyOf(trie.longestAt, states);
        label = Arrays.copyOf(trie.labelAt, states);
        denseRow = new int[states];
        dense = new int[width * placeDenseRows(trie.depth, states)];
        linkFailuresAndFillRows();
    }

    /**
     * Gives each ASCII character the strings hold a class in the order first met, from 1, and gathers the others into
     * {@code wide}. Returns the number of ASCII classes, so the wide ones take the classes after them.
     */
    private int classifyAscii(Iterable<String> strings, TreeSet<Character> wide) {
        int classes = 0;
        for (var s : strings) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= 128) wide.add(c);
                else if (asciiClass[c] == 0) asciiClass[c] = ++classes;
            }
        }
        return classes;
    }

    /** The trie as linked children while it is built: first child, next sibling, and the class on the edge into each. */
    private static final class LinkedTrie {
        final int[] firstChild;
        final int[] sibling;
        final int[] edge;
        final int[] depth;
        final int[] longestAt;
        final Object[] labelAt;
        int states = 1;

        LinkedTrie(int capacity) {
            firstChild = new int[capacity];
            sibling = new int[capacity];
            edge = new int[capacity];
            depth = new int[capacity];
            longestAt = new int[capacity];
            labelAt = new Object[capacity];
            Arrays.fill(firstChild, -1);
        }

        int childOrAdd(int state, int c) {
            int child = firstChild[state];
            while (child >= 0 && edge[child] != c) child = sibling[child];
            if (child >= 0) return child;
            child = states++;
            edge[child] = c;
            depth[child] = depth[state] + 1;
            sibling[child] = firstChild[state];
            firstChild[state] = child;
            return child;
        }

        void endAt(int state, int length, Object label) {
            if (length > longestAt[state]) {
                longestAt[state] = length;
                labelAt[state] = label;
            }
        }
    }

    private void insert(LinkedTrie trie, String s, Object label) {
        if (s.isEmpty()) return;
        int state = 0;
        for (int i = 0; i < s.length(); i++) state = trie.childOrAdd(state, classOf(s.charAt(i)));
        trie.endAt(state, s.length(), label);
    }

    private void flattenChildren(LinkedTrie trie) {
        int at = 0;
        for (int s = 0; s < trie.states; s++) {
            childStart[s] = at;
            int from = at;
            for (int child = trie.firstChild[s]; child >= 0; child = trie.sibling[child]) {
                childClass[at] = trie.edge[child];
                childTarget[at++] = child;
            }
            sortChildren(from, at);
        }
        childStart[trie.states] = at;
    }

    /** Gives every state up to {@link #DENSE_DEPTH} deep a full row and the rest none; returns the number of rows. */
    private int placeDenseRows(int[] depth, int states) {
        Arrays.fill(denseRow, -1);
        int rows = 0;
        for (int s = 0; s < states; s++) if (depth[s] <= DENSE_DEPTH) denseRow[s] = width * rows++;
        return rows;
    }

    /** Breadth first, so a state's failure link, and the row it inherits, are done before the state. */
    private void linkFailuresAndFillRows() {
        var queue = new ArrayDeque<Integer>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int state = queue.poll();
            for (int k = childStart[state]; k < childStart[state + 1]; k++) {
                linkFailure(state, childTarget[k], childClass[k]);
                queue.add(childTarget[k]);
            }
            if (denseRow[state] >= 0) fillDenseRow(state);
        }
    }

    private void linkFailure(int parent, int child, int c) {
        if (parent != 0) fail[child] = step(fail[parent], c);
        // A state's longest match is its own string or the longest one its suffix carries.
        int inherited = longest[fail[child]];
        if (inherited > longest[child]) {
            longest[child] = inherited;
            label[child] = label[fail[child]];
        }
    }

    private void fillDenseRow(int state) {
        int base = denseRow[state];
        for (int c = 1; c < width; c++) {
            int child = child(state, c);
            if (child >= 0) dense[base + c] = child;
            else dense[base + c] = state == 0 ? 0 : step(fail[state], c);
        }
    }

    private void sortChildren(int from, int to) {
        for (int i = from + 1; i < to; i++) {
            int c = childClass[i];
            int t = childTarget[i];
            int j = i - 1;
            while (j >= from && childClass[j] > c) {
                childClass[j + 1] = childClass[j];
                childTarget[j + 1] = childTarget[j];
                j--;
            }
            childClass[j + 1] = c;
            childTarget[j + 1] = t;
        }
    }

    private int classOf(char c) {
        if (c < 128) return asciiClass[c];
        if (wideChars.length == 0) return 0;
        int i = Arrays.binarySearch(wideChars, c);
        return i >= 0 ? wideClass[i] : 0;
    }

    private int child(int state, int c) {
        for (int k = childStart[state], end = childStart[state + 1]; k < end; k++) {
            int found = childClass[k];
            if (found == c) return childTarget[k];
            if (found > c) break;
        }
        return -1;
    }

    /** The state after {@code state} reads class {@code c}, following failure links. */
    private int step(int state, int c) {
        while (true) {
            int row = denseRow[state];
            if (row >= 0) return dense[row + c];
            int child = child(state, c);
            if (child >= 0) return child;
            state = fail[state];
        }
    }

    /**
     * {@code text} with each leftmost, longest occurrence of a string replaced by {@code replacement} of its label.
     * Occurrences do not overlap: of two that do, the one that starts first wins, and of two that start together, the
     * longer one.
     */
    @SuppressWarnings("unchecked")
    public String replaceAll(String text, Function<L, String> replacement) {
        if (empty || text.isEmpty()) return text;
        // Per end position, the longest match ending there; a shorter match ending at the same place lies inside it.
        var found = new long[0];
        var stateAt = new int[0];
        int count = 0;
        int state = 0;
        for (int i = 0, n = text.length(); i < n; i++) {
            char ch = text.charAt(i);
            int c = ch < 128 ? asciiClass[ch] : classOf(ch);
            if (c == 0) {
                state = 0;
                continue;
            }
            int row = denseRow[state];
            state = row >= 0 ? dense[row + c] : step(state, c);
            int length = longest[state];
            if (length > 0) {
                if (count == found.length) {
                    found = Arrays.copyOf(found, Math.max(8, count * 2));
                    stateAt = Arrays.copyOf(stateAt, found.length);
                }
                // Sorts by start, then longest first; an end is unique and ascending in scan order, which finds the state.
                found[count] = ((long) (i - length + 1) << 32) | (Integer.MAX_VALUE - (i + 1));
                stateAt[count++] = state;
            }
        }
        if (count == 0) return text;
        var ends = new int[count];
        for (int k = 0; k < count; k++) ends[k] = Integer.MAX_VALUE - (int) found[k];
        Arrays.sort(found, 0, count);
        var out = new StringBuilder(text.length());
        int at = 0;
        for (int k = 0; k < count; k++) {
            int start = (int) (found[k] >>> 32);
            int end = Integer.MAX_VALUE - (int) found[k];
            if (start < at) continue;
            int s = stateAt[Arrays.binarySearch(ends, end)];
            out.append(text, at, start).append(replacement.apply((L) label[s]));
            at = end;
        }
        return out.append(text, at, text.length()).toString();
    }
}
