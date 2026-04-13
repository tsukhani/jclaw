package utils;

import com.google.gson.Gson;

/**
 * Shared Gson instance for the entire application.
 * Replaces per-class {@code private static final Gson gson = new Gson()} declarations.
 */
public final class GsonHolder {

    public static final Gson INSTANCE = new Gson();

    private GsonHolder() {}
}
