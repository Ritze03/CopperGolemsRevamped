package com.coppergolemsrevamped.core;

/**
 * The Transporter filter mode (see superdoc/features/transporter.md). Stored by {@link #id} in {@link GolemData};
 * an unknown or missing id reads as {@link #OFF}, so old saves and future ids are safe.
 */
public enum FilterMode {
	OFF("off", "Off"),
	WHITELIST("whitelist", "Whitelist"),
	BLACKLIST("blacklist", "Blacklist");

	public final String id;
	public final String label;

	FilterMode(String id, String label) {
		this.id = id;
		this.label = label;
	}

	/** Off -> Whitelist -> Blacklist -> Off. */
	public FilterMode next() {
		return values()[(ordinal() + 1) % values().length];
	}

	public static FilterMode parse(String id) {
		for (FilterMode m : values()) if (m.id.equals(id)) return m;
		return OFF;
	}
}
