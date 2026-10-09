package com.obscura.storage.util;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The terminal search box syntax, same as in 1.21.1.
 *
 * <p>The query is split at {@code |} into alternatives (OR). Each alternative is split at spaces into terms, all of
 * which have to match (AND). A term is
 * <ul>
 * <li>{@code @text}: the item's mod id (registry namespace) contains the text,</li>
 * <li>{@code #text}: one of the item's tags contains the text,</li>
 * <li>{@code $regex}: the item's extra data (NBT here, data components on 1.21) matches the pattern, items without
 * data never match,</li>
 * <li>anything else: a regular expression that has to match the item name or one of its tooltip lines.</li>
 * </ul>
 * Matching is case insensitive. Patterns that are no valid regular expression are matched as plain text. An empty
 * query matches everything. The information about an item is only read when a term needs it, and an item whose
 * information cannot be read does not match that term.
 */
public final class TerminalSearch {
	private TerminalSearch() {
	}

	/** What the search can look at on an item. Lookups are allowed to throw, the item then fails the term. */
	public interface Target {
		/** Display name in lower case. */
		String displayNameLower();

		/** Namespace of the registry id. */
		String modId();

		List<String> tooltipLines();

		/** Tag ids like {@code minecraft:logs}. */
		List<String> tags();

		boolean hasData();

		/** The extra data as text, only asked if {@link #hasData()}. */
		String data();
	}

	public static Predicate<Target> compile(String query) {
		Predicate<Target> result = null;
		for (String alternative : query.split("\\|")) {
			String part = alternative.trim();
			if (part.isEmpty())continue;
			Predicate<Target> all = t -> true;
			for (String raw : part.split(" ")) {
				if (raw.isEmpty())continue;
				Predicate<Target> term = term(raw.toLowerCase(Locale.ROOT));
				if (term != null)all = all.and(term);
			}
			result = result == null ? all : result.or(all);
		}
		return result != null ? result : t -> true;
	}

	/** Shortcut for one-off checks. */
	public static boolean matches(String query, Target target) {
		return compile(query).test(target);
	}

	private static Predicate<Target> term(String s) {
		if (s.startsWith("@")) {
			String fs = s.substring(1);
			return t -> {
				try {
					return t.modId().contains(fs);
				} catch (Exception e) {
					return false;
				}
			};
		} else if (s.startsWith("#")) {
			String fs = s.substring(1);
			return t -> {
				try {
					return t.tags().stream().anyMatch(st -> st.contains(fs));
				} catch (Exception e) {
					return false;
				}
			};
		} else if (s.startsWith("$")) {
			Pattern m = buildPattern(s.substring(1));
			if (m == null)return null;
			return t -> {
				try {
					return t.hasData() && m.matcher(t.data()).find();
				} catch (Exception e) {
					return false;
				}
			};
		}
		Pattern m = buildPattern(s);
		if (m == null)return null;
		return t -> {
			try {
				if (m.matcher(t.displayNameLower()).find())return true;
				for (String line : t.tooltipLines()) {
					if (m.matcher(line).find())return true;
				}
			} catch (Exception e) {
			}
			return false;
		};
	}

	/** The pattern for {@code s}, the text itself if it is no valid regular expression, null if neither works. */
	public static Pattern buildPattern(String s) {
		try {
			return Pattern.compile(s, Pattern.CASE_INSENSITIVE);
		} catch (Throwable ignore) {
			try {
				return Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE);
			} catch (Throwable ignored) {
				return null;
			}
		}
	}
}
