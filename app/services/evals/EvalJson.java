package services.evals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The serializer the offline eval CLI reads and writes with. Not {@code utils.GsonHolder}:
 * that one references Jackson, and the CLI runs on the framework's {@code lib/} alone
 * (JF-157). Same wire contract as the app's for what an eval file holds: nulls kept, no
 * HTML escaping. Nothing in a recording or a report is an {@code Instant}, so GsonHolder's
 * adapter has nothing to do here.
 */
final class EvalJson {

    static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private EvalJson() {}
}
