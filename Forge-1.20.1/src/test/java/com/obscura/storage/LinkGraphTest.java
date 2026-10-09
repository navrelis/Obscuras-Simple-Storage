package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.LinkGraph;

/** Which networks an Inventory Link network reaches: every link end provides and accesses, loops must not repeat. */
@DisplayName("Inventory Link graph")
public class LinkGraphTest {

	private static List<String> reach(String start, Map<String, List<String>> links) {
		return LinkGraph.reachable(start, n -> links.getOrDefault(n, Collections.emptyList()));
	}

	@Test
	@DisplayName("Two networks linked both ways see each other once")
	void twoWay() {
		Map<String, List<String>> links = new HashMap<>();
		links.put("A", List.of("B"));
		links.put("B", List.of("A"));
		assertEquals(List.of("B"), reach("A", links));
		assertEquals(List.of("A"), reach("B", links));
	}

	@Test
	@DisplayName("Links are followed through other networks, a loop does not repeat anything")
	void chainAndLoop() {
		Map<String, List<String>> links = new HashMap<>();
		links.put("A", List.of("B"));
		links.put("B", List.of("A", "C"));
		links.put("C", List.of("B", "A"));
		assertEquals(List.of("B", "C"), reach("A", links));
		assertEquals(List.of("A", "C"), reach("B", links));
	}

	@Test
	@DisplayName("The start network, duplicates and missing networks are skipped")
	void selfAndDuplicates() {
		Map<String, List<String>> links = new HashMap<>();
		links.put("A", Arrays.asList("A", null, "B", "B"));
		links.put("B", null);
		assertEquals(List.of("B"), reach("A", links));
		assertEquals(List.of(), reach("X", links));
	}
}
