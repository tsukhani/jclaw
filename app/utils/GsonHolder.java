package utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Shared Gson instance for the entire application.
 * Replaces per-class {@code private static final Gson gson = new Gson()} declarations.
 *
 * <p>{@code serializeNulls()} is enabled so records produce the same wire
 * shape as the {@code Map.of(...)} / {@code HashMap.put(k, null)} idioms
 * that record-typed DTOs are progressively replacing — Gson default
 * serializes null map values but omits null object fields, and the
 * frontend has historically seen the former. Keeping the keys present
 * with explicit nulls maintains wire-format invariance across the
 * JCLAW-278 DTO migration without per-controller divergence.
 */
public final class GsonHolder {

    public static final Gson INSTANCE = new GsonBuilder().serializeNulls().create();

    private GsonHolder() {}
}
