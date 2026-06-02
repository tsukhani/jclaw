package jobs;

import channels.TelegramChannel;
import models.TelegramBinding;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.Tx;
import slash.Commands;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-99: register JClaw's slash-command set with every enabled
 * {@link TelegramBinding} at application startup so Telegram clients
 * show the native autocomplete dropdown when users type {@code /}.
 *
 * <p>Runs once per JVM boot. Idempotent at the Telegram API layer —
 * {@code setMyCommands} overwrites the existing list for each bot
 * without error, so there's no need to check current state first.
 *
 * <p>Per-binding failures are swallowed and logged; one revoked token
 * or network blip must not prevent other bots from getting their
 * autocomplete set up. Mirrors the defensive posture of
 * {@link TelegramStreamingRecoveryJob} (JCLAW-95).
 *
 * <p>JCLAW-360: bindings created or re-enabled via the admin UI mid-session
 * are covered too — {@link controllers.ApiTelegramBindingsController} calls
 * {@link #registerOne(TelegramBinding)} on its save path, so a new/re-enabled
 * binding gets its command menu immediately rather than at the next restart.
 */
// DB read (TelegramBinding::findAllEnabled) is wrapped in Tx.run; the rest
// is HTTP to api.telegram.org. @NoTransaction skips the redundant outer
// JPA wrapper so the cleanup-time EntityManager.close() doesn't race the
// shutdown hook on restart.
@OnApplicationStart
@NoTransaction
public class TelegramCommandsRegistrationJob extends Job<Void> {

    @Override
    public void doJob() {
        registerAll();
    }

    /** Visible for tests so the registration pass can run without a JVM restart. */
    public static void registerAll() {
        var bindings = Tx.run(TelegramBinding::findAllEnabled);
        if (bindings.isEmpty()) return;

        var botCommands = toBotCommands();
        for (var b : bindings) {
            register(b, botCommands);
        }
    }

    /**
     * JCLAW-360: register the slash-command set for a single binding, so a
     * binding created or re-enabled via the admin UI gets native command
     * autocomplete immediately rather than waiting for the next JVM restart.
     *
     * <p>No-op when the binding is null or disabled — a disabled binding has
     * no live channel, matching {@link #registerAll}'s {@code findAllEnabled}
     * scope. Per-binding failures are swallowed and logged (see
     * {@link #register}).
     */
    public static void registerOne(TelegramBinding b) {
        if (b == null || !b.enabled) return;
        register(b, toBotCommands());
    }

    /** Register {@code botCommands} for one binding; swallow + log failures. */
    private static void register(TelegramBinding b, List<BotCommand> botCommands) {
        try {
            TelegramChannel.setMyCommands(b.botToken, botCommands);
            EventLogger.info("channel", b.agent != null ? b.agent.name : null, "telegram",
                    "Registered %d slash command(s) for binding %d"
                            .formatted(botCommands.size(), b.id));
        } catch (Exception e) {
            EventLogger.warn("channel", b.agent != null ? b.agent.name : null, "telegram",
                    "Slash-command registration failed for binding %d: %s"
                            .formatted(b.id, e.getMessage()));
        }
    }

    /**
     * Map the {@link Commands.Command} enum into Telegram's BotCommand
     * shape. Single source of truth — adding a new enum entry
     * automatically flows through here on the next boot.
     *
     * <p>Package access plus {@code public} exposure so the default-package
     * test file can assert the mapping without reflection. Not intended
     * as a public API — callers should invoke {@link #registerAll}.
     */
    public static List<BotCommand> toBotCommands() {
        // Plain for-loop rather than stream().map(...).toList() — the
        // telegrambots BotCommand uses a Lombok @SuperBuilder whose build()
        // returns a wildcard type that .toList() infers as List<?>, which
        // won't assign to List<BotCommand>.
        var out = new ArrayList<BotCommand>(Commands.Command.values().length);
        for (var c : Commands.Command.values()) {
            out.add(BotCommand.builder()
                    .command(c.bareName())
                    .description(c.shortDescription)
                    .build());
        }
        return out;
    }
}
