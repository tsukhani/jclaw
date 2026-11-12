package services;

import models.BindingCredential;
import models.ChannelConfig;
import models.JsonCredential;
import models.McpServer;
import models.SkillSecret;
import play.db.jpa.JPA;
import utils.CredentialCipher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stored credentials that do not decrypt under this installation's secret (JCLAW-1455), each named with the page
 * where it is entered again. Every reader already treats one as unset; this is what Settings shows so the operator
 * knows which to re-enter, or that the right {@code certs/.env} would bring them all back.
 */
public final class UnreadableCredentials {

    /**
     * @param store where it is kept: {@code config}, a channel ({@code slack}, {@code telegram}, {@code whatsapp}),
     *              {@code skill}, or {@code mcp}
     * @param owner the config key, the bound or skill's agent's name, the MCP server's name, or the channel type
     * @param field which of the owner's credentials it is; a skill secret's is its skill and name
     * @param page  the route where the operator enters it again
     */
    public record Item(String store, String owner, String field, String page) {}

    private UnreadableCredentials() {}

    @SuppressWarnings("java:S2077") // table and column names come from BindingCredential, never from input
    public static List<Item> list() {
        var out = new ArrayList<Item>();
        for (var entry : ConfigService.listAll()) {
            if (entry.unreadable()) {
                out.add(new Item("config", entry.key(), entry.key(), "/settings"));
            }
        }
        var cipher = CredentialCipher.current();
        for (var credential : BindingCredential.values()) {
            var channel = credential.table.replace("_binding", "");
            @SuppressWarnings("unchecked")
            List<Object[]> rows = JPA.em().createNativeQuery("SELECT a.name, b." + credential.column + " FROM "
                    + credential.table + " b JOIN agent a ON a.id = b.agent_id ORDER BY a.name").getResultList();
            for (var row : rows) {
                if (row[1] instanceof String stored && !cipher.open(credential.field(), stored).readable()) {
                    out.add(new Item(channel, String.valueOf(row[0]), credential.column.replace('_', ' '), "/channels/" + channel));
                }
            }
        }
        @SuppressWarnings("unchecked")
        List<Object[]> secrets = JPA.em().createNativeQuery("SELECT a.name, s.skill_name, s.name, s." + SkillSecret.VALUE_COLUMN + " FROM "
                + SkillSecret.TABLE + " s JOIN agent a ON a.id = s.agent_id ORDER BY 1, 2, 3").getResultList();
        for (var row : secrets) {
            if (row[3] instanceof String stored && !cipher.open(SkillSecret.VALUE_FIELD, stored).readable()) {
                out.add(new Item("skill", String.valueOf(row[0]), row[1] + " " + row[2], "/skills"));
            }
        }
        List<McpServer> servers = McpServer.findAll();
        for (var server : servers) {
            for (var path : JsonCredential.MCP_SERVER.unreadable(server.configJson)) {
                out.add(new Item("mcp", server.name, path, "/mcp-servers"));
            }
        }
        List<ChannelConfig> channels = ChannelConfig.findAll();
        for (var channel : channels) {
            var type = channel.channelType.toLowerCase(Locale.ROOT);
            for (var path : JsonCredential.CHANNEL_CONFIG.unreadable(channel.configJson)) {
                out.add(new Item(type, type + " (app-wide)", path, "/channels/" + type));
            }
        }
        return out;
    }
}
