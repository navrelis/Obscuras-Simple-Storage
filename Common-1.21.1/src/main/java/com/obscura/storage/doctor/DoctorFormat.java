package com.obscura.storage.doctor;

import java.util.Locale;

/**
 * Number formatting of the Network Doctor output. Fixed (ROOT) locale: the output also goes to the
 * server console and into exported files, where a server locale's separators would be confusing.
 */
public final class DoctorFormat {
	private DoctorFormat() {}

	/** 1234567 -> "1,234,567". */
	public static String count(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	/** Milliseconds with two decimals: 0.4199 -> "0.42". */
	public static String ms(double ms) {
		return String.format(Locale.ROOT, "%.2f", ms);
	}

	/** A rate with one decimal: 12.56 -> "12.6", whole numbers without decimals: 14.0 -> "14". */
	public static String rate(double r) {
		double rounded = Math.round(r * 10) / 10.0;
		if (rounded == Math.rint(rounded))return count((long) rounded);
		return String.format(Locale.ROOT, "%.1f", rounded);
	}

	/** Bytes per second as kilobytes (1024) with one decimal. */
	public static String kilobytes(double bytes) {
		return String.format(Locale.ROOT, "%.1f", bytes / 1024.0);
	}

	/** {@code part} of {@code whole} as a whole percentage ("74%"), "0%" when {@code whole} is 0. */
	public static String percent(long part, long whole) {
		if (whole <= 0)return "0%";
		return Math.round(part * 100.0 / whole) + "%";
	}

	/** A share with one decimal: 0.0772 -> "7.7%". */
	public static String share(double fraction) {
		return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
	}
}
